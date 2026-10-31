# ADR-0019: C2M 工具结果中的媒体移到同一 user 轮次，不再留在 tool_result 内

- **状态**：Accepted
- **日期**：2026-10-31
- **取代**：部分取代 [C2M 请求契约](../reference/contracts/C2M-REQUEST-CONTRACT.md) §3.4.1 中
  「含图片的工具结果保留为 `tool_result` 内块数组」这一条（该条不是 ADR，记录在契约与
  `MessageTranslator.translateToolResult` 的 Javadoc 中）；其余 §3.4 / §3.5 仍有效
- **相关**：[ADR-0003 忠实映射](./adr-0003-translator-faithful-mapping.md)、
  [ADR-0007 规则优先于供应商类](./adr-0007-request-rules-over-provider-classes.md)、
  [ADR-0012 M2C 请求翻译](./adr-0012-m2c-request-translation.md)（反方向的媒体延后输出）、
  [C2M 请求契约](../reference/contracts/C2M-REQUEST-CONTRACT.md) §3.4、§3.5

## 背景

C2M（下游 OpenAI Chat、上游 Anthropic Messages）把 `role: "tool"` 消息翻译成 user 消息里的
`tool_result` 块。当工具结果含图片时，当前实现把图片作为块数组**留在 `tool_result.content` 内**。
依据是 Anthropic 官方 SDK 的 `ToolResultBlockParam.content` 允许 `ImageBlockParam`，
且 2026-09-08 用 mimo 实测上游能识别（契约 §3.4.2）。

2026-10-31 实测发现，部分 Anthropic Messages 上游（中转站或兼容实现）直接拒绝这种形态：

> A tool_result may contain only text blocks: an image cannot travel inside one.
> Send the picture in a user message instead.

契约 §3.4.1 原先认为「若遇到严格上游，用仅适用 MESSAGES 的请求体规则表达」。这一判断不成立：
请求体规则只有 `edit_object` / `set_value` / `delete` / `regex_replace`，能删图片但
**不能把块从 `tool_result` 内移到外层 user 内容**。删图片等于丢掉模型需要看的工具输出。

参考项目里，没有任何一个对 Anthropic 上游把图片移出 `tool_result`（new-api 透传、
CLIProxyAPI 保留、cc-switch 按配置替换为占位文本、sub2api 在另一条链路中丢弃）。
「移到 user 消息」的先例只出现在 Chat / Responses 目标上，包括 COSP 自己的 M2C
（`AnthropicToolResultExpander`：`[media moved to next user message]` + 来源标记）。

另一方面，user 消息内的 image 块是 Anthropic Messages 最基础的形态，所有上游都支持。
把图片移到 user 内容中，对宽松上游也是合法请求。

## 决策

**C2M 去程无条件把工具结果中的非文本块移出 `tool_result`，放进同一条 user 消息、所有
`tool_result` 块之后，并带来源标记。仅改 C2M 内部行为，不加供应商开关，Messages 直连不动。**

要点：

1. **作用范围**：只作用于 C2M 请求翻译。Messages 直连（下游本就是 Anthropic）原样透传，
   不改写客户端自己构造的 `tool_result`。
2. **无条件，无开关**：所有上游都支持移动后的形态，因此不增加 `provider_config` 字段，
   也不按供应商名或错误文本判断。
3. **移动后的形态**（一个 user 轮次内的块顺序）：
   - 先放该轮所有 `tool_result` 块，按 assistant `tool_use` 顺序排（沿用现有配对逻辑）；
   - 每个含媒体的 `tool_result` 只保留文本部分，并在末尾追加提示
     `[media moved after tool results]`；没有文本时内容就是这条提示；
   - 所有 `tool_result` 之后，按同一顺序为每个含媒体的结果输出一个来源标记文本块
     `[media from tool_use_id="<JSON 转义的 id>"]`，紧接它的媒体块；
   - 该轮原本就有的普通 user 内容排在这些媒体之后（与 merge 的现有结果一致）。
4. **提示措辞与 M2C 不同是刻意的**：M2C 的媒体进入**下一条** user 消息，所以写
   「next user message」；C2M 的媒体在**同一条** user 消息的 `tool_result` 之后，
   沿用那句话会描述错位置。来源标记格式与 M2C 一致（JSON 引号转义 id，防止 id 中的
   换行或括号被当成文本结构）。
5. **纯文本结果字节不变**：只含文本的工具结果仍按现有规则合并为字符串，不加任何提示。
6. **把 `tool_result` 放在最前**：Anthropic 对工具结果的位置有严格要求（user 内容中
   `tool_result` 应位于其它块之前）。该规则来自社区实现与报错文本，官方文档本次未能访问核验，
   但把媒体放在后面对两种解读都安全。

### 与 ADR-0003 的关系

ADR-0003 约束的是**字段值**：不补默认值、不修正范围、不凭空增删字段。本决策不改变任何值，
也不丢弃内容，只是把同一份图片换到上游能接受的位置，属于「把下游语义表达成目标协议的合法结构」，
与 M2C 的媒体延后输出同类。

## 后果

**正面**

- 严格拒绝 `tool_result` 内图片的上游可以正常使用 C2M，图片不丢。
- 移动后的形态对所有 Anthropic 上游都合法，不需要用户识别上游类型或配置规则。
- 与 M2C 方向的处理思路对称，来源标记格式统一。

**负面**

- 对原本能识别 `tool_result` 内图片的上游，模型看到的结构有变化：图片从「属于某个工具结果」
  变成「工具结果之后的 user 内容」，靠来源标记维持归属。同一轮多个工具都返回图片时，
  模型依赖标记区分。
- 契约 §3.4.2 的 mimo 实测针对的是旧形态，新形态需要重新实机验证。
- 发给上游的请求体与下游 Chat 的结构对应关系更间接，排查时需要知道这条移动规则。

## 备选方案

### 保持图片在 `tool_result` 内（现状）
拒绝：已实测严格上游直接 400，C2M 在这类上游上无法处理带图的工具结果。

### 用请求体规则删除图片
拒绝：规则只能删不能移，删除会丢掉模型需要的工具输出。

### 加供应商级开关，按需移动
拒绝：移动后的形态对所有上游合法，开关只增加配置负担；用户也明确倾向作为 C2M 内部默认行为。

### 收到该错误文本后自动改写重试
拒绝：违背「不按错误文本自动回退」的原则（与鉴权头、思考档位一致），且会让每次调用多一轮失败往返。

### 把图片放到下一条独立 user 消息
拒绝：Anthropic 会把相邻同角色消息合并，现有配对逻辑也会再做一次 merge，结果仍是同一 user 轮次；
直接在同一消息内按确定顺序输出更可预测。

## 参考

- [C2M 请求契约](../reference/contracts/C2M-REQUEST-CONTRACT.md) §3.4.1、§3.4.2、§3.5
- `pipeline/protocol/translate/MessageTranslator.java`（`translateToolResult` / `translateToolResultContent`）
- `pipeline/protocol/translate/ToolPairingNormalizer.java`（merge → pair → merge）
- `pipeline/protocol/translate/AnthropicToolResultExpander.java`（M2C 媒体延后输出与来源标记）
- Anthropic Python SDK `ToolResultBlockParam`（`content: Union[str, Iterable[Content]]`，Content 含 `ImageBlockParam`）
- cc-switch（`[cc-switch: tool result media moved to the following user message]`）、
  sub2api（`[Tool output media for call %s]`）、CLIProxyAPI（"Images returned by the preceding tool call(s):"）——
  均为 Chat / Responses 目标上的先例

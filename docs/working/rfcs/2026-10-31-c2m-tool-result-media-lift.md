# RFC：C2M 工具结果媒体移出 tool_result

- **日期**：2026-10-31（立项日）
- **状态**：已定稿且实施完毕（自动化与两家供应商实机均通过）
- **相关 ADR**：[ADR-0019：C2M 工具结果中的媒体移到同一 user 轮次](../../adr/adr-0019-c2m-tool-result-media-to-user.md)
- **分支**：（实施时由用户决定）

## 背景

> **需求复述**：部分 Anthropic Messages 上游拒绝 `tool_result` 内的图片，报错
> `A tool_result may contain only text blocks: an image cannot travel inside one. Send the picture in a user message instead.`
> 请求来自 Copilot → C2M（下游 Chat、上游 Messages）。用户要求：**只改 C2M 内部行为，Messages 直连暂不动**；
> 改写后的形态必须是 Messages 标准格式，不能让原本可用的供应商失效。

已确认的事实：

- 请求体规则只能删除，不能移动块，无法表达「把图片移到 user 内容」（见 ADR-0019 背景）。
- 官方 SDK `ToolResultBlockParam.content` 允许图片，user 消息内 image 块是最基础的形态，
  因此移动后的请求对宽松上游同样合法。
- 四个参考项目对 Anthropic 上游都不移动图片；「移到 user」的先例只在 Chat / Responses 目标上。

## 1. 范围

**做**：C2M 请求翻译的最终消息里，含非文本块的 `tool_result` 拆成「纯文本 tool_result + 同一 user 轮次后部的媒体」。

**明确不做**：

- Messages 直连路径（下游 Anthropic）不改：客户端自己构造的 `tool_result` 原样透传。
- M2C、C2R 等其它方向不改。
- 不加供应商开关、不加请求体规则操作、不按错误文本重试。
- 不改 merge → pair → merge 配对算法本身。
- 回程（C2M 响应）不受影响。

## 2. 形态规格

以一个 user 轮次（配对归一化之后的一条 user 消息）为单位：

| 位置 | 内容 |
|---|---|
| 前部 | 该轮全部 `tool_result` 块，顺序不变（已由配对逻辑按 `tool_use` 顺序排好） |
| 每个含媒体的 `tool_result` | content 改为字符串：原文本块按 `\n` 拼接，再追加 `\n[media moved after tool results]`；无文本时只有这条提示 |
| 中部 | 对每个含媒体的结果，按 `tool_result` 顺序输出：来源标记文本块 `[media from tool_use_id=<JSON 字符串>]`，紧接它的非文本块（保持原顺序） |
| 后部 | 该轮原有的其它块（普通正文等），顺序不变 |

- 只含文本的 `tool_result`、字符串 content 的 `tool_result`：**不改动，字节不变**。
- 没有任何含媒体 `tool_result` 的 user 消息：原样返回同一对象。
- 「非文本块」= `type` 不是 `text` 的块。当前 C2M 白名单下只有 `image`。
- `tool_result` 的其它键（`tool_use_id` 等）保留；只替换 `content`。
- id 用 JSON 序列化转义，与 M2C 一致，防止 id 中的换行、括号被读成文本结构。

## 3. 实施计划

| 步骤 | 文件 | 内容 |
|---|---|---|
| 1 | `pipeline/protocol/translate/ToolResultMediaLifter.java`（新增） | 包内 `final` 类，`List<Object> lift(List<Object> messages)`；无状态，可复用 |
| 2 | `ChatToMessagesRequestTranslator.java` | 在 `toolPairingNormalizer.normalize(...)` 之后调用 lifter；构造器内实例化 |
| 3 | `MessageTranslator.java` | 更新 `translateToolResult` Javadoc：块数组形态只是中间态，最终出站由 lifter 改写；删除「遇到严格上游用规则表达」的过时说法 |
| 4 | `ChatToMessagesRequestTranslatorTests.java` | 改写断言图片在 tool_result 内的 4 个用例；新增多工具、混合正文、纯文本不变、id 转义等用例 |
| 5 | `ToolResultMediaLifterTests.java`（新增） | lifter 单元测试：边界与不变量 |
| 6 | 文档 | C2M 请求契约 §3.4.1 / §3.5 更新；本 RFC 追加实施记录；索引同步 |

不改动：`ToolPairingNormalizer`、请求体规则引擎、M2C 全部代码、数据库、前端。

## 4. 风险与对策

| 风险 | 对策 |
|---|---|
| 宽松上游上模型对图片归属的理解变弱 | 来源标记带 tool_use_id；多工具顺序与 tool_result 顺序一致 |
| 上游要求 tool_result 必须在最前 | 规格固定 tool_result 在前，媒体与正文在后 |
| 旧实测（契约 §3.4.2，mimo）针对旧形态 | 实施后由用户重新实机验证；契约中保留旧实测为历史 |
| lifter 误改纯文本结果 | 单测钉住「无媒体时返回同一对象 / 字节不变」 |

## 5. 实施记录

### 2026-10-31：实现完成，自动化通过

与计划一致，无偏差。

- 新增 `ToolResultMediaLifter`：包内无状态类，在 `ChatToMessagesRequestTranslator.translateMessages`
  中接在 `toolPairingNormalizer.normalize(...)` 之后。无媒体的消息与纯文本结果返回同一对象；
  不修改输入；保留 `tool_result` 的其它键（`is_error`、`cache_control` 等）。
- `MessageTranslator.translateToolResult` Javadoc 改为说明块数组是中间态，删除「严格上游用规则表达」的过时说法。
- `ToolPairingNormalizer`、请求体规则引擎、M2C、Messages 直连、回程均未改动。

**测试**

| 测试 | 覆盖 |
|---|---|
| `ChatToMessagesRequestTranslatorTests$ToolResultContent`（8 例，改写 4 例、新增 1 例） | 纯文本/字符串不变；data URL 与 http 图片移出并保留源；图文混合；配对搬动后媒体跟随结果、正文最后；三个并行调用（含纯文本结果）的顺序与标记；全部 tool_result 只含字符串 |
| `ToolResultMediaLifterTests`（5 例，新增） | 无媒体返回同一对象、assistant 不处理；多段文本与多张图、其它键保留；id JSON 转义；结果→媒体→其它块的顺序；不修改输入 |

**验证结果**（重建 `target/test-classes` 后执行，跳过前端，默认排除 schema-migration）：

- 聚焦 6 类 **99 通过，0 失败/错误**。
- 默认 Java 全量 **1998 通过，0 失败/错误，BUILD SUCCESS**（2026-10-31 11:58:09 +08:00）。
- 过程中遇到一次 AssertJ `List<?>` + 变参 `containsExactly` 的 capture 泛型编译错误，改用显式类型的局部变量修复。

**文档**：C2M 请求契约 §3.4.1 增加新形态表并订正旧判断，§3.4.2 标为历史实测，§3.5 增加「移动必须在配对之后」；索引同步。

**边界**：未启动服务、未访问 live endpoint、未操作 admin.db，未提交或推送。

### 2026-10-31：两家供应商实机验证通过

用户实测，两个场景均走工具识图（如 `view_image`）：

| 场景 | 供应商 | 结果 |
|---|---|---|
| 修改前报错（拒绝 `tool_result` 内图片） | 供应商 A | 工具识图正常，请求不再被 400 拒绝 |
| 修改前可用的供应商 | 供应商 B（原可识别 `tool_result` 内图片，如 mimo） | 工具识图仍然正常，原可用性未回退 |

**结论**：ADR-0019 的移动形态对严格上游与宽松上游均实测通过。契约 §3.4.2 的历史实测记录
保留；新形态实测补充到 [C2M 请求契约 §3.4.1](../../reference/contracts/C2M-REQUEST-CONTRACT.md)。

## 6. 验收清单

- [x] 含图 tool_result 不再包含 image 块，图片位于同一 user 消息的 tool_result 之后
- [x] 来源标记与提示文案符合 §2
- [x] 纯文本 tool_result 出站不变
- [x] 配对搬动（中间插正文 user）后结果仍正确
- [x] 聚焦与全量 Java 回归通过
- [x] 用户实机：报错上游可用；原可用上游仍能识别图片

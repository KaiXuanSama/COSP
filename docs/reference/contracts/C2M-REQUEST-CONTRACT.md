# 协议翻译契约（请求侧）

> **状态**：C2M（下游 OpenAI → 上游 Anthropic）请求侧**已实现并经实流量验证**，见
> `ChatToMessagesRequestTranslator`；
> M2C（下游 `/v1/messages` + 上游 Chat）去程的**纯结构转换已实现并注册**，见
> `MessagesToChatRequestTranslator`；自动化转换/接线回归通过（2026-10-24），
> 并已在三家真实上游上完成去程实机存档（2026-10-24，见
> [实施记录](../../working/rfcs/2026-10-20-m2c-c2m-translation.md#m2c-live-2026-10-24)）。
> HTTP 最终序列化收包与客户端实机仍待后续阶段；C2M 回程阶段 A 已实现并注册、
> 自动化回归通过，响应规格独立见 [M2C 链路响应契约](./M2C-RESPONSE-CONTRACT.md)。
> 这不构成完整客户端兼容或可发布结论。
> 实际验证范围见 [阶段 2 记录](../../working/rfcs/2026-10-20-m2c-c2m-translation.md#m2c-stage-2)。
> **M2C 请求规格已拆分独立成文（2026-10-25）**：见 [M2C-REQUEST-CONTRACT.md](./M2C-REQUEST-CONTRACT.md)，
> 本文不再维护 `§10` 的 M2C 请求规格副本（历史存档保留）。主要取舍见
> [ADR-0012](../../adr/adr-0012-m2c-request-translation.md)、[ADR-0013](../../adr/adr-0013-m2c-output-format-alias.md)、
> [ADR-0014](../../adr/adr-0014-m2c-drop-server-fields.md)，最终调研、计划和剩余边界见
> [M2C/C2M RFC](../../working/rfcs/2026-10-20-m2c-c2m-translation.md#m2c-final-review)。
> 本文区分代码实现与实机验证，不把去程半轮当作完整翻译链路。
>
> 标注了实测日期的段落是已验证的事实，其余是约束。已实测：多轮工具链（见响应侧契约第 1 节）、
> 工具结果带图的多模态识别（3.4.2）。
>
> 相关：[AGENTS.md](../../../AGENTS.md)、[思考链回放调查](../COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md)、
> [供应商适配史](../../architecture/ADAPTATIONS.md)
>
> **C2R（下游 Chat → 上游 Responses）的请求侧调研已另立**：
> [C2R 请求翻译调研与实施记录](../../working/rfcs/2026-09-03-protocol-translation.md)；
> 当前实现为 `ChatToResponsesRequestTranslator`，不属于本文的 M2C 请求方向。
> 本文的编号与术语体系对它同样适用。

C2M = OpenAI Chat Completions → Anthropic Messages；M2C = 反向。
本文档只覆盖**请求体**。响应与 SSE 见[响应契约](./C2M-RESPONSE-CONTRACT.md)，
其中已实现的 M2C 响应是 C2M 请求的回程，不是本文的 M2C 请求。

---

## 1. 翻译层在链条中的位置

翻译是**下游请求进来后的第一层**，在设置层之前：

```text
直连（现状）
  下游 OpenAI 请求
    → 协议归一化（system 提取 / max_tokens 别名）
    → 设置层（思考深度、最大输出的四档注入模式）
    → 请求体与请求头规则
    → 以 OpenAI 协议发出

翻译（本契约）
  下游 OpenAI 请求
    → ★ 纯字段翻译 C2M（下游没说的字段留空，不推导）
    → 协议归一化
    → 设置层（兜底档在这里补上 thinking 等字段）
    → 请求体与请求头规则
    → 以 Anthropic 协议发出
```

### 1.1 为何翻译必须在设置层之前

两条路走到设置层时形态一致，四档语义因此**不需要为翻译单开一套**。翻译只负责如实搬运
下游说过的话，「下游没说的怎么办」始终由设置层的覆写 / 兜底 / 透传 / 删除回答。

推论：翻译层**不负责推导** `adaptive`、`budget_tokens` 这类下游协议无法表达的值。OpenAI 请求里
不可能出现 `thinking: {"type": "adaptive"}`，翻译后该字段就是缺失的，由兜底档决定补什么。

### 1.2 实现位置

翻译器套在上游服务**外侧**（装饰器），不进 `GenericAnthropicChatService` 内部。
`extractSystemPrompt` / `ensureMaxTokens` / 规则 / null 清洗的既有顺序完全不变，
`max_tokens` 那条线因此自动复用现成的别名归一化与两档逻辑。

**翻译必须在重试边界之外。** 空响应判定与重试预算用的是上游原生形态；若翻译发生在
`retryWhen` 内侧，`AnthropicContentDetector` 看到的就是合成出来的形状。

### 1.3 规则组按上游协议筛选

C2M 生效的是 `MESSAGES` 规则组，M2C 生效的是 `CHAT` 规则组。
规则按翻译后的 `bodyProtocol` 筛选，字段路径照最终出站形态写，而不是照下游入口协议写。

---

## 2. 不变式：翻译必须无损搬运「下游已表态」

这是本契约里最容易踩且现象最隐蔽的一条。

设置层 `fallback` 的判据是在**翻译后**的形态上算的。凡是下游表达过意图的字段，翻译层必须
映射到对应的目标形态，一个都不能丢：

| 下游 OpenAI 表态 | 翻译后必须存在 |
|---|---|
| `thinking: {"type": "disabled"}` | `thinking: {"type": "disabled"}` |
| `reasoning_effort: "low"` | 深度形态（见 4.3） |

若翻译图省事把 `reasoning_effort` 丢掉，一个明确要求 `low` 的请求跑到设置层会被判成「没表态」，
兜底档就把配置值补上去——**`fallback` 静默退化成 `override`**。只在下游带了字段时才错，
不容易被发现。

**落地要求**：这条必须有单测钉住，且要同时覆盖两个字段各自单独存在的情况。

### 2.1 不能前置的一行

思考注入支线里的这行（现位于 `AnthropicThinkingNormalizer.applyThinkingDimensions`，
阶段 4 刀 1 前它是 `GenericAnthropicChatService.prepareRequestBody` 里的一行）：

```java
body.remove("reasoning_effort");
```

曾经在设置层**之前**，会把翻译结果先杀掉。翻译层落地后它已移到设置层
**之后**：翻译器保留一份 `reasoning_effort` 供设置层判定「下游已表态」，
那份兼容副本在所有设置层逻辑结束后才被剥离。收尾清理仍需保留 ——
要删的从来不是这行本身，而是它**在设置层之前**执行这件事。

---

## 3. 字段映射表（C2M）

处置列的取值：**映射**（有对应物）、**丢弃**（静默，见第 5 节）、**报错**（拒绝该请求）。

### 3.1 顶层标量

| OpenAI | Anthropic | 处置 | 说明 |
|---|---|---|---|
| `model` | `model` | 映射 | 原样。路由已在上游完成，此处不改写 |
| `stream` | `stream` | 映射 | 原样 |
| `max_tokens` / `max_completion_tokens` | `max_tokens` | 映射 | 别名归一化由既有 `ensureMaxTokens` 完成，翻译层只搬运。缺失时**不在翻译层补默认值** |
| `stop` | `stop_sequences` | 映射 | 字符串 → 单元素数组；数组逐项要求是字符串，**非字符串元素报错**（不是 panic，见 6.1） |
| `temperature` | `temperature` | 映射 | 原样，**不缩放**（两侧值域相同，三个参考项目均未缩放） |
| `top_p` | `top_p` | 映射 | 原样 |
| `top_k` | `top_k` | 映射 | OpenAI 侧非标准字段，存在则搬运 |

**不因思考已开启而清空采样参数。** 这条与 AGENTS.md 一致。参考项目里 new-api 的
`strictSampling` 会清 `temperature` / `top_p` / `top_k`，且在「没要求思考」的分支也生效——
用户只是换了个模型名，采样参数就凭空消失。这是明确的反面案例。

### 3.2 消息结构

| OpenAI | Anthropic | 处置 | 说明 |
|---|---|---|---|
| `messages` 里 `role: system` | 顶层 `system` | 映射 | 由既有 `extractSystemPrompt` 完成，翻译层不重复做 |
| `role: developer` | 顶层 `system` | 映射 | 与 system 同等对待 |
| `role: user` / `assistant` | 同名 | 映射 | |
| 未知 role | — | **报错** | 三个参考项目都静默改成 `user`。我们选择报错：静默改 role 会改变对话语义，而这属于「下游发了非法请求」 |
| content 字符串 | content 字符串 | 映射 | **保持字符串形态，不升格成块数组**。部分上游对块数组更严格 |
| content 块数组 | 块数组 | 映射 | 逐块按 3.3 白名单转换 |
| 空 content | — | **整条丢弃** | Anthropic 拒收空块。**不塞占位字符串**（见 5.2） |

### 3.3 内容块白名单

**白名单转换，认不出的块丢弃，绝不透传。** 默认透传未知分片是这里最容易踩的坑：
Responses 或其它协议的专有分片原样发给 Anthropic，上游只会回 400 把整轮打挂。

| OpenAI 块 | Anthropic 块 | 处置 |
|---|---|---|
| `text` / `input_text` | `text` | 映射 |
| `image_url`（data URL） | `image.source: {type: "base64", media_type, data}` | 映射，解析 `data:<mt>;base64,<data>`。**Copilot 场景下的图片恒走这条**（见 3.4.2） |
| `image_url`（http/https） | `image.source: {type: "url", url}` | 映射。**不下载转 base64**——Anthropic 原生支持 URL 形态，代理侧下载是额外带宽、延迟与失败点。当前主要客户端不产生这种输入，但保留该分支的代价为零 |
| 其它 | — | 丢弃 |

### 3.4 工具

| OpenAI | Anthropic | 处置 | 说明 |
|---|---|---|---|
| `tools[].function.name` / `.description` | `tools[].name` / `.description` | 映射 | |
| `tools[].function.parameters` | `tools[].input_schema` | 映射 | 都是 JSON Schema。空 / null → `{"type":"object","properties":{}}`；`type: object` 缺 `properties` 补 `{}`；缺 `type` 补 `"object"` |
| `tools[].type` 非 `function` | — | 丢弃 | hosted tool 无 Chat 对应物 |
| `tool_choice: "auto"` | `{type: "auto"}` | 映射 | |
| `tool_choice: "required"` | `{type: "any"}` | 映射 | |
| `tool_choice: "none"` | `{type: "none"}` | 映射 | |
| `tool_choice: {type:"function", function:{name}}` | `{type: "tool", name}` | 映射 | |
| `tool_choice` 指向未声明的工具 | — | 丢弃整个 `tool_choice` | 保留会被上游拒 |
| assistant `tool_calls[]` | assistant `tool_use` 块 | 映射 | `id`→`id`、`function.name`→`name`、`function.arguments`（JSON 字符串）→ `input` 对象。**解析失败报错**，不静默留空 |
| `role: tool` + `tool_call_id` | user 消息里的 `tool_result` 块 | 映射 | 位置见 3.5，content 形态见 3.4.1 |

**工具调用 ID 原样搬运。** 不做前缀拼接。参考项目里 sub2api 对任何未知前缀的 id 一律拼
`toolu_`，可能撞长度限制或产生不合法 ID。

#### 3.4.1 tool_result 的 content 形态

`tool_result.content` 走**块翻译**（第 3.3 节的白名单），不是压平取文本。Anthropic 官方
SDK 里该字段是 `Union[str, Iterable[Content]]`，其中 `Content` 含 `ImageBlockParam` ——
**工具结果带图是协议原生支持的形态**。

出站形态由「翻译后还剩什么」决定：

| 翻译后 | 出站 content | 理由 |
|---|---|---|
| 什么都不剩 | — | 整条丢弃（见 5.2） |
| 只剩文本块 | 字符串 | 既有形态。多个文本块用 `\n` 拼接 |
| 含非文本块（当前只有图片） | 块数组 | 唯一能装下图片的形态 |

字符串输入直接短路，不绕一圈拆块再压回。

**为何纯文本仍输出字符串**：使本规则的影响面严格限定在「真的有图」这一种输入上，纯文本
工具结果的出站报文逐字节不变。与 user 消息对字符串 content 刻意不升格同一取向。

**这条曾经写错过。** 早期实现调 `stringify` 压平，而它只取文本块，于是 agent 调
`view_image` 之类工具读到的图片在这一步被静默丢弃 —— 那张图只存在于 `role: tool` 消息的
`content` 里。表现是「C2M 路线上模型看不见图，而 OpenAI 直连能看见」，且日志里
`tool_result.content` 只剩一段描述图片位置的文字，没有 base64。

**不要把 OpenAI 侧的「图片工具兼容规则」搬进翻译层。** 那条规则（把含图的 tool 消息整条
改成普通 user 消息、删掉 `tool_call_id`）是针对「某些 OpenAI 兼容中转站把 `role: tool`
的 content 当纯文本处理」这一实现缺陷的兼容妥協。**Anthropic 侧不存在该缺陷（已实测，
见 3.4.2）**，照协议把图片放进 `tool_result` 就够了 —— 无需为 Anthropic 侧再写一条兼容规则。

若将来遇到某个 Anthropic 中转站确实也认不出，那属于「这个上游需要什么」，用仅适用
`MESSAGES` 的请求体规则表达（那时规则层能表达，因为报文已是 Anthropic 形态）。

参考项目在这一点上都不完整，可以对照但不要照抄：one-api 的 `tool_result` 分支整个包在
`if message.IsStringContent()` 里，多模态 tool 消息会丢掉 `tool_result` 与 `tool_use_id`
（其 DTO 的 `Content` 字段是 `string`，结构上装不下图片块）；new-api 的 Chat→Claude 链把
下游 content 原样搬运，OpenAI 形态的 `image_url` 块直接发给 Anthropic；反方向它把
Anthropic 的块数组 `Marshal` 成 JSON 字符串塞进 OpenAI tool 消息，等于把图变成一段描述图
的文字。new-api 唯一做对的是 Responses 链的 `claudeToolResultToResponsesOutput`，那里做了
真正的块级翻译 —— 本节要求的正是同一件事的反方向。

#### 3.4.2 实测结论（2026-09-08，Copilot + mimo-v2.5 Anthropic 端点）

下游打 `/v1/chat/completions`、上游走 Anthropic 端点，agent 调 `view_image` 读一张人物
肖像照，**模型正确识别了图像内容**：思考链里出现帽檐宽度、羽毛装饰、暖色调、背景
虚化等只有看到像素才能得出的细节。修前的同一场景下模型只能看到一段
`[Image URI: vscode-chat-response-resource://...]` 文本。

两条由此从推断变成事实：

- **Anthropic 端点能识别 `tool_result` 里的图片块**。它不像 OpenAI 侧那些中转站那样把
  工具结果当纯文本处理，因此不需要在翻译层做 tool→user 那套妥协。
- **本节的形态选择是正确的**：块数组 + `image.source` 能被上游正确解析，不需要额外包装。

**已实测的只有 data URL 分支，而且在 Copilot 场景下走不到另一条。** Copilot 的 `view_image`
必须先把图片下载到本地才能识别（它读不了网页上的图，OpenAI 路径下同样如此，属于 Copilot
侧的限制），因此工具结果里的图片恒为 base64。`http`/`https` 图片走原生
`source.type: "url"` 那一条有单测覆盖，但**在当前主要客户端上没有触发路径**，
实流量验证要等到某个客户端真的直接发远程 URL 时才可能发生。

这条分支因此**不要因为「没验证过」就删掉或改成下载转 base64**：它是照 Anthropic 协议写的
正确映射，代价为零（不下载就没有额外带宽、延迟与失败点），只是暂时没有实流量经过。

### 3.5 tool_use ↔ tool_result 配对修复（必须实现）

逐项直译可能违反工具调用与结果的邻接要求。当前 **C2M 输出归一化**维护三项约定：

1. `tool_result` 必须紧邻前一条 assistant 的 `tool_use`
2. `tool_use` 必须被紧邻的下一条 user 应答
3. 归一化输出按语义轮合并相邻同角色消息

第 3 项是本实现的输出约定，**不是 Anthropic 禁止相邻同角色输入**。
合法 M2C 入站可以包含相邻 user/assistant，目标转换按 §10.4 处理，不能直接拒绝。

算法（借鉴 sub2api `normalizeAnthropicToolPairing`）：

```text
merge → pair → merge
```

- **首次 merge**：让并行调用聚拢到同一条 assistant 消息
- **pair**：按 `tool_use_id` 建索引 → 丢弃未被应答的 `tool_use`（该 assistant 无其他内容则整条丢）
  → 匹配的 `tool_result` 按调用顺序重发到紧邻的下一条 user → 孤儿 `tool_result` 丢弃
- **二次 merge**：配对可能重新拆分了 user 轮，这一步恢复交替

**三步顺序不可省略也不可交换。** 这是本契约里工程价值最高的一段，必须有单测覆盖
「未被应答的 tool_use」与「孤儿 tool_result」两种输入。

空 `tool_result` 内容**整条丢弃**，不填占位字符串（见 5.2）。content 的具体形态见 3.4.1。

**配对修复不得破坏块数组形态的 tool_result。** 三步里的 merge 与 pair 搬动的是整个
`tool_result` 块，只能操作消息级 content，不得动块内 content —— 否则带图的工具结果会在
重新摆位置时被压平。这一条要用单测钉住而非靠推断（在两条 tool_use / tool_result 之间
插一条正文 user，迫使 pair 真的发生搬动）。

---

## 4. 思考字段

### 4.1 三种协议三种形态

| 协议 | 开关 | 深度 |
|---|---|---|
| Chat 兼容生态（COSP 当前设置形态） | 部分上游支持 `thinking: {"type": "enabled"｜"disabled"}` | `reasoning_effort` 字符串，取值支持随上游/模型而异 |
| OpenAI Responses | — | `reasoning: {effort}`（有 `none`） |
| Anthropic Messages | `thinking: {"type": "disabled"｜"adaptive"｜"enabled"+budget_tokens}` | `output_config.effort`（顶层，五档 low/medium/high/xhigh/max，无 minimal/none） |

不要互相套用。COSP 的档位清单是 `Off / Minimal / Low / Medium / High / Xhigh / Max`。

`thinking` 开关是部分 Chat 兼容上游的字段，不是所有 OpenAI 官方模型通用的请求字段。
2026-10-21 核对的官方 SDK 已有 `reasoning_effort=none` 等取值，
不能再断言 none 只属于 Responses；本次仅纠正文档，不修改现有设置层的 Off 映射。

### 4.2 C2M 思考映射

| 下游 OpenAI | Anthropic |
|---|---|
| `thinking: {"type": "disabled"}` | `thinking: {"type": "disabled"}` |
| `reasoning_effort: <档位>` | `output_config.effort: <档位>` |
| 两者都缺失 | 两者都缺失，交给设置层 |

`Minimal` 在 Anthropic 侧无对应档。**原样发出**，由上游用错误码回答（见 4.5）。

### 4.3 M2C 思考映射

映射与显式 null 规则统一见 **§10.2**；历史 thinking 明文见 **§10.4**。
这里只保留导航，不维护第二张映射表。

### 4.4 不做 budget_tokens ↔ 档位换算

**决定：COSP 不实现这个换算。**

三个参考项目的换算规则毫无共识，说明这里没有权威答案：

| 项目 | 规则 |
|---|---|
| new-api | 按 `max_tokens` 百分比：minimal 5% / low 20% / medium 50% / high 80% / xhigh·max 95% |
| sub2api | 固定查表：medium 4096 / high 10240 / max 32768（`low` 不注入 thinking） |
| cc-switch | 固定查表：minimal·low 2048 / medium 8192 / high 16384 / xhigh·max 24576 |

反向更糟，两家的阈值表互不相同且都是纯启发式：

| 项目 | budget → 档位 |
|---|---|
| new-api | `0`→none / `<0`→high / `≤1024`→low / `≤8192`→medium / `>8192`→high |
| cc-switch | `<4000`→low / `4000–15999`→medium / `≥16000`→high |

new-api 那张表**永不产出 minimal / xhigh / max**，经一次中转 `minimal` 就被抹成 `low`，
往返不一致。两家都把 `output_config.effort` 排优先级 1、budget 反推排 2，自己也知道后者是兜底。

**结论**：M2C 只在 `output_config.effort` 存在时翻译深度，否则不发 `reasoning_effort`。
显式 null 的意见保留另见 §10.2，不代表从预算推导档位。
用户若需要在**上游 MESSAGES 的直连/C2M 路径**发送 `budget_tokens`，可用 MESSAGES 规则表达；
M2C 后执行的是 CHAT 规则，不能用前者恢复目标协议没有的预算语义。

### 4.5 不做自动降级

参考项目全部做降级，COSP 刻意不学。new-api 的 `minimal → low` 是**无条件**降级、不看能力位，
用户明确要 `minimal` 却收到 `low` 且没有任何提示。

理由与 AGENTS.md 一致：中转站会改模型名，按前缀硬编码能力表必然大面积失效；而尊重用户配置、
让上游用错误码回答是更诚实的选择。

cc-switch 有个折中值得记录但不采纳：只在**已知枚举**模式下钳到最高合法档，`passthrough` 模式
原值透传。理由是丢弃会让「选最深思考」静默退化成「不带 effort」。COSP 面对任意中转站，
「已知枚举」这个前提往往不成立。

### 4.6 若将来实现 budget_tokens，必须带三道保护

借鉴 cc-switch，注释理由都要写进代码：

1. `budget = min(budget, max_tokens / 2)`——否则 xhigh 档的大预算会吃干一个 modest 的
   `max_tokens`，只剩约 1 个输出 token
2. 钳完 `< 1024`（Anthropic 下限）就**直接关掉 thinking 并恢复正常采样**
3. **绝不抬高调用方的 `max_tokens`**——可能超模型输出上限而 400

第 3 条补上现有 `ensureMaxTokens` 缺的一条约束。对比 new-api 的手动模式会把 `max_tokens`
抬到 `budget + 1`。

### 4.7 思考链回放：`signature` 无解，但明文必须回放

跨协议时 `signature` **造不出来**——它由 Anthropic 自己签发。C2M 方向因此丢弃 assistant
消息里的思考内容，不尝试重建 `thinking` 块。

**M2C 方向不同（2026-10-20 修正，依据四参考项目调研）**：原来写「M2C 丢弃 `thinking` /
`redacted_thinking` 块」，那个结论**在工具链场景会导致硬失败**。

sub2api 给出要求回放的上游会拒绝缺失 reasoning_content 的工具续轮的依据。
cc-switch/CLIProxyAPI 也有回放路径，但带兼容开关或签名条件，**三家条件并非完全相同**。
COSP 用户选择工具链回放已有明文、不造占位；完整目标只在 §10.4 维护。

> **这不涉及「思考链缓存」**。V8.2 删掉的 `ReasoningCache` 是「代理存储上一轮内容、下一轮注入」，
> 是因为当年 Copilot 客户端不自己回传。而 Claude Desktop 等 Anthropic 客户端**本来就会把
> `thinking` 块放在历史消息里原样回传**，代理只需做格式转换。数据已在请求体中，无需任何跨请求状态。

**C2M 方向的既有风险不变**：Copilot BYOK 回传思考内容时，C2M 丢弃会让开启 extended thinking
且带工具的调用硬失败。落地前复核
[思考链回放调查](../COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md)。

### 4.8 Responses 的加密思考：R2* / *2R 的硬前提（2026-09-13 实测）

第 4.7 节区分已有明文可搬运与 Anthropic 签名不可重建。Responses 还可能携带
**不透明密文**，那是另一种不能凭空解释或重建的载体，不应与明文混为一谈。

> **前置事实：密文是上游的可选行为，不是 Responses 协议的必然产物**（2026-09-29 补测）。
> 实测一份真实链路（Codex → 第三方供应商），结论与「官方 Responses 一定签发密文」相反：
>
> | 响应样本 | reasoning 事件 | `reasoning_tokens` | `encrypted_content` |
> |---|---|---|---|
> | stepfun 读图（34 条） | **13 条** | **35** | **null** |
> | stepfun 工具链（119 条） | **38 条** | **129** | **null** |
> | stepfun 标题（112 条） | 0 条 | 0 | — |
> | astra 工具链（69 条） | 0 条 | 0 | — |
>
> 即：**该第三方供应商产出思考，但以明文下发，从不签发密文** —— 尽管请求方明确带了
> `include: ["reasoning.encrypted_content"]`。思考走独立 item + `reasoning_text` 内容块：
>
> ```json
> { "type": "reasoning", "summary": [],
>   "content": [{ "type": "reasoning_text", "text": "…明文思考…" }],
>   "encrypted_content": null }
> ```
>
> 而 Codex 回传时保持同一形态（明文 `content`、`encrypted_content: null`）。
> 因此本节的三条硬约束**只在「上游确实签发密文」时成立** —— 是否签发完全取决于上游，
> 不能假定。详见 [C2R 请求翻译调研](../../working/rfcs/2026-09-03-protocol-translation.md) §10.1。

#### 载体形态（实流量取证）

`reasoning` item 在请求与响应里的形状**不同**，重建时不能混用：

| 位置 | 字段 |
|---|---|
| 响应产出的 item | `{id, type, content, encrypted_content, summary, metadata, …}` |
| Codex 回传的 item（上游签发密文） | `{type, id, summary: [], encrypted_content}` —— 没有 `content` |
| Codex 回传的 item（上游**不签发**密文） | `{type, summary: [], content: [{type: "reasoning_text", …}], encrypted_content: null}` —— 有明文 `content` |

**两行的差别不是协议版本，是上游行为**：`encrypted_content` 与明文 `content` 是
`reasoning` item 的两种填充方式，取哪种由上游决定（见上方「前置事实」）。

`include: ["reasoning.encrypted_content"]` 是下游主动索要加密思考的信号，且它只在
`store: false`（或 ZDR）时才有意义 —— 服务端没有状态可回退，密文就是先前思考的**唯一载体**。

#### 三条硬约束

**① 必须取 `output_item.done` 的密文，`added` 的不完整。**

官方在 `encrypted_content` 条目下明文写着：流式时请用 `done` 事件里的完整 item，
`added` 的可能不完整。实测坐实且差距很大（同一次调用）：

| 调用 | `output_item.added` | `output_item.done` |
|---|---|---|
| 17092 | 1124 字符 | **4024** |
| 17093 | 1124 | **1848** |
| 17094 | 1124 | **3128** |

两者的密文头尾完全不同（`gAAAAABqppFMbYSk…` vs `gAAAAABqppFcTP2u…`），
**不是同一份被截断，是两次独立加密**。从 `added` 取会得到一份无法解密的密文，
回传后上游可能直接 400 —— 静默失效。

**② 密文绑定签发者，跨上游会解密失败。**

xAI 的真实错误（`sub2api` 记录，并为其写了专门的错误识别）：

```text
{"code":"invalid-argument","error":"Could not decrypt the provided encrypted_content."}
```

因此故障转移切换上游时，前一个上游签发的密文必须**主动剥离**（sub2api 的
`SanitizeOpenAICrossModeFailoverReasoning` 就是这件事）。含义是：**密文不可搬运到另一个上游**，
这与「翻译到另一种协议」是同一类问题 —— 换了消费方，密文即失效。

**③ 某些上游要求必须回传明文，缺失直接 400。**

`sub2api/chatcompletions_responses_bridge.go` 记录的原文：

> Codex histories may carry reasoning items with no plaintext summary (empty summary + opaque
> encrypted_content, e.g. after remote compaction); **DeepSeek's thinking mode rejects such
> histories with 400** "The `reasoning_content` in the thinking mode must be passed back to the API".

这条最要命：它把「丢弃思考」从「降质」升级为**硬失败**。Responses → Chat 翻译时，
加密思考在 Chat 格式里无处安放，而对话式上游拒绝没有 `reasoning_content` 的历史。

#### 剥离的收益与代价（实测结论与直觉相反）

**密文在后续请求里逐字节稳定**，它是可缓存前缀的一部分：

| 来源 | reasoning item id | 长度 | 密文头（28 字符） |
|---|---|---|---|
| 17092 产出 | `rs_0d5584…b2af` | 4024 | `gAAAAABqppFcTP2uGZnXV3QymPei` |
| 17093 回传 | `rs_0d5584…b2af` | **4024** | **完全相同** |
| 17093 产出 | `rs_0d5584…b6e1` | 1848 | `gAAAAABqppGANDoNnGFujxQTWVZQ` |
| 17094 回传 | `rs_0d5584…b6e1` | **1848** | **完全相同** |

因此：

- **剥离不会创造新的缓存命中** —— 它本来就在命中。
- **反而让命中率百分比略降**：被剥掉的正是「前几轮产出、本轮命中」的缓存区 token，
  分子分母同时减少但分母降得少。（不影响成本实质，缓存 token 单价本就低。）
- **真正的收益只是绝对 token 数下降**。17094 回传了 5872 字符密文（4024+1848），
  按 base64 约 4 字符/token 粗估 ≈ 1400 tokens，占该次 11152 的 13% —— **这是估算，
  未经精确分词器验证**。
- **操作注意**：中途开始剥离会造成一次全量 cache miss（前缀与之前建立的不同），
  之后重新稳定。「先跑几轮再改策略」比「一开始就剥离」代价更高。

#### sub2api 的解法（唯一见到的可用方案）

它**缓存自己流出去的明文思考**，按 reasoning item 的 id 索引，翻译时用
`ReasoningContentByID(itemID)` 钩子还原 `reasoning_content`。缓存未命中返回空串。

即：密文不可读 → 那就在自己还看得见明文的时候存下来。代价是需要一个按 item id
索引的思考缓存，而这正是本服务当前**刻意没有**的东西。

> 另一参考：`CLIProxyAPI` 的 `codex_reasoning_replay_cache.go` 给了同一思路的完整工程参数
> （TTL 1h / 10240 条上限 / 单 agent 256 轮与 16MB / 批量淘汰 128 / `homekv` CAS）。
> 两者都是「缓存明文」路线，但**仅在上游确实签发密文时才需要**。

#### 对本服务的含义

[ADAPTATIONS.md](../../architecture/ADAPTATIONS.md) 里那句
「当前 GitHub Copilot 客户端负责跨请求回放 `reasoning_content`；COSP 不再缓存、注入或定期清理
思考内容」的前提是 **Chat 线路 + Copilot BYOK** —— 那条路上客户端手上有明文，不需要代理代劳。

**在「上游不签发密文」时（实测的常见情况），Responses 线路同样满足这个前提** ——
Codex 手上是明文 `reasoning_text`（见上方「前置事实」）。

只有 **`Responses` + 上游确实签发密文** 时这个前提才不成立：Codex 手上只有密文，无法回放明文。
所以：

- 直连 Responses（现状）**不受影响** —— 原样透传，本服务不解释密文。
- **C2R 请求侧不需要思考缓存** —— 输入是 Chat 形态，下游手上有明文
  （且目标上游未必签发密文）。见
  [C2R 请求翻译调研](../../working/rfcs/2026-09-03-protocol-translation.md) §10.1。
- 一旦做 **R2C / R2M / M2R**（输入侧是 Responses 的思考），**必须先决定**：
  剥离（接受 400 或降质）还是自己缓存明文
  （接受一个思考缓存的维护成本）。这不是实现细节，是前置设计决策。

**尚未决定，不在本契约给出结论。** 本节只固定实测事实与约束，避免下次从零调研。

---

## 5. 无对应物字段的处置

### 5.1 静默丢弃（与三个参考项目一致）

三家的机制都是结构体白名单——未声明的字段在反序列化时就消失，转换层根本看不到，
因此「静默丢弃」是事实上的行业默认。

C2M 丢弃：`frequency_penalty`、`presence_penalty`、`n`、`logprobs`、`top_logprobs`、`seed`、
`response_format`、`logit_bias`、`user`、`stream_options`、`parallel_tool_calls`。

M2C 的已知非等价字段、显式拒绝边界和流式选项统一见 §10；
三个顶层服务端配置字段按 [ADR-0014](../../adr/adr-0014-m2c-drop-server-fields.md) 直接省略，
这是限定的兼容性取舍，不代表其服务端能力被实现，也不扩展到其它内容或执行要求。
已确认的值级边界以 §10 为准；新增拒绝策略须另行确认。

流式不索取 usage 的后果是 token/缓存明细缺失，并非所有统计都不写：
成功流式的日聚合仍记一次 `0,0`。接线缺口和证据见
[RFC §12](../../working/rfcs/2026-10-20-m2c-c2m-translation.md#m2c-final-review)。

> **注意不对称**：`stream_options` 仍属于 C2M 的丢弃清单（上游是 Anthropic，无此字段），
> 但 M2C 必须**生成**它（上游是 Chat，需要它才回 usage）。同一个字段名在两条方向上的处置相反，
> 这是协议差异而非矛盾。

必须显式丢弃而非留着：链条末尾只有 `removeIf(Objects::isNull)`，**非 null 的多余字段会原样
发给上游然后 400**。

**反面案例**：sub2api 的 `response_format` 与 `parallel_tool_calls` 转到 IR 了但下一段不读，
形成「看起来支持实际丢失」的假象——比干脆不声明更糟，读代码的人会以为它工作。
本契约要求丢弃点集中在一处并列出完整清单。

### 5.2 不塞占位内容

参考项目用魔法字符串填空内容：new-api 的 `"..."`、sub2api 的 `"(empty)"`。这些会作为**真实
内容**进入模型上下文与计费。

C2M 现有实现按 §3.2/§3.5 丢弃空内容，不能把这条直接套到反向。
**M2C 的空 tool_result 是合法应答，须保留空字符串**；允许编码已有错误状态和媒体来源，
但不伪造工具输出。目标规格见 §10.4/§10.5，决策见 ADR-0012。

### 5.3 不替客户端做决定

反面案例：

- new-api 在 `parallel_tool_calls: false` 时**凭空造** `tool_choice: {type: "auto"}` 来挂
  `disable_parallel_tool_use` 开关——下游只想关并行调用，却被追加了一个它没要求的工具选择策略
- sub2api M2C 硬编码注入 `parallel_tool_calls: true`

本契约：下游没表态的字段就是不存在，交给设置层。

---

## 6. 错误处置

### 6.1 报错而非静默修正的情况

| 情况 | 处置 | 理由 |
|---|---|---|
| `stop` 数组含非字符串元素 | 400 | new-api 用裸类型断言，`{"stop":[1,2]}` 会 panic 而不是 400 |
| `tool_calls[].function.arguments` 不是合法 JSON | 400 | 静默留空会让工具收到空参数 |
| 未知 `role` | 400 | 静默改成 `user` 会改变对话语义 |
| 已确认必须拒绝的非等价显式要求 | 400 | 各方向的确认范围不同，见 6.2；不是对所有未知字段全量拒绝 |

### 6.2 显式要求与推导值的区别

new-api 的 `BudgetSource` 区分用户显式值与系统推导值，是可参考的来源建模，
**不是授权 COSP 翻译器推导或自动修正**。本项目生成参数的默认/覆写仍由设置层负责，
译器不做预算换算和范围钳制。

也不将“有显式值”一概等同于“本地报错”：C2M 已确认的无对应字段仍按 §5.1 省略；
M2C 按 §10 的目标范围处理，已经确认不能等价表达的文档/执行要求才显式失败，
已确认的值级边界以 §10 为准；新增拒绝策略须另行确认，不由这条总原则替用户决定。

这个区分让两类值有不同的错误策略。同源的推理还有 new-api 的 `MergeExplicitAndSuffix`：
显式字段与模型名尾缀冲突时**报错**而非静默择一，理由是尾缀可能带独立计费身份，选错会让
请求语义与账目对不上。这条对 COSP 的 `[provider-key] model` 前缀路由同样成立。

### 6.3 错误分类

借鉴 new-api 的 `ClientError` 包装：区分「用户配置 / 请求非法」（4xx）与「翻译器内部故障」（5xx）。

当前 COSP 无 `@ControllerAdvice`，控制器的 `onErrorResume` 与 `findWebResponseException` 负责
透传上游状态码。翻译层的**本地**校验失败不经过上游，需要自己给出 4xx，新增分支先保持现有模式。

四类失败在控制器两侧各有独立分支，**不得归并**：

| 异常 | 状态码 | 成因 | 用户该改什么 |
|---|---|---|---|
| `ProtocolTranslationNotSupportedException` | 400 | 供应商未声明支持下游打的那个协议，而该方向的翻译未实现 | 勾上该协议，或换供应商 |
| `NoSupportedProtocolException` | 400 | 供应商一个协议都没勾（显式空数组） | 至少勾一个 |
| `RequestTranslationException` | 400 | 请求本身无法表达成目标协议（6.1 那四类） | 改请求，消息里带字段路径 |
| `ResponseTranslationException` | 502 | 上游给了个解析不了的报文 | 都没做错，是上游的问题 |

前三类重试无益，给 5xx 会诱导客户端重试。**落进 502 兜底分支的后果不只是状态码不对** ——
那个分支会把消息译成「无法连接到上游服务」，而上游根本没被尝试连接，排查方向会被指往
网络与上游可用性。

### 6.3.1 组装期异常必须转成 onError 信号

翻译器与调度器都是**同步**调用，而控制器的 `Mono.firstWithSignal(service.xxx(...), cancelSignal)`
参数是 eager 求值的。若应用服务在方法体里直接跑这些同步步骤，异常在 Mono **组装期**就抛出了
控制器方法，`onErrorResume` 不在链上 —— 上表的分类一条都用不上，下游拿到 WebFlux 默认 500。
流式更隐蔽：状态码尚未提交，因此发出去的不是 SSE error 帧而是 500 JSON。

因此 `ChatCompletionService` 与 `MessagesService` 的四个方法主体都包在
`Mono.defer` / `Flux.defer` 里，`ChatDispatchErrorSignalTests` 钉住这一点
（含「直连路径行为不变」那两条，否则把方法体换成 `Mono.error(...)` 也会全绿）。

### 6.4 不做错误驱动的事后整流

三个参考项目都有：匹配上游错误文案（`">= 1024"`、`"greater than or equal to 1024"`）后
改写 `budget_tokens` 并重试。上游改一次文案就全失效。

不作为主路径设计。若将来要做「上游 400 后一次性纠正重试」，判据必须比字符串匹配更稳，
且要复用既有的 `buildRetrySpec` 预算而非另起 `retryWhen`。

---

## 7. 请求翻译必须为响应侧留出口

响应翻译可以后做，请求翻译也可以是**无状态函数**；但其返回值必须携带本次请求上下文，
不能只返回 Map。响应侧需要知道请求侧发生过什么：

- 下游要的是流式还是非流式
- 有没有 `stream_options.include_usage`
- 工具是否被重命名过（若将来引入名称改写）
- 思考是**谁**打开的——下游要求的，还是设置层注入的（注入的那部分要不要回填给下游是个产品决定）

**要求**：请求翻译返回 `{translatedBody, translationContext}`，不要只返回一个 Map。
否则响应侧只能靠猜或二次解析请求体。

---

## 8. 落地顺序

正确性优先，两条不变式先钉测试再写实现：

1. **第 2 节的无损搬运** —— 单测覆盖 `thinking` 与 `reasoning_effort` 各自单独存在的情况，
   断言设置层的 `fallback` 不会退化成 `override`
2. **第 3.5 节的配对修复** —— 单测覆盖未被应答的 `tool_use` 与孤儿 `tool_result`
3. **第 3.4.1 节的 tool_result content 形态** —— 单测覆盖纯文本（仍为字符串）、data URL 图片、
   http URL 图片、图文混合保序，以及配对修复后图片仍在
4. 把 2.1 那行 `body.remove("reasoning_effort")` 移到设置层之后
5. 其余字段映射与丢弃清单
6. `translationContext` 出口
7. 响应与 SSE 侧 —— 已另立契约，见
   [RESPONSE-CONTRACT.md](./C2M-RESPONSE-CONTRACT.md)

前六项已完成（C2M 请求侧）。实际执行顺序随后调整为先做 **M2C 响应**而非 M2C 请求，
理由见响应侧契约第 0 节：补上响应翻译才能让 C2M 这条链端到端可用。

第 3 项（`tool_result` content 形态）是后补的：C2M 请求侧最初落地时压平了该字段，直到
agent 用工具读图的场景暴露出来才修，已于 2026-09-08 实测通过（见 3.4.2）。教训是
**「这个字段的目标形态是字符串」这个判断要按协议查证，不要按已见过的输入推断** ——
当时见过的 tool 消息都是纯文本的。

### 8.1 尚未决定的事项

- **深度的出站形态已选 `output_config.effort`，但 4.5 及更早的模型不认识它**。
  那些模型会以错误码回答，且本服务**不修** —— 与第 4.5 节「不做自动降级」一致。
  若要支持老模型，正确做法是让形态选择**可配置**（参考 cc-switch 的
  `thinkingLevelMap`：字符串=实际发送值 / null=该档明确不可用 / 键缺失=用上游默认），
  而不是从模型名推导。那需要一列新的模型配置，属于后续版本。
- **直连 Anthropic 线路上，下游发来的 `reasoning_effort` 不会被改写成
  `output_config.effort`**，而是被认作「已表态」后剥离，净效果是该次不发深度。
  那属于下游把 OpenAI 字段发给 Anthropic 端点的畸形请求，改写它等于替下游猜意图。
  翻译线路不受影响 —— 翻译器已把档位写进 `output_config.effort`，保留的那份只供判定。
- M2C 请求转换与设置模式见 `MessagesToChatRequestTranslatorTests`，HTTP 收包仍待后续阶段。
  C2M 请求侧见 `ChatToMessagesRequestTranslatorTests`，Anthropic 侧两个思考维度的四档注入见
  `ReasoningEffortSettingTests.Anthropic注入模式` 与 `GenericAnthropicChatServiceTests`。
- **Responses 的加密思考在翻译时剥离还是缓存明文** —— 三条硬约束见第 4.8 节。
  这是 **R2C / R2M / M2R**（输入侧是 Responses 的思考）落地前必须先做的决策（不是实现细节）：
  剥离会遇到部分上游的 400，缓存明文则要新增一个按 item id 索引的思考缓存，
  而那会推翻「COSP 不缓存思考」这条现有决策。等 R2* 有实际需求时再定。
  **C2R 不在其列** —— 它的输入是 Chat 形态的明文（第 4.8 节「前置事实」与
  [C2R 调研](../../working/rfcs/2026-09-03-protocol-translation.md) §10.1）。

---

## 9. 参考实现索引

调研于 2026-09-03，三个项目均在工作区内。

> **本节只覆盖 C2M / M2C。** C2R（下游 Chat → 上游 Responses）的请求侧调研是后来
> （2026-09-29）单独做的，含第四个参考项目，见
> [C2R 请求翻译调研](../../working/rfcs/2026-09-03-protocol-translation.md)。

| 项目 | 形态 | 请求侧入口 |
|---|---|---|
| new-api | C2M / M2C 两两互转 | `relaykit/relayconvert/internal/oai_chat/to_claude_messages_req.go`、`internal/claude_messages/to_oai_chat_req.go`；档位引擎 `reasoning/{claude,intent,suffix}.go` |
| sub2api | 以 OpenAI Responses 为中枢 IR，C2M 两段链式；M2C 另有直连 bridge | `backend/internal/pkg/apicompat/`，配对修复在 `responses_to_anthropic_request.go` |
| cc-switch | 代理层做真实转换；`thinkingLevelMap` 是给 Pi CLI 写配置、自身不执行翻译 | `src-tauri/src/proxy/providers/transform_codex_anthropic.rs`、`transform.rs` |

### 9.1 参考价值与适用范围

- sub2api 的 `tool_use` / `tool_result` 配对修复与三步顺序：用于 C2M 输出，不直接反用到 M2C。
- 「白名单转换 + 空内容整条丢」是现有 C2M 约定；M2C 空工具应答按 §10.4 保留。
- cc-switch 的 budget 三道保护
- cc-switch 的「平台判定绝不掺入 model 名」——model 名属模型厂商，掺进去会把托管平台误判成
  模型官方接口
- cc-switch 的冲突剥离方向是参考项目的兼容观察：在特定目标上游剥 effort。
  **M2C 未采纳该供应商特定整流**，其当轮双字段表态按 §10.2 交现有设置层/上游处理。
- new-api 的 `BudgetSource` 来源区分与 `ClientError` 分类
- new-api 的 golden 文件回归：整请求体快照，字段级回归一目了然

### 9.2 明确不借鉴

- 按模型名前缀硬编码能力表 + 逐级降级（三家都有）
- new-api 的 `strictSampling` 在「没要求思考」时也清采样参数
- 空内容塞魔法字符串
- 替客户端凭空造字段
- 把 http 图片 URL 一律下载转 base64
- 错误驱动的事后整流
- 按工具名的单点 hack（sub2api 只对 `name == "Read"` 且 `pages == ""` 生效）

---

<a id="m2c-request-spec"></a>

## 10. M2C 去程字段规格（历史存档，2026-10-24 版本）

> **2026-10-25：本节已拆分独立成文，真源为 [M2C-REQUEST-CONTRACT.md](./M2C-REQUEST-CONTRACT.md)**。
> 以下内容作为历史快照保留，仅供追溯拆分前版本；任何新增/修改请直接改独立契约，不要在本节改动。

**方向：下游 MESSAGES → 上游 CHAT；声明键 `(MESSAGES, CHAT)`。**
本节描述 2026-10-24 冻结的字段行为。实现和测试进展只在 RFC 维护。主要策略以
[ADR-0012](../../adr/adr-0012-m2c-request-translation.md) 为依据；
旧格式别名见 [ADR-0013](../../adr/adr-0013-m2c-output-format-alias.md)。
三个顶层服务端配置字段的省略边界见 [ADR-0014](../../adr/adr-0014-m2c-drop-server-fields.md)。
调研、代码文件清单、实施依赖和验证矩阵只在 RFC 维护。

### 10.1 顶层字段与流式上下文

| 下游字段 | 目标字段/处置 |
|---|---|
| model | 原样交译器；后续主干装配按现有规则剥供应商前缀 |
| stream | 入口还原已实现：true/false/null/缺省与端点分流一致；后续执行和装配仍以 ctx.stream 为准 |
| max_tokens | 同名原值，不补默认、不抬高、不按模型名换别名 |
| temperature / top_p | 原值搬运，不因开启思考而自动删除 |
| top_k | 无通用 Chat 对等字段，省略 |
| stop_sequences | 非空数组转同序 stop 数组，单元素也不压成字符串；空数组/null 省略；非数组、非字符串元素报路径错误，不排序或静默丢元素 |
| system | 暂留顶层作为约定中间态，交 §10.3 的 CHAT 支线处理；不能被顶层白名单提前丢掉 |
| thinking / output_config.effort | 见 §10.2 |
| output_config.format | 见 §10.7；不能因只处理 effort 而丢失 |
| tools / tool_choice | 见 §10.6 |
| metadata / service_tier / 额外追踪信息 | 仅明确无映射的辅助字段省略；不把内容与执行要求当元数据清掉 |
| cache_control | 仅协议外壳处省略；不递归删除 schema/input 中同名业务字段 |
| mcp_servers / container / context_management | 顶层字段直接省略，不校验值或类型、不报错、不模拟服务端能力；schema/input 等业务内容中的同名键保留 |

**流式：**只在 M2C 的 stream=true 时新建 `stream_options.include_usage:true`；
false/缺省不发该选项。额外混入 Messages 请求的 Chat stream_options 不是本方向的透传契约，
不将其其它成员原封不动搬到上游。

`TranslationContext` 从注入前的源请求提取，不能把“向上游索取统计”写成“Chat 下游要求统计”。
Anthropic 回程是否输出 usage 不依赖该 Chat 专用标志。后续 CHAT 请求体规则可改 false/删除选项，
译器/执行器不得在规则之后偷偷补回。

### 10.2 当轮思考配置与显式 null

| 下游表态 | 映射 |
|---|---|
| thinking.type=disabled | thinking.type=disabled |
| thinking.type=adaptive 或 enabled | 仅转成 enabled 开关；不搬 budget_tokens、不反推 effort |
| output_config.effort 为字符串 | reasoning_effort 同值，未知档位不自动降级 |
| thinking:null | 保留顶层 null 到设置层，避免 fallback 误判未表态 |
| output_config.effort:null | 写 reasoning_effort:null，同上 |
| output_config:null | 按现有 Anthropic 表态判据，用 reasoning_effort:null 保留显式空意见 |
| output_config:{} 或仅 format | 不代表深度意见，不阻止正常 fallback |
| 未提供 thinking/effort | 译器不推导，现有 ChatThinkingStage 按模型配置处理 |

未知开关类型或非对象的配置报路径化错误，不擅自修正；
有效 effort 与 disabled 同时出现时不加供应商特定整流，仍交现有四模式/目标上游处理。
装配末阶段才清顶层 null。历史 reasoning_content 与当轮思考设置不是同一个字段，不能联动误删。

`thinking` 是兼容生态字段，不能承诺每家官方/兼容 Chat 上游都接受；保持现有设置层的删除模式
和请求规则出口，不在本方向加模型名识别表。

### 10.3 system 归一化支线

**已实现（阶段 1）**：`ChatSystemPromptStage` 只声明 CHAT，并委托
`SystemPromptNormalizer.inlineSystemIntoMessages`。现有注册表按 bodyProtocol 自动命中，不修改主干。
实际过程：

1. 无顶层 system 时完整 no-op，不重排现有消息或改 developer/system 角色。
2. 字符串保留正文；数组按原序取 text 块，以 `\n\n` 拼接。未知非文本 system 块不应静默丢内容。
3. 仅在**整体提示词前导区域**剥已知的行首 `x-anthropic-billing-header:` 归属行，处理 LF/CRLF/CR，
  保留同块后续正文；遇到普通正文后不再过滤后续块，同名引用和普通身份描述不被通配删除。
4. 删除顶层 system，非空文本放消息首部。若首条已有字符串 system，按
   “顶层文本 + 双换行 + 原首条文本”合并；其它消息的相对顺序不动。
  首条数组 content 和 developer 不合并；首条空白字符串由顶层文本替换，不添多余分隔。
5. 空 system 不造占位消息；已存在的 messages 即使遇空 system 也要求为数组（包括显式 null 的错误）。
  非空 system 且 messages 缺失时报 messages 路径错误；空 system 且缺 messages 时只删除空字段。
  错误检查在修改 body 前完成，不将非法消息列表凭空替换成新数组。
6. 复制要改动的容器，不能修改原始嵌套 Map/List；再次执行应幂等。

注意现有 `stringifyContent` 用单 LF，不能直接当成 system 双 LF 的同义方法。
标准直连 CHAT 无顶层 system 保持 no-op；当前 OpenAiController 不回填顶层 system，
支线的非标准组合单测不能被当作已存在的 HTTP 契约变更。

### 10.4 消息、工具调用与因果配对

**消息形态：**只接受 MESSAGES 的 user/assistant 角色；未知角色和错置工具块
不自动升格或改为 user。相邻原始同角色消息按 Anthropic 语义归为同一轮，保留源索引用于报错。
这不是对最终 Chat 消息强制交替，也不调用现有 C2M 的 merge→pair→merge。

字符串正文原样，纯文本块按原序以 LF 拼成字符串；保留非空文本内部空白；含图片/文件才保留 parts。
assistant 的正文、tool_calls、reasoning_content 保持在同一条消息；工具-only assistant 可用空字符串 content。

| Anthropic 输入 | Chat 目标 |
|---|---|
| tool_use.id / name | tool_calls[].id / function.name，原样、不加前缀；缺失/空白/非字符串报错 |
| tool_use.input 对象 | function.arguments 的 JSON 字符串，不用 Map.toString、不双重编码 |
| input 缺省/null | 无参兼容映射为 `{}`；不把字符串或数组猜测成对象 |
| input 非 null 且非对象（包括空字符串/空数组） | 不猜测修正，给出 input 字段路径 |
| user.tool_result | 独立 role=tool，tool_call_id 原样匹配前置调用 |

**已确认的配对策略：**

- 请求内建索引，结果只能应答先前调用，不能把前置结果拉去应答未来调用。
- 重复 tool_use ID 或重复 tool_result ID 报错，不使用 first/last-wins 掩盖歧义。
- 未获应答的调用清理并告警；assistant 有正文则保留，清空才删除。
- 孤儿结果连同其附属媒体一起丢弃并留痕，不凭空制造对应调用。
- 真正空结果仍是有效应答，保持 tool 消息的 `content:""`，不会因此清理其调用。
- 本组有效结果按调用顺序紧邻 assistant 连续发出，媒体和普通正文不得插到结果组中间。
- Chat 允许连续 tool/user；不能将「无相邻同角色」当反向验收标准。

**历史思考：**在配对清理后仍有 tool_calls 的 assistant 轮，把已有 thinking 明文按原序
用 LF 拼为 reasoning_content；清掉最后一个工具调用后不再回放。signature 不搬、redacted_thinking
丢弃并告警；没有明文不补任何占位。纯文本轮不回放，也不建跨请求存储。

末尾普通 assistant 原样作为历史消息保留，不改为 user，也不额外拒绝。
Chat 没有通用的 prefill 续写保证，实际效果由目标上游决定；未应答工具清理仍独立执行。

### 10.5 工具结果内容、错误与媒体

Chat tool 消息只发 `role/content/tool_call_id`，**不发 name、is_error 或协议 cache_control**。

| tool_result 内容 | 目标方案 |
|---|---|
| 缺省/null/空串/空数组 | 空字符串，仍是已应答；不造 `(empty)` 等结果 |
| 字符串或纯文本块 | 保留文本，按原序合并；不把块 JSON 当正文 |
| is_error=true | 用最小固定文本标记表达已有错误状态，正文保留；false/缺省不添加成功文案 |
| 图片、含媒体文档 | 共用 §10.7 转换，从 tool.content 抽出，所有 tool 结果之后附媒体 user 消息 |
| 纯文本文档 | 保留在工具文本通道；标题/上下文与文档内容保持关联 |

**固定文本编码**（不含随机 ID 或时间戳）：

- 错误：在工具正文之前追加 `[tool_result is_error=true]\n`；无正文仍保留该已存在的错误事实。
- 媒体：工具文本尾部追加 `[media moved to next user message]`，已有正文时用一个 LF 分隔。
- 媒体 user：每个有效结果的媒体组前放 `[media from tool_use_id=<JSON 字符串>]` 文本 part；
  ID 使用 JSON 引号与转义。组按调用顺序排列，含媒体文档整组迁移，纯文本文档留 tool。
- 文档元数据：`[document title]\n<原标题>`、`[document context]\n<原上下文>` 放在源内容之前；
  缺省/null/空串不造标记，正文与元数据仍按源块相邻排列。

媒体组按有效工具 ID 标注来源，含媒体文档的标题/上下文随媒体一起放置；
不同工具的媒体不可混为无来源图片列表。随后再输出普通 user 内容，不能让来源标记提升为 system 指令。
ID/标题等文本通过 JSON 正确转义，不插入可执行模板。

允许固定的“媒体已移到后续 user 消息”说明，但**标记必须由真实元数据触发**，
无错误、无媒体的空结果仍是空串。文案和混合内容的 golden 排列属于 RFC §12.7 的工程细节，
不编造思考或工具返回，不加随机 ID/时间戳。标记会计入 token，是已接受的知情成本。

### 10.6 工具定义、schema 与选择

| 字段 | 目标方案 |
|---|---|
| 自定义 tool（type 缺省/null/custom） | type=function，name/description/input_schema 分别进入 function.name/description/parameters |
| 服务端原生工具 | 无等价执行能力时明确拒绝，不把 server tool 伪装成客户端函数 |
| input_schema | 缺省/null 使用最小 object schema；对象只补缺失的根 type 和 type=object 时缺失的 properties，显式 null 不替换；根非对象报错，布尔子 schema 保留 |
| strict | 显式布尔进入 function.strict；缺省/null 省略，非布尔报路径错误 |
| tool_choice auto / any / none | auto / required / none |
| tool_choice tool+name | `{type:"function",function:{name}}`；要求工具已声明 |
| disable_parallel_tool_use 显式 true / false | parallel_tool_calls false / true |
| 并行开关缺省/null | 不注入，不凭空补 auto 选择策略 |

无声明工具时 auto/none 省略；显式 required/具名选择无法满足时，报路径化错误，
不是悄悄放宽成 auto。无工具时不发无意义并行参数。

保留 `required/additionalProperties/$defs/$ref/format/pattern/examples` 和布尔子 schema。
不复制参考项目的删约束、bool→object 或按特定工具名修改参数。
协议字段清理只作用于协议外壳，**schema、tool_use.input 中的 cache_control/signature 等业务键不动**。

工具扩展仅在协议外壳处理，不递归查找 schema/input 中的同名业务属性：

| 字段 | 值级处置 |
|---|---|
| input_examples | 提示信息省略；非空值告警，不重写为 schema 约束 |
| eager_input_streaming | 显式布尔省略并告警；null/缺省省略，非布尔报类型错误。它描述参数流形态，不是执行权限 |
| defer_loading | false/null/缺省省略；true 无法等价保留工具可见性与发现时机，报路径错误；非布尔报类型错误 |
| allowed_callers | null/缺省或仅 direct 的数组省略；其它 caller 无法等价表达调用方式，报对应元素路径错误。空数组省略并告警，不承诺与服务端缺省行为等价；非数组/非字符串元素报类型错误 |

caller 是工具调用方式声明，不是代理可用来证明安全权限的边界。

### 10.7 文档与结构化输出

OpenAI 官方 file part 形态见 [SDK 固定版本](https://github.com/openai/openai-python/blob/v1.109.1/src/openai/types/chat/chat_completion_content_part_param.py)。
Anthropic document 有多种源形态，不能只实现 base64 后声称支持全部文档。

**普通图片与工具内图片共用转换**：

- `image.source.type=base64` → `image_url.url=data:<media_type>;base64,<原 data>`，
  MIME 须为 `image/*`，不重编码或解析图片。
- `image.source.type=url` → 原 URL 放入 `image_url.url`，仅接受 HTTP(S)，
  scheme 大小写不敏感，不下载或改写路径/签名参数；未知源及字段类型错误报告源路径。

| 文档源/字段 | 目标行为 |
|---|---|
| base64 PDF | Chat file.file_data=`data:application/pdf;base64,...`，原数据搬运；不解码提取正文 |
| 文件名 | 存在的 filename 扩展优先，否则使用稳定 document.pdf；不从标题/模型名猜 |
| source.type=text | media_type 必须为 text/plain；data 转 text，内容保留 |
| source.type=content | 展开字符串或其中 text/image parts，保持顺序，不 stringify 成 JSON 正文 |
| title/context | 作为相邻文本元数据保留；固定编码见 §10.5 |
| tool_result 内 document | 共用转换；含媒体时按 §10.5 的来源/顺序规则输出 |
| URL 文档 | 首版明确报错，不把 URL 塞进 file_data，也不替用户下载 |
| source.type=file | 首版明确报错：Anthropic file_id 不等于 OpenAI 账号/服务的上传 ID |
| citations.enabled=true / 需要引用定位 | 首版明确报错，不能承诺 Chat 回程重建引用坐标；false/缺省不造引用 |

格式必需字段缺失/类型错误报告精确 source 路径，不把包含不支持文档的混合文本请求当成功处理。
其它 MIME/扩展源的支持不凭模型名推断；文件内容有效性与实际模型是否支持由目标上游检验。
未列出的内容/执行型块不默认透传；需要新增拒绝策略时先按 RFC §12.7 冻结边界。

**上游能力差异按忠实映射处理，不自动降级**：目标上游不支持已正确映射的 `json_schema` 时
（实测 DeepSeek 返回 `This response_format type is unavailable now`，而 stepfun 与 mimo
接受同一请求体并返回合规 JSON），保留可理解的失败，不偷偷改写成 `json_object` 或其它形态 ——
那会静默丢掉 schema 约束。实机证据与差分复核见
[实施记录](../../working/rfcs/2026-10-20-m2c-c2m-translation.md#m2c-live-2026-10-24)。

**`response_format` 的联合类型结构**（官方 SDK 定义）：`type` 与伴随字段成对出现，
但 `json_object` **没有伴随字段** —— 它只保证合法 JSON，不携带 schema，约束完全靠 prompt。

| `type` | 伴随字段 | 说明 |
|---|---|---|
| `text` | 无 | 默认 |
| `json_object` | 无 | 只保证语法合法，无 schema |
| `json_schema` | `json_schema` | `name` / `description` / `schema` / `strict` |

Anthropic 侧 `output_config.format` 当前只有 `json_schema` 一种类型，故本方向只映射该类型；
若将来新增类型，译器以路径化错误拒绝。`json_object` 是未来唯一可选的降级目标，
但降级需同时解决 schema 约束丢失与 prompt 引导（必须在 prompt 中完整定义字段结构），
不是改一个字段名就能完成。

**结构化输出包装：**

- `output_config.format.type=json_schema` → `response_format.type=json_schema`。
- `format.schema` 必须为对象，再原样拷入 `response_format.json_schema.schema`；
  目标 name 固定 `cosp_output`，以 strict=true 表达源格式约束。
- 不为目标上游的 schema 子集偷偷改用户约束；不支持时错误可见。
- format 缺省/null 不生成 response_format；存在但形态不合法不能静默丢弃。
- effort 与 format 独立处理；旧 beta `output_format` 作为相同格式的别名使用同一转换逻辑。
  若同时出现 `output_format` 和 `output_config.format`（包括显式 null），报 `output_format` 路径错误；
  仅有 `output_config.effort` 不构成重复格式要求。

### 10.8 实现不变量与错误边界

- 不改变输入 Map 的深层内容；只用请求内局部状态，复制被改动的容器并保持后续规则可写。
- 同一输入产生确定性结果，消息/schema/input 不因无依据排序、随机标记或时间戳变化。
- 不对整份大文档反复 JSON 序列化/解析，不引入下载、上传、OCR 或文件存储。
- `RequestTranslationException` 带源字段路径；日志只记录方向、路径和必要 ID，
  不输出文档/base64、工具参数或 thinking 明文作为新诊断内容。
- 本地错误沿既有 defer 信号到出口：非流式 400，流式 HTTP200 + event:error；
  不等价于已实现完整的跨协议上游错误体转换。
- 主干顺序、鉴权设置、路由优先级、重试与空响应预算不在此方向重定义。
- 纯去程完成仍是半轮开发态；完整回程与客户端验收之前不宣称可生产使用。

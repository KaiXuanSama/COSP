# 协议翻译契约（请求侧）

> **状态**：O2A（下游 OpenAI → 上游 Anthropic）请求侧**已实现**，见
> `OpenAiToAnthropicRequestTranslator` 与 `ChatCompletionService` 两处分支；
> A2O（下游 `/v1/messages` + 上游 OpenAI）请求侧**尚未实现**，
> `MessagesService` 的翻译分支仍抛 `ProtocolTranslationNotSupportedException`。
> 本文档因此既是已实现说明（O2A）也是设计契约（A2O）。
>
> 相关：[AGENTS.md](../AGENTS.md)、[思考链回放调查](COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md)、
> [供应商适配史](PROVIDER_ADAPTATIONS.md)

O2A = OpenAI Chat Completions → Anthropic Messages；A2O = 反向。
本文档只覆盖**请求体**。响应与 SSE 侧留待后续，但第 7 节规定了请求侧必须为它留的出口。

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
    → ★ 纯字段翻译 O2A（下游没说的字段留空，不推导）
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

翻译路线上生效的是 `ANTHROPIC` 规则组，**不是**用户下游说的 `OPENAI`。规则的字段路径照最终
出站形态写，这是唯一自洽的选择。但它与用户直觉相反，UI 上必须说清楚。

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

`GenericAnthropicChatService.prepareRequestBody` 里的

```java
body.remove("reasoning_effort");
```

曾经在设置层**之前**，会把翻译结果先杀掉。翻译层落地后它已移到设置层
**之后**：翻译器保留一份 `reasoning_effort` 供设置层判定「下游已表态」，
那份兼容副本在所有设置层逻辑结束后才被剥离。收尾清理仍需保留 ——
要删的从来不是这行本身，而是它**在设置层之前**执行这件事。

---

## 3. 字段映射表（O2A）

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
| `image_url`（data URL） | `image.source: {type: "base64", media_type, data}` | 映射，解析 `data:<mt>;base64,<data>` |
| `image_url`（http/https） | `image.source: {type: "url", url}` | 映射。**不下载转 base64**——Anthropic 原生支持 URL 形态，代理侧下载是额外带宽、延迟与失败点 |
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
| `role: tool` + `tool_call_id` | user 消息里的 `tool_result` 块 | 映射 | 见 3.5 |

**工具调用 ID 原样搬运。** 不做前缀拼接。参考项目里 sub2api 对任何未知前缀的 id 一律拼
`toolu_`，可能撞长度限制或产生不合法 ID。

### 3.5 tool_use ↔ tool_result 配对修复（必须实现）

逐项直译**必然**违反 Anthropic 的三条不变式：

1. `tool_result` 必须紧邻前一条 assistant 的 `tool_use`
2. `tool_use` 必须被紧邻的下一条 user 应答
3. 角色交替

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

空 `tool_result` 内容**整条丢弃**，不填占位字符串（见 5.2）。

---

## 4. 思考字段

### 4.1 三种协议三种形态

| 协议 | 开关 | 深度 |
|---|---|---|
| OpenAI Chat Completions | `thinking: {"type": "enabled"｜"disabled"}` | `reasoning_effort` 字符串（无 `none`） |
| OpenAI Responses | — | `reasoning: {effort}`（有 `none`） |
| Anthropic Messages | `thinking: {"type": "disabled"｜"adaptive"｜"enabled"+budget_tokens}` | `output_config.effort`（顶层，五档 low/medium/high/xhigh/max，无 minimal/none） |

不要互相套用。COSP 的档位清单是 `Off / Minimal / Low / Medium / High / Xhigh / Max`。

### 4.2 O2A 思考映射

| 下游 OpenAI | Anthropic |
|---|---|
| `thinking: {"type": "disabled"}` | `thinking: {"type": "disabled"}` |
| `reasoning_effort: <档位>` | `output_config.effort: <档位>` |
| 两者都缺失 | 两者都缺失，交给设置层 |

`Minimal` 在 Anthropic 侧无对应档。**原样发出**，由上游用错误码回答（见 4.5）。

### 4.3 A2O 思考映射

| 下游 Anthropic | OpenAI |
|---|---|
| `thinking: {"type": "disabled"}` | `thinking: {"type": "disabled"}` |
| `output_config.effort` 存在 | `reasoning_effort` 同值 |
| `thinking: {"type": "adaptive"}` | **只搬运开关语义，不推导深度** |
| `thinking: {"type": "enabled", budget_tokens: N}` | **只搬运开关语义，不从 N 反推档位** |

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

**结论**：A2O 只在 `output_config.effort` 存在时翻译深度，否则不发 `reasoning_effort`。
用户若需要 `budget_tokens`，用仅适用 ANTHROPIC 的请求体规则显式表达。

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

### 4.7 思考链回放：已知无解

跨协议时 `signature` **造不出来**——它由 Anthropic 自己签发。三个参考项目全部选择丢弃整个
reasoning item，sub2api 的注释最清楚：`encrypted_content` 不透明，带 `content` 数组的形态
也一并丢，否则 `reasoning_text` 塞进去直接 400。

**本契约的处置**：O2A 丢弃 assistant 消息里的思考内容，不尝试重建 `thinking` 块。
A2O 丢弃 `thinking` / `redacted_thinking` 块。

Copilot BYOK 会回传上一轮思考内容，因此**翻译路线上开启 extended thinking 且带工具时会硬失败**。
这不是「翻译得不够漂亮」，是会被上游直接拒绝。落地前先复核
[思考链回放调查](COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md)。

---

## 5. 无对应物字段的处置

### 5.1 静默丢弃（与三个参考项目一致）

三家的机制都是结构体白名单——未声明的字段在反序列化时就消失，转换层根本看不到，
因此「静默丢弃」是事实上的行业默认。

O2A 丢弃：`frequency_penalty`、`presence_penalty`、`n`、`logprobs`、`top_logprobs`、`seed`、
`response_format`、`logit_bias`、`user`、`stream_options`、`parallel_tool_calls`。

A2O 丢弃：`metadata`、`mcp_servers`、`container`、`context_management`、`service_tier`、
顶层 `cache_control`。

必须显式丢弃而非留着：链条末尾只有 `removeIf(Objects::isNull)`，**非 null 的多余字段会原样
发给上游然后 400**。

**反面案例**：sub2api 的 `response_format` 与 `parallel_tool_calls` 转到 IR 了但下一段不读，
形成「看起来支持实际丢失」的假象——比干脆不声明更糟，读代码的人会以为它工作。
本契约要求丢弃点集中在一处并列出完整清单。

### 5.2 不塞占位内容

参考项目用魔法字符串填空内容：new-api 的 `"..."`、sub2api 的 `"(empty)"`。这些会作为**真实
内容**进入模型上下文与计费。

本契约：空内容**整条丢弃**。丢一条比发一条被污染的消息更可用。

### 5.3 不替客户端做决定

反面案例：

- new-api 在 `parallel_tool_calls: false` 时**凭空造** `tool_choice: {type: "auto"}` 来挂
  `disable_parallel_tool_use` 开关——下游只想关并行调用，却被追加了一个它没要求的工具选择策略
- sub2api A2O 硬编码注入 `parallel_tool_calls: true`

本契约：下游没表态的字段就是不存在，交给设置层。

---

## 6. 错误处置

### 6.1 报错而非静默修正的情况

| 情况 | 处置 | 理由 |
|---|---|---|
| `stop` 数组含非字符串元素 | 400 | new-api 用裸类型断言，`{"stop":[1,2]}` 会 panic 而不是 400 |
| `tool_calls[].function.arguments` 不是合法 JSON | 400 | 静默留空会让工具收到空参数 |
| 未知 `role` | 400 | 静默改成 `user` 会改变对话语义 |
| 目标协议无法表达下游的显式要求 | 400 | 见 6.2 |

### 6.2 显式要求与推导值的区别

借鉴 new-api 的 `BudgetSource`：**用户显式给的值做不到就报错，系统推导出来的值可以静默调整。**

这个区分让两类值有不同的错误策略。同源的推理还有 new-api 的 `MergeExplicitAndSuffix`：
显式字段与模型名尾缀冲突时**报错**而非静默择一，理由是尾缀可能带独立计费身份，选错会让
请求语义与账目对不上。这条对 COSP 的 `[provider-key] model` 前缀路由同样成立。

### 6.3 错误分类

借鉴 new-api 的 `ClientError` 包装：区分「用户配置 / 请求非法」（4xx）与「翻译器内部故障」（5xx）。

当前 COSP 无 `@ControllerAdvice`，控制器的 `onErrorResume` 与 `findWebResponseException` 负责
透传上游状态码。翻译层的**本地**校验失败不经过上游，需要自己给出 4xx，新增分支先保持现有模式。

### 6.4 不做错误驱动的事后整流

三个参考项目都有：匹配上游错误文案（`">= 1024"`、`"greater than or equal to 1024"`）后
改写 `budget_tokens` 并重试。上游改一次文案就全失效。

不作为主路径设计。若将来要做「上游 400 后一次性纠正重试」，判据必须比字符串匹配更稳，
且要复用既有的 `buildRetrySpec` 预算而非另起 `retryWhen`。

---

## 7. 请求翻译必须为响应侧留出口

响应翻译可以后做，但请求翻译**不能做成完全无状态的一次性变换**。响应侧需要知道请求侧发生过什么：

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
3. 把 2.1 那行 `body.remove("reasoning_effort")` 移到设置层之后
4. 其余字段映射与丢弃清单
5. `translationContext` 出口
6. 响应与 SSE 侧 —— 已另立契约，见
   [PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md](./PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md)

前五项已完成（O2A 请求侧）。实际执行顺序随后调整为先做 **A2O 响应**而非 A2O 请求，
理由见响应侧契约第 0 节：补上响应翻译才能让 O2A 这条链端到端可用。

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
- 单测尚未覆盖 A2O 请求方向（未实现）。O2A 请求侧见
  `OpenAiToAnthropicRequestTranslatorTests`，Anthropic 侧两个思考维度的四档注入见
  `ReasoningEffortSettingTests.Anthropic注入模式` 与 `GenericAnthropicChatServiceTests`。

---

## 9. 参考实现索引

调研于 2026-09-03，三个项目均在工作区内。

| 项目 | 形态 | 请求侧入口 |
|---|---|---|
| new-api | O2A / A2O 两两互转 | `relaykit/relayconvert/internal/oai_chat/to_claude_messages_req.go`、`internal/claude_messages/to_oai_chat_req.go`；档位引擎 `reasoning/{claude,intent,suffix}.go` |
| sub2api | 以 OpenAI Responses 为中枢 IR，O2A 两段链式；A2O 另有直连 bridge | `backend/internal/pkg/apicompat/`，配对修复在 `responses_to_anthropic_request.go` |
| cc-switch | 代理层做真实转换；`thinkingLevelMap` 是给 Pi CLI 写配置、自身不执行翻译 | `src-tauri/src/proxy/providers/transform_codex_anthropic.rs`、`transform.rs` |

### 9.1 值得借鉴（已纳入本契约）

- sub2api 的 `tool_use` / `tool_result` 配对修复与三步顺序
- sub2api 的「白名单转换 + 空内容整条丢」及其因果说明
- cc-switch 的 budget 三道保护
- cc-switch 的「平台判定绝不掺入 model 名」——model 名属模型厂商，掺进去会把托管平台误判成
  模型官方接口
- cc-switch 的冲突剥离方向：DeepSeek 端点 `thinking: disabled` 与 effort 互斥时，
  尊重客户端的 `disabled`、剥掉 effort，而非反过来覆盖客户端意图
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

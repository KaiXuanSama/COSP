# 协议翻译契约（响应侧 · C2R 链路）

> **方向**：本契约覆盖 **C2R 链路（下游 /v1/chat/completions · 上游 Responses）** 的
> **响应回程**：上游 Responses 响应（非流式 JSON / SSE 事件流）→ 下游 OpenAI Chat Completions
> 响应（`chat.completion` / `chat.completion.chunk`）。
>
> **易混警告**：这是 C2R 链路（下游 Chat · 上游 Responses）的响应方向，即 R2C 响应；
> 与 C2M/M2C 链响应方向相反。本文件单独成文，不与其他链互相抄写。
>
> 状态：**R2C 回程已实现并注册，自动化回归通过，三家真实上游实机验证**（2026-10-04，
> 记录在 [协议翻译四方向 RFC](../../working/rfcs/2026-09-03-protocol-translation.md) §E）。
> 本文件是事件/字段规格与测试矩阵来源；「必须 / 不要」是约束，标注实测日期的段落才是已验证事实。

**决策链**：[ADR-0005：R2C 回程翻译的六项核心决策](../../adr/adr-0005-r2c-response-translation.md)（已归档，本文为事件级细节）。
**相关**：[请求侧契约](./C2R-REQUEST-CONTRACT.md)、[协议翻译四方向 RFC](../../working/rfcs/2026-09-03-protocol-translation.md)（R2C 调研/计划/实施记录）、
[M2C 链路响应契约](./M2C-RESPONSE-CONTRACT.md)。

---

## 1. 实现位置与复用

`ResponsesToChatResponseTranslator`（`@Component`，声明 `(CHAT, RESPONSES)`）套在上游执行器
外侧、`retryWhen` 之外；`AfterSend` / `BeforeSend` / 调度器零改动即自动接通。
复用 `OpenAiResponseShapes`（全部 Chat 形状）、`UpstreamEventClassifier`（输出分类）、
`TranslationContext`（includeUsage）、`ResponsesStreamEvents`（终态判定）。

## 2. 事件 → 动作总表（流式状态机）

| 上游事件 | 动作 | 产帧数 |
|---|---|---|
| `response.created` | adopt id/model；发 role 帧 | 1 |
| `response.in_progress` | 忽略 | 0 |
| `response.output_item.added`（function_call/custom_tool_call） | 注册工具（三级键 + pending 拼入）；发 tool start 帧（index/id/name，arguments=""） | 1 |
| `response.output_item.added`（message/reasoning/其它） | 忽略 | 0 |
| `response.output_text.delta` | 发 content delta（前缀：role 未发则带上） | 1 |
| `response.reasoning_text.delta` / `reasoning_summary_text.delta` | 发 reasoning_content delta（前缀：needsReasoningBreak 差额补足 + role 检查） | 1 |
| `response.reasoning_text.done` / `reasoning_summary_text.done` | 设 needsReasoningBreak 标记（不发固定 `\n\n`） | 0 |
| `response.output_text.done` / `content_part.*` / `reasoning_part.*` | 忽略 | 0 |
| `response.function_call_arguments.delta` / `custom_tool_call_input.delta` | 查工具（三级）→ 命中：累加 + 发差量帧；未命中：存 pending | 0/1 |
| `response.function_call_arguments.done` / `custom_tool_call_input.done` | 前缀补齐：对比已发长度，只发未发过的差量（不发 name） | 0/1 |
| `response.output_item.done`（function_call） | 若工具已发过参数则不发；否则用 done 携带的完整 arguments 补发 | 0/1 |
| `response.output_item.done`（其它类型） | 忽略 | 0 |
| `response.completed` / `response.done` | 吸收：提取 usage → lastUsageRaw；终态 output[] 补发；发 finish chunk | 1..N |
| `response.incomplete` | 同上 + `incomplete_details.reason` 映射（`max_output_tokens`→`length`、`content_filter`→`content_filter`、其它→`stop`） | 1..N |
| `response.failed` / `error` | 不产内容帧，warn 日志 | 0 |
| `response.cancelled` / `canceled` | 同 failed 处置 | 0 |
| （流结束，concatWith finalizeStream） | 未发 finish 则按三档补（已发/有内容→`length`/无内容→`stop`）；usage chunk（includeUsage && hasUsage）；`[DONE]` | 0..3 |
| 未知事件 / 解析失败 | debug 日志跳过 | 0 |

## 3. 关键决策与行为

- **终态事件吸收**：`completed` / `done` / `incomplete` 不透传原文，转出 finish chunk；`[DONE]` 由流结束触发。
- **failed/error 不产帧**：不转 onError 信号（空响应门已按原生形态判定过），warn 日志 + 按「无实质内容收尾」处理。
- **终态 output[] 补发**：`completed`/`incomplete` 携带完整 `output[]`，若其中有流内从未以 delta 发出的内容（message 文本 / reasoning / function_call），补发对应 chunk；与非流式共用提取代码。
- **output_item.added 对 message / reasoning 不产帧**（只有 function_call 产）。
- **model 口径**：保持上游裸名透传（实测三家下游 agent 均无问题；契约勘误见 RFC §6-1）。
- **非流式 content 空时给 `""` 而非 null**。

## 4. 非流式映射

`response.output[]` → Chat `choices[0].message`：

| Responses | Chat |
|---|---|
| message item（output_text parts） | content（多 item/part 空行分隔，只补足到 2 个换行） |
| reasoning item | reasoning_content（content 优先、summary 兜底，两者不拼接） |
| function_call / custom_tool_call | tool_calls[]（id 用 call_id 空回落 item id；arguments 取 input；多工具保序） |
| output_text content 空 | `""`（非 null） |
| usage | prompt=input 直接映射（不加缓存）；reasoning_tokens 归位 completion_tokens_details；details 只给非零份 |
| id / model / created_at | 原样透传 |

## 5. 错误与边界

- 丢弃但留痕：`logprobs` / `obfuscation` / `encrypted_content` / `sequence_number` / `annotations` / `web_search_call`。
- 不产内容帧的事件不中断流；解析失败单事件跳过（不中断整条流）。
- 输出帧必须再过 `UpstreamEventClassifier.classify(…, CHAT, frame)`，`[DONE]` 由此分类为 Terminal。
- usage 双通道：`UpstreamEvent.usage()` 槽由执行器填充（记账用），翻译器只传递不重算；出站 usage chunk 由状态机自算。
- 落库改写器已接线：回程命中时 `translateChunksForLog` 用独立状态重放，产出下游实际收到的 chunk + 逐事件产帧数。
- 空响应判定与重试发生在上游原生形态，不受翻译影响。

## 6. 实现与验证状态

- 实现：`ResponsesToChatResponseTranslator` + `ResponsesToChatNonStreamTranslator` + `ResponsesToChatStreamTranslator` + `R2CStreamState` + `ResponsesToChatUsageConverter`。
- 自动化：非流式 29 条 + 流式 27 条 + 注册/集成，均覆盖。
- 实机：三家供应商（mimo-tokenplan / stepfun / deepseek）非流式 + 流式 + 工具调用 + 多轮验证通过。

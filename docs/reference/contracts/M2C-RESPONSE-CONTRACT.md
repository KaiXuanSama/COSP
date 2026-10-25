# 协议翻译契约（响应侧 · M2C 链路）

> **方向**：本契约覆盖 **M2C 链路（下游 /v1/messages · 上游 Chat）** 的**响应回程**：
> 上游 OpenAI Chat Completions 响应（`chat.completion` / `chat.completion.chunk`）→
> 下游 Anthropic Messages 事件流（`message_start` / `content_block_*` / `message_delta` / `message_stop`）。
>
> **易混警告**：`C2M-RESPONSE-CONTRACT.md` 覆盖的是 **C2M 链路（下游 Chat · 上游 Messages）**
> 的响应方向（Anthropic Messages 响应 → OpenAI Chat 响应，即 M2C 响应）。两条链方向相反，
> 另一条链不在本文续写，本链的规格也不并入 `C2M-RESPONSE-CONTRACT.md`。
>
> 状态：**阶段 A 已实现并注册（ADR-0015 Accepted），自动化回归与三家真实上游通过，客户端实机未验证**。
> 实施范围与验证记录见 [专项 RFC](../../working/rfcs/2026-10-25-c2m-response-translation.md#c2m-stage-a)，实机看 [三家存档](../../working/rfcs/2026-10-25-c2m-response-translation.md#c2m-live-2026-10-25)。
> 本文档是字段契约与测试矩阵来源；
> 「必须 / 不要」是约束，标注实测日期的段落才是已验证事实。

**牵头决策**：[ADR-0015](../../adr/adr-0015-c2m-response-translation.md)。
**相关**：[请求侧契约（C2M/M2C）](./C2M-REQUEST-CONTRACT.md)、
[M2C/C2M RFC](../../working/rfcs/2026-10-20-m2c-c2m-translation.md)、
[响应侧契约（C2M 链路，M2C 响应）](./C2M-RESPONSE-CONTRACT.md)。

---

## 0. 为何新立本文档

`C2M-RESPONSE-CONTRACT.md` 的语义已锁定为「C2M 链路（下游 Chat · 上游 Messages）的响应」，
其内部实际内容是 M2C 响应（Anthropic → OpenAI）。本次实现的是反过来的一条链：
M2C 链路（下游 Messages · 上游 Chat）的响应，即 C2M 响应（OpenAI → Anthropic）。
两方向在同一文件中会互相污染，故契约独立成文。

## 1. 链路与位置

```text
下游 Claude 客户端（POST /v1/messages）
  → COSP 请求翻译（Messages → Chat，已实现：MessagesToChatRequestTranslator）
  → 上游 OpenAI Chat（chat.completion / SSE）
  → ★ 本契约：响应翻译（Chat → Messages，ChatToMessagesResponseTranslator）
  → 下游 Anthropic Messages 事件流
```

翻译器定位：套在 `GenericOpenAiChatService` **外侧**、`retryWhen` **之外**；
空响应判定与落库用上游原生形态，翻译不得进入重试内侧。
（与 M2C 响应翻译器 `MessagesToChatResponseTranslator` 的定位原则一致。）

## 2. 事件序列骨架

上游 OpenAI SSE chunk → 下游 Anthropic 事件：

```text
message_start（id/model/usage 占位）
  → content_block_start(thinking/text/tool_use) → content_block_delta(thinking_delta/text_delta/input_json_delta)* → content_block_stop
  → …（多块）
  → message_delta（stop_reason + usage）→ message_stop
```

约束：**每流只能有一个 message_delta**；块之间严格顺序，切换必须「先 stop 再 start」。

## 3. 事件映射总表（流式）

| OpenAI 事件 | 下游 Anthropic 事件 | 帧数 |
|---|---|---|
| 首个有效 Chat 帧（包括空 `delta`/仅 usage） | `message_start`，不依赖 `role` | 1，另加该帧的载荷输出 |
| `choices[0].delta.reasoning_content`（非空） | `content_block_start(thinking)` + `content_block_delta(thinking_delta)` 流 | 1+ |
| `choices[0].delta.content`（非空） | `content_block_start(text)` + `content_block_delta(text_delta)` 流 | 1+ |
| `choices[0].delta.tool_calls[]` | 当前工具 start/delta；其它工具暂缓 | 0..多帧 |
| 后续空 `delta` | 不打开空内容块 | 0（含 finish 时按下一行） |
| `choices[0].finish_reason` | 记录首次有效原因，关闭/重放内容块，暂不发 `message_delta` | 0..多帧 |
| 非尾部 `usage` | 更新展示快照，不逐条相加 | 0，首帧可能另产 `message_start` |
| trailing usage-only chunk | 关闭/重放内容后，`message_delta` + `message_stop` | 2+ |
| `[DONE]` / 正常 EOF | 同上；已经结算则零帧 | 0 或 2+ |

`message_start.message` 带 `content:[]`、`stop_reason:null`、`stop_sequence:null`，
`output_tokens` 为 0；只有报了内容才打开 text/thinking 块。一个 chunk 同时含思考、正文、
工具时逐项处理，不用互斥 `if/else` 静默丢另一类内容。只读取首个 choice，不拼接候选回答。

## 4. 跨帧状态（参考 new-api `ClaudeConvertInfo`）

必须持有：

- `currentBlockType`：无打开块 / `text` / `thinking` / `tool_use`（对应参考模型的 lastMessagesType）
- `currentBlockIndex` / `nextBlockIndex`：Anthropic 当前与下一个稠密块索引
- 工具映射：OpenAI `tool_calls[].index` → Anthropic block index（独立索引域，必须显式映射）
- 每个工具：上游 index、block index、ID、Name、**逐片** pendingArguments、started/closed/emittedArguments
- 首次 finishReason、contentFinished/finalized、展示用的最新上游 usage 快照

每次订阅与日志重放独立创建 `C2MStreamState`；自动重试仍留在上游执行器的原生 gate 内。
记账槽不进入这个状态，仍由门面携带上游 `UsageTokens` 快照。

## 5. 交错内容缓冲（借鉴 CLIProxyAPI）

工具块打开期间到达的文本/思考增量**不得直接发帧**（Anthropic 要求块严格顺序）。
缓冲为 `BufferedContent(type, text)`（借鉴参考实现的 InterleavedContentChunk），finalize 时按原顺序重放为独立块
（`start → delta → stop`）。

当前工具的参数继续逐片下发；其它工具的参数保留原片边界排队，待当前工具关闭后按首次出现
顺序输出。交错正文/思考在工具组结束后按其原序输出，同类相邻片可共用块但不合并 delta。
这可能改变工具与文本间的全局交错位置，却不混淆任何工具的参数归属或各类文本内部顺序。
不得仅因为看到另一个 tool index 就关闭当前工具再向旧块补 delta，也不缓存整轮再造单 chunk。

## 6. message_delta 延迟单发（借鉴 cc-switch / CLIProxyAPI）

- 上游 `finish_reason` 到达：只记录 `stop_reason`，不立即发。
- 同时关闭/重放内容块，但**不**提前结算消息；finish chunk 自带 usage 也仍等待尾帧。
- `[DONE]` / 正常 EOF / trailing usage-only chunk：此时才发唯一一次 `message_delta` + `message_stop`。
- 尾 usage 判据：无 choice 且存在可解析的计数，并已看到内容、工具或 finish；
  首个只带 usage 的占位帧不结束生成。
- 多次 `finish_reason` 去重；错误事件不伪装成成功完成。

## 7. 工具调用映射

- `content_block_start(tool_use)` 延迟到 `id` + `name` 齐备才发；此前到达的 `arguments`
  分片缓冲，start 后立即 flush 为 `input_json_delta`。
- `arguments` 分片**原样透传**，不中途解析（切点可能落在 `\"` / `\uXXXX` 中间）。
- **工具名缺失**：仅在工具组收尾仍缺时生成 `tool_<上游 index>`；有名字的原值不改。
- 工具 id 有值原样传；从未提供时用消息 id 与上游工具 index 生成稳定的 `toolu_cosp_<UUID>`。
  同一消息重放稳定、不同消息不复用占位 ID，避免多轮历史被去程重复 ID 校验拒绝；
  不冒充上游提供过的值。重复空/不同元数据不覆盖已经宣布的首个有效 id/name。
- **无参工具 close 时补 `{}` 的 `input_json_delta`**（按 sub2api，防客户端只靠 delta 拼输入）；
  只有完全没有非空参数片才补，已有 `{}` 不重复，截断或不合法参数片不修补、不替换为 `{}`。
- 元素/function/index/arguments 的类型错误走 `ResponseTranslationException`；
  finish 后继续带实质载荷同样失败，不能往已关闭块补内容。错误不扩大自动重试预算。

## 8. finish_reason → stop_reason 映射

| OpenAI finish_reason | Anthropic stop_reason |
|---|---|
| `stop` | `end_turn` |
| `length` | `max_tokens` |
| `tool_calls` / 旧 `function_call` | `tool_use` |
| `content_filter` | `end_turn` |
| 未知 | `end_turn`（记 warning） |

停止原因未提供时：有工具用 `tool_use`，否则 `end_turn`；不因工具存在而覆盖上游显式的
`length` / `content_filter` / `stop`。

## 9. usage：落库与展示双轨

- **落库**：始终取**上游 Chat 原生 usage**（`OpenAiUsageParser` 解析；由 `GenericOpenAiChatService`
  在流内挂到 `UpstreamEvent.usage()`）。翻译器只透传，出口按「最后一份有数据的」结算，
  **绝不在出口重算落库值**。
- **出站展示**：`message_start` / `message_delta` 内的 usage（`input_tokens` 不含缓存 +
  `cache_read_input_tokens` + `cache_creation_input_tokens`）由转换器按展示口径自算，
  仅用于下游展示，**不参与落库**。
- 两条轨道分离理由：落库口径与协议无关、与直连一致（一处 SUM 不被按行区分）；
  展示口径随下游协议变化。

精确的出口判据为**最后一份有数据的（非 null 且非 `UsageTokens.EMPTY`）**；不逐帧相加。
展示换算为 `max(0, prompt_tokens - cache_read - cache_creation)`，output 取 completion；
缓存命中字段优先级复用 `OpenAiUsageParser`（标准嵌套 → DeepSeek 命中字段 → effectiveCachedTokens，
从不读顶层 cached_tokens）。写入字段取首个存在的 `prompt_tokens_details.cache_write_tokens` /
`cache_creation_tokens` / 顶层 `cache_creation_input_tokens`，缺失不是实际 0；显式 0 不触发别名回退。
流式用 Chat 后到快照覆盖，不能照搬 Anthropic 的「正数才覆盖」合并。无原生槽不从 JSON
补造记账，无上游 usage 不写明细，即使 Messages 必需展示字段给了 0 占位也一样。

**帧日志与 usage 分开**：`ChunkLogPayload` 保存 translated/upstream/frameCounts，
独立状态重放且每个上游事件都有一个产帧数（含零帧）。EOF 追加帧单列收尾段；
由 `[DONE]` 或 usage 尾帧触发的产出归该事件。Chat 的 upstream 视图是既有清洗后的
Chat 帧，并不声称逐字节原始网络数据；`usage_raw` 始终是清洗/翻译前提取的一份上游 usage。

既有批量重译接口只收帧列表，不含 error/cancel 的结束原因：正常完整流可与实际输出对照，
失败/取消日志可能包含按正常 EOF 推导的补帧，不能当作这些帧确实发给了下游的证据。
这一观测边界不改变实际出站的异常传播或原生 usage 记账，见
[已知技术债](../../architecture/KNOWN_DEBT.md#十三其余轻微项)。

## 10. 非流式映射

`choices[0].message` → Anthropic message：

| 字段 | 映射 |
|---|---|
| `reasoning_content` | `thinking` 块 |
| `content`（字符串/数组） | `text` 块 |
| `tool_calls[]` | `tool_use` 块（`arguments` 解析为对象，失败 → `{}`） |
| `finish_reason` | `stop_reason`（表见第 8 节） |
| `usage` | 展示口径换算 |
| `id` | **透传上游 id，不改写为 `msg_*`** |
| `model` | **透传上游模型名，不在回程做转换**（下游前缀路由属请求侧语义） |
| 空/缺失 choices 或 0 字节 body | 原生 gate 先判空并按预算处理，耗尽放行后才构造空 text + `end_turn`，不再加一次翻译重试 |
| reasoning-only（无工具） | 保留 thinking，同时复制已有思考为 text 兜底；有工具不补正文 |

工具 `input` 必须是对象：完整 arguments 能解析成对象则保留，否则用 `{}`（非流式已确认的
兼容规则，与流式不解析分片刻意不同）。非法 JSON、非对象根、错误 choices/message 容器
为结构性翻译错误（502），不与空响应混同。非流式只保留首个候选；文本数组内部顺序保持。

## 11. thinking 块与签名

- OpenAI 响应无签名概念，转出的 `thinking` 块**不带 `signature`**（四家参考均不生成；
  遵循 ADR-0012 不伪造内容）。
- 不带签名是否触发下游客户端报错，由实机联测确认；不伪造无依据签名。

## 12. 错误与控制事件

- HTTP 上游错误沿既有错误解包/渲染路径，保留上游状态码与错误体，翻译器不换头/改道重试。
- 非流式结构错误为 502；流式结构错误或流内 error 走已有 `event:error` 路径，
  **不追加正常 message_delta/message_stop**，以免出口把失败记成 COMPLETED。
- 取消不追加成功收尾；正常 EOF 才走 `finalizeStream`。错误渲染与心跳仍在出口，翻译层不造 SSE 信封。
- 空响应判定复用既有口径（`EmptyUpstreamResponseException` + 重试预算）。

## 13. 待实测确认项

以下三项在实现后必须实机联测（mock + 真实 Claude 客户端）：

1. 无签名 `thinking` 块对 Claude Code / Copilot 的容忍度。
2. 无参工具补 `{}` 的 `input_json_delta` 是否被下游正确拼装。
3. 上游裸模型名回显在下一轮路由中是否可用（对齐 M2C 响应契约第 7 节勘误）。

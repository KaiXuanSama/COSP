# 协议翻译契约（响应侧 · M2C 链路）

> **方向**：本契约覆盖 **M2C 链路（下游 /v1/messages · 上游 Chat）** 的**响应回程**：
> 上游 OpenAI Chat Completions 响应（`chat.completion` / `chat.completion.chunk`）→
> 下游 Anthropic Messages 事件流（`message_start` / `content_block_*` / `message_delta` / `message_stop`）。
>
> **易混警告**：歧 `C2M-RESPONSE-CONTRACT.md` 覆盖的是 **C2M 链路（下游 Chat · 上游 Messages）**
> 的响应方向（Anthropic Messages 响应 → OpenAI Chat 响应，即 M2C 响应）。两条链方向相反，
> **不得在此文件续写**，也不要把本节内容并入 `C2M-RESPONSE-CONTRACT.md`。
>
> 状态：**契约已定稿（ADR-0015 Accepted），实现未开始**。本文档是设计契约与测试矩阵来源；
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
  → ★ 本契约：响应翻译（Chat → Messages，待实现）
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
| `choices[0].delta.reasoning_content`（非空） | `content_block_start(thinking)` + `content_block_delta(thinking_delta)` 流 | 1+ |
| `choices[0].delta.content`（非空） | `content_block_start(text)` + `content_block_delta(text_delta)` 流 | 1+ |
| `choices[0].delta.tool_calls[]` | `content_block_start(tool_use)` + `content_block_delta(input_json_delta)` 流 | 1+ |
| `choices[0].delta` 空 | 不产帧 | 0 |
| `choices[0].finish_reason` | 仅记录，暂不发 `message_delta` | 0 |
| `usage`（trailing usage-only chunk / 任意 chunk） | 合并入状态 | 0 |
| `[DONE]` / 流自然结束 | `message_delta`（stop_reason + usage）+ `message_stop` | 2 |

## 4. 跨帧状态（参考 new-api `ClaudeConvertInfo`）

必须持有：

- `lastMessagesType`：`none` / `text` / `thinking` / `tools`
- `index`：Anthropic content block 当前索引
- 工具映射：OpenAI `tool_calls[].index` → Anthropic block index（独立索引域，必须显式映射）
- 每个工具：`BlockIndex / ID / Name / PendingArguments / Started`
- `finishReason`、`done`、usage 四值（input/output/cacheRead/cacheWrite）

## 5. 交错内容缓冲（借鉴 CLIProxyAPI）

工具块打开期间到达的文本/思考增量**不得直接发帧**（Anthropic 要求块严格顺序）。
缓冲为 `InterleavedContentChunk(type, text)`，finalize 时按原顺序重放为独立块
（`start → delta → stop`）。

## 6. message_delta 延迟单发（借鉴 cc-switch / CLIProxyAPI）

- 上游 `finish_reason` 到达：只记录 `stop_reason`，不立即发。
- `[DONE]` / 流自然结束 / trailing usage-only chunk：此时才发唯一一次 `message_delta` + `message_stop`。
- 多次 `finish_reason` 去重；错误事件不伪装成成功完成。

## 7. 工具调用映射

- `content_block_start(tool_use)` 延迟到 `id` + `name` 齐备才发；此前到达的 `arguments`
  分片缓冲，start 后立即 flush 为 `input_json_delta`。
- `arguments` 分片**原样透传**，不中途解析（切点可能落在 `\"` / `\uXXXX` 中间）。
- **工具名缺失**：生成兜底名（形态由实现定，原则：避免丢失合法工具调用）。
- **无参工具 close 时补 `{}` 的 `input_json_delta`**（按 sub2api，防客户端只靠 delta 拼输入）。

## 8. finish_reason → stop_reason 映射

| OpenAI finish_reason | Anthropic stop_reason |
|---|---|
| `stop` | `end_turn` |
| `length` | `max_tokens` |
| `tool_calls` | `tool_use` |
| `content_filter` | `end_turn` |
| 未知 | `end_turn`（记 warning） |

## 9. usage：落库与展示双轨

- **落库**：始终取**上游 Chat 原生 usage**（`OpenAiUsageParser` 解析；由 `GenericOpenAiChatService`
  在流内挂到 `UpstreamEvent.usage()`）。翻译器只透传，出口按「最后一份非 null」结算，
  **绝不在出口重算落库值**。
- **出站展示**：`message_start` / `message_delta` 内的 usage（`input_tokens` 不含缓存 +
  `cache_read_input_tokens` + `cache_creation_input_tokens`）由转换器按展示口径自算，
  仅用于下游展示，**不参与落库**。
- 两条轨道分离理由：落库口径与协议无关、与直连一致（一处 SUM 不被按行区分）；
  展示口径随下游协议变化。

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
| 空 choices | 构造空响应 `end_turn`；reasoning-only 回退为 text（避免空回合），但**不掩盖「上游返回空 choices」**——仍走既有空响应判定与重试口径 |

## 11. thinking 块与签名

- OpenAI 响应无签名概念，转出的 `thinking` 块**不带 `signature`**（四家参考均不生成；
  遵循 ADR-0012 不伪造内容）。
- 不带签名是否触发下游客户端报错，由实机联测确认；不伪造无依据签名。

## 12. 错误与控制事件

- 流中上游 error：SSE 头未发 → 透传状态码与错误体；已发 chunk → 终止帧 + `message_stop` + 日志记录。
- 空响应判定复用既有口径（`EmptyUpstreamResponseException` + 重试预算）。

## 13. 待实测确认项

以下三项在实现后必须实机联测（mock + 真实 Claude 客户端）：

1. 无签名 `thinking` 块对 Claude Code / Copilot 的容忍度。
2. 无参工具补 `{}` 的 `input_json_delta` 是否被下游正确拼装。
3. 上游裸模型名回显在下一轮路由中是否可用（对齐 M2C 响应契约第 7 节勘误）。

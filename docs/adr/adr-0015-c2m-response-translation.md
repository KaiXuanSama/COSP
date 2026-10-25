# ADR-0015: C2M 回程响应翻译采用状态机、交错缓冲与延迟收尾，落库保持上游原生 usage

- **状态**：Accepted
- **日期**：2026-10-25
- **取代**：（无；本决策对应 AGENTS.md 所述「C2M 响应回程仍未实现」，补齐 (MESSAGES, CHAT) 链路的响应半轮）
- **相关**：
  [ADR-0012 M2C 去程](./adr-0012-m2c-request-translation.md)、
  [ADR-0013 output_format 别名](./adr-0013-m2c-output-format-alias.md)、
  [ADR-0014 三字段省略](./adr-0014-m2c-drop-server-fields.md)、
  [响应侧契约（M2C 已落地）](../reference/contracts/C2M-RESPONSE-CONTRACT.md)、
  [C2M 请求契约](../reference/contracts/C2M-REQUEST-CONTRACT.md)、
  [M2C 链路响应契约（C2M 响应，本决策的实现契约）](../reference/contracts/M2C-RESPONSE-CONTRACT.md)

## 背景

`(MESSAGES, CHAT)` 链路（下游 Anthropic Messages、上游 OpenAI Chat）目前已注册**去程请求翻译**
（`MessagesToChatRequestTranslator`），但**响应回程仍未实现**：下游 `POST /v1/messages` 请求经
COSP 转发给上游 `chat.completion` 后，上游返回 OpenAI 形态响应（JSON 或 SSE），下游 Anthropic
客户端无法解析，链路处于受控半轮开发态，不可用于正常客户端会话。

四个参考项目（new-api / CLIProxyAPI / cc-switch / sub2api）均已实现「OpenAI Chat 响应 →
Anthropic Messages 响应」的回程翻译，方向与本需求完全一致。调研结论（2026-10-25）：

- **事件序列骨架完全一致**：`message_start → content_block_start/delta/stop（thinking/text/tool_use）→ message_delta(usage) → message_stop`。
- **跨帧状态不可避免**：OpenAI `tool_calls[].index` 与 Anthropic `content_block.index` 是两个独立索引域，必须显式映射；thinking/text 块占用中间索引。
- **参数分片不解析**：`input_json_delta` 须原样透传，切点可能位于 `\"` / `\uXXXX` 中间。
- **块切换先 stop 再 start**：每流只有一个 open 块；工具调用期间的交错文本/思考必须缓冲，不能现到现发。
- **message_delta 只发一次且延迟**：每流只能有一个 `message_delta`，重复会导致 Claude Code 中止连接；须等 usage 集齐 / `[DONE]` 再统一收尾。
- **usage 双轨**：上游 Chat 原生 usage（`prompt_tokens`/`completion_tokens`/`cached_tokens`）与本服务落库口径「总输入含缓存命中与写入、cached 仅命中」天然一致，直接沿用 `OpenAiUsageParser`；而 Anthropic 客户端展示形态（`input_tokens` 不含缓存 + `cache_read/cache_creation`）是另一条输出轨道。

## 决策

**C2M 回程响应翻译采用有状态转换器：状态机参考 new-api `ClaudeConvertInfo`，交错内容缓冲借鉴 CLIProxyAPI，收尾借鉴「message_delta 延迟单发」；落库始终用 chunk 翻译前的上游原生 usage，翻译器只传递不重算。**

### 1. 流式状态机参考 new-api

新增跨帧状态对象（参考 `convmeta.ClaudeConvertInfo`），至少持有：

- `lastMessagesType`：`none` / `text` / `thinking` / `tools`，当前打开的块类型
- `index`：Anthropic content block 当前索引（单调递增，工具块也占位）
- `toolCallBaseIndex` + `toolCallMaxIndexOffset` + `toolCalls[]`：OpenAI tool index → Anthropic block index 显式映射；每个工具记录 `BlockIndex / ID / Name / PendingArguments / Started`
- `finishReason`、`done`：终止语义与幂等

块切换规则：**先 stop 旧块、再 start 新块**；工具块在 `id` + `name` 齐备后才 `content_block_start`，此前到达的 `arguments` 分片缓冲，start 后立即 flush为 `input_json_delta`。

### 2. 交错内容缓冲（借鉴 CLIProxyAPI）

工具调用打开期间到达的文本/思考增量不得直接发帧（Anthropic 要求块严格顺序）。缓冲为
`InterleavedContentChunk(type, text)`，在 finalize 阶段按原顺序重放为独立块
（`start → delta → stop`）。这保证「先思考、再调工具、再输出」等交错场景不污染工具参数。

### 3. message_delta 延迟单发（借鉴 cc-switch / CLIProxyAPI）

- 上游 `finish_reason` 到达：**不立即发** `message_delta`，仅记录 `stop_reason` 状态。
- `[DONE]` / 流自然结束 / trailing usage-only chunk（choices 为空但带 usage）：此时才发唯一一次 `message_delta`（`stop_reason` + 合并后的 usage）+ `message_stop`。
- 多次 `finish_reason` 去重；错误事件不得伪装成成功完成。

### 4. 非流式映射

`choices[0].message`：`reasoning_content` → `thinking` 块；`content`（字符串或数组）→ `text` 块；`tool_calls` → `tool_use` 块（`arguments` 解析为对象，失败 → `{}`）；`finish_reason` → `stop_reason`：
`stop→end_turn`、`length→max_tokens`、`tool_calls→tool_use`、`content_filter→end_turn`（已定稿，见第 6 节）。

### 5. usage：落库永远用翻译前的上游原生值

这是本决策最关键的一条约束：

- **落库数据**：始终取**上游 Chat 原生 usage**（`OpenAiUsageParser` 解析 `prompt_tokens`/`completion_tokens`/`cached_tokens`，由 `GenericOpenAiChatService` 在流内挂到 `UpstreamEvent.usage()`）。翻译器**只透传**：`translateStream` 记录最后一份非 null 的上游 usage，在分类后 `withUsage(...)` 携带，出口按「最后一份非 null」结算。**绝不在出口重算落库值**。
- **出站展示**：Anthropic 客户端看到的 `message_start` / `message_delta` 内的 usage（`input_tokens` 不含缓存 + `cache_read_input_tokens` + `cache_creation_input_tokens`）由转换器按展示口径自算，仅用于下游展示，**不参与落库**。
- 两条轨道分离的理由：落库口径与协议无关、与直连一致（一处求和查询 SUM 不被按行区分）；展示口径随下游协议变化。参考 `MessagesToChatResponseTranslator` 既有注释：「那是『给下游看什么』，与『记账用什么』是两件事」。

### 6. 已确认细项（决策落成）

四个参考项目并不处处一致。以下分歧点在实现契约与测试矩阵中会直接暴露，
本 ADR 已逐项定稿，后续按此实现：

1. **下游 `model` 回显：保持中立，透传上游模型名，不在回程翻译做转换。**
   - 下游为指定供应商会在模型名前加供应商标识，这是请求侧路由语义；回程不应改写。
   - 与既有 M2C 同口径（M2C 代码透传上游裸名，实测三家下游 agent 对裸名无问题）。
2. **thinking 块不带 `signature`。**
   - OpenAI 响应无签名概念；四家参考均不生成签名。
   - 遵循 ADR-0012「忠实映射、不伪造内容」。不带签名是否触发客户端报错，
     由实机联测确认；不因此伪造无依据签名。
3. **工具块模型：单块 + 交错缓冲。**
   - **澄清**：「单块」不等于「把整轮内容缓冲后合成一个 chunk 下发」——那是
     sub2api 收集型路径，不是本决策。本决策是流式逐帧转换：同一时刻只有
     一个 open content block，工具块打开期间到达的文本/思考缓冲、finalize 时
     按序重放为独立块。
   - new-api 的多块并行（`ToolCallBaseIndex` + 多块 stop）不采用。
4. **`content_filter` → `end_turn`。**
   - Anthropic 无 refusal 语义；cc / CLI / sub2api 三家一致（new-api 用 `refusal` 不采用）。
5. **工具名缺失：生成兜底名。**
   - 参考 CLI `tool_<index>` / cc `unknown_tool`；具体形态在实现契约定，
     原则是避免丢失合法工具调用。
6. **无参工具 close 时补 `{}` 的 `input_json_delta`。**
   - 按 sub2api：防客户端只靠 delta 拼输入；需 Claude Code 实机确认空参数容忍度。
7. **OpenAI `id`（`chatcmpl-*`）透传，不改写为 `msg_*`。**
   - 与 M2C 一致（M2C 也透传上游 `msg_xxx`）；CLI 同。
8. **非流式空 choices / reasoning-only 响应：空响应兜底 + thinking 回退为 text。**
   - 构造空响应 `end_turn`（sub2api），reasoning-only 回退为 text 避免空回合；
   - 但不掩盖「上游返回了空 choices」——仍走既有空响应判定与重试口径。

## 后果

**正面**

- 补齐 (MESSAGES, CHAT) 响应半轮，链路端到端可用，下游 Anthropic 客户端可正常解析。
- 状态机与交错缓冲设计沿用参考项目验证过的成熟路径，工具参数分片跨帧拼接正确。
- message_delta 延迟单发避免「重复 message_delta 导致下游 abort」与 usage 缺失。
- 落库保持上游原生 usage，`api_call_usage` 与 `api_usage_daily` 口径不变，汇总查询不受协议分支影响。

**负面**

- 引入跨帧状态为与移交翻译器，复杂度显著高于 M2C（后者 Chat 协议无块生命周期）。
- 交错缓冲增加内存与延迟（工具期间文本延后输出）。
- 与 M2C 响应翻译代码量不对称，需要更仔细的块生命周期测试（含交错、分片、迟到工具名）。

## 备选方案

### 直接透传上游 OpenAI 响应
拒绝：下游是 Anthropic 客户端，收到 `chat.completion` / `chat.completion.chunk` 无法解析；
SSE 帧协议不同，链路不可用。

### 不实现回程，维持半轮态
拒绝：半轮态仅是受控开发态，不能用于正常客户端会话或发布；本决策即补上这一半。

### 逐帧即时映射（不缓冲交错内容）
拒绝：工具块打开期间到达的文本若直接发帧，违反 Anthropic「块严格顺序」要求，
会把文本拼进工具参数或导致下游拼出废串。

### 落库使用翻译后的展示 usage
拒绝：展示形态（input_tokens 不含缓存）与全库口径（prompt=总输入）不一致，
会让 `SUM(prompt_tokens)` 按行承载两种定义，无法对账；且翻译器在重试边界之外、
无法提供每轮起止信息，落库应留在上游执行器。

## 参考

- [new-api `to_claude_messages_resp.go`](../../reference/new-api/relaykit/relayconvert/internal/oai_chat/to_claude_messages_resp.go)（状态机 `ClaudeConvertInfo`）
- [CLIProxyAPI `openai_claude_response.go`](../../reference/CLIProxyAPI/internal/translator/openai/claude/openai_claude_response.go)（交错缓冲 `InterleavedContentChunks`）
- [cc-switch `streaming.rs`](../../reference/cc-switch/src-tauri/src/proxy/providers/streaming.rs)（message_delta 延迟单发、截止空白防护）
- [sub2api `chatcompletions_anthropic_bridge.go`](../../reference/sub2api/backend/internal/pkg/apicompat/chatcompletions_anthropic_bridge.go)（空响应兜底）
- [响应侧契约](../reference/contracts/C2M-RESPONSE-CONTRACT.md)（M2C 已落地，事件序列/索引域/usage 口径均可镜像）
- [UpstreamEvent.usage() 语义](../../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/protocol/UpstreamEvent.java)

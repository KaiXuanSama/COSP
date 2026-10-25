# RFC：C2M 回程响应翻译（上游 Chat → 下游 Messages）

- **日期**：2026-10-25（立项日）
- **状态**：已定稿，实施待开始（结论已晋升 [ADR-0015](../../adr/adr-0015-c2m-response-translation.md)）
- **相关 ADR**：[ADR-0015：C2M 回程响应翻译](../../adr/adr-0015-c2m-response-translation.md)、
  [ADR-0012：M2C 去程](../../adr/adr-0012-m2c-request-translation.md)、
  [ADR-0013：output_format 别名](../../adr/adr-0013-m2c-output-format-alias.md)、
  [ADR-0014：三字段省略](../../adr/adr-0014-m2c-drop-server-fields.md)
- **前置文档**：
  [M2C/C2M 链路总 RFC](./2026-10-20-m2c-c2m-translation.md)（去程已实现，本文是回程专项）、
  [M2C 链路响应契约（C2M 响应）](../../reference/contracts/M2C-RESPONSE-CONTRACT.md)、
  [C2M 链路响应契约（M2C 响应）](../../reference/contracts/C2M-RESPONSE-CONTRACT.md)、
  [协议翻译四方向 RFC](./2026-09-03-protocol-translation.md)

> 本文定位：**C2M 回程（上游 OpenAI Chat 响应 → 下游 Anthropic Messages 响应）** 的专项档案。
> 它是 `2026-10-20-m2c-c2m-translation.md` 中「C2M 回程仍未实现」那一半的展开：
> 参考项目调研、决策、实现面盘点、实施计划与验证判据。
> 本文不重复 M2C 去程的内容；方向约定沿 [链路总 RFC](./2026-10-20-m2c-c2m-translation.md) §1
> （回程缩写 `C2M 响应` = 上游 Chat → 下游 Messages，声明 `(MESSAGES, CHAT)`）。

---

## 1. 背景：这条链缺什么

`(MESSAGES, CHAT)` 链路（下游 `/v1/messages` · 上游 Chat）：

```text
下游 Claude 客户端（POST /v1/messages）
  → ★ 请求翻译（Messages → Chat，已实现：MessagesToChatRequestTranslator）
  → 上游 OpenAI Chat（chat.completion / SSE）
  → ★ 本 RFC：响应翻译（Chat → Messages，未实现）
  → 下游 Anthropic Messages 事件流
```

现状：去程已注册（ADR-0012/0013/0014），回程缺失 → `AfterSend.warnIfHalfRound`
透传上游原生 Chat chunk，下游 Claude 客户端无法解析（界面卡住 / 无输出）。
本文实现的是四参考项目均已实现的「上游 OpenAI Chat 响应 → 下游 Anthropic 响应」。
协议 `ProtocolTranslator` 实现现状已列出该缺口（`TODO(待实现)`）。

## 2. 参考项目调研面（结论）

四参考项目（new-api / CLIProxyAPI / cc-switch / sub2api）均已实现该方向，核心共识：

1. **事件序列骨架一致**：`message_start → content_block_start/delta/stop（thinking/text/tool_use）→ message_delta(usage) → message_stop`。
2. **跨帧状态不可避免**：OpenAI `tool_calls[].index` 与 Anthropic `content_block.index` 是独立索引域，必须显式映射；thinking/text 块占用中间索引。
3. **参数分片不解析**：`input_json_delta` 原样透传（切点可能在 `\"` / `\uXXXX` 中间）。
4. **块切换先 stop 再 start**：Anthropic 要求块严格顺序；工具调用期间交错文本/思考必须缓冲，finalize 重放。
5. **message_delta 只发一次且延迟**：重复会 abort Claude Code；等 usage 集齐 / `[DONE]` 再收尾。
6. **usage 双轨**：上游 Chat 原生 usage 与 C 服务落库口径一致（总输入含缓存、cached 仅命中）；Anthropic 展示形态（input 不含缓存 + cache_read/cache_creation）是另一条输出轨道。

各项目差异与定稿见 [ADR-0015 §6](../../adr/adr-0015-c2m-response-translation.md)（八项已确认）。

## 3. COSP 实现面盘点（落地点）

### 3.1 主干：零改动

| 组件 | 角色 | 本功能改动 |
|---|---|---|
| `TranslatorRegistry` | 集合注入 + 按方向建两张表，新 `@Component` 自动进入 | **零改动** |
| `BeforeSend.translateStep` | 查去程/回程翻译器，记入 ctx | 零改动（新 Bean 自动命中回程表） |
| `AfterSend.process` | 从 `ctx.responseTranslator()` 读，套在执行器外侧 | 零改动 |
| `UpstreamEventClassifier` | 按产出协议分类：`message_stop` → Terminal | 零改动（MESSAGES 已有） |
| `UpstreamExecutor` | `invoke`/`invokeStream` + `chunkRewriter` | 零改动 |
| `GenericOpenAiChatService` / `AbstractUpstreamChatService` | 上游执行器（upstream=CHAT） | 需核对否定 `chunkRewriter` 已透传至落库（见 3.3） |
| `ChatContentDetectorStage` / `ChatChunkNormalizeStage` | 空响应判定/归一化（upstream=CHAT） | 零改动 |
| `AnthropicController` | 下游 SSE 映射 / `extractEventType` | 零改动 |
| `GatewayAuthFilter` | 已保护 `/v1/messages` | 零改动 |

### 3.2 新增组件清单

| 组件 | 职责 | 位置 |
|---|---|---|
| `ChatToMessagesResponseTranslator` | 回程翻译器，`@Component`，声明 `(MESSAGES, CHAT)`；实现 `ResponseProtocolTranslator`，非流式/流式/落库重译三方法 | `pipeline/protocol/translate/` |
| `C2MStreamState` | 跨帧状态（参考 `convmeta.ClaudeConvertInfo`）：`lastMessagesType`/`index`/工具映射/`finishReason`/`done`/usage 四值 | `pipeline/protocol/translate/` |
| `ChatToMessagesNonStreamTranslator` | 非流式：`choices[0].message` → Anthropic message | `pipeline/protocol/translate/` |
| `ChatToMessagesStreamTranslator` | 流式：SSE chunk → 0..n 个 Anthropic 事件 | `pipeline/protocol/translate/` |
| `C2MUsageShowConverter`（或并入状态机） | Anthropic 展示口径换算（input 不含缓存 + cache_read/cache_creation）—— 仅展示，不参与落库 | `pipeline/protocol/translate/` |

**命名与注册**：类名 `ChatToMessagesResponseTranslator`（上游 Chat → 下游 Messages），
`downstreamProtocol()=MESSAGES`、`upstreamProtocol()=CHAT`。这与 M2C 响应（`MessagesToChatResponseTranslator`）
方向相反，勿混淆。

### 3.3 关键接线：usage 双轨与落库

- **落库**：上游 Chat 原生 usage 由 `GenericOpenAiChatService` 在流内挂到 `UpstreamEvent.usage()`；
  翻译器只透传（`carriedUsage` 模式），出口按「最后一份非 null」结算。**不重算**。
- **展示**：`message_start`/`message_delta` 内的 usage 由展示换算器自算，仅下游可见。
- **落库重译**：`AfterSend.chunkRewriterFor` 调用 `translateChunksForLog`，用独立新状态重放一遍（幂等）。
- **需核对**：`GenericOpenAiChatService` 侧是否已把 `chunkRewriter` 参数透传至 `ApiCallLog` 写入
  （M2C 阶段已为 Anthropic 执行器补过；Chat 执行器的同一参数路径需确认）。见 §5 验证。

### 3.4 空响应与重试

- 空响应判定复用既有口径（`ChatContentDetectorStage`，upstream=CHAT）；翻译在内侧会让判定器看到合成 chunk，
  **必须套在 `retryWhen` 之外**（与 M2C/R2C 同原则）。
- `AfterSend` 的半轮告警：回程 Bean 注册后自动消失。

## 4. 决策摘要（已定稿，ADR-0015 §6）

| # | 决策 |
|---|---|
| 1 | model 保持中立，透传上游模型名，不在回程转换（下游前缀路由属请求侧） |
| 2 | thinking 块不带 signature（四家参考一致；实测确认客户端容忍度） |
| 3 | 工具块模型：单块 + 交错缓冲（**非**整轮收集后合成单 chunk） |
| 4 | `content_filter → end_turn` |
| 5 | 工具名缺失生成兜底名（形态实现契约定） |
| 6 | 无参工具 close 补 `{}` `input_json_delta`（sub2api 式） |
| 7 | OpenAI `id` 透传，不改写 `msg_*` |
| 8 | 非流式空 choices → 空响应 `end_turn`；reasoning-only 回退 text；仍走既有空响应判定 |

## 5. 实施计划

### 阶段 A：单测 + 状态机骨架

1. `C2MStreamState`：`lastMessagesType`/`index`/工具映射（`BlockIndex/ID/Name/PendingArguments/Started`）/`finishReason`/`done`/usage 四值。
2. `ChatToMessagesStreamTranslator`：
   - `message_start`：首帧发（id/model/usage 占位）
   - `content_block_start(tool_use)`：延迟到 id+name 齐备；pending args 在 announce 后 flush
   - `content_block_delta`：thinking/text/tool 参数分片原样透传
   - 交错缓冲：工具块打开期间文本/思考 → `InterleavedContentChunk`，finalize 重放
   - `message_delta`：延迟单发（finish 只记录；`[DONE]`/流结束/trailing usage chunk 才发）
   - 工具名缺失兜底、无参工具补 `{}`
3. `ChatToMessagesNonStreamTranslator`：非流式映射 + 空 choices 兜底。
4. 翻译器装配：`ChatToMessagesResponseTranslator` `@Component`，三方法。

**测试**（单测，参考 `MessagesToChatStreamTranslatorTests`）：
- 帧数对照（0/1/多帧）、块切换 stop→start、交错缓冲重放、迟到工具名、参数分片跨帧拼接
- finish 延迟单发、trailing usage-only chunk、无 `[DONE]` 兜底
- usage 双轨：落库透传 + 展示换算（含 cache 减法）
- 非流式：reasoning-only 回退、空 choices
- `TranslatorRegistry`：注册后 `(MESSAGES, CHAT)` 回程命中

### 阶段 B：mock 场景扩展与实机联测

| 场景 | 压什么 |
|---|---|
| `translate-c2m-baseline-*` | 端到端（Chat → Messages 事件流） |
| `translate-c2m-tool-split-*` | 参数分片断言帧（双索引域映射） |
| `translate-c2m-interleaved-*` | 交错文本/思考缓冲重放 |
| `translate-c2m-late-tool-name-*` | 工具名延迟到达 |
| `translate-c2m-no-args-*` | 无参工具补 `{}` |
| `translate-c2m-no-done-*` | 无 `[DONE]` 流结束兜底 |
| `translate-c2m-usage-only-*` | trailing usage-only chunk |
| `translate-c2m-empty-choices-*` | 非流式空 choices / reasoning-only |

> mock 扩展在 `tools/mock-upstream/protocols/chat.js` 侧实现（上游 Chat 场景），
> 供 `mock-cosp` 下游嗅探与真实 Claude 客户端联测。

### 阶段 C：验证与回归

- 聚焦测试 + 全量回归（Maven）
- 实机联测（`cosp-live-mock-testing-skill`）：mock 上游 + `mock-cosp` + 真实 Claude Desktop / Claude CLI
- 核对 `chunkRewriter` 落库路径（§3.3），确认落库仍是上游原生 usage
- 更新响应契约（M2C-RESPONSE-CONTRACT.md 已定稿，若实现偏离需同步）

## 6. 风险与边界

- **无签名 thinking**：若 Claude 客户端拒绝无签名块，需回头评估（ADR-0015 第 6.2 已标记实测项）。
- **参数分片**：上游「切不切/怎么切」无共识，必须原样透传、不中途解析。
- **交错缓冲**：增加内存与延迟（工具期间文本延后输出）；但这是块顺序约束的代价。
- **空响应判定**：翻译必须在 `retryWhen` 之外；`ChatContentDetectorStage` 看上游原生形态。
- **落库口径**：展示换算绝不参与落库；`SUM(prompt_tokens)` 定义保持唯一。
- **半轮态**：注册回程前，`AfterSend.warnIfHalfRound` 依然告警；本项目上线前必须完成阶段 B 联测。

## 7. 验收判据

- 单测：聚焦测试全绿；覆盖状态机、交错、延迟收尾、salt 双轨。
- 实机：Claude Desktop / Claude CLI 经 COSP 直连 mock Chat 上游，正常显示流式/非流式回复、工具调用、usage。
- 落库：`api_call_log` 落的是上游原生 Chat chunk（`translated` 字段存在）；`api_call_usage` prompt_tokens=总输入、cached=命中。
- 回归：全量 Maven 测试通过；前端构建通过。

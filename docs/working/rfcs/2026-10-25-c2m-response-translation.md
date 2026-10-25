# RFC：C2M 回程响应翻译（上游 Chat → 下游 Messages）

- **日期**：2026-10-25（立项日）
- **状态**：实施中 —— [阶段 A](#c2m-stage-a) 已实现并注册、自动化通过；[三家真实上游实机存档](#c2m-live-2026-10-25) 已记录（2026-10-25）；阶段 B mock/客户端实机与后续验收未开始（决策见 [ADR-0015](../../adr/adr-0015-c2m-response-translation.md)）
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
>
> 下文 §1–§7 保留立项时的调研与计划，不据其中「未实现/需核对」推断当前状态。
> 实际组件、接线纠错和验证证据统一见 [阶段 A 实施记录](#c2m-stage-a)；字段规格只维护在
> [M2C 链路响应契约](../../reference/contracts/M2C-RESPONSE-CONTRACT.md)。

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

---

<a id="c2m-stage-a"></a>

## 8. 阶段 A 实施记录（2026-10-25）

### 8.1 本轮完成范围

用户授权实施阶段 A：纯转换、流式状态机、注册、日志接线与自动化测试。
本轮**未**启动 COSP/mock 服务，未访问 live endpoint，未读写工作区 `admin.db`，
未改前端、schema、依赖或供应商配置，也未执行 git 提交/推送。
已接受的 ADR-0015 正文保持不变。

新增回程 Bean `ChatToMessagesResponseTranslator`（`pipeline/protocol/translate/`）：

| 组件 | 实际职责 |
|---|---|
| `ChatToMessagesResponseTranslator` | 声明 `(MESSAGES, CHAT)`，三方法：非流式、流式、独立状态日志重放；输出按 MESSAGES 重新分类 |
| `C2MStreamState` | 订阅级身份、当前单块、显式工具 index 映射、逐片待发参数、交错文本/思考、finish/finalized、展示 usage 快照 |
| `ChatToMessagesStreamTranslator` | 一帧 → 零/一/多帧，逐片参数与单块切换、延迟 message_delta、正常 EOF 收尾，异常/取消不补成功完成 |
| `ChatToMessagesNonStreamTranslator` | 首候选 → Messages message，保留上游 id/model，无签名 thinking，非流式对象参数/空响应与纯思考兜底 |
| `ChatToMessagesUsageConverter` | 非流式/流式共用的出站 usage 换算；命中字段复用 OpenAiUsageParser，不改原生记账槽 |
| `AnthropicResponseShapes` | 两态共用 Messages 外壳、文本/思考/工具形状及停止原因映射 |

字段和帧的精确规则见响应契约，不在本记录重建另一份映射表。
其中**单块不是合片**：首工具参数实时逐片下发；其它工具保留原分片排队，
工具组结束后依次输出；交错正文/思考保留自己的片边界与顺序，不向已 stop 的块发 delta。
工具缺名的实现形态为 `tool_<上游 index>`，只在收尾仍缺时补；无参数补 `{}` 仅一次。
工具 ID 从未提供时按消息 id + 工具 index 生成稳定 UUID 占位，同一消息日志重放一致、
不同消息不复用 ID（否则下一轮去程的全请求重复 ID 校验会拒绝历史）。上游有 id 时仍原样透传。

### 8.2 接线复核与立项记录纠错

- §3.1 的主干复用已证实：`BeforeSend` / `AfterSend` / `TranslatorRegistry` /
  `UpstreamExecutorRegistry` 的运行逻辑无改动；新增 Bean 即两张表同键命中，
  RESPONSE_TRANSLATION 自动登记，原生 Chat 判空恢复介入。
- **Chat 日志 rewriter 确有漏传**：`GenericOpenAiChatService.invokeStream` 接收却未传，
  `AbstractUpstreamChatService.chatCompletionStream` 未有该参数，四条流式日志分支写死 null。
  已贯通成功、HTTP 错误、网络失败、空响应耗尽四分支；非流式保留上游响应体存档。
- §3.3 的「最后一份非 null」应按实际出口读成**最后一份有数据的（非 null 且非空）**。
  记账解析只在执行器；新回程携带槽快照但不从翻译 JSON 补造或改写指标。
  展示扣缓存不进入 `api_call_usage` 或 `api_usage_daily`，`usage_raw` 仍取上游对象。
- §7 的帧日志描述应按既有 `ChunkLogPayload` 解释：一列内是
  `translated / upstream / frameCounts` 对象，而非新数据库列；Chat 的 upstream 视图是既有
  清洗后的 Chat 帧，不宣称逐字节原始网络数据，usage 提取位仍在清洗/翻译之前。
- 日志重放接口不带 error/cancel 原因，仍可能按正常 EOF 推导结束帧；
  本轮只对正常完整流断言 translated 与实际输出一致，不据失败/取消日志推断真实下发。
  跨译器的接口改进记入 [KNOWN_DEBT](../../architecture/KNOWN_DEBT.md#十三其余轻微项)，
  不以此改变实际流的 error/cancel 语义或 usage 记账。
- **非流式 Chat fallback 补最小保护**：保留已接受的纯思考正文兜底，但工具轮不复制思考为正文，
  非空文本数组不被思考替换。该清洗发生在回程之前，必须钉住否则纯译器测试会假绿。
- 已有 `AnthropicController` 与 `UpstreamErrorRenderer` 接受 ResponseTranslationException：
  非流式 502、流式 `event:error`；无需新增控制器运行逻辑。
  正常 EOF 才补完成帧，不在 error/cancel 路径合成成功 message_stop。
- 新回程 Spring 注册测试改为验证两半均为真实 Bean；半装配守卫测试仍手工只装去程，
  保留原命题，不把它们改成伪全实现。

### 8.3 自动化验证

新增五类 **103 条**用例：

| 测试类 | 条数 | 主要判据 |
|---|---:|---|
| `ChatToMessagesNonStreamTranslatorTests` | 34 | 身份/块顺序、无签名、对象参数与空响应、纯思考兜底、停止原因、结构错误、占位工具 ID 跨消息隔离 |
| `ChatToMessagesStreamTranslatorTests` | 35 | 当前工具实时分片、转义跨片、并行工具串行输出、单块生命周期、迟到/缺失元数据、交错内容、延迟/去重、EOF/error |
| `ChatToMessagesUsageConverterTests` | 9 | 共用缓存优先级、写入别名不相加、显式 0/缺失、展示 clamp 不改原始指标 |
| `ChatToMessagesResponseTranslatorTests` | 13 | 注册方向、槽同一性/无槽不补造、零产帧带 usage、订阅重建、日志 frameCounts、正常/异常/取消收尾、占位 ID 重放稳定 |
| `MessagesC2mResponseIntegrationTests` | 12 | 真实 Before/AfterSend + Chat 执行器 + WebClient stub；四个日志分支、原生 usage_raw/指标/日聚合、原生判空预算、清洗后的数组/工具轮 |

现有两类 Chat 执行器测试仅适配日志参数；Spring 装配用例增加回程真实 Bean 断言。
聚焦回归覆盖旧 M2C/R2C 翻译、主干错误信号、半轮守卫、Chat 重试/记账与新方向。

- **最终聚焦 Maven**：**237 通过，0 失败/错误**，BUILD SUCCESS，
  完成于 2026-10-25 19:08:22 +08:00。
- **最终默认全量 Java 回归**：**1943 通过，0 失败/错误**，BUILD SUCCESS，
  完成于 2026-10-25 19:20:39 +08:00。
- 前端使用已有 `skip.installnodenpm` / `skip.npm` 属性跳过（本轮未改前端）；
  schema-migration 依仓库默认 Tag 排除，未改 schema 所以不额外跑迁移专项。
- 编辑器诊断无新增代码错误。首轮编辑器测试曾因新状态类加载缓存失败，
  用 Maven 重新编译验证，未把该结果当源码通过证据。
- SSE stub 的多行 JSON 必须压成单行或逐行加 `data:`；
  重试后异步 `doFinally` 的次数断言等 `publishCallRecorded` 信号，不用睡眠赌时序。

**usage 关键差分测试**：上游 prompt=100、completion=7、cache-read=40、cache-write=20；
下游展示 input=40，明细与出口统计仍 prompt=100，cached=40；原始 credit/reasoning 字段
留在 usage_raw，不串入展示或重新解析成另一份记账值。

### 8.4 阶段 B/C 与发布边界

阶段 A 只证明代码与自动化接线；未执行 §5 阶段 B 的 `translate-c2m-*` mock 扩展/联测。
Claude Desktop / Claude CLI 多轮工具执行、无签名思考兼容、无参 `{}` 拼装、
人为重发、迟到 usage 与真实日志双栏仍需实机验收；没有由单测结果推导客户端兼容。
早期 M2C RFC 的随机端口最终请求序列化收包工作也不因本阶段通过而自动完成。

文档门禁：本阶段源码/活文档已同步；**完整功能发布门禁仍被上述实机验收阻塞**。
契约缺口备忘保留，不在本轮拆出 M2C 独立请求契约或 C2R 两份契约。

---

<a id="c2m-live-2026-10-25"></a>

### 8.5 三家真实上游实机存档（2026-10-25）

**授权与环境**：用户重启 COSP 并关闭 `[stepfun] step-5-preview` 的 MESSAGES 路径、
只保留 CHAT 路径以强制走 M2C 去程 + C2M 回程；下游鉴权关闭；随后同样测试
`[mimo-tokenplan] mimo-v2.6-flash` 与 `[deepseek] deepseek-flash`。
请求经 `http://localhost:11434/v1/messages` 直接发送。

**方法（PowerShell 5.1）**：请求体一律写无 BOM UTF-8 文件后 `curl.exe --data-binary "@file"`
发送，正文与回复全英文避免编码问题；管理后台登录取 JWT 查询日志。每次调用后
记录下游收到的 Messages 响应/SSE 帧，并从 `api_call_log` 取 upstream/translated 双栏。

**判定表（三家全部通过）**：

| 验证项 | stepfun `step-5-preview` | mimo `mimo-v2.6-flash` | deepseek `deepseek-flash` |
|---|---|---|---|
| 非流式回程 | ✅ message/thinking/text/end_turn | ✅ 同左 | ✅ 同左 |
| 流式回程 | ✅ SSE 完整帧序列 | ✅ 同左 | ✅ 同左 |
| 工具调用（非流式） | ✅ tool_use + stop=tool_use | ✅ 同左 | ✅ 同左（无 thinking） |
| 流式工具参数分片 | ✅ input_json_delta 逐片 | —（未重复打） | —（未重复打） |
| 多轮工具上下文 | ✅ tool_result 回传正常 | —（未重复打） | —（未重复打） |
| 思考链 thinking 块 | ✅ | ✅ | ✅ |
| 截断映射 | ✅ max_tokens 正确 | —（未重复打） | —（未重复打） |
| 日志双栏/usage 口径 | ✅ | ✅ | ✅ |

**翻译前原始 chunk（upstream）差异**：

| 维度 | stepfun | mimo | deepseek |
|---|---|---|---|
| 思考字段 | `reasoning_content` | `reasoning_content` | `reasoning_content`（逐字极短分片） |
| 每帧 usage | 几乎每帧带递增 usage | 仅尾部 usage-only 帧 | finish 帧自带 usage |
| 附加字段 | delta 带 `role` | 无 | 每帧带 `system_fingerprint` |
| 尾部 | finish 帧 + usage 帧 + `[DONE]` | finish 帧 + usage 帧 + `[DONE]` | finish 帧自带 usage + `[DONE]` |
| 缓存字段 | `prompt_tokens_details.cached_tokens` | `prompt_tokens_details: null` | `cached_tokens` + `prompt_cache_hit_tokens` |

**翻译后 chunk（translated）归一化**：三家均输出：
`message_start` → `content_block_start/delta(thinking_delta)/stop`（index 0）→
`content_block_start/delta(text_delta)/stop`（index 1）→ `message_delta`（end_turn + 展示 usage）→
`message_stop`。块索引稠密，思考/正文分块正确。

**帧数（upstream → translated）**：

| 供应商 | upstream 帧 | translated 帧 | frameCounts 对齐 |
|---|---:|---:|---|
| stepfun | 15 | 19 | ✅ |
| mimo | 11 | 14 | ✅ |
| deepseek | 44 | 48 | ✅ |

**关键判据**：

- usage 展示：stepfun/deepseek 显示 `cache_read_input_tokens`（值为 0）；mimo 因上游
  `prompt_tokens_details: null` 不展示该字段。三家 `input_tokens` = 上游 `prompt_tokens`
  换算正确，明细落库仍保留上游 prompt/completion/cached 口径。
- deepseek 逐字思考分片逐片映射为 `thinking_delta`，顺序正确、无合并污染。
- 收尾：mimo 依赖尾部 usage-only 帧触发 `message_delta`；deepseek 由 finish 帧自带 usage
  触发；三家 `message_delta` 均只发一次。

**局限与未覆盖**：未用真实 Claude Desktop / Claude CLI 客户端消费下游帧；未测人工重发、
无参工具 `{}` 拼装、无签名思考客户端兼容；mimo/deepseek 未重复打流式工具与多轮。
上述结论只证明 COSP 与三家上游之间的端到端翻译，不代替客户端实机验收。

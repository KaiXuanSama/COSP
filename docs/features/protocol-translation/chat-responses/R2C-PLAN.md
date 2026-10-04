# R2C 回程翻译（响应体）实施计划

> **状态**：阶段一、二、三全部完成（2026-10-04：三家供应商实机验证通过 + §7.4 双重修复闭环——C2R 去程 reasoning item 与控制器 DTO 字段，落库证据齐全）。C2R 线路端到端可用
>
> **需求背景**：把上游 Responses 响应（非流式 JSON / SSE 事件流）翻译回下游 Chat
> Completions 形态，让 C2R 线路（下游打 `/v1/chat/completions`、上游只支持 Responses）
> 端到端可用。C2R 去程已实测可用（[PLAN.md](./PLAN.md) §7.3），当前响应是原样透传的
> 半轮中间态（`AfterSend.warnIfHalfRound` 每次调用都在告警）。
>
> 相关：[R2C-RESEARCH.md](./R2C-RESEARCH.md)（四项目对比 + §9 决策定案，本文决策的事实基础）、
> [C2R-RESEARCH.md](./C2R-RESEARCH.md)（去程调研）、
> [../chat-messages/RESPONSE-CONTRACT.md](../chat-messages/RESPONSE-CONTRACT.md)
> （M2C 响应侧契约——**本方向最重要的同形先例**，§16 含 C2R 实测事件序列）。

---

## 0. 决策与理由

R2C-RESEARCH §9 已定案六项（usage 收尾对齐 C2M、IncludeUsage 取 TranslationContext、
created 混合兜底、added 缺失 pending 存留、reasoning 分隔用 new-api「标记+差额」、
phase/encrypted 丢弃留痕），§6 倾向列即实现口径。本节只记录**深度探索代码后新增**的
决策点与事实约束。

### 0.1 架构事实（探索核实，直接决定实现形状）

| # | 事实 | 出处 | 对 R2C 的含义 |
|---|---|---|---|
| 1 | **上游终态事件以 `Terminal` 形态到达翻译器**。执行器用 `UpstreamEventClassifier.classify(…, RESPONSES, data)` 分类，7 种终态（`ResponsesStreamEvents.TERMINAL_OUTCOMES`）都归 Terminal，`data()` 携带原文 | `ResponsesStreamEvents` + `AfterSend` | 翻译器把 `map(UpstreamEvent::data)` 后的事件原文交给状态机，switch 按事件 type 分派——**Terminal 不是特殊输入**，与 Body 同一条路（M2C 同形：`message_stop` 也是这样进来） |
| 2 | **usage 是双通道**：`UpstreamEvent.usage()` 槽由执行器填（`ResponsesUsageParser`，记账用），翻译器「只传递不重算」（M2C 的 `carriedUsage` 模式）；出站给下游看的 OpenAI 形态 usage chunk 由状态机自算 | `MessagesToChatResponseTranslator.translateStream` 的注释 | R2C 照抄该模式：`doOnNext` 收集槽位值 → 末帧 `withUsage(carried)`；出站 usage 从终态事件原文提取 |
| 3 | **Responses 的 usage 只在终态事件一次给全**（不需要 Anthropic 那种跨事件合并；但仍兼容中途携带的上游——取最后一份非 null） | `ResponsesUsageParser` Javadoc + `GenericResponsesChatService:352-402` | 状态机持有一个「最后一份 usage 原文」，finalize 时转 OpenAI 形态 |
| 4 | **输出帧必须再过 classify**：`UpstreamEventClassifier.classify(objectMapper, CHAT, frame)` —— `[DONE]` 由此被分类为 Terminal，控制器据此收尾 | `MessagesToChatResponseTranslator.translateStream` 末行 | R2C 输出协议是 CHAT，复用同一行 |
| 5 | **`OpenAiResponseShapes` 的形状助手全套可复用**：`roleDelta` / `contentDelta` / `reasoningDelta` / `toolCallStartDelta` / `toolCallArgumentsDelta` / `finishDelta` / `chunk` / `DONE_SENTINEL` | `OpenAiResponseShapes` | R2C 零新形状——M2C 已把 Chat 侧形状收敛完 |
| 6 | **落库改写器已接线**：`AfterSend.chunkRewriterFor` 在回程命中时自动构造（调 `translateChunksForLog`），半轮态传 null 落上游原文 | `AfterSend` | R2C 落地后落库自动切换为「下游实际收到的 chunk + 逐事件产帧数」，零接线改动 |
| 7 | **空响应判定（`ResponsesContentDetector`）在翻译内侧**，读上游原生形态 | 响应侧契约 §12 | R2C 落地不影响判定与重试——它们先于翻译发生 |

### 0.2 新增决策点（R2C-RESEARCH §9 未覆盖）

| # | 决策点 | 结论 | 理由 |
|---|---|---|---|
| 8 | **上游终态事件的吸收**：`completed` / `done` / `incomplete` 到达时**不透传原文**，转出一个 finish chunk；流结束时 `finalizeStream` 统一补 usage chunk + `[DONE]` | `[DONE]` 不绑任何事件、由流结束触发——M2C 类注释的既定原则（上游可能发完终态才关连接，也可能不发终态就断开）。终态原文对 Chat 客户端是无意义噪声 |
| 9 | **`response.failed` / `error` 事件的处置**：不产内容帧、记 warn 日志、按「无实质内容收尾」处理（finish_reason=`stop`）；**不转 onError 信号** | 这些事件到达翻译器时，空响应门已按原生形态判定过（`output` 为空 → 已卷入重试），能走到这里是重试耗尽放行的最终轮。转 onError 会在「SSE 头已发出」后触发二次分类，且 M2C 先例（`error` 事件只记录，处置在调用方）不这么做。Chat 协议没有失败事件概念，给下游一个结构完整的流 + 日志留痕是最不坏的选项。**与 `ResponsesStreamEvents.outcomeOf` 的 FAILURE 相位不冲突**：那是 ResponsesController（直连）的生命周期判定，翻译线路的生命周期由 Chat 侧出口管理 |
| 10 | **终态事件 output[] 的补发**（new-api `terminalOutputChunks`）：`completed`/`incomplete` 携带完整 `output[]`，若其中有**流内从未以 delta 发出**的内容（message 文本 / reasoning / function_call），补发对应 chunk | 「只发终态、不发 delta」的上游真实存在——`ResponsesContentDetector` 的 Javadoc 明确记录这类上游（终态事件的 `output[]` 是其唯一内容来源）。不补发的症状：下游收到空回复但 usage 照计费（sub2api 的 `SupplementResponseOutput` 注释钉住的坑）。**这同时是非流式翻译器的取数路径**——两者共用「从 output[] 提取」的代码 |
| 11 | **`output_item.added` 对 message / reasoning 不产帧**（只有 function_call 产） | M2C 同判：Chat 没有块生命周期概念，空 delta 帧是噪声（其 Javadoc 点名 new-api 的 fall-through 噪声帧是反面案例） |
| 12 | **非流式 content 空时给 `""` 而非 null** | M2C 非流式同口径（`content` 始终 put）；CPA 测试钉过「null 覆盖已有内容」的坑 |

---

## 1. 范围与验收

- **交付后 C2R 线路端到端可用**：Chat 客户端（Copilot）打 `/v1/chat/completions` →
  C2R 去程 → Responses 上游 → **R2C 回程** → 收到标准 Chat chunk 流 / JSON。
- 验收信号（实机）：
  1. `AfterSend.warnIfHalfRound` 的半轮告警**消失**（回程命中即不再触发）
  2. Chat 客户端能解析响应（不再看到 `object:"response"` 原文）
  3. 落库 chunk 列显示**下游实际收到的 Chat chunk**（不再只有上游事件栏）
  4. `downstream_protocol=CHAT`、`upstream_protocol=RESPONSES` 的调用恢复正常渲染

---

## 2. 组件与接线

### 2.1 新增组件（`pipeline/protocol/translate/`，三件套对齐 M2C）

| 组件 | 可见性 | 职责 |
|---|---|---|
| `ResponsesToChatResponseTranslator` | `@Component`，声明 `(CHAT, RESPONSES)` | 门面：三个接口方法 + `Flux.defer` 内建状态 + `carriedUsage` + 输出 classify。**加 `@Component` 即自动进 `TranslatorRegistry` 回程表——`AfterSend` / `BeforeSend` / 调度器全部零改动**，半轮透传路径自动切换 |
| `ResponsesToChatNonStreamTranslator` | 包私有 | Responses 完整 JSON → Chat JSON（§0.1-10：从 `output[]` 提取，与流式终态补发共用提取代码） |
| `ResponsesToChatStreamTranslator` | 包私有 | 逐事件翻译（`translateEvent(data, state) → List<String>`）+ `finalizeStream(state)`；switch 按 18 种事件 type 分派（见 §3 事件表） |
| `R2CStreamState` | 包私有 | 跨事件状态（见 §2.2） |

**复用清单（零新建）**：`OpenAiResponseShapes`（全部形状）、`UpstreamEventClassifier`
（输出分类）、`TranslationContext`（includeUsage）、`ResponsesStreamEvents`（终态判定
——翻译器自己也需要判终态，复用这份清单而不是再写一份，理由同其 Javadoc「两个消费者」）。

### 2.2 `R2CStreamState` 字段设计（对照 M2CStreamState + R2C-RESEARCH §2.2/§2.3）

| 字段 | 来源 | 说明 |
|---|---|---|
| `id` / `model` / `created` | M2C 同形 | created 时 adopt 上游 `response.id`；**model 用上游真实名**（占位用 `upstreamModel` 入参）——注意：这与 R2C-RESEARCH §6-10「回显下游带前缀名」**不冲突**，那里说的是非流式响应体的 `model` 字段（入参传下游名）；流式每帧的 model 与 M2C 同口径用上游名。**两处口径需在实现时统一核对**（见 §6-3） |
| `sentRole` | §9-3 混合方案 | created 时发 role 帧；首个内容事件检查未发则带上 |
| `nextToolIndex` + 三级映射（`outputIndexToKey` / `itemIDToKey` / `callIDToKey`） | §9-4 + 三家共识 | 本地重编号；`toolByKey` 持工具记录（CallID/Name/Index/Arguments/ArgsSentAt/NameSent） |
| `pendingArgsByOutputIndex` / `pendingArgsByItemID` | §9-4 | delta 早于 added 的存留，注册时拼入 |
| `needsReasoningBreak` | §9-5 | 「标记 + 差额」：done 设标记，下个 delta 按开头换行数补足到 2 |
| `sawToolCall` / `sawContent` / `finalized` | M2C 同形 | 三档收尾判定 + 幂等 |
| `lastUsageRaw` | §0.1-3 | 最后一份非 null 的 usage 原文（JsonNode），finalize 转 OpenAI 形态 |
| `includeUsage` | TranslationContext | 仅流式消费 |

### 2.3 事件 → 动作总表（流式状态机的完整规格）

| 上游事件 | 动作 | 产帧数 |
|---|---|---|
| `response.created` | adopt id/model；发 role 帧（§9-3） | 1 |
| `response.in_progress` | 忽略 | 0 |
| `response.output_item.added`（function_call/custom_tool_call） | 注册工具（三级键 + pending 拼入）；发 tool start 帧（index/id/name，arguments=""） | 1 |
| `response.output_item.added`（message/reasoning/其它） | 忽略（§0.2-11） | 0 |
| `response.output_text.delta` | 发 content delta（前缀：role 未发则带上） | 1 |
| `response.reasoning_text.delta` / `reasoning_summary_text.delta` | 发 reasoning_content delta（前缀：needsReasoningBreak 差额补足 + role 检查） | 1 |
| `response.reasoning_text.done` / `reasoning_summary_text.done` | 设 needsReasoningBreak 标记（不发固定 `\n\n`） | 0 |
| `response.output_text.done` / `content_part.*` / `reasoning_part.*` | 忽略（内容已在 delta；块生命周期无意义） | 0 |
| `response.function_call_arguments.delta` / `custom_tool_call_input.delta` | 查工具（三级）→ 命中：累加 + 发差量帧；未命中：存 pending | 0/1 |
| `response.function_call_arguments.done` / `custom_tool_call_input.done` | 前缀补齐：对比已发长度，只发未发过的差量（不发 name） | 0/1 |
| `response.output_item.done`（function_call） | 若工具已发过参数则不发；否则用 done 携带的完整 arguments 补发 | 0/1 |
| `response.output_item.done`（其它类型） | 忽略 | 0 |
| `response.completed` / `response.done` | **吸收**：提取 usage → lastUsageRaw；终态 output[] 补发（§0.2-10）；发 finish chunk（§0.2-8） | 1..N |
| `response.incomplete` | 同上 + `incomplete_details.reason` 映射（`max_output_tokens`→`length`、`content_filter`→`content_filter`、其它→`stop`） | 1..N |
| `response.failed` / `error` | **不产内容帧**，warn 日志（§0.2-9） | 0 |
| `response.cancelled` / `canceled` | 同 failed 处置 | 0 |
| （流结束，concatWith finalizeStream） | 未发 finish 则按三档补（已发/有内容→`length`/无内容→`stop`）；usage chunk（includeUsage && hasUsage）；`[DONE]` | 0..3 |
| 未知事件 / 解析失败 | debug 日志跳过（M2C 同判：单事件失败不中断流） | 0 |

---

## 3. 明确不做的事

| 不做 | 理由 |
|---|---|
| `logprobs` / `obfuscation` / `encrypted_content` / `sequence_number` | 三家共识 + R2C-RESEARCH §2.6；Chat 无承载位置 |
| `annotations`（url_citation）转换 | §9 已定丢弃（最小实现）；new-api 独有，Copilot 不消费 |
| 思考缓存 | C2R 输入侧不需要（C2R-RESEARCH §10.1）；「R2C + 上游签发密文」场景在本方向是**输出侧**丢弃密文（§9-6），不构成缓存需求 |
| `sub2api` 的「非流式强制 SSE + 缓冲累加器」路径 | COSP 支持真非流式（`GenericResponsesChatService.invoke`），不需要 |
| `namespace` / `web_search_call` 工具输出的翻译 | Chat 无对应物（与 C2R 去程「不合成」对称）；`web_search_call` 吞掉并 debug 留痕（sub2api 同判） |
| 落库 / 调度 / 出口的任何改动 | 全部零改动（§0.1-6 接线已就位） |

---

## 4. 阶段划分与提交边界

三阶段各自独立提交、独立验证。**先非流式后流式**（M2C 先例：非流式无状态机，
先把字段映射、usage 换算、终态提取三件事钉死，流式复用其结论）。

### 阶段一：非流式翻译器 + 门面

**改动**：新增 `ResponsesToChatNonStreamTranslator` + `ResponsesToChatResponseTranslator`
（门面先实现 `translateResponse`，另两个方法可先返回空实现/抛 Unsupported——
**注意**：门面注册进 Registry 后回程即「已命中」，流式也会走它，所以空实现必须是
「透传原文」而非抛异常，或者**本阶段先不注册**（阶段二一并注册）。
→ **取后者：阶段一不带 `@Component` 或注释掉，阶段二补上**——避免流式路径拿到一个
只会透传的假回程）。

**验证**：`ResponsesToChatNonStreamTranslatorTests` 全绿（用 `samples/` 三份非流式样本
+ deepseek 实测形态做 fixture）+ 全量绿。

**单测组织**（参照 `MessagesToChatNonStreamTranslator` 的测试）：
1. 纯文本 output → content
2. reasoning item（content 优先 / summary 兜底 / 两者皆有）
3. function_call → tool_calls（call_id 优先、name、arguments、多工具顺序）
4. 多 message item 空行分隔（`appendSeparatedText` 语义——**该助手若 M2C 未沉淀为
   共享工具，在本阶段提为 `OpenAiResponseShapes` 或独立助手**，流式终态补发复用）
5. status/incomplete_details → finish_reason 全分支
6. usage 换算（含 `output_tokens_details.reasoning_tokens` → `completion_tokens_details`）
7. model 回显下游名 / id 原样 / created 用 `created_at`
8. 空文本给 `""` 非 null（§0.2-12）

### 阶段二：流式状态机 + 注册

**改动**：新增 `R2CStreamState` + `ResponsesToChatStreamTranslator`；门面补
`translateStream` / `translateChunksForLog` + `@Component` 注册。

**验证**：
1. `ResponsesToChatStreamTranslatorTests`：§2.3 事件表的每一行至少一条用例；
   重点组——
   - **三种形态回放**（`samples/` 的 stepfun-midway 形态 C / astra 纯文本 /
     stepfun-toolcalls 形态 B 的事件序列做 fixture，断言完整 chunk 序列）
   - 前缀补齐（delta 累加后 done 只发差量；done 先到 delta 后到的 MiniMax 乱序重放）
   - pending（delta 早于 added）
   - 三级键（item_id 缺失时 output_index 兜底等）
   - 终态补发（只发 completed 不发 delta 的上游 → output[] 内容补发）
   - failed/error 不产帧 + 三档收尾
   - reasoning 分隔的「标记+差额」（done 后 delta 自带 `\n\n` 开头时不重复补）
   - role 混合兜底（无 created 的事件流）
   - `[DONE]` 由流结束触发（无终态事件直接断流 → finalizeStream 补）
   - usage chunk 仅 includeUsage 时发、finish 在前 usage 在后
2. `translateChunksForLog` 与流式共用状态机（独立状态重放，M2C 注释的幂等要求）
3. 全量绿（**重点盯**：既有 `ChatDispatchErrorSignalTests` / 集成测试若断言
   「C2R 回程未实现告警」需同步更新——探索未见直接断言，落地后跑全量确认）

### 阶段三：实机验证（需重启服务，用户手动）

**判据**（§1 验收信号）+ 三家供应商复测（mimo-tokenplan / stepfun / deepseek 各跑
非流式 + 流式 + 工具调用 + 多轮）：Chat 形态正确解析、reasoning_content 渲染、
工具调用可执行、落库 chunk 列为 Chat chunk、半轮告警消失。

---

## 5. 挑战与风险（深度调研结论）

| # | 挑战 | 风险等级 | 缓解 |
|---|---|---|---|
| 1 | **上游形态多样性**：id 形态（UUID/16 位 hex/`fc_` 前缀）、分片粒度（逐字符/单 delta 全量）、字段集（deepseek 最全）三家三样 | 高 | 已有原则：只依赖事件骨架 + `call_id` 配对语义（R2C-RESEARCH §10 对表）；绝不依赖字段存在性 |
| 2 | **乱序上游**（MiniMax 实测：done 先于 delta 且参数为空） | 高 | 前缀补齐 + pending 双机制（§9-4/§5）；用该场景做定向单测 |
| 3 | **model 字段的两处口径（已定案，§6-1）**：契约 §7「回显下游带前缀名」与代码「透传上游裸名」矛盾 | 低 | **保持现状（上游裸名）**：实测三家下游 agent 均无问题；改写需在回程前加主干且波及三线路+直连。契约勘误注记随阶段一顺手加 |
| 4 | **只发终态不发 delta 的上游**：流内零 delta，内容全在 `completed.output[]` | 中 | 终态补发（§0.2-10）+ 与非流式共用提取代码；`ResponsesContentDetector` 已确认这类上游存在 |
| 5 | **`response.failed` 的双重语义**：空响应门可能已把它重试掉；耗尽放行的最终轮它以 Terminal 到达翻译器 | 中 | §0.2-9 的既定处置（不产帧 + warn）；实机验证时用 mock 的 `fail-*` 场景确认不炸 |
| 6 | **帧数对等的落库重译**：`translateChunksForLog` 必须与出站共用同一状态机逻辑，否则日志帧数与实际下发不符 | 中 | M2C 模式：独立状态 + 同一 `translateEvent`/`finalizeStream` 重放；有既定测试模式可抄 |
| 7 | **门面注册的时序**：阶段一若提前注册，流式会拿到只会透传的假回程（比半轮告警更糟——静默） | 低 | 阶段一不注册（§4 已定）；阶段二连同流式一起上 |
| 8 | **与既有集成测试的交互**：`ChatCompletionHalfRoundTranslationTests` 这类「半轮态」命名的测试可能断言透传行为 | 低 | 阶段二全量跑时逐一核对该类测试；半轮语义对 `(CHAT, RESPONSES)` 消失、对其它未实现方向保留 |

---

## 6. 遗留与待确认（2026-10-04 全部定案）

1. **model 口径已定案：保持现状（上游裸名透传）**。决策依据：
   - 实测上 Copilot / Claude CLI / Codex 三家下游 agent 对裸名**均无问题**（多轮
     会话长期运行验证），契约 §7 担心的「下轮路由失败」未实际发生；
   - 若要改写，需在回程翻译器分支**之前**加一段主干专门改写下发 model——
     结构成本高且波及 M2C / 直连（三线路 + 直连必须同口径）；
   - 契约 §7 与代码的矛盾**不在本分支处理**，仅实施时在该节加一行勘误注记
     指向本条（矛盾详情见 §5-3）。
2. **`custom_tool_call` 已定案：按 function 翻译**——`id` 用 `call_id`、`name`
   照搬、`arguments` 取其 `input` 字段（字符串）。下游看到的是普通工具调用，
   可直接执行。流式事件 `custom_tool_call_input.delta/done` 与
   `function_call_arguments.delta/done` 同一处理路径（§2.3 已并入同两行）。
3. **`name:""` 覆盖坑已调研：COSP 天然免疫，无需防护**。sub2api 的坑源于
   **单一共享结构体**序列化所有 delta（Go 对空字符串默认输出 `"name":""`，
   客户端按 index 累积时覆盖首帧的真名字，其修复是 `omitempty`）。COSP 的
   `OpenAiResponseShapes` 用**两个独立构造方法**：`toolCallArgumentsDelta`
   构造的 function map 只有 `arguments` 键——`name` 键在路径上不存在
   （`LinkedHashMap` 只序列化 put 进去的键），两条构造路径物理隔离。

---

## 7. 实施记录（各阶段完成后追加）

### 7.1 阶段一：非流式翻译器（2026-10-04 完成）

**落地内容**：
- `ResponsesToChatNonStreamTranslator`（`pipeline/protocol/translate/`，约 380 行含 Javadoc）
- `ResponsesToChatResponseTranslator` 门面：`translateResponse` 已实现（usage 槽位
  只传不重算）；**按计划未注册 `@Component`**——流式/落库方法抛
  `UnsupportedOperationException`（不可达：Registry 查不到本类），类注释写明
  阶段二补注解与实现
- `ResponsesToChatNonStreamTranslatorTests`：29 条，8 组 `@Nested`
  （message→content 6 / reasoning 4 / tool_calls 5 / finish_reason 5 / usage 3 /
  头部字段 2 / 丢弃与异常 3，外加分隔不叠加 1）
- 契约勘误：RESPONSE-CONTRACT §7 头部加勘误注记（指向本文 §6-1）
- 验证：新测试 29/29 绿；全量 `./mvnw surefire:test` **1419 条全绿**（未注册故
  对既有链路零影响——与预判一致）

**实现中的口径落点**（与 §0/§6 决策一一对应）：
- content 多 item/part 空行分隔 + `appendSeparated`（只补足到 2 个换行，不叠加）
- reasoning content 优先 summary 兜底（两者不拼接——拼接会把同一段思考发两遍）
- `custom_tool_call` 按 function 翻译、arguments 取 `input`（§6-2）
- usage **prompt=input 直接映射**（不加缓存——与 Anthropic 侧三项相加相反，
  Javadoc 里特意标注「绝不能照抄」）；reasoning_tokens 归位
  `completion_tokens_details`；details 只给非零份
- id 优先 `call_id` 空回落 item `id`；arguments 缺失补 `"{}"`；空 content 给 `""`
- 头部 id/model/created_at 全部透传（§6-1 定案）

**计划外发现**：无——本阶段所有坑（null 覆盖、分隔叠加、reasoning 重复）都已在
调研阶段预判并落进测试。

### 7.2 阶段二：流式状态机 + 注册（2026-10-04 完成）

**落地内容**：
- `R2CStreamState`（约 300 行）：工具三级映射（output_index/item_id/call_id 共指一条
  记录）+ 本地重编号 + `argsSentAt` 前缀补齐位 + pending 存留 + reasoning「标记+差额」
  分隔 + 三档收尾标志
- `ResponsesToChatStreamTranslator`（约 420 行）：§2.3 事件表全量实现——18 行规格
  逐行落实（终态吸收、output[] 补发、failed/error 不产帧、usage 收尾统一发）
- `ResponsesToChatUsageConverter`：usage 换算从非流式私有方法提为共享类（流式收尾
  与非流式必须同口径——「一份实现、两个消费点」）
- 门面补 `translateStream`（M2C 同构：defer 内建状态 + carriedUsage + 输出 classify）
  与 `translateChunksForLog`（独立状态重放）；**补上 `@Component`——C2R 回程位即命中，
  半轮告警路径失效**
- `ResponsesToChatStreamTranslatorTests`：27 条，7 组 `@Nested`（骨架 4 / 内容增量 4 /
  工具 7 / 终态补发 3 / 失败终态 3 / usage 2 / 真实形态回放 2 / 落库 1，外加合计）
- 验证：新测试 27/27 绿；全量 `./mvnw surefire:test` **1446 条全绿**——注册后
  `TranslatorRegistry` 自动收集、既有集成测试（含 `ChatCompletionHalfRoundTranslationTests`）
  无一受影响，与「零接线改动」预判一致

**测试 fixture 的三类真实形态**：samples 形态 C 的 14 事件骨架（stepfun 逐字符分片）、
deepseek 官方（单 delta 全量参数）、MiniMax 乱序（done 先到参数空、完整参数在随后 delta）。

**计划外发现（实现期 bug，单测抓出）**：
- `registerTool` 初版只把工具记录存进**主键**（`call:`），而 `keyByItemId` 次级索引
  指向的 `item:` 键在 `toolsByKey` 里不存在——delta 事件（只带 `item_id`）三级回落
  查不到记录、参数进 pending 永远拼不上。修复：注册时写入**所有可用键**（三键共指
  同一记录）。**这正是三级映射设计的本意，初版实现漏了半边**——`nameOnlyOnFirstFrame`
  与 `doneSupplementsMissingRemainder` 两个用例把它打出来
- Java 文本块里嵌 JSON 字符串的引号转义是 `\\"`（文本块的 `\"` 产出裸引号 →
  JSON 非法 → 解析出空 delta）：probe 式二分（一次性探针测试打印真实帧）比盯
  行号快得多——异常行号指向**旧编译**的 class 曾误导排查方向

### 7.3 阶段三：实机验证（2026-10-04，进行中）

**已完成**：mimo-tokenplan 与 stepfun 各三项（非流式全套 / 流式工具调用 / 多轮回传）
**全部通过**——响应已是标准 `chat.completion` / `chat.completion.chunk`：

- 非流式：`reasoning_content` 渲染、`finish_reason` 正确、usage 换算正确
  （`reasoning_tokens` 归位 `completion_tokens_details`、mimo 的 `cached_tokens`
  归位 `prompt_tokens_details`）
- 流式：chunk 序列 `role → reasoning ×N → tool[0] name → args → finish=tool_calls →
  [DONE]`；stepfun 的逐字符参数分片正确透传；name 只发一次
- 多轮：工具结果回传正确消化

**发现的真问题**（deepseek 官方，§7.4）已修复，待复测。

### 7.4 计划外：deepseek 硬约束③实测触发与修复（2026-10-04）

**现象**：deepseek 多轮回传报 400：
`The reasoning_text in the thinking mode must be passed back to the API`。

**根因**：C2R 去程的 `translateAssistant` 把下游回传的 `reasoning_content`
<strong>丢弃</strong>（阶段一依据「Chat 客户端不回放思考」的过时判断），而
deepseek 官方（思考模式对话式上游）要求历史 assistant 消息必须携带思考——
这正是 [REQUEST-CONTRACT §4.8](../../chat-messages/REQUEST-CONTRACT.md)
<strong>硬约束③</strong>（sub2api 记录过同一错误原文），因供应商而异
（mimo/stepfun 不校验，deepseek 官方校验），不能假定任何一种。

**修复**：`translateAssistant` 把 `reasoning_content` 翻译为<strong>明文 reasoning
item</strong>（`content[].reasoning_text` + `encrypted_content: null`，契约 §4.8
记录的「上游不签发密文」填充方式），置于正文 item 之前（与上游产出顺序一致）。
与 R2C 响应侧构成<strong>往返闭环</strong>：R2C 给下游 `reasoning_content` →
下游回传 → C2R 还原 reasoning item——思考两侧不断链，且<strong>零缓存</strong>
（透传明文；§4.8 的「缓存明文」路线仅在「上游签发密文 + 下游只有密文」时需要，
本线路下游手上本来就有明文）。

**测试**：C2R 翻译器 46 条全绿（新增 reasoning item 翻译 / 空白省略 / 往返闭环
断言）；全量 <strong>1448 条全绿</strong>。

**复测与二次排障（同日，找到真正的根因）**：重启复测时 deepseek 第二轮返回 200，
但落库的上游 input 里<strong>没有 reasoning item</strong>——修复代码在实机上未生效。
逐层排除（规则组不命中 RESPONSES / 装配浅拷贝与清 null 都不删 item / 磁盘 class
是新版且服务启动晚于编译）后定位<strong>真凶</strong>：`OpenAiController` 用强类型
`OpenAiChatRequest` 反序列化再重建 Map，而 `Message` DTO <strong>没有
`reasoningContent` 字段</strong>——Copilot BYOK 回传的思考在<strong>控制器入口
被 Jackson 静默剥掉</strong>，翻译器从未收到过该字段。单测直调翻译器覆盖不到这一层
（R2C 实机排障暴露的测试盲区）。此前「400 → 200」的差异并非修复生效，而是复测构造
的场景恰好未触发 deepseek 的校验（400 的原始触发来自 Copilot 真实使用路径）。

**二次修复**：`OpenAiChatRequest.Message` 增加
`@JsonProperty("reasoning_content") private String reasoningContent`（含字段级
Javadoc 记录这次的坑）；`buildRequestBody` 无需改动（`objectMapper.convertValue`
序列化 DTO 时自动带上）。

**防漂移测试**：`OpenAiControllerTests.reasoningContentSurvivesControllerDeserialization`
——用 ArgumentCaptor 捕获 service 层收到的 Map，断言字段穿透「DTO 反序列化 +
Map 重建」两层。<strong>控制器层的贯通测试</strong>从此有先例可抄。

**最终验证**：C2R 翻译器 46 + 控制器 7（+1 新增）全绿；全量 <strong>1449 条全绿</strong>。
待再次重启后复测 deepseek 多轮——这次落库 input 应出现 reasoning item。

**复测定案（同日，重启后）**：deepseek 两轮全 200；<strong>落库 input 出现
reasoning item</strong>（`message/developer → user → reasoning → function_call →
function_call_output`，`content[].reasoning_text` 明文、位置在 function_call 之前、
`encrypted_content` 为 null 形态正确），上游正确消化并在回答中带出时区细节。
§7.4 的修复链至此<strong>全部闭环</strong>：DTO 字段 → 翻译器 reasoning item →
上游接受 → 落库证据齐全。

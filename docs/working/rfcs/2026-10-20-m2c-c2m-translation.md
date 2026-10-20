# RFC：M2C 去程与 C2M 回程翻译（下游 Anthropic → 上游 Chat 的完整链路）

- **日期**：2026-10-20（立项日）
- **状态**：**讨论中** —— 结构调研与四参考项目对比已完成；决策 1/2/3/4 均已定案（见 §6），等待开始实施
- **相关 ADR**：（尚无；决策定稿后新建 ADR 承载「M2C 去程思考/文档块处置」决策族）
- **分支**：`feat/m2c-request-c2m-response-translation`
- **前置文档**：
  [协议翻译四方向 RFC](./2026-09-03-protocol-translation.md) §A 方向总览、
  [C2M 请求契约](../../reference/contracts/C2M-REQUEST-CONTRACT.md) §4.3（M2C 思考映射）、
  [C2M 响应契约](../../reference/contracts/C2M-RESPONSE-CONTRACT.md) §0

> **需求复述**：用户近期开始把 Claude Desktop 作为常用客户端，其**文档处理能力**是主要动机，
> 因此 `/v1/messages` 这条下游路径从「能用」升级为「常用」。
> 当前 COSP 在该路径上只支持 **M→M 直连**与 **C→M**（下游 Chat、上游 Messages）；
> 一旦供应商只有 OpenAI 兼容端点（即上游 CHAT），M2C 去程翻译缺失会让整个请求
> 在协议调度阶段就被拒（`ProtocolTranslationNotSupportedException`，控制器译 400）。
> 本轮要补齐 **M2C 去程 + C2M 回程**这一对，使「下游 Anthropic、上游任何 OpenAI 兼容服务」
> 端到端可用。
>
> **本轮只做结构调研**，不写实现代码。本文记录「接在哪、缺什么、有哪些必须拍板的选择」。

---

## 1. 方向命名：先避开一个真实的歧义

现有文档里 **「M2C 响应」不等于「M2C 的回程」**，必须先钉住，否则实施时一定会接错方向。

约定（源自 [Ollama 下游 RFC](./2026-10-19-ollama-downstream.md) §2 对同一问题的记录）：

- **请求侧缩写**：`X2Y` = 「下游 X 的请求 → 上游 Y」
- **响应侧缩写**：`X2Y` = 「上游 X 的响应 → 下游 Y」（沿用「上游在前」的写法）

于是同一对协议的两半恰好共用**同一个缩写**，但含义相反：

| 本文要做的 | 缩写 | 实际含义 | 声明 `(downstream, upstream)` |
|---|---|---|---|
| 去程 | **M2C 请求** | 下游 Messages 请求 → 上游 Chat 请求 | `(MESSAGES, CHAT)` |
| 回程 | **C2M 响应** | 上游 Chat 响应 → 下游 Messages 响应 | `(MESSAGES, CHAT)` |

已落地的对照（`ProtocolTranslator` 的「实现现状」段，本次核对确认）：

| 已存在 | 类 | 声明 |
|---|---|---|
| C2M 请求 | `ChatToMessagesRequestTranslator` | `(CHAT, MESSAGES)` |
| M2C 响应 | `MessagesToChatResponseTranslator` | `(CHAT, MESSAGES)` |
| C2R 请求 | `ChatToResponsesRequestTranslator` | `(CHAT, RESPONSES)` |
| R2C 响应 | `ResponsesToChatResponseTranslator` | `(CHAT, RESPONSES)` |

> **实施时以 `downstreamProtocol()` / `upstreamProtocol()` 两个声明方法为准，不要靠缩写或类名猜。**
> `TranslatorRegistry` 的两张表也是按这两个方法建的，猜错方向的症状是查表未命中 →
> 报「翻译不支持」，而错误消息会把人引向「去检查供应商勾了什么协议」。
>
> 另一处容易搞混的地方：**`MessagesToChatResponseTranslator` 名字里的 `MessagesToChat` 是「响应流向」**，
> 它是 C2M 的回程，不是本文要补的那个方向。本文的回程类应命名为
> `ChatToMessagesResponseTranslator`（上游 Chat → 下游 Messages）。

---

## 2. 现状盘点：这条链路缺什么

### 2.1 缺的两块

| # | 缺什么 | 现状后果 |
|---|---|---|
| 1 | `(MESSAGES, CHAT)` 的**去程**翻译器 | `ProtocolDispatchManager.dispatch` 在供应商不勾 MESSAGES、只勾 CHAT 时，规则 2 找不到已实现的候选方向，抛 `ProtocolTranslationNotSupportedException` → 400。**这是本文要解决的主问题。** |
| 2 | `(MESSAGES, CHAT)` 的**回程**翻译器 | 去程补上后若回程仍缺，`AfterSend` 会走 `warnIfHalfRound` 透传上游原生 Chat chunk。下游 Claude Desktop 解析不了 Chat chunk，表现为「界面卡住 / 无输出」。 |

### 2.2 已经就绪的（**不需要改动**，已逐个核对）

这一节是本轮调研最重要的产出：M2C/C2M 是**纯增量接线**，主干、注册表、出口层一行都不用改。

| 组件 | 为什么不用改 |
|---|---|
| `MessagesService` | 早已退化成「声明协议 + 建 ctx + 交主干」，翻译分支不在它里面 |
| `BeforeSend.translateStep` | 按 `(downstream, upstream)` 查两张表；去程未命中才抛错，命中即改写 body |
| `AfterSend.process` | 回程翻译器从 `ctx.responseTranslator()` 读，本块不自己查表、不加协议分支 |
| `ProtocolDispatchManager` | 构造期从 `List<RequestProtocolTranslator>` 收集 `implementedRequestRoutes`，新 `@Component` 自动进入该集合 |
| `TranslatorRegistry` | 集合注入 + 按方向建两张表，零改动 |
| `RequestPipelineContext` | `applyTranslation` 同时替换 body 与 `bodyProtocol`，查表键自动切到 CHAT |
| `GenericOpenAiChatService` / `AbstractUpstreamChatService` | 上游是 CHAT，执行器与 usage 解析现成 |
| `ChatOutboundStage` | 读 `base_url`，无协议必需头（对比 `MessagesOutboundStage` 补 `anthropic-version`） |
| `ChatContentDetectorStage` | 空响应判定的键是 `upstreamProtocol = CHAT`，命中 |
| `ChatChunkNormalizeStage` / `ChatReasoningFallbackStage` | 键同为 `upstreamProtocol`，命中 |
| `AnthropicController` | 出口只认 `UpstreamEvent` 形态，不关心帧是怎么翻译来的 |
| `StreamLifecycle` / `NonStreamLifecycle` | 同上；Anthropic 侧靠 `extractEventType` 回填 SSE 的 `event:` 名 |
| `UpstreamEventClassifier` | 按传入协议判定终止标记，回程翻译器只要产出 `message_stop` 即可收尾 |
| `GatewayAuthFilter.PROTECTED_PATHS` | `/v1/messages` 已在列 |
| `ProviderRouteResolver` | 路由只由模型名决定，协议不参与候选集筛选 |
| `ProviderProtocolSupport` | 默认全勾，MESSAGES 本来就是合法取值 |
| 前端 `ALL_WIRE_PROTOCOLS` / `RULE_ENGINE_WIRE_PROTOCOLS` | 三协议早已齐备 |
| `tools/mock-upstream` | 已同时提供 `/chat/completions` 与 `/messages`，实机验证无需改 mock |

### 2.3 必须同步更新（否则测试会红，或注释会说谎）

> ✅ **2026-10-20 已修三处**（只改注释，编译通过）：
> `ProtocolTranslator` 的 TODO 块与「实现现状」表、`MessagesService` 的「跨协议为何不再抛错」、
> `ProtocolDispatchManager` 的回退序理由。下表标注「✅ 已修」的行**无需再做**。

| 位置 | 现状问题 | 状态 |
|---|---|---|
| `ProtocolTranslator` 的 `TODO(待实现)` 注释块 | 曾写「`MessagesService` 目前尚未改用查表……需先把 `MessagesService` 的翻译分支改成查 `TranslatorRegistry`」。实际该分支**早已上移主干**，照着做会去改一个不存在的东西。 | ✅ 已修（改为指向本 RFC） |
| `ProtocolTranslator` 的「实现现状」表 | M2C 请求 / C2M 响应两条「未实现」要改写 | ✅ 已修 |
| `MessagesService` 的「跨协议为何不再抛错」 | 同上，指向了不存在的分支 | ✅ 已修 |
| `ProtocolDispatchManager.TRANSLATION_FALLBACK_ORDER` 注释 | 原写「MESSAGES —— 已有一个方向完整实现（C2M 去程 + M2C 回程）」，M2C 落地后 MESSAGES 作为**下游**也完整，理由需重述 | ✅ 已修（改为「C2M 双向完整，M2C 双向亦已规划定案」） |
| `ProtocolDispatchManagerTests.skipsUnimplementedCandidateAndFallsToNextImplemented` | 该用例的前提是「`(MESSAGES, CHAT)` 未实现 → 跳过 CHAT 降到 RESPONSES」。M2C 落地后此断言必红，需换成仍未实现的方向（如 `(MESSAGES, RESPONSES)`） | ⏳ 实施阶段处理（**测试代码，不能提前改**，否则现在必红） |
| `ChatDispatchErrorSignalTests` 中「下游 Anthropic 而供应商只有 OpenAI（M2C 请求翻译未实现）」一组 | 前提消失，需改为「已实现路径」或改方向 | ⏳ 同上 |
| `TranslatorRegistrySpringWiringTests` | 目前只断言 `(CHAT, MESSAGES)` 的两半；应补 `(MESSAGES, CHAT)` 的 `containsSame` 断言，理由见该类的类注释：装配漏了**不响**，只在第一个跨协议请求时暴露 | ⏳ 同上 |
| `pipeline/README.md` 的翻译支线一节 | 「19 个实现」的计数与「四方向双通」的描述 | ⏳ 同上 |
| 本目录 `README.md` 的状态表 | 新增本 RFC 一行 | ✅ 已完成 |
| [协议翻译 RFC](./2026-09-03-protocol-translation.md) §A 方向状态表 | M2C 从「请求侧待做；响应侧已实现」改为指向本 RFC | ✅ 已完成 |
| `docs/reference/contracts/C2M-REQUEST-CONTRACT.md` §4.7 | M2C thinking 处置的旧结论会导致工具链硬失败 | ✅ 已修（按三家共识改写） |
| 同上 §5.1 | 需注明 M2C 必须**生成** `stream_options.include_usage` | ✅ 已修（新增说明段） |

> **未改动的一处已知不一致（刻意）**：`ProtocolTranslator` 的「实现现状」仍写
> 「M2C 请求未实现 / C2M 响应未实现」—— 这是**代码事实**（当前分支确实没有那两个类），
> 改成「已实现」会让注释说谎。实施阶段落地后再改。

---

### 2.4 翻译链路优先级：现有实现对行为的精确说明

> 用户在实施 C2R 时曾考虑加「协议流转编排」，因复杂度高先用**优先级**临时实现。
> 本节把现有行为逐条钉住，避免实施 M2C 时误改。

#### 2.4.1 优先级判定**不在翻译器里**

**翻译器只声明方向，不参与任何选择。** 判定的唯一落点是
`pipeline/before/dispatch/ProtocolDispatchManager.dispatch(...)`：

```
BeforeSend.routeStep(ctx)
  └─ protocolDispatchManager.dispatch(下游协议, 供应商配置)
       ├─ 规则 1：供应商支持下游协议 → 直连（不读任何优先级）
       ├─ 规则 3：供应商一个协议都没勾 → NoSupportedProtocolException
       ├─ 规则 2：按 TRANSLATION_FALLBACK_ORDER 遍历候选
       │           条件 = supported.contains(候选)
       │                  && implementedRequestRoutes.contains((下游, 候选))
       └─ 规则 2 失败态 → ProtocolTranslationNotSupportedException
```

翻译器（`@Component`）对这套逻辑的**唯一贡献**是「声明自己存在」——
`ProtocolDispatchManager` 构造期从 `List<RequestProtocolTranslator>` 收集方向集合。
**它不知道、也不需要知道优先级顺序。**

#### 2.4.2 四条规则的实际执行顺序（已按代码核实）

| 顺序 | 规则 | 读优先级吗 | 说明 |
|---|---|---|---|
| 1 | **同名协议直连** | **否** | `supported.contains(downstream)` 为真即返回，**永不读回退序** |
| 2 | **一个协议都没勾** → 报错 | 否 | 与「勾了但翻译没实现」用不同异常类型 |
| 3 | **按回退序遍历**，挑第一个「支持 + 去程已实现」的 | **是** | 这是优先级唯一生效处 |
| 4 | 回退序里一个都不满足 → 报错 | 是（取代表） | 抛优先级最高的那个候选名做提示 |

**「优先直连，然后才考虑优先级选翻译路线」这句判断与代码完全一致。**
测试 `ProtocolDispatchManagerTests.directRouteIgnoresImplementationStatus` 与
`allThreeProtocolsSupportedKeepsEveryDownstreamDirect` 钉住了这一点。

#### 2.4.3 回退序是 `C → M → R`（已核实）

```java
static final List<WireProtocol> TRANSLATION_FALLBACK_ORDER =
        List.of(WireProtocol.CHAT, WireProtocol.MESSAGES, WireProtocol.RESPONSES);
```

顺序依据（代码注释原文）：

1. `CHAT` —— 兼容面最广，字段最少，往它翻的信息损耗最小
2. `MESSAGES` —— 已有一个方向完整实现（C2M 去程 + M2C 回程）
3. `RESPONSES` —— 排最后：字段最富（`reasoning.encrypted_content` 在 Chat 里无处安放），往回翻必然丢信息

> ⚠️ **更正一处我本节早先引入的错误**：上一轮我在核对时把这条注释改成了
> 「C2R 去程与 M2R/R2M 均未实现」，但**事实是 C2R 去程已实现**
> （`ChatToResponsesRequestTranslator`，声明 `(CHAT, RESPONSES)`）。
> 已改回原表述。**教训**：改注释前应先列出实际翻译器与它们声明的方向，而不是凭印象。

#### 2.4.4 当前实际已实现的去程方向（脚本核对）

按类里 `downstreamProtocol()` / `upstreamProtocol()` 的实际返回值：

| 类 | 类型 | 声明方向 | 存在性 |
|---|---|---|---|
| `ChatToMessagesRequestTranslator` | 去程 | `(CHAT, MESSAGES)` | ✅ |
| `ChatToResponsesRequestTranslator` | 去程 | `(CHAT, RESPONSES)` | ✅ |
| `MessagesToChatResponseTranslator` | 回程 | `(CHAT, MESSAGES)` | ✅ |
| `ResponsesToChatResponseTranslator` | 回程 | `(CHAT, RESPONSES)` | ✅ |
| **本文要加的** `MessagesToChatRequestTranslator` | 去程 | `(MESSAGES, CHAT)` | ⏳ |
| **本文要加的** `ChatToMessagesResponseTranslator` | 回程 | `(MESSAGES, CHAT)` | ⏳ |

**因此 `implementedRequestRoutes` 当前 = `{(CHAT,MESSAGES), (CHAT,RESPONSES)}`**，
缺少 `(MESSAGES, *)` 与 `(RESPONSES, *)` 的全部方向。

#### 2.4.5 M2C 落地后**会改变的调度行为**（实施时必须验证）

这是最容易忽略的连带影响：M2C 去程一注册，`(MESSAGES, CHAT)` 就进入
`implementedRequestRoutes`，于是**下游 MESSAGES 的请求会改变分流结果**。

| 场景 | 当前行为 | M2C 落地后 |
|---|---|---|
| 下游 MESSAGES，供应商勾 `[CHAT]` | ❌ 抛 `ProtocolTranslationNotSupportedException` → 400 | ✅ 翻译到 CHAT |
| 下游 MESSAGES，供应商勾 `[CHAT, RESPONSES]` | ❌ 抛错（候选 CHAT 未实现 → 跳过；RESPONSES 也未实现 → 报错） | ✅ 按回退序挑 **CHAT**（C 在 R 之前） |
| 下游 MESSAGES，供应商勾 `[RESPONSES]` | ❌ 抛错 | ❌ 仍抛错（M2R 未实现） |
| 下游 MESSAGES，供应商勾 `[MESSAGES, CHAT]` | ✅ 直连（规则 1） | ✅ 直连（**规则 1 先于优先级，不受影响**） |
| 下游 CHAT / RESPONSES | 不变 | **完全不变**（M2C 只影响 MESSAGES 作下游） |

> **第三行是回退序 `C → M → R` 的直接体现**：`CHAT` 排在 `RESPONSES` 前，
> 所以供应商同时支持两者时选 CHAT。这正是用户提醒的「优先级顺序是 C、M、R」。

#### 2.4.6 为什么**不**在翻译器里做优先级

用户判断正确。若放在翻译器里会怎样（**反面推演**）：

| 若把选择逻辑放翻译器 | 后果 |
|---|---|
| 每个翻译器要判断「我该不该被选中」 | 翻译器从「纯字段搬运」变成「参与调度决策」，违反 ADR-0003 的职责边界 |
| 需要注入 `ProviderRuntimeConfiguration` | 翻译器现在是**无状态纯函数**（只收 `Map`），注入配置会让它变成有状态 Bean，且配置变化影响翻译器单测 |
| 无法表达「去程未实现就跳过」 | 那需要全局视角（看所有候选），单个翻译器看不到 |
| 报错点前移不了 | 现在的报错在调度阶段，消息能说「到 X 的翻译均未实现」；放进翻译器就只能说「我不支持」 |

**结论**：优先级判定留在调度器是正确设计，本次 M2C **不应改变它**。

---

## 3. 组件清单

### 3.1 新建：去程 M2C

门面 `MessagesToChatRequestTranslator implements RequestProtocolTranslator`，
声明 `(MESSAGES, CHAT)`，带 `@Component`。

> ⚠️ **它必须额外做一件事**：流式时向上游注入 `stream_options.include_usage: true`。
> 不注入则上游不返回 usage，整笔 token 漏记且**不报错**。
> 四家参考实现全部做了这件事，详见 §4.3.7。

按项目既有粒度（对齐 `ChatToMessagesRequestTranslator` 下的协作类拆分），门面之下需要：

| 类 | 职责 | 对应 C2M 侧的先例 |
|---|---|---|
| `AnthropicMessageTranslator` | Anthropic `messages[]` → Chat `messages[]`：`tool_use` → 顶层 `tool_calls`、`tool_result` → 独立 `{role:"tool"}` 消息、`image` → `image_url`、`thinking` 丢弃 | `MessageTranslator` |
| `AnthropicContentBlockTranslator` | 单块转换（`text` / `image` / `document` / `tool_use` / `tool_result`） | `ContentBlockTranslator` |
| `AnthropicToolTranslator` | `{name, description, input_schema}` → `{type:"function", function:{...}}`；`tool_choice` 三态反向映射（`any`→`required`、`tool`→`{type:"function",function:{name}}`） | `ToolTranslator` |
| `ToolPairingNormalizer`（复用或新增反向版） | 见 §4.2，**方向相反** | `ToolPairingNormalizer` |

> **调研补充的三条协作类要求**（§5.5）：
> - `AnthropicMessageTranslator` 还需做 **`tool_result` 的 `name` 反查回填**（Anthropic 的 `tool_result` 只有 `tool_use_id`，
>   而 Chat 的 tool 消息可带 `name`）；建 `Map<tool_use_id, name>` 边遍历边记
> - `tool_result` 里的图片必须**提到后续 user 消息**（Chat tool 消息只能载文本），
>   四家全部这么做，但空 content 的占位文本各不相同 —— 需定一个常量写进契约
> - 纯文本 content 必须用**字符串**而非 parts 数组（sub2api 注释：strict chat upstreams reject array content），
>   与 C2M 侧 `MessageTranslator.translateUser` 同一条纪律

### 3.2 新建：回程 C2M

门面 `ChatToMessagesResponseTranslator implements ResponseProtocolTranslator`，
声明 `(MESSAGES, CHAT)`，带 `@Component`。

| 类 | 职责 | 对应 M2C 侧的先例 |
|---|---|---|
| `C2MStreamState` | 跨事件状态机：Chat chunk 流 → `message_start` / `content_block_start` / `content_block_delta` / `content_block_stop` / `message_delta` / `message_stop` | `M2CStreamState` |
| `ChatToMessagesNonStreamTranslator` | `chat.completion` JSON → Anthropic `message` JSON | `MessagesToChatNonStreamTranslator` |
| `ChatToMessagesStreamTranslator` | 逐 chunk 翻译 + 收尾 | `MessagesToChatStreamTranslator` |
| `FinishReasonMapper` | `stop`→`end_turn`、`tool_calls`→`tool_use`、`length`→`max_tokens`、`content_filter`→`refusal` | `StopReasonMapper`（反向） |
| `ChatUsageConverter` | **项目内不存在，必须新建**：Chat `usage` → Anthropic 三段式 | `AnthropicUsageAccumulator`（反向） |
| `AnthropicMessageShapes` | Anthropic 响应报文的形态常量（`type` 字段、SSE event 名） | `OpenAiResponseShapes` |

> **回程也必须处理「DeepSeek reasoning-only」这一形态**（§5.5）。
> sub2api 的 `chatMessageToAnthropicBlocks` 明确写了：当上游只给 reasoning 不给正文、
> 且无 tool_calls 时，**把 reasoning 当作可见 text 输出**，否则整轮响应为空。
> COSP 的 C2M 侧（`ChatToMessagesResponseTranslator`）应同样处理 ——
> 这与 `ChatReasoningFallbackStage` 在上游 Chat 方向做的事同源，但发生在翻译层。

> **`ChatUsageConverter` 是唯一「没有现成参照」的协作类**。已核对：
> `AnthropicUsageAccumulator` 是 Anthropic → OpenAI 方向；
> `ResponsesToChatUsageConverter` 是 Responses → Chat 方向；
> **Chat → Anthropic 方向的换算器当前不存在**。换算规则见 §4.3。

### 3.3 落库与日志：零接线，但有前提

`AfterSend.chunkRewriterFor` 只在回程命中后构造 rewriter。因此 C2M 回程一注册，
`api_call_log` 就会自动同时记录「上游 Chat chunk」与「下游 Anthropic 事件」两栏 + `frameCounts`，
日志页两栏对齐视图随之生效。

**前提**：`translateChunksForLog` 必须用**独立状态重放**，不能复用出站流的状态。
M2C 侧的实现注释已记录这个坑（落库发生在 `doFinally`，此时出站状态机的 `finalized` 已置位，
复用会产出不完整帧序列）。

---

## 4. 字段规格要点（完整契约另立）

本节只记录**结构性**映射与已知坑，逐字段清单在实施阶段写入契约文档。
现有 [C2M 请求契约](../../reference/contracts/C2M-REQUEST-CONTRACT.md) §4.3 已定的 M2C 思考映射
仍是有效前提，不在此重复。

### 4.1 顶层字段搬运

去程需要显式处置的字段（其余按白名单制丢弃，理由同 C2M：链条末尾只有 `removeIf(Objects::isNull)`，
非 null 的多余字段会原样发给上游然后 400）：

| Anthropic | Chat | 处理 |
|---|---|---|
| `model` / `stream` | 同 | 直通（`stream` 随后被 `RequestBodyAssembler` 覆写） |
| `max_tokens` | `max_tokens` | 直通，**不补默认值**（由 `MaxOutputTokensSetting` 管，但那一步对 CHAT 无支线） |
| `temperature` / `top_p` / `top_k` | `temperature` / `top_p` | `top_k` **无对应**，丢弃 |
| `stop_sequences` | `stop` | 数组 → 数组；空数组省略 |
| `thinking` / `redacted_thinking` | 见 §4.5 | `signature` 恒丢弃；thinking 明文**仅在带 `tool_calls` 时**折叠进 `reasoning_content` |
| 顶层 `system` | **落回 `messages[]`** | 见 §6.1（**待定决策 1**） |
| `tools` / `tool_choice` | 同 | 结构转换，见 §4.2 |
| `thinking` / `output_config.effort` | `reasoning_effort` | 依契约 §4.3：只在 `output_config.effort` 存在时翻译深度；`thinking` 只搬开关语义。**注意不要学 cc-switch 的 `budget_tokens` 反推档位与自动降级**（§5.6） |
| `metadata` / `service_tier` / `container` / `mcp_servers` / `context_management` | — | 丢弃（契约 §4.3 已列） |

### 4.2 工具配对：**方向相反**，不能直接复用

C2M 侧的 `ToolPairingNormalizer` 修的是「OpenAI 邻接 → Anthropic 邻接」：
把散落的 `role:"tool"` 消息**合并**成紧邻 `tool_use` 的 `tool_result` 块。

M2C 需要的是反向：Anthropic `user` 消息里的 `tool_result` 块**拆出来**，
成为独立的 `{role:"tool", tool_call_id, content}` 消息，且必须紧跟对应的 assistant 消息。

**这是本轮最容易静默出错的一处**：配错方向不会编译失败（两个方法签名可以完全相同），
症状是上游收到合法 JSON 但工具结果和调用对不上，模型行为诡异而没有报错。
建议新类命名为 `AnthropicToolResultExpander`，并在类注释里显式指向 `ToolPairingNormalizer` 说明这是反向。

同时：孤儿 `tool_result`（找不到匹配的 `tool_use`）的处置应与 C2M 同口径 —— **丢弃并留痕**，
不凭空造 `tool_calls` 去配。

### 4.3 usage：**双通道**与「落库必须是上游原生口径」

这一节比换算公式重要得多。**换算只影响下游看到的帧，绝不影响落库。**

#### 4.3.1 两条通道的分工

| 通道 | 载体 | 内容 | 谁消费 |
|---|---|---|---|
| **记账通道** | `UpstreamEvent.usage()`（`UsageTokens`） | **上游原生**归一后的值 | `api_call_usage`（明细）+ `api_usage_daily`（聚合） |
| **渲染通道** | `UpstreamEvent.data()`（帧 JSON） | **下游协议形态** | 下游客户端（Claude Desktop） |

本项目已确立这条纪律，两处现成先例：

- `MessagesToChatResponseTranslator` / `ResponsesToChatResponseTranslator` 用 `carriedUsage` 模式，
  注释明写「**只传递不重算**」—— 上游执行器挂什么值，翻译器原样搬到出口
- `pipeline/README.md`：「三种协议的累积差异……**全被生产者吸收**，出口一行协议分支都不剩」

C2M 必须**照抄这个模式**：`ChatToMessagesResponseTranslator` 也做 `carriedUsage`，
**不把 Anthropic 形状的 usage 写回槽位**。

#### 4.3.2 落库的两个表分别记什么（已核实代码）

| 表 | 写入方 | 数据来源 | 口径 |
|---|---|---|---|
| `api_call_usage` | 上游执行器（`AbstractUpstreamChatService.saveUsage` / `saveUsageIfPresent`） | `usage_raw` = 上游原始 usage JSON 全文（零损失）+ 三个 token 列由 `OpenAiUsageParser` 从**同一份原文**解析 | **上游原生** |
| `api_usage_daily` | 出口（`UsageAccounting`） | 读 `UpstreamEvent.usage()` 槽位 | **上游原生**（经生产者归一） |

两者同源，因此**永远一致**。这一点在 `AbstractUpstreamChatService` 的注释里写得很明确：
「本轮 usage 指标 —— **单次解析、两处消费**」。

#### 4.3.3 换算公式（**只用于渲染通道**）

```
Anthropic input_tokens                = Chat prompt_tokens − prompt_tokens_details.cached_tokens
Anthropic cache_read_input_tokens     = Chat prompt_tokens_details.cached_tokens
Anthropic cache_creation_input_tokens = 0
Anthropic output_tokens               = Chat completion_tokens

Anthropic 没有 total_tokens 字段
```

**为什么必须减**：Anthropic 的 `input_tokens` **不含缓存**（缓存读写是并列的两列），
而 OpenAI 的 `prompt_tokens` **已含缓存**（`cached_tokens` 只是其中标注出来的子集）。
直接搬会让 Claude Desktop 侧的输入 token 被重复计一次。
`AnthropicUsageAccumulator` 的类注释记录了同一事实的反向表述（`prompt = input + cache_read + cache_creation`），
两个方向互为逆运算，可直接对照。

**为什么 `cache_creation` 恒为 0**：Chat 协议的缓存写入拼写（`cache_write_tokens` / `cache_creation_tokens`）
是非标准扩展，且 `OpenAiUsageParser` 只提取 `cached_tokens`，不提取写入量。
Chat 上游普遍不实现显式缓存写入，填 0 是诚实表达而非缺失。

#### 4.3.4 最容易犯的错：把换算结果写回 usage 槽

**这是本 RFC 里唯一一个「会静默算错账」的坑**，必须单列。

若实现时贪图省事，在翻译器里这样写：

```java
// ❌ 错误：把下游形态的 usage 回写记账槽
UsageTokens anthropicShaped = new UsageTokens(inputTokens, outputTokens, cachedTokens);
return UpstreamEvent.classify(objectMapper, MESSAGES, frame).withUsage(anthropicShaped);
```

后果链条：

1. `UpstreamEvent.usage()` 里的 `promptTokens` 变成 `prompt − cached`（**少了缓存那部分**）
2. 出口 `UsageAccounting` 读到它 → `api_usage_daily` 的 `input_tokens` **系统性偏小**
3. 与此同时 `api_call_usage` 仍由执行器写入**原生值** → **两张表对不上**
4. **没有任何报错**。数字看起来合理，只是偏小；而且偏小的比例随缓存命中率变化 ——
   缓存用得越好的场景，算得越错。这与本项目最警惕的失效形态（「错误答案静默地看起来像正确答案」）完全一致

**正确写法**：换算结果**只**进 `data()`，槽位继续 `carriedUsage` 传递。

#### 4.3.5 另一条容易搞反的口径：`UsageTokens` 永远是 Chat 形状

`UsageTokens(promptTokens, completionTokens, cachedTokens)` 的 `promptTokens` **恒为总输入、含缓存**，
无论上游说哪种协议：

| 上游协议 | 谁做归一 | 结果 |
|---|---|---|
| CHAT | `OpenAiUsageParser`（本身就是这个形状） | 直取 |
| MESSAGES | `AnthropicUsageParser.toTokens`：`prompt = input + cache_read + cache_creation` | 加回缓存 |
| RESPONSES | `ResponsesUsageParser`：`input_tokens` 已是总量 | 直取 |

**因此 C2M 不需要对槽位做任何处理** —— 上游是 CHAT，`OpenAiUsageParser` 已产出正确口径。

> **反过来的不对称值得记住**：`AnthropicUsageParser` 里 `cachedTokens`（分子）只取 `cache_read`，
> **不含** `cache_creation`（那是成本项而非命中项）。所以「prompt 含三项之和」与
> 「cached 只含 read」是**刻意不对称**的。C2M 方向做减法时用的是同一个定义，天然一致。

#### 4.3.6 一个尚未决定的小问题：`message_start` 的 usage 填什么

Anthropic 的 `message_start` 要求带 usage，但此时上游 Chat 的 usage 还没到（在流末尾）。
四家参考实现的处理：

- sub2api：填 `{input_tokens: 估算值或 0, output_tokens: 0}`
- CLIProxyAPI：填 `{input_tokens: 0, output_tokens: 0}`

**建议填 0**（与 CLIProxyAPI 一致）：估算是伪造精确度，而 Anthropic 官方流本来也允许
`message_start` 的 `output_tokens` 为 0，真实值由 `message_delta` 补。
这一条不影响落库，只影响下游首帧的展示。

#### 4.3.7 ⚠️ 必须向上游注入 `stream_options.include_usage`（**本轮新发现，影响两条链**）

**这是本轮全局核对最重要的产出，且它不只在 M2C/C2M 范围内。**

**问题**：OpenAI 兼容上游在流式模式下**默认不返回 usage**，必须显式声明
`stream_options.include_usage: true` 才会在末尾吐一个 usage-only chunk。
而 Anthropic 下游**没有 `stream_options` 这个字段**（见 `TranslationContext.readIncludeUsage`），
因此 M2C 路线的 `TranslationContext.includeUsage` **恒为 false** —— 上游不会给 usage。

**后果链**：

1. `AnthropicUsageAccumulator` 收不到任何 usage → `message_delta` 里 usage 全 0
2. Claude Desktop 显示的 token 数是 0
3. `api_call_usage` 与 `api_usage_daily` **都不写记录**（`apiCallUsage.save` 只在 usageRaw 非 null 时写）
4. 统计页的调用次数与 token 全都不计 —— 而 `api_call_log` 仍有一条成功记录
5. **缓存命中率无从观察**（决策 4 说「命中率可通过 usage 观察」的前提被破坏）

**四家全部解决了这个问题**（已逐条核对）：

| 项目 | 做法 | 代码位置 |
|---|---|---|
| **cc-switch** | 专门的 `inject_openai_stream_include_usage`，在 `claude.rs` 的 Anthropic→OpenAI 路径上**无条件调用** | `transform.rs` L349，`claude.rs` L442 |
| **new-api** | 多个 adaptor 在流式时设 `StreamOptions = &dto.StreamOptions{IncludeUsage: true}` | `relay/channel/openai/adaptor.go` L56/L92、`relay/compatible_handler.go` L63 |
| **CLIProxyAPI** | `openai_compat_executor.go` 用 `SetBoolIfDifferent(..., "stream_options.include_usage", true)`；Kimi 执行器同理 | `internal/runtime/executor/openai_compat_executor.go` L390 |
| **sub2api** | `types.go` 有 `IncludeUsage` 字段，由 executor 按下游请求设置 | — |

cc-switch 的注释把后果写得很直白（译）：

> 流式请求必须注入 `stream_options.include_usage`，否则 OpenAI 兼容上游不在 SSE 末尾给 usage
> → 转换出的 Anthropic `message_delta` 全是 0 → 整笔 input/output/cache 漏记。

**这不是 M2C 专属问题，C2M 方向同样存在**（且更早就存在）：

`ChatToMessagesRequestTranslator` 把下游 Chat 请求翻成 Anthropic 请求时，
`stream_options` 被丢弃（Anthropic 无此字段），而 `ctx.stream()` 是恒定的下游值。
于是 C2M 路线下，**无论 Chat 下游有没有要求 usage，向上的 Anthropic 上游都拿不到它想要的东西** ——
不过 Anthropic SSE 协议**本身就带 usage**，所以 C2M 是安全的（无需注入）。

**真正需要修的是 M2C 方向**，因为它的上游是 Chat。

**修法（两个选项）**：

| 方案 | 做法 | 评价 |
|---|---|---|
| **A. 翻译器内联** | `MessagesToChatRequestTranslator.translateRequest` 在 `stream == true` 时写 `target.put("stream_options", Map.of("include_usage", true))` | 简单直接，与四家一致 |
| **B. 装配层统一注入** | 在 `RequestBodyAssembler` 写 `stream` 字段后，若 `bodyProtocol == CHAT && stream` 则补 `stream_options.include_usage` | 覆盖更广（C2R 路线也受益），但会改变 C2R 的现有行为 |

**倾向 A**：它与「`stream_options` 是 OpenAI 专属字段，翻译器负责协议字段形态」这条分工一致；
且 B 会在**没有任何翻译的直连 CHAT** 路径上也生效 —— 那是行为变更，需要单独论证。

> **注意一个反例**：CLIProxyAPI 的测试里有一条
> 「`ExecuteStream: expected payload override stream_options.include_usage=false to win over built-in default`」
> —— 说明**调用方显式设 false 时应尊重**。M2C 场景下游是 Anthropic，永远不会设这个字段，
> 因此「无条件写 true」是安全的。但若将来有别的下游协议带这个字段，需注意优先级。

**不做这一条的后果是「静默无统计」**：所有执行、响应、错误处理都正常，
只有 token 计数为 0、统计页空空 —— 与本项目最警惕的失效形态完全一致。

#### 4.3.8 全局核对发现的三处细节（本轮补充）

以下三条不属于四项原定决策，但都会影响实施正确性。

##### (1) C2M 方向已存在的同源缺陷：`include_usage` 无法传导

`ChatToMessagesRequestTranslator` 走的是「下游 Chat → 上游 Anthropic」。下游可能带
`stream_options.include_usage`，但该字段被丢弃（Anthropic 无此字段）。

**目前无害**：Anthropic 的 SSE 协议**本身就在 `message_start` / `message_delta` 里带 usage**，
不依赖下游要不要。

**但要记一笔**：`TranslationContext.includeUsage` 在 C2M 路线上恒为 false（因为
`C2MStreamState` 的构造参数来自被丢弃前的下游值，而下游确实可能带 `true`）。
这个字段在 C2M 路线上的语义是「下游要不要 usage chunk」，而 Chat 下游要、Anthropic 上游给
—— 目前**恰好能对上**（M2C 侧 `includeUsage` 控制的是要不要多发一个 usage-only chunk）。

**结论**：不需要改，但 §4.3.7 的 M2C 注入必须**只加在 M2C 方向**，
不要顺手改成「所有翻译器都注入」—— 那会破坏 C2M 的现有语义。

##### (2) `max_tokens` 方向不对称：M2C 不需要补默认值，但要确认上游是否必填

| | Anthropic | Chat |
|---|---|---|
| `max_tokens` | **必填** | 可选 |
| 由谁补 | `MessagesMaxTokensStage`（`MaxTokensNormalizer.ensureMaxTokens`） | 无支线，跳过 |

M2C 是「Anthropic 必填 → Chat 可选」，所以**翻译后不需要补默认值**（Chat 侧缺了也合法）。
但 `MessagesMaxTokensStage` 查的是 `bodyProtocol`，M2C 翻译后切到 CHAT → **不执行**，
行为正确。

**反过来要确认的是**：某些 Chat 上游（如 OpenAI 官方的 o-series）可能要求
`max_completion_tokens` 而非 `max_tokens`，且部分上游对两者都缺失的请求报错。
但按本项目原则（不按模型名硬编码能力表、尊重用户配置），**原样搬运、让上游报错**，
不加任何推导 —— 与 C2M 侧 `ChatToMessagesRequestTranslator` 的「不补 max_tokens 默认值」
同一条纪律。

##### (3) `stop_sequences` 与 `stop` 的形态差异已被 C2M 侧钉住，M2C 要对称

C2M 侧「`stop` 是字符串或数组 → 一律包成 `stop_sequences` 数组」，M2C 反向：

| Anthropic | Chat | 处理 |
|---|---|---|
| `stop_sequences: []`（空数组） | — | **省略**（Chat 的空数组会让部分上游 400） |
| `stop_sequences: ["a"]`（单元素） | `stop: "a"` 或 `["a"]` | 两种都合法。参考四家：new-api 单元素 → 字符串；CLIProxyAPI → 数组；cc-switch → 数组 |
| `stop_sequences: ["a","b"]` | `stop: ["a","b"]` | 数组 |

**建议单元素输出字符串**（与 new-api 一致）：这是最常见的形态，且部分严格上游对
单元素数组也接受。**与 C2M 侧不对称是有意的** —— C2M 那一侧 Anthropic 要求必须是数组，
所以不能保留字符串。

**必须拒绝非字符串元素**：C2M 侧已有此纪律（`{"stop":[1,2]}` 报错而非跳过），M2C 对称。

### 4.4 流式：帧数不对等与终止标记

C2M 回程必须处理两类「形态契约」，缺一个是硬失败而非降级：

1. **每帧 JSON 必须有 `type` 字段** —— `AnthropicController.extractEventType` 靠它回填 SSE 的
   `event:` 名。Anthropic 客户端是状态机，只发 `data` 无法解析（会表现为「流有数据但界面不动」）。
2. **末尾必须产出 `message_stop`** —— `UpstreamEventClassifier` 按 MESSAGES 协议识别它，
   `StreamLifecycle` 的 Layer 1 靠它即时收尾。缺它则只能等 Layer 2（连接关闭）兜底，
   而部分上游发完不关连接（HTTP keep-alive），表现为 Toast 永远悬挂在 CHUNK。
   这与 M2C 侧「`[DONE]` 由流结束触发而非绑定某个事件」是同一类问题，处置方向相反。

**不需要**产出 `[DONE]`（那是 Chat 的哨兵）。

帧数对照（Chat → Anthropic）：

| 帧数 | Chat chunk |
|---|---|
| 0 | `{role:"assistant"}` 空 delta、`finish_reason` 非 null 但无 delta |
| 1 | 首个带内容的 chunk（同时产 `message_start` 需要提前发出 —— 见下） |
| 多 | 收尾（`message_delta` + `message_stop`）、带 `tool_calls` 的首个 delta（同时开 `content_block`） |

> **`message_start` 的时机**：Anthropic 要求它是**第一帧**，且携带 usage（输入 token）。
> 但 Chat 的 usage 通常在**最后一个** chunk（且只有 `stream_options.include_usage` 时才给）。
> 因此 `C2MStreamState` 必须在**首个 chunk 到达时**发 `message_start`，
> 其 usage 字段填已知值（通常是 0），真实 usage 在 `message_delta` 里补 ——
> 这与 Anthropic 官方流的行为一致（`message_start` 的 `output_tokens` 恒为 1 或 0，
> `message_delta` 才给最终值）。这一条需要在实施时用真实上游实测确认。

> **`message_delta` 不能随 `finish_reason` 一起早发**（调研补充，§5.2）。
> 权威 usage（含 `cached_tokens`）通常出现在 `finish_reason` **之后的** usage-only chunk 里。
> 若见了 `finish_reason` 就收尾，缓存统计会恒为 0。
> CLIProxyAPI 的判据（可参照实现）：
>
> ```
> isTrailingUsageChunk = 有 usage && 无 choices[0]
>                        && (已收到 finish_reason || 开过任一 block || 有累积内容)
> 只有 isTrailingUsageChunk 为真，或收到 [DONE]，才发 message_delta + message_stop
> ```

### 4.5 thinking 块的回放（**会推翻契约 §4.7 的 M2C 部分**）

原契约 [C2M 请求契约](../../reference/contracts/C2M-REQUEST-CONTRACT.md) §4.7 写「M2C 丢弃 `thinking` / `redacted_thinking` 块」，
理由是跨协议造不出 `signature`。

**§5.5(1) 的调研表明这个结论在工具链场景下会导致硬失败**：

- sub2api / cc-switch / CLIProxyAPI 三家都在 **assistant 消息带 `tool_calls` 时**
  把 thinking 明文折叠进 `reasoning_content`
- sub2api 的注释给出硬理由：DeepSeek 的 thinking 模式**要求**产生该工具调用的
  `reasoning_content` 被回放，否则直接 400
- COSP 的模型支持清单里 DeepSeek / MiMo 都是一等公民，因此这条不能忽略

**修正后的口径**：

| 场景 | `signature` | thinking 明文 | `redacted_thinking` |
|---|---|---|---|
| assistant 带 `tool_calls` | 丢弃 | **折叠进 `reasoning_content`**（多块用 `\n` 连接） | 丢弃 + warn |
| assistant 纯文本轮 | 丢弃 | 丢弃（三家一致：不带 tool_calls 时不回放） | 丢弃 + warn |

**待定细节**：当 assistant 带 `tool_calls` 但**上游没给 thinking 明文**时，是否给占位
（cc-switch 用 `"tool call"`）。占位能避免「有 tool_calls 但 `reasoning_content` 为空」被拒，
但会污染下游统计。**倾向：不给占位**，先让上游报错 —— 因为占位是伪造内容。

#### 4.5.1 已定案（2026-10-20）

✅ **按三家共识执行**：assistant 带 `tool_calls` 时，把 thinking 明文回放进 `reasoning_content`。

**不给占位**（与 cc-switch 的 `"tool call"` 不同）：没有 thinking 明文就不写 `reasoning_content`
字段。理由是本项目一贯的「不伪造上游内容」原则 —— 若上游因此报错，那个错误是真实信息，
比一个让调用「看起来成功」的占位字符串更有价值。

#### 4.5.2 这**不涉及**思考链缓存问题 —— 两个机制必须分清

用户的判断是对的。下面把两个容易混淆的机制并排说清，以免实施时误加一层缓存。

| | **「思考链缓存」**（本项目 V8.2 已删除） | **「thinking 明文回放」**（本次要做的） |
|---|---|---|
| 做什么 | 代理侧**存储**上一轮的 `reasoning_content`，下一轮**注入**回去 | 只是**翻译字段**：把请求里已有的 thinking 块搬成 `reasoning_content` |
| 是否跨请求持有状态 | **是**（SQLite 表 `reasoning_cache`） | **否**，纯函数式翻译，无状态 |
| 触发原因 | Copilot 早期版本丢失了 `reasoning_content`，客户端不自己回传 | Claude Desktop **自己会把 thinking 块带回来**，我们只需搬运 |
| 现状 | **已删除**（`SchemaMigrationRunner.migrateToV82RemoveReasoningCache` 执行 `DROP TABLE IF EXISTS reasoning_cache`） | 待实现 |

**为什么不需要再建缓存**：`COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md` 的结论是
「后续请求所需的 `reasoning_content` 并非由 COSP 补充，而是已经由上游客户端保留并重新发送」。
那条结论对 Claude Desktop 同样成立 —— 而且更直接：Anthropic 协议的 assistant 消息**本来就带
`thinking` 块**，客户端会把整段历史原样回传。**数据已经在请求体里了，我们只做格式转换。**

> **实施时的判断依据**：如果发现自己在写「存到某处、下一轮再取出来」的代码，
> 那就走错方向了 —— 那正是 V8.2 删掉的东西。

#### 4.5.3 `signature` 的处置（含一个未闭环的风险）

`signature` 是 Anthropic 用私钥对思考内容签发的完整性凭证，**跨协议无法生成**，因此恒丢弃。

但这里有一个**必须记录的风险**：sub2api 的 `types.go` 注释写明：

> Anthropic always sends `signature` on thinking blocks (empty on `content_block_start`);
> strict clients such as Grok Build reject the block with "missing field `signature`"
> when the key is absent.

**也就是说：`signature` 键可以存在但为空串，不能整个缺失。** 四家回程实现的实际做法：

| 项目 | 回程 thinking 块是否带 `signature` |
|---|---|
| new-api | 不带（`ClaudeMediaMessage` 的 `Signature` 未赋值） |
| sub2api | 不带（`AnthropicContentBlock.Signature` 未赋值），但它的 `MarshalJSON` 会在 `type=thinking` 时**强制输出该键**（值可为空串） |
| cc-switch | 不带 |
| CLIProxyAPI | 不带 |

**C2M 回程需要验证**：Claude Desktop 是否接受缺失 `signature` 键的 thinking 块。
已知有严格客户端（Grok Build）会拒绝，因此建议**按 sub2api 的做法**：
构造 thinking 块时**保留 `signature` 键并置空串**，而不是省略。这需要实机验证。

> 这与 §4.7 的既有结论不冲突 —— 那里说的是「造不出**有效签名**」，这里说的是
> 「键必须存在，值可以是空」。两者是不同层面的事。

---

## 5. 四参考项目实现对比（2026-10-20 调研）

> 四个参考项目均已拉取到调研当日的最新提交，全部只读。
> 本节记录它们的**实际做法**，是 §6 四项决策的依据。

### 5.0 调研对象与定位

| 项目 | 提交 | 相关文件 | 方向 | 特点 |
|---|---|---|---|---|
| **new-api** | `32e587406`（2026-10-19） | `relaykit/relayconvert/internal/claude_messages/to_oai_chat_req.go`（347 行）<br>`…/to_oai_chat_resp.go`（574 行）<br>`…/shared/claude/usage.go`、`media.go`、`reasoning.go` | 双向皆有（另有独立的 `oai_chat/` 目录做 C2M/M2C） | 转换矩阵式布局，按「源协议目录 → 目标协议文件」组织；带诊断系统 `convdiag` |
| **sub2api** | `8f2cbb53d`（2026-10-19） | `backend/internal/pkg/apicompat/chatcompletions_anthropic_bridge.go`（961 行） | **单文件直连 bridge** | 明确声明「跳过 Responses 中间表示」；注释极详尽，逐条记录踩坑 |
| **cc-switch** | `f92ff0b0`（2026-10-19） | `src-tauri/src/proxy/providers/transform.rs`（2362 行） | Rust，单文件双向 | 专注 Claude Code / Codex 路由；带模型能力钳制表 |
| **CLIProxyAPI** | `ae3ee3be`（2026-10-20） | `internal/translator/openai/claude/openai_claude_request.go`（25 KB）<br>`…/openai_claude_response.go`（40 KB） | 注册表式（`translator.Register(Claude, OpenAI, …)`） | 流式状态机最完整，时序约束写得最细 |

> **方向辨析**：CLIProxyAPI 的 `internal/translator/claude/openai/chat-completions/` 是
> **OpenAI → Claude（C2M）**，`internal/translator/openai/claude/` 才是本文要的
> **Claude → OpenAI（M2C + C2M 回程）**。命名规律是「`<目标>/<源>/`」而非「源→目标」，
> 曾按直觉读反过一次。

---

### 5.1 决策 1 依据：`system` 落回 `messages[]`

**四家全部在翻译器内联，没有一家有独立的「system 归一支线」概念。**

| 项目 | 字符串形态 | 数组形态 | 拼接分隔符 | billing header 过滤 |
|---|---|---|---|---|
| new-api | 一条 `{role:"system"}` | 全部 text 块拼成一条 | **无分隔**（`strings.Builder` 直拼） | 否 |
| sub2api | 一条 `{role:"system"}` | `joinResponsesContentPartText` | **`\n\n`** | **是**（`parseAnthropicSystemContentParts` 显式过滤） |
| cc-switch | 一条 `{role:"system"}` | 逐块取 text | **`\n`** | **是**（`strip_leading_anthropic_billing_header`） |
| CLIProxyAPI | 一条 role=system，**content 保留 parts 数组** | 逐块 `convertClaudeContentPart` | 不拼接（保留结构） | 部分（`IsClaudeCodeAttributionSystemText`） |

**结论**：

1. **方案 A（翻译器内联）符合行业惯例**，四家无一例外。我们的方案 B（新增 `ChatSystemPromptStage` 支线）
   在架构上更符合四轴框架，但它是一个**只有本项目才会做的抽象** —— 代价是要新增一条对直连 CHAT
   也会执行的路径。
2. **四家的分隔符互不相同**（无分隔 / `\n\n` / `\n` / 不拼），说明这里没有权威答案。
   COSP 已有 `SystemPromptNormalizer.mergeSystem` 用 `\n\n`，**建议 M2C 对齐 `\n\n`** 以保持两条方向一致。
3. **新增一条原 RFC 漏掉的必做项：过滤 Claude Code 的 attribution / billing header。**
   sub2api 与 cc-switch 都显式做了，sub2api 的注释直接点名「filters the billing header」。
   这与 [Claude Desktop 缓存 RFC](./2026-10-17-claude-desktop-prompt-cache.md) 观察到的
   `system[0]` billing header 是同一个东西 —— 那条经验在 M2C 方向同样适用。

---

### 5.2 决策 2 依据：usage 是否无条件发出

**四家全部无条件发 usage**，没有一家根据「下游有没有要求」来决定。

| 项目 | 触发条件 |
|---|---|
| new-api | `UsageFromOpenAI` 直接换算，流式收尾恒发 |
| sub2api | `chatUsageToAnthropicUsage`，在 `message_delta` 里恒发 |
| cc-switch | 同上 |
| CLIProxyAPI | `emitAnthropicMessageDelta(...)` 在收尾时恒发 |

**换算口径四家完全一致**：

```
input_tokens  = prompt_tokens − cached_tokens − cache_creation_tokens
cache_read    = prompt_tokens_details.cached_tokens
cache_creation= prompt_tokens_details.cache_write_tokens ?? cache_creation_tokens
output_tokens = completion_tokens
```

**三处必须记住的细节**：

1. **`cache_write_tokens` 与 `cache_creation_tokens` 是同一数量的两种拼写，不可相加。**
   sub2api、new-api、CLIProxyAPI 三家都在注释里明写这一点，且都是「优先 write、回退 creation」。
2. **减法要做负数保护**（`if inputTokens < 0 { inputTokens = 0 }`）—— 三家都有。
3. **new-api 多一层语义判断**：若上游 usage 本身已是 Anthropic 语义
   （`UsageSemantic == Anthropic`），则**不做减法**（否则会把已经分开的量再减一次）。
   我们的 `UpstreamEvent.usage()` 是归一后的 `UsageTokens`，不存在这个歧义，但值得记录。

**结论**：决策 2 定为 **A（无条件发）**，四家一致支撑。

---

### 5.3 决策 3 依据：`document` 块处置 —— **四家 2:2 分裂，但支持方有共同方案**

| 项目 | 处置 | 代码位置 |
|---|---|---|
| **new-api** | `source.type == "text"` → Chat `{type:"text", text: data}`<br>`base64 + application/pdf` → **Chat `{type:"file", file:{filename:"document.pdf", file_data:"data:..."}}`**<br>其它 → `convdiag` 记 `Severity: Error` 并**跳过该块** | `to_oai_chat_req.go` L205–232 |
| **CLIProxyAPI** | 仅 `source.type == "base64"` → **Chat `{type:"file", file:{filename, file_data}}`**<br>filename 取 document 块顶层 `filename` 字段<br>`container_upload` 走同一函数<br>`source.type == "file"`（Files API 引用）→ 转换失败并上报 | `openai_claude_request.go` L568–601 |
| **sub2api** | **静默丢弃**（switch 无 default 分支） | `chatcompletions_anthropic_bridge.go` L168–254 |
| **cc-switch** | **静默丢弃**（`_ => {}`） | `transform.rs` `convert_message_to_openai` |

**关键发现：Chat Completions 存在 `file` content part 扩展**，不是所有实现都知道：

```json
{"type": "file", "file": {"filename": "document.pdf", "file_data": "data:application/pdf;base64,..."}}
```

- new-api 的 `dto.ContentTypeFile = "file"` 与 `MessageFile{FileName, FileData, FileId}` 是显式建模的
- 它**不属于 OpenAI 官方 Chat Completions 规范**，是部分兼容供应商支持的扩展
- new-api 只有在「PDF base64」时才用它；**纯文本 document 则降级成普通 text 块**
- 两家都在**无法转换时给出诊断/上报**，不是静默丢弃

**这直接改变了决策 3 的选项集**：原 RFC 列的四个选项（丢弃/伪造提示/提取文本/报错）**都不完整**，
真实行业做法是第五种 —— **转成 `file` content part，转换不了才降级**。

> **尚未验证的前提**：目标上游是否接受 `file` part。这必须实机验证（`tools/live-test/` D 组）。
> 若不接受，上游会回 400；那时需要按「按供应商配置决定是否启用 file part」处理，
> 而不是全局写死。

---

### 5.4 决策 4 依据：`cache_control` 断点

| 项目 | 处置 |
|---|---|
| new-api | 搬进 Chat `MediaContent.CacheControl` —— **但只在 `isOpenRouter && 上游模型是 anthropic/claude` 分支**；普通分支拼字符串时**丢失** |
| sub2api | 不处理（丢弃） |
| cc-switch | 不处理（丢弃） |
| CLIProxyAPI | 不处理（丢弃） |

**结论**：丢弃是主流（3:1）。唯一保留 cache_control 的 new-api，其保留条件恰好是
**「上游本身也是 Anthropic」** —— 那在 M2C 里不会出现（上游一定是 Chat）。

> **重要补充（2026-10-20 二次核实）**：不要把这个结论理解成「缓存要失效了」。
> Chat 侧的**自动前缀缓存不看任何标记**，只看「请求前缀是否逐字节相同」。
> `cache_control` 丢失丢掉的是**边界控制权**，不是缓存本身。
> 另：四家**没有一家**把 `cache_control` 转成 `prompt_cache_key` —— 两者语义不同，无法互译。
> 完整分析与决策依据见 §6.4。

---

### 5.5 四家一致、但原 RFC 没写的**必做项**

以下四条在四家中出现频率高到应视为「事实标准」，其中第一条直接影响功能可用性。

#### (1) 工具链的 `reasoning_content` 回放 —— **会推翻契约 §4.7 的一条适用性**

| 项目 | 做法 |
|---|---|
| sub2api | `anthropicThinkingToReasoningContent(blocks, hasToolCalls)` —— **只在 assistant 带 `tool_calls` 时**把 thinking 折叠进 `reasoning_content`；普通文本轮不带 |
| cc-switch | 同上条件（`preserve_reasoning_content && role == "assistant" && !tool_calls.is_empty()`）；**无 thinking 内容时给 `"tool call"` 占位** |
| CLIProxyAPI | 只在 `role == "assistant"` 且 `shouldMapClaudeThinkingToGPTReasoning(...)` 时收集，`\n\n` 连接 |
| new-api | 走 `reasoning.ApplyToOpenAIChat`（完整 reasoning intent 系统） |

sub2api 的注释给出了硬理由：

> DeepSeek's thinking mode requires the reasoning_content that produced a tool call
> to be replayed on that assistant message and **answers 400 otherwise**.

**这对我们意味着什么**：

原契约 [C2M 请求契约](../../reference/contracts/C2M-REQUEST-CONTRACT.md) §4.7 定的是「M2C 丢弃 thinking 块」，
理由是跨协议造不出 `signature`。那个结论**在纯对话场景成立，但在工具链场景会导致硬失败** ——
DeepSeek / MiMo 这类要求 `reasoning_content` 回放的供应商会直接 400。

**修正方向**（需在契约里改写 §4.7 的 M2C 部分）：

- `signature` 仍然丢弃（造不出来）
- 但 **`thinking` 的明文内容，在 assistant 消息带 `tool_calls` 时**要折叠进 `reasoning_content`
- 是否给占位（cc-switch 的 `"tool call"`）是一个待定细节：占位能避免「有 tool_calls 但 reasoning_content 为空」被拒，
  但会污染下游统计

> 这一条比原四项决策更紧迫 —— 不做的话，我们这条链在**任何工具调用场景**下都可能不可用。

#### (2) 工具消息的 `name` 字段需要从 `tool_use` 反查回填

Anthropic 的 `tool_result` 块**只有 `tool_use_id`，没有 tool name**；而 Chat 的 `{role:"tool"}` 消息
**可以**带 `name`。new-api 与 CLIProxyAPI 都做了反查回填：

- new-api：`toolNames[mediaMsg.Id] = mediaMsg.Name` 边遍历边建表，`tool_result` 无 name 时先记 index，
  最后 `*openAIMessages[result.index].Name = toolNames[result.id]`
- CLIProxyAPI：`toolNameByID[toolUseID]` → `toolResultJSON.name`

sub2api 与 cc-switch 不回填（依赖上游不严格）。**建议回填** —— 成本低，且能提高对严格上游的兼容性。

#### (3) `tool_result` 里的图片必须提到后续 user 消息

四家**全部**这么做，理由一致：Chat 的 tool 消息只能承载文本。

| 项目 | 具体做法 |
|---|---|
| new-api | 图片进 `mediaMessages`（即当前 user 消息），无文本时给 `"[image]"` 占位 |
| sub2api | `toolResultImageParts` 延后追加到同一 user 消息 |
| cc-switch | `pending_tool_media` + `flush_pending_chat_tool_media`，**在所有 tool 消息之后、普通内容之前** flush 成一条 user 消息 |
| CLIProxyAPI | `relayedToolImages` → 一条 user 消息，首块是提示文字 `"Images returned by the preceding tool call(s):"` |

**四家的空 tool content 占位各不相同**（`"[image]"` / `"[Tool returned image content...]"`），
说明这也是无权威答案的地方 —— 建议定一个常量并写进契约。

#### (4) 纯文本时用字符串 content，有图才用 parts 数组

sub2api 的注释直说：

> strict chat upstreams reject array content

这条与 COSP 已实现的 C2M 侧 `MessageTranslator.translateUser` **同一条纪律**
（「字符串 content 保持字符串形态，不升格成块数组」）。**M2C 必须对称实现。**

---

### 5.6 四家做法**不建议学**的地方

调研的价值不只是「该怎么做」，也包括「为什么不能那么做」。以下与 COSP 既有契约冲突，
列出以免实施时被「业界都这么干」带偏：

| 做法 | 出处 | 为什么不学 |
|---|---|---|
| **抬高 `max_tokens` 到 floor** | sub2api（`DefaultMinMaxOutputTokens`） | 契约 §4.6 第 3 条已定「绝不抬高 `max_tokens`」—— 可能超模型输出上限而 400 |
| **自动降级 effort**（`max`→`xhigh`；thinking 关闭时 effort 强制 `low`） | cc-switch | 契约 §4.5 已定「不做自动降级」。用户明确要 `max` 却静默收到 `xhigh` |
| **按模型名硬编码能力表**（`gpt-5-pro` 恒 `high`；`gpt-*-pro` 的 `low`→`medium`；`is_openai_o_series` 决定字段名） | cc-switch | AGENTS.md 硬约束：中转站会改模型名，按前缀硬编码必然大面积失效 |
| **reasoning 模型按模型名判断后不发 `temperature`/`top_p`** | sub2api（`isReasoningModel`） | 同上。COSP 的做法是「原样发出，让上游用错误码回答」 |
| **无条件注入 `parallel_tool_calls: true`** | sub2api、new-api | 契约 §4.7 已把它列为反面案例 |
| **`redacted_thinking` 注入占位文本**（`"[redacted thinking]"`） | cc-switch | 伪造上游内容。COSP 应显式丢弃并留日志 |
| **`output_config.effort` 未知值时静默丢弃** | cc-switch | 与「尊重用户配置」冲突；COSP 倾向原样发出让上游报错 |

---

### 5.7 本节结论对 §6 的影响

| 决策 | 调研前倾向 | 调研后 | 变化的理由 |
|---|---|---|---|
| 1. `system` 落点 | B（新增支线） | **B（已定案）** | 用户定案：主干-支线结构本来就是为了方便实现各协议翻译；四家全内联，但它们都没有这类注册表基建 |
| 2. usage 无条件发 | A | **A（已定案）** | 四家一致 |
| 3. `document` 处置 | 四选一待定 | **方案 5：转 `{type:"file"}`（已定案）** | 用户定案：尽量保证下游行为不变，上游不支持是上游的问题；需在 Claude Desktop 单独验证 |
| 4. `cache_control` | 丢弃 | **丢弃（已定案）** | 四家无双转成任何东西的先例；且缓存不会真失效（Chat 侧自动生效），丢的只是边界控制权 |
| **（新增）thinking 回放** | 契约 §4.7 定「丢弃」 | **已定案：带 `tool_calls` 时回放 reasoning_content，不给占位** | sub2api/cc-switch/CLIProxyAPI 三家一致，且是 DeepSeek/MiMo 的硬要求；用户确认按三家共识 |

---

## 6. 决策（均已定案）

以下 4 项会改变架构或产品行为。每项都已在 §5 调研后标出**行业惯例**与**本项目已有契约是否冲突**。

> **定案状态（2026-10-20）**：
> | # | 决策 | 结论 |
> |---|---|---|
> | 1 | `system` 落回 `messages[]` | **方案 B：新增 `ChatSystemPromptStage` 支线** |
> | 2 | usage 是否无条件发 | **方案 A：无条件发**，且换算不得回写记账槽 |
> | 3 | `document` 块处置 | **方案 5：转 Chat `{type:"file"}`**，尽量保持下游行为不变 |
> | 4 | `cache_control` 处置 | **丢弃**（定案）；不做 `prompt_cache_key` 注入；保证前缀稳定 |
> | 5 | thinking 回放（非原定决策） | **按三家共识：assistant 带 `tool_calls` 时回放 thinking 明文**；不给占位；不重建思考链缓存（§4.5.1） |
> | 6 | `include_usage` 注入（本轮新发现） | **方案 A：翻译器流式时写 `stream_options.include_usage`**；不注入会导致整笔统计漏记（§4.3.7） |
>
> 另需修正契约 §4.7（M2C 部分的 thinking 处置）—— 见 §4.5。

### 6.1 决策 1：`system` 如何落回 `messages[]`

**问题**：Anthropic 的 `system` 是**顶层字段**，Chat 的 system 是 **`messages[]` 里的一条**。
搬过去需要一个「落回」动作。而 `RequestBodyAssembler` 按 `ctx.bodyProtocol()` 查三张支线表：

| 步骤 | 现有 MESSAGES 支线 | 现有 CHAT 支线 |
|---|---|---|
| system 归一 | `MessagesSystemPromptStage`（抬升到顶层） | **无实现** |
| max_tokens | `MessagesMaxTokensStage` | 无（Chat 侧可选，跳过正确） |
| 思考注入 | `MessagesThinkingStage` | `ChatThinkingStage`（写 `reasoning_effort`） |

M2C 翻译后 `bodyProtocol` 切成 CHAT，于是 system 那一步**查不到实现 → 跳过**。

**两个方案**：

| 方案 | 做法 | 代价 |
|---|---|---|
| **A. 翻译器内联** | 在 `MessagesToChatRequestTranslator.translateRequest` 里把顶层 `system` 插成 `messages[0]` | 简单、不碰骨架；但违反 C2M 已确立的分工先例（「翻译器不管 system 语义归一化」，见 `ChatToMessagesRequestTranslator` 类注释的「本类不做什么」） |
| **B. 新增支线** | 新建 `ChatSystemPromptStage implements SystemPromptNormalizeStage` + `SystemPromptNormalizer.inlineSystemIntoMessages` | 与 C2M 完全对称、符合四轴框架；但该支线对**直连 CHAT** 也会执行 —— 实际是 no-op（Chat 客户端不发顶层 `system`），但这是一条「新增的每次请求都会跑的路径」 |

**倾向 B**，理由是四轴框架的判据是「协议差异应是数据/支线而非主干分叉」，
而 A 把协议差异塞进了翻译器。但 B 需要确认「直连 CHAT 时的 no-op 是否真的无害」
（若某个客户端**同时**发顶层 `system` 和 `messages` 里的 system，B 会改变其行为）。

> ✅ **已定案：方案 B**（2026-10-20）。用户判断：主干-支线结构本身就是为了方便实现各协议的翻译功能，
> system 落回 `messages[]` 是典型的「协议特定步骤」，放进支线是这套结构的正确用法。
> 四家参考项目全内联，但它们都没有这类注册表基建，不能作为反面理由。
>
> **实施时必须处理的两个细节**（§5.1）：
> - 数组形态 `system` 合并时用 `\n\n` 分隔（对齐 COSP 既有的 `SystemPromptNormalizer.mergeSystem`）
> - **过滤 Claude Code 的 billing header / attribution 文本** —— sub2api 与 cc-switch 都显式做了，
>   漏掉它会让上游每轮多收一段无意义文本
>
> **B 的直连 CHAT 风险已评估**：Chat 客户端不会发顶层 `system`，因此 `ChatSystemPromptStage` 在直连时是 no-op。
> 若某客户端**同时**发顶层 `system` 与 `messages` 里的 system，该支线会把两者合并 —— 这是**行为变更**，
> 需在支线实现里加一条单测钉住这个语义。

### 6.2 决策 2：usage 是否无条件发出

**问题**：`TranslationContext` 只有两个字段 `(wasStream, includeUsage)`，
而 `includeUsage` 读的是 Chat 专属的 `stream_options.include_usage`。
Anthropic 下游**没有这个概念** —— 它的 SSE 协议规定 `message_start` / `message_delta` 必须带 usage。

**选项**：

| 方案 | 做法 |
|---|---|
| **A. 无条件发** | `C2MStreamState` 不看 `context.includeUsage()`，恒发 usage 帧 |
| **B. 扩 `TranslationContext`** | 加一个 `downstreamExpectsUsage` 字段，由翻译器按下游协议决定 |

**倾向 A**：`includeUsage` 的语义是「下游**要求**附带」，而 Anthropic 是「下游**协议规定**必须带」——
这是两种不同的事实。A 在翻译器里硬编码符合「谁的协议谁负责」，B 会让 `TranslationContext`
承载一个只有一条链用得上、且值恒为 true 的字段。

> **调研后：A 已可定案**（§5.2）。四家无一例外都无条件发，且换算口径完全一致：
> ```
> input_tokens   = prompt_tokens − cached_tokens − cache_creation_tokens   // 负数钳到 0
> cache_read     = prompt_tokens_details.cached_tokens
> cache_creation = prompt_tokens_details.cache_write_tokens ?? cache_creation_tokens
> ```
> **两个坑**：
> 1. `cache_write_tokens` 与 `cache_creation_tokens` 是**同一数量的两种拼写，不可相加**（new-api / sub2api / CLIProxyAPI 三家都在注释里明写）
> 2. **`message_delta` 不能早发** —— 权威 usage（含 `cached_tokens`）通常在后置的 usage-only chunk 里。
>    CLIProxyAPI 的做法是只在「trailing usage-only chunk」或 `[DONE]` 时发，否则缓存统计恒为 0
>
> **但这里有一条比换算更硬的约束（用户提醒 + 已核实代码，见 §4.3）**：
> **换算只影响下发帧，绝不得回写 `UpstreamEvent.usage()` 槽位。**
> 落库（`api_call_usage` 与 `api_usage_daily`）记的必须是**上游原生日径**，
> 否则两张表会对不上，而且**不会报错**。具体写法与后果链见 §4.3.4。

### 6.3 决策 3：`document` 内容块如何处置（**与本次需求直接相关**）

**问题**：Claude Desktop 的文档处理能力靠 Anthropic 的 **`document` content block**
（base64 PDF / 纯文本文件）。而 **Chat Completions 官方规范里没有文档类型** —— 它只有 `image_url`。

| 上游协议 | 文档能否透传 |
|---|---|
| CHAT | **部分**（非官方扩展 `{type:"file"}`，见下） |
| RESPONSES | ✅ 官方支持（`input_file`）—— 但那是 M2R 方向，不在本分支 |

**候选处置**（前四项是原 RFC 列出的，第五项是调研新发现）：

1. **丢弃 + `log.warn`** —— 与 C2M 对 hosted 工具块的处置同调（`.orElse(null)`）
2. **丢弃 + 在下游响应里追加可见提示** —— 但这是代理在伪造模型输出，语义上是错的
3. **提取 PDF 文本后塞进 content** —— 需要 PDF 解析依赖，超出翻译层职责，且会改变 token 计数
4. **报错拒绝整条请求**（`RequestTranslationException`）—— 与 C2M 的「不认识的块类型报错」同调
5. **转成 Chat `{type:"file"}` content part**（**调研新发现**）：
   ```json
   {"type":"file","file":{"filename":"document.pdf",
                            "file_data":"data:application/pdf;base64,JVBERi0..."}}
   ```
   纯文本 document（`source.type == "text"`）则降级成普通 `{type:"text"}` 块，不丢内容

**行业惯例：2:2，但支持方有共同方案**（§5.3）：

| 项目 | 做法 |
|---|---|
| new-api | 文本 → text 块；PDF base64 → `file` part；**其它 → 报错级诊断 + 跳过** |
| CLIProxyAPI | base64 → `file` part（filename 取块顶层 `filename`）；Files API 引用（`source.type=="file"`）→ 无法转换并上报 |
| sub2api | 静默丢弃 |
| cc-switch | 静默丢弃 |

**关键事实：`{type:"file"}` 不属于 OpenAI 官方 Chat Completions 规范**，
是部分兼容供应商支持的扩展。new-api 把它显式建模成 `dto.ContentTypeFile`，
说明它有实际使用者，但**不能假设所有上游都接受**。

**推荐：方案 5**，理由：

- 它是唯一「既不丢信息也不伪造内容」的做法，与用户选 Claude Desktop 的原始动机直接对齐
- **但必须实机验证目标上游是否接受 `file` part**：若不接受会回 400，
  此时需要「按供应商配置开关」而不是全局写死

> ✅ **已定案：方案 5（2026-10-20）**。用户的指导原则是
> **「应尽量保证下游行为不变，上游不支持是上游的问题」** —— 翻译层的职责是如实搬运，
> 而不是替上游做降级决定。若上游拒绝 `file` part，把上上游的原生的 400 如实透传即可。
>
> **必须单独验证的一项**（用户指定，已列入 §10 验收清单）：
> 在 **Claude Desktop 真实客户端**里发一份 PDF，确认：
> 1. 请求确实带 `{type:"file"}` 且被上游接受（或得到可理解的 400）
> 2. 模型确实“看到”了文档内容（而非拿不到但无报错）
> 3. 纯文本 document（`source.type == "text"`）降级成 text 块后内容完整
>
> **兜底**：若实机验证发现上游普遍不接受，再改为「按供应商配置开关」。
> 那时也**不要**退回静默丢弃 —— 应保留显式报错，否则用户看不出文档没被处理。

> **附带事实**：`/v1/messages/count_tokens` 端点**不存在**（已核对，全项目无此路径）。
> Claude Desktop 在长上下文 / 文档场景会调它，当前得到 404。
> 这不属于翻译层，但会成为下一个缺口 —— 建议先记入 `KNOWN_DEBT.md`，避免届时误判为翻译 bug。
> **CLIProxyAPI 已实现 `ClaudeTokenCount`**（返回 `{input_tokens: N}`），可作为将来实现的参照。

### 6.4 决策 4：`cache_control` 断点的处置

**先说清楚它到底是什么，以及我此前的一个错误说法。**

> ⚠️ **更正**：本节早先写的「Chat 协议没有对等物 —— OpenAI 的自动前缀缓存不读这个字段」
> 只对了一半，且容易误导。准确表述见下。

#### 6.4.1 两个协议关心的是同一件事，但控制手段相反

| | Anthropic Messages | OpenAI Chat Completions |
|---|---|---|
| 缓存是否自动 | **否**，必须显式打点 | **是**，超过 1024 token 自动生效 |
| 缓存粒度 | 由 `cache_control` 标记的**断点**决定 | 服务端自动取**最长公共前缀**（从 1024 起、每 128 token 递增） |
| 命中量的**依据** | 断点位置 | **请求前缀逐字节相同** |
| 写缓存的代价 | 显式写入有溢价（`cache_creation`） | 无写入费用，命中才有折扣 |

**关键结论：`cache_control` 丢失不等于缓存失效。** Chat 侧的自动前缀缓存**照样生效** ——
它不看任何标记，只看「请求前缀是否逐字节相同」。丢掉 `cache_control` 的**真实后果**是：

1. **失去对缓存边界的控制** —— 在 Anthropic 侧，用户可以把断点打在「稳定的大块」末尾，
   让不稳定的尾部单独变化；丢掉之后，命中边界完全由 Chat 服务端的自动策略决定
2. **无法保证「该命中时一定命中」** —— Anthropic 侧不打点＝不缓存；
   Chat 侧是「够长＋前缀相同」就缓存，粒度更粗但**不需要用户操心**

因此这不是「功能丢失」，而是「控制权转移」。**对 Claude Desktop 这种客户端，实际影响比想象中小**。

#### 6.4.2 四家实际怎么做（已逐条核对代码）

| 项目 | 对 `cache_control` 的处理 | 是否注入 `prompt_cache_key` |
|---|---|---|
| **new-api** | 搬进 Chat `MediaContent.CacheControl`（`openai_request.go:498`）**但只在 `isOpenRouter && 上游模型是 anthropic/claude` 分支**；普通分支拼字符串时丢失 | `dto.GeneralOpenAIRequest` 有 `PromptCacheKey` 字段，但 `claude_messages/` 的去程**从不生成它**（只在 Chat→Responses 之间透传用户已给的 key） |
| **sub2api** | 不处理 | 不生成 |
| **cc-switch** | 不处理 | 不生成 |
| **CLIProxyAPI** | 不处理 | 不生成；但有 `support-prompt-cache-key` **供应商配置开关**，开启时才让 `prompt_cache_key` 通过 |

**四家没有一家把 `cache_control` 转成 `prompt_cache_key`。** 原因很直接：
两者语义不同 —— `cache_control` 标记「到哪里为止可缓存」，`prompt_cache_key`
是「这一组请求属于同一缓存域」的**分组键**（用于替代 `user` 字段做多租户隔离）。
它们不是同一维度的东西，无法互相翻译。

> **一个容易混淆的事实**：本服务自己的 `pipeline/README.md` /
> `2026-10-17-claude-desktop-prompt-cache.md` 里分析的是 **M→M 直连**的缓存命中率。
> 那条路上 `cache_control` 完全保留，所以那套分析在 M2C 路线上**不适用** ——
> M2C 的缓存行为由 Chat 上游自动策略决定，跟断点位置无关。

#### 6.4.3 有没有可行的改善手段

有，但都不在「翻译 cache_control」这个方向上。

**手段 A：保证请求前缀稳定（**最有价值，且是纯工程问题**）**

Chat 的自动缓存只看前缀，所以**真正决定命中率的是我们怎么构造请求**，不是有没有断点标记。

| 危险做法 | 后果 |
|---|---|
| 把 `system` 拼进第一条 message 且**每轮改写**（如注入时间戳） | 首字节就不同 → 全轮不命中 |
| 字段顺序不稳定（`LinkedHashMap` 已保证） | —— 本项目已做对 |
| 每轮在 `messages` **中间**插入内容 | 从那一点起全部失效 |
| 把易变内容放在**稳定大块之前** | 同上 |

这与 `2026-10-17-claude-desktop-prompt-cache.md` 的结论**完全同构**（那里是
「第 4 块无 cache_control 且每轮追加 → 其后的前缀全失效」），只是 M2C 侧没有
`cache_control` 可以打点，只能靠**构造顺序**来保护前缀。

> **给 Claude Desktop 的具体提醒**：它的 `system` 是 4 个 block，第 4 块每轮追加。
> M2C 把 system 拼成一条 Chat system message 时，**第 4 块的位置决定了有多少前缀能被保护**。
> 若按原顺序拼（1,2,3,4），第 4 块一变，它**之前**的三块仍命中（因为在前缀里），
> 它**之后**的 `messages` 全部失效。这与 M→M 直连的行为**一致**，不是新增退化。

**手段 B：按供应商开关注入 `prompt_cache_key`（可选，低收益）**

CLIProxyAPI 已有这个模式（`support-prompt-cache-key` 配置项）。对本服务的意义：

- 需要「同一会话路由到同一缓存分片」时有用（多实例/多租户场景）
- 但 **COSP 是单机本地代理**，没有多租户分片问题
- 且 `prompt_cache_key` **不是所有 OpenAI 兼容上游都支持**，乱发可能被严格上游拒绝

**结论：不做**。若将来要支持，按 CLIProxyAPI 的模式做成**供应商配置开关**，
默认关闭，不要全局注入。

**手段 C：在 `usage` 里如实反映缓存命中（已自动具备）**

M2C 路线下 `api_call_usage.cached_tokens` 会拿到 Chat 上游返回的 `cached_tokens`
（`OpenAiUsageParser` 已有 fallback 链），因此**缓存命中率在统计页上是可见的**。
用户能自己观察到「走 M2C 时命中率多少」，不需要代理额外做什么。

这是最重要的一点：**它不是黑盒**。

#### 6.4.4 决策

**丢弃 `cache_control` 字段本身；不做 `prompt_cache_key` 注入；把「保证前缀稳定」写进实现注意事项。**

理由：

1. 四家参考项目**没有一家**做 `cache_control` → 任何东西的转换，说明这个方向本身不成立
2. 缓存不会真的失效（Chat 侧自动生效），丢的只是**边界控制权**
3. `prompt_cache_key` 在单机代理场景没有收益，且有被严格上游拒绝的风险
4. 命中率可通过 usage 观察 —— 若将来实测发现命中率异常低，那时再按数据决定是否加手段 B

**但必须写进契约的「已知限制」**：走 M2C 路线时缓存断点由上游决定，
`cache_control` 被丢弃。

> **调研后：已可定案 —— 丢弃**（§5.4）。四家中 3 家直接丢弃；唯一保留的 new-api
> 只在「上游本身也是 Anthropic」时才搬，而那个条件在 M2C 里不会出现。
> 因此这不是妥协，而是行业共识。

---

## 7. 明确不做的事

这一节回答「为什么当初没顺手做 X」。

| 不做 | 理由 |
|---|---|
| **M2R / R2M（Messages ↔ Responses）** | 另一对协议方向，与本文无关。文档走 Responses 上游才是「文档能透传」的正解，但那是独立功能，另行立项 |
| **`/v1/messages/count_tokens` 端点** | 不属于翻译层（不产生上游调用），且归一化口径（哪种 tokenizer）本身是个待调研问题。先记 `KNOWN_DEBT.md` |
| **PDF / 文档内容提取** | 超出翻译层职责。协议翻译只搬运，不做内容理解 —— 这是 `ProtocolTranslator` 与 ADR-0003「翻译器只做字段搬运」确立的边界 |
| **`budget_tokens` ↔ 档位换算** | 契约 §4.4 已定「不做」。三家参考项目的换算表毫无共识，反向表的阈值也各不相同 |
| **自动降级（如 `minimal` → `low`）** | 契约 §4.5 已定「不做」。让上游用错误码回答比静默降级诚实 |
| **思考链重建（`signature`）** | 契约 §4.7 已定「已知无解」。`signature` 由 Anthropic 签发，跨协议造不出来 |
| **按模型名硬编码能力表** | cc-switch 的 `clamp_reasoning_effort_for_model` / sub2api 的 `isReasoningModel` 都这么做，但 AGENTS.md 硬约束禁止：中转站会改模型名，按前缀硬编码必然大面积失效 |
| **自动降级或抬高参数** | sub2api 把 `max_tokens` 抬到 floor、cc-switch 把 `max`→`xhigh`——契约 §4.5 / §4.6 已定不做 |
| **`redacted_thinking` 注入占位文本** | cc-switch 写 `"[redacted thinking]"`。伪造上游内容，COSP 应显式丢弃并留日志 |
| **修主干 / 注册表 / 出口层** | 已核对全部就绪（§2.2）。任何「顺手改一下主干」的冲动都应视为越界 |
| **协议流转编排** | `ProtocolDispatchManager.TRANSLATION_FALLBACK_ORDER` 的 TODO 是独立功能（用户按供应商/模型指定映射），不在本文范围 |

---

## 8. 分阶段计划

按项目既有的「让已有的一半变成可用的整体」原则排序（见 C2M 响应契约 §0 的同一条理由）。

### 阶段一：去程 M2C 单独上线

只加 `MessagesToChatRequestTranslator` 及协作类，**不加回程**。

- 此时 `AfterSend` 会走 `warnIfHalfRound`，下游收到**上游原生 Chat chunk**
- 这正是 `TranslatorRegistry` 设计里那个「正常中间态」：可以用真实上游验证
  「Anthropic 请求是否被 OpenAI 兼容上游接受」
- 落库会记录上游原生 chunk（`chunkRewriterFor` 返回 null，因为回程未命中）

**验收**：mock-upstream 的 `/chat/completions` 端点被真实调用，且 COSP 日志的
`downstream_protocol = MESSAGES` / `upstream_protocol = CHAT` 两列正确。

### 阶段二：回程 C2M 非流式

- 加 `ChatToMessagesNonStreamTranslator` + `ChatUsageConverter` + `FinishReasonMapper`
- 非流式形态简单、无状态机，可先把字段映射、usage 换算、`finish_reason` 映射钉死
- 流式那侧的每一条映射都能复用这里的结论

**验收**：Claude Desktop 非流式对话可用，usage 数值与上游 `prompt_tokens` 对得上（含缓存减法）。

### 阶段三：回程 C2M 流式

- 加 `C2MStreamState` + `ChatToMessagesStreamTranslator` + `AnthropicMessageShapes`
- **`Flux.defer` 内建状态**：`retryWhen` 会重订阅，跨轮复用状态会让 `message_start` 缺失
- 收尾用 `concatWith(Flux.defer(...))` 而非 `doFinally`（后者无法注入元素）

**验收**：见 §10。

### 阶段四：文档化收尾

- 更新 §2.3 列出的全部过期注释与测试
- 新建 C2M 请求契约 / 响应契约（或扩写现有两份）—— 需要先确定它们与
  `C2M-REQUEST-CONTRACT.md` / `C2M-RESPONSE-CONTRACT.md` 的关系：
  那两份文件名里的 `C2M` 指的是**协议对**而非单个方向，因此**扩写现有两份**比新建更符合既有习惯
- 决策定稿后新建 ADR

---

## 9. 风险与对策

| 风险 | 表现 | 对策 |
|---|---|---|
| **装配漏了不响** | `@Component` 不在扫描范围 → 注册表空表正常启动，既有测试全绿，直到第一个跨协议请求才报「翻译不支持」 | `TranslatorRegistrySpringWiringTests` 补 `(MESSAGES, CHAT)` 的 `containsSame` 断言（断言必须命中**容器里那个 Bean**，不能只断言「某个实例」） |
| **工具配对配反方向** | 签名相同，编译通过；上游收到合法 JSON 但 `tool_result` 与 `tool_use` 对不上，模型行为诡异而无报错 | 新类名 `AnthropicToolResultExpander` 并在注释里显式指向 `ToolPairingNormalizer` 说明反向；单测覆盖「多轮工具链」场景 |
| **翻译进重试内侧** | 空响应判定看到合成 chunk，每轮都判空，耗尽预算（约 62 秒 / 6 次上游调用） | 翻译器必须是 `@Component`，由 `BeforeSend` / `AfterSend` 在 `retryWhen` 外侧装配 —— **不要**把翻译写进执行器 |
| **`message_delta` 早发** | usage 权威值（含 `cached_tokens`）通常在后置的 usage-only chunk 里；早发会让缓存统计恒为 0 | 对齐 CLIProxyAPI：`message_delta` 只在「trailing usage-only chunk」或流结束时发（见 §4.4） |
| **usage 重复计算** | 输入 token 统计偏高（`prompt_tokens` 已含缓存，直接搬会重复） | `ChatUsageConverter` 显式做减法，单测钉住「有 cached_tokens 时 input + cache_read = prompt」 |
| **换算结果回写记账槽** | `api_usage_daily` 系统性偏小、与 `api_call_usage` 对不上，**且不报错**；缓存命中率越高偏得越多 | **翻译器必须用 `carriedUsage` 模式：换算只进 `data()`，槽位原样传递**。单测断言 `event.usage()` 与上游值逐字相等（见 §4.3.4） |
| **`message_start` 缺失 / 时机错** | 下游状态机不启动，表现为「有数据但界面不动」 | 单测断言第一帧是 `message_start`；实机验证时看 SSE 逐帧 |
| **`message_stop` 缺失** | Toast 永远悬挂在 CHUNK（部分上游不主动关连接） | `C2MStreamState` 的 `finalizeStream` 恒产出 `message_stop`；单测覆盖「上游不发终止标记就断连」 |
| **`includeUsage` 语义错用** | Anthropic 下游永远收不到 usage | 见 §6.2，倾向方案 A（无条件发） |
| **文档块静默丢弃** | 用户以为文档被处理了，实际模型没看到 | 决策 3 定为「转 `file` part + 无法转换时留痕」，**不得静默丢弃**；§10 有 Claude Desktop 专项验证 |
| **`file` part 不被上游接受** | 整条请求 400，文档场景完全不可用 | 先实机验证（§10 专项）；若普遍不接受，改为「按供应商配置开关」，**但不退回静默丢弃** |
| **工具链 reasoning_content 缺失** | DeepSeek / MiMo 直接 400，工具链整轮失败 | 见 §5.5(1)/§4.5：带 `tool_calls` 时必须回放 thinking 明文 |
| **误加思考链缓存** | 重建 V8.2 已删除的 `reasoning_cache` 类逻辑，引入跨请求状态与一致性风险 | 判据：只需「翻译请求体里已有的 thinking 块」；若代码在「存下来等下一轮取」，方向就错了（§4.5.2） |
| **回程 thinking 块缺 `signature` 键** | 严格客户端（如 Grok Build 系）拒「missing field `signature`」 | 构造块时保留该键并置空串（§4.5.3）；Claude Desktop 侧需实机验证 |
| **不注入 `include_usage` → 整笔统计漏记** | 执行与响应完全正常，但 token 全 0、统计页空白、`api_call_usage` 不写行，**无任何报错** | 翻译器流式时写 `stream_options.include_usage: true`（§4.3.7）；实机验证时先看 `cached_tokens` 是否非 null |
| **单测绿但实机不对** | 翻译器单测覆盖不到真实上游的字段变体 | 走 `tools/live-test/` 的 D 组（翻译组）+ mock-upstream 的参数分片场景（`translate-tool-*`）；文档场景另走 §10 的 Claude Desktop 专项 |
| **cache_control 丢失导致缓存失效** | 实际不会 —— Chat 侧自动前缀缓存不看标记 | 但**必须保证请求前缀稳定**：易变内容（如时间戳、上传信息）不要插到稳定大块之前。若实测命中率异常低，按 §6.4.3 手段 B 加供应商配置开关 |

---

## 10. 验收清单

### 单元测试

- [ ] `MessagesToChatRequestTranslatorTests` —— 字段映射、白名单丢弃、思考映射（对齐契约 §4.3）、`stop_sequences` → `stop`
- [ ] 工具配对：多轮 `tool_use` / `tool_result` 展开正确、孤儿 `tool_result` 丢弃并留痕
- [ ] **工具链 thinking 回放**：带 `tool_calls` 的 assistant 消息产出非空 `reasoning_content`；纯文本轮不带（见 §5.5(1)）
- [ ] **`document` 块**：按 §6.3 定案的策略处理，且无法转换时留痕而非静默
- [ ] **`stream_options.include_usage` 注入**：流式请求的出站 body 含 `stream_options.include_usage: true`（§4.3.7）
- [ ] **`ChatSystemPromptStage` 直连 no-op**：Chat 客户端不发顶层 `system` 时不改变请求体；
      若同时存在顶层 `system` 与 `messages` 里的 system，语义已被单测钉住（§6.1）
- [ ] **请求前缀稳定性**（决策 4）：同一会话连续两轮请求，`messages` 与 `system` 的**公共前缀逐字节相同**
      （Chat 自动缓存只看这个，不靠任何标记）
- [ ] **`tool` 消息 `name` 回填**：从同轮 `tool_use` 反查（见 §5.5(2)）
- [ ] `ChatToMessagesNonStreamTranslatorTests` —— 完整报文映射、`finish_reason` → `stop_reason`
- [ ] `ChatUsageConverterTests` —— **含 cached_tokens 的减法**、缺失字段的保守默认值
- [ ] **usage 双通道隔离**：翻译后 `UpstreamEvent.usage()` 必须与上游执行器挂的值**逐字相等**（不得是换算后的值）；
      另验「有缓存的上游响应」下 `api_call_usage.prompt_tokens == api_usage_daily.input_tokens`（§4.3.4）
- [ ] `ChatToMessagesStreamTranslatorTests` —— 帧数对照表逐项、「首帧必为 `message_start`」、「收尾必产 `message_stop`」
- [ ] **回程 thinking 块的 `signature` 键存在且为空串**（不是省略键）—— 严格客户端会拒「missing field `signature`」（§4.5.3）
- [ ] **不得引入任何「存储 → 下一轮注入」的缓存逻辑** —— 数据已在请求体里，只需搬运（§4.5.2）
- [ ] `TranslatorRegistrySpringWiringTests` —— `(MESSAGES, CHAT)` 两半都 `containsSame` 容器 Bean
- [ ] `ProtocolDispatchManagerTests` —— 「下游 MESSAGES + 供应商只勾 CHAT」现在应**命中翻译**而非抛错（原有用例需改）

### 实机验证（`tools/live-test/`）

- [ ] D 组：`downstream_protocol = MESSAGES` / `upstream_protocol = CHAT` 两列正确
- [ ] mock 侧 `/chat/completions` 被真实调用（判据是 mock 的「▶ 请求进入」行数，不是 COSP 日志行数）
- [ ] 流式逐帧：`message_start` → `content_block_start` → `content_block_delta`… → `message_delta` → `message_stop`
- [ ] 多轮工具链（≥ 5 轮）配对全平，无相邻同角色
- [ ] 非流式 usage 数值与下游 Claude Desktop 展示对得上
- [ ] **落库口径验证**：拿一份带缓存的上游响应，对比 `api_call_usage`（上游原生 `prompt_tokens`）
      与 `api_usage_daily`（聚合）—— 两者必须相等；再对比下游看到的 `input_tokens + cache_read_input_tokens`
      等于上游 `prompt_tokens`（§4.3.3 的逆运算）
- [ ] **缓存命中率可观测**：走 M2C 时 `api_call_usage.cached_tokens` 有值（非 null），
      统计页的命中率能真实反映上游 Chat 的自动前缀缓存效果（决策 4）

### ⚠️ Claude Desktop 专项验证（用户指定，不可省）

文档转换是本次需求的核心动机，**必须在真实 Claude Desktop 里单独测**，不能只靠 mock：

1. **PDF 文档**：发一份真实 PDF，确认
   - 请求确实带 `{type:"file","file":{filename,file_data:"data:application/pdf;base64,..."}}`
   - 上游接受（2xx）或返回可理解的 400（而非静默丢块后答非所问）
   - **模型真的“看到”了文档内容** —— 问一个只有文档里才有的细节，确认能答对
     （这是关键：如果文档被丢了，模型会用已有知识编一个看起来合理的答案）
2. **纯文本 document**：确认降级成 `{type:"text"}` 后内容完整
3. **文档 + 工具链组合**：带附件同时要求模型调工具，确认两者不互相干扰
4. **不同上游各测一次**：至少一个「纯 OpenAI 兼容中转站」和一个官方端点 ——
   `file` part 不是官方规范，不同上游的支持程度可能不同

> 若某些上游不接受 `file` part，需记录下来并考虑「按供应商配置开关」（§6.4.3 手段 B 的模式）。
- [ ] Claude Desktop 真实客户端非流式 + 流式对话可用

### 文档

- [ ] §2.3 的全部过期注释与测试已更新
- [ ] 契约文档（扩写现有两份）已更新 M2C / C2M 方向
- [ ] `ProtocolTranslator` 的 `TODO(待实现)` 块已删除（它的前提已不成立）
- [ ] 本 RFC 状态改为「已定稿且实施完毕」并追加实施记录
- [ ] 决策定稿后新建 ADR，并在本 RFC 头部回填 ADR 链接
- [ ] `docs/working/rfcs/README.md` 状态表已更新

---

## 11. 实施记录

> 按阶段追加，**不改写历史**。计划与实际不符时在此记录偏差原因。

（尚未开始）

# RFC：M2C 去程与 C2M 回程翻译（下游 Anthropic → 上游 Chat 的完整链路）

- **日期**：2026-10-20（立项日）
- **状态**：**讨论中** —— M2C 去程主要决策已确认、实施未开始；少量边界待确认，C2M 回程待专项复核
- **最后调研**：2026-10-21（M2C 去程最终核对；未运行实现、编译或联测）
- **相关 ADR**：[ADR-0012：M2C 去程的忠实映射与显式损失边界](../../adr/adr-0012-m2c-request-translation.md)
- **分支**：`feat/m2c-request-c2m-response-translation`
- **前置文档**：
  [协议翻译四方向 RFC](./2026-09-03-protocol-translation.md) §A 方向总览、
  [C2M 请求契约](../../reference/contracts/C2M-REQUEST-CONTRACT.md) §4.3（M2C 思考映射）、
  [C2M 响应契约](../../reference/contracts/C2M-RESPONSE-CONTRACT.md) §0

> **需求复述**：用户近期开始把 Claude Desktop 作为常用客户端，其**文档处理能力**是主要动机，
> 因此 `/v1/messages` 这条下游路径从「能用」升级为「常用」。
> 当前 COSP 在该路径上只支持 **M→M 直连**；已实现的 **C→M** 使用下游 Chat 入口，
> 不代表反向的 M→C 已可用。
> 一旦供应商只有 OpenAI 兼容端点（即上游 CHAT），M2C 去程翻译缺失会让整个请求
> 在协议调度阶段就被拒（`ProtocolTranslationNotSupportedException`，控制器译 400）。
> 目标是补齐 **M2C 去程 + C2M 回程**这一对；各上游的文档、工具和思考能力仍受其实际支持范围限制。
>
> 本文是同一功能的完整档案：保留 2026-10-20 的结构调研与参考项目比较，
> 收入 2026-10-21 的最终核对、用户确认与实施计划。**文档落盘不表示功能已实现。**

## 阅读入口与事实归属

| 要查什么 | 唯一维护位置 |
|---|---|
| 已确认的取舍与理由 | [ADR-0012](../../adr/adr-0012-m2c-request-translation.md)；不包含未确认的附加拒绝策略 |
| M2C 逐字段目标规格 | [请求契约 §10](../../reference/contracts/C2M-REQUEST-CONTRACT.md#m2c-request-spec)，明确标为待实现 |
| 最终调研证据、纠错与剩余问题 | [本文 §12](#m2c-final-review) |
| 去程实施步骤、依赖、文件清单 | [本文 §8](#m2c-plan) |
| 自动化与用户手动验收 | 本文 §10 |

不另建第二份 RFC、根目录 PLAN 或独立调查副本；功能尚在推进，也不移入 `archive/`。
本文仍处于讨论状态，是因为 §12 的待确认项和回程设计尚未闭环，不是已确认决策被重新打开。

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

### 2.2 主干与可复用组件（2026-10-21 复核）

**主干编排与注册表不需要新增协议分支**。但前轮「所有边界与观测也零改动」的判断过宽：
入站 `stream` 还原需要补齐，Chat 回程日志改写器也存在漏传，证据见 §12。

| 组件 | 复用范围与例外 |
|---|---|
| `MessagesService` | 早已退化成「声明协议 + 建 ctx + 交主干」，翻译分支不在它里面 |
| `BeforeSend.translateStep` | 按 `(downstream, upstream)` 查两张表；去程未命中才抛错，命中即改写 body |
| `AfterSend.process` | 回程翻译器从 `ctx.responseTranslator()` 读，本块不自己查表、不加协议分支 |
| `ProtocolDispatchManager` | 构造期从 `List<RequestProtocolTranslator>` 收集 `implementedRequestRoutes`，新 `@Component` 自动进入该集合 |
| `TranslatorRegistry` | 集合注入 + 按方向建两张表，零改动 |
| `RequestPipelineContext` | `applyTranslation` 同时替换 body 与 `bodyProtocol`，查表键自动切到 CHAT |
| `GenericOpenAiChatService` / `AbstractUpstreamChatService` | 去程发送与 usage 解析可复用；回程阶段必须补 `chunkRewriter` 传递，见 §3.3 |
| `ChatOutboundStage` | 读 `base_url`，无协议必需头（对比 `MessagesOutboundStage` 补 `anthropic-version`） |
| `ChatContentDetectorStage` | 空响应判定的键是 `upstreamProtocol = CHAT`，命中 |
| `ChatChunkNormalizeStage` / `ChatReasoningFallbackStage` | 键同为 `upstreamProtocol`，命中 |
| `AnthropicController` | 正常 SSE 映射可复用；`buildRequestBody` 必须补回 `stream`。跨协议上游错误体是否被客户端接受另需验证 |
| `StreamLifecycle` / `NonStreamLifecycle` | 同上；Anthropic 侧靠 `extractEventType` 回填 SSE 的 `event:` 名 |
| `UpstreamEventClassifier` | 按传入协议判定终止标记，回程翻译器只要产出 `message_stop` 即可收尾 |
| `GatewayAuthFilter.PROTECTED_PATHS` | `/v1/messages` 已在列 |
| `ProviderRouteResolver` | 路由只由模型名决定，协议不参与候选集筛选 |
| `ProviderProtocolSupport` | 默认全勾，MESSAGES 本来就是合法取值 |
| 前端 `ALL_WIRE_PROTOCOLS` / `RULE_ENGINE_WIRE_PROTOCOLS` | 三协议早已齐备 |
| `tools/mock-upstream` | 现有端点可验证请求是否到达；当前不校验翻译字段，也不能验证按 `include_usage` 返回尾部 usage，不能代替收包测试 |

### 2.3 必须同步维护的实现与测试清单

前轮把「新 Bean 会影响生产集合」误推成「所有手工测试集合都会变化」。
以下以实际构造方式为准；本次只更新文档，**尚未修改下列代码或测试**。

| 位置 | 本次核对 | 实施阶段动作 |
|---|---|---|
| `ProtocolTranslator` / `MessagesService` 注释 | 已指向本 RFC；去程与回程仍确实未实现 | 各自落地后分开更新状态，不能提前删掉全部 TODO |
| `ProtocolDispatchManager.TRANSLATION_FALLBACK_ORDER` 注释 | 仍含「C2R 去程未实现」等旧说明；前轮标为「已修」不实 | 只修注释；固定顺序与选择逻辑不变 |
| `AnthropicController.buildRequestBody` | 未把已建模的 `stream` 放回 Map | 补回字段并加 HTTP 入站测试 |
| `ProtocolDispatchManagerTests.skipsUnimplementedCandidateAndFallsToNextImplemented` | 手工只装 M2R stub，新 Bean 不会让它变红 | 保留未实现守卫，另补真实 M2C 集合正例 |
| `ChatDispatchErrorSignalTests` | 手工只装 C2M 译器，不随 Spring 集合变化 | 说明 fixture 边界；新增真实 M2C 的成功与翻译异常信号用例 |
| `RequestBodyStageSpringWiringTests` | 当前断言 system 两个实现、CHAT 查不到 | 新增 CHAT system 后确实需要更新数量、协议集合与 `containsSame` |
| `PipelineContexts.registryWithAllBodyStages` | 手工收集 system 支线，不会自动获得新 Bean | 补 CHAT；MESSAGES-only helper 保持原范围 |
| `TranslatorRegistrySpringWiringTests` | 现有断言不会因加 Bean 自动失败，但缺 M2C 覆盖 | 去程阶段补真实 M2C Bean；回程落地时再补另一半 |
| `pipeline/README.md` / 架构文档 | 部分目录、方向与实现数量说明已过时 | 按实际类路径和已完成阶段更新，不用数量推断支持能力 |
| Chat 执行器的 `chunkRewriter` | 接口收到参数但父类未接收，日志调用仍传 null | 交给回程阶段修复，见 §3.3 |
| `tools/live-test/d-translate.js` / `lib.probe` | 旧 D 组只测 C2M，`probe` 按下游路径计数 | 新 M2C 探针使用低层函数、显式以 CHAT 计数，不冒充旧 D 组已覆盖 |

文档中的决策、目标契约与实施状态分别维护，入口见本文顶部；历史已定稿 RFC 不为本次调研重写正文。

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

这是在尚无供应商/模型级编排时采用的**固定回退顺序**，不是按枚举声明序或实现数量动态计算。
「字段更少」也不能推出「翻译信息损耗更小」。

> **2026-10-21 订正**：源码注释仍误称 C2R 去程未实现；前轮「已改回正确表述」的记录不准确。
> `ChatToResponsesRequestTranslator` 已声明 `(CHAT, RESPONSES)`。后续只纠正注释，
> **不因为增加 M2C 而重排常量**；已实现方向必须从译器声明核对，不能从注释反推。

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

> **第二行是回退序 `C → M → R` 的直接体现**：`CHAT` 排在 `RESPONSES` 前，
> 所以供应商同时支持两者时选 CHAT。这正是用户提醒的「优先级顺序是 C、M、R」。

#### 2.4.6 为什么**不**在翻译器里做优先级

用户判断正确。若放在翻译器里会怎样（**反面推演**）：

| 若把选择逻辑放翻译器 | 后果 |
|---|---|
| 每个翻译器要判断「我该不该被选中」 | 翻译器从「纯字段搬运」变成「参与调度决策」，违反 ADR-0003 的职责边界 |
| 把供应商候选与配置引入翻译器 | 字段转换开始依赖路由政策，职责和测试边界被扩大；并非「注入配置」本身必然使 Bean 有请求态 |
| 无法表达「去程未实现就跳过」 | 那需要全局视角（看所有候选），单个翻译器看不到 |
| 报错点前移不了 | 现在的报错在调度阶段，消息能说「到 X 的翻译均未实现」；放进翻译器就只能说「我不支持」 |

**结论**：优先级判定留在调度器是正确设计，本次 M2C **不应改变它**。

---

## 3. 组件清单

### 3.1 新建：去程 M2C

门面 `MessagesToChatRequestTranslator implements RequestProtocolTranslator`，
声明 `(MESSAGES, CHAT)`，带 `@Component`。

> 流式时索取上游 usage 的目标规格见[请求契约 §10](../../reference/contracts/C2M-REQUEST-CONTRACT.md#m2c-request-spec)。
> **前提是入口先补回 `stream`**，不能只在手工 Map 单测中验证注入，见 §12.1。

按项目既有粒度（对齐 `ChatToMessagesRequestTranslator` 下的协作类拆分），门面之下需要：

| 类 | 职责 | 对应 C2M 侧的先例 |
|---|---|---|
| `AnthropicMessageTranslator` | 解析 Anthropic 语义轮、构造 Chat 消息；与配对清理协调 thinking 回放 | `MessageTranslator`（不能反向调用） |
| `AnthropicContentBlockTranslator` | 文本、图片、文档及其元数据转换，普通内容与工具内内容共用 | `ContentBlockTranslator`（方向不同） |
| `AnthropicToolTranslator` | 工具定义、schema、strict、选择与显式并行控制转换 | `ToolTranslator`（方向不同） |
| `AnthropicToolResultExpander` | 请求内因果配对、重复检查、未应答/孤儿清理、工具媒体归属与延后输出 | 不复用输出为 MESSAGES 的 `ToolPairingNormalizer` |

门面之外新增 `ChatSystemPromptStage`，委托 `SystemPromptNormalizer.inlineSystemIntoMessages`。
它属于已有请求体归一化插槽，不是翻译器内部的第二套 system 逻辑。
协作类不持有跨请求状态；具体字段和空结果纪律只在请求契约维护。

### 3.2 新建：回程 C2M

> **以下为回程初步清单，不属于此次纯去程实施。** 回程字段、usage 缺失值、状态机与日志
> 必须另作专项复核；本轮不把这些候选全部晋升为 Accepted 决策。

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
> COSP 已有 `ChatReasoningFallbackStage`，回程设计需先核对输入是否已被兜底处理，
> **不能在翻译层再重复生成同一段可见文本**。

> **`ChatUsageConverter` 是唯一「没有现成参照」的协作类**。已核对：
> `AnthropicUsageAccumulator` 是 Anthropic → OpenAI 方向；
> `ResponsesToChatUsageConverter` 是 Responses → Chat 方向；
> **Chat → Anthropic 方向的换算器当前不存在**。换算规则见 §4.3。

### 3.3 落库与日志：回程有一处实际接线缺口

`AfterSend.chunkRewriterFor` 会构造改写器，但 `GenericOpenAiChatService.invokeStream`
没有向 `AbstractUpstreamChatService.chatCompletionStream` 传递它；父类各处流式落库仍传 null。
因此前轮「回程一注册就自动双栏」的判断不成立，详见 §12.5 的证据。

纯去程半轮记录 Chat 原生载荷是预期；**回程阶段**才补齐参数传递与真实日志双栏测试。
`translateChunksForLog` 仍须用独立状态重放，不能复用已结束的下发状态机。

---

## 4. 规格索引与回程设计注意事项

M2C 逐字段规格统一维护于[请求契约 §10](../../reference/contracts/C2M-REQUEST-CONTRACT.md#m2c-request-spec)，
不在 RFC 再保留第二套字段表。本节保留研究中的职责说明与回程候选，后者尚未完成专项复核。

### 4.1 顶层字段搬运

顶层白名单、`stream_options`、显式 null、`output_config` 的 effort/format 分离，
见请求契约 §10.1、§10.2、§10.7。历史「所有未列字段都可静默丢弃」不适用于
已确认要显式拒绝的文档与执行语义，边界和待确认项见 §12.7。

### 4.2 工具配对：**方向相反**，不能直接复用

现有 `ToolPairingNormalizer` 输出 Anthropic 结构；M2C 需要输出独立 Chat tool 消息，
因此不能仅因方法签名相似而复用。新的 `AnthropicToolResultExpander` 按请求契约 §10.4、§10.5
实现反向配对与媒体延期；历史、空结果与重复 ID 的选择见 ADR-0012。

Chat 也有工具邻接要求，但**没有全局角色交替要求**；原类注释关于 OpenAI 没有邻接约束的说法不能照搬。

### 4.3 usage：**双通道**与「落库必须是上游原生口径」

这一节比换算公式重要得多。**换算只影响下游看到的帧，绝不影响落库。**

#### 4.3.1 两条通道的分工

| 通道 | 载体 | 内容 | 谁消费 |
|---|---|---|---|
| **记账通道** | `UpstreamEvent.usage()`（`UsageTokens`） | **上游原生**归一后的值 | 出口写 `api_usage_daily`；明细由执行器从同源 usage 原文另写，见 §4.3.2 |
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

在一次成功、usage 完整且两处写入成功的调用中，两者应保持同一 token 口径。
这不意味着行数永远相等：重试、无 usage 与 best-effort 写入都有不同的记录条件，
无 usage 的成功流式仍记日聚合 `0,0`，见 §12.4。

#### 4.3.3 换算公式（**只用于渲染通道**）

> **回程草案**：下面仅展示「只有缓存读取量、没有缓存写入量」的简单情形，
> 不是完整的未知值/别名契约。完整回程换算留到对应阶段复核。

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

**不再断言 `cache_creation` 恒为 0**：`OpenAiUsageParser` 的归一化槽不提取写入量，
不能据此证明原始 usage 没报告。若上游给出写缓存字段，回程应从原始载荷核对，
还需区分「没有报告」与「报告零」；§5.2 的参考公式不能和本例同时当成已定契约。

#### 4.3.4 最容易犯的错：把换算结果写回 usage 槽

**这是一类「会静默算错账」的坑**，必须单列。

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

#### 4.3.7 M2C 必须主动索取上游流式 usage

Anthropic 没有 Chat 的 `stream_options` 字段；而按 Chat 常见接口约定，
需发送 `stream_options.include_usage:true` 才返回尾部 usage。
**上游选项是否发出**与 **`TranslationContext.includeUsage` 的下游语义**是两件事，不能互相推导。

若上游未返回 usage，执行器拿不到 token/缓存量，`api_call_usage` 不写行；
成功流式的日聚合仍通过 `record(0,0)` 计一次调用。回程也无法凭空还原真实 usage。
前轮「两个统计表都不写」以及错误引用 `AnthropicUsageAccumulator` 的后果链已撤回。

四家均有索取 usage 的机制，但落点和条件不同：

| 项目 | 真正调用位置与条件 |
|---|---|
| cc-switch | `providers/claude.rs` 调 `inject_openai_stream_include_usage`，函数内部判断 stream |
| new-api | `relay/channel/openai/adaptor.go` 的 Claude 转换路径，仅 `SupportStreamOptions && IsStream` 时设置 |
| CLIProxyAPI | `openai_compat_executor.go` 流式执行先设 true，再执行 payload 配置，配置可覆盖 |
| sub2api | `openai_gateway_messages_chat_fallback.go` 在 `clientStream` 为真时设置 `ChatStreamOptions{IncludeUsage:true}` |

COSP 采用**仅 M2C 翻译器生成该选项**的方案，随后 CHAT 请求体规则仍有最终决定权。
不在通用装配器中给直连 CHAT 扩大行为；C2R 上游是 RESPONSES，也不会因 CHAT 条件受益。
必须先修复 §12.1 的入站 `stream` 缺口，精确规格见请求契约 §10.1。

#### 4.3.8 全局核对发现的三处细节（本轮补充）

以下三条不属于四项原定决策，但都会影响实施正确性。

##### (1) C2M 的 includeUsage 传递不是缺陷

`OpenAiController.buildRequestBody` 保留 `stream_options`；
`ChatToMessagesRequestTranslator` 从原始 source 提取上下文，然后才把无对应字段排除在 target 外。
因此下游传 true 时，`TranslationContext.includeUsage` **可以为 true**。
上游 Anthropic 自带 usage，回程据上下文决定是否给 Chat 下游额外 usage chunk。
这条现有链路无需改动；前轮「恒 false」的表述是错误结论，不是已知债。

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

**工程方案统一为数组**，包括单元素数组，避免同一 RFC 两套输出约定。
不是因为字符串非法，而是无需为相同字段再增加一种输出分支；具体校验见请求契约 §10.1。

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

### 4.5 thinking 明文与签名是两类信息

早期因无法生成签名而把 M2C thinking 明文也列为丢弃，混淆了「签名不可重建」
与「请求里已有明文可搬运」。sub2api 的工具链回放说明提供了实际失败依据；
但 cc-switch/CLIProxyAPI 的启用条件并不完全相同，比较见 §5.5(1)。

#### 4.5.1 已定案（2026-10-20）

用户已确认采用**工具链中回放已有明文、不造缺失内容**的策略，2026-10-21 补齐了
配对清理后再判断是否仍有工具调用的要求。字段规则见请求契约 §10.4；理由归 ADR-0012。

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

这说明**有客户端检查键是否存在**，不代表所有客户端都接受空签名。前轮记录的回程做法如下，
仍需在回程专项中复核实际序列化路径：

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

> 四个参考项目全部只读。2026-10-21 在同一批提交上补查了外层调用者与官方 SDK；
> 下文显式订正前轮误判。完整提交和可定位入口见 §12.8，不把代码注释等同于客户端实测。

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

**以下是共同的换算思路，不表示各家别名、缺失值和启用条件完全一致**：

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

**2026-10-21 订正：Chat Completions 官方定义已有 `file` content part**：

```json
{"type": "file", "file": {"filename": "document.pdf", "file_data": "data:application/pdf;base64,..."}}
```

- new-api 的 `dto.ContentTypeFile = "file"` 与 `MessageFile{FileName, FileData, FileId}` 是显式建模的
- OpenAI 官方 SDK 固定版本 `v1.109.1` 已定义该形态，来源见 §12.8；前轮「非官方扩展」判断错误
- new-api 只有在「PDF base64」时才用它；**纯文本 document 则降级成普通 text 块**
- new-api 会记录诊断后跳块；CPA 通过 `UserTurnDrops` 只在用户轮被丢空时拒绝，混合文本仍可能继续发出。不能把“有诊断”误读成“任何不支持文档都阻断”

**这直接改变了决策 3 的选项集**：原 RFC 列的四个选项（丢弃/伪造提示/提取文本/报错）**都不完整**，
可行做法是第五种 —— 转成 `file` content part。COSP 对无法转换的文档采用**显式拒绝**，
比上述参考项目的部分丢弃策略更严格。

> **仍需实测**：目标上游和模型是否支持文件输入；官方定义不保证所有兼容端点支持。
> 不支持时保留可理解的错误，不静默丢文件。首版不新增供应商开关，更不自动改选 M2R。

---

### 5.4 决策 4 依据：`cache_control` 断点

| 项目 | 处置 |
|---|---|
| new-api | 搬进 Chat `MediaContent.CacheControl` —— **但只在 `isOpenRouter && 上游模型是 anthropic/claude` 分支**；普通分支拼字符串时**丢失** |
| sub2api | 不处理（丢弃） |
| cc-switch | 不处理（丢弃） |
| CLIProxyAPI | 不处理（丢弃） |

**结论**：普通 Chat 目标通常丢弃该标记；new-api 的保留属于特定 Chat 方言。
**Chat 协议与其背后的模型厂商不是一回事**，M2C 也可能连到托管 Anthropic 模型的 Chat 端点，
前轮「这个条件不会出现」不成立。

COSP 首版仍按已确认决策丢弃、不映射为 `prompt_cache_key`。丢弃不意味着所有缓存必定失效，
也不能保证所有上游仍自动命中；新版官方断点机制与部署支持范围见 §12.2。

---

### 5.5 消息与工具边界：共性与分歧

以下是参考实现提供的证据，**不是四家全一致的事实标准**；COSP 选择另由 ADR 与契约约束。

#### (1) 工具链的 `reasoning_content` 回放 —— **会推翻契约 §4.7 的一条适用性**

| 项目 | 做法 |
|---|---|
| sub2api | `anthropicThinkingToReasoningContent(blocks, hasToolCalls)` —— **只在 assistant 带 `tool_calls` 时**把 thinking 折叠进 `reasoning_content`；普通文本轮不带 |
| cc-switch | 同上条件（`preserve_reasoning_content && role == "assistant" && !tool_calls.is_empty()`）；**无 thinking 内容时给 `"tool call"` 占位** |
| CLIProxyAPI | 只在 `role == "assistant"` 且 `shouldMapClaudeThinkingToGPTReasoning(...)` 时收集，`\n\n` 连接 |
| new-api | 顶层思考意图走 `reasoning.ApplyToOpenAIChat`；这不等于已证明 assistant 历史 thinking 也被回放 |

sub2api 的注释给出了硬理由：

> DeepSeek's thinking mode requires the reasoning_content that produced a tool call
> to be replayed on that assistant message and **answers 400 otherwise**.

**这对我们意味着什么**：

原契约 [C2M 请求契约](../../reference/contracts/C2M-REQUEST-CONTRACT.md) §4.7 定的是「M2C 丢弃 thinking 块」，
理由是跨协议造不出 `signature`。那个结论**在纯对话场景成立，但在工具链场景会导致硬失败** ——
DeepSeek / MiMo 这类要求 `reasoning_content` 回放的供应商会直接 400。

**2026-10-21 补查**：cc-switch 有供应商兼容开关；CPA 的
`shouldMapClaudeThinkingToGPTReasoning` 依据签名/兼容选项，不以 `hasToolCalls` 为唯一条件。
本项目的工具链限定与不给占位是已确认的自身选择，不以所有参考项目一致为前提。

#### (2) `tool.name` 回填建议已撤销

new-api 与 CLIProxyAPI 都做了反查回填：

- new-api：`toolNames[mediaMsg.Id] = mediaMsg.Name` 边遍历边建表，`tool_result` 无 name 时先记 index，
  最后 `*openAIMessages[result.index].Name = toolNames[result.id]`
- CLIProxyAPI：`toolNameByID[toolUseID]` → `toolResultJSON.name`

但官方 `ChatCompletionToolMessageParam` 没有 `name`。sub2api、cc-switch 不发此字段
并非依赖上游宽容；相反，附加非官方键可能被严格上游拒绝。
**用户于 2026-10-21 确认默认不发**，配对只依赖 `tool_call_id`。

#### (3) `tool_result` 里的图片必须提到后续 user 消息

四家**全部**这么做，理由一致：Chat 的 tool 消息只能承载文本。

| 项目 | 具体做法 |
|---|---|
| new-api | 图片进 `mediaMessages`（即当前 user 消息），无文本时给 `"[image]"` 占位 |
| sub2api | `toolResultImageParts` 延后追加到同一 user 消息 |
| cc-switch | `pending_tool_media` + `flush_pending_chat_tool_media`，**在所有 tool 消息之后、普通内容之前** flush 成一条 user 消息 |
| CLIProxyAPI | `relayedToolImages` → 一条 user 消息，首块是提示文字 `"Images returned by the preceding tool call(s):"` |

四家的媒体提示与空结果处理并不相同。用户已区分「已有错误/媒体归属的最小编码」
与「伪造空结果」：前者允许，真正空结果仍保留空串，精确目标见请求契约 §10.5。

#### (4) 纯文本时用字符串 content，有图才用 parts 数组

sub2api 的注释直说：

> strict chat upstreams reject array content

sub2api 会折叠纯文本数组；cc-switch 仅单文本块折叠，CPA 保留 parts 数组，
所以这不是四家一致的格式。COSP 选择纯文本折叠来减少目标形态，见请求契约 §10.4；
不能把它写成 Chat 官方只接受字符串。

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
| **无条件注入 `parallel_tool_calls: true`** | sub2api 的直接 bridge | 契约 §5.3 已列为反面案例；new-api 外层 Extract/Attach 读取显式 disable_parallel_tool_use，不能再归入此行 |
| **`redacted_thinking` 注入占位文本**（`"[redacted thinking]"`） | cc-switch | 伪造上游内容。COSP 应显式丢弃并留日志 |
| **`output_config.effort` 未知值时静默丢弃** | cc-switch | 与「尊重用户配置」冲突；COSP 倾向原样发出让上游报错 |

---

### 5.7 本节结论对 §6 的影响

| 决策 | 调研前倾向 | 调研后 | 变化的理由 |
|---|---|---|---|
| 1. `system` 落点 | B（新增支线） | **B（已定案）** | 用户定案：主干-支线结构本来就是为了方便实现各协议翻译；四家全内联，但它们都没有这类注册表基建 |
| 2. usage 无条件发 | A | **A（已定案）** | 四家一致 |
| 3. `document` 处置 | 四选一待定 | **方案 5：转 `{type:"file"}`（已定案）** | 用户定案：尽量保证下游行为不变，上游不支持是上游的问题；需在 Claude Desktop 单独验证 |
| 4. `cache_control` | 丢弃 | **丢弃（已定案）** | 不在首版自动适配供应商方言或新版缓存控制；不承诺命中率不变 |
| **（新增）thinking 回放** | 旧契约定「丢弃」 | **已定案：工具链回放已有明文，不给占位** | 避免要求回放的上游拒绝工具续轮；具体启用条件不是三家完全一致 |

---

## 6. 决策记录与确认范围

**取舍的权威记录是 [ADR-0012](../../adr/adr-0012-m2c-request-translation.md)**，
它只承载已确认的策略；逐字段目标见请求契约 §10，工程建议与待确认问题见 §12.7。

2026-10-20 已确定 system 支线、文档搬运、cache_control 丢弃、工具链 thinking 明文回放、
M2C 索取流式 usage，以及回程渲染与记账隔离；2026-10-21 又确认了四项：
不发 `tool.name`、允许已有元数据的最小文本编码、扩充纯结构转换范围、
清理未应答调用而重复 ID 报错。**这些确认不等于回程全部算法已定稿。**

### 6.1 决策 1：`system` 如何落回 `messages[]`

选择独立 `ChatSystemPromptStage`，而不是译器内联。四家参考项目的内联先例保留于 §5.1；
本项目复用已存在的 system 插槽。无顶层 system 时必须 no-op。
支线直接收到「顶层 system + 首条 system 消息」的合并语义要测试，
但当前 Chat HTTP 入口并不回填顶层 system，不能把该单测误称为已存在的 HTTP 行为变化。

### 6.2 决策 2：usage 的两种语义

M2C 去程负责索取上游 usage；C2M 回程按 Anthropic 协议输出 usage，
不以 Chat 专用 `includeUsage` 控制是否输出。换算只进渲染载荷，记账槽原样携带。
回程的字段别名、缺失值和首帧数值还需专项复核，§4.3 的示例不是完整实现契约。

### 6.3 决策 3：文档内容与不支持边界

选择原生 `file`/文本内容搬运，而不是丢弃、OCR 或假造模型输出。
最终范围含 `source.content` 与工具结果内文档；无法等价处理的 URL 文档、跨服务文件 ID、
引用定位要求和服务端工具显式报错。具体形态见请求契约 §10.7。

**必须由用户在完整回程后用 Claude Desktop 实测**：不仅检查 2xx，还要确认模型能回答
只存在于文档内的细节；至少覆盖两家上游。首版不新增 file 开关；若后续确有适配需求，另作决策。

`/v1/messages/count_tokens` 当前没有端点；这不是请求翻译器职责。
是否被当前 Claude Desktop 操作触发需实际抓包，不能把协议可能调用写成每次文档操作都会调用。

### 6.4 决策 4：缓存控制

首版丢弃协议层 `cache_control`，不自动生成 `prompt_cache_key` 或新式缓存断点。
这是一项兼容性取舍，**不是断言 Chat 永远没有显式缓存 API**；新版证据见 §12.2。

保持系统文本、消息顺序和已有历史转换的确定性，不插入随机标记；
这有助于前缀缓存，但 `LinkedHashMap` 顺序不等于服务端必按原始 JSON 字节缓存。
缓存阈值、TTL、计价和命中范围由实际上游决定，不承诺跨协议命中率不变。

现有 `OpenAiUsageParser` 可解析上游报告的缓存量；不报告时保持 null，而不是伪造零命中。
Claude Desktop 已保存的 MESSAGES 缓存规则不能自动迁到 CHAT，处理计划见 §12.6。

---

## 7. 明确不做的事

这一节回答「为什么当初没顺手做 X」。

| 不做 | 理由 |
|---|---|
| **M2R / R2M（Messages ↔ Responses）** | 另一对协议方向，另行立项；不是文档输入的唯一可行方向，Chat 官方也有 file part |
| **`/v1/messages/count_tokens` 端点** | 是独立的下游 API 用例，tokenizer 与本地估算/上游委托方式需另行调研，不能假定它必然不产生上游调用 |
| **PDF / 文档内容提取** | 超出翻译层职责。协议翻译只搬运，不做内容理解 —— 这是 `ProtocolTranslator` 与 ADR-0003「翻译器只做字段搬运」确立的边界 |
| **`budget_tokens` ↔ 档位换算** | 契约 §4.4 已定「不做」。三家参考项目的换算表毫无共识，反向表的阈值也各不相同 |
| **自动降级（如 `minimal` → `low`）** | 契约 §4.5 已定「不做」。让上游用错误码回答比静默降级诚实 |
| **思考链重建（`signature`）** | 契约 §4.7 已定「已知无解」。`signature` 由 Anthropic 签发，跨协议造不出来 |
| **按模型名硬编码能力表** | cc-switch 的 `clamp_reasoning_effort_for_model` / sub2api 的 `isReasoningModel` 都这么做，但 AGENTS.md 硬约束禁止：中转站会改模型名，按前缀硬编码必然大面积失效 |
| **自动降级或抬高参数** | sub2api 把 `max_tokens` 抬到 floor、cc-switch 把 `max`→`xhigh`——契约 §4.5 / §4.6 已定不做 |
| **`redacted_thinking` 注入占位文本** | cc-switch 写 `"[redacted thinking]"`。伪造上游内容，COSP 应显式丢弃并留日志 |
| **重构主干 / 注册表 / 通用鉴权重试策略** | 无需改变现有编排和 C→M→R 顺序；必要的入站 stream 回填不是主干重构，回程日志漏传另按阶段修复 |
| **协议流转编排** | `ProtocolDispatchManager.TRANSLATION_FALLBACK_ORDER` 的 TODO 是独立功能（用户按供应商/模型指定映射），不在本文范围 |
| **文件下载、上传、跨供应商 ID 映射与 OCR** | 超出纯结构转换；不能为了“支持文档”暗增 I/O、持久化和凭据边界 |
| **自动应用新的缓存规则或扩大载荷限制** | 不改用户供应商数据，不把 64MiB 缓冲限制解释为同大小文件承诺；只规划可选样例与边界验证 |

---

<a id="m2c-plan"></a>

## 8. 分阶段实施计划（2026-10-21）

**当前执行范围是去程；以下均为未来工作，不是已完成清单。** 原计划的「去程单独上线」
改为「功能分支内受控验证」：去程一注册就可能被调度命中，但没有回程时正常 Chat 响应
仍无法满足 Anthropic 客户端契约。`AfterSend.warnIfHalfRound` 已明确要求合并主干前补齐回程。

### 8.1 阶段 0：文档落盘与边界冻结

1. 把最终调研、计划和字段规格归入本文与请求契约，已确认决策晋升 ADR-0012。
2. 关闭 §12.7 的剩余问题，尤其不要把工程建议直接变成未经确认的请求拒绝条件。
3. 冻结本次去程范围，保留回程的专项复核与日志接线任务；更新 RFC 状态时分清两半。

本次文档落盘完成第 1 项，不代表第 2 项、任何代码或实机验收已完成。

### 8.2 阶段 1：入站还原与 system 支线

依赖阶段 0 的相关边界确认；下面两项可并行：

- `AnthropicController.buildRequestBody` 回填 `stream`。先写真实 JSON→DTO→Map 的
   true/false/缺省测试，证明进入译器前已有字段；`ctx.stream` 仍是执行与出站模式的权威值。
- 新增 `ChatSystemPromptStage`，委托 `SystemPromptNormalizer.inlineSystemIntoMessages`。
   测试双换行拼接、仅前导 billing 行过滤、同块正文保留、幂等、标准 CHAT no-op 和不可变输入。
   同步 system 三支线的 Spring 装配断言与 `PipelineContexts` helper。

### 8.3 阶段 2：纯结构转换

1. 内容协作类：文本/图片/文档/元数据与源类型边界；普通消息和工具结果复用同一转换规则。
2. 工具协作类：工具定义、schema、strict、选择与显式并行开关。第 1、2 项可并行。
3. 消息与配对：解析语义轮 → 请求内因果索引 → 重复检查 → 未应答/孤儿清理 →
    连续 tool 结果 → 带来源的媒体 user → 清理后判断 thinking 回放。依赖前两项。
4. `MessagesToChatRequestTranslator` 门面：顶层字段、显式 null、stop、format、
    system 中间态、include_usage 与 `TranslationContext`。声明 `(MESSAGES, CHAT)`，
    由现有注册表自动收集，不修改调度或主干。

每一项先补契约测试再接入；不复制现有反方向转换器，不加跨请求状态或新的 JSON/文件依赖。

### 8.4 阶段 3：真实接线与自动化收包

依赖阶段 1、2：

- Spring 测试断言新译器与 system 支线命中**容器里的同一 Bean**；
   覆盖 M→C、M→C/R 选 C、M→M/C 直连、M→R 仍未实现，回归已有 C2M/C2R 与三协议直连。
- 保留手工 stub 的未实现守卫，另补真实 M2C 的成功路径与 `RequestTranslationException` 信号。
- 新 `M2cRequestPipelineIntegrationTests`：真实 `AnthropicController` → DTO → `MessagesService`
   → 真实译器/装配器/Chat 执行器 → 随机端口 `HttpServer`。路由配置可 stub，日志和记账接口
   可 mock；不访问根目录 `admin.db` 或真实供应商。
- HTTP stub 必须检查实际序列化 body；只有收到 `include_usage=true` 才在 finish 后返回
   usage-only + `[DONE]`，否则不给。另用 CHAT 规则改 false 验证最终决定权。
- 本地翻译失败时上游调用数应为零；分别验证非流式 400、流式 HTTP 200 + `event:error`。
   上游 HTTP 错误沿用原有出口机制，不伪装成正常文本。

### 8.5 阶段 4：纯去程受控验收

依赖阶段 3，实机操作由用户手动执行：

- 新 `tools/live-test/d-m2c-request.js` 使用 `lib` 已导出的
   `request/mockMark/settledCount/waitForLog`，以 **CHAT** 显式计数。
   不复用 `probe` 的下游路径推断，也不在本次重写旧 C2M D 组。
- 使用独立 CHAT-only mock 供应商、带前缀模型名；核对上游实际被调用、最终出站 body 与
   日志协议列。普通 mock 不检查字段的限制必须写在报告里，字段正确性以阶段 3 收包测试为主。
- 准备可选的 CHAT 缓存稳定化规则样例，作用于 system 消息的 `messages[].content`；
   原 MESSAGES 样例不覆盖，用户现存配置不自动修改。
- 只把 M2C 去程标为完成；**不在此阶段验收 Claude Desktop 正常会话、不合并主干发布半轮。**

### 8.6 后续阶段：C2M 回程与完整发布

保留原「先非流式、再流式」顺序，但需先复核 §3.2/§4.3/§4.4 的草案，不能直接照表实现。

1. 非流式：确认完整响应映射、终止原因、usage 别名/缺失值，保证记账槽不重算。
2. 流式：每次下游订阅独立状态，覆盖首帧、尾部 usage、正常结束/异常/取消。
    自动重试发生在执行器内部，不能想当然认为外层翻译状态会随每次内部重试自动重建。
3. 修复 Chat 执行器的 `chunkRewriter` 参数传递；重放使用独立状态，实测双栏与 frameCounts。
4. 完成 §10.4 的 Claude Desktop 文档、多轮工具、错误展示和两家上游验证，再过完整发布文档门禁。

### 8.7 文件清单与可复用入口

以下路径相对仓库根目录；**新增文件只是计划**，不为它们建立指向不存在文件的链接。
Java 根包为 `src/main/java/com/kaixuan/copilot_ollama_proxy/`，测试根包为
`src/test/java/com/kaixuan/copilot_ollama_proxy/`。

| 范围 | 路径/符号 | 动作 |
|---|---|---|
| 入站 | `api/anthropic/AnthropicController.java` | 回填 stream，保留 DTO/错误出口职责 |
| 译器 | `pipeline/protocol/translate/MessagesToChatRequestTranslator.java` | 新去程门面 |
| 消息 | 同目录 `AnthropicMessageTranslator.java`、`AnthropicToolResultExpander.java` | 新语义轮/配对协作类 |
| 内容与工具 | 同目录 `AnthropicContentBlockTranslator.java`、`AnthropicToolTranslator.java` | 新纯转换协作类 |
| system | `pipeline/before/requestbody/system/ChatSystemPromptStage.java` | 新增 CHAT 支线 |
| system 纯逻辑 | 同目录 `SystemPromptNormalizer.java` | 增加 inlineSystemIntoMessages，不改变另两个方向 |
| 注释与状态 | `SystemPromptNormalizeStage.java`、`ProtocolTranslator.java`、`MessagesService.java`、`ProtocolDispatchManager.java` | 只纠正文案/实现状态；不改接口与调度顺序 |
| 新单测 | `pipeline/protocol/translate/MessagesToChatRequestTranslatorTests.java` 及必要的同包协作类 `*Tests` | 字段、媒体、schema、配对、输入不变性 |
| 新支线测试 | `pipeline/before/requestbody/system/ChatSystemPromptStageTests.java` | system 边界与幂等 |
| 新收包测试 | `pipeline/M2cRequestPipelineIntegrationTests.java` | 真 HTTP 入站与序列化出站、条件 usage |
| 现有测试 | `api/anthropic/AnthropicControllerTests.java`、`pipeline/ChatDispatchErrorSignalTests.java` | 入站字段与错误信号 |
| 装配测试 | `pipeline/before/requestbody/RequestBodyStageSpringWiringTests.java`、`RequestBodyAssemblerTests.java` | 三 system 支线与四模式/规则顺序 |
| 查表与调度测试 | `pipeline/protocol/translate/TranslatorRegistrySpringWiringTests.java`、`pipeline/before/dispatch/ProtocolDispatchManagerTests.java` | 真实 Bean 与独立 stub 分开验证 |
| 手工 helper | `testing/PipelineContexts.java` 的 registryWithAllBodyStages | 补 CHAT system；不改 Messages-only helper |
| 手工探针 | `tools/live-test/d-m2c-request.js`（新增）、`tools/live-test/README.md` | 纯去程范围和正确上游计数 |
| 可选规则 | `docs/reference/samples/claude-desktop-chat-cache-rule.json`（计划新增） | CHAT 版本，不改原 MESSAGES 文件或用户库 |
| 实施后文档 | `pipeline/README.md`（Java 根包内）、`docs/architecture/REQUEST_PIPELINE.md` | 与真实接线同批更新，不提前把计划写成当前架构 |
| 回程阶段专属 | `pipeline/after/send/chat/GenericOpenAiChatService.java`、`AbstractUpstreamChatService.java` | 传递日志改写器，纯去程阶段不动 |

复用 `RequestProtocolTranslator`、`TranslatedRequest`、`TranslationContext`、现有装配顺序、
`ReasoningEffortSetting.applyTo`、`RequestTranslationException`。
HTTP 测试方式参考 `ProviderRequestBodyTransformationIntegrationTests`，不是另引 MockWebServer。

---

## 9. 风险与对策

| 风险 | 对策与验收落点 |
|---|---|
| 入口丢 stream，手工译器测试假绿 | 真 JSON 入站与最终出站收包一起测，§12.1 |
| 新支线未被 Spring 收集，静默跳过 | `containsSame` 真 Bean，不能只测试手工 new，§12.3 |
| 反向配对误用、空结果被丢 | 请求契约 §10.4 的因果/空值矩阵，保留所有可配对的空应答 |
| 媒体被序列化成正文或孤儿图片漂出 | 共用块转换，媒体按有效调用归属，最终序列化 body 断言 |
| 无法映射文档仍继续回答 | 带字段路径显式失败；客户端实测确认模型确实看到文件 |
| 兜底模式因丢 null 而退化成覆写 | thinking/effort 各种表态 × 四模式，§10.1 |
| 用户 schema 被递归清洗破坏 | 不按同名键递归删 cache_control/signature，不改 JSON Schema 约束 |
| include_usage 缺失或被规则改写 | 条件 usage stub 与最终规则优先级测试，不造假的统计值 |
| 参考项目局部方法被误当完整实现 | 同时读调用者、注册表、序列化路径；固定版本证据，§12.8 |
| 旧 D 组/计数协议导致假成功或假失败 | 新 M2C 去程探针显式以 CHAT 计数，§12.4 |
| 半轮被当成可发布版本 | 去程仅受控验证；完整回程、日志与客户端验收后再发布 |
| 回程重算 usage、重复 reasoning 兜底或日志丢 rewriter | 留到回程专项；保持原生记账槽、核对既有 fallback、补日志真实接线 |
| 缓存/鉴权/大文件错误被误诊成字段翻译失败 | 记录 §12.6 的运行边界，不扩散到全局策略重构 |

---

## 10. 验收清单

### 10.1 纯转换、system 与设置层

- [ ] 顶层标量保留、白名单丢弃、单/多元素 stop 数组、错误元素路径。
- [ ] system 字符串/数组/空值、前导 billing 行的 LF/CRLF/CR、同块正文与非前导文本保留、幂等、no-op。
- [ ] user/assistant 单条与相邻语义轮、纯文本/图文顺序；角色及块位置异常不自动改角色。
- [ ] 单/多工具、多轮、乱序结果、孤儿、未应答、重复 ID、真正空结果、媒体-only、错误+媒体。
- [ ] 清理后有无工具调用决定 thinking 回放；无明文不补占位、纯文本轮不回放。
- [ ] tool 消息无 name；schema/strict/choice/parallel 显式布尔、缺省与空值。
- [ ] 文档 base64/text/content、工具内文档、title/context、URL/文件 ID/引用要求拒绝；不把媒体编码变正文。
- [ ] JSON Schema 输出与 effort 同时存在，处理一项不抹掉另一项。
- [ ] 输入深层内容不变，可处理 `Map.of`/`List.of` fixture；输出经后续规则可修改。
- [ ] 同输入转换确定、未变化历史结构不因随机值或排序变化而变动；业务参数中同名 cache_control/signature 不被删除。
- [ ] thinking disabled/enabled/adaptive/null、effort low/null、output_config null/empty/format-only
   分别覆盖 fallback/override/passthrough/delete；不误删历史 reasoning_content。
- [ ] CHAT 规则在 system 归一与思考设置之后执行、null 清理最后；MESSAGES 规则不误执行。

### 10.2 真 Bean、HTTP 入站与最终出站

- [ ] 新译器/system 支线 `containsSame` 实际 Bean；手工 stub 未实现守卫仍在。
- [ ] §2.4 的路由矩阵不变；不引入枚举声明顺序、模型名或译器内优先级判断。
- [ ] 入站 JSON 的 stream true/false/缺省经真实 DTO 回到 Map，翻译上下文与最终出站一致。
- [ ] 随机端口 HttpServer 实收 `/v1/chat/completions`、去前缀模型、正确 schema/文件/媒体/请求头。
- [ ] 条件 usage stub：只有出站 include_usage=true 才给尾帧。样例 prompt=100、completion=7、cached=40，
   去程半轮的记账槽与两处成功写入仍使用 100/7，不被减成 60/7。
- [ ] CHAT 规则显式关闭 include_usage 后，stub 不发 usage；不写用量明细，成功流式日聚合记 `0,0`。
- [ ] 本地翻译错误上游调用为零；非流式 400 与字段路径、流式 HTTP200 + event:error；Publisher 组装不抛同步异常。
- [ ] 上游 400/401 等错误沿用现有状态/正文路径，不被当正常模型文本。
- [ ] 回归 C2M/C2R、三协议直连、既有 system 支线与设置层。

执行时先做编辑器诊断，再运行包含编译的定向测试，例如
`./mvnw.cmd test "-Dtest=MessagesToChatRequestTranslatorTests,ChatSystemPromptStageTests,M2cRequestPipelineIntegrationTests"`，
最后 `./mvnw.cmd test` 全量。**以上命令为未来计划，本轮未执行。** 新类未编译前不能只调 surefire:test
而误读旧 target 为通过；本次不动 schema，无需额外迁移 tag。测试依赖以 pom 实际内容为准，
不为本文示例引入新的测试库。

### 10.3 用户手动：纯去程联测

- [ ] 新探针通过 Node 语法检查；复用低层计数函数，指定上游 CHAT。
- [ ] 独立 CHAT-only mock 供应商，正确地址与鉴权，模型名使用显式供应商前缀。
- [ ] mock `/chat/completions` 确实被调用；按协议/stream/场景计数，日志两列是 MESSAGES/CHAT。
- [ ] 对照最终出站 body；记录现有 mock 不校验字段/不提供条件 usage 的限制。
- [ ] 不重启用户正在使用的 COSP，不改生产供应商、真实凭据和根目录数据库。
- [ ] 明确此处只证明去程，收到原生 Chat 响应不是 Claude Desktop 功能验收。

### 10.4 用户手动：完整回程后的 Claude Desktop 专项

**不可省，也不可提前把下面勾为完成。** 当前仓库没有可复用的完整 Claude Desktop 文档请求样本；
先观察实际字段形态，不假设一定发送 base64 或一定不带引用要求。

1. PDF：上游实际收到 file part；在支持的上游上询问仅文档内存在的细节，确认模型看到了内容。
    不支持时应出现可理解的错误，不能静默丢文件后继续回答。
2. 纯文本及 content 源文档：正文、标题、上下文完整，图文顺序与来源可识别。
3. 工具返回图片/文档、文档+工具组合、至少五轮含并行工具的对话；验邻接和因果配对，不要求 Chat 全局角色交替。
4. 至少两家实际上游，其中包含官方端点与兼容端点；记录模型支持差异，不把官方 schema 当作所有上游能力保证。
5. 流式事件与非流式报文、签名键兼容、尾部 usage、上游错误展示、取消/异常与正常结束分别验证。
6. 有缓存样本时，在隔离调用窗口核对原生日聚合与明细 token 口径；下游拆分值按届时定稿的回程公式核对。
    未报告 cached_tokens 时应显示未知，不把非 null 当作所有上游必须满足的验收条件。
7. 补齐真实日志双栏/frameCounts 与独立状态重放，确认 chunkRewriter 没有在 Chat 执行器里丢失。

### 10.5 文档与发布门禁

- [x] 最终研究、计划已落入同一个功能 RFC；已确认决策进入 ADR，索引可达。
- [x] 请求契约有单一 M2C 目标规格，且明确未实现；未确认建议留在 RFC。
- [ ] §12.7 的未决项确认完毕后再冻结受影响的规格。
- [ ] 实施时同步 §2.3 的代码注释与测试，不提前宣称另一半完成。
- [ ] 接线落地后再更新架构活文档与可选规则/探针说明。
- [ ] 去程、回程、客户端及日志验收均完成后，追加实施记录并更新整体状态。

本轮只通过文档落盘检查，不构成功能合并/发布放行。

---

## 11. 实施记录

> 按阶段追加，**不改写历史**。计划与实际不符时在此记录偏差原因。

### 2026-10-21：最终调研与文档落盘（未开始编码）

- 完成 M2C 去程的入口→译器→装配→执行器追踪，以及四参考项目、官方 SDK 的交叉核对。
- 用户确认四项补充选择，已晋升 ADR-0012；不能等同为批准所有工程推论。
- 最终证据收入 §12，计划与验收更新为 §8/§10，逐字段目标集中到请求契约 §10。
- 订正此前关于 stream、file、tool.name、测试必红、usage、日志自动接线与参考项目一致性的误判。
- 保留回程草案，但明确其复核/接线任务；把「去程单独上线」纠正为开发中间态。
- 本次只修改 docs 文档，没有实现新译器、支线、测试、探针或配置样例，未运行构建/实机测试。

---

<a id="m2c-final-review"></a>

## 12. M2C 去程最终调研报告（2026-10-21）

### 12.1 真实入口丢失 stream：旧注入方案的阻塞点

| 调用位置 | 核对结果 |
|---|---|
| [`AnthropicMessagesRequest`](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/protocol/anthropic/AnthropicMessagesRequest.java) 的 setStream | 已建模字段不会再进 additionalProperties |
| [`AnthropicController.buildRequestBody`](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/api/anthropic/AnthropicController.java) | 不回填 stream；入站 stream=true 也不会进入传给译器的 Map |
| [`MessagesService.messagesStream`](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/entry/MessagesService.java) | 只把 true 交给 ctx，未改 body |
| [`BeforeSend.translateStep`](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/BeforeSend.java) | 仅调用 translateRequest(ctx.body()) |
| [`RequestBodyAssembler.assemble`](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/before/requestbody/RequestBodyAssembler.java) | 翻译之后才 body.put(stream, ctx.stream()) |

**丢的不是实际流式执行模式，而是翻译时可读取的流式意图。** 所以仅手工给译器 Map 填 stream
会产生假绿。最小修法是入口还原时补回，与 OpenAiController 的既有处理一致；
不扩大译器签名、不改 ctx/主干、不全局注入选项。阶段 1 和阶段 3 必须分别验证两端。

### 12.2 协议事实与参考实现的纠错

| 旧判断 | 最终核对与后果 |
|---|---|
| Chat file 是非官方扩展 | 官方固定 SDK 已定义；真正未知的是目标模型/上游支持，不是协议完全没有 |
| tool.name 是严格上游必需 | 官方 tool 消息未定义此键；用户确认不发，不能把扩展当必需字段 |
| thinking 回放三家完全同条件 | sub2api 限工具链；cc-switch 有兼容开关；CPA 有签名/兼容 gate，未限 hasToolCalls。保留 COSP 决策，修正依据 |
| 四家纯文本都输出字符串 | sub2api 折叠多块；cc-switch 仅单块折叠；CPA 保留数组。COSP 选择不依赖“全共识” |
| new-api 总是注入 parallel=true | 真入口 ExtractRequest→主体转换→AttachRequest；外层保留 strict 并按显式 disable_parallel_tool_use 取反 |
| 没有历史工具邻接约束 | Chat 同样要求调用后的结果连续；不能把现有 C2M normalizer 注释当协议规范 |
| Chat 永远无断点/none 只属 Responses | 2026-10-21 读取的官方 SDK main 已有新式缓存字段与 reasoning_effort 扩展；首版不自动适配，也不硬编码模型能力 |
| Chat 协议不可能托管 Anthropic 模型 | 协议格式与模型厂商独立；new-api 的 OpenRouter 方言分支可能落在 M2C 路线上 |

官方 SDK 定义只能证明字段存在，不证明 Claude Desktop 本次一定发送，也不证明所有兼容服务都接受。
动态 main 来源均记调研日期；正式兼容性结论仍需实际报文与目标上游验证。

### 12.3 测试装配：哪些会变，哪些不会

- [`ProtocolDispatchManagerTests`](../../../src/test/java/com/kaixuan/copilot_ollama_proxy/pipeline/before/dispatch/ProtocolDispatchManagerTests.java)
   的跳过候选测试自行 `managerWith(MESSAGES, RESPONSES)`，没有 Spring 集合；新 M2C Bean 不影响其命题。
- [`ChatDispatchErrorSignalTests`](../../../src/test/java/com/kaixuan/copilot_ollama_proxy/pipeline/ChatDispatchErrorSignalTests.java)
   同样手工构造只含 C2M 的调度器/注册表；应把说明限定为 fixture，而不是删除失败守卫。
- [`RequestBodyStageSpringWiringTests`](../../../src/test/java/com/kaixuan/copilot_ollama_proxy/pipeline/before/requestbody/RequestBodyStageSpringWiringTests.java)
   确实含 system hasSize(2)、仅 M/R、CHAT isEmpty；新增 CHAT system 后这些断言必须同步。
- [`PipelineContexts.registryWithAllBodyStages`](../../../src/test/java/com/kaixuan/copilot_ollama_proxy/testing/PipelineContexts.java)
   是手工 helper，必须主动加入新支线；否则部分测试仍在验证旧装配。
- [`TranslatorRegistrySpringWiringTests`](../../../src/test/java/com/kaixuan/copilot_ollama_proxy/pipeline/protocol/translate/TranslatorRegistrySpringWiringTests.java)
   需补真实 M2C 覆盖，但原有断言不会仅因多一个 Bean 自行失败。

结论：区分“测试将失败”和“测试缺少新行为覆盖”，不能为了制造/消除红灯改变原命题。

### 12.4 usage 与联测判据

[`UsageAccounting.recordStream`](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/api/shared/UsageAccounting.java)
无 usage 时仍记 `0,0`；成功流式计数保留。
[`AbstractUpstreamChatService.saveUsage`](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/after/send/chat/AbstractUpstreamChatService.java)
则在 usageRaw=null 时不写明细。**不能把“缺用量”写成“整个调用不存在”。**

[`TranslationContext.fromDownstreamBody`](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/protocol/TranslationContext.java)
在 C2M 上读取原始请求，前轮所谓 C2M includeUsage 恒 false 也不是事实。

联测方面：

- [`d-translate.js`](../../../tools/live-test/d-translate.js) 只有 C→M；通过它不等于验证 M→C。
- [`lib.probe`](../../../tools/live-test/lib.js) 由下游 apiPath 推 mock 计数协议；跨协议时会计错。
   新探针选用已导出的低层函数，明确传上游 CHAT，避免扩大本次修改面。
- [`mock-upstream.handleChatRequest`](../../../tools/mock-upstream/mock-upstream.js) 只给场景传 res/model，
   不校验 messages/tools/stream_options；[`chat.writeNormalStream`](../../../tools/mock-upstream/protocols/chat.js)
   也不按选项输出尾部 usage。**2xx、调用数与字段正确性是三种不同证据。**

### 12.5 回程日志钩子没有自动接通

[`AfterSend`](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/AfterSend.java) 构造 rewriter 后交给执行器；
[`GenericOpenAiChatService.invokeStream`](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/after/send/chat/GenericOpenAiChatService.java)
收到但没有传给父类，父类各处 saveStreamLog/saveStreamLogWithError 仍给 null。

这影响**日志双栏**，不是说主干回程翻译完全无效。纯去程不需要改它；完整回程必须修传递路径
并测试 `ChunkLogPayload`，不能只断言 AfterSend 已构造一个函数就宣布观测可用。

### 12.6 运行边界与现有配置

| 项目 | 当前事实/限制 | 计划边界 |
|---|---|---|
| 鉴权 | `AuthHeaderSetting` 默认取下游；只带 x-api-key 时会沿用头名、换供应商 key | 只认 Bearer 的上游由后台配置 CONFIGURED/AUTHORIZATION，不让译器代选 |
| 其它请求头 | anthropic-version/beta 等端到端头可继续透传 | 必要时用现有头规则删除，不加通用黑名单 |
| max_tokens 别名 | CHAT 没有对应归一化支线；规则引擎不提供任意数值字段 copy/rename | 不声称现有规则能无损动态重命名；首版不按模型名猜，后续配置能力另议 |
| 缓存规则 | [现有样例](../../reference/samples/claude-desktop-cache-rule.json) 只选 MESSAGES/system[*].text | 规划单独 CHAT/messages[].content 可选样例；仅加协议标签不够，不自动改库 |
| 大文件 | `WebClientConfig` 聚合缓冲 64MiB；base64 约放大 4/3，Jackson 单字符串约束可能更早触发 | 不承诺 64MiB PDF，不在翻译功能中扩大限制；执行时核对实际 Jackson 版本/约束 |
| 错误出口 | 非流式透传上游状态/原文；流式 HTTP200 + event:error，原文可能仍是 Chat 形态 | Claude Desktop 错误展示需测试；必要适配属于出口而不是去程译器 |
| count_tokens | 当前无端点，尚无本轮客户端调用样本 | 独立需求，不把它混入翻译器；不假定每次文档操作必调用 |
| 开发半轮 | 去程命中即会发送，回程缺失且 gate 刻意跳过 | 仅受控验证，不能独立生产发布 |

代码入口：[`AuthHeaderSetting`](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/application/runtime/AuthHeaderSetting.java)、
[`ProviderRequestHeaderService`](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/application/provider/ProviderRequestHeaderService.java)、
[`RequestBodyRuleEngine`](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/application/provider/RequestBodyRuleEngine.java)、
[`WebClientConfig`](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/infrastructure/config/WebClientConfig.java)、
[`UpstreamErrorRenderer`](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/api/shared/UpstreamErrorRenderer.java)。

<a id="m2c-open-items"></a>

### 12.7 已确认与未确认必须分开

**已确认策略**见 ADR-0012；以下从会话 plan 保留下来的是实施建议或待决细项，
本次仅要求文档归档，不能借此把它们全部升级为 Accepted：

| 条目 | 当前方案/备选 | 状态与实施门槛 |
|---|---|---|
| 末尾普通 assistant prefill | Chat 无通用等价续写保证；建议显式拒绝，也可保留输入交目标上游处理 | **待确认**；未答工具的既定清理规则不等于批准拒绝所有 assistant 结尾请求 |
| input_examples / allowed_callers / defer_loading / eager_input_streaming | 不应悄悄削弱执行限制；建议按值区分默认无操作、可等价表达、不能表达 | **待确认**逐字段策略；不能仅因字段出现就全拒，也不将它们伪装成普通函数属性 |
| 非空 container/mcp_servers/context_management | 涉及服务端执行/隐藏上下文，建议拒绝非等价要求；空值与无操作配置另分 | **待确认**值级边界；用户已确认的是无法等价时显式失败，不是所有容器对象一律失败 |
| JSON Schema 输出包装 | 建议固定 name=`cosp_output`，strict=true，schema 不改；旧 beta output_format 不隐式转换 | 包装为**工程建议**；旧别名的拒绝/支持需在冻结规格前确认，不静默丢显式格式意图 |
| 元数据固定文案与混合内容排序 | 仅编码已有错误、来源、标题/上下文；不添加随机量，分别保持文本与媒体顺序 | 允许最小标记已确认，**具体文案/拼接 golden**在实现时冻结 |
| 缺省/null 的 tool_use.input | 建议沿用无参兼容映射为空对象；非对象非空值报错 | 工程建议，不能用 String.valueOf 兜底；与空 tool_result 不是同一字段 |
| 回程未知值、首帧 usage、finish_reason、signature、EOF/取消/错误与日志 | 保留 §3.2/§4 的调研草案 | **后续回程专项**，此次去程计划不代替其定稿 |

其它机械细节（例如单元素 stop 保持数组）按契约标注为目标方案并用测试固定，
无需每个常量各写一篇 ADR。只有形成新的行为取舍时才新增/取代决策。

### 12.8 证据索引与版本

四个本地参考仓库都在 `references/`，本轮未修改、构建或测试。提交与前轮相同，
因此本次改正来自检查范围扩大，不归因于参考项目更新：

| 项目 / 固定提交 | 核对入口 |
|---|---|
| cc-switch `f92ff0b03964188f1cd011d9302ab8869d7a8219` | `src-tauri/src/proxy/providers/transform.rs` 的单消息转换、billing 与 include_usage；`providers/claude.rs` 真调用者；`proxy/tool_media.rs` 的媒体归属/延后 |
| new-api `32e587406c6ebe891f18ab9aeb2f4a1f6b7b9e14` | `relaykit/relayconvert/internal/claude_messages/to_oai_chat_req.go`；`request_registry.go` 的 Extract/Attach；`internal/toolconv/decode.go`、`encode.go`；`relay/channel/openai/adaptor.go` |
| sub2api `8f2cbb53d9d1c40e1865b07ac460571f7d4c48de` | `backend/internal/pkg/apicompat/chatcompletions_anthropic_bridge.go`；`chatcompletions_responses_bridge.go` normalize；`anthropic_to_responses.go` 结果 helper；`internal/service/openai_gateway_messages_chat_fallback.go` 真入口 |
| CLIProxyAPI `ae3ee3be303fb07fb8173279923413005bf32177` | `internal/translator/openai/claude/openai_claude_request.go`；`translator/common/openai_tools.go`、`parts.go`；`internal/runtime/executor/openai_compat_executor.go` 的 usage→payload 配置顺序 |

**官方来源**（本轮部分官网页面返回 403/区域不可用，使用官方 SDK 源码验证字段，不冒称读到了官网正文）：

- OpenAI 固定 `v1.109.1`：[Chat file](https://github.com/openai/openai-python/blob/v1.109.1/src/openai/types/chat/chat_completion_content_part_param.py)、
   [tool 消息](https://github.com/openai/openai-python/blob/v1.109.1/src/openai/types/chat/chat_completion_tool_message_param.py)、
   [JSON Schema 输出](https://github.com/openai/openai-python/blob/v1.109.1/src/openai/types/shared_params/response_format_json_schema.py)、
   [function strict/schema](https://github.com/openai/openai-python/blob/v1.109.1/src/openai/types/shared_params/function_definition.py)。
- OpenAI 动态 main（2026-10-21 读取）：[Chat 参数](https://github.com/openai/openai-python/blob/main/src/openai/types/chat/completion_create_params.py)、
   [text part 缓存断点](https://github.com/openai/openai-python/blob/main/src/openai/types/chat/chat_completion_content_part_text_param.py)；
   只记录 API 演进，不将当前模型支持表硬编码到 COSP。
- Anthropic 动态 main（2026-10-21 读取）：[document 源类型](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/document_block_param.py)、
   [tool_result 可选内容](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/tool_result_block_param.py)、
   [tool 定义](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/tool_param.py)、
   [output_config](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/output_config_param.py)、
   [JSON 输出 schema](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/json_output_format_param.py)、
   [content 源](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/content_block_source_param.py)、
   [并行开关](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/tool_choice_auto_param.py)、
   [语义轮与 prefill](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/message_create_params.py)。
- Jackson `2.19.4`：[StreamReadConstraints](https://github.com/FasterXML/jackson-core/blob/jackson-core-2.19.4/src/main/java/com/fasterxml/jackson/core/StreamReadConstraints.java)
   的默认字符串长度为 20,000,000；这是库版本证据，不是已运行 COSP 后测得的文件大小上限。

# `pipeline/` —— 主干-支干轴

> **本文件入库**（`.gitignore` 的 `/*.md` 只忽略**根目录**，且 `!README.md` 全局豁免）。
> 因此它是这批结构里**唯一能跟着 `git pull` 到达另一台设备**的说明 ——
> `plan_.md` / `AGENTS.md` / `REFACTOR_HANDOFF.md` 都在仓库根、都不入库，靠人带。
> **跨设备协作时以本文件为准。**

> **历史**：本文件原属 `upstream/` 层（`upstream/README.md`），阶段 5 包结构归拢时随包整体
> 迁到 `pipeline/`。它描述的形态随之从「上游执行层」升级为「**主干-支干轴**」——
> 因为归拢后，发送前块与发送后块终于同属一个顶层包。

---

## 0. 定位：四轴框架与主干-支干形态

判断「某个类该住哪」的总尺子是**四条轴** —— 每个轴一个顶层包，名字即答案：

| 轴 | 判据 | **测试它**（删掉会怎样） | 顶层包 |
|---|---|---|---|
| **主干-支干** | 一次请求的数据怎么流；协议差异是**数据**（主干）还是**代码**（支线） | 下游收到的**内容**会变 | **`pipeline/`**（本层） |
| **下游边界（出口）** | 怎么收进来、怎么送出去；按**下游**协议分岔 | 下游**收不到**东西 | `api/` |
| **控制面** | **人**在外部作用于在途请求（取消 / 人工重发） | 人**没法插手** | `control/` |
| **观测** | 让**人**看见发生了什么（Toast / 明细日志 / 统计） | 下游收到的**完全不变**，但后台瞎了 | **`observability/`**（已归拢） |

**最后那条测试是识别观测轴的关键**：观测的东西可以整段删掉而请求照常成功
（`UpstreamCallReporter` / `CallLifecyclePublisher` / `ApiUsageCollector` 都满足它）。

**依赖方向**：`主干 → 观测` 单向（与 `主干 → 应用服务` 同向）。观测**永不**被主干依赖回来。
**已实测兑现**（第 7a-1 步）：`observability/` 对外只 import `protocol/` 与自身，
对 `application/` / `infrastructure/` / `api/` / `control/` 零反向依赖。

> 另外三个顶层包是**支撑**而非轴：`application/`（应用服务与后台查询）·
> `protocol/`（纯 DTO，无 Spring 依赖）· `infrastructure/`（持久化 / 安全 / 配置）。

### 本层内部的形态：主干 / 支线 / 出口

本项目自造的形态名：**SESE Pipeline with Joining Branches**（带汇回支线的单入口单出口管道）。

```
                    ┌─ 支线 ─┐
主干 ── 分岔点 ─────┤        ├──── 汇回 ── 主干继续 ──→ 出口（按下游协议分岔，不汇回）
                    └─ 支线 ─┘
```

| 概念 | 判据 | 本层里的例子 |
|---|---|---|
| **主干** | 协议差异是**数据** | `protocol/UpstreamEvent`、`after/attempt/UpstreamRetryPolicy`、`after/attempt/EmptyResponseGate` |
| **支线** | 协议差异是**代码**，且**有进有出、同类型进出** | `before/requestbody/`、`before/outbound/`、`after/chunk/`、`after/content/`、`after/send/` 五个接入点 |
| **出口** | **只有出没有回** | 不在本层（在 `api/` 的三个 Controller 里，按下游协议分岔） |

**最关键的一条约束**：支线**必须汇回**主干。因此它只能同类型进出，契约被压得很窄
（不能改下游协议 / 重试语义 / 落库形态）—— **扩展因此才安全**。

顺带一句与 `application/` 的分工：`pipeline/` 只回答「**一次请求的数据怎么流**」。
「人可能在后台中途插手」（取消 / 人工重发）是**另一条轴**，在 `control/`。

### 「主干」在本层有两个含义，别混

| 说法 | 指的是 |
|---|---|
| **主干（vs 支线 / 出口）** | 形态角色 —— 协议差异是数据 |
| **主干（vs 边缘）** | 结构位置 —— 本层目录树里**不带块前缀**的部分（`RequestPipeline` / `ctx` / `protocol/`） |

第三层含义是**块**：发送前块 `before/` 与发送后块 `after/` —— 二者都是「主干」的一部分，
只是被「真正发出 HTTP」这条缝切成了两半。`entry/` 则是它们的**调用者**。

---

## 1. 目录树与它的读法

```text
pipeline/                         ← 主干-支干轴（阶段 5 归拢后的形态）
├── RequestPipeline               门面（两行：beforeSend.process → return afterSend.process）
├── RequestPipelineContext        请求级状态 + 本次选中的策略（跨块传递）
├── PipelineStep                  可跳过步骤的词汇表（枚举）
│
├── entry/                        接口服务：三端点 → 主干入口的适配层
│   ├── ChatCompletionService         下游 CHAT
│   ├── ResponsesService              下游 RESPONSES
│   └── MessagesService               下游 MESSAGES
│       ↑ 三者同形：defer 包裹 → ctx.forEndpoint(...) → RequestPipeline.execute(ctx)
│
├── before/                       ★ 块 1：发送前（同步 void step(ctx)）
│   ├── BeforeSend                     编排：routeStep → translateStep → assembleStep → assembleOutboundStep
│   ├── dispatch/                      ProtocolDispatchManager + Decision + ProviderProtocolSupport
│   ├── notify/                        ProtocolNotifier（把协议决策写进生命周期事件）
│   ├── requestbody/               ★ 接入点：请求体装配（含 3 步）
│   │   ├── RequestBodyAssembler          主干侧编排：协议无关阶段序列 + 查表调三步支线
│   │   ├── RequestBodyStageRegistry      接入点级查表（键 = bodyProtocol；未命中**跳过**）
│   │   ├── system/      SystemPromptNormalizeStage + SystemPromptNormalizer + MessagesSystemPromptStage
│   │   ├── maxtokens/   MaxTokensNormalizeStage + MaxTokensNormalizer + MessagesMaxTokensStage
│   │   └── thinking/    ThinkingInjectStage + AnthropicThinkingNormalizer +
│   │                    Messages / Chat / ResponsesThinkingStage（三协议各一）
│   └── outbound/                  ★ 接入点 = 步骤：出站请求装配（刀 3 B）
│       ├── OutboundRequestStage       契约：protocol() + resolveBaseUrl + applyProtocolHeaders
│       ├── OutboundRequestStageRegistry  查表（键 = upstreamProtocol；**未命中即报错**）
│       ├── OutboundRequestAssembler   主干侧编排：三层头装配 + 支线选列/补协议头 → ctx.applyOutbound
│       ├── chat/       ChatOutboundStage      （读 base_url；无协议必需头）
│       ├── messages/   MessagesOutboundStage  （读 anthropic_base_url；补 anthropic-version）
│       └── responses/  ResponsesOutboundStage （读 responses_base_url；无协议必需头）
│
├── after/                        ★ 块 2：发送后（返回 Flux 的异步状态机）
│   ├── AfterSend                      编排：选执行器 → send 插槽 → 回程翻译插槽
│   ├── attempt/                       每轮尝试级控制（协议无关，三线共用）
│   │   ├── UpstreamRetryPolicy            自动重试①：要不要重试（纯判定）+ 两个异常解包
│   │   ├── UpstreamAutoRetry              自动重试②：几次、多久（读配置 + 组装 Retry）
│   │   ├── EmptyResponseGate              空响应拦截机制（流式扣放 / 非流式一次判 / 耗尽放行）
│   │   └── EmptyUpstreamResponseException 空响应信号异常（复用重试预算）
│   │       （`UpstreamCallReporter` 第 7a-1 步已搬 `observability/notify/`：它零主干依赖）
│   ├── send/                      ★ 接入点：发送（唯一一步）
│   │   ├── UpstreamExecutor           契约：protocol() + invoke / invokeStream
│   │   ├── UpstreamExecutorRegistry   查表（键 = 上游协议；**未命中即报错**）
│   │   ├── UpstreamCallRunner         外层编排骨架（**无状态静态**）：主干后半段的家 ——
│   │   │                              defer/gate/retryWhen/耗尽放行/resendloop/doFinally + 落库 2 份
│   │   ├── AttemptContext             协议无关的流级状态容器：计时 / logChunks / respHeaders /
│   │   │                              statusCode / ttfb / emptyResponsePassthrough
│   │   ├── chat/       AbstractUpstreamChatService + GenericOpenAiChatService + OpenAiUsageParser
│   │   ├── messages/   GenericAnthropicChatService + AnthropicUsageParser
│   │   └── responses/  GenericResponsesChatService + ResponsesUsageParser + ResponsesStreamEvents
│   ├── chunk/                     ★ 接入点：回程帧处理（含 2 步）
│   │   ├── ChunkStageRegistry         接入点级查表（键 = upstreamProtocol；未命中**跳过**）
│   │   ├── normalize/   ChunkNormalizeStage + UpstreamChunkNormalizer + ChatChunkNormalizeStage
│   │   └── fallback/    ReasoningFallbackStage + ReasoningFallback + ChatReasoningFallbackStage
│   └── content/                   ★ 接入点 = 步骤：空响应判定（唯一一步）
│       ├── ContentDetectorStage       契约
│       ├── ContentDetectorRegistry    查表（键 = upstreamProtocol；**未命中即报错**）
│       └── OpenAi / Anthropic / ResponsesContentDetector + 三个 *ContentDetectorStage
│
└── protocol/                     跨块 / 跨层词汇与契约（不下沉的唯一理由：跨块）
    ├── WireProtocol                   三协议枚举（**全项目扇出最高**：56 个 main 文件）
    ├── UpstreamEvent                  统一形态（Body / Terminal 两态）；`api/` 也消费
    ├── UpstreamEventClassifier        把一帧分成「载荷」与「终止标记」两态
    ├── NoSupportedProtocolException · ProtocolTranslationNotSupportedException
    │   RequestTranslationException · ResponseTranslationException   ← 出口做状态码映射要用
    ├── ProtocolTranslator · TranslatedRequest · TranslationContext
    └── translate/                     请求/响应翻译支线（17 个实现 + 契约 + 注册表）
        RequestProtocolTranslator · ResponseProtocolTranslator · TranslatorRegistry
```

> **两个跨层的值类型已上提到顶层 `protocol/`**（第 7a-1 步），因为它们消费方跨三个顶层包，
> 留在本层会让那些包反向依赖 `pipeline/`：
> - `protocol/ChunkLogPayload` —— 生产在块 2，消费在 `observability/record/` 与 `infrastructure/persistence/`；
> - `protocol/usage/UsageTokens` —— 生产在块 2（三个 `*UsageParser`），消费在 `observability/record/`
>   （本来它在 `application/usage/`，会让 `application ⇄ observability` 成环）。

### 四条读法（为什么长这样）

1. **顶层包 = 一条轴**（见 §0 的四轴表）—— 本层是「主干-支干」那条轴。
2. **块在次层**：`before/` 与 `after/` 是「真正发出 HTTP」切出的两半；`entry/` 是它们的调用者。
   **依赖方向：`entry/ → before/ → after/`，而 `protocol/` 在上面**（谁都能用，它不依赖任何块）。
3. **接入点作子包** —— 子包名 = 这条支线**挂在主干的哪个接入点**：
   `requestbody`（请求体装配）/ `outbound`（出站请求装配）/ `send`（发送）/
   `chunk`（回程帧）/ `content`（空响应判定）。**前两个在 `before/`，后三个在 `after/`。**
4. **步骤自成子包 / 按数量分组** —— 接入点下每个**步骤**一个子包（1 个契约 + 各协议实现 + 包装）；
   **接入点只有一步时，接入点即步骤**（`content/` `outbound/` `send/` 不套步骤层）。
   协议实现的分组看数量：**只有一两个小类时平铺**（`content/`），
   **有一组专属类时按协议分子包**（`send/` 与 `outbound/` 的 chat/messages/responses）。
   两种都在用 —— 分组是为了可读，不是为了整齐。

> **为什么一定要按步骤拆**：`system` / `max_tokens` / `thinking` 三步确实都围绕 body，
> 但那只是**位置共性**；它们在 `RequestBodyStageRegistry` 里是**三张独立表、三个独立查表键**，
> 即**三个独立步骤**。按共性合并会用一个共性抹掉三个语义单位。
>
> **为什么 `protocol/` 不下沉到某个块**：判据是**跨块**，不是「重要」或「通用」——
> 放哪个块都错，才留在公共层。`UpstreamEvent` 被 `api/` 与三个 Service 消费、
> `ChunkLogPayload` 被 `infrastructure/persistence/` 消费、四个异常被出口分类器消费，
> 翻译契约则被两个块 + `ctx` 共引。

### 扩展语义（加东西时加在哪）

| 你要做的事 | 落点 | 代价 |
|---|---|---|
| 加一个**上游协议** | 每个**步骤的包**里加一个实现类（`@Component`）—— 含 `before/requestbody/` 三步、`after/chunk/` 两步、`after/content/` `before/outbound/` 各一步；`after/send/` 下加一个执行器 | 动 N 个包 —— 但那正是领域事实：**每个步骤都得能为新协议表态**，少一个会在那条线路上报装配错误或静默跳过 |
| 加一个**步骤** | 加一个子包（契约 + 各协议实现 + 在接入点注册表的表里加一项） | 主干一个字不动 |
| 加一个**接入点** | 加一个子包 + 一个接入点级注册表 | 主干加一个调用点 |

> **「加一个 `@Component` 即被查表命中」—— 五张表现在都成立**（2026-09-25 核实）。
> 它曾**只对 `content/` 与 `send/` 成立**：那时 `requestbody/` 与 `chunk/` 两个注册表的
> **查表键是常量**（`RequestBodyStageRegistry` 只被 Messages 执行器调用、恒查 `MESSAGES`；
> `ChunkStageRegistry` 只被 Chat 执行器调用、恒查 `CHAT`），于是给别的协议补实现**不会被调用**。
> 那是 **send 插槽边界画错**的表征（阶段 4）。
>
> **阶段 4 刀 1 / 刀 3 B 已消解这一条**：
> - `RequestBodyStageRegistry` 的消费者已上移主干（`before/requestbody/RequestBodyAssembler`），
>   键是 **`ctx.bodyProtocol()`**（动态）→ 给任一协议补实现都会被调用；
> - `ChunkStageRegistry` 的键是 **`ctx.upstreamProtocol()`**（动态，跨协议路由下会是上游协议那个值）
>   → 同样命中；它的消费者仍只有 Chat 执行器（`after/send/chat/AbstractUpstreamChatService`），
>   那是**回程中段仍在执行器闭包**的残留（见 §4.1），不是查表键的问题。
>
> 因此「加一个 `@Component`」现在对**五张表**都成立。剩下的偏差是「谁调用」而非「能不能命中」。

---

## 2. 按执行顺序的步骤树

> ⚠️ **本节编号只供阅读，不是代码里的引用坐标。** 步骤号是**全局位置**，插入一步会让后面全部错位
> （本项目已有同类教训：plan 里记过的「行号会漂」）。**类注释里写的是步骤的「基名」（如
> `空响应判定`）而非编号**。引用某一步时，请写**基名**或**类名**。

> ⚠️ **协议无关步骤的归位进度（2026-09-25，块化与归拢后）**：曾经这批步骤**全在 send
> 插槽内部**执行（三执行器各持私有管道、控制流方向反了）。**阶段 4 刀 1/2/3（含 B）已矫正**：
> - **刀 1 ✅** 请求体装配上移主干（现为发送前块步骤 6 `assembleStep`）；
> - **刀 2 ✅** 内层 gate 编排 / 帧处理 / 落库 / `retryWhen`（步骤 10–14）收归
>   `after/send/UpstreamCallRunner`（**无状态静态编排件**），流级状态入 `after/send/AttemptContext`；
> - **刀 3 ✅** 块化：`RequestPipeline.execute` 按「真正发出 HTTP」拆成发送前块 `BeforeSend`
>   （同步 `void step(ctx)`，步骤 4–7）与发送后块 `AfterSend`（返回 Flux 的异步状态机，步骤 8 起）；
>   翻译对经 ctx 跨块传递（回程翻译器在发送前块选、发送后块用）。
> - **刀 3 B ✅** 出站头与地址装配上移发送前块（现为步骤 7 `assembleOutboundStep` →
>   `before/outbound/OutboundRequestAssembler`）：协议无关三层头在主干、协议特定的选列 + 补版本头走
>   `before/outbound/` 支线（按 `upstreamProtocol` 查表，未命中报错）。执行器 `buildWebClient` 只剩
>   「铺 ctx 的头和地址 + 抓传输层快照 + 发送」，那些要等真正发请求那一刻，故留发送后块。
>
> 现在的形态是「主干（经 runner 编排）持流程，执行器只填传输 + 协议特有中段」。
> 步骤树里唯一标着「执行器」的那一步（上游往返）是执行器交出的**传输闭包** ——
> 由 runner 在每轮 `defer` 内调起，既不是主干持有的步骤、也不属于某个抽象「支线」包，
> 因此**不再带「主干：」前缀**。其余步骤都已归主干或具名支线。
> 契约收缩（`UpstreamExecutor` → transport）**已降级为可选清理**（见 §4.1），
> 故执行器仍持有这些闭包 —— 那不是偏差，是「协议特有的传输细节」的正当归属。
> `EmptyResponseGate` / `UpstreamAutoRetry` 等机制一直是主干件；刀 2 把**编排**也搬回了主干侧。

```
下游请求（三条端点之一）

步骤 0  出口：入站鉴权与日志（WebFilter 链，非主干的一部分）
        GatewayAuthFilter · RequestLoggingFilter · ResponseLoggingFilter · SseConnectionGate

步骤 1  出口：协议端点（api/ 的三个 Controller）
        OpenAiController(下游 CHAT) · AnthropicController(MESSAGES) · ResponsesController(RESPONSES)

── 主干开始 ────────────────────────────────────────────────────────────────

步骤 2  主干：端点服务（三条同形）
        ChatCompletionService / MessagesService / ResponsesService
        作用：defer 包裹 → ctx.forEndpoint(...) → RequestPipeline.execute(ctx)

步骤 3  主干：管道入口（唯一）        RequestPipeline.execute(ctx)
        主干唯一入口，只有两行：beforeSend.process(ctx) → return afterSend.process(ctx)
        以「真正发出 HTTP」为界拆成两个功能块（阶段 4 刀 3 块化）：
          ├─ 发送前块 BeforeSend —— 同步 void step(ctx) 序列（步骤 4~7）
          └─ 发送后块 AfterSend  —— 返回 Flux 的异步状态机（步骤 8 起）

── 发送前块（BeforeSend.process：同步改 ctx）─────────────────────────────────

步骤 4  发送前块：路由步骤            BeforeSend.routeStep(ctx)
          ├─ ProviderRouteResolver.resolve(model)     解析供应商 + 剥 [provider-key] 前缀
          ├─ ProtocolDispatchManager.dispatch(...)    决定「直连还是翻译 + 上游协议」
          ├─ ProtocolNotifier.notifyProtocols(...)    把 (下游, 上游) 写进生命周期事件
          └─ RequestPipelineContext.applyRouting(...) 就地回填路由与调度结论进 ctx
        （块化后不再返回 PipelinePreamble —— route 结论直接写 ctx）

步骤 5  发送前块：翻译步骤【支线】     BeforeSend.translateStep(ctx)
        ★ 仅跨协议时执行（ctx.translationNeeded() 为假则整步跳过）
          ├─ 去程 RequestProtocolTranslator（键 = downstream + upstream）
          │    未命中语义：**报错**
          │      ├─ 分支 C2M（下游 CHAT、上游 MESSAGES，已实现）
          │      │    ChatToMessagesRequestTranslator → MessageTranslator / ToolTranslator /
          │      │    ContentBlockTranslator / ToolPairingNormalizer / StopReasonMapper
          │      └─ 分支 其余方向 → 未实现，抛 ProtocolTranslationNotSupportedException
          ├─ 回程 ResponseProtocolTranslator（未命中记 null，留给发送后块透传）
          ├─ RequestPipelineContext.applyTranslation(...)   回填翻译后 body + 上游协议
          ├─ RequestPipelineContext.applyTranslators(去程, 回程)  翻译对进 ctx（回程跨块用）
          └─ RequestPipelineContext.markCompleted(REQUEST/RESPONSE_TRANSLATION)
             作用：shouldApplyEmptyResponseGate() 读它 —— 半轮实现态跳过空响应拦截

步骤 6  发送前块：装配步骤            BeforeSend.assembleStep(ctx) → RequestBodyAssembler.assemble(ctx)
        八阶段的显式序列（协议无关公共序列 + 协议特定支线）：
          ① 复制             浅拷贝，不污染调用方
          ② 解析模型名       剥前缀
          ③ 写协议字段       写 model + stream
          ④ system 抬升      【支线】未命中即跳过（Chat/Responses 没有这一步）
          ⑤ max_tokens 落定  【支线】同上（只有 Anthropic 有）
          ⑥ 思考注入         【支线】同上（三协议各一实现；Anthropic 内部再分深度/方式）
          ⑦ 请求体规则       RequestBodyRuleEngine.transform（按 bodyProtocol 筛组）
          ⑧ 清 null          **必须最后**
          ├─ 支线：请求体协议特定步骤（RequestBodyStageRegistry，键 = bodyProtocol）
          │    未命中语义：**跳过**（「该协议没这一步」是领域事实）
          │      system/      SystemPromptNormalizeStage    → MessagesSystemPromptStage
          │      maxtokens/   MaxTokensNormalizeStage       → MessagesMaxTokensStage
          │      thinking/    ThinkingInjectStage           → Messages / Chat / ResponsesThinkingStage
          └─ 分支：请求体规则 RequestBodyRuleEngine.transform（按协议筛组）
        ★ 装配放在翻译之后 —— body 此时已是上游形态，装配据 bodyProtocol 查表

步骤 7  发送前块：出站装配步骤【支线】 BeforeSend.assembleOutboundStep(ctx) → OutboundRequestAssembler.assemble(ctx)
        定下「发去哪、带什么头」，写进 ctx.outboundHeaders/outboundBaseUrl：
          ① 解析地址   支线 resolveBaseUrl（读协议特定列）→ normalizeBaseUrl（协议无关尾斜杠归一）
          ② 三层头     主干：透传下游头（除 hop-by-hop / Host / Content-Length）
                       → 按供应商级配置装鉴权头（AuthHeaderSetting，先删两个再注一个）
                       → 应用请求头规则（{apiKey} 占位、/del/ 删除）**拥有最终决定权**
          ③ 协议头     支线 applyProtocolHeaders（set-if-absent）—— **必须在②之后**，规则才能覆盖
          ├─ 支线：出站装配（OutboundRequestStageRegistry，键 = upstreamProtocol）
          │    未命中语义：**报错**（每条线路都必须能解析出地址）
          │      chat/       ChatOutboundStage      → 读 base_url，无协议头
          │      messages/   MessagesOutboundStage  → 读 anthropic_base_url，补 anthropic-version
          │      responses/  ResponsesOutboundStage → 读 responses_base_url，无协议头
        ★ 协议无关的三层头装配在主干，协议特定的两件事（选列 / 补版本头）在支线

── 发送后块（AfterSend.process：返回 Flux 的异步状态机）───────────────────────

步骤 8  发送后块：读两态 + 选执行器    AfterSend.process(ctx)
          ├─ ctx.responseTranslator()                 从 ctx 读回程翻译器（不再自己查表）
          ├─ ctx.stream()                             主干上唯一一次读「是不是流式」
          └─ UpstreamExecutorRegistry.require(ctx.upstreamProtocol())
             未命中语义：**报错**（装配坏了，不是领域事实）

步骤 9  插槽：发送                    UpstreamExecutor
          invoke(ctx, chunkRewriter)        → Mono<UpstreamEvent>（一次取全）
          invokeStream(ctx, chunkRewriter)  → Flux<UpstreamEvent>（逐事件）
        ★ 主干按 ctx.stream() 选这两个方法之一 —— 两态分岔的第一处
          ├─ 分支：GenericOpenAiChatService（上游 CHAT）
          ├─ 分支：GenericAnthropicChatService（上游 MESSAGES）
          └─ 分支：GenericResponsesChatService（上游 RESPONSES）

步骤 10 执行器：上游往返（一次）
          ① 建 WebClient：铺 ctx.outboundHeaders + ctx.outboundBaseUrl（出站装配已在步骤 7 完成）
             + capturingHttpClient / filter（抓传输层头快照，须待真正发出请求那一刻）
          ② Flux.defer 每轮起点重置（计时 / chunk 收集 / gate.reset）
          ③ HTTP 往返（流式 exchangeToFlux；非流式 retrieve().toEntity）
          ④ 错误响应分支 → 即时落库 + Flux.error(WebClientResponseException)
          ⑤ 发 CONNECTED 生命周期事件
        ★ 前缀是「执行器」而非「主干」：本步是执行器交出的**传输闭包**
          （由 runner 在每轮 defer 内调起），不属于主干持有的步骤。见上方归位说明。

步骤 11 支线：空响应拦截
        机制（主干）：EmptyResponseGate —— 流式扣放 / 非流式一次判 / 耗尽放行
        契约（支线）：ContentDetectorStage，经 ContentDetectorRegistry.require(upstreamProtocol) 查表
        未命中语义：**报错**（每协议都必须能判空，判不了 = 空响应兜底对那条线路失效）
        ★ 只有**检测器**是协议关联的，机制本身协议无关
          ├─ 分支：ChatContentDetectorStage      → OpenAiContentDetector
          ├─ 分支：MessagesContentDetectorStage  → AnthropicContentDetector
          └─ 分支：ResponsesContentDetectorStage → ResponsesContentDetector

步骤 12 主干：自动重试                UpstreamAutoRetry.build(...) → Retry
        filter(UpstreamRetryPolicy::isRetryableFailure) + 指数退避 + 发 RETRYING 事件

步骤 13 主干：回程帧处理（仅流式）
          ① mapNotNull(ServerSentEvent::data) 拆信封
          ② 提取 usage 原始 JSON（三个 *UsageParser）
          ③ 形态归一（见下方支线）
          ④ reasoning fallback（见下方支线）
          ⑤ UpstreamEventClassifier.classify 分「载荷 / 终止标记」两态
          ⑥ doFinally 成功收尾落库
          ├─ 支线：chunk 形态归一（ChunkStageRegistry.findNormalizer，键 = upstreamProtocol）
          │    未命中语义：**跳过**
          │      normalize/  ChunkNormalizeStage → ChatChunkNormalizeStage → UpstreamChunkNormalizer
          └─ 支线：reasoning fallback（ChunkStageRegistry.findFallback，键 = upstreamProtocol）
               未命中语义：**跳过**
                fallback/   ReasoningFallbackStage → ChatReasoningFallbackStage → ReasoningFallback

步骤 14 主干：落库（每次上游往返各一条）  ApiCallLogService / ApiCallUsageService
        ★ 刀 2 起收归 UpstreamCallRunner 的 2 份静态方法（saveNonStreamLog / saveStreamLog[WithError]）
          上游协议统一从 ctx.upstreamProtocol() 取 —— 曾经的 9 份（两态 × 三协议）已塌成一份

步骤 15 插槽：响应翻译                ResponseProtocolTranslator（键 = downstream + upstream）
        未命中语义：**透传 + WARN**（半轮实现态是正常中间态）
        ★ 从 ctx.responseTranslator() 取（发送前块步骤 5 已选好），套在执行器**外侧**、
          因而在 retryWhen **之外** —— 判定与落库看的是上游原生形态
          └─ 分支 M2C：MessagesToChatResponseTranslator
               → MessagesToChatStreamTranslator / MessagesToChatNonStreamTranslator /
                 M2CStreamState / AnthropicUsageAccumulator / OpenAiResponseShapes / TranslatedChunkLog

步骤 16 主干：统一形态出口            UpstreamEvent（Body / Terminal 两态）

── 主干结束 ────────────────────────────────────────────────────────────────

步骤 17 出口：错误渲染与 SSE 收尾（api/ 的三个 Controller，按下游协议分岔）
        openAiErrorResponse / anthropicErrorResponse / responsesErrorResponse —— 三套骨架**刻意不同**
        StreamLifecycle.attach(...) 收尾协议（心跳 / 取消 / 终止 / 错误帧）
        UpstreamFailureClassifier 把异常归入 FailureKind
```

---

## 3. 与主干正交的轴

> 四轴总表见 §0。本层（主干-支干）之外的三个轴各在一处：
> **出口**在 `api/`（§3.3）· **控制面**在 `control/`（§3.1）· **观测**尚未归拢（§3.2）。

### 3.1 控制面（`control/`，不在本层）

「人可能在后台中途作用于在途请求」是**另一条轴**，与「数据怎么流」正交：

| 类 | 作用 | 与主干的关系 |
|---|---|---|
| `control/CallResendLoop` | 人工重发（后台「静默重试」）的递归循环 | 只服务**流式**；不消耗自动重试预算 |
| `control/CallRetryRegistry` | 重发信号注册表 | 入口是 HTTP 端点（`CallLifecycleController`） |
| `control/CallCancellationRegistry` | 取消信号注册表 | 同上，孪生兄弟 |
| `control/CallCanceledException` | 取消信号异常 | 控制器据此回 ABORTED |

> **为什么它们不算「重试的第三件」**：`UpstreamRetryPolicy`（要不要）+ `UpstreamAutoRetry`（几次/多久）
> 是**自动重试**的两件事；人工重发**不在请求流里**、**不消耗预算**、**只服务流式** ——
> 它只是碰巧也叫「重试」。

### 3.2 观测轴（**已收拢** —— 顶层包 `observability/`，第 7a-1 步）

「让**人**看见发生了什么」是**第四条轴**。其判据是**删掉它下游收到的不变，但后台瞎了** ——
`UpstreamCallReporter` / `CallLifecyclePublisher` / `ApiUsageCollector` / `LogEventPublisher`
全都满足它。此前它散在三处（`application/` · `infrastructure/web/` · 主干内两个薄适配器），
现已按**延迟**分四组归到顶层 `observability/`：

```
observability/
├─ port/       CallLifecycleNotifier（生命周期通知端口，DIP）
├─ publisher/  CallLifecyclePublisher · UsageEventPublisher · LogEventPublisher   ← 实时
├─ record/     ApiCallLogService · ApiCallUsageService · ApiUsageDailyService     ← 明细 + 聚合
└─ notify/     UpstreamCallReporter（零主干依赖，可搬）
```

| 归拢后住哪 | 是什么 | 延迟 |
|---|---|---|
| `publisher/` 三个 | 实时推送（Toast 相位 / 统计卡 / 日志列表） | **实时** |
| `record/ApiCallLogService` · `ApiCallUsageService` | 明细落库（`api_call_log` / `api_call_usage`），每次往返一行 | **明细** |
| `record/ApiUsageDailyService`（**新抽的端口**） | 日聚合（`api_usage_daily`，统计卡数据源） | **聚合** |
| `notify/UpstreamCallReporter` | 生命周期事件 / 调用记录信号的 best-effort 通知 | 实时 |
| `port/CallLifecycleNotifier` | 生命周期通知**端口**（DIP） | — |

> **`ProtocolNotifier` 留 `before/notify/`**（未搬）：它的归属有两条依据，
> **依赖方向那条是决定性的** —— 它 import `ProtocolDispatchDecision`（主干数据），
> 搬进 `observability/` 会让 `observability → pipeline`，**与既定方向（主干 → 观测）相反**。
> 对照：`UpstreamCallReporter` 零主干依赖（只 import 端口与事件类型），故可搬。
> 两者的差别正在于「有没有反向依赖主干的数据」。
>
> **实测兑现**：收拢后 `observability/` 对外只 import `protocol/` 与自身，
> 对 `application/` / `infrastructure/` / `api/` / `control/` **零反向依赖**。

> **落库的触发点仍在块 2**（`UpstreamCallRunner` 在 `doFinally` 里调 `saveStreamLog`，
> 三个执行器调 `apiCallUsage.save`）—— 观测轴只提供**能力**（端口），
> 因为只有块 2 知道「一次上游往返结束了、拿到了什么字节」。这是
> `主干 → 观测` 单向依赖的正常形态（同 `UpstreamCallReporter` 的「主干内薄适配器」）。
> **不要在归拢时把触发点也搬走** —— 观测轴无法得知那一刻。

> **待决策点已作废**（2026-09-26 实测）：`plan_.md` §4.8.8 第 8 节曾记
> 「`ApiCallLogService` / `ApiCallUsageService` 的搬迁要拆读写接口」，但两者 javadoc
> 已明写「仅暴露 provider 需要的写入能力；查询能力保留在 infrastructure 的 Repository 上」，
> 且读侧独立在 `CallLogQueryService` —— **读写早已分离**，故为纯写端口，直接搬。

### 3.3 出口轴（`api/`）

> **它不是第五轴 —— 它一直是第二轴**（§0 四轴表的第 2 行），判据是
> 「删掉它下游**收不到**东西」。本小节描述的是它的**内部拓扑**（阶段 6，2026-09-26）。

```
api/
├─ openai/  anthropic/     三个协议端点
├─ shared/                 出口自己的共享件（≠ 观测轴，≠ 控制面）
│     UpstreamFailureClassifier · UpstreamErrorRenderer · StreamLifecycle · UsageAccounting
├─ ollama/                 模型发现（不是出口）
└─ CallLifecycle / CallLog / UsageQuery / ProviderAdmin …  后台 API（不是出口）
```

**三个 Controller 按下游协议分岔**：三套错误 JSON 骨架、SSE 事件名回填策略、
`sse` 收尾协议均**刻意不同**。详见 `docs/REQUEST_PIPELINE.md`。

**一条判据**（归拢讨论的产物）：**出口可以知道「这些字节要包成什么 HTTP 形状」，
不该知道「这些字节在协议上是什么意思」**。

#### 3.3.1 反复出现的误判：`api/shared/` 不是观测轴

它看起来像 —— 因为出口代码里最显眼的行都在调观测（`publish(...)`）。但按判据测：

$$\text{删掉 } \texttt{api/shared/} \;\Rightarrow\; \text{下游}\textbf{什么都收不到}$$

而观测轴是「删掉它，下游收到的**完全不变**，但后台瞎了」。两者正相反。

实测三个类的对外依赖，恰好说明「**出口在使用另外三条轴**」：

| `api/shared/` 的类 | 它用谁 | 它自己是什么 |
|---|---|---|
| `UpstreamFailureClassifier` | `pipeline.protocol`（4 个异常）· `application.runtime` | **出口**的共享件 |
| `UpstreamErrorRenderer` | 同类（只因分类结果而依赖） | **出口**的共享件 |
| `UsageAccounting` | `observability.record`（端口）· `pipeline.protocol` · `protocol.usage` | **出口**的共享件 |
| `StreamLifecycle` | **`control`**（取消注册表）· `observability.publisher` · `protocol.lifecycle` | **出口**的共享件 |

> 与 7a-1 同源：**轴 = 代码住在哪，≠ 这段代码在跟谁说话**。
> 观测轴的实现全在 `observability/`；`api/shared/` 只是**在调用它**。

#### 3.3.2 什么该共用、什么必须各备

| 层 | 谁负责 | 为何 |
|---|---|---|
| **分类**（这是什么失败） | `UpstreamFailureClassifier` | 三条共用；分岔会得到不同结论 |
| **状态码 + 日志** | `UpstreamErrorRenderer` | 三条共用；状态码表达「用户该去改什么」，与下游协议无关 |
| **body 形状** | 各端点的 `ErrorBodies` | **协议决定**：Chat 分两档 `type`，Anthropic 多一层，Responses 流式扁平 |
| **usage 记账** | `UsageAccounting` | 三条共用；两档语义**刻意不同**（见 §4.2） |
| **SSE 收尾协议** | `StreamLifecycle` | 三条共用；协议差异由回调表达 |
| **非流式收尾** | `NonStreamLifecycle`（步 2 新增） | 三条共用；**回调只有一个**（错误响应），因为非流式没有 Layer 1 —— 终态恒为 `COMPLETED` |

**两个 Lifecycle 的差异是两态本质差异，不要抹平**：取消检测（`takeUntilOther`+标志 vs
`firstWithSignal`+异常）、产物类型（`Flux<SSE>` vs `Mono<ResponseEntity<?>>`）、
状态码能否改、有无心跳。六合一会把这些压成标志位，读的人看不出走的是哪一支。

**必须保留的差异**（不得抹平）：Responses 流式错误体**不能**复用非流式骨架 ——
顶层无 `type` 时事件状态机无法分派，症状是**流挂住、界面转圈**而非报错。

### 3.4 两态轴（流式 / 非流式）

**主线**：非流式 = 「恰有一个元素的流」，因此主干只有一种输入形态（`UpstreamEvent`）。
真正的两态分岔只剩两处**真本质**：一次取全 vs 逐事件、回程翻译收 `Mono` vs `Flux`。

---

## 4. 已知的、**刻意保留**的不对称（不要「顺手统一」）

| 项 | 为什么保留 |
|---|---|
| 三个 `*ContentDetector` 的**方法名骗人** | `OpenAiContentDetector` 里叫 `hasMeaningfulPayload` 的**其实是流式用的**（读 `delta`）；非流式那个叫 `hasMeaningfulNonStreamPayload`（读 `message`）。`ChatContentDetectorStage` 已按**取值路径**接好 —— 不要按名字改 |
| 各注册表的**未命中语义不同** | `before/requestbody/` `after/chunk/` → **跳过**（「该协议没这一步」是领域事实）；`after/content/` `after/send/` `before/outbound/` → **报错**（装配坏了，不是领域事实）。**不要统一** |
| 两个插槽的**未命中语义不同** | 去程 → 报错；回程 → 透传 + WARN（半轮态是开发中间态） |
| `after/content/` `before/outbound/` `after/send/` **不套步骤层子包** | 接入点只有一步时，接入点即步骤。加一层求「深度整齐」会重复一次含义（三个的**协议实现**分组方式不同，见 §1 读法 4） |
| `EmptyResponseGate` 住在 `after/attempt/` 而非 `after/content/` | 它协议无关（三条线路共用同一套扣放机制），只有**检测器**因协议而异。`attempt/` 持机制 + 契约、`content/` 供各协议检测器实现，是「主干持契约、支线供实现」的直接体现 |
| 两个执行器**不继承** `AbstractUpstreamChatService` | 强行抽公共父类会退化成一堆钩子（子类看不见自己依赖什么）。**抽特征，不抽骨架** |
| 落库 **9→2 已合并**（阶段 4 刀 2） | 曾按「两态 × 三协议」写 9 份；刀 2 收归 `UpstreamCallRunner` 两份静态方法，上游协议从 `ctx.upstreamProtocol()` 取。`api_call_log` 两列各自两型不变（未压平），两列取值回归全绿 |

---

## 4.1 已知的**结构偏离**（待修，不是「刻意保留」）

上表是*刻意*的不对称；本节是*待修*的偏离 —— 区别在于前者不该动，后者该动。
**2026-09-25：阶段 4 三刀（含 B）与阶段 5 包结构归拢（前 5 步）均已落地** ——
主干后半段归位、请求体与出站装配上移、编排块化、`upstream/` 顶层包消失。
下表仅剩两条：一条是**已降级的可选清理**，一条是**归拢带出的新发现**。

| 偏离 | 表征 | 矫正 |
|---|---|---|
| **send 插槽吞掉主干后半段** ✅刀1/2/3 | 步骤（协议无关的 body 装配 / 头与地址装配 / 兜底 / 重试 / 落库）曾在三执行器各写一份 | 刀 1 上移 body；刀 2 内层+重试+落库收归 `UpstreamCallRunner`、流级态入 `AttemptContext`；刀 3 B 上移出站头/地址。**编排与装配已全回主干侧** |
| **思考注入只插槽化了 1/3** ✅刀1 | `thinking/` 曾只有 `MessagesThinkingStage`；CHAT / RESPONSES 的注入在各自 `applyReasoningEffort` 里 | 刀 1 已把 thinking 步骤对齐（详见刀 1 落地记录） |
| **步骤有第三种扩展机制** ✅刀1 | Chat 曾走 `customizeRequestBody` 虚方法钩子、Messages 走查表、Responses 不接 | 刀 1 统一到主干显式阶段序列 |
| **ctx 统一原则在插槽内没执行** ✅刀1/2 | 曾 `prepareRequestBody` 参数穿线、Chat/Responses 读不到 `ctx.bodyProtocol()` | 刀 1/2 随步骤上移消解；runner 与 `AttemptContext` 均以 ctx 为准 |
| **回程中段仍在执行器闭包** ⏳可选 | 帧归一 / reasoning fallback / usage 解析（协议特有）留在执行器 `postLoop` 闭包 —— 这是**协议特有**的正当归属，不是偏差；仅其「消费者只有 Chat 执行器」一点与主干化取向不一致 | 契约收缩（`UpstreamExecutor` → transport）可分离协议特有中段与传输，**已降级为可选清理**（块化已拿走主要收益） |
| **`buildWebClient` 三份** ✅刀3 B | 三执行器各一份，曾差异为 baseUrl 来源 + `anthropic-version` 头 | 刀 3 B 把那些差异归进 `before/outbound/` 支线，三份现**逐字同形**（只差方法名/可见性）；合并是随时可做的纯清理 |
| **观测轴散在三处** ✅步7a-1 | `application/lifecycle` · `infrastructure/web/` · 主干内两个薄适配器 | 已收拢到顶层 `observability/{port,publisher,record,notify}/`；依赖方向实测单向（见 §3.2） |
| **usage 被解析两遍** ✅步7b-1 | 发送块解析一次（写 `api_call_usage`）；`api/` 三个 Controller 又解析一次（写 `api_usage_daily` + 推前端）。且 `ResponsesController.recordStreamUsage` 里复制了一份协议语义（「最后一份非 null 胜出，与 Anthropic 需跨事件 merge 不同」） | **不是「搬位置」而是「消重复」**：解析收到生产者，结果挂在 `UpstreamEvent.usage()` 上；出口只做一条**与协议无关**的「取最后一份非 null」。详见下方 §4.2 |

### 4.2 usage 槽：跨块交出协议解析结果（步 7b-1，✅ 已完成）

**问题**：同一份上游字节被解析两遍，后一遍在**出口** —— 而出口本该只知道
「这些字节要包成什么 HTTP 形状」，不该知道「这些字节在协议上是什么意思」（§3.3）。

**修法**：槽开在 `UpstreamEvent` 上，而不是 `ctx` 上 ——

| 为何不是 `ctx` | 说明 |
|---|---|
| `ctx` 从不返回给出口 | 它由 `entry/` 的三个 Service 在 `defer` 内创建，出口只拿到 `Flux<UpstreamEvent>` |
| 出口记账发生在**流内** | Layer 1 见到终止标记**那一刻**就 finalize（不等 TCP 关闭）——那时出口手里只有那一帧 |

> `ctx` 的跨块手法（`applyTranslators` / `applyOutbound`）只适用于**发送前块内部**：
> 那时 ctx 还在调用栈上、尚未发出 I/O。**数据开始流动之后，唯一能承载跨层信息的就是流里的元素。**

**语义**：生产者（三执行器 / 翻译器）挂「**到目前为止的累积值**」到每帧上；
出口的消费规则是「**取最后一份非 null**」—— 这条规则**与协议无关**。

- 只在尾帧挂会退步：上游不发终止标记就断连时靠 Layer 2 兜底，那需要**流内已见过的值**；
- 三种协议的累积差异（Chat / Responses 后到覆盖、Anthropic 只有正数才覆盖、
  C2M 由上游侧算好）**全被生产者吸收**，出口一行协议分支都不剩 —— 这正是消重复的判据。

**有意的行为变更**（仅一处）：C2M 且下游未请求 `include_usage` 时，
翻译器不发 usage 帧 → 旧出口解析不到 → 记 `0,0`；现在记真实值。
这是**修正**：`api_call_usage`（明细）本就记真实值，两张表口径因此统一。
调用**次数**不变（那是出口「恰记一次」的不变式，本步不动）。

**未动的东西**：出口的 `completed` CAS + `canceled` 守卫 + Layer 1/Layer 2 两层判定
全部原地保留 —— 那是出口独有状态。若改为主干直接记账（曾评估的「甲案」），
就要把这四个条件复制到主干，**那是换个地方重复，不是消重复**。

**7b-2 的收尾**（✅ 2026-09-26）：出口剩下的「读槽 → 记账」三份逐字相同，
已收归 `api/shared/UsageAccounting`（与 `StreamLifecycle` / `UpstreamFailureClassifier` 同址）。
两档记账语义**刻意不同**，不要统一：

| 路径 | 无 usage 时 | 为何如此 |
|---|---|---|
| **流式** `recordStream` | **记 `0,0`** | 它同时是「本次调用发生过」的计数，跳过会让统计卡的**调用次数**少算 |
| **非流式** `recordNonStream` | **不记** | 响应体里真的没有 usage 就没有可记的量 |

> 累积规则还有第三档容易漏：**`EMPTY` 也不覆盖**。上游可能给出本服务不认识的
> usage 字段名（解析为 `EMPTY`），收下它会把先前的真实值刷成 `0` ——
> 这与重构前三条线路的 `if (!tokens.isEmpty())` 逐字一致。

**钉住它的测试**：`api/openai/OpenAiControllerUsageSlotTests`（6 条，出口侧）
与 `api/shared/UsageAccountingTests`（9 条，两档边界）。
前者的 `doesNotParseDataWhenSlotAbsent` 是**反直觉**的关键断言 ——
让 data 里带着合法 usage（999/888）却不挂槽，期望出口**忽略**它：
若有人偷偷把解析搬回出口，这条会失败。

---

## 5. 改动本层时的纪律

1. **纯移动 / 纯改名**要单独提交 —— 混入行为变更就没法验证「零行为变更」。
   阶段 5 包结构归拢（七步）就是照这条走的：每步一个提交、每步 `1309` 全绿。
2. **改了包名必须删 `target\classes` / `target\test-classes` 再编译**（增量编译会**假绿**）。
   **不要删整个 `target/`** —— VS Code 的 Java 语言服务同时在里面写，会争抢、
   导致 `could not create parent directories`；真坏了就**重载 VS Code 窗口**。
3. **移动包后必须检查 `.github/instructions/*.md` 的 `applyTo` glob** ——
   它们是「路径即契约」的配置，移动是它们失效的**唯一原因**，而失效时**完全静默**。
   **已实例印证**（阶段 5 第 5 步）：`ollama-api.instructions.md` 的 `**/upstream/discovery/**`
   随 `discovery/` 搬走而失效，已改 `**/application/discovery/**`。
4. **注意「测试数变少」** —— 那是文件丢失的可靠信号（比任何断言都早），
   常见原因是 `git mv` 的目标目录不存在而**静默失败**。
5. **全量验证**：`.\mvnw.cmd compiler:compile compiler:testCompile surefire:test`，基线 **1333**
   （阶段 5 步 7a-1 前为 1309；步 7b-1 +6 `OpenAiControllerUsageSlotTests`，
   步 7b-2 +9 `UsageAccountingTests`，阶段 6 步 1 +9 `UpstreamErrorRendererTests`）。

### 5.1 搬包实操的三条细则（阶段 5 实测，后续搬包直接复用）

1. **拆包会新增「同包 → 跨包」引用**：把同一个包里的类分散到多个子包后，
   原本**无 import 的同包引用要补 import**（整包搬移不会触发，自包含子树也不会）。
   **只按编译报错补，不要猜** —— 报错点是精确清单。
2. **全库替换要「最长前缀优先 + 类名精确锚定」，且绝不留宽泛兜底**：
   替换串**必须带 `.` 前缀**（仓库里有 `ProtocolDispatchManagerTests` 这类同名前缀测试类）；
   先处理具体子包（`send.*` / `chunk.*` / `content.*`），层根类**按类名逐个锚定**，
   最后**不要留下无差别的 `upstream.` → 兜底规则** —— 它会把已搬进公共层的文件的
   `package` 声明误改（阶段 5 第 4 步踩过，第 5 步去掉兜底后残留直接为 0）。
3. **`test-compile` 报 SUCCESS 之后仍可能假绿**：VS Code 的 Java 语言服务用 ecj
   编译出带 error marker 的 `.class`，Maven 见时间戳较新就跳过重编 ——
   症状是「编译成功但测试报 `Unresolved compilation problem`」。
   搬包后用 **`mvnw clean test`** 或至少复跑一次 `mvnw test` 确认。

   > ⚠️ **`clean test` 可能偶发失败，那不是回归**：`clean` 删掉 `target/test-admin.db` 后，
   > 首次启动的 schema 迁移与并发测试存在竞态（报 `no such table: app_config` / `api_call_log`）。
   > **复跑即绿**（阶段 5 第 4 步实测：第一次失败、后两次连续 1309 全绿）。
4. **`package` 声明与 import 要分两步改，别指望一次替换全覆盖**（第 7a-1 步实测）：
   「按 FQN 精确锚定」的替换**不包含**搬移文件自己的 `package` 行（那里只有包名、无类名）。
   搬完必须**单独扫一遍搬移文件的首行**确认。本次 9 个 main + 4 个 test 共 13 处都是这样补的。
5. **先建目标目录再 `git mv`**（第 7a-1 步实测）：`git mv` 对**不存在的目标目录**直接报
   `fatal: renaming ... failed: No such file or directory`，**且不会自动建目录**。
   本次 `src/test/.../protocol/` 未预先创建，四个文件里三个成功、一个失败 ——
   而**文件数守恒检查会立即暴露它**（test 110 ≠ 111）。
   > 这正是「测试数变少 = 文件丢失的可靠信号」那条纪律的又一次印证。
6. **拆包后要扫的不是「报错文件」而是「所有引用方」**（第 7a-1 步实测）：
   本次 `ApiUsageCollector`（`infrastructure/web/`）引用同包的 `UsageEventPublisher`、
   `UpstreamAutoRetry`（`pipeline/after/attempt/`）引用同包的 `UpstreamCallReporter` ——
   两者都**原本无 import**，拆包后必须补。编译报错能找出来，但**第一轮只会报第一批**
   （test 侧要有 `test-compile` 才暴露）；搬包后应直接把 `mvnw test` 跑到位，别只看 `compile`。

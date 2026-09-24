# `upstream/` —— 上游执行层

> **本文件入库**（`.gitignore` 的 `/*.md` 只忽略**根目录**，且 `!README.md` 全局豁免）。
> 因此它是这批结构里**唯一能跟着 `git pull` 到达另一台设备**的说明 ——
> `plan_.md` / `AGENTS.md` / `REFACTOR_HANDOFF.md` 都在仓库根、都不入库，靠人带。
> **跨设备协作时以本文件为准。**

---

## 0. 形态：主干 / 支线 / 出口

本项目自造的形态名：**SESE Pipeline with Joining Branches**（带汇回支线的单入口单出口管道）。

```
                    ┌─ 支线 ─┐
主干 ── 分岔点 ─────┤        ├──── 汇回 ── 主干继续 ──→ 出口（按下游协议分岔，不汇回）
                    └─ 支线 ─┘
```

| 概念 | 判据 | 本层里的例子 |
|---|---|---|
| **主干** | 协议差异是**数据** | `UpstreamEvent`、`UpstreamRetryPolicy`、`EmptyResponseGate`、`UpstreamCallReporter` |
| **支线** | 协议差异是**代码**，且**有进有出、同类型进出** | `content/`、`chunk/`、`requestbody/`、`send/` 四个接入点 |
| **出口** | **只有出没有回** | 不在这层（在 `api/` 的三个 Controller 里，按下游协议分岔） |

**最关键的一条约束**：支线**必须汇回**主干。因此它只能同类型进出，契约被压得很窄
（不能改下游协议 / 重试语义 / 落库形态）—— **扩展因此才安全**。

顺带一句与 `application/` 的分工：`upstream/` 只回答「**一次请求的数据怎么流**」。
「人可能在后台中途插手」（取消 / 人工重发）是**另一条轴**，在 `control/`。

---

## 1. 目录树与它的读法

```
upstream/                        ← 层根 = 主干（协议无关的共用件，扁平住这里）
├── UpstreamEvent                统一形态（Body / Terminal）；非流式 = 恰有一个元素的流
├── UpstreamEventClassifier      把一帧分成「载荷」与「终止标记」两态
├── UpstreamRetryPolicy          自动重试①：要不要重试（纯判定）+ 两个异常解包
├── UpstreamAutoRetry            自动重试②：几次、多久（读配置 + 组装 Retry）
├── UpstreamCallReporter         生命周期事件 / 调用记录信号的 best-effort 通知
├── EmptyResponseGate            空响应拦截机制（流式扣放 / 非流式一次判 / 耗尽放行）
├── ChunkLogPayload              落库的 chunk 载荷（含翻译改写）
├── EmptyUpstreamResponseException  空响应信号异常（复用重试预算）
│
├── send/                        ★ 接入点：发送（唯一一步）
│   ├── UpstreamExecutor           契约：protocol() + invoke / invokeStream
│   ├── UpstreamExecutorRegistry   查表（键 = 上游协议；**未命中即报错**）
│   ├── UpstreamCallRunner         外层编排骨架（**无状态静态**，刀 2 起）：主干后半段的家 ——
│   │                              defer/gate/retryWhen/耗尽放行/resendloop/doFinally + 落库 2 份
│   ├── AttemptContext             协议无关的流级状态容器（刀 2 起）：计时 / logChunks /
│   │                              respHeaders / statusCode / ttfb / emptyResponsePassthrough
│   ├── chat/       AbstractUpstreamChatService + GenericOpenAiChatService + OpenAiUsageParser
│   ├── messages/   GenericAnthropicChatService + AnthropicUsageParser
│   └── responses/  GenericResponsesChatService + ResponsesUsageParser + ResponsesStreamEvents
│
├── requestbody/                 ★ 接入点：请求体装配（含 3 步）
│   ├── RequestBodyStageRegistry   接入点级查表（键 = bodyProtocol；未命中**跳过**）
│   ├── system/      SystemPromptNormalizeStage + SystemPromptNormalizer + MessagesSystemPromptStage
│   ├── maxtokens/   MaxTokensNormalizeStage + MaxTokensNormalizer + MessagesMaxTokensStage
│   └── thinking/    ThinkingInjectStage + AnthropicThinkingNormalizer + MessagesThinkingStage
│
├── chunk/                       ★ 接入点：回程帧处理（含 2 步）
│   ├── ChunkStageRegistry         接入点级查表（键 = upstreamProtocol；未命中**跳过**）
│   ├── normalize/   ChunkNormalizeStage + UpstreamChunkNormalizer + ChatChunkNormalizeStage
│   └── fallback/    ReasoningFallbackStage + ReasoningFallback + ChatReasoningFallbackStage
│
├── content/                     ★ 接入点 = 步骤：空响应判定（唯一一步）
│   ├── ContentDetectorStage       契约
│   ├── ContentDetectorRegistry    查表（键 = upstreamProtocol；**未命中即报错**）
│   └── OpenAi / Anthropic / ResponsesContentDetector + 三个 *ContentDetectorStage
│
└── discovery/                   GenericDiscoveryService（模型发现链路，**不在聊天链路上**）
```

### 三条读法（为什么长这样）

1. **层根即主干** —— 协议无关的共用件**直接住在 `upstream/` 下**，不另设 `core/`。
   支线**依附**主干，因此用**嵌套**（接入点是子包）表达依附，而不是靠命名声明同级。
2. **接入点作子包** —— 子包名 = 这条支线**挂在主干的哪个接入点**：
   `send`（发送）/ `requestbody`（请求体装配）/ `chunk`（回程帧）/ `content`（空响应判定）。
3. **步骤自成子包** —— 接入点下每个**步骤**一个子包，内含「1 个契约接口 + 各协议的实现 + 包装」。
   **接入点只有一步时，接入点即步骤** —— 所以 `content/` 与 `send/` **自己就是步骤**，
   不再套一层。这个不规则是**事实的不规则**，不是命名不一致。

> **为什么一定要按步骤拆**：`system` / `max_tokens` / `thinking` 三步确实都围绕 body，
> 但那只是**位置共性**；它们在 `RequestBodyStageRegistry` 里是**三张独立表、三个独立查表键**，
> 即**三个独立步骤**。按共性合并会用一个共性抹掉三个语义单位。

### 扩展语义（加东西时加在哪）

| 你要做的事 | 落点 | 代价 |
|---|---|---|
| 加一个**上游协议** | 每个**步骤的包**里加一个实现类（`@Component`）；`send/` 下加一个执行器 | 动 N 个包 —— 但那正是领域事实：**每个步骤都得能为新协议表态** |
| 加一个**步骤** | 加一个子包（契约 + 各协议实现 + 在接入点注册表的表里加一项） | 主干一个字不动 |
| 加一个**接入点** | 加一个子包 + 一个接入点级注册表 | 主干加一个调用点 |

> ⚠️ **上表的「加一个 `@Component` 即被查表命中」目前只对 `content/` 与 `send/` 成立。**
> `requestbody/` 与 `chunk/` 两个注册表当前**各只有一个消费者，且消费者的查表键是常量**：
> `RequestBodyStageRegistry` 只被 `GenericAnthropicChatService` 调用（键恒为 `MESSAGES`），
> `ChunkStageRegistry` 只被 Chat 执行器调用（键恒为 `CHAT`）。因此给 `MESSAGES` 补一个
> `ChunkNormalizeStage` 实现**不会被调用** —— 调用点在别的执行器里、根本不查这张表。
> 这不是注册表的缺陷，而是 **send 插槽边界画错**的表征（见 `plan_.md` 阶段 4）：
> 请求体装配与回程处理本应是主干步骤、对所有协议统一调用，现在却各自锁在执行器内部。
>
> **2026-09-24 进度**：刀 2 把**编排**（gate/retry/落库/defer）收归 `UpstreamCallRunner` 后，
> 回程帧的**归一/fallback 中段仍留执行器闭包**（`postLoop`，协议特有），`RequestBodyStageRegistry`
> 的调用点也未变。因此**这两条支线的「可扩展」仍是名义上的** —— 要真正统一到主干、对所有协议
> 一致查表，须待**刀 3 契约收缩**把中段的协议特有部分与传输部分彻底分离。

---

## 2. 按执行顺序的步骤树

> ⚠️ **本节编号只供阅读，不是代码里的引用坐标。** 步骤号是**全局位置**，插入一步会让后面全部错位
> （本项目已有同类教训：plan 里记过的「行号会漂」）。**类注释里写的是步骤的「基名」（如
> `空响应判定`）而非编号**。引用某一步时，请写**基名**或**类名**。

> ⚠️ **步骤 11–17 的归位进度（2026-09-24）**：曾经这批协议无关的步骤**全在 send 插槽内部**执行
> （三执行器各持私有管道、控制流方向反了）。**阶段 4 刀 1/2 已矫正**：
> - **刀 1 ✅** 请求体 + 出站头装配（步骤 11–12）上移主干；
> - **刀 2 ✅** 内层 gate 编排 / 帧处理 / 落库 / `retryWhen`（步骤 13–17）收归
>   `send/UpstreamCallRunner`（**无状态静态编排件**），流级状态入 `send/AttemptContext`。
>
> 现在的形态是「主干（经 runner 编排）持流程，执行器只填传输 + 协议特有中段」。
> 下文步骤前缀 `主干：执行器内部` 是**刀 3 之前的过渡措辞** —— 编排已不在执行器里，
> 但契约收缩（`UpstreamExecutor` → transport）留待刀 3，故执行器仍是这些步骤的**装配入口**。
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
        主干唯一入口，流式与非流式共用；内部只读一次 ctx.stream() 用于「选机制」

步骤 4  主干：前奏（路由 + 调度）      RequestPipeline.run(...)
          ├─ ProviderRouteResolver.resolve(model)     解析供应商 + 剥 [provider-key] 前缀
          ├─ ProtocolDispatchManager.dispatch(...)    决定「直连还是翻译 + 上游协议」
          └─ ProtocolNotifier.notifyProtocols(...)    把 (下游, 上游) 写进生命周期事件
        产出：PipelinePreamble（route + decision）

步骤 5  主干：ctx 回填路由            RequestPipelineContext.applyRouting(...)

步骤 6  插槽：请求翻译                RequestProtocolTranslator（键 = downstream + upstream）
        未命中语义：**报错**
          ├─ 分支 C2M（下游 CHAT、上游 MESSAGES，已实现）
          │    ChatToMessagesRequestTranslator → MessageTranslator / ToolTranslator /
          │    ContentBlockTranslator / ToolPairingNormalizer / StopReasonMapper
          └─ 分支 其余方向 → 未实现，抛 ProtocolTranslationNotSupportedException

步骤 7  主干：ctx 回填翻译结论        RequestPipelineContext.applyTranslation(...)
        ★ 仅跨协议时执行（直连跳过）

步骤 8  主干：登记去程/回程            RequestPipelineContext.markCompleted(PipelineStep)
        作用：shouldApplyEmptyResponseGate() 读它 —— 半轮实现态跳过空响应拦截

步骤 9  主干：选执行器                UpstreamExecutorRegistry.require(ctx.upstreamProtocol())
        未命中语义：**报错**（装配坏了，不是领域事实）

步骤 10 插槽：发送                    UpstreamExecutor
          invoke(ctx, chunkRewriter)        → Mono<UpstreamEvent>（一次取全）
          invokeStream(ctx, chunkRewriter)  → Flux<UpstreamEvent>（逐事件）
        ★ 主干按 ctx.stream() 选这两个方法之一 —— 两态分岔的第一处
          ├─ 分支：GenericOpenAiChatService（上游 CHAT）
          ├─ 分支：GenericAnthropicChatService（上游 MESSAGES）
          └─ 分支：GenericResponsesChatService（上游 RESPONSES）

步骤 11 主干：执行器内部 —— 请求体装配（显式阶段序列 prepareRequestBody）
          ① copyRequestBody         浅拷贝，不污染调用方
          ② resolveModel            剥前缀
          ③ writeProtocolFields     写 model + stream
          ④ applyReasoningEffort    思考深度四档
          ⑤ ★ 协议特定步骤（见下方支线）★
          ⑥ removeNullFields        **必须最后**
          ├─ 支线：请求体协议特定步骤（RequestBodyStageRegistry，键 = bodyProtocol）
          │    未命中语义：**跳过**（「该协议没这一步」是领域事实）
          │      system/      SystemPromptNormalizeStage    → MessagesSystemPromptStage
          │      maxtokens/   MaxTokensNormalizeStage       → MessagesMaxTokensStage
          │      thinking/    ThinkingInjectStage           → MessagesThinkingStage
          └─ 分支：请求体规则 RequestBodyRuleEngine.transform（按协议筛组）

步骤 12 主干：执行器内部 —— 出站头装配（三层，后者覆盖前者）
          ① 透传下游头（除 hop-by-hop / Host / Content-Length）
          ② 按供应商级配置装配鉴权头（AuthHeaderSetting）—— 先删两个再注一个
          ③ 应用数据库请求头规则（{apiKey} 占位、/del/ 删除）—— **拥有最终决定权**
        ★ 协议不参与这个决定（出站头是供应商级事实）

步骤 13 主干：执行器内部 —— 上游往返（一次）
          ① Flux.defer 每轮起点重置（计时 / chunk 收集 / gate.reset）
          ② HTTP 往返（流式 exchangeToFlux；非流式 retrieve().toEntity）
          ③ 错误响应分支 → 即时落库 + Flux.error(WebClientResponseException)
          ④ 发 CONNECTED 生命周期事件

步骤 14 支线：空响应拦截
        机制（主干）：EmptyResponseGate —— 流式扣放 / 非流式一次判 / 耗尽放行
        契约（支线）：ContentDetectorStage，经 ContentDetectorRegistry.require(upstreamProtocol) 查表
        未命中语义：**报错**（每协议都必须能判空，判不了 = 空响应兜底对那条线路失效）
        ★ 只有**检测器**是协议关联的，机制本身协议无关
          ├─ 分支：ChatContentDetectorStage      → OpenAiContentDetector
          ├─ 分支：MessagesContentDetectorStage  → AnthropicContentDetector
          └─ 分支：ResponsesContentDetectorStage → ResponsesContentDetector

步骤 15 主干：自动重试                UpstreamAutoRetry.build(...) → Retry
        filter(UpstreamRetryPolicy::isRetryableFailure) + 指数退避 + 发 RETRYING 事件

步骤 16 主干：回程帧处理（仅流式）
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

步骤 17 主干：落库（每次上游往返各一条）  ApiCallLogService / ApiCallUsageService
        ★ 刀 2 起收归 UpstreamCallRunner 的 2 份静态方法（saveNonStreamLog / saveStreamLog[WithError]）
          上游协议统一从 ctx.upstreamProtocol() 取 —— 曾经的 9 份（两态 × 三协议）已塌成一份

步骤 18 插槽：响应翻译                ResponseProtocolTranslator（键 = downstream + upstream）
        未命中语义：**透传 + WARN**（半轮实现态是正常中间态）
        ★ 套在执行器**外侧**，因而在 retryWhen **之外** —— 判定与落库看的是上游原生形态
          └─ 分支 M2C：MessagesToChatResponseTranslator
               → MessagesToChatStreamTranslator / MessagesToChatNonStreamTranslator /
                 M2CStreamState / AnthropicUsageAccumulator / OpenAiResponseShapes / TranslatedChunkLog

步骤 19 主干：统一形态出口            UpstreamEvent（Body / Terminal 两态）

── 主干结束 ────────────────────────────────────────────────────────────────

步骤 20 出口：错误渲染与 SSE 收尾（api/ 的三个 Controller，按下游协议分岔）
        openAiErrorResponse / anthropicErrorResponse / responsesErrorResponse —— 三套骨架**刻意不同**
        StreamLifecycle.attach(...) 收尾协议（心跳 / 取消 / 终止 / 错误帧）
        UpstreamFailureClassifier 把异常归入 FailureKind
```

---

## 3. 与主干正交的两条轴

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

### 3.2 两态轴（流式 / 非流式）

**主线**：非流式 = 「恰有一个元素的流」，因此主干只有一种输入形态（`UpstreamEvent`）。
真正的两态分岔只剩两处**真本质**：一次取全 vs 逐事件、回程翻译收 `Mono` vs `Flux`。

---

## 4. 已知的、**刻意保留**的不对称（不要「顺手统一」）

| 项 | 为什么保留 |
|---|---|
| 三个 `*ContentDetector` 的**方法名骗人** | `OpenAiContentDetector` 里叫 `hasMeaningfulPayload` 的**其实是流式用的**（读 `delta`）；非流式那个叫 `hasMeaningfulNonStreamPayload`（读 `message`）。`ChatContentDetectorStage` 已按**取值路径**接好 —— 不要按名字改 |
| 三个注册表的**未命中语义不同** | `requestbody/` `chunk/` → **跳过**（「该协议没这一步」是领域事实）；`content/` `send/` → **报错**（装配坏了）。**不要统一** |
| 两个插槽的**未命中语义不同** | 去程 → 报错；回程 → 透传 + WARN（半轮态是开发中间态） |
| `content/` 与 `send/` **不套子包** | 接入点只有一步时，接入点即步骤。加一层求「深度整齐」会重复一次含义 |
| `EmptyResponseGate` 住在**层根**而非 `content/` | 它协议无关（三条线路共用同一套扣放机制），只有**检测器**因协议而异。层根持机制 + 契约、`content/` 供各协议检测器实现，是「主干持契约、支线供实现」的直接体现 |
| 两个执行器**不继承** `AbstractUpstreamChatService` | 强行抽公共父类会退化成一堆钩子（子类看不见自己依赖什么）。**抽特征，不抽骨架** |
| 落库 **9→2 已合并**（阶段 4 刀 2） | 曾按「两态 × 三协议」写 9 份；刀 2 收归 `UpstreamCallRunner` 两份静态方法，上游协议从 `ctx.upstreamProtocol()` 取。`api_call_log` 两列各自两型不变（未压平），两列取值回归全绿 |

---

## 4.1 已知的**结构偏离**（阶段 4 矫正中，不是「刻意保留」）

上表是*刻意*的不对称；本节是*待修*的偏离 —— 区别在于前者不该动，后者该动。
**2026-09-24：刀 1/2 已矫正主干后半段归位与请求体上移，刀 3 收尾契约收缩。**

| 偏离 | 表征 | 矫正 |
|---|---|---|
| **send 插槽吞掉主干后半段** ✅刀1/2 | 步骤 11–17（协议无关的 body 装配 / 头装配 / 兜底 / 重试 / 落库）曾在三执行器各写一份 | 刀 1 上移 body/头（步骤 11–12）；刀 2 内层+重试+落库收归 `UpstreamCallRunner`、流级态入 `AttemptContext`。**编排已回主干侧** |
| **思考注入只插槽化了 1/3** ✅刀1 | `thinking/` 曾只有 `MessagesThinkingStage`；CHAT / RESPONSES 的注入在各自 `applyReasoningEffort` 里 | 刀 1 已把 thinking 步骤对齐（详见刀 1 落地记录） |
| **步骤 11 有第三种扩展机制** ✅刀1 | Chat 曾走 `customizeRequestBody` 虚方法钩子、Messages 走查表、Responses 不接 | 刀 1 统一到主干显式阶段序列 |
| **ctx 统一原则在插槽内没执行** ✅刀1/2 | 曾 `prepareRequestBody` 参数穿线、Chat/Responses 读不到 `ctx.bodyProtocol()` | 刀 1/2 随步骤上移消解；runner 与 `AttemptContext` 均以 ctx 为准 |
| **回程中段仍在执行器闭包** ⏳刀3 | 帧归一 / reasoning fallback / usage 解析（协议特有）留在执行器 `postLoop` 闭包；`RequestBodyStageRegistry` 调用点键仍为常量 | 刀 3 契约收缩：`UpstreamExecutor` → transport，分离协议特有中段与传输 |
| **`buildWebClient` 三份** ⏳刀3 | 三执行器各一份，差异仅 baseUrl 来源 + `anthropic-version` 头 | 刀 3 归并为按协议取端点的单一构造 |

---

## 5. 改动本层时的纪律

1. **纯移动 / 纯改名**要单独提交 —— 混入行为变更就没法验证「零行为变更」。
2. **改了包名必须删 `target\classes` / `target\test-classes` 再编译**（增量编译会**假绿**）。
   **不要删整个 `target/`** —— VS Code 的 Java 语言服务同时在里面写，会争抢、
   导致 `could not create parent directories`；真坏了就**重载 VS Code 窗口**。
3. **移动包后必须检查 `.github/instructions/*.md` 的 `applyTo` glob** ——
   它们是「路径即契约」的配置，移动是它们失效的**唯一原因**，而失效时**完全静默**。
4. **注意「测试数变少」** —— 那是文件丢失的可靠信号（比任何断言都早），
   常见原因是 `git mv` 的目标目录不存在而**静默失败**。
5. **全量验证**：`.\mvnw.cmd compiler:compile compiler:testCompile surefire:test`，基线 **1297**。

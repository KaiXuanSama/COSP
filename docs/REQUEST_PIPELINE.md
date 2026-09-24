# 下游请求处理链路

> 本文档描述一次下游请求从进入 COSP 到发往上游之间经历的处理层，重点是
> **应用服务层**（决定发给谁、用什么协议）与**上游执行层**（决定请求长什么样、怎么发）
> 的分工与顺序。
>
> 本文描述的是**现状**。**§0 的「三个平行主干」已经过时**：阶段 3.4 已把三个应用服务收成
> 「建 ctx + 交主干」，主干（`RequestPipeline`）唯一。下文 §1/§3 已同步为现状；
> 形态名与判据见 §0（那是术语的最小留存，与实现状态无关）。

## 0. 形态名：SESE Pipeline with Joining Branches

本服务的请求处理链路有一个统一的形态名，写在注释、提交信息与文档里时统一使用：

> ### **SESE Pipeline with Joining Branches**
> **带汇回支线的单入口单出口管道**

**一句话定义**：一条主干，沿途分出若干**会汇回**的支线，末端是一个分岔的**出口**。

```
                    ┌─ C2M ─┐
主干 ── 分岔点 ─────┤       ├──── 汇回 ── 主干继续 ──→ 出口（按下游协议分岔，不汇回）
                    └─ C2R ─┘
```

| 概念 | 判据 | 例子 |
| --- | --- | --- |
| **主干** | 协议差异是**数据** | 请求体规则（协议存在 `groups[].protocols` 里）、请求头规则、鉴权头再分配（`AuthHeaderSetting`） |
| **支线** | 协议差异是**代码**，且**有进有出、同类型进出** | 请求翻译、响应翻译、思考注入 |
| **出口** | **只有出没有回**，且**只有一个** | 错误渲染、SSE 信封 |

**最关键的一条约束**：支线**必须汇回**主干。因此它只能同类型进出，契约被压得很窄
（不能改下游协议／重试语义／落库形态）—— **扩展因此才安全**，这正是「加一个类就能接新协议」的前提。

> ⚠️ **这个形状没有既有的单一名字**，`SESE Pipeline with Joining Branches` 是本项目自造的
> 描述性命名（沿用了编译器领域 SESE 区域的术语）。**引用时不要写成「经典模式」那样的话**。
>
> 它最接近的成熟形态是**编译器的 pass 流水线**（Pass 是 IR → IR 的变换，必须单入口单出口，
> 由 `PassRegistry` 收集、按名查找）。与它不同的是：本形态的阶段序列**顺序有语义**
> （翻译必须在请求体规则之前），因此更接近编译器前端的固定降级链，而非可重排的优化池。
>
> 与几个**真实存在**的既有模式的关系：**Pipes and Filters** 不保证汇回；
> **Chain of Responsibility** 允许处理者终结请求；**Intercepting Filter** 靠 `@Order` 约定、
> 无类型保证（本项目被这个弱点打中过）。三者都缺「分叉必须汇回」这条约束，因此不要套用。

**现状**：**主干已经唯一**（阶段 3.4 全部完成后，三个应用服务退化为「建 ctx + 交主干」，
`RequestPipeline.execute(ctx)` 是唯一入口）。形态名「SESE Pipeline with Joining Branches」已成立，
尚未做的只是**包结构重排**（见 `plan_.md` 阶段 3.7：
**① 控制面出列 ✅ · ② `provider`→`upstream`+解散 `generic` ✅ · ③ 支线按接入点·步骤分包 ✅**，仅剩收尾）。
目标形态与迁移依据见根目录的 `请求处理链路重构方向.md`
（**该文件未入库**，根目录 `*.md` 被 `.gitignore` 忽略 —— 若它已不存在，本节即为该术语的最小留存）。

## 1. 全景

```
下游请求 POST /v1/chat/completions
│
├─ 一、WebFilter 链（框架层，所有请求都经过）
│   ├─ -120  GatewayAuthFilter      下游鉴权（只拦三个聊天端点）
│   ├─ -110  ResponseLoggingFilter  响应日志（DEBUG 才工作）
│   └─ -100  RequestLoggingFilter   请求日志（DEBUG 才工作）
│
├─ 二、控制器层（协议入口）
│   ├─ OpenAiController / AnthropicController / ResponsesController
│   ├─ 虚拟模型拦截（nano_llm / readme）→ 本地返回，不碰上游
│   ├─ 建 requestId，发 RECEIVED 生命周期事件
│   ├─ 流式 → Flux<ServerSentEvent>；非流式 → Mono + 取消信号
│   └─ onErrorResume 按异常类型分派状态码（全服务唯一的错误出口）
│
├─ 三、应用服务层（建上下文 + 交主干）
│   ├─ ChatCompletionService / MessagesService / ResponsesService（**三者同形**）
│   └─ RequestPipeline.execute(ctx) —— **主干唯一入口**（流式与非流式共用）
│       只有两行：beforeSend.process(ctx) → return afterSend.process(ctx)
│       以「真正发出 HTTP」为界拆成两个功能块（阶段 4 刀 3 块化）：
│       ├─ 发送前块 BeforeSend（同步 void step(ctx)）
│       │   ├─ routeStep     ProviderRouteResolver.resolve → ProtocolDispatchManager.dispatch
│       │   │                → ProtocolNotifier.notifyProtocols → ctx.applyRouting（就地回填）
│       │   ├─ translateStep 请求翻译插槽（未命中→报错）+ 回程翻译器选好记进 ctx
│       │   └─ assembleStep  RequestBodyAssembler.assemble（请求体装配）
│       └─ 发送后块 AfterSend（返回 Flux 的异步状态机）
│           ├─ 读 ctx.responseTranslator() + ctx.stream()
│           ├─ send 插槽      UpstreamExecutorRegistry.require（未命中→报错）
│           └─ 响应翻译插槽    从 ctx 取回程翻译器（未命中→透传+WARN）
│
└─ 四、上游执行层（有 I/O 的装配与发送）
    ├─ GenericOpenAiChatService / GenericAnthropicChatService /
    │  GenericResponsesChatService（父类 AbstractUpstreamChatService）
    ├─ ⑤ 模型名还原   resolveModel
    ├─ ⑥ 思考注入     ReasoningEffortSetting
    ├─ ⑦ 请求体规则   RequestBodyRuleEngine
    ├─ ⑧ null 清洗
    ├─ ⑧a 鉴权头      applyAuthenticationHeaders
    ├─ ⑧b 请求头规则  applyHeaders 规则层
    └─ ⑨ 发送         WebClient.post().bodyValue()（在 retryWhen 内）
        │
        ▼
    上游供应商
```

**一句话分工**：应用服务层只负责「建上下文并交给主干」；
主干回答「发给谁、用什么协议、要不要翻译」；
上游执行层回答「请求体和请求头各长什么样、怎么发出去」。

## 2. WebFilter 链

按 `@Order` 从小到大执行（数字越小越先）。只有 `GatewayAuthFilter` 会拦截请求。

```
-120  GatewayAuthFilter
      · 只拦三个聊天端点的 POST（PROTECTED_PATHS）
      · 开启鉴权时校验 Authorization: Bearer 或 x-api-key（OR 判据）
      · 失败直接写 401，不进入业务链
      · 发现接口（/api/version、/api/tags、/api/show）必须匿名可访
-110  ResponseLoggingFilter     非 DEBUG 直接放行
-100  RequestLoggingFilter      非 DEBUG 直接放行；读 body 后重放，
                                并重建 getFormData() 缓存
```

SPA 路由回退（`SpaRoutingConfig.spaRoutes()`）**不在这个链上** —— 它是
`RouterFunction`，由 `RouterFunctionMapping` 在 HandlerMapping 阶段处理，
而 WebFilter 链先于 HandlerMapping 执行。它只对带 `Accept: text/html` 的 GET 生效，
并排除带扩展名的路径（否则 `/assets/*.js` 会被吞掉）。

## 3. 应用服务层

三个应用服务自 3.4c-2 起**同形**：只做「建 `RequestPipelineContext`（`forEndpoint`）
+ 交主干」，**零回填、零协议分支**。方法体裹在 `Mono.defer` / `Flux.defer` 里 ——
主干内的路由解析、调度、翻译都是**同步**调用且会抛异常，不包 defer 时异常会在
**Mono 组装期**逃出控制器方法，`onErrorResume` 根本不在链上。

```
ChatCompletionService.chatCompletion(...)          ← 三个服务同形，只是端点不同
    │  Mono.defer 包裹（同步异常 → onError 信号）
    ↓
    RequestPipelineContext.forEndpoint(body, model, 下游协议, headers, requestId, stream)
    ↓
    RequestPipeline.execute(ctx)                   ← 主干唯一入口，流式/非流式共用
    │  （内部只读一次 ctx.stream() 用于「选机制」）
    ↓
    ① 路由解析
    │   ProviderRouteResolver.resolve(model)
    │     · 有 [provider-key] 前缀 → 精确取该供应商，且模型须在它名下
    │     · 无前缀 → 必须在全部启用供应商中唯一命中，否则返回 null
    │     · 只按模型名路由；协议不参与候选集筛选
    │     · 返回 null → 抛 UnresolvedModelRouteException → 控制器 400
    ↓
    ② 协议调度
    │   ProtocolDispatchManager.dispatch(下游协议, provider)
    │     规则 1：供应商支持下游同名协议 → 直连（translationNeeded=false）
    │     规则 2：不支持 → 按 TRANSLATION_FALLBACK_ORDER 挑一个，标记需翻译
    │     规则 3：一个都不支持 → 抛 NoSupportedProtocolException → 400
    │   · 无 I/O 的纯决策组件，组合可被纯单元测试穷举
    ↓
    ③ 补生命周期协议信息（best-effort，供前端 Toast 显示 C→M 标记）
    ↓
    ④ ctx.applyRouting(resolvedModel, provider, 上游协议)
    ↓
    ⑤ 请求翻译插槽（仅跨协议；直连时整个翻译链不存在）
        TranslatorRegistry.findRequestTranslator(下游, 上游)
        ├─ 命中 C2M（下游 CHAT、上游 MESSAGES）
        │     ChatToMessagesRequestTranslator.translateRequest
        │       · OpenAI 请求体 → Anthropic 请求体
        │       · 保留一份 reasoning_effort 兼容副本（供上游执行层判定「已表态」）
        │       → ctx.applyTranslation(body, 上游协议, translationContext)
        └─ 未命中 → 抛 ProtocolTranslationNotSupportedException → 400
    ↓
    ⑥ send 插槽   UpstreamExecutorRegistry.require(上游协议)
        ├─ 命中 → invoke / invokeStream（按 ctx.stream() 选，见 §4）
        └─ 未命中 → 抛 IllegalStateException（协议被声明支持却无执行器 = 装配坏了）
    ↓
    ⑦ 响应翻译插槽（仅跨协议）
        TranslatorRegistry.findResponseTranslator(下游, 上游)
        ├─ 命中 → 回程 MessagesToChatResponseTranslator
        └─ 未命中（半轮实现态）→ 原样透传 + WARN
```

**请求翻译插槽套在上游执行层外侧**，因此天然在 `retryWhen` 与空响应判定之外 ——
上游执行层内部看到的始终是**上游原生形态**。若把翻译挪进重试内侧，
空响应判定器会把每一轮都当成空响应。

## 4. 上游执行层

请求体与请求头是**两条独立流水线**，汇入同一次 `WebClient` 发送。

### 4.1 请求体装配 —— 三条线路的阶段序列

`prepareRequestBody` 是一根**显式阶段序列**（Step 3.3c 起），不再是若干散行。
每条线路的序列在代码里逐阶段调用，阶段名即方法名 —— 因此本节与代码可以逐行对照。

**三条线路共用的四个阶段**（形状相同，编号固定）：

| # | 阶段 | 方法 | 为何在这个位置 |
|---|---|---|---|
| 1 | 复制 | `copyRequestBody` | 主干会逐阶段改写 body，不能污染调用方的 Map |
| 2 | 解析模型名 | `resolveModel` | 后续阶段都要用它查模型配置 |
| 3 | 写协议字段 | `writeProtocolFields` | model（已剥前缀）与 stream 是「主干对上游的陈述」 |
| 末 | 清 null | `removeNullFields` | **必须是最后一步**，理由见下 |

**差异全在中间**，且差异的成因只有一个：**下游与上游是否同协议**。

```
Chat（GenericOpenAiChatService / AbstractUpstreamChatService）
    1 复制 → 2 解析模型名 → 3 写协议字段
    4 思考深度      ReasoningEffortSetting.applyTo
                    → reasoning_effort / thinking（off 档写 thinking:"disabled"）
    5 协议特定步骤  customizeRequestBody —— 子类钩子，本线路是请求体规则
    末 清 null
    共 6 阶段

Responses（GenericResponsesChatService）
    1 复制 → 2 解析模型名 → 3 写协议字段
    4 思考深度      ReasoningEffortSetting.applyToResponses → reasoning.effort
    5 请求体规则    RequestBodyRuleEngine.transform（按 RESPONSES 筛组）
    末 清 null
    共 6 阶段
    · 与 Chat 形状一致，只有阶段 4 的出站形态不同

Anthropic（GenericAnthropicChatService）—— 阶段最多
    1 复制 → 2 解析模型名 → 3 写协议字段
    4 协议归一化    extractSystemPrompt  system 从 messages 提到顶层
                    ensureMaxTokens      max_tokens 必填注入（缺失上游 400）
    5 思考两维      applyThinkingDimensions   深度先、方式后，off 档跳过方式
    6 剥兼容副本    dropReasoningEffortAlias  必须在阶段 5 之后
    7 请求体规则    transform（按 MESSAGES 筛组）
    末 清 null
    共 8 阶段
    · 阶段 4/5/6 都是「下游说 Chat、上游说 Anthropic」留下的债
```

**每个阶段的位置都有理由，两类位置约束最容易被破坏**：

- **`removeNullFields` 必须在最后。** 规则可能把字段显式设为 null 表达「删掉它」
  （「设置字段值」留空即置 null），先清洗后执行规则会让那个 null 原样出站；
  且预览不做 null 剥离，运行时先清洗会让同一条 `exists` 条件「预览命中、线上不命中」。
- **Anthropic 的阶段 5/6 顺序不可交换。** 见 §6 顺序陷阱。

**Responses 侧刻意不做的两个注入**（字段名看起来天造地设，容易顺手接上）：
不注入 `max_output_tokens`（该字段在 Responses 里是**可选**的，接上会给所有
「下游没带」的调用凭空补一个上限），不注入 Anthropic 的思考方式（Responses 里
没有 `thinking` 字段，思考深度已由 `reasoning.effort` 表达，不存在第二个维度需要协调）。

### 4.2 请求头装配

在 `buildWebClientWithHeaders` 的 `defaultHeaders(...)` 中执行。三层，后者覆盖前者。

```
applyHeaders(headers, downstreamHeaders, apiKey, headerRulesJson, stream, authHeader)
    │
    第 1 层  透传下游端到端头
    │       copyForwardableHeaders
    │         · 排除 hop-by-hop / Host / Content-Length（NON_FORWARDABLE_HEADERS）
    ↓
    第 2 层  鉴权头再装配
    │       applyAuthenticationHeaders
    │         ① 探测 downstreamHeaders 是否带 Authorization / x-api-key
    │            （读参数而非目标 headers；非空白才算「带了」）
    │         ② AuthHeaderSetting.resolveHeader → 目标头名
    │            取下游：下游恰好带 1 个就认它；带 0 或 2 个则用配置值兜底
    │            取设置：始终用配置值
    │         ③ 无条件删掉两个鉴权头
    │         ④ 按目标注入供应商 active key
    │            值为空则发空值 —— 「发出去」比「悄悄跳过」好排查
    ↓
    第 3 层  媒体类型 + 请求头规则
            · Content-Type: application/json
            · Accept: text/event-stream（流式）或 */*（非流式）
            · 供应商规则：{apiKey} 占位替换、/del/ 删除
              —— 拥有最终决定权（在鉴权装配之后，可把被删的头加回来）
```

### 4.3 发送

```
buildWebClientWithHeaders(...)
    · 复用注入的 WebClient.Builder（禁止裸 WebClient.builder()）
    · clientConnector 用注入的 httpClient（JDK DNS 解析器）
    · filter 抓取出站头快照 → 写 api_call_log
  .post().uri(chatCompletionsUri()).bodyValue(requestBody)
    · 外层包 RetryPolicyService 的重试预算（UpstreamAutoRetry.build 造出的 Retry）
    · 空响应拦截由 EmptyResponseGate（机制，三条线路共用）负责，
      判据由 ContentDetectorRegistry 按上游协议查表得到：
      OpenAI 用 OpenAiContentDetector，Anthropic 用 AnthropicContentDetector，
      Responses 用 ResponsesContentDetector，三者不可互相套用
```

## 5. 完整顺序表

| 序 | 层 | 关键类 / 方法 | 对请求做了什么 |
|---|---|---|---|
| — | WebFilter | `GatewayAuthFilter` | 校验下游凭据（OR 判据），失败 401 |
| — | 控制器 | `OpenAiController.chatCompletions` | 虚拟模型拦截、建 requestId、分流、错误分类 |
| ① | 主干·发送前块 | `BeforeSend.routeStep` → `ProviderRouteResolver.resolve` | 剥前缀选唯一供应商 |
| ② | 主干·发送前块 | `BeforeSend.routeStep` → `ProtocolDispatchManager.dispatch` | 判直连 / 翻译 / 无支持 |
| ③ | 主干·发送前块 | `ProtocolNotifier.notifyProtocols` + `ctx.applyRouting` | 补协议信息、回填路由 |
| ④ | 主干·发送前块 | `BeforeSend.translateStep` → `TranslatorRegistry.findRequestTranslator` | 命中则 `ChatToMessagesRequestTranslator`；回程翻译器一并选好记进 ctx |
| ⑤ | 主干·发送前块 | `assembleStep` → `RequestBodyAssembler.resolveModel` | 剥前缀还原真实模型名 + 设 `stream` |
| ⑥ | 主干·发送前块 | `assembleStep` → `ReasoningEffortSetting` | 写思考深度（Anthropic 侧另写 `thinking`） |
| ⑦ | 主干·发送前块 | `assembleStep` → `RequestBodyRuleEngine.transform` | 供应商规则组改 / 删字段 |
| ⑧ | 主干·发送前块 | `assembleStep` → `removeNullFields` | 清掉规则产生的 null |
| ⑧a | 上游执行 | `applyAuthenticationHeaders` | 探测 → 决定头名 → 删两个 → 注一个 |
| ⑧b | 上游执行 | `applyHeaders` 规则层 | `{apiKey}` 占位、`/del/` 删除 |
| ⑨ | 上游执行 | `UpstreamExecutorRegistry.require` → `WebClient.post().bodyValue()` | 查表选中执行器，在重试预算内发出 |
| ⑩ | 主干·发送后块 | `AfterSend` 读 `ctx.responseTranslator()`（发送前块步骤 5 已选好） | 命中则回程翻译；未命中透传 + WARN |

> 序 ①–④ / ⑩ 是**主干自己的步骤**：①–④ 在发送前块（`BeforeSend`）、⑩ 在发送后块（`AfterSend`）；
> ⑤–⑨ 中，⑤–⑧ 请求体装配自阶段 4 刀 1 已上移主干（发送前块 `assembleStep` → `RequestBodyAssembler`），
> ⑧a–⑨ 出站头装配与发送仍在上游执行层内部。
> ⑤–⑧ 是 `RequestBodyAssembler` 那根阶段序列的一部分，逐阶段名与位置见 §4.1 ——
> 那里列的是 <strong>8 个阶段的完整形态</strong>（Anthropic 侧），本表只列跨线路共同的骨架。

## 6. 顺序陷阱

这几处顺序是刻意的，改动时不要「顺手理顺」。阶段编号对应 §4.1 的序列。

**`removeNullFields`（末阶段）排在请求体规则（阶段 5/7）之后。** 两件事都依赖它：
规则的「设置字段值」留空即置 null，若先清洗后执行规则，那个 null 会原样出站，
而部分上游对多余的 null 字段并不宽容；且编辑器预览直接作用于用户粘贴的请求体、
不做 null 剥离，若运行时先清洗，同一条 `exists` 条件会「预览命中、线上不命中」
—— 预览一旦会说谎，它的全部价值就没了。

**Anthropic 侧剥 `reasoning_effort`（阶段 6）排在思考两维（阶段 5）之后。** C2M 翻译器把
`reasoning_effort` 映射到 `output_config.effort` 后**刻意保留一份兼容副本**，
供思考深度与思考方式两层判定「下游是否已表态」。提前剥会让兜底档把一个已表态的请求
当成未表态，静默退化成覆写档。

**思考深度与思考方式在 Anthropic 侧不可交换（同属阶段 5）。** 深度先、方式后，且深度写了
`thinking: {"type":"disabled"}` 时**跳过方式**。先方式后深度会让深度兜底档把方式刚写的
字段误认为「下游已表态」；不跳过方式则会把 `disabled` 改写成 `adaptive`，
把用户配的「关闭思考」静默丢弃。

**协议归一化（Anthropic 阶段 4）排在请求体规则（阶段 7）之前。** 规则的字段路径是照
最终发往上游的形态写的（`system` 已提顶层、`max_tokens` 已补齐），若在归一化前执行，
用户看到的预览与实际请求体结构不一致。

**请求头规则在鉴权装配之后。** 规则层拥有最终决定权：需要双头并存的中转站可以把
被删的头加回来，需要非 Bearer 形态的可以用 `{apiKey}` 占位改写。

**空响应判定用各线路自己的 Detector。** 三份实现（`OpenAiContentDetector`、
`AnthropicContentDetector`、`ResponsesContentDetector`）的判据互不通用，
把 OpenAI 的 JSON/SSE 判定套到 Anthropic 上会把正常响应的头两个事件判成空。
三个阶段 3.6 起由 `ContentDetectorRegistry` 按 `ctx.upstreamProtocol()` **查表**取得
（未命中即报错；三个实现分别是 `ChatContentDetectorStage` / `MessagesContentDetectorStage`
/ `ResponsesContentDetectorStage`）。

> ⚠️ **Chat 的接线是「反」的。** `OpenAiContentDetector` 里叫 `hasMeaningfulPayload`
> 的那个方法**其实是流式用的**（读 `choices[].delta`），非流式那个叫
> `hasMeaningfulNonStreamPayload`（读 `choices[].message`）。
> `ChatContentDetectorStage` 已按**取值路径**而非名字接好 —— 改它时不要「顺手理顺」。

## 7. 相关文档

| 主题 | 文件 |
| --- | --- |
| 出站与入站鉴权头的设计 | [AUTH_HEADER_ASSEMBLY.md](./AUTH_HEADER_ASSEMBLY.md) |
| 跳协议翻译契约（请求侧） | [PROTOCOL_TRANSLATION_CONTRACT.md](./PROTOCOL_TRANSLATION_CONTRACT.md) |
| 跳协议翻译契约（响应侧） | [PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md](./PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md) |
| 供应商适配史与请求转换取舍 | [PROVIDER_ADAPTATIONS.md](./PROVIDER_ADAPTATIONS.md) |

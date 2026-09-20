# 下游请求处理链路

> 本文档描述一次下游请求从进入 COSP 到发往上游之间经历的处理层，重点是
> **应用服务层**（决定发给谁、用什么协议）与**上游执行层**（决定请求长什么样、怎么发）
> 的分工与顺序。

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
├─ 三、应用服务层（无 I/O 的路由与编排）
│   ├─ ChatCompletionService / MessagesService / ResponsesService
│   ├─ ① 路由解析   ProviderRouteResolver
│   ├─ ② 协议调度   ProtocolDispatchManager
│   ├─ ③ 分派       直连 / 跨协议翻译 / 未实现
│   └─ ④ 翻译编排   ChatToMessagesRequestTranslator（去程）
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

**一句话分工**：应用服务层回答「发给谁、用什么协议、要不要翻译」；
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

三个应用服务结构对称，方法体都裹在 `Mono.defer` / `Flux.defer` 里 ——
下面 ①②④ 都是**同步**调用且会抛异常，不包 defer 时异常会在 **Mono 组装期**
逃出控制器方法，`onErrorResume` 根本不在链上。

```
ChatCompletionService.chatCompletion(...)
    │  Mono.defer 包裹（同步异常 → onError 信号）
    ↓
    ① 路由解析
    │   ProviderRouteResolver.resolve(model)
    │     · 有 [provider-key] 前缀 → 精确取该供应商，且模型须在它名下
    │     · 无前缀 → 必须在全部启用供应商中唯一命中，否则返回 null
    │     · 只按模型名路由；协议不参与候选集筛选
    │     · 返回 null → 控制器报「没有可用的上游服务」
    ↓
    ② 协议调度
    │   ProtocolDispatchManager.dispatch(下游协议, provider)
    │     规则 1：供应商支持下游同名协议 → 直连（translationNeeded=false）
    │     规则 2：不支持 → 按 TRANSLATION_FALLBACK_ORDER 挑一个，标记需翻译
    │     规则 3：一个都不支持 → 抛 NoSupportedProtocolException → 400
    │   · 无 I/O 的纯决策组件，四种组合可被纯单元测试穷举
    ↓
    ③ 补生命周期协议信息（best-effort，供前端 Toast 显示 O→A 标记）
    ↓
    ④ 分派
        ├─ 直连 ─────────────→ 原请求体不变，直接进上游执行层
        ├─ C2M（下游 CHAT、上游 MESSAGES）
        │      去程 ChatToMessagesRequestTranslator.translateRequest
        │        · OpenAI 请求体 → Anthropic 请求体
        │        · 保留一份 reasoning_effort 兼容副本（供上游执行层判定「已表态」）
        │      → 进上游执行层
        │      ← 回程 MessagesToChatResponseTranslator
        └─ 其它方向 → 抛 ProtocolTranslationNotSupportedException → 400
```

**翻译器套在上游执行层外侧**，因此天然在 `retryWhen` 与空响应判定之外 ——
上游执行层内部看到的始终是**上游原生形态**。若把翻译挪进重试内侧，
空响应判定器会把每一轮都当成空响应。

## 4. 上游执行层

请求体与请求头是**两条独立流水线**，汇入同一次 `WebClient` 发送。

### 4.1 请求体装配

三条线路的顺序不同，差异来自「下游与上游是否同协议」。

```
GenericOpenAiChatService / GenericResponsesChatService（下游上游同为 OpenAI 系）
    ① resolveModel      剥 [provider-key] 前缀 → 上游真实模型名
    ② 设 stream 标志
    ③ 思考深度          ReasoningEffortSetting.applyTo / applyToResponses
                        → reasoning_effort（Responses 侧写 reasoning.effort）
    ④ 请求体规则        RequestBodyRuleEngine.transform
                        按声明适用 CHAT / RESPONSES 的规则组执行
    ⑤ null 清洗         removeIf(Objects::isNull)


GenericAnthropicChatService（下游 CHAT + 上游 MESSAGES 时，体已被 C2M 翻译过）
    ① resolveModel      剥前缀 + 设 stream
    ② extractSystemPrompt   system 从 messages 提到顶层（Anthropic 不接受 OpenAI 形态）
    ③ ensureMaxTokens       max_tokens 必填注入（缺失上游 400）
    ④ 思考深度              ReasoningEffortSetting.applyToAnthropic
                            → 顶层 output_config.effort
    ⑤ 思考方式              AnthropicThinkingSetting.applyTo
                            → thinking 对象（④ 写了 disabled 时跳过本步）
    ⑥ 剥 reasoning_effort   兼容副本，必须在 ④⑤ 之后
    ⑦ 请求体规则            transform，按 MESSAGES 筛组
    ⑧ null 清洗
```

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
    · 外层包 RetryPolicyService 的重试预算
    · 空响应判定用各线路自己的 Detector（OpenAI 用 OpenAiContentDetector，
      Anthropic 用 AnthropicContentDetector，不可互相套用）
```

## 5. 完整顺序表

| 序 | 层 | 关键类 / 方法 | 对请求做了什么 |
|---|---|---|---|
| — | WebFilter | `GatewayAuthFilter` | 校验下游凭据（OR 判据），失败 401 |
| — | 控制器 | `OpenAiController.chatCompletions` | 虚拟模型拦截、建 requestId、分流、错误分类 |
| ① | 应用服务 | `ProviderRouteResolver.resolve` | 剥前缀选唯一供应商 |
| ② | 应用服务 | `ProtocolDispatchManager.dispatch` | 判直连 / 翻译 / 无支持 |
| ③ | 应用服务 | `ChatCompletionService` 分派 | 直连走原体，跨协议进翻译 |
| ④ | 应用服务 | `ChatToMessagesRequestTranslator` | OpenAI 体 → Anthropic 体（上游执行层外侧） |
| ⑤ | 上游执行 | `resolveModel` | 剥前缀还原真实模型名 + 设 `stream` |
| ⑥ | 上游执行 | `ReasoningEffortSetting` | 写思考深度（Anthropic 侧另写 `thinking`） |
| ⑦ | 上游执行 | `RequestBodyRuleEngine.transform` | 供应商规则组改 / 删字段 |
| ⑧ | 上游执行 | `removeIf(Objects::isNull)` | 清掉规则产生的 null |
| ⑧a | 上游执行 | `applyAuthenticationHeaders` | 探测 → 决定头名 → 删两个 → 注一个 |
| ⑧b | 上游执行 | `applyHeaders` 规则层 | `{apiKey}` 占位、`/del/` 删除 |
| ⑨ | 上游执行 | `WebClient.post().bodyValue()` | 在重试预算内发出 |

## 6. 顺序陷阱

这几处顺序是刻意的，改动时不要「顺手理顺」。

**null 清洗排在请求体规则之后。** 两件事都依赖它：规则的「设置字段值」留空即置 null，
若先清洗后执行规则，那个 null 会原样出站，而部分上游对多余的 null 字段并不宽容；
且编辑器预览直接作用于用户粘贴的请求体、不做 null 剥离，若运行时先清洗，
同一条 `exists` 条件会「预览命中、线上不命中」—— 预览一旦会说谎，它的全部价值就没了。

**Anthropic 侧剥 `reasoning_effort` 排在两个思考设置层之后。** C2M 翻译器把
`reasoning_effort` 映射到 `output_config.effort` 后**刻意保留一份兼容副本**，
供思考深度与思考方式两层判定「下游是否已表态」。提前剥会让兜底档把一个已表态的请求
当成未表态，静默退化成覆写档。

**思考深度与思考方式在 Anthropic 侧不可交换。** 深度先、方式后，且深度写了
`thinking: {"type":"disabled"}` 时**跳过方式**。先方式后深度会让深度兜底档把方式刚写的
字段误认为「下游已表态」；不跳过方式则会把 `disabled` 改写成 `adaptive`，
把用户配的「关闭思考」静默丢弃。

**请求头规则在鉴权装配之后。** 规则层拥有最终决定权：需要双头并存的中转站可以把
被删的头加回来，需要非 Bearer 形态的可以用 `{apiKey}` 占位改写。

**空响应判定用各线路自己的 Detector。** 三份实现（`OpenAiContentDetector`、
`AnthropicContentDetector`、`ResponsesContentDetector`）的判据互不通用，
把 OpenAI 的 JSON/SSE 判定套到 Anthropic 上会把正常响应的头两个事件判成空。

## 7. 相关文档

| 主题 | 文件 |
| --- | --- |
| 出站与入站鉴权头的设计 | [AUTH_HEADER_ASSEMBLY.md](./AUTH_HEADER_ASSEMBLY.md) |
| 跳协议翻译契约（请求侧） | [PROTOCOL_TRANSLATION_CONTRACT.md](./PROTOCOL_TRANSLATION_CONTRACT.md) |
| 跳协议翻译契约（响应侧） | [PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md](./PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md) |
| 供应商适配史与请求转换取舍 | [PROVIDER_ADAPTATIONS.md](./PROVIDER_ADAPTATIONS.md) |

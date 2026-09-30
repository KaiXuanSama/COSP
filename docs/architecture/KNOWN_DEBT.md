# 已知技术债与刻意不做的取舍

> 本文档记录经审查确认、但**刻意不在当前分支处理**的项，以及为什么。
> 首次汇总于 `feat/responses-protocol` 分支审查（2026-09-13，约 +10113/-902 行、12 个提交）。

沉淀成文档而不是留 `TODO` 注释，是因为这些项的判断依据不在单个文件里 —— 它们涉及三条线路的
一致性、跨分支的改动边界，或需要先做实测才能定方案。散落在代码里只会看到「这里有点问题」，
看不到「为什么现在不动它」。

按 [AGENTS.md](../../AGENTS.md) 的 TODO 约定：已经做出的取舍不留 `TODO`。本文档就是那些取舍的去处。

## 判断原则

一次功能分支该修什么，取决于**这个缺陷是不是它引入的**：

| 情形 | 处理 |
|---|---|
| 本分支引入 | 本分支修 |
| 既有债，本分支未加重 | 不动，记这里 |
| 既有债，本分支**放大**了它 | 记这里并注明放大倍数，不在功能分支重构 |
| 修它需要改动三条线路的共同行为 | 单独分支，三侧一起改 |

最后一条最容易被忽略：只修一侧会让三条线路的行为分叉，而分叉的症状（落库时序、错误传播路径
不一致）比原来的债更难排查。

## 一、三侧上游服务的阻塞 JDBC 未桥到 boundedElastic

**现状**：`GenericOpenAiChatService`、`GenericAnthropicChatService`、`GenericResponsesChatService`
都在 `doOnNext` / `doOnError` / `doFinally` 里同步调用 `apiCallLog.save(...)`（底层是
`jdbcTemplate.update`），即阻塞操作跑在 Reactor 的 event-loop 线程上。

**与约定的关系**：[AGENTS.md](../../AGENTS.md) 写的是「**新增或修改**的响应式链中，阻塞 JDBC 必须经
`Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 桥接」。三侧都不合规，
且这个形状早于 Responses 接入。

**为什么不只修 Responses**：落库时序会分叉。桥接后落库变成异步，而三侧的
「空响应判定 → 重试 → 落库」顺序是共用的口径；只桥一侧会让那一侧的日志写入时间点与另两侧不同，
排查跨协议问题时无法对齐时间线。错误传播路径同理 —— 桥接引入的新订阅链有自己的错误出口。

**要还的话怎么还**：三侧一起，一个独立分支，且必须覆盖「落库失败不影响响应下发」
（现有测试已钉住这一点：log 写失败仍写孤儿 usage）。

## 二、`ChunksViewer` 的 `v-html` 未净化

**位置**：`frontend/src/components/calllog/ChunksViewer.vue:551`，`renderMd`（包的是
`marked.parse`）的结果直接进 `v-html`，无 DOMPurify。

**风险面**：渲染的是**上游返回的文本**。上游可控（用户自己配的中转站，也可能是被入侵的中转站），
而管理后台的 JWT 存在 localStorage —— 这条链可以从「上游注入一段 HTML」扩大到管理员会话泄露。

**与约定的关系**：[AGENTS.md](../../AGENTS.md) 与
[前端规则](../../.github/instructions/frontend.instructions.md) 都明文要求「上游或日志中的不可信文本
未经净化不得进入 `v-html`」。参考项目 `sub2api` 在同类位置全部配了 DOMPurify。

**为什么不在 Responses 分支修**：该分支未改动这个文件（`git diff --stat master...HEAD` 对该文件为空），
它是既有债。但这是**本文档里唯一有安全影响**的一条，优先级应高于其余各项 —— 建议单独开分支尽快处理，
不要跟着功能排期走。

## 三、`Settings.vue` 的协议地址状态重复

**现状**：2106 行，全前端最大。抽屉与弹窗两套协议地址状态几乎逐行同构：
`editMirroringBaseUrl` / `mirroringBaseUrl`、`onEditBaseUrlFocus` / `onProviderBaseUrlFocus`、
`onEditBaseUrlInput` / `onProviderBaseUrlInput`、`editEndpointHintOf` / `providerEndpointHintOf`。

**本分支放大了它**：每个函数体内部现在有 `MESSAGES` / `RESPONSES` 两条几乎相同的分支，
重复从 2 份变成 2×3。加第四条协议要在 8 处各加一个分支。

**为什么不在功能分支做**：这是结构问题而非缺陷 —— `protocolUrls.spec.ts` 专门钉住了
「两条线路的联动状态互不影响」，行为是对的。纯判断逻辑也已经在 `protocolUrls.ts`（212 行、48 个用例）,
留在 SFC 里的确实只是「持有 ref + 绑事件」，符合该模块承诺的分工。在功能分支里顺手重构
会让 diff 里的功能改动与结构改动混在一起，review 时无法分辨哪一处是行为变更。

**要做的话怎么做**：抽 `features/provider-config/useProtocolBaseUrls.ts`，内部把三条地址存成
`Record<WireProtocol, string>` 而不是三个独立 ref —— 联动就退化成
`for (const p of ALL_WIRE_PROTOCOLS) if (p !== 'CHAT') ...` 一个循环。抽屉与弹窗各调一次
`useProtocolBaseUrls()` 就天然拥有独立状态，那正是当前用两套 ref 手工维持的东西。

## 四、不抽三侧上游服务的公共基类

**现状**：`GenericResponsesChatService` 与 `GenericAnthropicChatService` 仍有一段同构的
调用骨架 —— setter 注入块、非流式的 `defer → doOnNext 落库 → doOnError 落库 → … → retryWhen`、
流式的 `exchangeToFlux` 错误分支。（落库那一族两态 × 三协议共 9 份**已收归
`UpstreamCallRunner`**，见第十条。）

> **原先列举的六类同构已全部收归**（本节此前列的五类 + 落库）：
> `isRetryableFailure` / `hasNetworkCause` / `hasSslHandshakeFailure` → `UpstreamRetryPolicy`；
> 两个 `find*` 解包 → 同类的 `findEmptyUpstreamException` / `findWebResponseException`；
> 静默重试 loop → `control/CallResendLoop`；`retryWhen` 规格 → `UpstreamAutoRetry`；
> 非流式判空与耗尽放行、流式 gate → `EmptyResponseGate`；
> 空响应判定 → `ContentDetectorStage` 支线（按 `upstreamProtocol` 查表）。
> **落库那一族**已一并收归（`UpstreamCallRunner` 的静态方法，见第十条）。
> 本节因此只剩「调用骨架的外层编排」这一层 —— 亦已由 runner 收归，抽公共基类的
> 论点至此**不再有具体依据**（三条线路的执行器都已是「建 `AttemptContext` + 闭包 → 委托 runner」
> 的薄适配器，没有值得抽的骨架了）。

**这是决策，不是待办**（`GenericResponsesChatService` 类注释已写明）。抽取的触发条件是
「**三侧口径出现差异**」，而不是「结构相似」。理由：这些方法的相似是因为三条线路当前对
重试预算、空响应判定、落库时机的**要求恰好一致**；一旦某个上游要求不同的退避策略或不同的
判空口径，模板方法就会变成需要开洞的约束。在那之前，模板方法带来的间接层只会让
「这条线路到底怎么处理错误」变得更难读。

> **已印证这条判据**：真正该抽的那几类（判定、算子序列、日志文案）都是**会静默分叉**的，
> 它们被抽成了独立的小工具类（`UpstreamAutoRetry` / `control/CallResendLoop` / `EmptyResponseGate`），
> 而不是一个「上游服务基类」。三个执行器仍平级、各自拥有自己的 Reactor 链 ——
> **抽特征而不是抽骨架。**
>
> 附带的口径是：执行器保留的那几行**适配器**（`buildRetrySpec`、`publishLifecycle`）
> 与签名绑定，不符即编译失败，属「安全的重复」；被抽走的都是不会编译失败的东西。

> **本条与第十二条不矛盾 —— 两者说的是不同的事**：
> 本条反对的是**抽一个「上游服务基类」让三个执行器继承**（模板方法 + 钩子，子类看不见依赖）。
> 第十二条要做的是把协议无关的**编排**上移到**主干**（`RequestPipeline` 持有的 runner），
> 执行器退化成把协议特定闭包交出去的**薄适配器** —— 那是**组合**不是继承，主干不是执行器的父类。
> 判据一致：会静默分叉的编排该由一处持有（主干），协议特定的部分（transport / usage 解析）
> 仍是各协议一份。**「抽骨架」（本条反对）与「主干持编排」（第十二条）的差别，就在控制流归谁**。

## 五、后端协议白名单四处硬编码

**现状**：`{"CHAT", "MESSAGES", "RESPONSES"}` 这个集合在四处各自写死：

| 位置 | 形态 |
|---|---|
| `ProviderRequestTransformService.java:58` | `Set.of("CHAT", "MESSAGES", "RESPONSES")` |
| `ProviderAdminService.java:51` | `Set.of("CHAT", "MESSAGES", "RESPONSES")` |
| `ProviderRuntimeConfiguration.java:41` | `"[\"CHAT\",\"MESSAGES\",\"RESPONSES\"]"` |
| `schema.sql:28` | `DEFAULT '["CHAT","MESSAGES","RESPONSES"]'` |

前两处的注释都写了「刻意不引用枚举，因为校验的是外部输入字符串，`valueOf` 会把非法值
变成异常控制流」。**这个理由只支持不用 `valueOf`，不支持硬编码字面量** ——
`Arrays.stream(WireProtocol.values()).map(Enum::name).collect(toSet())` 同时满足集合包含判断
与单一真源。而 `ProviderAdminService.java:347` 正是这么做的，于是同一个类里存在两种口径。

**后两处保留字面量是对的**：SQL 无法引用枚举；`ProviderRuntimeConfiguration` 若引用枚举会造成
`application.runtime` 与 `pipeline.protocol` 双向依赖（该文件注释已说明）。它们只需保留
交叉引用注释。

**漏改的症状**：`ProviderAdminService` 漏改 → 界面上勾不到新协议；
`ProviderRequestTransformService` 漏改 → 保存时报「不支持的线路协议」。两者都需要用户复现才能发现。

## 六、`ProviderProtocolSupport.OPTIMISTIC_ALL` 契约与实现不符

**位置**：`ProviderProtocolSupport.java:59` 声明
`private static final Set<WireProtocol> OPTIMISTIC_ALL = EnumSet.allOf(WireProtocol.class)`，
而 `:68` 的 `@return` 写「支持的协议集合（**不可变**）」。`EnumSet` 是可变集合，`final` 只锁引用。

四处返回路径（`:72`、`:94`、`:99`、`:122`）直接返回这个 static 常量。任一调用方对返回值调
`remove` / `add` 会污染它，影响进程内**所有**供应商的后续判定，且没有任何日志痕迹。
另一分支（`:124`）用的是 `Set.copyOf(protocols)`，那才真的不可变 —— 两条返回路径的可变性不一致。

**当前无触发者**（`ProtocolDispatchManager:110` 只读，测试只做断言），所以不是缺陷。
但这正是「新调用方在不知情下踩中」的形态。修法一行：`Set.copyOf(EnumSet.allOf(WireProtocol.class))`。

## 七、`ruleSetJson.ts` 的错误文案漏 `RESPONSES`

**位置**：`frontend/src/features/request-body-rules/ruleSetJson.ts:101`

```ts
return `${path}.protocols[${index}] 必须为 "CHAT" 或 "MESSAGES"`
```

`isWireProtocol` 认三个值，文案只列两个。用户在 JSON 视图里把 `RESPONSES` 打成 `RESPONSE`，
拿到的提示会让他以为 Responses 根本不是合法取值。这是机械重命名的漏改（master 原文是
`必须为 "OPENAI" 或 "ANTHROPIC"`，逐字替换时没跟上「新增了第三个成员」）。

**为什么至今没被发现**：`ruleSetJson.spec.ts:49` 只断言 `result.error` 含**路径**
（`groups[0].protocols[0]`），不断言取值清单 —— 文案错了测试照样绿。

**修法**：从常量派生而非手写，`RULE_ENGINE_WIRE_PROTOCOLS` 在该文件第 8 行已经 import 了：

```ts
return `${path}.protocols[${index}] 必须是以下之一：${
  RULE_ENGINE_WIRE_PROTOCOLS.map(p => `"${p}"`).join(' / ')}`
```

同时把 spec 的断言收紧到 `toContain('RESPONSES')`。

## 八、Responses 路径的工具调用无端到端验证

**现状**：`ResponsesContentDetector` 的工具调用判定、`GenericResponsesChatService` 的透传、
前端 `aggregateResponsesChunks` 的解析都有单元测试，但**没有一次真实的带工具调用的 Responses 流**
被端到端验证过。已实测的是正文与思考链（2026-09-12 对 MiMo 与 DeepSeek）。

**为什么这是最大的空白**：这条路径已经栽过两次，两次都是「照文档实现了一支、真实上游走另一支」：

| 日期 | 漏的那一支 | 发现方式 |
|---|---|---|
| 2026-09-12 | 思考链的 `content[]`（只实现了 `summary[]`） | 真机调用 MiMo / DeepSeek |
| 2026-09-13 | 工具参数的定稿字段 `arguments` / `input`（只实现了 `delta`） | 代码审查 |

第二条是审查发现而非实测发现，意味着**工具调用这条路至今没有真实样本**。而这两次的共同根因是
「官方文档写 `A or B`，只实现了 A」—— 只有真实报文能证明哪一支真的存在。

**症状形态**：不报错，表现为「调工具时特别慢、token 莫名偏高」（一次正常回答变 6 次计费调用
加约 60 秒退避，耗尽预算后仍放行内容），极难归因。

**建议**：拿 Codex 触发一次带工具调用的 Responses 流，采样落库的 chunks 与
`api_call_log`，比对三处：判定器是否全程认出载荷、前端规整视图是否正确分段、参数是否完整拼回。

## 九、条件路径的存量错误配置不做自动改写（2026-09-13 已修生成逻辑）

**已修的部分**：编辑器的条件路径下拉此前一律从「当前作用域对象」生成路径，
而引擎在数组模式下以**元素**为作用域求值 —— 于是下拉给出 `./tools[*]/type`，
引擎解析时第一步找 `tools` 字段就找不到，直接返回 null。规则**永不命中且零告警**
（「未匹配不告警」是引擎的既定决策：防御性规则本就该在上游没带那个字段时静默放行）。

症状是「配了规则但看起来完全没生效」，且预览面板显示「输入与输出一致」——
与「字段本来就不存在」这一正常情形无法区分。

现已把作用域推导抽成 `frontend/src/features/request-body-rules/pathOptions.ts`，
由 `resolveConditionScope` 按规则的 `array` 开关决定作用域，与引擎的
`conditionsMatch` 一一对应。抽成纯模块是因为逻辑原先长在 SFC 里而前端只对
`features/**` 测单测，那份实现零覆盖 —— 不抽出来，修完还是没有回归防线。

**刻意不做的部分：存量已保存的错误路径不自动改写。**

用户库里可能已经存了 `./tools[*]/type`（下拉给的，或从别处抄的），
改生成逻辑不会修正它们。三种处理方式都试过取舍：

| 方式 | 为什么不选 |
|---|---|
| 保存时自动改写 | 要判断「哪些路径可安全改写」，而数组嵌套下容易误伤 —— 改写错了会静默改变用户已生效的规则语义 |
| 引擎在路径解析失败时告警 | 违背「未匹配不告警」这条已论证的决策。要区分「字段本来就没有」（正常）与「路径永远解析不了」（配错），而从路径本身分不出来 —— 两者都是解析返回 null |
| **不处理（当前选择）** | 用户意识到规则没生效时会自行检查路径并修改。配置错误由配置者负责，不做隐式修正 |

判定依据：自动改写属于「替用户猜意图」，而这里的猜错代价是**静默改变规则行为**，
比让用户自己发现并修改更危险。

## 十、落库那一族的跨协议合并 —— **已解决**

> **已落地**：9 份 `saveXxxLog` 收归 `pipeline/after/send/UpstreamCallRunner` 的三个静态方法
> （`saveNonStreamLog` / `saveStreamLog` / `saveStreamLogWithError`），上游协议统一从
> `ctx.upstreamProtocol()` 取。三个执行器不再各留一份，`DEFAULT_PROTOCOL` 脆弱性随之消失（见下）。
> 下文保留原始分析作为「为什么这样合」的依据。

**当时的现状**：`saveNonStreamLog` / `saveStreamLog` / `saveUsage` 在三个执行器里共 **9 份**
（两态 × 三协议），是收归完六类同构后**唯一剩下的协议轴重复**。

**已核实可合并**，差异只有两处：

| 差异 | 说明 |
|---|---|
| 上游协议常量 | `WireProtocol.MESSAGES.name()` / `RESPONSES.name()` / Chat 硬编码 `DEFAULT_PROTOCOL` |
| `ChunkLogPayload.from(chunkRewriter, chunks)` vs `.direct(chunks)` | **`from` 在 `rewriter == null` 时本就退回 `direct`** —— 两条路等价 |

其余（参数顺序、调用哪个重载）逐字相同。

**合并顺带修掉的脆弱性（已兑现）**：Chat 曾用 `DEFAULT_PROTOCOL`（= `"CHAT"`）填上游协议列，
正确性依赖一条**隐式不变量**——「每个协议的服务只保存自己的上游协议」，无类型保证。
收归后 Chat 也走协议感知重载、上游协议从 `ctx.upstreamProtocol()` 取，
将来 C2R 等跳协议方向落地时不会再静默记错。

> **落地副作用**：Chat 从 `ApiCallLogService` 的 8 参短重载（内部填
> `DEFAULT_PROTOCOL`）切到 10 参协议感知重载。落库结果对直连**完全等价**（CHAT/CHAT），
> 但 Mockito mock 接口**不执行 default 方法**，故 stub 短重载的测试要改 stub 到长重载 ——
> 改的是 mock 配置不是断言。`ApiCallLogRepository` 里的 `DEFAULT_PROTOCOL` 短重载**保留**
> （仓储层的向后兼容入口，非主路径；主路径已全部走协议感知重载）。

**原「为什么当时不做」**：它当时不在收归轴上、且牵动 `api_call_log`
两列语义。主干后半段归位后，落库自然塌成一处，两列各自两型仍保持不变
（`response_body`：正常体 / 错误体；`chunks`：裸数组 / 翻译前后对象 —— 合并没有压平它们）。

## 十二、send 插槽边界画错，吞掉了主干后半段 —— **已还**

> **最终形态**：`RequestPipeline.execute` 按「真正发出 HTTP」拆成发送前块 `BeforeSend`
> （同步 `void step(ctx)` 序列）与发送后块 `AfterSend`（异步状态机）；协议无关的装配与编排
> 全回主干侧（`RequestBodyAssembler` · `OutboundRequestAssembler` · `UpstreamCallRunner`），
> 协议特定部分走各接入点的查表支线。三个执行器退化成「建 `AttemptContext` + 交协议闭包」。
>
> **契约收缩（`UpstreamExecutor` → transport）是可选清理**：块化已拿走它大部分收益 ——
> 发送后块现在只剩「铺 ctx 装好的头/地址 + 抓传输层快照 + 发送」。
> 控制流方向已完全矫正：主干持流程，插槽只填传输 + 协议特有中段。
>
> 下文分析基于「尚未动手」时写就，作为**为什么边界要这么画**的背景保留。

**这是最严重的结构债，也是第十条、第四条的共同根因。** 单列一条是因为它牵动的不是
某一族函数，而是整条主干的控制流方向。

**现状**：`RequestPipeline.execute` 全文只有 5 步（前奏 → 回填 → translate 插槽 → **send 插槽**
→ responseTranslate 插槽）。而「按步骤逐个处理请求体 / 请求头 / 响应」的那条链 ——
请求体装配、出站头、上游往返、空响应兜底、自动重试、回程帧处理、落库 ——
**全在 send 插槽内部**，三个执行器各持一条私有管道（1096 + 1049 + 876 = 3021 行）。

**控制流是反的**：设计意图是「主干持流程、插槽填一步」，实际是「插槽持流程、主干供工具」。
`EmptyResponseGate` / `UpstreamAutoRetry` / `UpstreamRetryPolicy` / `UpstreamCallReporter`
确实只有一份，但它们是**被插槽调用的工具**，不是主干上的步骤。

**按判据核对**（协议无关→主干，协议相关→插槽）：协议无关的 8 项（copy / resolveModel /
writeProtocolFields / 请求体规则 / removeNullFields / 出站头 / 空响应兜底 / 自动重试 / 落库）
**全留在插槽内部各写一份**；协议相关的 5 项只有 2 项（system 抬升、max_tokens）真插槽化了。
即：抽出来的是较小的那一半。

**根因**：早先定「三执行器实现共同接口」时，接口形状直接继承了既有的
`chatCompletion` / `chatCompletionStream`，于是 `invoke(ctx, chunkRewriter) → UpstreamEvent`
描述的是「整个上游交互」而非「发送一次」。粒度一旦固定，主干后半段就物理上没有落点。

**放大历史**：这不是某个功能分支引入的，而是主干化重构的**设计取舍遗留** ——
当时为降风险选了「执行器实现共同接口」，把边界画在了现成方法上。后来收响应侧重复时
只抽得动「机制」（gate / retry policy），抽不动「编排」（那些机制怎么串），
正是因为编排与 `Flux.defer` / `retryWhen` 绑在同一个方法体里、没有 `AttemptContext`
可以承载流级状态。

**为什么曾经看不出来**：包分好了，但 send 内部的控制流方向问题
**包重排改不动**（它是运行时结构，不是目录结构）。「两套扩展机制并存」是它的表层症状。

**修法（三步，全部已落地）**：
1. **发送前上移**：body 装配 + 头装配进主干（`prepareRequestBody` 在 `Flux.defer` **之前**调用，
   不碰 Reactor 结构）；
2. **内层 + 重试上移**：引入 **`AttemptContext`**（给流级状态一个显式的家），主干经
   `UpstreamCallRunner`（**无状态静态编排件**）组装 gate 编排 / 帧处理 / 落库 / `retryWhen`；
   三个执行器退化成「建 `AttemptContext` + 闭包 → 委托 runner」；
3. **编排块化 + 出站装配上移**：`RequestPipeline.execute` 拆成发送前块 / 发送后块，
   出站头与地址装配进发送前块（协议特定部分走 `outbound/` 支线）。

> **不要各做一半**：`retryWhen` 必须与内层编排同侧。若主干持内层而插槽持 `retryWhen`，
> 控制流又反过来。当前两者都在 runner 内，故安全。

> **执行器退化成薄适配器后，外部签名不变** —— 执行器 setter 零改动、测试子类零额外注入，
> 得益于 runner 无状态。

**不还的代价**：加第四个协议（如 Ollama）= 再写一个 ~900 行执行器，
正是当初启动重构要消除的痛点。

## 十三、其余轻微项

这些不影响行为，列出来是为了避免下次审查重复发现。

| 项 | 位置 | 说明 |
|---|---|---|
| 注释断言「本表没有触发器」 | `SchemaMigrationRunner.java:1114` | 与 `:1908-1909` 矛盾（V3 确实建了两个）。行为无害 —— V12 新表 DDL 的两条 CHECK 与那两个触发器逐条等价，且 `schema.sql` 从未声明它们，升级库因此收敛到新库形态。但错误前提会让下次改这张表的人不去检查触发器，而这是 SKILL.md 记的头号坑 |
| `ResponsesRequest` 注解冗余 | `ResponsesRequest.java` | 同时有 `@JsonIgnoreProperties(ignoreUnknown = true)` 与 `@JsonAnySetter`，后者已吃掉未知字段。无害 |
| `intIfPresent` 用 `asInt()` | `ResponsesUsageParser` | 超 `Integer.MAX_VALUE` 的 token 会截断。三侧一致，Responses 近期碰不到 |
| 弹窗协议勾选框只有 `title` | `Settings.vue:1202` | 缺 `aria-label`。抽屉侧同一控件反而有可见文字「启用」，两处不一致。这是本分支之前就有的形态 |
| 三行地址标签无 `for` | `Settings.vue:1361`、`:1390` | 点标签不聚焦到输入框。`n-input` 内部 id 拿不到，实践中用 `aria-labelledby` 或让 label 包住输入更合适 |
| diff 配对允许交叉 | `diff.ts` 的 `alignRange` | 贪心配对保证一对一但不保证顺序单调，两个元素在两侧顺序互换时渲染顺序会与原文相反。当前不可见（`DiffJsonNode` 不显示下标），且现有规则都不重排数组。若要收口，在 `candidates.sort` 后加一道「丢弃与已确定配对交叉的候选」 |

> **已核实、不需修的边界（留档以免重复调研）**：provider 层的 CONNECTED 事件
> 非流式传剥前缀的 `modelName`、流式传下游原始 `model`，两条线路的**重试规格同样如此**。
> 观感上「Toast 显示两个名字」**不会发生** —— `CallLifecyclePublisher.withDisplayModel`
> 在唯一出口按该调用的**首个事件**统一模型名（`RECEIVED` 由控制器同步发出，持有客户端原文），
> 因此 provider 层传什么都会被覆盖。`CallLifecyclePublisher` 的 Javadoc 已写明「不在
> provider 层补前缀是有意为之」。**故「两处统一用 `modelName`」这条旧建议已作废** ——
> 它改变不了任何可观测结果。

## 参考

- 分支审查的完整结论（含已修项）：[AGENTS.md](../../AGENTS.md) 各节不变量
- 迁移的强制流程：[数据库迁移 Skill](../../.github/skills/cosp-schema-migration-skill/SKILL.md)
- 请求/响应侧的跨协议契约：[请求侧](../features/protocol-translation/chat-messages/request-contract.md)、[响应侧](../features/protocol-translation/chat-messages/response-contract.md)

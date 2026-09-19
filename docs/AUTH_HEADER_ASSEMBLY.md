# 鉴权头的两个方向

> 本文档描述 COSP 出站与入站两个方向上「用哪个鉴权头」的现行设计。
> 出站自 schema V14 起由供应商级配置决定；入站自同一批改动起接受两种头。

## 0. 为什么出站头名不该由代码决定

出站鉴权头曾经**按上游协议**分派：`MESSAGES` 发 `x-api-key`，`CHAT` / `RESPONSES` 发
`Authorization: Bearer`。依据是「Anthropic 官方用 x-api-key」，实测不成立：

| 事实 | 说明 |
| --- | --- |
| Claude CLI 的头名由**凭据环境变量**决定，与协议无关 | `ANTHROPIC_API_KEY` → `x-api-key`；`ANTHROPIC_AUTH_TOKEN` → `Authorization: Bearer` |
| cc-switch 默认走后者 | 与 cc-switch 对接时下游发的是 `Authorization` |
| 部分中转站只认 `Authorization` | anyrouter 实测：改发 Bearer 后响应由 503 变 429（通过鉴权层、被配额挡住） |

根因不是「选错了那一档」，而是**这个选择本身不该由代码替用户做**。它与项目既有的
「会进入出站请求的参数都建模成 `{值, 模式}`」是同一条原则 —— 单个值无法表达
「下游自己做了选择怎么办」。

## 1. 出站装配

### 1.1 数据模型

`provider_config.auth_header`（V14 新增，纯加列，无表重建）：

```sql
auth_header TEXT NOT NULL DEFAULT '{"mode":"DOWNSTREAM","header":"AUTHORIZATION"}'
    CHECK (json_valid(auth_header))
```

JSON 形态 `{mode, header}`，**值用枚举名**而非头名字面量：

| 维度 | 取值 | 含义 |
| --- | --- | --- |
| `mode` | `DOWNSTREAM`（取下游） | 下游恰好带了一种就沿用那一种 |
| | `CONFIGURED`（取设置） | 始终用配置的 `header` |
| `header` | `AUTHORIZATION` | `Authorization: Bearer <供应商 key>` |
| | `X_API_KEY` | `x-api-key: <供应商 key>` |

用枚举名而非 `Authorization` 字面量的理由：头名大小写不敏感且存在拼写变体
（`X-Api-Key` / `x-api-key`），存字面量等于把「第几处写的是哪种拼写」变成配置的一部分。
头名字面量只在 `AuthHeaderSetting.Header.headerName()` 一处映射。

**`schema.sql` 的 DEFAULT、V14 迁移的 DEFAULT、`AuthHeaderSetting.DEFAULT_AUTH_HEADER_JSON`
三处必须逐字一致** —— 分叉会让「新库」「升级后的库」「保存过一次的供应商」在直接查库时
看起来是三种配置。前端 `DEFAULT_AUTH_HEADER_CONFIG` 同语义（小写字面量形态）。

### 1.2 行为矩阵

| 模式 | 下游恰好带 1 个 | 下游带 0 个或 2 个 |
| --- | --- | --- |
| **取下游** | 认下游选的那个头名，**值换成供应商 key** | **兜底**：用配置的 `header` |
| **取设置** | 用配置的 `header` | 用配置的 `header` |

「带 0 个或 2 个」都属异常输入，本服务兜底。因此**取下游模式下配置的 `header`
不是「用不上」，而是「异常时的兜底」** —— 界面上因此不灰显，只在模式段的 hint 里说明。

「下游带 2 个」是真实存在的：同时设了 `ANTHROPIC_API_KEY` 与 `ANTHROPIC_AUTH_TOKEN`
的客户端两个头都会发。此时随便取一个等于把「哪个头有效」的猜测搬回代码里。

### 1.3 执行顺序与实现要点

```
1. 探测下游带来的两种鉴权头
2. 按 模式 + header 决定目标头名
3. 删掉两种鉴权头（无条件）
4. 按目标头名注入供应商的 active key
5. …此后才是请求头规则层
```

**探测读 `downstreamHeaders` 参数，不读目标 headers。** 两者在当前调用形态下等价
（两个鉴权头都不在 `NON_FORWARDABLE_HEADERS` 里，`copyForwardableHeaders` 会覆盖进 target；
且所有调用点给的 target 都是新建的空 `HttpHeaders`），但读参数**不依赖那个前提** ——
不要求拷贝步骤先执行，也不会在将来某个调用点传入预置了鉴权头的 target 时误判。
省掉一条顺序依赖比省掉一个参数更值，因此**刻意不写**「会因调换顺序而失败」的用例：
写它等于把「顺序重要」固化下来，而目标恰恰是让顺序不再重要。

**空白值不算「带了」。** `HttpHeaders.containsHeader` 只判键在不在，`Authorization: ""`
也返回 `true`；用它会把一个空头当成「下游做了选择」，把取下游顶到「恰好一个」分支上。
故取 `getFirst(name)` 后判 `isBlank`（私有 `hasNonBlankHeader`）。

**「删两个、注一个」而非「只改选中那个头的值」。** 在「下游恰好带一个」时两者净效果相同，
但前者少一条分支，且天然保证没被选中的那一侧一定不带下游的值出站。下游凭据出站既有
泄露风险，也可能让严格上游因多余认证头拒绝。

**规则层保留最终决定权。** 装配在规则之前执行，需要双头并存的中转站可以用规则把另一个
加回来，需要非 Bearer 形态的可以用 `{apiKey}` 占位改写。这一分层不属于本次改动，
但改装配时不要把它挪到规则之后。

### 1.4 传解析后的对象，不传 JSON 原文

`applyHeaders` 的参数是 `AuthHeaderSetting` 而不是 `authHeaderJson` 字符串。
两者都是 `String` 时，调用点把 `headerRulesJson` 与 `authHeaderJson` 写反**能编译通过**，
症状是规则集被当设置解析（静默回默认值）、设置被当规则集解析（打一条 warn 后静默无规则）——
两条都不报错，只是行为悄悄不对。传类型让编译器挡住这一类。

```java
public void applyHeaders(HttpHeaders headers, HttpHeaders downstreamHeaders, String apiKey,
                         String headerRulesJson, boolean stream, AuthHeaderSetting authHeader)

// 无下游上下文的入口（模型拉取）
public void applyHeaders(HttpHeaders headers, String apiKey, String headerRulesJson,
                         AuthHeaderSetting authHeader)
```

`authHeader` 为 `null` 时兜 `AuthHeaderSetting.defaults()`。

### 1.5 写入严格、读取宽容

两处口径刻意相反，别顺手统一：

| 入口 | 认不出的值 |
| --- | --- |
| `ProviderAdminService`（表单写入） | 抛 `IllegalArgumentException` → 400，**不写库** |
| `AuthHeaderSetting.parse`（读库） | 回 `defaults()`（取下游 + Authorization） |

写入侧静默兜底的代价是把用户配置悄悄改掉、且发生在一次「看起来成功」的保存之后；
读取侧抛异常则会让一行脏数据打断整条聊天链路。两者要的都不是对方的答案。
另外，**表单未出现该字段 = 保留原值**（不是回默认值）—— 这条约定在整个项目里通用，
把它当成「清空」会让只改模型的保存路径抹平用户已配好的方式。

### 1.6 四个调用点

| 文件 | 线路 / 场景 |
| --- | --- |
| `AbstractUpstreamChatService` | CHAT |
| `GenericAnthropicChatService` | MESSAGES |
| `GenericResponsesChatService` | RESPONSES |
| `ProviderModelDiscoveryService` | 模型拉取（无下游） |

前三个直接 `AuthHeaderSetting.parse(provider.authHeaderJson(), objectMapper)`。
协议（`WireProtocol`）**不再参与**鉴权头决策 —— 只在请求体规则筛组、日志协议名与
`applyProtocolHeaders`（`anthropic-version` 等协议必需头）里继续使用。

模型拉取是唯一没有下游请求的调用点，因此也是唯一能验证「取下游模式下配置值仍生效」的
生产路径：两项探测皆为假，两种模式都落到配置的 `header`。它的鉴权配置与请求头规则同源，
都读 `ProviderConfigRow`；地址与协议则来自表单。这个不对称是既有约定，本次沿用 ——
且对鉴权头恰好严格正确：该设置的入口在新增/修改弹窗，拉取按钮在编辑抽屉，
抽屉里不存在「未保存的鉴权头」这种状态。

## 2. 入站（COSP 自身鉴权）

`GatewayAuthFilter` 保护三个聊天端点（`POST /v1/chat/completions`、`/v1/responses`、
`/v1/messages`），凭据载体**两个头都认**：

| 头 | 形态 |
| --- | --- |
| `Authorization` | `Bearer <key>`（大小写不敏感前缀） |
| `x-api-key` | 裸值，无 scheme 前缀 |

**判据是 OR 而非优先级。** 两个头都带而只有一个对时必须放行 —— 不能写成
「先看 `Authorization`，不匹配就拒」。该组合真实可达（同时设了两个环境变量，或请求经过
一层网关补了头），「向 COSP 证明身份」的本质是证明知道那把 Key，载体是哪个头无关。
提前返回会把一次合法请求判成 401，而排查时会看到「Key 明明是对的」。

**刻意不认 `Authorization: <裸密钥>`。** 它不是任何客户端的既有写法，认它只扩大接受面；
需要裸值形态的客户端用 `x-api-key` 即可。

**两个头各自只接受自己那一种形态**：`x-api-key` 里带 `Bearer ` 前缀不会被剥掉（会因多出
前缀而比对失败）。悄悄兼容会让「哪种写法有效」变得无法从代码读出，而混着用的请求本身
就说明配置有误。

### 2.1 与出站刻意不同

| | 出站 | 入站 |
| --- | --- | --- |
| 问题 | 该**发**哪个头 | 该**认**哪个头 |
| 形态 | 用户显式选一个 | 两个都认（OR） |
| 依据 | 发哪个头是对上游的**协议级陈述**，会改变报文语义；「取下游」遇到下游带两个头时无法判断意图，只能兜底 | 认哪个头**不改变任何出站内容**，因此没有歧义，也就不需要用户表态 |

两者结论相反不是表述方式的问题，而是问题本身不同。不要顺手对齐。

## 3. 前端

| 文件 | 职责 |
| --- | --- |
| `features/provider-config/authHeader.ts` | 模式/头名清单、文案、`parse`（两维各回自的默认）、`serialize`（大写枚举名）、`authHeaderValueState` |
| `components/settings/ModeScopedField.vue` | 泛型化为 `M extends string`，`labels` 与 `valueState` 由调用方给出 |
| `views/Settings.vue` | 控件位于高级设置里各线路地址块之后、请求头覆盖之前 |

**界面用小写字面量、落库用大写枚举名**（`downstream` / `authorization` 对
`DOWNSTREAM` / `AUTHORIZATION`）。这个不对称是后端定的 —— `DEFAULT_AUTH_HEADER_JSON`
是逐字节比对的常量，前端只能照抄，不能自作主张统一。单测用字面量
`BACKEND_DEFAULT_JSON` 钉住，不要改成「先序列化再反序列化」那种自证式断言。

**`parseAuthHeaderConfig` 两维各回自的默认**，不整体丢弃：脏 JSON 整份回默认，
某一维认不出只回退坏的那一维（`header` 脏不影响已选的 `mode`）—— 它们在界面上是两个
独立可点控件，用户只改坏一个。

抽屉侧（`editForm`）**不提供入口**，与 `useProxy` 现状一致：新功能只在新增/修改弹窗提供。
若要给抽屉也加，须同时改模型拉取的读库口径（§1.5 那个「严格正确」的理由会失效）。

## 4. 实测验证

### 4.1 四组配置 × 三种下游输入（2026-09-19）

上游 `[mimo-tokenplan] mimo-v2.5`（两种头都认），12 次调用全部 200 且各自独立：

| 组 | 配置 | 下游无头 | 下游带 `Authorization` | 下游带 `x-api-key` |
| --- | --- | --- | --- | --- |
| 1 | 取下游 + Authorization | 200 | 200 | 200 |
| 2 | 取下游 + x-api-key | 200 | 200 | 200 |
| 3 | 取设置 + Authorization | 200 | 200 | 200 |
| 4 | 取设置 + x-api-key | 200 | 200 | 200 |

> **这 12 次不能独立证明功能正确。** 上游两种头都认，所以即使装配永远只发一种头，
> 12 次也都会 200。真正有判别力的是调用日志请求头快照里的**头名**：该快照对敏感头只脱敏
> **值**（`snapshot.put(name, isSensitive ? "****" : value)`），**头名保留**，
> 且 `authorization` 会被规范化为 `Authorization`。因此展开任意一条记录即可看出
> 本次出站用的是哪种头。
>
> 对照应有结果：组 1 应为 `Authorization` / `Authorization` / **`x-api-key`**；
> 组 2 应为 `x-api-key` / `Authorization` / **`x-api-key`**；组 3、4 各三次恒定。
> 每组第 3 条（下游只带 `x-api-key`）是判别力最强的那条。

### 4.2 自动化测试

| 层 | 覆盖 |
| --- | --- |
| `AuthHeaderSettingTests` | parse / defaults / 脏值兜底 / `resolveHeader` 全矩阵（2 模式 × 2 方式 × 下游 4 态） |
| `ProviderRequestHeaderServiceTests` | 装配矩阵 11 条（含「配置那一维在取下游下不必然生效」两条、空白值口径、下游凭据不出站） |
| 三条聊天线 | 各一条接线用例，配 `CONFIGURED + X_API_KEY`（**不能配 Authorization** —— 它与默认值相同，漏接线也照样绿） |
| `ProviderModelDiscoveryAuthHeaderTests` | 拉取路径 6 条（该路径原先零覆盖） |
| `GatewayAuthFilterTests` | 23 条，含两种载体、OR 语义两个方向、两种形态边界、三个端点 |
| 前端 `authHeader.spec.ts` / `providers.spec.ts` | 纯逻辑与「未传即不发」 |

后端全量 1079 条。

## 5. 边界与刻意不做

**不做任何自动回退。** 上游不认那个头名时返回 401/403，本服务**不**换另一个头重试、
不按错误码降级、不按 base URL 猜测中转站类型。那等于把「哪个头有效」的猜测搬回代码里，
而且悄悄换过之后用户从界面和日志上都看不出发生了什么 —— 排查会指向凭据而非配置
（这正是 anyrouter 那次排查绕了好几轮的原因）。401 配上调用日志里的出站头名是完整
可自查的引导。与「不按模型名降级思考档位」同一条原则。

**存量出站形状变化（升级即生效）。** 默认值「取下游 + Authorization」相对改造前，
只有一类输入结果不同：**下游只带 `x-api-key`**（Claude Code 用 `ANTHROPIC_API_KEY` 时）
且供应商只认 `Authorization`（如 anyrouter）时，由通变成不通。

这条翻转是**功能的一部分**而非需要缓解的代价：401 可自查（日志里有出站头名）可自解
（改一下模式）。这类部署需把该供应商配成「取设置 + Authorization」。

> 曾以为「开启网关鉴权时下游必然带 `Authorization`（那是 COSP 自己的 key）」能缩小触发面，
> 该判断在入站同时接受 `x-api-key` 之后**已失效** —— 开启鉴权只保证下游带了**它用来认证的
> 那个头**，而那可能是任一个。

**不决定「发几个头」。** 需要双头并存这类诉求仍归请求头规则层表达，因为规则层在装配
之后执行并保留最终决定权。

## 6. 改动时容易踩的地方

- **三处默认值必须逐字一致**：`schema.sql`、V14 迁移、`AuthHeaderSetting.DEFAULT_AUTH_HEADER_JSON`。
- **测试夹具里的配置值要选与默认值不同的那一维**，否则「漏接线」与「接线正确」的断言
  无法区分（这是本项目反复踩到的一类）。
- **清理过期注释不能只 grep `TODO`。** 本特性落地时 `grep TODO(临时实现)` 已干净，
  但两处**方法级 Javadoc** 仍在描述「按出站协议装配 / 当前临时统一为 Bearer」。
  描述性注释没有统一标记，须加搜**被删掉那个机制的关键词**（如「按出站协议」）。
- **删 import 前先搜**：三个调用点里只有一个的 `WireProtocol` import 变成未用，
  另两个在规则筛组与日志协议名处仍需要它。
- **手写 DDL 的测试夹具会随每次加列而漏。** 加 `pc.auth_header` 到
  `loadProvidersWithModels` 的 SELECT 后，凡是用自建 `CREATE TABLE provider_config`
  的夹具都会以 `no such column: pc.auth_header` 硬失败。判据不是「有没有建
  `provider_config` 表」，而是「**会不会被那条 SELECT 读到**」—— 本次两处报错都属于
  好的一种；值得警惕的是「夹具建了全部列但少一个，而用例恰好没断言它」那种静默失配。
- **`ProviderConfigRow` / `ProviderRuntimeConfiguration` 的渐进式便捷构造器要沿用。**
  加字段时补一个「少一个参数」的重载传旧参 + 默认值，让只关心其它维度的调用点与夹具
  不必污染 diff。`ProviderConfigRow` 的重载传**空串**而非默认值：本 record 是数据库行的
  原文快照，不替消费者决定「空代表什么」（与 `maxOutputTokens` 的 null 原样传递同一条口径）。

## 7. 相关文档

| 主题 | 文件 |
| --- | --- |
| 供应商适配史与请求转换取舍 | [PROVIDER_ADAPTATIONS.md](./PROVIDER_ADAPTATIONS.md) |
| 已知技术债与刻意不做的取舍 | [KNOWN_DEBT.md](./KNOWN_DEBT.md) |
| anyrouter 的供应商行为调查 | [ANYROUTER_INVESTIGATION.md](./ANYROUTER_INVESTIGATION.md) |

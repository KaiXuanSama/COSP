# Responses 协议直连实施计划

> **状态**：已完成（2026-09-25，阶段三落地）
>
> **前身文档**：本功能早期写过一份 `implementation.md`（写于 schema V11、
> 给「另一台机器上的自己」的实施说明），其中协议改名方案（`CHAT_COMPLETIONS` 命名）
> 后来被 [PROTOCOL-RENAME-PLAN.md](../protocol-translation/PROTOCOL-RENAME-PLAN.md)
> 的 `CHAT` 方案取代，阶段二/三的内容也已并入本文 §4、§5。该文档已删除，
> 其有效结论全部沉淀在本文与前述改名计划中。

## 0. 本文档的性质

**计划文档，写作时曾约定不进仓库**（与当时的 `PROTOCOL_RENAME_PLAN.md` 同一约定）。
本分支起改为入库留档：决策与其理由必须写清 —— 实现期真正需要回查的是「当初为什么这么定」。
文中的行号、测试数等快照数字以写作时为准。

范围：为 COSP 自身增加 OpenAI **Responses API** 直连能力（下游打 `/v1/responses`，
上游走供应商的 Responses 端点，报文原样透传）。**不含** C2R 请求翻译与 R2C 响应翻译，
那是后续独立的一步，调研结论另见对话记录。

## 1. 版本号事实

当前 `SchemaMigrationRunner.CURRENT_SCHEMA_VERSION = 12`。V12 是上一步的「线路协议按 API
路径全称重命名」（`OPENAI → CHAT`、`ANTHROPIC → MESSAGES`）。**本次要写的是 V13。**

V12 留下的一笔红利：它重建 `api_call_log` 时已把 `RESPONSES` 写进两个协议列的 CHECK 白名单，
当时的理由是「多一个合法值零成本，而重建这张日志表两次有实际风险」。因此 **V13 不需要碰
`api_call_log`**。

## 2. 阶段划分与提交边界

三个阶段各自可独立提交、独立验证：

| 阶段 | 内容 | 落地后的系统行为 |
|---|---|---|
| 一 | V13 迁移：加列 + 回填协议集合 | 新列无人读取，但协议集合已含 `RESPONSES`；`ProviderProtocolSupport` 会忽略这个未知名并 warn |
| 二 | `WireProtocol.RESPONSES` + 读写链路 + 前端 | 地址可配、协议可勾选；调度器认得它，但没有执行器 |
| 三 | `/v1/responses` 端点 + 上游直连 | 完整可用 |

**阶段一落地后会刷 warn**，这是已知的中间态：`ProviderProtocolSupport.parse` 对未知协议名
是「忽略并 warn」而非报错，所以 V13 写入的 `RESPONSES` 会被逐个忽略。集合里还有 CHAT 与
MESSAGES，因此不会落到「没有可识别协议 → 回退全集」那条路，两条现有线路行为不变。

若不接受这段 warn，可把阶段一与阶段二合并提交。但**顺序不能倒** —— 先加枚举值再迁移，
中间态下前端能勾选一个数据库尚未接受的值。

## 3. 阶段一：V13 迁移

### 3.1 前置：冻结 V12 的版本号常量

`migrateToV12ProtocolRename` 当前用 `CURRENT_SCHEMA_VERSION` 写版本号。把常量提到 13 之后，
**V12 会把版本直接跳到 13，V13 静默永不执行**。

这是上一轮踩过的坑的精确重演（`migrateToV11ProviderProxy` 原本也这么写，V12 开发期才发现）。
所以第一步必做：

```java
private static final double V12_VERSION = 12;
private static final double CURRENT_SCHEMA_VERSION = 13;
```

把 `migrateToV12ProtocolRename` 内部与 `historicalMigrations()` 里的 `CURRENT_SCHEMA_VERSION`
引用改为 `V12_VERSION`。

> 结构性问题：每加一个版本就要手工冻结上一个，漏一次就是静默跳版。已连续踩两次。
> 值得考虑让 `MigrationStep` 自己持有版本号并由框架统一写库，但那是独立重构，
> 不在本次范围内 —— 顺手做会让 V13 的 diff 混进一堆与 Responses 无关的改动。

### 3.2 新增列

纯 `ADD COLUMN`，不需重建表（与 V11 同类）：

```java
addColumnIfNotExists("provider_config", "responses_base_url", "TEXT NOT NULL DEFAULT ''");
```

回填 `base_url` 原值，只针对 NULL 与空串（照抄 V8.8 的 `anthropic_base_url` 回填）：

```sql
UPDATE provider_config SET responses_base_url = base_url
WHERE (responses_base_url IS NULL OR trim(responses_base_url) = '')
  AND base_url IS NOT NULL AND trim(base_url) <> ''
```

**回填理由与 V8.8 不同，Javadoc 不能照抄。** V8.8 有两条理由：一是「升级前的行为正是用
base_url 拼 /messages，照抄原值才能让行为保持不变」，二是界面可读性。第一条在这里**不成立**
—— 升级前根本没有 Responses 线路，不存在要保持的行为。只剩第二条：显式写入让用户在界面上
直接看到当前生效的地址，而不是一个空输入框加一句「留空则复用」。这一条足够支撑回填，
但把 V8.8 那段说辞抄过来就是写了个假理由。

### 3.3 回填协议集合

**存量供应商一律追加 `RESPONSES`。**

理由是项目自己的既有原则，前端 `DEFAULT_NEW_PROVIDER_PROTOCOLS` 的注释已经写下来了：

> 多数中转站的 Anthropic 端点与 OpenAI 同源，勾上后不通至多是上游报错，
> 而默认不勾会让「明明支持却调不通」变成需要用户自己发现的问题。

同一条逻辑适用于 Responses：默认勾上 → 用户打 `/v1/responses` → 不支持就报错 → 用户感知到
并取消勾选。**重点是用户可感知。** 默认不勾则让「支持却调不通」变成需要用户自己想到去勾的
隐藏状态，那是更差的失败模式。附带好处：全勾让阶段三的手动验证不需要先去后台改配置。

追加**不会影响两条现有线路**，这是接受该方案的关键支撑。调度规则 1 是「同名协议优先直连」：

- 下游打 `/v1/chat/completions` → 规则 1 命中 CHAT → 直连，不受影响
- 下游打 `/v1/messages` → 规则 1 命中 MESSAGES → 直连，不受影响
- 下游打 `/v1/responses` → 规则 1 命中 RESPONSES → 直连 → 供应商没这个端点就 404（预期）

#### 三类值的处理口径

| 存量值 | 处理 | 理由 |
|---|---|---|
| 非空数组（`["CHAT","MESSAGES"]`、`["CHAT"]` 等） | 追加 `RESPONSES` | 用户至少启用了一条线路，说明这个供应商在用 |
| 显式空数组 `[]` | **不动** | 用户主动声明「哪条线路都不要」，往里塞值是迁移在改用户意图 |
| 脏数据 / 非数组 | 不动 | 运行时本就回退全集（已含 RESPONSES），迁移不必也不该猜 |

空数组那条是硬约束。`schema.sql` 与 `ProviderProtocolSupport` 的注释都强调它是「显式的非法
配置，调度器会明确报错而非静默回退」，其全部价值在于**可被发现**。补一个 RESPONSES 会把一个
用户主动禁用的供应商变成部分可用，而用户不会知道自己的配置被改了。

#### 实现

SQL 能完整表达，不需要像 V8.9 那样逐行读改：

```sql
UPDATE provider_config
SET supported_protocols = json_insert(supported_protocols, '$[#]', 'RESPONSES')
WHERE json_valid(supported_protocols)
  AND json_type(supported_protocols) = 'array'
  AND json_array_length(supported_protocols) > 0
  AND NOT EXISTS (
    SELECT 1 FROM json_each(supported_protocols) WHERE value = 'RESPONSES'
  )
```

`json_insert(x, '$[#]', v)` 是 SQLite 的数组追加惯用法。`NOT EXISTS` 子句保证幂等
（重跑迁移不会追加两次）。JSON1 扩展可用 —— CHECK 约束里已在用 `json_valid`，
但 `json_each` / `json_type` / `json_insert` 要在实现时确认同样可用。

**追加到末尾恰好保持字母序是巧合**，因为 `RESPONSES` 的首字母 R 排在 CHAT 与 MESSAGES 之后。
注释里要点明这一点，否则将来加第四个协议时会有人以为「追加就行」。之所以在意字母序，
见 §6。

### 3.4 `schema.sql` 的 DDL 默认值

```sql
supported_protocols TEXT NOT NULL DEFAULT '["CHAT","MESSAGES","RESPONSES"]'
    CHECK (json_valid(supported_protocols)),
responses_base_url  TEXT NOT NULL DEFAULT '',
```

默认值用**字母序**，与 `ProviderAdminService.parseSupportedProtocols` 的 `TreeSet` 落库口径
一致 —— 否则「新建的供应商」和「保存过一次的供应商」在库里的字符串顺序不同，
直接查库对比时看起来像 bug。

`responses_base_url` 的列注释要写清它与 `anthropic_base_url` 的区别：后者独立成列是因为
「中转站的 Anthropic 端点位置不可预测」；Responses 端点同理，且多数中转站**根本没有**
这个端点，所以留空回退 `base_url` 是常态而非例外。

### 3.5 测试

`SchemaMigrationRunnerTests` 按 skill 要求的四条补：

1. 历史递归升级（从早期版本一路升到 13）
2. V12 → V13 直接前驱升级
3. 空库基线含 `responses_base_url` 且 `supported_protocols` 默认含 RESPONSES
4. 不重放（已是 V13 的库再跑一次不变化）

另需三条专项，对应 §3.3 的三类值：

5. `["CHAT","MESSAGES"]` → `["CHAT","MESSAGES","RESPONSES"]`
6. **`[]` 保持 `[]`**（这条最重要，钉住「不改用户意图」）
7. 已含 RESPONSES 的行不重复追加

第 6 条要写成断言 `isEqualTo("[]")` 而不是 `doesNotContain("RESPONSES")` —— 后者在
`[]` 被改成 `["CHAT"]` 这种错误下仍然通过。

### 3.6 阶段一明确不做

- 不加 `WireProtocol.RESPONSES` 枚举值（归阶段二）
- 不碰 `api_call_log`（V12 已预留白名单）
- 不碰 `provider_request_transform.body_rules_json` 里的组 `protocols`。规则组的协议字段
  「缺失视为全协议」，因此存量规则组会自动适用于新协议 —— 这是既有语义的自然结果，
  不是遗漏。往里追加 `RESPONSES` 反而会把「未声明」这个状态破坏掉。

## 4. 阶段二：枚举加值与读写链路

### 4.1 两处「两个协议」硬假设

加第三个枚举值会让两处代码从「正确」变成「悄悄错」，且都不报错。

#### ① `ProviderProtocolSupport.OPTIMISTIC_ALL` —— 改理由，不改实现

```java
private static final Set<WireProtocol> OPTIMISTIC_ALL = EnumSet.allOf(WireProtocol.class);
```

在 §3.3 选择「全勾」之后，这个 `allOf` **重新自洽了**：DDL 默认值、迁移回填、解析失败回退
三处口径统一，都是全集。所以实现保持不变，`allOf` 反而比硬编码三个值更好（少一处需要同步）。

但它的 Javadoc 现在写着：

> 纯粹是让常量与枚举保持同源，不为「将来可能有第三种协议」做准备 —— OpenAI 与 Anthropic
> 已是事实标准，第三种协议出现的概率极低。

**这个事实判断已被推翻，必须重写。** 新理由：三处口径统一，`allOf` 是那个统一口径的表达，
而非「不做准备」的副产品。这是纯注释改动，但不改会留下一句与代码现状矛盾的话 ——
过期的理由比没有理由更误导。

#### ② `ProtocolDispatchManager` 规则 2 遍历 `values()`

`WireProtocol` 的 Javadoc 自己预警过：

> 声明顺序不承载语义……加入第三种协议后必须改用独立声明的回退序常量，否则「挑哪个上游协议」
> 会变成受本文件常量书写顺序摆布的隐式行为。

落地这个常量，建议顺序 **CHAT → MESSAGES → RESPONSES**：

- CHAT 兼容面最广，翻译损耗最小
- MESSAGES 已有一个方向实现（C2M 去程 + M2C 回程）
- RESPONSES 排最后：C2R/R2C 均未实现，且它字段最富，往回翻必然丢信息
  （`reasoning.encrypted_content` 在 Chat 里无处安放）

> **后记（2026-10-04）**：本节写就时 C2R/R2C 尚未实现；二者现已双向落地并实机验证
> （[R2C-PLAN.md](../protocol-translation/chat-responses/R2C-PLAN.md)），上列末条的
> 「必然丢信息」已被 §7.4 的 reasoning item 明文往返闭环解决。「已实现优先」的排序原则不变。

验证这个顺序合理：供应商勾了 MESSAGES + RESPONSES、下游打 chat 时，会挑 MESSAGES
（当时 C2M 已实现）而非 RESPONSES（当时未实现）。**挑一个已实现的方向优于挑未实现的**，
顺序在这个场景下有实际差别。

放在 `ProtocolDispatchManager` 还是 `WireProtocol`？倾向前者：它是调度策略而非协议属性，
放在枚举里会让人以为声明顺序又有语义了。

### 4.2 后端逐点清单

对照 `anthropic_base_url` 的完整落地路径：

| 文件 | 改动 |
|---|---|
| `WireProtocol` | 加 `RESPONSES` 常量 + Javadoc |
| `ProviderConfigRow` | +1 参数 `responsesBaseUrl` |
| `ProviderConfigRepository` | SELECT 拼串、rowMapper、内部 Builder、`updateProviderProtocols` 加第三个「null 表示不改」参数 |
| `ProviderRuntimeConfiguration` | +1 字段 + `resolveResponsesBaseUrl()`；三个便利重载顺延 |
| `DatabaseRuntimeProviderCatalog` | 映射新字段 |
| `ProviderAdminService` | `SUPPORTED_PROTOCOLS` 加 `"RESPONSES"`、表单读 `responsesBaseUrl`、`buildProviderView` 输出 |
| `OutboundProxyTargetProjector` | **第三个端点也要投影** |

`OutboundProxyTargetProjector` 最容易漏。它的类注释写着「只投 base_url 会让 Anthropic 直连
线路漏掉代理」—— 同一句话现在对 Responses 成立。漏了的症状：用户开了代理，Responses 线路
却直连出去，另两条正常，看起来像代理时好时坏。

`updateProviderProtocols` 的三参签名已经开始难读（三个都是「null 表示不改」）。可以考虑
换成一个入参 record，但那会波及三条保存路径与它们的测试，**建议本次先加参数**，
留待将来真有第四个字段时一起改。

### 4.3 恰好正确、需测试钉住

两处不需改动，但依赖「Responses 属于 OpenAI 系」这个隐含事实：

| 位置 | 现有判断 | Responses 走向 |
|---|---|---|
| `ProviderRequestHeaderService.applyAuthenticationHeaders` | `if (upstream == MESSAGES) 写 x-api-key else 写 Authorization` | else 分支 → `Authorization: Bearer` ✓ |
| `ProviderModelDiscoveryService.applyProtocolHeaders` | `if (protocol != MESSAGES) return` | 直接 return，不发 `anthropic-version` ✓ |

两处都是「恰好对」，所以要补测试。否则将来有人改那个 `if`（比如改成 `switch`）时，
不会知道第三条线路依赖它现在的形状。

### 4.4 前端

`frontend/src/types/protocol.ts`：加 `'RESPONSES'`、`WIRE_PROTOCOL_LABELS` 加
`'Responses API'`、`WIRE_PROTOCOL_ABBREVIATIONS` 加 `'R'`。

`protocol.spec.ts` 里那条钉住 `RESPONSES → 'R'` 的测试语义会变（从「走 `charAt(0)` 兜底」
变成「走表查找」）。**要改写而不是删除** —— 兜底路径仍需覆盖，换一个真正未知的协议名即可。

`protocolUrls.ts` 有一处真实的设计变化：

- `DEFAULT_NEW_PROVIDER_PROTOCOLS` 加 `RESPONSES`（与后端 DDL 默认值一致）
- `shouldMirrorOnFocus` + `mirrorAnthropicBaseUrl` 这套「联动」现在是 OpenAI→Anthropic
  单向单路，加第三路后变成一对多。建议把 `mirrorAnthropicBaseUrl` 泛化改名为
  `mirrorBaseUrl(source, mirroring, current)`（实现零变化），调用方各持一个 `mirroring` ref
- `RESPONSES_ENDPOINT_SUFFIX = '/responses'`
- `resolvePrimaryProtocol` / `orderProtocolRows` 从两路扩到三路
- `resolveModelPullTarget` 顺序改为 **CHAT → RESPONSES → MESSAGES**。RESPONSES 提到 MESSAGES
  之前，因为它与 CHAT 同为 OpenAI 系：同一个 `GET /v1/models`、同一个 `Authorization` 头、
  不需要 `anthropic-version`。地址为空时回退 `base_url`，与后端 `resolveResponsesBaseUrl`
  同口径

`Settings.vue`：三条保存路径（新建弹窗、改名弹窗、编辑抽屉）各加一个协议开关 + 一个地址框。

### 4.5 测试夹具的手写 DDL

四个测试文件手写了 `CREATE TABLE provider_config`：

- `ProviderRequestBodyTransformationIntegrationTests`
- `ProviderRequestTransformServiceTests`
- `ProviderConfigRepositoryQueryTests`
- `RepositoryUpsertTests`

rowMapper 读新列后，这些夹具都要加 `responses_base_url`，否则 `no such column`。
这是加列时必然撞上的一处，先列出来免得当成意外。

## 5. 阶段三：接线

### 5.1 结构：与 Anthropic 平级，不继承 `AbstractUpstreamChatService`

`GenericAnthropicChatService` 的类注释给了先例：

> 两种协议的 Reactor 链体结构不同……若强行抽公共父类，那两个方法会退化成一堆钩子 ——
> 模板方法反而让子类作者看不见自己正在依赖什么。

Responses 与 Chat 的差异（事件类型驱动的多事件流 vs 单一 chunk 序列 + `[DONE]`）更接近
Anthropic 与 Chat 的差异，而不是 Chat 的一个变体。且空响应判定要新写
`ResponsesContentDetector` —— `OpenAiContentDetector` 按 `choices[].delta` 解析，
对 Responses 完全不适用。

所以 `GenericResponsesChatService` 与另两个平级。代价同 Anthropic：接线顺序第三次写一遍。
这是有意接受的，理由与那边相同。

### 5.2 新增文件

| 文件 | 要点 |
|---|---|
| `protocol/openai/ResponsesRequest` | 仿 `AnthropicMessagesRequest`：只建模 `model` / `stream` + `@JsonAnySetter` 收其余；`@JsonIgnoreProperties(ignoreUnknown = true)` 必需。`input` 用 `Object`（可以是字符串或数组），理由同 `system` |
| `api/openai/ResponsesController` | `POST /v1/responses`，流式 / 非流式两条 |
| `application/openai/ResponsesService` | 仿 `MessagesService`：**方法体裹 `defer`** + 路由 + 调度 + `translationNeeded()` 抛 `ProtocolTranslationNotSupportedException` |
| `provider/generic/openai/GenericResponsesChatService` | 直连执行、重试、落库、usage |
| `provider/generic/openai/ResponsesContentDetector` | 空响应判定 |
| `provider/generic/openai/ResponsesUsageParser` | usage 提取，见 §5.6 |

`defer` 那条不是可选的。`ChatDispatchErrorSignalTests` 钉住了这一点：路由、调度、翻译都是
同步调用，而控制器的 `Mono.firstWithSignal(service.xxx(...), cancelSignal)` 参数 eager 求值
—— 不包 defer 时异常在组装期就逃出控制器方法，`onErrorResume` 不在链上，下游拿到 WebFlux
默认 500（流式也不是 SSE error 帧）。新端点要有同类测试。

### 5.3 安全：`GatewayAuthFilter.PROTECTED_PATHS` 加 `/v1/responses`

**不能漏。** 漏了等于新开一个绕过下游鉴权的聊天入口 —— 真实的安全缺口，且在未开启下游鉴权
时完全无症状，只有开启后才暴露。该字段的注释已经写了「将来若再有新的聊天端点，加一行即可」。

### 5.4 终止事件不止一个

Anthropic 只有 `message_stop`，Responses 有一串。调研中 sub2api 的清单可直接用：

```
response.completed | response.done | response.incomplete
response.failed | response.cancelled | response.canceled | error
```

`cancelled` / `canceled` 两种拼法都要收（sub2api 注释说明有上游只发其中一种）。

SSE 的 `event:` 类型回填走 `AnthropicController` 那套 `extractEventType`（从事件 JSON 的
`type` 字段取），Responses 与 Anthropic 同类，与 Chat 不同。

### 5.5 两处必须现在就定的取舍

#### ① `max_output_tokens` 不接

AGENTS.md 已有明确记载：`MaxOutputTokensSetting` 只有 Anthropic 线路消费，因为那条线路
`max_tokens` 必填，不补就发不出去；OpenAI 线路刻意不接，因为那边该字段可选，接上会给所有
「下游没带」的调用凭空补一个上限。

Responses 的 `max_output_tokens` **同样可选**，所以按同一条原则**不接**。

这一点很容易顺手接上（名字就叫 `max_output_tokens`，看起来天造地设），
所以代码里要写清为什么不接，而不是留个空白让人补。

#### ② 思考深度：`Off` 档终于有原生表达

Responses 用 `reasoning.effort`，而调研已一手核验它**支持 `"none"`**
（`new-api/relaykit/relayconvert/reasoning/intent.go:15` 的 `EffortNone Effort = "none"`，
且 `ParseEffort` 接受它）。

因此 R 线路的 `Off` 档直接发 `reasoning.effort: "none"`，**不必**像 OpenAI Chat 侧那样借
`thinking: {"type": "disabled"}` 这个方言。给 `ReasoningEffortSetting` 加第三个
`applyToResponses`，三条线路各写各的出站字段：

| 线路 | 方法 | 出站字段 | `Off` 档 |
|---|---|---|---|
| CHAT | `applyTo` | `reasoning_effort` | `thinking: {"type":"disabled"}` |
| MESSAGES | `applyToAnthropic` | `output_config.effort` | 同上（Anthropic 五档无「不思考」） |
| RESPONSES | `applyToResponses` | `reasoning.effort` | `reasoning.effort: "none"` |

四档注入模式（override / fallback / passthrough / delete）语义不变，三条线路共用同一列。
「下游已表态」的判据取 `reasoning.effort` 单字段 —— Responses 里没有与 `thinking` 并列的
第二个开关字段，不需要像 CHAT 侧那样取并集。

**不做自动降级**：`reasoning.effort` 的档位清单与 COSP 的七档不完全重合，不认识的档位原样
发出，由上游用错误码回答。这与现有原则一致。

#### ③ Anthropic 思考方式不接

`AnthropicThinkingSetting` 只服务 MESSAGES 线路，Responses 侧没有对应概念
（`thinking` 对象是 Anthropic 的形态）。不接。

### 5.6 usage：新 parser，但换算是「直接搬」

```
prompt_tokens     = input_tokens                            ← 本身就是总输入
completion_tokens = output_tokens
cached_tokens     = input_tokens_details.cached_tokens      ← input_tokens 的子集
```

**不要照抄 Anthropic 的加法。** `AnthropicUsageParser.toTokens` 必须
`input + cache_read + cache_creation`，是因为 Anthropic 把输入拆成三个互斥量；
OpenAI 系不拆，`input_tokens` 已含缓存部分。照抄会让缓存 token 被重复计入。

三个 token 列的口径不变（`prompt_tokens` 含缓存命中与写入，`cached_tokens` 只是命中部分），
因此前端 `cacheHitRate.ts` 的 `cached / prompt` 不需要加协议分支 —— 这正是那个口径「跨协议
固定」的价值。

`usage_raw` 存上游原文。跨事件合并规则参照 Anthropic 侧：三个 token 列可跨事件合并
（只有正数才覆盖），`usage_raw` 永远挑一份完整原文而非拼字段。

## 6. 四种顺序互不相同，不要「统一」

加第三个协议后出现四个顺序，其中三个独立：

| 顺序 | 载体 | 值 | 依据 |
|---|---|---|---|
| 落库 JSON 元素序 | `ProviderAdminService` 的 `TreeSet` | 字母序：CHAT, MESSAGES, RESPONSES | 已有实现决定；DDL 默认值须与它一致 |
| 展示 / 勾选序 | 前端 `ALL_WIRE_PROTOCOLS` | CHAT, RESPONSES, MESSAGES | 语义：两个 OpenAI 接口相邻 |
| 翻译回退序 | `TRANSLATION_FALLBACK_ORDER` | CHAT, MESSAGES, RESPONSES | 见 §4.1②，按「已实现优先」排 |
| 枚举声明序 | `WireProtocol` | CHAT, RESPONSES, MESSAGES | **不承载语义**，仅为可读性取语义序 |

第一个与第三个数值上相同但**理由完全无关**，将来可能分叉。第四个的 Javadoc 已明确声明
不承载语义，不要因为它与展示序相同就去依赖它。

这一节要写进代码注释（至少在 `TRANSLATION_FALLBACK_ORDER` 旁边），否则将来必有人来「统一」。

## 7. 明确不做的事

- **不做 C2R 请求翻译与 R2C 响应翻译。** 独立的下一步。
- **不接 `previous_response_id` / `store` / `conversation`。** 直连路径下这些字段由
  `@JsonAnySetter` 原样透传，COSP 不解释也不缓存。它们只在跨协议翻译时才成为问题
  （调研：new-api 直接报错拒绝，cc-switch 建了 ~900 行跨请求缓存）。
- **不按模型名猜测能力。** 调研发现 sub2api 按 `strings.HasPrefix(model, "gpt-5")` 剥离
  `temperature` / `top_p`，因为 Responses 会对 reasoning 模型报
  `Unsupported parameter: temperature`。这个上游事实是真的，但按模型名前缀硬编码与 COSP
  原则冲突（中转站改名即失效）。原样发出，让上游用错误码回答。
- **不加响应侧 DTO。** 响应全程 `String` 透传，与 Anthropic 侧同一决策：
  「对上游格式差异免疫是当前的优势，引入响应 DTO 等于加一道上游字段必须符合预期结构的约束」。
- **不动 `/api/*` Ollama 发现链。** Responses 不参与模型发现协议。

## 8. 遗留与待确认

- `docs/PROTOCOL_RENAME_PLAN.md` 仍未跟踪。按本文档 §0 的约定，它与本文件都不进仓库；
  实施完成后若要留存结论，另写总结文档。
- ~~§3.3 的 SQL 依赖 `json_each` / `json_type` / `json_insert`，实现时需确认可用。~~
  **已验证可用**（阶段一实施时实测通过）。
- §4.2 提到 `updateProviderProtocols` 的三参签名已开始难读，本次先加参数，留待第四个字段。
- §3.1 提到「每加版本手工冻结上一个」是结构性问题，已连续踩两次，值得独立重构但不在本次范围。

## 9. 阶段一实施记录

**状态：已完成。** 后端 825 个测试全通过（原 820 + 新增 5），BUILD SUCCESS。

### 9.1 落地内容

| 文件 | 改动 |
|---|---|
| `SchemaMigrationRunner` | 冻结 `V12_VERSION`、`CURRENT_SCHEMA_VERSION = 13`、`V13_RESPONSES_PROTOCOL` 常量、`migrateToV13ResponsesProtocol` + 两个辅助方法、基线描述文案 |
| `schema.sql` | `supported_protocols` DEFAULT 加 `RESPONSES`、新增 `responses_base_url` 列 |
| `SchemaMigrationRunnerTests` | 新增 5 个用例、新增 `seedDatabaseAtVersion` 与两个读取辅助、修正 3 处受影响的存量断言 |

### 9.2 计划外发现一：回填端点也会被触发器挡住

Plan §3.3 只提到「追加协议的 UPDATE 会撞上行级校验触发器」，实现时我照此只在
`appendResponsesProtocolForV13` 里摘装触发器 —— **漏了回填端点那条 UPDATE 同样是 UPDATE**。
`v13SurvivesRowLevelValidationTriggerWithLegacyDirtyEnabledValue` 立刻报
`SQLITE_CONSTRAINT_TRIGGER`，栈顶正是 `backfillResponsesBaseUrlForV13`。

修法：把摘装提升到 `migrateToV13ResponsesProtocol` 一层，包住两条 UPDATE。

**这比「两处各摘一次」更对，不只是省事**：摘装配对只有一处、不会漏，而且
「本迁移的所有写入都要绕开行级校验」本身就是迁移级的事实，不是某一条 UPDATE 的局部细节。
将来 V13 再加第三条 UPDATE 时不需要重新想起这件事。

> 教训：这个坑在 V7.1、V9、V12 已踩过三次，plan 里也写了，但我把它记成了「那条 UPDATE 的
> 属性」而不是「这张表的属性」。**判断该不该摘触发器的依据是「本次迁移是否 UPDATE
> `provider_config`」，而不是「哪一条语句」。**

### 9.3 计划外发现二：`seedDatabaseAtPreviousVersion` 会随新版本自动前移

测试夹具 `seedDatabaseAtPreviousVersion` 取注册表的**倒数第二项**，这个设计的原意是
「新增迁移时本方法无需修改」，对「最新那个迁移是唯一该跑的」这个不变量确实成立。

但加入 V13 后它从 V11 前移到 V12，而有两个用例需要的恰恰是 **V11 的形态**：

- `v11DatabaseRenamesProtocolLiteralsEverywhereDuringV12Migration` 要写入 `'OPENAI'` /
  `'ANTHROPIC'` 字面量 —— 那些值在 V12 之后就撞 CHECK 白名单了，**会直接报错**；
- `crossVersionUpgradeKeepsProtocolOfTruncatedOpenAiLogWrittenAfterV86` 的锋利度本身就
  依赖「缺失的迁移恰好是 V12 重命名」（见该方法注释），前移会让那一层证明**静默失效**。

修法：新增 `seedDatabaseAtVersion(jdbcTemplate, version)`，让这两个用例显式钉住 11，
并在 Javadoc 里写清「自动前移是特性，但需要特定版本形态的用例必须写死版本」。
`seedDatabaseAtPreviousVersion` 保留并委托给它。

**这类夹具将来还会再咬一次**：任何「构造某个历史版本的形态」的用例都不能用自动前移的夹具。
判据是：用例里是否出现只在某个版本区间内合法的字面量或结构。

### 9.4 三处存量断言的修正

都是「跑完整迁移链、断言终态」的用例，V13 追加 RESPONSES 后终态变了：

| 位置 | 改动 |
|---|---|
| 空库基线用例的列清单 | 加 `responses_base_url` |
| V8.8 回填用例 | `["CHAT","MESSAGES"]` → `["CHAT","MESSAGES","RESPONSES"]` |
| V12 主用例的协议集合 | 同上，并加注释说明尾部 RESPONSES 来自紧随其后的 V13 |

**规则组的 `protocols` 不受影响**（V8.7 用例断言的 `["CHAT"]` 保持不变），这验证了
plan §3.6「不碰 `body_rules_json`」的决定是自洽的 —— 那里的「未声明即全协议」语义让存量
规则组自动适用于新协议，不需要迁移介入。

### 9.5 已知中间态（符合预期，不修）

`ProviderProtocolSupport` 现在会对库里的 `RESPONSES` 刷 warn，因为枚举值还不存在。
集合里还有 CHAT 与 MESSAGES，所以不会落到「回退全集」那条路，两条现有线路行为不变。
阶段二加枚举值后这条 warn 自然消失。

## 10. 阶段二（后端部分）实施记录

**状态：后端已完成，前端未动。** 后端 834 个测试全通过（阶段一 825 + 新增 9），BUILD SUCCESS。

### 10.1 落地内容

| 文件 | 改动 |
|---|---|
| `WireProtocol` | 加 `RESPONSES` 常量；改写「声明顺序」那段（已落地回退序常量）；补 CHAT/RESPONSES 同属 OpenAI 但是两个接口的说明 |
| `ProtocolDispatchManager` | 新增 `TRANSLATION_FALLBACK_ORDER`，替换 `values()` 遍历 |
| `ProviderProtocolSupport` | `allOf` 实现不变，重写那段被推翻的理由；三处「两种协议」措辞改为「全部协议」 |
| `ProviderConfigRow` | +1 参数 `responsesBaseUrl` |
| `ProviderConfigRepository` | SELECT、rowMapper、Builder、`updateProviderProtocols` 加第三个「null 不改」参数 |
| `ProviderRuntimeConfiguration` | +1 字段、`resolveResponsesBaseUrl()`、默认全集改为三元素、三个便利重载顺延 |
| `DatabaseRuntimeProviderCatalog` | 映射新字段 |
| `ProviderAdminService` | 白名单加 `RESPONSES`、表单读 `responsesBaseUrl`、视图输出、`parseProtocolsForView` 回退值改用 `values()` |
| `OutboundProxyTargetProjector` | 第三个端点投影 + 抽出 `fallbackToBaseUrl` |
| `ProviderModelDiscoveryService` | 仅 Javadoc：写清「排除式判断」为何让新 OpenAI 系协议自动落对 |
| `ProviderRequestHeaderService` | 仅 Javadoc：同上 |

### 10.2 计划外发现：既有用例遍历 `values()` 而恰好通过

`ProtocolDispatchManagerTests.bothProtocolsSupportedStillPrefersSameNameOverTranslation`
在加枚举值后失败：

```
[下游 RESPONSES 在两种协议都支持时应直连] Expecting value to be false but was true
```

**这不是回归，是用例本身的缺陷被暴露。** 它的命题是「同名优先」，只对供应商**声明支持**的
协议成立；而它遍历的是 `WireProtocol.values()`。当时勾选集恰好等于全集（`["CHAT","MESSAGES"]`），
所以两者无法区分 —— 第三个协议一加入，巧合消失。

修法：遍历范围改为 `ProviderProtocolSupport.of(provider)`，并改名为
`everySupportedProtocolPrefersSameNameOverTranslation` 以匹配真实命题。

同类问题还有两处硬编码断言（`missingProtocolConfigurationFallsBackToBothProtocols`、
`malformedProtocolConfigurationFallsBackToBothProtocols`），都改成 `containsExactlyInAnyOrder(values())`
—— 那两个回退的语义就是「全部」，逐个列举会让加协议时忘改的行为悄悄退化成子集。

> 判据：**测试里出现 `values()` 时要问「这个命题真的对全部枚举值成立吗」。**
> 若命题实际依赖某个子集（勾选集、已实现集），遍历全集只是当下的巧合。

### 10.3 「恰好正确」的两处：加了测试而非改实现

| 位置 | 判断 | Responses 落向 |
|---|---|---|
| `ProviderRequestHeaderService.applyAuthenticationHeaders` | `if (upstream == MESSAGES) x-api-key else Bearer` | else → `Authorization: Bearer` ✓ |
| `ProviderModelDiscoveryService.applyProtocolHeaders` | `if (protocol != MESSAGES) return` | 直接返回，不发 `anthropic-version` ✓ |

两处都是**否定式判断**，因此新加入的 OpenAI 系协议自动落到正确一侧。这比逐协议 `switch` 更好：
后者要为每个新协议补一条，漏掉的症状分别是「上游 401」和「给 OpenAI 端点发了 Anthropic 版本头」，
且其余线路一切正常 —— 最难联想到成因的形态。

前者加了显式测试（`responsesUpstreamSendsBearerLikeChatBecauseItIsAnOpenAiEndpoint`）。
后者的 `applyProtocolHeaders` 是私有方法，**没有为它开放可见性** —— 那会让内部细节变成契约。
我一度写了个用 `values()` 间接断言的用例，但它是同义反复、零断言价值，已删除，
改为在生产代码 Javadoc 里写清这个依赖。

### 10.4 新增测试（9 个）

- `ProtocolDispatchManagerTests` +4：Responses 直连、三条全勾时每条都直连、
  **回退序优先于枚举声明序**、回退序覆盖全部协议
- `ProviderRequestHeaderServiceTests` +1：Responses 走 Bearer
- `RepositoryUpsertTests` +1：空串清空 vs null 保留（`blankEndpointClearsWhileNullPreserves`）
- `ProviderModelDiscoveryProtocolTests` 新建 +3：三协议名大小写不敏感、
  未知名（含旧协议名 `OPENAI`）回退 CHAT、`ModelPullCommand` 规范化

其中「回退序优先于枚举声明序」那条是最有价值的：下游 CHAT、候选 {MESSAGES, RESPONSES} 时
必须挑 MESSAGES。**枚举声明序是 CHAT, RESPONSES, MESSAGES，会挑 RESPONSES** ——
而 C2M 已实现、C2R 未实现。这一条同时证明「用了回退序」与「回退序的排法是对的」。

### 10.5 阶段二后端明确不做

- **不加 `/v1/responses` 端点**（阶段三）。因此现在勾了 RESPONSES 又打那个路径会得到 404，
  但那是「路径不存在」而非「协议不支持」。
- **不给 `GatewayAuthFilter` 加路径**：端点还不存在，先加等于保护一个空路径。阶段三与端点同批。
- **`updateProviderProtocols` 保持三参平铺**，不换入参 record。理由写进了它的 Javadoc：
  换 record 会波及三条保存路径与它们的测试，而本次主题是加一个端点维度。留待第四个字段。
- ~~**前端未动**~~ 已在阶段二后续完成，见 §11。

## 11. 阶段二（前端部分）实施记录

**状态：已完成。** 前端 939 个测试全通过（原 930 + 新增 9），`vue-tsc -b` 0 错误。

### 11.1 落地内容

| 文件 | 改动 |
|---|---|
| `types/protocol.ts` | 加 `RESPONSES`；新增 `WIRE_PROTOCOL_DESCRIPTIONS`（悬停说明）、`WIRE_PROTOCOL_URL_LABELS`（短标题）、**`RULE_ENGINE_WIRE_PROTOCOLS`**（见 §11.3） |
| `provider-config/protocolUrls.ts` | 后缀常量改名 + `WIRE_PROTOCOL_ENDPOINT_SUFFIXES` 表；`mirrorAnthropicBaseUrl` → `mirrorBaseUrl`；`resolvePrimaryProtocol` / `orderProtocolRows` 从两路扩到三路；`resolveModelPullTarget` 改收 `Record<WireProtocol, string>` |
| `provider-config/presets.ts` | `ProviderPreset` / `PresetFormValues` 加 `responsesBaseUrl` |
| `stores/providers.ts` | `Provider` 与 `ProviderProtocolInput` 加 `responsesBaseUrl`，提交时三字段齐发 |
| `views/Settings.vue` | 弹窗三行改 `v-for`；抽屉折叠区改 `v-for`；镜像状态改为按协议索引的 `Record`；标题改用短名 + 气泡 |
| `request-body-rules/*` + `RuleGroupCard.vue` | 四处默认全集改用 `RULE_ENGINE_WIRE_PROTOCOLS` |

### 11.2 标题改用接口名（用户提出）

原先是「OpenAI 请求Url」「Anthropic 请求Url」—— **两行都是厂商名**，而 Responses 同属 OpenAI，
沿用厂商名会出现两行都叫「OpenAI」。改成 `Chat / Responses / Messages 请求Url` 后三行同维度。

短名无法回答「这三个什么关系」（尤其 Chat 与 Responses 容易被当成新旧版本的同一个东西），
因此加了 `WIRE_PROTOCOL_DESCRIPTIONS` 挂在标题的原生 `title` 上。测试断言里排除了厂商名，
防止有人改回去。

Responses 放第二行（`ALL_WIRE_PROTOCOLS` 取语义序 `CHAT, RESPONSES, MESSAGES`），
理由即用户所说：它与 Chat 同属 OpenAI。

### 11.3 计划外发现：前端全集会让新建规则组被后端 400

`ALL_WIRE_PROTOCOLS` 有一类消费者是**请求体规则组**的「适用协议」，四处用它铺默认全集
（`createRuleGroup`、`emptyGroup`、`normalizeProtocols` 的缺省、帮助页示例 JSON）。

而后端 `ProviderRequestTransformService.PROTOCOLS` 仍是 `Set.of("CHAT","MESSAGES")` ——
一旦前端全集含 `RESPONSES`，**新建一个规则组就会在保存时收到 400「不支持的线路协议」**。
用户什么都没配错。`RuleGroupCard.vue` 的多选选项同理，会主动提供一个必然被拒的选择。

修法：新增 `RULE_ENGINE_WIRE_PROTOCOLS`（`['CHAT','MESSAGES']`），与 `ALL_WIRE_PROTOCOLS`
**刻意分离**。两者语义不同：

- `ALL_WIRE_PROTOCOLS` —— 系统认识哪些协议（地址配置、协议勾选、日志展示）
- `RULE_ENGINE_WIRE_PROTOCOLS` —— 规则引擎能对哪些协议生效

> 这验证了用户的判断（「不确定后端有没有做，如果没有也不着急」）—— 后端确实没做。
> 但**不是「不用改」而是「必须显式挡住」**：不改的话新增协议会顺着共享常量漏进一个
> 后端拒绝的取值。Responses 请求体改写落地时，两处白名单必须同一批改 ——
> 先改任一侧都会造成「界面能勾但保存报错」或「保存成功但规则不生效」。

### 11.4 联动状态必须按协议拆开

原先是一个布尔 `mirroringAnthropicBaseUrl`（Chat 地址联动 Anthropic）。加入 Responses 后
联动从一对一变成一对多，而**两条线路的联动状态互相独立**：

「Anthropic 有值（不联动）+ Responses 为空（联动）」是常见配置 —— 共用一个快照会让其中一条
要么该联动却不联动、要么不该联动却被覆写。改成 `Record<WireProtocol, boolean>`，
弹窗与抽屉各持一份。新增测试 `两条线路的联动状态互不影响` 钉住这一点。

`mirrorBaseUrl` 只改名不改实现：函数体与目标是哪条线路无关，留着协议专属的名字会让
调用方以为需要为每条线路再写一份。

### 11.5 抽屉折叠区改用 v-for

原先「首行 + 单个次行」都展开写死，注释理由是「Transition 只接受单个子元素，
而两者内容结构相同但仅此两处，重复的代价小于消重」。加入第三条线路后**次行不再是固定一行**，
展开写死就要在下次加协议时再改一遍。

现在首行仍单独写（它不参与动画），折叠区内部用 `v-for` 渲染 `editProtocolRows.slice(1)`。
`__collapse` 那一层因此多担了一个职责：把多行收进单个过渡子元素。顺带给它加了
`display: flex` + `gap` —— 内部行同时出现同时消失，不需要像包装层那样单独过渡间距。

### 11.6 拉取模型的优先级与展示序不同

`resolveModelPullTarget` 用 `CHAT → RESPONSES → MESSAGES`，依据是**请求头相似度**：
Responses 与 Chat 同为 OpenAI 系（同一个 `Authorization: Bearer`、不需要 `anthropic-version`），
因此 Chat 未启用时退到 Responses 比退到 Messages 少一处请求头差异。

这个顺序与 `ALL_WIRE_PROTOCOLS` 数值相同但**理由不同**（那个是展示语义序），
因此用独立常量 `MODEL_PULL_PRIORITY` 写出 —— 将来某个协议的请求头变了，只该改这一处。
这与后端「四个顺序互不相同」是同一类问题，现在前端也有两个。

### 11.7 spec 里 `RESPONSES` 从兜底路径变成查表路径

`protocol.spec.ts` 原先用 `RESPONSES` 举例测「未知协议走首字母兜底」。现在它走查表，
那条断言会悄悄变成查表的重复测试。改用真正未知的标识（`gemini` / `COMPLETIONS`）——
**测兜底路径必须用真正未知的值**，否则断言会随协议加入而失效却仍然通过。

### 11.8 前端明确不做

- **「适用协议」不加 Responses**：后端规则引擎不支持，见 §11.3。
- **未跑 `npm run build`**：那会覆写 `src/main/resources/static`，而服务正在运行
  （本次对话本身经它代理）。界面验证留给用户重启后进行。

## 12. 修正：展示序改为跟随翻译回退优先级

**状态：已完成。** 前端 939 通过、`vue-tsc` 0 错误、后端 `ProtocolDispatchManagerTests` 13 通过。

### 12.1 一处事实纠正

用户提出「按现在的显示顺序，上游同时支持 M 和 R 时 C 请求会优先选 R 翻译」。

**这个具体后果当前不会发生**：后端 `TRANSLATION_FALLBACK_ORDER` 在阶段二就定为
`CHAT → MESSAGES → RESPONSES`（已实现优先），该场景选的是 MESSAGES。
`translationTargetFollowsFallbackOrderNotEnumDeclarationOrder` 正是钉这一点的用例。

### 12.2 但核心诉求成立，而且指出了真问题

**前端展示序（`C R M`）与后端回退序（`C M R`）不一致，这本身就是误导。**
协议地址行从上到下排列，用户会把那个排列读成优先级 —— 于是界面在暗示一条
尚未实现的翻译路径（C2R）优先级更高。

原先前端取「语义序」（两个 OpenAI 接口相邻），理由是好读。但**「读起来舒服」不值得用
「对系统行为的错误预期」去换**，尤其这个预期指向的是未实现的路径。

改为 `ALL_WIRE_PROTOCOLS = ['CHAT', 'MESSAGES', 'RESPONSES']`，与后端逐项一致。
两处 Javadoc 都写明「同一个事实的两个表达，改一处必须改另一处」。

### 12.3 顺序清单更新（现在是 4 个，其中 2 个必须同步）

| 顺序 | 值 | 依据 | 关系 |
|---|---|---|---|
| 前端展示 / 勾选序 | `C, M, R` | 用户读成优先级 | **必须与下一项同步** |
| 后端翻译回退序 | `C, M, R` | 已实现优先 | **必须与上一项同步** |
| 前端拉取模型优先级 | `C, R, M` | 请求头相似度 | 独立 |
| 后端落库 JSON 元素序 | `C, M, R` | 字母序（查库对比） | 独立（数值巧合） |

`MODEL_PULL_PRIORITY` **保持 `C R M` 不变**，这个差异本身就是它必须独立声明的证据：
拉取模型不经任何翻译，只差一个 `anthropic-version` 头，不该受翻译优先级影响。
它的注释已从「与展示序相同但理由不同」改为「与展示序**不同**，而这个差异即独立的理由」。

### 12.4 计划外发现：`DEFAULT_NEW_PROVIDER_PROTOCOLS` 硬编码导致 4 个用例失败

改顺序后 4 个测试失败，全指向 `normalizeProtocols` 的脏数据回退与新建默认值 ——
根因是 `DEFAULT_NEW_PROVIDER_PROTOCOLS` 硬编码了一份 `['CHAT', 'RESPONSES', 'MESSAGES']`。

改成 `[...ALL_WIRE_PROTOCOLS]` 派生。**它的定义本来就是「全部」**，硬编码一份不仅会在
改顺序时漏，还会连带影响 `normalizeProtocols`（脏数据回退用的正是它）——
一处硬编码造成两个语义无关的功能同时出错。

### 12.5 两处 TODO（用户要求）

**① 协议流转编排**（`ALL_WIRE_PROTOCOLS` 与 `TRANSLATION_FALLBACK_ORDER` 各一条）

全局固定顺序无法覆盖真实场景。用户给的例子：某供应商不支持 Chat、只支持 Messages 与
Responses，而它名下模型 1 只支持 Responses、模型 2 只支持 Messages ——
**全局顺序对这两个模型只能给出同一个答案，其中必有一个是错的**。

目标是让用户按供应商、按模型编排「下游协议 → 上游协议」的映射。当前的固定顺序是
「在没有编排能力时把错误率压到最低」的过渡方案，不是目标形态。

编排能力将在 C2R 翻译落地之前规划与实现。在那之前直连场景不受影响 ——
规则 1（同名协议优先直连）根本不读这个顺序，而三条线路默认全勾时绝大多数调用都走规则 1。

**② Responses 请求体改写规则**（`RULE_ENGINE_WIRE_PROTOCOLS` 一条）—— **已在 §13 完成**

## 13. 请求体规则支持 RESPONSES（端点之前先行）

**状态：已完成。** 后端 837 通过（+3）、前端 949 通过（+10）、`vue-tsc` 0 错误。

### 13.1 为何在端点之前做

`POST /v1/responses` 尚未接入，因此声明了该协议的规则组**当前不会被执行** ——
`RequestBodyRuleEngine.transform` 按上游协议筛组，没有那条线路就没有调用方。

先放开仍然值得，两个理由：

1. **规则引擎本身协议无关。** `groupAppliesTo` 按协议名做字符串匹配，加一个取值不需要
   引擎改动 —— 也就是说这件事的成本只有「放开白名单」，与端点是否存在无关。
2. **请求体改写是接一个新中转站时最先需要的能力。** 等端点落地才放开，那段时间里用户
   在界面上看不到这个选项，端点上线当天才能开始配规则。

### 13.2 改动只有一处实质

| 位置 | 改动 |
|---|---|
| `ProviderRequestTransformService.PROTOCOLS` | 加 `"RESPONSES"` |
| `RULE_ENGINE_WIRE_PROTOCOLS`（前端） | 加 `'RESPONSES'` |

其余全是注释与测试。**预览接口不需要改**：它走 `transformWithRules`，刻意不做协议筛选
（编辑器里「适用协议」是规则组自己的属性，替用户筛掉正在编辑的组只会让预览变空白）。

### 13.3 `RULE_ENGINE_WIRE_PROTOCOLS` 现在等于全集，但保留

两者语义不同：`ALL_WIRE_PROTOCOLS` 是「系统认识哪些协议」，本常量是「规则引擎能对哪些
协议生效」，而后者受**后端白名单**约束。

它曾短暂地是真子集（Responses 加入系统后、后端白名单放开之前），那段时间证明这两个概念
确实会分叉，因此即使现在数值相同也不合并。测试断言写死三个字面量而非比对
`ALL_WIRE_PROTOCOLS` —— 若写成比对，将来某个新协议加进系统但规则引擎还没支持时，
断言会自动通过，而那正是需要它失败的时刻。

### 13.4 V1 兼容那条判断不需要改，但理由值得写下

`parseRules` 里 V1 规则集**只在 CHAT 下执行**。加入 RESPONSES 后这条判断仍然正确，
而且是**结构上正确**而非恰好正确：

> 判断写成「仅 CHAT 放行」而非「排除某几个协议」，新协议自动落到不执行的一侧 ——
> 而那正是正确的一侧，因为 V1 规则不可能是为一个当时还不存在的协议写的。

对应测试从 `legacyV1RuleSetIsSkippedOnNonOpenAiProtocol` 改名为
`legacyV1RuleSetOnlyAppliesToChatProtocol`，非 CHAT 那一侧改为遍历 `values()`。

### 13.5 计划外发现：又一处 `values()` 判据问题

`RequestBodyRuleEngineTests` 里三条协议无关的用例（缺失 protocols 视为全协议、
空 protocols 永不适用、V1 仅 CHAT）都只列举了两个协议。它们的命题**确实对全集成立**，
因此改为遍历 `values()`。

`migration.spec.ts` 有一条硬编码 `['CHAT', 'MESSAGES']` 的断言直接失败 ——
它测的是「缺失 protocols 铺全集」，改为比对 `RULE_ENGINE_WIRE_PROTOCOLS`。

> 这已是本轮第三次遇到同类问题（前两次见 §10.2、§12.4）。判据稳定下来了：
> **断言里出现协议集合时，先问「这个命题是对某个具体集合成立，还是对『那个集合的全部』成立」。**
> 前者写死字面量（如 `RULE_ENGINE_WIRE_PROTOCOLS` 与后端白名单同源），
> 后者比对常量或遍历 `values()`（如「全协议适用」）。写反的症状都是「加协议时静默退化」。

### 13.6 新增测试

后端 3 条：
- `preV12ProtocolNamesInRuleGroupAreRejected` —— 旧协议名（`OPENAI` / `ANTHROPIC`）必须被拒。
  若放行，一份旧版本导出的规则集能存进来，而引擎按新名匹配 → 那个组静默永不生效。
- `everyWireProtocolCanBeDeclaredByRuleGroup` —— 遍历 `values()`，每条线路都能声明并落库。
- `ruleGroupCanDeclareAllProtocolsAtOnce` —— 一个组声明全集也要能存下。

前端 10 条：`RULE_ENGINE_WIRE_PROTOCOLS` 三条（同源、每项合法、是全集子集）、
新建 `editorState.spec.ts` 七条（默认协议集、含 Responses、每项合法、ID 唯一、
order 与命名、默认启用无规则、默认状态无组）。

### 13.7 本轮明确不做

- **不接 `/v1/responses` 端点**（下一步）。
- **不扩请求体模板键**：模板是编辑器的调试样本（`base` / `message-*` / `tools` / `custom`），
  Responses 的请求体形态不同（`input` 而非 `messages`），理论上值得一套专属模板。
  但用户可选 `custom` 自填样本，而模板只影响预览、不影响生产行为 —— 优先级低于端点本身。

## 14. 阶段三实施记录（后端直连场景）

**状态：已完成。** 后端 953 通过（+116），`BUILD SUCCESS`。

### 14.1 落地内容

| 文件 | 状态 | 要点 |
|---|---|---|
| `protocol/openai/ResponsesRequest` | 新增 | 只建模 `model` / `input`（`Object`）/ `stream`，其余走 `@JsonAnySetter` |
| `api/openai/ResponsesController` | 新增 | `POST /v1/responses`，两层完成判定 + `event:` 名回填 + 心跳 |
| `application/openai/ResponsesService` | 新增 | 两个方法体裹 `defer`，`translationNeeded()` 抛未实现 |
| `provider/generic/openai/GenericResponsesChatService` | 新增 | 直连执行、重试、落库、usage；与另两侧平级不继承 |
| `provider/generic/openai/ResponsesContentDetector` | 新增 | 空响应判定，走 `output[]` 而非 `output_text` |
| `provider/generic/openai/ResponsesStreamEvents` | 新增 | 七个终态事件；控制器与判定器共用一份 |
| `provider/generic/openai/ResponsesUsageParser` | 新增 | 换算「直接搬」，无 `merge()` |
| `application/runtime/ReasoningEffortSetting` | 改 | 加 `applyToResponses`（返回 `void`） |
| `infrastructure/web/GatewayAuthFilter` | 改 | `PROTECTED_PATHS` 加 `/v1/responses` |

计划（§5）里的每一条取舍都按原样落地，没有中途改主意的地方。

### 14.2 §5 之外的实现细节

这几处计划里没写，实现时才需要定：

**`ResponsesStreamEvents` 独立成类。** §5.4 只说了「终止事件不止一个」，没说清单放哪。
控制器要用它 finalize、判定器要用它决定「终态事件是否也算载荷贡献」，写两份必然漂移
（漏一个拼法的症状是那类上游的流永远收不到收尾）。

**流式不设 gate。** §5.1 只说链体结构不同。具体差异是：Chat 侧要在「确认非空」之前扣住帧，
而 Responses 的事件**必须按序完整下发** —— 下游客户端是状态机，扣住 `response.created`
会让它无法初始化。改为「边下发边记录是否见过实质载荷」，与 Anthropic 侧同一形状。

**`applyToResponses` 返回 `void`。** `applyToAnthropic` 返回 `boolean` 是为了告诉调用方
「我写了 `disabled`，思考方式那一维请跳过」。Responses 侧既不写 `thinking`、也没有第二个
思考维度需要协调，返回值无处可用。

**`isDeltaEventWithPayload` 用后缀匹配。** 官方事件名形如
`response.output_text.delta`，但兼容端点的前缀不完全一致。按 `.delta` 后缀判断而非精确
等值，漏判的症状是把有内容的一轮判成空响应然后重发五次。

**终态事件也要检查载荷。** 部分上游完全不发增量事件，只在 `response.completed` 里一次性
给出完整 `output[]`。只看 delta 会把这类上游的每次调用都判成空响应。

### 14.3 计划外发现：重试次数断言的语义

`GenericResponsesChatServiceTests` 里两条重试用例先写成
`isEqualTo(RetryPolicyService.DEFAULT_MAX_ATTEMPTS)`，实际是 6 而非 5。

配置项的语义是**重试次数**，首发那次不算重试，所以总往返是 `1 + 5`。断言改为写成
`1 + DEFAULT_MAX_ATTEMPTS` 的表达式并抽成常量 —— 这样「改了默认预算」与「重试语义被写反」
在失败信息里可区分，写死 `6` 则两者都只显示一个数字。

### 14.4 两处否定式判据得到验证

`ProviderRequestHeaderService` 的两个私有方法此前只对两种协议成立，加入 Responses 后
**自动正确**，因为都写成否定式：

```java
if (upstream == MESSAGES) x-api-key else Bearer     // Responses 落到 else ✓
if (protocol != MESSAGES) return;                    // Responses 跳过 anthropic-version ✓
```

`GenericResponsesChatServiceTests` 的 `onlyBearerAuthenticationHeaderIsSent` 与
`anthropicVersionHeaderIsNotSent` 把这个「自动」变成断言 —— 否则它只是巧合。
前者还刻意在下游头里塞了 `x-api-key`，验证它确实被删掉而非恰好没人发。

### 14.5 新增测试（116 条）

| 测试类 | 条数 | 重点 |
|---|---|---|
| `ResponsesContentDetectorTests` | 29 | 缓存/加密思考块判空、工具执行痕迹不算载荷、终态事件携带完整 output 算贡献、`.delta` 后缀匹配 |
| `ResponsesUsageParserTests` | 13 | **缓存命中不重复计入总输入**（`input=100, cached=80` → `prompt=100`）、`null` 与 `0` 的区分、顶层 `cached_tokens` 兼容 |
| `GenericResponsesChatServiceTests` | 25 | 出站路径保留、只发 Bearer、不发 `anthropic-version`、**不注入 `max_output_tokens` / `thinking`**、有状态字段透传、空响应重试与耗尽放行 |
| `GenericResponsesChatServiceUsagePersistenceTests` | 9 | **落库联动**：日志与用量的软链接、协议列、ttfb、孤儿行、错误日志不双记、空响应每轮一条且耗尽后不补、`publishCallRecorded` 的 finally 语义 |
| `ResponsesControllerTests` | 18 | **`event:` 名回填**、未建模字段透传、终态事件不计入 CHUNK、两层完成判定 CAS 去重、错误分类四档、错误体 OpenAI 形态 |
| `ReasoningEffortSettingTests.Responses注入模式` | 12 | 四档 × `off`/常规、空 `reasoning` 容器不算表态、只摘 `effort` 保留 `summary` |
| `ChatDispatchErrorSignalTests`（扩） | +6 | Responses 两个方法的组装期不抛 + R2C/R2M 未实现走信号 + 直连仍委托 |
| `GatewayAuthFilterTests`（扩） | +4 | `/v1/responses` 的 401 / 放行 / 共用同一把 Key |

最值得保留的两条：`缓存命中不重复计入总输入`（照抄 Anthropic 加法的唯一防线，
且错了不报错、只是输入量虚高）与 `maxOutputTokensIsNeverInjected`（字段名看起来天造地设，
接上去毫无阻力，用例里给模型配了明确值所以接上就会失败）。

### 14.6 计划外发现：落库链一度是零覆盖

第一版测试写完、944 全绿之后回过头核对，发现一个真缺口：
**`GenericResponsesChatServiceTests` 从未注入 `apiCallLog`**，于是
`saveNonStreamLog` / `saveStreamLog` / `saveStreamLogWithError` 三条分支在测试里
始终走 `if (apiCallLog == null) return null;` 早退 —— 一条都没被执行过。
usage 那条我注入了替身、验证了「调用路径通」，但连它也只是手工注入，
`logId` 的软链接、`publishCallRecorded` 的 finally 语义都没有断言。

落库是**静默**的：写错了不报错、不抛异常，只是管理后台的日志页少东西或字段不对，
而那正是平时没人会逐条核对的地方。补了
`GenericResponsesChatServiceUsagePersistenceTests`（§14.5 表格第 4 行）。

> 教训值得单列：**「调用路径通」与「副作用正确」是两个缺陷面。**
> 前者的测试只要注入替身就能写出来，而后者的测试必须真的 mock 落库接口并断言参数。
> 写测试时若某个可选依赖（`@Autowired(required = false)` 那一类）只需注入一部分就能
> 让全部用例通过，那说明另一部分**一行都没验到** —— 这里的 `apiCallLog` 正是如此。

顺带的产物是两条容易写错的断言被钉住了：流式错误响应只写一条日志
（`doOnError` 里 `findWebResponseException != null` 的排除若失效就会双记），
以及空响应耗尽后不再补写收尾那条（`emptyResponsePassthrough` 标记的意义）。

### 14.7 本轮明确不做

- **不做 R2C / R2M 翻译**（两个 `TODO(待实现)` 已落在 `ResponsesService`，并交叉引用了
  `ProtocolDispatchManager` 的协议流转编排 TODO）。
- **不加 `DownstreamLogView` 参数重载**：当前只有直连一个视图，加重载没有调用方。
  跨协议落地时按 Anthropic 侧那四个重载的形状补。
- **不扩请求体模板键**（同 §13.7）。
- **不做前端改动**：这一轮纯后端。端点对前端的唯一可见影响是「协议勾选后真的能用了」，
  而勾选界面在阶段二已完成。
- **不给 Anthropic 侧补同类落库测试**：它同样没有（只有 Chat 侧有
  `AbstractUpstreamChatServiceUsagePersistenceTests`），但那是既有的、已上线线路的
  历史状态，与本轮新增 Responses 线路无关。要补应作为一次独立的收尾，不要混进来 ——
  混进来会让「本轮改了什么」变得难以判断。

## 15. 空响应判定器的两个缺口（实盘调用暴露，已修）

**状态：已修复。** 后端 972 通过（+19），`BUILD SUCCESS`。

### 15.1 怎么发现的

阶段三落地后做了一次真机流式验证（`POST /v1/responses` 打 `[mimo-tokenplan] mimo-v2.5`
与 `[deepseek] deepseek-flash`）。流式**本身完全正常** —— 208 条事件的 `event:` 名
全部正确回填、`chunked` 真流式、供应商前缀已剥、usage 解析正确。异常信号来自逐字段比对：
两家返回的 `reasoning` 项都是

```json
{"type":"reasoning","summary":[],"content":[{"type":"reasoning_text","text":"Okay, ..."}]}
```

`summary` 是**空数组**，思考正文在 `content` 里 —— 而判定器对 reasoning 项只看 `summary`。

### 15.2 两个缺口是同一形状

| # | 缺口 | 官方原文 | 触发场景 |
|---|---|---|---|
| A | reasoning 只看 `summary[]`，漏 `content[]` | `content: optional array of object { text, type }` | 思考是唯一内容载体 |
| B | message content 只看 `text`，漏 `refusal` | `content: array of ResponseOutputText **or** ResponseOutputRefusal` | 模型拒绝回答 |

**根因是我的推断错误**，值得记下：写 A 处代码时看到官方 `summary` 无 `optional` 前缀、
`content` 有，就推断「content 是次要路径」。**这个推断是错的** —— `optional` 的含义是
「可能不存在」，不是「少有人用」。实测 MiMo 与 DeepSeek **两家都只走 content**，
即 sampled 样本 100% 命中。B 处则是典型的联合类型只取一支。

> 判据（与 §13.5 那条同源，可合并记忆）：
> **看到官方写 `A or B`、或看到字段标 `optional`，先假设两条都存在真实上游。**
> 只取一支的症状与前几轮完全一样：**静默退化**，不报错、不抛异常。

### 15.3 端到端后果（实测，非推断）

把探针从判定器推进到服务层，实测**上游调用次数**：

```
nonStreamRefusalOnlyResponseIsNotRetried    expected: 1  but was: 6   ← 缺口 B
nonStreamReasoningOnlyResponseIsNotRetried  expected: 1  but was: 6   ← 缺口 A
```

一次正常回复变成 6 次真实计费的上游调用、6 条 `api_call_log`、6 条 `api_call_usage`、
约 60 秒退避等待，日志里还写着「上游空响应（无正文/思考链/工具调用）」——
**结论与事实相反**。

最坏的一点是**它不表现为功能故障**：耗尽后 `onErrorResume` 会放行最后一轮的 body，
用户最终仍能拿到内容。症状只是「有时候特别慢、token 用量莫名偏高」，
极难被归因到判定器。

### 15.4 修复（只改一个文件）

`ResponsesContentDetector`，三处：

1. `ITEM_MESSAGE` → `arrayHasContent(content)`，判定覆盖 `text` 与 `refusal`。
2. `ITEM_REASONING` → `summary[]` **或** `content[]` 任一有内容即算。
3. 事件层新增第三个缺口 C：原来只认 `.delta` 后缀，现在按**字段名**判定
   （`delta` / `text` / `refusal`），并检查内容的四个位置 —— 顶层、`part` 里、
   `item` 里、终态 `response.output[]` 里。

第 3 点是被 A、B 带出来的：官方 `ResponseTextDoneEvent` / `ResponseReasoningTextDoneEvent` /
`ResponseRefusalDoneEvent` 都带**全文**，只认 `.delta` 时「一个 delta 都不发、
只在定稿事件里给全文」的上游会被判空。它与 A、B 是共犯关系 ——
`refusal.done` 上同时叠加了「后缀不匹配」与「字段名不匹配」两层。

实现上把判定收敛成两个原语，避免第四条路径再被漏掉：
`carrierHasContent(载体)` 与 `arrayHasContent(数组)`。载体可以是事件本身、`part` 对象或
`content[]`/`summary[]` 里的元素 —— 它们在「内容存在哪个字段」这件事上结构相同。

### 15.5 为什么流式没出事（也说明非流式是薄弱面）

两个缺口在流式下都**不会**咬到真实上游：MiMo 61 个 + DeepSeek 135 个
`response.reasoning_text.delta`、3 + 7 个 `output_text.delta`，`.delta` 后缀匹配全接住。

原因是判定机会的数量不同：

- **流式**逐事件逻辑或 —— N 次机会，任一 delta 命中就安全；
- **非流式**是整体判定 —— 只有一次机会。

这条规律对将来有用：**新增判定分支时，先想「非流式那条单次判定的路径能不能过」。**

### 15.6 顺带查证的两件事

**① `output_text` 是 SDK-only 字段。** 官方原文：

> `output_text`: **SDK-only convenience property** ... Supported in the Python and JavaScript SDKs.

即它**不是服务端承诺返回的**。实测 DeepSeek 返回 `output_text: ""` 而 message 项里的
正文完好 —— 若判定器依赖它，那份完全正常的响应会被判空重试 5 次。
「不用 `output_text`」这个决定（§5 时期从 new-api / sub2api 都不用来推）现在有官方依据了。

**② 终态事件清单是安全超集。** 官方实际只有 4 个终态事件
（`response.completed` / `failed` / `incomplete` / `error`），我们清单里 7 个，
多出的 `response.done` / `cancelled` / `canceled` **官方文档零次提及**
（`cancelled` 只是 response 的 status 取值，取消靠轮询而非事件）。多认不造成错误
（永不匹配），但它们是来历不明的猜测项。已保留不动 —— 万一某个兼容端点真的会发，
删掉反而更危险。

### 15.7 本轮明确不做

- **不动 `response.failed` 的分类**。它带 `error` 而 `output` 通常为空 → 判定为「无载荷」
  → 归入空响应重试。日志措辞因此不准确（失败 ≠ 空响应），但**行为是对的**：
  一次失败本来就该重试。把它算作载荷会**取消**重试，那是另一个方向的错误。
  真要改是「给 `response.failed` 单独的失败信号」这种设计改动，不是判定器的修补。
- **不动 Chat 侧的同类疑点**。`OpenAiContentDetector.payloadHasContent` 只有
  `content` / `REASONING_KEYS` / `tool_calls`，没有 `refusal`；而 Chat Completions 的
  `choices[].delta.refusal` 是官方字段。**但我手上只有 Responses 的官方文档，
  没有 Chat 的**，因此这是「疑似」而非「已证」——不改未经验证的东西。
  要修应先取 Chat 的官方文档核实，且它属于既有线路，应独立一轮。
- **不删超集的三个终态类型**（理由见 §15.6②）。


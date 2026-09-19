# 方向一实施文档：协议语义改名 + Responses 协议适配

> 目标读者：在另一台机器上实现本功能的自己（或代理）。那台机器有 `new-api`、`sub2api`、
> `cc-switch` 三个参考项目，本机没有 —— 凡是需要查证参考实现的地方，本文用「**待查证**」
> 标出并给出具体要查什么，不要凭记忆填。
>
> 本文写于 schema **V11**、master 位于 `bf8757b` 时。开工前先确认这两个前提是否仍成立，
> 若 schema 已推进到 V12+，把本文的 V12 顺延为下一个整数。

## 0. 为什么分三个阶段，以及为什么这个顺序

三个阶段各自独立可验证，不要合并成一个提交：

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| 一 | 协议枚举改名 + V12 迁移 | 全部测试通过，**行为零变化** |
| 二 | C2R 请求翻译 + R2C 响应翻译 | 能实际调通只支持 `/responses` 的模型 |
| 三 | 供应商配置与前端 | 能在管理后台勾选第三种协议 |

**阶段一必须在最前面。** 它是纯风险、零收益的机械改动，越晚做撞上的存量数据越多 ——
等 Responses 落地后再改名，要多迁移一批 `api_call_log` 里协议列为新值的行。

**阶段二不要拆成「只做请求」或「只做响应」。** 一条链的去程与回程缺一半就整条不可用，
这是响应侧契约第 0 节已经确立的原则（当初 A2O 先做响应侧，正是因为它能让「已有的一半
变成可用的整体」）。C2R/R2C 两侧都是新的，必须一起做。

---

## 阶段一：协议语义改名 + V12 迁移

### 1.1 命名决定

改名的动机：`OPENAI` 是**厂商名**，而 `WireProtocol` 的 Javadoc 自己写的定义是「标识
报文格式，而非某个具体供应商或端点路径」。OpenAI 现在有两种报文格式（Chat Completions
与 Responses），厂商名再也无法区分它们。

最终命名（**不要**用 `Chat` / `Mess` / `Resp` 这组缩写，`Mess` 在英文里是「混乱」，
日志和文档里读起来别扭）：

```java
public enum WireProtocol {
    /** OpenAI Chat Completions 格式（/v1/chat/completions）。 */
    CHAT_COMPLETIONS,
    /** Anthropic Messages 格式（/v1/messages）。 */
    MESSAGES,
    /** OpenAI Responses 格式（/v1/responses）。 */
    RESPONSES
}
```

映射关系：`OPENAI` → `CHAT_COMPLETIONS`，`ANTHROPIC` → `MESSAGES`，新增 `RESPONSES`。

翻译方向缩写随之变化。既有类名也要改，因为 `OpenAiToAnthropic` 同样带厂商名：

| 旧 | 新 | 方向 |
|---|---|---|
| `O2A` / `OpenAiToAnthropicRequestTranslator` | `C2M` / `ChatToMessagesRequestTranslator` | 下游 Chat → 上游 Messages |
| `A2O` / `AnthropicToOpenAiResponseTranslator` | `M2C` / `MessagesToChatResponseTranslator` | 上游 Messages → 下游 Chat |
| `A2OStreamState` | `M2CStreamState` | — |
| — | `C2R` / `ChatToResponsesRequestTranslator` | 阶段二新增 |
| — | `R2C` / `ResponsesToChatResponseTranslator` | 阶段二新增 |

前端缩写映射同步改成 `C` / `M` / `R`（三者首字母互不冲突，路径标记 `C→M`、`C→R` 都清晰）。

### 1.2 改名的影响面（实测数据）

开工前先跑一遍确认数字，若与下表差异很大说明代码已变动：

```powershell
# 字面量总数（含注释与文档字符串）
(Get-ChildItem -Path src,frontend\src -Recurse -Include '*.java','*.ts','*.vue' `
  | Select-String -Pattern "OPENAI|ANTHROPIC").Count
# 引用 WireProtocol 的文件数
(Get-ChildItem -Path src,frontend\src -Recurse -Include '*.java','*.ts','*.vue' `
  | Select-String -Pattern 'WireProtocol' -List).Count
```

写本文时：**1857 处字面量、41 个文件**。

`OpenAi` / `Anthropic` 作为**类名前缀**的地方不要一律改。判据是「它指的是报文格式还是
厂商/端点」：

| 保持不动 | 理由 |
|---|---|
| `GenericOpenAiChatService` | 它就是打 OpenAI 家端点的执行器 |
| `GenericAnthropicChatService` | 同上 |
| `OpenAiContentDetector` / `AnthropicContentDetector` | 判的是具体报文结构 |
| `OpenAiUsageParser` / `AnthropicUsageParser` | 同上 |
| `AnthropicThinkingSetting` | 它是 Anthropic 家独有的设置 |
| `api/openai/` `api/anthropic/` 包 | 按端点归属分包 |

只改**枚举成员与翻译器**，不改执行器与解析器。这条界线要在改名提交的信息里写清楚，
否则下一个人会以为改漏了。

### 1.3 V12 迁移

**动手前必读** `.github/skills/cosp-schema-migration-skill/SKILL.md`。以下是本次特有的点。

三处需要迁移的列（已实测确认，行号为写本文时的 `schema.sql`）：

```sql
-- 25 行，provider_config
supported_protocols TEXT NOT NULL DEFAULT '["OPENAI","ANTHROPIC"]'
    CHECK (json_valid(supported_protocols))

-- 158-159 行，api_call_log
downstream_protocol TEXT NOT NULL DEFAULT 'OPENAI'
    CHECK (downstream_protocol IN ('OPENAI', 'ANTHROPIC'))
upstream_protocol   TEXT NOT NULL DEFAULT 'OPENAI'
    CHECK (upstream_protocol IN ('OPENAI', 'ANTHROPIC'))
```

#### 陷阱 A：`api_call_log` 的两列有 CHECK 约束，必须整表重建

SQLite 的 `ALTER TABLE` 改不了 CHECK 约束和 DEFAULT。所以 V12 对 `api_call_log`
是一次**整表重建**，而 Skill 里明确记着这条的三个坑：

1. **`DROP TABLE` 会静默删除触发器**，重建后必须逐一恢复。先跑
   `SELECT name, sql FROM sqlite_master WHERE type='trigger' AND tbl_name='api_call_log'`
   把现有触发器抄下来。
2. **两个索引要重建**：`idx_api_call_log_created_id` 与
   `idx_api_call_log_provider_created_id`（写本文时是这两个，动手前重新确认）。
3. **`INSERT ... SELECT` 必须写明列名，不能用 `SELECT *`**。这张表经过多次
   `ADD COLUMN`，物理列序不可预测，而错位的数据往往仍满足所有约束。

搬运数据时就地转换协议值：

```sql
INSERT INTO api_call_log_v12 (id, /* ...显式列出全部列... */
        downstream_protocol, upstream_protocol, /* ... */)
SELECT id, /* ... */
       CASE downstream_protocol
           WHEN 'OPENAI' THEN 'CHAT_COMPLETIONS'
           WHEN 'ANTHROPIC' THEN 'MESSAGES'
           ELSE 'CHAT_COMPLETIONS'   -- 脏数据兜底，不让一行阻断整个升级
       END,
       CASE upstream_protocol /* 同上 */ END,
       /* ... */
FROM api_call_log;
```

`ELSE` 分支是必须的：新表的 CHECK 比旧表严格，一行来源不明的脏数据会让整个 V12 回滚。
这与 V9 迁移里给 `reasoning_effort` 加 `json_valid` 兜底是同一个道理。

#### 陷阱 B：`supported_protocols` 是 JSON 数组，逐元素替换而非整串替换

不要写 `REPLACE(supported_protocols, 'OPENAI', 'CHAT_COMPLETIONS')` —— 那会把
`["OPENAI","ANTHROPIC"]` 变成 `["CHAT_COMPLETIONS","ANTHROPIC"]` 看似正确，但如果某天
出现了包含子串的协议名就会错。用两次有序替换，且**先替换长的**：

```sql
UPDATE provider_config SET supported_protocols =
    REPLACE(REPLACE(supported_protocols, '"ANTHROPIC"', '"MESSAGES"'),
            '"OPENAI"', '"CHAT_COMPLETIONS"')
WHERE supported_protocols LIKE '%OPENAI%' OR supported_protocols LIKE '%ANTHROPIC%';
```

带上引号匹配整个 JSON 字符串元素，避免部分匹配。`WHERE` 子句让这条 UPDATE 幂等 ——
第二次运行时已转换的行不再命中。

这一列的 DEFAULT 也要改（它写在建表 DDL 里，需要重建表或接受"新旧库默认值不同"）。
**建议一并重建 `provider_config`**，理由是：不重建的话，新库的 DEFAULT 是新值、
升级库的 DEFAULT 是旧值，「全库同形态」只在一侧成立。V11 刚给这张表加过 `use_proxy`，
重建时别漏掉它。

#### 陷阱 C：历史迁移体里的字面量一律不动

Skill 的第一条陷阱：历史迁移必须引用**冻结的版本常量**。同理，V8.8 那次迁移体里写的
`'["OPENAI","ANTHROPIC"]'` 字面量**不要改** —— 那是已发生的历史，改了会让「从 V8.7
升上来的库」与「新建库」走出不同结果。

具体来说，改名前先做这一步：把 `CURRENT_SCHEMA_VERSION` 在 V11 迁移体里的引用换成
新的 `V11_VERSION = 11` 常量，只让 V12 使用 `CURRENT_SCHEMA_VERSION`。

### 1.4 代码侧改名清单

按依赖顺序改，每步之后编译一次：

1. **`WireProtocol`** 枚举成员 + Javadoc（那份 Javadoc 现在只描述两种协议，要扩成三种，
   并把「为何不叫 ApiFormat」那段保留 —— 它解释的是历史，仍然有效）
2. **`ProviderProtocolSupport`** —— 见下方 1.5，这里有个真陷阱
3. **`ProtocolDispatchManager`** / `ProtocolDispatchDecision` / `ProtocolTranslator`
   —— 主要是 Javadoc 里的方向描述
4. **翻译器类改名**（`translate/` 包下 4 个类 + 2 个测试类）
5. **两个应用服务**（`ChatCompletionService`、`MessagesService`）的
   `DOWNSTREAM_PROTOCOL` 常量与注入字段名
6. **前端 `types/protocol.ts`** —— 前端字面量已收敛在这一个文件，是好消息；
   但 `isWireProtocol` 是硬编码的两次比较，要改成基于 `ALL_WIRE_PROTOCOLS` 的判断，
   免得加第三种时又漏一处
7. **前端 `callLifecycle.ts` 的 `protocolAbbreviation`** —— 映射表改成
   `{ CHAT_COMPLETIONS: 'C', MESSAGES: 'M', RESPONSES: 'R' }`
8. **两份契约文档**（`docs/PROTOCOL_TRANSLATION_CONTRACT.md` 与
   `..._RESPONSE_CONTRACT.md`）里的 O2A/A2O 记法
9. **`AGENTS.md`** 的协议翻译一节

### 1.5 陷阱 D：`OPTIMISTIC_ALL` 会让存量供应商"突然支持 Responses"

这是阶段一**最容易被忽略、后果最实际**的一处。`ProviderProtocolSupport` 里：

```java
private static final Set<WireProtocol> OPTIMISTIC_ALL = EnumSet.allOf(WireProtocol.class);
```

它的 Javadoc 甚至写着「用 `EnumSet.allOf` 而非硬编码两个值，纯粹是让常量与枚举保持同源，
**不为「将来可能有第三种协议」做准备**」。那个判断现在被推翻了。

加上 `RESPONSES` 后，这个回退集自动变成三元素。于是：

- `supported_protocols` 字段缺失或解析失败的供应商，会被认为**支持 Responses**
- `ProtocolDispatchManager` 规则 2 遍历 `WireProtocol.values()` 找候选协议，
  枚举声明顺序决定了谁被选中 —— 加在末尾时影响较小，但不能依赖声明顺序

**处置**：把 `OPTIMISTIC_ALL` 改成显式的两元素集合，并写明理由 ——
乐观回退的语义是「回到落库之前的行为」，而落库之前只有两种协议存在。Responses 必须由
用户**显式勾选**才生效，不能靠兜底获得。

```java
/**
 * 解析失败或字段缺失时的回退：Chat Completions 与 Messages 两种。
 *
 * <p>刻意<strong>不含</strong> {@link WireProtocol#RESPONSES} 且不用 EnumSet.allOf：
 * 本常量的语义是「回到协议落库之前的行为」，而那时只有这两种协议存在。
 * 用 allOf 会让配置缺失的存量供应商凭空获得 Responses 支持，
 * 而它们的上游很可能根本没有那个端点。新协议必须由用户显式勾选。
 */
private static final Set<WireProtocol> OPTIMISTIC_LEGACY =
        Set.of(WireProtocol.CHAT_COMPLETIONS, WireProtocol.MESSAGES);
```

同时检查 `ProviderProtocolSupport.parse` 的「未知协议名忽略而非报错」逻辑 ——
那条现在正好帮上忙：降级回滚时，库里留着 `RESPONSES` 的旧版本代码会忽略它而非崩溃。
这条行为要保留并在 Javadoc 里点明它现在有了真实用途。

### 1.6 阶段一的测试

- `SchemaMigrationRunnerTests`：按 Skill 要求加「前置版本 → V12」用例 + 幂等（跑两遍）
  + 空库路径。**重点断言**：存量 `api_call_log` 行的协议值被正确转换、触发器与索引在
  重建后仍存在、`supported_protocols` 的 JSON 元素被逐个替换
- `ProviderProtocolSupportTests`：新增「字段缺失时回退集不含 RESPONSES」
- `ProtocolDispatchManagerTests`：原先穷举四种组合，现在是九种。**不必全部写** ——
  只补「下游 CHAT_COMPLETIONS + 供应商只支持 RESPONSES」这类新增的可达组合
- 前端 `protocol.spec.ts`（若不存在则新建）：`isWireProtocol` 对三种值都为真、
  对旧值 `'OPENAI'` 为假

阶段一的验收标准是**行为零变化**：除了协议名，任何日志、任何响应、任何界面显示都不该
有可观测的差异。

---

## 阶段二：C2R 请求翻译 + R2C 响应翻译

### 2.1 开工前必须查证的事（本机无参考项目，这部分是空白）

这一节是整个阶段二的前提。**不要凭记忆写 Responses 的报文形态**，OpenAI 那套 API 的
字段名很容易记错，而记错的代价是一整轮返工。

在有参考项目的机器上查这几件事：

| 要查什么 | 去哪里查 | 为什么必须查 |
|---|---|---|
| `/responses` 请求体字段全集 | `new-api` 的 relay 层、OpenAI 官方文档 | `input` 与 `messages` 的结构差异是整个 C2R 的核心 |
| `input` 数组的元素形态 | 同上 | 它不是 `messages`，角色与内容的表达方式不同 |
| 流式事件类型全集 | `new-api` 对 `/responses` 的 SSE 处理 | 事件名形如 `response.output_text.delta`，与 Chat 的 `chunk` 完全不同 |
| 工具调用在 Responses 里的形态 | 同上 | Chat 是 `tool_calls[]`，Responses 待查证 |
| 思考内容的承载方式 | 同上 | 若 Responses 有独立的 reasoning 字段，要接进现有的思考链路 |
| `usage` 字段名与口径 | 同上 | 影响计费，见响应侧契约第 9 节 |
| 非流式响应的 `output` 数组结构 | 同上 | 它是嵌套的，不是 `choices[0].message` |
| 错误响应形态 | 同上 | 决定 `isRetryableFailure` 要不要扩 |

`sub2api` 与 `cc-switch` 主要看它们**怎么处理协议差异**（是翻译还是透传），
以及有没有踩过什么坑留下注释。请求侧契约第 9 节已经列过「值得借鉴 / 明确不借鉴」，
这次也按那个格式记录，追加到契约文档里。

查证后**先写契约文档再写代码** —— 这是 O2A/A2O 那次的做法，两份契约文档（531 行 +
782 行）就是那么来的。建议新建 `docs/RESPONSES_TRANSLATION_CONTRACT.md`，
或在既有两份里各加一节。倾向新建：Responses 与 Anthropic 的差异点完全不同，
混在一起会让本已很长的文档更难读。

### 2.2 架构接线（这部分不需要查证，照既有形态做）

翻译器套在上游服务**外侧**（装饰器），必须在 `retryWhen` 之外。这条是硬约束，
`ProtocolTranslator` 的 Javadoc 与响应侧契约第 12 节都写着理由：

> 空响应判定与落库用的是上游**原生**形态。若翻译发生在 `retryWhen` 内侧，
> ContentDetector 看到的是合成出来的 chunk，会把每一轮都判成空并耗尽预算。

`ChatCompletionService` 现有的翻译分支形态（写本文时的行号）：

```java
// 149 行：非流式
if (!decision.translationNeeded()) { /* 直连 */ }
TranslatedRequest translated = c2mTranslator.translateRequest(openAiRequest);   // 163
// ... 调上游，传 DownstreamLogView.protocolOnly(...)                            // 172
return m2cTranslator.translateResponse(upstream);                              // 173

// 217 行：流式
TranslatedRequest translated = c2mTranslator.translateRequest(openAiRequest);   // 227
DownstreamLogView logView = new DownstreamLogView(/* 协议 + chunk 改写器 */);    // 233
return m2cTranslator.translateStream(upstream, route.model(), translated.context()); // 242
```

C2R/R2C 照这个形态加分支。现在 `decision.translationNeeded()` 为真时有两种上游协议，
所以要按 `decision.upstreamProtocol()` 分派。**建议此时引入 `ProtocolTranslator` 的
注册表查表** —— 它的 Javadoc 里已经写了「一旦出现第三条链，注册表才有意义，
届时再补 `supports(下游, 上游)` 的查表」。现在就是那个时候。

需要新建的类（按既有命名与职责划分）：

```
application/protocol/translate/
├── ChatToResponsesRequestTranslator.java     # C2R 去程
├── ResponsesToChatResponseTranslator.java    # R2C 回程总入口
├── ResponsesToChatStreamTranslator.java      # 流式（需要跨事件状态机）
├── ResponsesToChatNonStreamTranslator.java   # 非流式
└── R2CStreamState.java                       # 跨事件状态，参照 M2CStreamState
```

另外需要：

- `provider/generic/responses/GenericResponsesChatService.java` —— 打 `/responses` 的
  执行器。**参照 `GenericAnthropicChatService`**（它已包含重试、空响应兜底、静默重试、
  usage 解析、落库的完整形态），而不是从 `AbstractUpstreamChatService` 重写
- `provider/generic/responses/ResponsesContentDetector.java` —— 空响应判定。
  取值路径必须照 Responses 自己的响应结构写，**不要复用 OpenAI 的**
  （`AGENTS.md` 里已记着两侧取值路径各自独立的理由）
- `provider/generic/responses/ResponsesUsageParser.java` —— 同理

`provider_config` 还需要第三个 base_url 列（`responses_base_url`），语义与
`anthropic_base_url` 一致：为空时回退 `base_url`。这需要**再一次迁移（V13）**，
或者并入 V12 一起做。建议**并入 V12** —— 既然已经要重建 `provider_config`，
多加一列的边际成本为零，而分两次要重建两遍。

> 注意：加了这一列，`OutboundProxyTargetProjector` 要把第三个端点也投影进代理目标集，
> 否则开了代理的供应商走 Responses 线路时会直连。它现在投影两个端点，
> 那段 Javadoc 写着「只投 base_url 会让 Anthropic 直连线路漏掉代理」，同样的道理。

### 2.3 mock 工具

`tools/` 下已有五个 mock 脚本。阶段二需要新增一个 `mock:responses`（建议端口 8085，
**别用 9090**，那是 Clash / mihomo 控制面默认端口）。参照 `mock:anthropic` 的形态，
它已经覆盖了「只能用 mock 触发的工具参数分片」这类边界。

`AGENTS.md` 里记着一条教训值得重读：**mock 的载荷要照下游 schema 查证**。当初 mock
`read_file` 工具时只发了个相对路径的 `filePath`，工具必然以参数校验失败告终，
把真正想观察的变量淹没了。

### 2.4 阶段二的验收

- 能实际调通只支持 `/responses` 的模型（GPT-6-Astra），这是本方向的原始诉求
- 流式 / 非流式 / 多轮工具链三种场景都通（与 O2A 那次的验收口径一致）
- 调用日志里 `downstream_protocol = CHAT_COMPLETIONS`、`upstream_protocol = RESPONSES`，
  且 chunk 列同时保留上下游两个视图（`DownstreamLogView` 的机制已就绪，
  只要正确传入 chunk 改写器）

---

## 阶段三：供应商配置与前端

范围较小，列清单即可：

- `supported_protocols` 的可选值加第三项 —— 后端 `ProviderAdminService.parseSupportedProtocols`
  已经用 `SUPPORTED_PROTOCOLS` 集合校验，加值即可
- 供应商编辑页的协议多选控件加一项（`types/protocol.ts` 的
  `ALL_WIRE_PROTOCOLS` 与 `WIRE_PROTOCOL_LABELS` 已是单一真源，改那里）
- `responses_base_url` 的输入框（参照 `anthropic_base_url` 的既有形态，
  含"留空回退"的提示文案）
- 请求体规则编辑器的协议分组加一项（`features/request-body-rules/` 下的
  `types.ts` 与 `migration.ts`）
- 供应商编辑页的 URL 预览 `features/provider-config/protocolUrls.ts` 加一条

前端的 `protocolUrls.spec.ts`、`migration.spec.ts` 都会因为新增枚举值需要更新，
那是预期的。

---

## 附：几条跨阶段的注意事项

**`json_valid` 约束会卡住脏数据。** 两次重建表都要给带 CHECK 的列写 `CASE` 兜底，
理由见 1.3 陷阱 A。这是 V9 踩过的坑。

**`.mvnw clean` 可能因服务占用 `target/classes/static` 而失败。** 用
`compiler:compile compiler:testCompile surefire:test` 绕过。另外 `surefire:test` 单跑时
`SpaRoutingConfigTests` 可能因静态资源未同步而报 503，加 `resources:resources` 即可。

**测试类名必须以 `Tests` 结尾**，Surefire 只拾取这个后缀。

**不要主动启动服务或操作根目录 `admin.db`。**

**改动带长篇 Javadoc 的类时先读完那段注释。** 本方向要动的几个类（`WireProtocol`、
`ProviderProtocolSupport`、`ProtocolTranslator`、`DownstreamLogView`）注释都很长，
且都记着「为什么不能改成另一种写法」的结论。1.5 那个陷阱就是一个例子 ——
注释明确写了「不为第三种协议做准备」，而现在正要做第三种。遇到这种情况**更新那段注释**，
说明判断为何变了，不要默默改掉代码留下过时的解释。

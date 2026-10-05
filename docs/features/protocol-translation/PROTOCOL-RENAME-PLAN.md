# 协议命名重构规划（第一步：纯重命名）

> **状态**：规划中，尚未动手。本文件是「三协议命名重构」第一步的实现契约。
>
> 相关：[AGENTS.md](../../../AGENTS.md)、[数据库迁移 Skill](../../../.github/skills/cosp-schema-migration-skill/SKILL.md)、
> [请求侧翻译契约](./chat-messages/REQUEST-CONTRACT.md)、[响应侧翻译契约](./chat-messages/RESPONSE-CONTRACT.md)

## 0. 这一步为什么必须单独做

目标是接入 OpenAI **Responses API**，但现有命名容纳不了它。`OPENAI` 这个名字同时承载
两件事：**「OpenAI 这家公司」**与**「Chat Completions 这个接口」**。两协议时代没有造成
伤害（OpenAI 只有一个接口在用），加入 Responses 后立刻自相矛盾 —— Responses **也是**
OpenAI 的，而 `OPENAI` 却指代 Chat。

所以整体分三步走，本文件只覆盖第一步：

| 步骤 | 内容 | 行为变化 |
|---|---|---|
| **① 纯重命名（本文件）** | 枚举值、类名、前端类型、文档术语；schema 重建 + 存量重映射 | **零** |
| ② 加 RESPONSES 协议 | 枚举加值、`responses_base_url`、通用上游服务、调度回退序落地 | 新增线路 |
| ③ C2R / R2C 翻译 | 请求侧改写 + 响应侧 SSE 状态机 | 新增翻译方向 |

**第一步单独做的唯一理由：它是三步中唯一「行为必须零变化」的一步，因此测试全绿就是
完整证明。** 混进第二步后，一个失败的用例无法区分「重命名改错了」还是「新协议逻辑有
问题」。反过来，先完成重命名再加协议，第二步动手时 `C`/`M`/`R` 已经就位，不必在同一次
改动里同时处理「旧名歧义」与「新协议语义」。

---

## 1. 命名定案

三个名字都取自各自的 **API 路径全称**，同一维度，不混入厂商名：

| 现在 | 改为 | 展示名 | 缩写 |
|---|---|---|---|
| `OPENAI` | `CHAT` | Chat Completions API | **C** |
| `ANTHROPIC` | `MESSAGES` | Anthropic API | **M** |
| （第二步新增） | `RESPONSES` | Responses API | **R** |

方向标识随之改写：`O2A` → **`C2M`**，`A2O` → **`M2C`**。第二步之后还会有
`C2R` / `R2C` / `M2R` / `R2M`。

**枚举名与展示名刻意不同**：枚举名指「接口」（`MESSAGES`），展示名用官方叫法
（`Anthropic API`）。若枚举也叫 `ANTHROPIC`，读者会以为 M 那条线与 C/R 不是同一类东西。

**持久化字面量与枚举常量名保持逐字一致**（AGENTS.md 的既有不变量）。绝不引入
「代码叫 CHAT、库里存 OPENAI」的映射层 —— 那会让此后每个读日志的人都得知道这层翻译。

---

## 2. 影响面清单

实测范围：后端主代码 10 文件 22 处枚举引用、测试 15 文件 113 处、前端 45 文件、
schema 与迁移各 1 处、文档 3 份。以下按「改动性质」而非目录组织，因为验证方式不同。

### 2.1 硬阻塞：`api_call_log` 两列的 CHECK 约束

```sql
downstream_protocol TEXT NOT NULL DEFAULT 'OPENAI' CHECK (downstream_protocol IN ('OPENAI', 'ANTHROPIC'))
upstream_protocol   TEXT NOT NULL DEFAULT 'OPENAI' CHECK (upstream_protocol   IN ('OPENAI', 'ANTHROPIC'))
```

这是**唯一必须动 schema 的地方，且必须整表重建** —— SQLite 的 `ALTER TABLE` 改不了
已有列的 CHECK、DEFAULT 与类型（三者都要改：CHECK 换白名单、DEFAULT 换 `'CHAT'`）。

**重建成本已实测，风险可接受**：

| 指标 | 实测值 | 含义 |
|---|---|---|
| 总行数 | 8960 | `INSERT...SELECT` 秒级 |
| 未瘦身行数 | 1016 | 只有这些行携带大载荷 |
| `call-log.max-records` | 1000（默认） | **未瘦身行数有上界，重建成本恒定** |

`ApiCallLogRetentionTask` 只清空载荷列、**从不删行**，所以总行数随时间增长；但真正重的
只有保留窗口内那 ~1000 行，已瘦身的行五个大列全空。因此重建成本不会随使用时间恶化。

**本次白名单一次到位写三个值**（含第二步才会用到的 `RESPONSES`），避免第二步再重建一次
同一张表。这不是超前设计：CHECK 白名单多一个暂未使用的合法值零成本，而重建大表两次有
实际风险。

### 2.2 软约束：`provider_config.supported_protocols`

```sql
supported_protocols TEXT NOT NULL DEFAULT '["OPENAI","ANTHROPIC"]' CHECK (json_valid(supported_protocols))
```

CHECK 只验 JSON 合法性、**不验元素取值**，因此不阻塞。但两件事必须做：

- 存量值重映射：`["OPENAI","ANTHROPIC"]` → `["CHAT","MESSAGES"]`
- DEFAULT 改成新名字

**不迁移数据会产生「侥幸不坏但语义已错」的状态**：`ProviderProtocolSupport.parse` 对
未知协议名是**忽略而非报错**，于是存量 `["OPENAI","ANTHROPIC"]` 会被逐个忽略 → 落到
「没有任何可识别协议 → 回退全集」。功能表面正常，但配置已经失去意义，且日志里会刷
warn。必须迁移。

### 2.3 后端 Java：枚举、类名、包名

| 对象 | 现在 | 改为 |
|---|---|---|
| 枚举值 | `WireProtocol.OPENAI` / `.ANTHROPIC` | `.CHAT` / `.MESSAGES` |
| 请求翻译器 | `OpenAiToAnthropicRequestTranslator` | `ChatToMessagesRequestTranslator` |
| 响应翻译器 | `AnthropicToOpenAiResponseTranslator` | `MessagesToChatResponseTranslator` |
| 非流式子翻译器 | `AnthropicToOpenAiNonStreamTranslator` | `MessagesToChatNonStreamTranslator` |
| 流式子翻译器 | `AnthropicToOpenAiStreamTranslator` | `MessagesToChatStreamTranslator` |
| 流式状态机 | `A2OStreamState` | `M2CStreamState` |
| 字段名 | `o2aTranslator` / `a2oTranslator` | `c2mTranslator` / `m2cTranslator` |

主代码 22 处枚举引用分布在 10 个文件，其中值得单独留意的：

- `MessagesService` / `ChatCompletionService` 的 `DOWNSTREAM_PROTOCOL` 常量
- `GenericAnthropicChatService` 有 6 处（含 3 处 `.name()` 落库）
- `ProviderModelDiscoveryService` 有 4 处（含一处 `protocol == null` 兜底）
- `ProviderRequestTransformService.PROTOCOLS` 的字符串白名单 `Set.of("OPENAI", "ANTHROPIC")`

**服务类名与包名本轮不改**（`application/openai/`、`application/anthropic/`、
`provider/generic/openai/`、`GenericOpenAiChatService`、`GenericAnthropicChatService`）。
理由：它们指的是「服务哪一侧的哪个端点」，而端点归属并未改变；把包名一起动会让这次
diff 从「术语替换」膨胀成「目录重排」，评审时看不出哪些是机械替换、哪些是真实改动。
第二步引入 `RESPONSES` 时再评估是否需要按协议重组包结构。

### 2.4 前端：类型、缩写映射、字面量

- `types/protocol.ts`：`WireProtocol` 联合类型、`ALL_WIRE_PROTOCOLS`、
  `WIRE_PROTOCOL_LABELS`、`isWireProtocol` 的字面量判断
- `stores/callLifecycle.ts`：`protocolAbbreviation` 的 `{OPENAI:'O', ANTHROPIC:'A'}` 表
- `features/provider-config/protocolUrls.ts`：`DEFAULT_NEW_PROVIDER_PROTOCOLS`、
  `normalizeProtocols` 的取值校验
- `features/request-body-rules/ruleSetJson.ts`：校验消息里的 `"OPENAI" 或 "ANTHROPIC"`
- `features/request-body-rules/migration.ts`：V1 迁移把存量规则归入 `["OPENAI"]` 单组

**顺手收敛一处重复**：协议缩写映射当前有三份独立实现 ——
`stores/callLifecycle.ts` 的 `protocolAbbreviation`、`views/UsageLog.vue` 与
`views/CallLog.vue` 各自的 `formatCallType` 三元表达式。三处都要改，正好收敛到
`types/protocol.ts` 或 `features/call-log/` 一处。这是本轮**唯一允许的结构性改动**，
因为不收敛就要在三个地方各写一遍同样的三值映射，而第二步加 R 时又要再改三处。

### 2.5 文档

- [REQUEST-CONTRACT.md](./chat-messages/REQUEST-CONTRACT.md)（请求侧）：O2A/A2O 术语贯穿全文
- [RESPONSE-CONTRACT.md](./chat-messages/RESPONSE-CONTRACT.md)（响应侧）：同上
- `AGENTS.md`：架构图、协议支持说明、思考深度两侧字段说明中的协议名

---

## 3. 迁移设计（V12）

当前 `CURRENT_SCHEMA_VERSION = 11`，本次取 **12**（连续整数，见迁移 Skill）。

### 3.1 迁移内容

一个版本内完成三件事，因为它们必须同时生效（协议名的代码侧与库侧不能错开）：

1. **重建 `api_call_log`**：CHECK 扩为三值白名单、DEFAULT 改 `'CHAT'`，
   搬迁时把存量 `'OPENAI'` → `'CHAT'`、`'ANTHROPIC'` → `'MESSAGES'`
2. **重映射 `provider_config.supported_protocols`** 的存量值，并改 DEFAULT
3. **重映射 `provider_request_transform.body_rules_json`** 里每个规则组的
   `protocols` 数组元素

第 3 项容易漏：规则组的适用协议存在 JSON 里，不是独立列。

### 3.2 重建 `api_call_log` 的注意事项

参照 V9 重建 `provider_model` 的流程（建新表 → 搬数据 → 删旧表 → 改名 → 重建索引），
但本表更简单：

- **无触发器**，因此不存在 V9 那个「`DROP TABLE` 静默删除触发器」的坑
- **有两个索引必须显式重建**（`DROP TABLE` 同样会带走它们）：
  ```sql
  idx_api_call_log_created_id          ON api_call_log(created_at DESC, id DESC)
  idx_api_call_log_provider_created_id ON api_call_log(provider_key, created_at DESC, id DESC)
  ```
- **逐列显式列出，不用 `SELECT *`**：这张表历经多次 `ADD COLUMN`，物理列顺序不可假定
- **搬迁时用 `CASE` 完成重映射**，一条 `INSERT ... SELECT` 内同时改名，
  不做「先搬后 UPDATE」两步 —— 新表 CHECK 已经不接受旧值，搬进去就会失败

映射用 `CASE` 而非两条 UPDATE，同时也避开了迁移 Skill 记录的「一次性重映射必须同时
发生」那个坑（本次两个值互不重叠，但保持同一写法更稳）。

### 3.3 幂等性

三项都要能重复执行：

- `api_call_log` 重建：以「CHECK 是否已含 `CHAT`」或版本号为准，不重复重建
- 两处重映射：旧值已不存在时 `UPDATE` 影响 0 行，天然幂等

---

## 4. 明确不做的事

这一轮的价值全在「行为零变化，因此可被测试完整证明」。以下都是**顺手就能做但绝不能
在本轮做**的，混进来会毁掉这个性质：

| 不做 | 原因 |
|---|---|
| 加 `RESPONSES` 枚举值 | 属第二步；本轮加了就有「新协议是否可达」的行为问题 |
| 加 `responses_base_url` 列 | 同上。按 V8.8 `anthropic_base_url` 的 `ADD COLUMN` 范式，零重建风险，第二步做 |
| 落地 C→R→M 回退序 | 两协议下回退目标唯一，写了也无法验证；第二步随 R 一起做 |
| 改 `ProtocolDispatchManager` 的规则 2 | 同上 |
| 改服务类名与包名 | 见 §2.3，会让 diff 从术语替换膨胀成目录重排 |
| 修 `ProviderProtocolSupport` 的乐观回退语义 | 那是三协议下才出现的问题（见 §6），本轮 `EnumSet.allOf` 行为不变 |
| 实现 M2C 请求 / C2M 响应 | 属第三步 |

---

## 5. 验证

改动性质决定验证方式 —— **行为零变化意味着现有测试是完整的证明**：

| 层 | 命令 | 期望 |
|---|---|---|
| 迁移 | `.\mvnw.cmd test -Dtest=SchemaMigrationRunnerTests` | 全绿，且新增 V12 用例 |
| 后端全量 | `.\mvnw.cmd test` | **817 例全绿**（不增不减，除迁移新增） |
| 前端类型 | `vue-tsc -b` | 0 错误 |
| 前端全量 | `npm run test:run` | **910 例全绿** |

**测试数量不应因重命名而变化**（除 V12 迁移用例）。若出现用例增减，说明这一轮混进了
行为改动，应当拆出去。

### 5.1 迁移测试（按 Skill 的三层契约）

1. **前驱版本用例**：构造 V11 结构 → 跑 runner → 断言 `api_call_log` 的 CHECK 已含三值、
   存量协议值已重映射、两个索引存在；**跑两次验证幂等**
2. **递归链**：沿用 `registeredMigrationVersions()` 迭代，不新增硬编码终版用例
3. **空库路径**：真实 `schema.sql` 建库 → 跑 runner → 断言基线结构
4. **不重放守卫**：`upgradeFromPreviousVersionRunsOnlyTheMissingMigration` 与两个
   `crossVersionUpgrade*` 用例**不动**（按 Skill，它们按构造就是版本无关的）

现有 `SchemaMigrationRunnerTests` 有 4 处断言旧协议字面量（第 439-443 行附近的
`assertProtocols(jdbcTemplate, "openai-stream", "OPENAI")` 等），需要区分：
**那些是 V8.6 迁移的断言，锚定的是固定历史版本的行为**。V8.6 当时写入的就是 `'OPENAI'`，
但 V12 之后那些行会被重映射成 `'CHAT'`。因此这些断言要改成 V12 之后的期望值，
而不是保留旧字面量 —— 它们不是「固定历史 fixture」，而是「跑完全部迁移后的终态」。

### 5.2 全文检索兜底

改完后应当搜不到残留（参考项目目录除外）：

```
WireProtocol.OPENAI | WireProtocol.ANTHROPIC
"OPENAI" | "ANTHROPIC"        （src/ 与 frontend/src/ 内）
O2A | A2O | OpenAiToAnthropic | AnthropicToOpenAi
```

⚠️ `cc-switch/`、`new-api/`、`sub2api/` 是参考项目，命中一律忽略。
`src/main/resources/static/` 是前端构建产物，会在下次构建时自动覆盖。

---

## 6. 留给第二步的两个已知问题

本轮不修，但记在这里避免第二步重新发现：

**① `ProtocolDispatchManager` 规则 2 在三协议下产生隐式偏好。**
现在的实现是「挑第一个供应商支持的」：

```java
for (WireProtocol candidate : WireProtocol.values()) {
    if (supported.contains(candidate)) { return translated(downstreamProtocol, candidate); }
}
```

两协议时结果唯一（只剩一个候选），三协议时不唯一 —— 下游 `CHAT`、供应商支持
`{MESSAGES, RESPONSES}` 时走哪条，答案由**枚举声明顺序**决定，那是巧合而非设计。

**已定案的解法**：直连优先级最高；需要翻译时按 **C → R → M** 固定顺序挑第一个供应商
支持的。落地时**回退序必须写成独立常量**（如 `TRANSLATION_FALLBACK_ORDER`），
不复用枚举声明顺序 —— 否则又变回隐式，且「为什么是这个顺序」没有地方写注释。

这个解法保住了调度器「不关心哪个方向有实现」的职责边界：固定序让结论可预测，
挑中未实现方向时由调用方抛 `ProtocolTranslationNotSupportedException`。

**② `ProviderProtocolSupport` 的乐观回退语义在三协议下要重新审视。**
类注释当前写着「不为将来可能有第三种协议做准备 —— 第三种协议出现的概率极低」，
这个理由将被推翻。代码写法（`EnumSet.allOf`）恰好是对的，但行为后果变了：
加入 `RESPONSES` 后，「解析失败回退全集」会把 `RESPONSES` 也算上，而多数供应商
**并不支持** Responses。回退从「回到 V8.8 之前的宽容行为」变成「声称支持一个大概率
不存在的端点」。

---

## 7. 建议提交粒度

一次提交完成，commit message 说明「行为零变化」：

```
refactor(protocol): 协议名按 API 路径全称重命名，为 Responses 接入让出命名空间

OPENAI → CHAT、ANTHROPIC → MESSAGES；O2A/A2O → C2M/M2C。
V12 重建 api_call_log 扩 CHECK 白名单至三值并重映射存量协议字面量。
行为零变化，后端 817 + 前端 910 例全绿。
```

理由：schema 迁移与代码重命名**不能分开提交** —— 中间态下代码写 `CHAT` 而库的 CHECK
只认 `OPENAI`，任何一次调用落库都会失败。

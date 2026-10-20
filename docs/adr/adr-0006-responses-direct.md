# ADR-0006: Responses 直连的端点与执行器设计

- **状态**：Accepted
- **日期**：2026-09-17（原 `RESPONSES_PROTOCOL_IMPLEMENTATION_PLAN.md` 首次入库）
- **取代**：（无）
- **相关**：[RFC：Responses 直连](../working/rfcs/2026-09-17-responses-direct.md)、
  [ADR-0004 C2R](adr-0004-c2r-request-translation.md)

## 背景

在 C2R 翻译存在的前提下，还有一个更直接的路径：**让 COSP 原生接受 `/v1/responses`**，
把 Responses 作为**下游**协议（而不是仅作为翻译的**上游**目标）。这样 Responses 原生客户端
（如 Codex CLI）可以不经翻译直接使用。

需要解决三件事：
1. **数据模型**：`downstream_protocol` / `upstream_protocol` 列要能存第三个值
2. **枚举与读写链路**：`WireProtocol` 加值后，所有 switch/if 都要正确分支
3. **执行器**：Responses 上游的请求/响应形态与 Chat 不同（`input` 数组、事件流 18 类）

## 决策

### 1. 数据库迁移走独立版本 V13，冻结 V12 的版本号常量

新增 V13 迁移做三件事：加协议列、回填存量数据、调整 `schema.sql` 默认值。

**V12 的版本号常量必须冻结为字面量**，不能用 `CURRENT_SCHEMA_VERSION`——历史的教训是
V13 曾用动态常量，抬高版本号后 V13 把自己写成新版本，导致 V13 与 V14 一起被跳过。

### 2. 枚举加值时，两处「两个协议」硬假设必须显式处理

代码里有两处从「正确」变成「悄悄错」的地方，且**都不报错**：
- 某处 `if (protocol == MESSAGES) ... else ...` 假设只有两种协议
- 某处默认值/白名单只列了两项

这类问题的特征是**加了第三个值后行为静默改变**，因此加枚举时要逐点排查并加测试钉住。

### 3. `max_output_tokens` 不接

AGENTS.md 已有明确记载：`MaxOutputTokensSetting` 只有 Anthropic 线路消费，因为那条线路
`max_tokens` **必填**，不补就发不出去；OpenAI 线路刻意不接，因为那边该字段**可选**，
接上会给所有「下游没带」的调用凭空补一个上限。

Responses 的 `max_output_tokens` 同样可选，**按同一条原则不接**。

这一点很容易顺手接上（名字就叫 `max_output_tokens`，看起来天造地设），
**所以代码里要写清为什么不接**，而不是留个空白让人补。

### 4. 思考深度的 `Off` 档首次有原生表达

Responses 用 `reasoning.effort`，且调研已一手核验它**支持 `"none"`**
（`new-api/relaykit/relayconvert/reasoning/intent.go:15` 的 `EffortNone`，
且 `ParseEffort` 接受它）。

因此 R 线路的 `Off` 档直接发 `reasoning.effort: "none"`，**不必**像 OpenAI Chat 侧那样
借 `thinking: {"type": "disabled"}` 这个方言。

给 `ReasoningEffortSetting` 加第三个 `applyToResponses`，三条线路各写各的出站字段。

### 5. 请求体规则组的协议字段保持「缺失视为全协议」

**不给存量规则组追加 `RESPONSES`**。规则组的 `protocols` 字段缺失时视为全协议，
因此存量规则组**自动适用于新协议**——这是既有语义的自然结果，不是遗漏。
往里追加 `RESPONSES` 反而会破坏「未声明」这个状态。

### 6. 执行器独立，不继承 `AbstractUpstreamChatService`

Responses 的请求/响应形态与 Chat 差异过大（`input` 数组 vs `messages`、
18 类事件 vs 扁平 chunk），继承会带来大量空实现与语义扭曲。
采用 `GenericResponsesChatService` + 独立 `ResponsesUsageParser` + `ResponsesStreamEvents`。

### 7. 空响应判定器：安全超集（7 个终态事件）

多认不错（永不匹配则退回不拦截）；删掉万一兼容端点真发反而危险。

## 后果

**正面**

- Responses 原生客户端可直连，无需翻译开销
- `max_output_tokens` 不接保持了与 Chat 线路一致的语义（该字段可选就不补）
- 规则组「缺失即全协议」的既有语义自然覆盖新协议，零迁移成本
- 独立执行器让 Responses 的事件流处理可以按自己的形态演进，不被 Chat 的抽象约束

**负面**（知情成本）

- **枚举加值的排查成本高**：两处「两个协议」硬假设不报错，只能靠人工排查 + 测试钉住
- **`Off` 档在三条线路有三种出站形态**（`thinking` 方言 / `reasoning.effort` / `none`），
  新增线路时要重新判断用哪种
- **执行器不共享意味着请求构造、重试、落库等逻辑有重复实现**。这是刻意的取舍：
  形态差异太大时，共享抽象的成本高于重复

## 备选方案

### 方案 A：让 Responses 走 C2R 翻译路径（不新增端点）

- 描述：下游仍打 `/v1/chat/completions`，内部翻译成 Responses
- 拒绝理由：**Responses 客户端必须改协议**才能用。且 C2R 翻译会丢失 Responses 独有特性
  （`previous_response_id`、`store`、`conversation` 等）。直连才能完整支持

### 方案 B：执行器继承 `AbstractUpstreamChatService`

- 描述：复用请求构造与重试骨架
- 拒绝理由：Chat 的骨架围绕 `messages` 数组与扁平 chunk 设计，Responses 的 `input` 数组与
  18 类事件要大量覆盖方法。实测结果是半数方法空实现，抽象得不偿失

### 方案 C：`max_output_tokens` 接上设置层

- 描述：既然字段同名，就统一按 Anthropic 线路那样注入
- 拒绝理由：**凭空给调用加一个上限**会改变用户可观察行为（模型输出被截断）。
  Anthropic 线路接是因为字段必填、不接就发不出去；Responses 字段可选，接上就是无理由的
  行为变更

## 参考

- 完整实施计划与记录（含四阶段、逐点清单）：[RFC：Responses 直连](../working/rfcs/2026-09-17-responses-direct.md)
- 结果摘要：[同 RFC §A](../working/rfcs/2026-09-17-responses-direct.md#a-结果摘要)
- 迁移规则：[数据库迁移 Skill](../../.github/skills/cosp-schema-migration-skill/SKILL.md)

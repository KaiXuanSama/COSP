# ADR-0004: C2R 去程翻译的六项决策

- **状态**：Accepted
- **日期**：2026-09-30（调研与计划定稿）
- **取代**：（无）
- **相关**：[RFC §C C2R 去程](../working/rfcs/2026-09-03-protocol-translation.md#c-c2r-去程--计划与实施记录)、
  [ADR-0003 忠实搬运](adr-0003-translator-faithful-mapping.md)

## 背景

C2R = 下游 Chat Completions → 上游 Responses。两者同属 OpenAI 家族，但接口形态差异不小：
`messages` 数组 vs `input` 数组、`system` 消息 vs `instructions` / `developer` role、
扁平 `tools` vs 嵌套 `tools`、`reasoning_effort` vs `reasoning.effort`。

调研对比了四个参考项目（new-api / sub2api / CPA / 本服务既有 C2M），发现**它们在关键字段上
各走各路**，有些做法互相矛盾。因此需要一组明确决策，而不是"挑一个项目照抄"。

## 决策

六项决策，每项都在调研中对比了至少三个参考实现。

### 1. `system` 落点：留在 `input`，role 改写为 `developer`

**不提升到顶层 `instructions`。**

依据：Responses 官方语义里 `system` 与 `developer` 是同一层级的东西，而 Responses 的
message item 官方角色集合不含 `system`。CPA 作者**试过提升路线后改走 developer 路线**
（其 `instructions` 提取代码整段被注释掉），是现成实证。实测 Codex 流量里双通道并存，
说明"单通道"是简化，但 C2R 拿到的下游消息**没有判据**做二分拆分，因此不搞启发式。

落点由新的 `ResponsesSystemPromptStage` 支线承担，而非翻译器——对齐 C2M 的
`MessagesSystemPromptStage` 分工先例。

### 2. `store` / `include`：强制注入

`store: false`、`include: ["reasoning.encrypted_content"]`。

- `store:false`：无状态代理不做服务端持久化
- `include:[...]`：索要密文让**回程翻译器**拿得到完整上下文。无服务端状态 + 无回传 = 多轮思考断链

这是**给回程铺路的基础设施字段**，不是替客户端做生成参数决策。实测第三方上游不签发密文
（强制无害）；一旦上游真签发（官方 OpenAI），R2C 需要它。

### 3. `max_output_tokens`：映射、不 clamp、不补默认

`max_completion_tokens` 优先于 `max_tokens`，值原样搬运。

clamp 属"自动修正"（上游对 `<16` 自己报错）；补默认值是设置层职责。CPA 的完全不映射
被调研列为"不应照抄"。

### 4. 工具：忠实搬运

只翻译下游实际传入的工具，**不捏造、不凭空去除**。`namespace` / `web_search` 是 Responses
原生客户端才会发的形态，Chat 请求里不存在——不去合成。移除与否交给请求体规则（用户显式配置）。

### 5. `id`：不造

`tool_calls[].id` → `function_call.call_id`；`tool` 消息的 `tool_call_id` →
`function_call_output.call_id`。item 级 `id` 与 assistant `message.id` **不合成**。

实测一半样本（stepfun 0/6、0/5）不造 id 照样被接受——请求侧 `id` 可选、上游不校验。
配对语义靠 `call_id`。只填**有来源**的字段。

### 6. `reasoning_effort` → `reasoning.effort`，**删原字段**；`summary` 不写

删原字段的依据：`applyToResponses` 的表态判定只看 `reasoning.effort`，改名后设置层自然
看到表态；保留旧字段反而有害（Chat 字段，Responses 上游可能拒收；这条线没有 MESSAGES 侧
那样的收尾清理方）。

`summary` 不写：显式 opt-in，不与 effort 耦合（采纳 CPA 理由）。

### 三条「不应照抄 CPA」

调研同时明确排除了三个看起来很方便的做法：**不补默认 effort、不剥 `temperature`/`top_p`、
不跳过 `max_output_tokens` 映射**。采样参数原样搬运，由上游用错误码回答——这是
[ADR-0003 忠实搬运](adr-0003-translator-faithful-mapping.md)的直接应用。

## 后果

**正面**

- 六项决策各自有实证依据（参考项目代码 + 真实抓包），而非"看起来更合理"
- `system` 落点选 developer 路线避免了启发式拆分，行为可预测
- `include` 的强制注入为 R2C 回程预留了思考链完整性，是**跨方向的基础设施投资**

**负面**（知情成本）

- **依赖上游容忍未映射字段**。下游若传了 Responses 不认识的 Chat 专有字段，会由上游报错，
  而不是被翻译器过滤。这与 ADR-0003 的取向一致，但对用户而言是「错误更靠上游」
- **`store:false` 与 `include` 是硬编码注入**，不接受下游覆盖。若将来需要支持有状态上游，
  需要重新决策（新建 ADR 取代本条）
- **`system` 方向的落点决定了回程也必须按 developer 处理**，两个方向被绑在一起。
  改一处要考虑另一处

## 备选方案

### 方案 A：`system` 提升到顶层 `instructions`（new-api 路线）

- 描述：把 system 消息移出 `input`，写入 `instructions` 字段
- 拒绝理由：实测 Codex 流量双通道并存（`instructions` 与 `input[].developer` 同时出现），
  而下游 Chat 请求没有判据做二分拆分；强行提升会改变语义优先级。CPA 试过后放弃

### 方案 B：`system` 原样保留为 `role: "system"`（sub2api 路线）

- 描述：不动 role，直接放进 input
- 拒绝理由：sub2api 的实现里该 role 会**落入 default 分支被当成 `user`**（其自身缺陷）。
  且 Responses 官方 message item 角色集合不含 `system`

### 方案 C：不注入 `store` / `include`，完全透传下游意愿

- 描述：下游没说就不发
- 拒绝理由：无状态代理若让上游持久化会产生孤儿服务端状态；不索要密文则多轮思考断链
  （R2C 拿不到完整上下文）。这两个字段不是"生成参数"，是代理自身运作模式的必要声明

## 参考

- 完整调研（四项目对比 + 真实抓包）：[RFC §B](../working/rfcs/2026-09-03-protocol-translation.md#b-c2r-去程--调研)
- 实施记录：[RFC §C](../working/rfcs/2026-09-03-protocol-translation.md#c-c2r-去程--计划与实施记录)
- 代码：`ChatToResponsesRequestTranslator`、`ResponsesSystemPromptStage`

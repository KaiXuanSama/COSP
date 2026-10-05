# C2R 去程翻译（请求体）实施计划

> **状态**：三阶段全部完成（2026-10-04：翻译器 + system 支线 + 实机验证）。去程可用；
> 回程（R2C）也已完成（2026-10-04 三家供应商实机验证 + §7.4 修复闭环，见
> [R2C-PLAN.md](./R2C-PLAN.md)）—— C2R 线路已端到端可用
>
> **需求背景**：让下游 Chat Completions 请求（`/v1/chat/completions`）能发往只支持
> Responses 的上游供应商。这是 protocol-translation 功能 C2R 方向的**前半（去程）**；
> 回程（R2C，Responses 事件流 → Chat chunk）是另一半，已由
> [R2C-PLAN.md](./R2C-PLAN.md) 完成。
>
> 相关：[C2R-RESEARCH.md](./C2R-RESEARCH.md)（四项目对比 + 真实抓包，本文决策的事实基础）、
> [R2C-RESEARCH.md](./R2C-RESEARCH.md)（回程翻译调研与决策，下一步的素材）、
> [../chat-messages/REQUEST-CONTRACT.md](../chat-messages/REQUEST-CONTRACT.md)
> （C2M 契约，翻译口径的先例）、
> [../chat-messages/RESPONSE-CONTRACT.md](../chat-messages/RESPONSE-CONTRACT.md) §16
> （C2R 响应事件序列的实测记录，回程的素材）。

---

## 0. 决策与理由（六项已定）

C2R-RESEARCH §7 列了 14 个决策点、§9 列了 6 个未决项，本节记录已拍板的结论。
「依据强度」高的项直接定案；system 落点含一个分工确认点（§6）。

| # | 决策点 | 结论 | 理由 |
|---|---|---|---|
| 1 | `system` 落点 | **方案 B（CPA 对齐）**：留在 `input`，role 改写为 `developer`；`instructions` 不写 | 与 Responses 官方语义最贴（`system` 与 `developer` 是同一层级的东西）；CPA 作者试过提升路线后改走此路（其 `instructions` 提取代码整段被注释）是现成实证。实测 Codex 双通道并存（§10.2）说明「单通道是简化」，但 C2R 拿到的下游消息**没有判据**做二分，不搞启发式拆分 |
| 2 | `store` / `include` | **强制注入（CPA 对齐）**：`store:false`、`include:["reasoning.encrypted_content"]` | 沿用 sub2api 函数头的成文理由：无状态代理不做服务端持久化；索要密文让**回程翻译器**拿得到完整上下文（无服务端状态 + 无回传 = 多轮思考断链）。实测第三方上游不签发密文（§10.1），强制无害；一旦上游真签发（官方 OpenAI），R2C 回程需要它——这是给回程铺路的基础设施字段，不是替客户端做生成参数决策 |
| 3 | `max_output_tokens` | **映射、不 clamp、不补默认**：`max_completion_tokens` 优先于 `max_tokens`，值原样搬运 | clamp 属「自动修正」，上游对 `<16` 自己报错（与「报错而非静默修正」一致）；CPA 的整个不映射已被调研列为「不应照抄」。补默认值是 `MaxOutputTokensSetting`（设置层四模式）的职责。**注入现状（已核实）**：max tokens 支线只有 MESSAGES 一侧（`MessagesMaxTokensStage` + `MaxTokensNormalizer.ensureMaxTokens`，因 Anthropic 把 `max_tokens` 列为必填）；RESPONSES 侧支线不存在，属本方向新增件；CHAT 直连不注入是**语义缺失**（见 §6.4 TODO，不在本分支修） |
| 4 | 工具处理 | **忠实搬运**：只翻译下游 Chat 实际传入的工具，不捏造、不凭空去除 | `namespace` / `web_search` 是 Responses 原生客户端才会发的形态，Chat 请求里不存在——不去合成。下游传了什么就翻译什么，移除与否交给请求体规则（用户显式配置），不交翻译器猜 |
| 5 | `id` 造不造 | **不造**：`tool_calls[].id` → `function_call.call_id`；`tool` 消息的 `tool_call_id` → `function_call_output.call_id`；item 级 `id` 与 assistant `message.id` 不合成 | 实测一半样本（stepfun 0/6、0/5）不造照样被接受——`id` 在请求侧可选，上游不校验。配对语义靠 `call_id`（`function_call_output` 恒带 `fco_` 前缀 id 的那个是上游自己的事）。只填**有来源**的字段 |
| 6 | effort / summary | `reasoning_effort` → `reasoning.effort`（改名后**删原字段**，与 C2M 相反，依据见下）；`reasoning.summary` **不写** | `applyToResponses` 的表态判定**只看 `reasoning.effort`**（Javadoc 明写「不像 Anthropic 侧兼看 reasoning_effort，那条线路只有直连」）——改名后设置层自然看到表态，保留反而有害：① `reasoning_effort` 是 Chat 字段，Responses 上游可能拒绝多余字段；② 这条线没有 MESSAGES 侧那个 `body.remove` 收尾方。注入已有专门支线（`ResponsesThinkingStage` + 四模式）。summary 不写采纳 CPA 理由：显式 opt-in，不与 effort 耦合 |

**三条「不应照抄 CPA」同时生效**（C2R-RESEARCH §7 末）：不补默认 effort、不剥
`temperature`/`top_p`、不跳过 `max_output_tokens` 映射。采样参数（`temperature` /
`top_p`）原样搬运，由上游用错误码回答。

---

## 1. 范围与预期中间态

- **只做去程**：`ChatToResponsesRequestTranslator` + 一个新支线（见 §3）。
- **回程未实现期间的既定行为**：`TranslatorRegistry.findResponseTranslator(CHAT, RESPONSES)`
  未命中 → 编排层**原样透传**上游 SSE（不静默，留痕）。下游 Chat 客户端收到
  Responses 原生事件流、解析不了 —— 与当年「C2M 先做去程」时完全相同的中间态，
  目的是「先用真实上游验证请求是否被接受」（`TranslatorRegistry` 的 Javadoc 契约）。
  回程落地后这条链才端到端可用。

> **后记**：此中间态是本计划实施期间的真实状态，作为历史记录保留；
> 回程已于 [R2C-PLAN.md](./R2C-PLAN.md) 落地，该链路现已端到端可用。

---

## 2. 字段映射总表

### 2.1 顶层标量

| Chat（下游） | Responses（上游） | 处置 |
|---|---|---|
| `model` | `model` | 原样 |
| `stream` | `stream` | 原样（`TranslationContext.wasStream` 记原始值） |
| `stream_options.include_usage` | —（丢弃） | 丢弃前记入 `TranslationContext.includeUsage`（Responses 的 SSE 自带 usage） |
| `max_completion_tokens` ?? `max_tokens` | `max_output_tokens` | 前者优先；只搬运不 clamp 不补默认 |
| `temperature` / `top_p` | 同名 | 原样搬运（不按模型剥离） |
| `reasoning_effort` | `reasoning.effort` | 改名后**删原字段**（决策 #6 依据：`applyToResponses` 只看 `reasoning.effort` 判表态；这条线无 remove 收尾方，留原字段反而可能被上游拒） |
| `tools` | `tools` | 嵌套→扁平，见 §2.3 |
| `tool_choice` | `tool_choice` | 展平，见 §2.3 |
| `parallel_tool_calls` | `parallel_tool_calls` | 原样 |
| `response_format` | `text.format` | 映射（外层 `type` + 内层 `json_schema` 合并；非 `json_schema` 类型原样放 `type`） |
| —（无） | `store` | **强制 `false`**（决策 #2） |
| —（无） | `include` | **强制 `["reasoning.encrypted_content"]`**（决策 #2） |
| —（无） | `instructions` | 不写（决策 #1） |
| `n` > 1 | — | **报错**（`RequestTranslationException`） |
| `messages` | `input` | 见 §2.2 |

Chat 侧**静默丢弃清单**（Responses 无对应物，进 §5.1 的清单测试）：
`frequency_penalty`、`presence_penalty`、`logit_bias`、`logprobs`、`top_logprobs`、
`seed`、`user`、`stop`、`service_tier`、`store`（Chat 侧同名但语义不同，见下）、
`function_call` / `functions`（legacy）。

> `stop`：Responses 无 `stop` 参数（有 `text.format` 但无停止序列），静默丢弃。
> legacy `function_call` 与 `tool_choice` 并存时**报错**（new-api 先例，可检测的矛盾）；
> 仅 legacy `function_call` 单独出现时报错（不支持 legacy 形态，翻译它属于新功能）。

### 2.2 `messages[]` → `input[]`（按 role 分派）

| Chat 消息 | Responses input item | 说明 |
|---|---|---|
| `role:"system"` | `{type:"message", role:"system", content:[…]}` | **role 不动**——改写 `developer` 是新支线的职责（§3.2）。位置与顺序保留 |
| `role:"user"` | `{type:"message", role:"user", content:[…]}` | 内容块映射见 §2.2.1 |
| `role:"assistant"`（纯文本） | `{type:"message", role:"assistant", content:[{type:"output_text",…}]}` | **不造 `id`**（决策 #5） |
| `role:"assistant"`（带 `tool_calls`） | 每个工具调用一个 `{type:"function_call", call_id, name, arguments}` item | `tool_calls[].id` → `call_id`；**不造 `id`**。若同一条消息还有文本，文本照常产 message item |
| `role:"assistant"`（带 `reasoning_content`） | 文本部分照常；`reasoning_content` **丢弃** | Chat 的明文思考无 Responses 请求侧对应物（`reasoning` item 是上游产出的，客户端回放靠密文或明文 text —— 下游 Chat 客户端本就不回放，见 BYOK 调查） |
| `role:"tool"` | `{type:"function_call_output", call_id, output:…}` | `tool_call_id` → `call_id`。**配对修复**：找不到前置 `function_call.call_id` 匹配时按 C2M 契约 §3.5 同类口径处理（CPA 批次状态机为参照：配不上则丢弃该条并留痕，不凭空造 `function_call` 去配） |

#### 2.2.1 内容块白名单（`content` 数组形态）

| Chat 块 | Responses 块 | 说明 |
|---|---|---|
| `{type:"text", text}` | `{type:"input_text"/"output_text", text}` | user/system → `input_text`，assistant → `output_text`（C2R-RESEARCH §4.1 两家一致） |
| `{type:"image_url", image_url:{url}}` | `{type:"input_image", image_url}` | 解包成纯 url 字符串；**丢 `detail`**（三家共识）；仅 user 消息 |
| `{type:"input_audio", …}` | `{type:"input_audio", …}` | 仅 user 消息 |
| `{type:"file", file:{…}}` | `{type:"input_file", …}` | `filename`/`file_data` 官方键 |
| `content` 为纯字符串 | 包成单元素 `input_text` 数组 | — |

不认识的块类型：**报错**（`RequestTranslationException`），不静默丢弃——与 C2M 契约
「不认识的形态报错」同口径。

### 2.3 工具与 tool_choice

```jsonc
// Chat（嵌套）                          →  Responses（扁平）
{"type":"function",                         {"type":"function",
 "function":{"name":"exec_command",          "name":"exec_command",
   "parameters":{…},                          "parameters":{…},
   "strict":false}}                           "strict":false}
```

- `strict`：下游带了原样搬；**缺省时显式写 `false`** —— 两侧默认值相反
  （Chat 默认 `false`、Responses 默认 `true`），省略不写等于悄悄改语义
  （CPA 注释是唯一因果说明，实测 10/10 工具全带 `strict:false` 佐证）
- `tool_choice` 展平：`{"type":"function","function":{"name":X}}` →
  `{"type":"function","name":X}`；`"auto"`/`"none"`/`"required"` 字符串形态原样
- **不合成** `namespace` / `web_search`（决策 #4）：那是 Responses 原生客户端的形态，
  Chat 请求里不会出现；若未来上游回显中出现，属回程（R2C）的问题

---

## 3. 组件与接线（总览）

### 3.1 `ChatToResponsesRequestTranslator`（新，阶段一）

- 位置：`pipeline/protocol/translate/`，`@Component`，声明 `(CHAT, RESPONSES)`，
  实现 `RequestProtocolTranslator`
- **`TranslatorRegistry` / `ProtocolDispatchManager` 零改动**：加 `@Component` 即自动进表；
  调度规则 2（`implementedRequestRoutes` 含 `(CHAT, RESPONSES)`）自动让
  「下游 CHAT + 供应商只勾 RESPONSES」命中翻译线路
- 签名：`translateRequest(Map<String,Object>) → TranslatedRequest`
  （`TranslationContext.fromDownstreamBody` 复用，为回程留出口——契约 §7 要求）
- 结构参照 `ChatToMessagesRequestTranslator`：**白名单制**（没被显式搬过来的顶层字段一律
  丢弃，Javadoc 记着「链条末尾只有 removeIf(isNull)，非 null 多余字段会原样发给上游然后
  400」）、消息遍历产 item 流、子翻译器组合（MessageTranslator/ToolTranslator 私有类或
  同包协作类，实现时按 C2M 的粒度拆）
- `ObjectMapper` 注入（data-url 图像解析可能需要，参照 C2M `ContentBlockTranslator`）

### 3.2 `ResponsesSystemPromptStage`（新支线，阶段二）

- 位置：`pipeline/before/requestbody/system/`，`@Component`，
  实现 `SystemPromptNormalizeStage`，`protocol()` 返回 `RESPONSES`
- 职责：把 `input[]` 里 `role=="system"` 的 message item 改写为 `"developer"`
- 纯逻辑放静态工具（`SystemPromptNormalizer` 加一个方法，或独立
  `ResponsesSystemRoleRewriter`——实现时看哪个更贴合现有类职责）
- **为什么在支线而非翻译器**：对齐 C2M 分工先例（翻译器不管 system 语义归一化，
  `ChatToMessagesRequestTranslator` 的 Javadoc 明确写「不提取 system 到顶层，
  那是 SystemPromptNormalizer 的职责」）；查表键是 `bodyProtocol`，C2R 翻译后
  自动为 `RESPONSES`，`RequestBodyAssembler` 的查表机制零改动；直连 RESPONSES
  场景若有客户端发非标准 `system` role，同一支线也覆盖
- ⚠️ **分工确认点**：若决策本意是「翻译器内直接改 role、不新增 SystemPrompt 家族支线」，
  删掉本组件、把改写并入翻译器的消息遍历即可——影响面仅此一处（见 §6）

### 3.3 调度与设置层（零改动清单）

| 组件 | 改动 |
|---|---|
| `TranslatorRegistry` | 零（自动收集） |
| `ProtocolDispatchManager` | 零（`implementedRequestRoutes` 自动含新方向） |
| `RequestBodyAssembler` | 零（查表自动命中 `ResponsesSystemPromptStage` / 已有 `ResponsesThinkingStage`） |
| `ReasoningEffortSetting` | 零（`applyToResponses` 已就位，表态判定只看 `reasoning.effort`） |
| schema / 迁移 | 零（纯代码，无库变更） |

### 3.4 既有测试受影响清单（深度探索核实）

| 测试 | 影响 | 阶段 |
|---|---|---|
| `RequestBodyStageSpringWiringTests` | `systemPromptStages` 从 1 变 2；`findSystemPromptStage(RESPONSES)` 从 empty 变 present；`otherProtocolsFindNothingYet` 用例需改（其 Javadoc 写着「另两条协议查不到是预期」的理由要同步改写） | 阶段二 |
| `ProtocolDispatchManagerTests` | 无需改——该文件用自构造的 `managerWithAllRoutesImplemented()` / `managerWith()`，**不依赖生产 Bean**，实现状态是显式注入的变量 | — |
| `ChatDispatchErrorSignalTests` / `RequestPipelineTests` | 若有「C2R 未实现报错」的断言需核查（探索未见直接引用，阶段一落地后跑全量确认） | 阶段一 |

---

## 4. 明确不做的事

| 不做 | 理由 |
|---|---|
| **R2C 回程翻译** | 独立下一步；调研与决策已完成（[R2C-RESEARCH.md](./R2C-RESEARCH.md)），关键素材齐备（RESPONSE-CONTRACT §16 三种事件形态 + 三家联测）。去程先行是为用真实上游验证请求可被接受 |
| **思考缓存** | 实测不需要（§10.1：C2R 输入是 Chat 明文；第三方上游不签发密文）。真正需要它的是「R2C + 上游确实签发密文」，届时再议（CPA 的 TTL/容量参数是现成参照） |
| **`namespace` / `web_search` 工具合成** | 决策 #4：忠实搬运，Chat 里没有的不造 |
| **协议流转编排**（`ProtocolDispatchManager` 的 TODO） | 独立事项，有自己的规划；C2R 落地不依赖它（直连走规则 1 不读回退序） |
| **max tokens 支线化（RESPONSES 侧）** | 本轮在翻译器实现一次映射；`ResponsesMaxTokensStage` 是本方向的**新增件而非重构**——RESPONSES 线路（直连 + C2R 翻译后）目前完全吃不到 `MaxOutputTokensSetting` 的注入。与 `MessagesMaxTokensStage` 对称，独立一轮处理（见 §6.4） |
| **CHAT 直连的 max tokens 注入缺失** | 历史成因：曾以为 CHAT 路径无该字段；实际 Chat 协议有 `max_tokens`/`max_completion_tokens`，缺失属**语义缺失**而非无字段可注。刻意不在本分支修（独立一轮），见 §6.4 |
| **补默认 effort / 剥采样参数 / 跳过 max_output_tokens 映射** | C2R-RESEARCH §7 三条「不应照抄 CPA」 |
| **`reasoning.summary` 写值** | 决策 #6：显式 opt-in，不与 effort 耦合 |

---

## 5. 阶段划分与提交边界

三个阶段各自可独立提交、独立验证。顺序刻意如此：阶段一先让「请求能发成上游能懂的
形态」成立（验证手段是 mock 收到的 body + 原样透传的响应）；阶段二补 role 改写；
阶段三是真实 HTTP 的端到端确认。**不要把阶段一与二合并**——阶段一落地后全量测试
必须全绿（含既有 dispatch/wiring 断言），这是「翻译器正确」的完整证明；混进支线
后一个失败用例无法区分是翻译错还是支线错。

### 阶段一：翻译器主体（核心，最大块）

**产出**：`ChatToResponsesRequestTranslator` + 完整单测。

**改动文件**：
- 新增 `src/main/java/.../pipeline/protocol/translate/ChatToResponsesRequestTranslator.java`
- 新增 `src/test/java/.../pipeline/protocol/translate/ChatToResponsesRequestTranslatorTests.java`

**验证**（全部独立于其他阶段）：
1. `./mvnw test -Dtest=ChatToResponsesRequestTranslatorTests` — 新测试全绿
2. `./mvnw surefire:test` — 全量绿（证明注册表自动收集无副作用；wiring 测试的
   `hasSize(1)` 断言**不含**翻译器，只数 Stage，不受影响）
3. 手动 sanity：`curl` 打 COSP `/v1/chat/completions`（配一个只勾 RESPONSES 的
   mock 供应商）→ mock 日志确认收到 Responses 形态 body；响应原样透传（中间态）

**单测组织**（参照 `ChatToMessagesRequestTranslatorTests` 的 `@Nested` 分组）：
1. 顶层标量逐项：model/stream 原样、max 双字段优先级、采样参数原样、
   `reasoning_effort` 改名+删原字段、`store:false`/`include` 强制值
2. `response_format` → `text.format`（含 `json_schema` 合并与非 schema 类型）
3. 消息映射全表（§2.2）：system role 不动、user 内容块白名单、assistant 文本/工具/
   reasoning_content 丢弃、tool 配对成功与失配
4. 内容块：字符串 content 包装、`detail` 丢失、未知块类型报错
5. 工具：嵌套→扁平、`strict` 缺省显式 false / 显式值透传、`tool_choice` 展平
6. 硬失败：`n>1`、legacy `function_call`（单独或与 `tool_choice` 并存）
7. `TranslatedRequest.context`：wasStream / includeUsage 取值
8. **静默丢弃清单**：§2.1 清单里每个字段一条断言（防止「以为是映射其实是丢弃」的漂移）

### 阶段二：`ResponsesSystemPromptStage` 支线（小）

**产出**：role 改写支线 + wiring 测试更新。

**改动文件**：
- 新增 `src/main/java/.../pipeline/before/requestbody/system/ResponsesSystemPromptStage.java`
- 静态纯逻辑（`SystemPromptNormalizer` 加方法或新工具类）
- 修改 `src/test/java/.../requestbody/RequestBodyStageSpringWiringTests.java`
  （`hasSize(1)`→`hasSize(2)`、`findSystemPromptStage(RESPONSES)` empty→present、
  `otherProtocolsFindNothingYet` 的 DisplayName 与 Javadoc 理由改写——CHAT 仍是
  「不需要」的正当理由，RESPONSES 变为「有实现」）

**验证**：
1. 新支线的纯逻辑单测：input 里 system → developer；无 system 时不改动；
   非 message item（function_call 等）不受影响
2. `./mvnw test -Dtest=RequestBodyStageSpringWiringTests` — 更新后全绿
3. `./mvnw surefire:test` — 全量绿
4. 集成断言（可选）：C2R 全链路测试里，翻译+装配后 body 的 `input[]` 无 `system` role
   （此断言依赖阶段一，放在本阶段做链路级验证）

### 阶段三：实机联测（验证收尾）

**产出**：不改代码，跑实机 + 记录；发现问题回灌修。

**验证**（前置：COSP 运行 :11434 + `npm run mock` :8081 + 只勾 RESPONSES 的供应商已配）：
1. `tools/live-test` 形态打 `/v1/chat/completions`（非流式 + 流式各一次）
2. 判据（对应 LIVE_TEST_MATRIX 的思路，判据是 mock 真实被调用）：
   - mock 日志收到 Responses 形态请求（`input` 数组、`store:false`、
     `include:["reasoning.encrypted_content"]`、扁平 tools）
   - 上游返回 200（请求被接受，阶段一的核心目标）
   - COSP 侧响应为 Responses 事件流原样透传（回程未实现时的既定中间态，当时实测即此形态；
     R2C 落地后此判据已成历史，现状参见 R2C-PLAN.md §7.3 的复测记录）
   - 调用日志里 `downstream_protocol=CHAT`、`upstream_protocol=RESPONSES`
3. 记录进 PLAN 的实施记录节（§7，写完追加）

---

## 6. 遗留与待确认

1. **system 分工确认点**（§3.2 ⚠️）：本计划按「支线做 role 改写」写；若本意是
   翻译器内做，改动仅 §3.2 一节 + 阶段二。
2. **~~`applyToResponses` 与保留字段的交互~~ 已在深度探索中定案**：`downstreamHasResponsesOpinion` 只看 `reasoning.effort`（其 Javadoc 明写「那条线路目前只有直连」）。C2R 翻译器**改名后删原字段**，不需要 C2M 那种「保留 + 收尾清理」模式——决策表 #6 与 §2.1 已按此更新。**注意**：`ReasoningEffortSetting` 的这条 Javadoc 在 C2R 落地后要同步改写（「C2R 尚未实现、只有直连」将成为过时陈述）。
3. **`reasoning_content` 丢弃的回程影响**：去程丢了 Chat 的明文思考，回程（R2C）
   落地时下游多轮里没有思考可回放——与 Chat 客户端「本就不回放」的行为一致
   （BYOK 调查），但值得在回程计划里复核一次。
4. **TODO(分支化) max tokens 三线补齐**：两个缺口，统一一轮处理：
   - `ResponsesMaxTokensStage`：与 MESSAGES 侧对称，让 RESPONSES 线路吃到注入；
     落地时**读 `max_output_tokens`（Responses 正名）**，与翻译器输出形态对齐，
     避免出现「翻译器刚写好、支线又改写」的冲突（时序：`translateStep` 在前、
     `assembleStep` 在后，支线看到的是翻译后的 body）。
   - `ChatMaxTokensStage`：CHAT 直连的语义缺失（协议有字段、历史上误判为无字段而未实现），
     `MaxOutputTokensSetting.applyTo` 目前写着「当前无调用方」。
   两者可同一轮做（同一 Setting、同一注入模式），不限于本分支。
5. **`emptyProtocolSetAndUnimplementedTranslationAreDistinctFailures` 等调度用例的
   生产语义变化**：调度测试本身无需改（自构造 manager），但 C2R 落地后生产里
   「下游 CHAT + 只勾 RESPONSES」从报错变为走翻译——若 `ChatDispatchErrorSignalTests`
   有依赖该行为的断言需核查（深度探索未逐行核实该文件，阶段一全量跑时确认）。

## 7. 实施记录（各阶段完成后追加）

### 7.1 阶段一：翻译器主体（2026-10-04 完成）

**落地内容**：
- `ChatToResponsesRequestTranslator`（`pipeline/protocol/translate/`，约 470 行含 Javadoc）
- `ChatToResponsesRequestTranslatorTests`：44 条，8 组 `@Nested`（顶层标量 7 / 思考 4 /
  response_format 3 / messages→input 11 / 内容块 3 / 工具 8 / 硬失败 4 / context 3 / 静默丢弃 1）
- 验证：新测试 44/44 绿；全量 `./mvnw surefire:test` **1384 条全绿**（含既有 dispatch/wiring
  断言，证明注册表零改动无副作用——`RequestBodyStageSpringWiringTests` 只数 Stage 不数翻译器，
  预判正确）

**计划内决策的落实现场**：
- `reasoning_effort` 改名后删原字段（§6.2 定案），`thinking` 不搬运（Responses 的开关在
  effort 的 `none` 档，与 Chat 的正交双字段结构不同——这条是 PLAN §2.1 未显式写的补充决策，
  依据 `ReasoningEffortSetting` 的 REASONING_FIELD Javadoc「off 档映射为 none」）
- `developer` role 归一到 `system`（支线的约定中间形态）；`translateContent` 未知块类型
  **报错**而非 C2M 的丢弃——PLAN §2.2.1 已定，理由：块类型空间小，静默丢让多模态无声降级
- 实机验证（阶段三）推迟：需要重启服务，由用户手动操作后进行

**计划外发现**：
- AssertJ 的 `doesNotContainKey(String)` 在通配符 `Map<?, ?>` 上不可用（键类型 capture
  无法匹配 String）——用 `containsKey().isFalse()` 或先收窄类型绕过
- PowerShell 管道 `| Select-String` 会吞 mvn 的退出码语义（显示 BUILD SUCCESS 但
  `$LASTEXITCODE=1`）；干净验证要重定向到文件再读

### 7.2 阶段二：ResponsesSystemPromptStage 支线（2026-10-04 完成）

**落地内容**：
- `ResponsesSystemPromptStage`（`pipeline/before/requestbody/system/`，声明 RESPONSES，
  转调静态纯逻辑）
- 纯逻辑落在 `SystemPromptNormalizer.rewriteSystemToDeveloper`（与该类的抬升/合并/
  压平同域，符合「system 归一化的静态工具集」职责）
- `ResponsesSystemPromptStageTests`：6 条纯逻辑单测（改写、多条原位、非 message item
  不受影响、幂等、no-op、不可变元素容忍）
- `RequestBodyStageSpringWiringTests` 更新：system 支线 `hasSize(1)→(2)`、
  `findSystemPromptStage(RESPONSES)` empty→present、`otherProtocolsFindNothingYet`
  的断言与 Javadoc 理由改写（CHAT 的 system 仍是「不需要」；RESPONSES 的 max_tokens
  仍空）、`foundStagesAreUsable` 补 Responses 侧冒烟
- `testing/PipelineContexts.registryWithAllBodyStages` 补装新支线（装配器测试的真实
  实现清单）
- 验证：新测试 + wiring 14/14 绿；全量 `./mvnw surefire:test` **1390 条全绿**

**计划外发现（wiring 冒烟测试的价值实证）**：
- 初版 `rewriteSystemToDeveloper` 用「原 Map put」改写，被 wiring 冒烟的
  `Map.of` fixture 打出 `UnsupportedOperationException` —— 直连线路的 body 元素
  是否可变不是支线能假设的。改为「构造替换元素 + 换 List 元素」后修复，并补
  `toleratesImmutableItems` 用例钉住。这正验证了 wiring 测试类注释里「防静默降级」
  之外的第二重价值：**比纯逻辑单测更早暴露实现假设**（纯逻辑单测自己造的
  fixture 全是可变集合，测不出这类问题）

### 7.3 阶段三：实机联测（2026-10-04 完成）

**环境**：COSP 重启后实机（mimo-tokenplan 供应商只勾 RESPONSES、模型
`[mimo-tokenplan] mimo-v2.6-flash`、网关鉴权关闭）。

**判据全部达成**：

| 判据（§5 阶段三） | 实测 |
|---|---|
| 请求被上游接受 | 非流式 / 流式均 **HTTP 200**，~1.8s |
| 上游收到 Responses 形态 body | 落库的 `request_body` 顶层恰 9 键（`model/stream/max_output_tokens/input/tools/tool_choice/store/include/reasoning`），逐项核对 §2 映射表全部一致：`store:false`、`include:["reasoning.encrypted_content"]`、工具**扁平** + `strict:false` 显式补值、`tool_choice:"auto"`、`max_tokens:512→max_output_tokens:512` |
| **system 改写生效**（阶段二支线） | 落库 input 为 `message/developer` + `message/user`——`system→developer` 在真实链路上命中 |
| 响应原样透传（中间态预期） | 非流式回 `object:"response"` 的 Responses JSON（`output[]` 含 reasoning + message item、`output_text` 汇总）；流式回 `response.created → reasoning_text.delta → …` 原生事件流，`sequence_number` 连续 |
| 落库协议列 | `downstream_protocol=CHAT`、`upstream_protocol=RESPONSES`、`status_code=200` |

**顺带验证的链路事实**：

- `reasoning.effort` 落库为 `max` 而下游发的是 `low` —— 这是该供应商模型级
  OVERRIDE 注入的结果：翻译层正确搬运了 `low`，设置层随后按配置改写。
  「翻译在前、设置层在后」的顺序在真实链路上得到确认，两层各司其职
- 空响应判定未误伤：Responses 原生响应（reasoning item + message item）通过了
  `ResponsesContentDetector` —— 该判定器读 Responses 自己的结构、与翻译无关，
  这正是「两侧取值路径独立」设计的收益在实机上的体现

**测试脚手架的坑（实机操作记录）**：

- PowerShell 5.1 里 `curl -d '{...}'` 的引号转义会把 JSON 打坏 → 400，且
  落库不可见（管道早期被拒）——**用文件传 body**（`--data-binary "@file"`）+
  `UTF8Encoding($false)` 写文件（ASCII 会把中文变 `?`）
- 管理端点：登录 `POST /auth/login`、日志列表 `GET /config/api/logs`、
  详情 `GET /config/api/logs/{id}`（详情里 `request_body` 即上游收到的形态）

**第二家供应商：stepfun / step-5-preview（同日追加）**

供应商本就只勾 RESPONSES（`api.stepfun.com/step_plan/v1`），四项测试全过：

1. **非流式 + 全套字段**：200（4.4s）；reasoning 以明文 `reasoning_text` 下发
   （与调研 §10.1「stepfun 不签发密文」一致）、message item 正文完整。
   注意其顶层 `output_text` 便利字段为空——那是上游自己的汇总字段，透传下
   无关紧要，回程翻译器将自 `output[].content` 取正文
2. **流式**：200；35 事件，`sequence_number` 连续，骨架与调研形态 B 一致
3. **流式工具调用（形态 C 复现）**：200；`function_call` 事件组完整
   （`output_item.added(arguments:"") → arguments.delta ×2（`{`/`}` 逐片非法
   JSON）→ arguments.done → output_item.done`），`call_id` 带 `call_` 前缀、
   item id 为 16 位十六进制裸串——**与调研抓包的 stepfun 样本逐项吻合**
4. **多轮回传（去程翻译的完整闭环）**：把上一轮的 `function_call`（Chat 的
   `tool_calls[].id`）与工具结果（`role:tool`）回发 → 落库 input 序列为
   `message/developer → message/user → function_call(call_id=…) →
   function_call_output(call_id=…, output=…)`——**配对键对齐、工具结果
   压平为字符串、system 改写、assistant 工具调用展开**四个决策点在一条
   请求里同时验证；上游正确消化并回答了工具结果

与 mimo 的差异：stepfun 保持 `reasoning_effort` 原值不改写（mimo 的模型级
OVERRIDE 会改写）——两家行为差异与调研 §10.5 表格「上游是否改写 effort 因供应商而异」
的记录一致，翻译层不感知。

**第三家供应商：deepseek 官方 / deepseek-flash（同日追加）**

官方端点（`api.deepseek.com/v1`，只勾 RESPONSES）。四项全过，且带来两个**官方实现
特有**的观察：

1. **响应字段集最全**：非流式响应带 `truncation` / `safety_identifier` /
   `prompt_cache_retention` / `moderation` 等官方 OpenAI 风格字段——比两家中转
   丰富得多，透传下无影响；**这批字段是 R2C 回程的「真实字段全集」样本**，
   回程状态机不应假设字段存在与否
2. **`function_call` 的 id 风格不同**：item `id` 是 UUID（`b3d0ae33-…`）、
   `call_id` 是 `call_00_…` 风格——与 stepfun 的 16 位十六进制裸串不同；
   参数以**单 delta 全量**下发（`{}` 一次性，无分片）。三家对比坐实：
   **id 形态与分片粒度因供应商而异，R2C 回程状态机不能依赖任何一种具体形态**
   （只能依赖事件骨架与 `call_id` 配对语义）
3. 多轮回传闭环同样通过：落库 `message/developer → user → function_call →
   function_call_output`，上游正确回答工具结果
4. 附带一个配置观察：deepseek 供应商最初 `enabled:false` 且勾全部三协议——
   那种状态下要么路由失败、要么（勾了 CHAT 时）规则 1 直连短路、根本到不了
   C2R。测试 C2R 必须只勾 RESPONSES（规则 1 优先级高于翻译）

# RFC：协议翻译四方向（C2M / M2C / C2R / R2C）

- **日期**：2026-09-03（立项日：C2M 契约首次入库）
- **状态**：**已定稿且实施完毕**
  （决策已晋升 [ADR-0002](../../adr/adr-0002-protocol-naming.md) ~ [ADR-0005](../../adr/adr-0005-r2c-response-translation.md)）
- **相关 ADR**：ADR-0002（命名体系）、ADR-0003（C2M 翻译口径）、ADR-0004（C2R 去程）、ADR-0005（R2C 回程）
- **契约**：[C2M-REQUEST-CONTRACT.md](../../reference/contracts/C2M-REQUEST-CONTRACT.md)、
  [C2M-RESPONSE-CONTRACT.md](../../reference/contracts/C2M-RESPONSE-CONTRACT.md)
- **样本**：[`reference/samples/`](../../reference/samples/)（7 份真实 Codex 抓包）

> **本 RFC 是四个方向的完整档案**（需求 → 调研 → 计划 → 实施记录）。
> 决策的原子记录在 ADR-0002 ~ 0005；字段规范在 `reference/contracts/`；原始抓包在 `reference/samples/`。
> 本文档正文约 130 KB / 2100 行，**请先读下方导航目录**。

## 导航目录

| 章节 | 内容 | 原始来源 |
|---|---|---|
| [§A 方向总览](#a-方向总览) | 四方向状态表、命名约定 | `protocol-translation/README.md` |
| [§B C2R 去程 · 调研](#b-c2r-去程--调研) | 四参考项目对比、字段映射、硬失败情况 | `C2R-RESEARCH.md` |
| [§C C2R 去程 · 计划与实施记录](#c-c2r-去程--计划与实施记录) | 六项决策、字段映射总表、三阶段、遗留 | `C2R-PLAN.md` |
| [§D R2C 回程 · 调研](#d-r2c-回程--调研) | 四参考项目对比、流式状态机、乱序实测 | `R2C-RESEARCH.md` |
| [§E R2C 回程 · 计划与实施记录](#e-r2c-回程--计划与实施记录) | 决策、组件接线、三阶段、挑战与风险 | `R2C-PLAN.md` |

> **C2M / M2C 方向说明**：该方向为早期实现，无独立调研与计划文档，
> 其字段规范见 [C2M 请求契约](../../reference/contracts/C2M-REQUEST-CONTRACT.md) 与
> [C2M 响应契约](../../reference/contracts/C2M-RESPONSE-CONTRACT.md)。下文 §A 保留方向状态表。

---

## A. 方向总览


三个线路协议（CHAT / MESSAGES / RESPONSES）两两之间的六个翻译方向。一对协议一个子目录。

### 方向状态

| 方向 | 含义 | 状态 |
|---|---|---|
| **C2M** | 下游 Chat → 上游 Messages | ✅ 已实现并实测 |
| **M2C** | 下游 Messages → 上游 Chat | 请求侧待做；响应侧已实现并实测 |
| **C2R** | 下游 Chat → 上游 Responses | ✅ 双向可用（去程 [PLAN.md](#c-c2r-去程--计划与实施记录) §7 + 回程 [R2C-PLAN.md](#e-r2c-回程--计划与实施记录) §7，三家供应商实机验证） |
| **R2C** | 下游 Responses → 上游 Chat | ✅ 已实现（作为 C2R 的回程半边，随 [R2C-PLAN.md](#e-r2c-回程--计划与实施记录) 落地并实机验证） |
| **M2R** / **R2M** | Messages ↔ Responses | ❌ 未实现 |

### 文档

| 文档 | 内容 |
|---|---|
| [chat-messages/REQUEST-CONTRACT.md](../../reference/contracts/C2M-REQUEST-CONTRACT.md) | C2M/M2C 请求侧契约 |
| [chat-messages/RESPONSE-CONTRACT.md](../../reference/contracts/C2M-RESPONSE-CONTRACT.md) | M2C/C2M 响应侧契约（第 16 节含 C2R 实测序列） |
| [chat-responses/C2R-RESEARCH.md](#b-c2r-去程--调研) | C2R 请求翻译调研（四项目对比 + 真实抓包） |
| [chat-responses/R2C-RESEARCH.md](#d-r2c-回程--调研) | R2C 回程翻译调研与决策（四项目对比 + 三家联测验证） |
| [chat-responses/R2C-PLAN.md](#e-r2c-回程--计划与实施记录) | R2C 回程翻译实施计划（决策表 + 事件动作规格 + 三阶段） |
| [chat-responses/samples/](../../reference/samples/) | 7 份真实 Codex 抓包（请求 + 响应事件流） |
| [PROTOCOL-RENAME-PLAN.md](2026-09-30-protocol-rename.md) | 协议命名重构规划（`OPENAI→CHAT` 改名第一步，已落地） |

### 命名约定

- 方向缩写：`C`=Chat Completions、`M`=Messages、`R`=Responses，`C2M` 读作
  「下游 C 翻译到上游 M」。
- 枚举名与持久化字面量逐字一致（无映射层），见
  [PROTOCOL-RENAME-PLAN.md](2026-09-30-protocol-rename.md) §1。

---

## B. C2R 去程 · 调研


> **状态**：调研完成，**尚未实现**。本文只固定调研事实与候选方案，不含已落地的约束。
> 落地时应把确定的部分并入
> [REQUEST-CONTRACT.md](../../reference/contracts/C2M-REQUEST-CONTRACT.md)（请求侧契约）
> 或另立 C2R 章节。
>
> 调研于 2026-09-29。四个项目均在工作区内（`new-api/` / `sub2api/` / `cc-switch/` / `CLIProxyAPI/`，
> 都是独立参考项目，只读）。
>
> **§0–§9 是读源码得出的，§10 是真实抓包。** 后者修正了前九节的三处判断，
> 并修正了[请求侧契约](../../reference/contracts/C2M-REQUEST-CONTRACT.md) §4.8 的一处前提。

C2R = 下游 OpenAI Chat Completions → 上游 OpenAI Responses。
请求侧翻译；响应侧（Responses 事件流 → Chat chunk）是另一半，本文不覆盖。

---

### 0. 四个项目各自覆盖什么

| 项目 | C2R 请求侧 | 说明 |
|---|---|---|
| `new-api` | ✅ 完整 | `relaykit/relayconvert/internal/oai_chat/to_oai_responses_req.go` |
| `sub2api` | ✅ 完整 | `backend/internal/pkg/apicompat/chatcompletions_to_responses.go` |
| `cc-switch` | ❌ **没有** | 只做 Anthropic↔Responses（`transform_responses.rs`）与**反方向** R2C（`transform_codex_chat.rs`）。`/chat/completions` 入口（`handlers.rs:762`）只透传 |
| `CLIProxyAPI`（CPA） | ✅ 完整 | `internal/translator/codex/openai/chat-completions/codex_openai_request.go` |

#### CPA 的方向命名容易误读

CPA 的注册表是 `Register(from, to, ...)`（`translator.go:24`），`Codex` 与 `OpenaiResponse` 是**两个不同标识符**（`constant/constant.go:14,23`）—— 但 Codex 用的就是 Responses 协议。所以：

```go
Register(OpenAI, Codex, ConvertOpenAIRequestToCodex, ...)                  // ← 这才是 C2R
Register(OpenaiResponse, OpenAI, ConvertOpenAIResponsesRequestToOpenAIChatCompletions, ...)  // R2C
```

`ConvertOpenAIRequestToCodex` 的文档注释直说：「Converts an OpenAI Chat Completions request JSON
into an OpenAI Responses API request JSON」（`codex_openai_request.go:16-18`）。
**它是四个项目里唯一 C2R 与 R2C 都实现的**，且是唯一为「Codex 号池」场景特化的。

#### cc-switch 的 C2R 缺失是事实，不是遗漏

它的 Chat→Responses 只存在于**响应侧**（`streaming_codex_chat.rs:811`
`create_responses_sse_stream_from_chat_with_context`）。请求侧无对应实现。因此 C2R **只有三个参照**。

---

### 1. 字段映射对比

处置列：**映射** / **丢弃** / **报错** / **未找到**。

#### 1.1 顶层标量

| Chat | Responses | new-api | sub2api | CPA | 备注 |
|---|---|---|---|---|---|
| `model` | `model` | 映射 | 映射 | 映射 | 原样 |
| `stream` | `stream` | 映射 | **强制 `true`** | 映射 | sub2api 假定上游恒流式 |
| `max_tokens` / `max_completion_tokens` | `max_output_tokens` | 映射（后者优先） | 映射（后者优先，clamp 128） | **不映射**（注释掉） | 见 §1.4 |
| `temperature` / `top_p` | 同名 | 无条件映射 | 按 `isReasoningModel` 剥离 | **不映射**（注释掉） | 见 §1.5 |
| `top_k` | — | 未找到 | 未找到 | 未找到 | 非官方字段 |
| `reasoning_effort` | `reasoning.effort` | 映射（经 Intent 抽提） | 映射 + `summary:"auto"` | 映射 + **缺省补 `"medium"`** | 见 §1.3 |
| `response_format` | `text.format` | 映射（外层优先合并） | 映射（只取内层） | 未找到 | 见 §1.6 |
| `tools` / `functions` | `tools` | 映射（toolconv 统一） | 映射（两者都追加） | 映射（仅 function/custom 结构转换，内置工具直通） | |
| `tool_choice` | `tool_choice` | **展平** | **原样透传（缺陷）** | **展平** | 见 §1.2 |
| `parallel_tool_calls` | 同名 | 映射 | 映射 | **强制 `true`** | |
| `store` | `store` | 透传 | **强制 `false`** | **强制 `false`** | 见 §2 |
| `include` | `include` | 不设置 | **强制密文** | **强制密文** | 见 §2 |
| `service_tier` | 同名 | 未映射 | 映射 | 映射（经 `normalizeCodexServiceTier`） | |
| `prompt_cache_key` | 同名 | 映射 | 由 service 层注入 | 未找到 | |
| `user` / `metadata` | 同名 | 映射 | 未映射 | 未找到 | |
| `frequency_penalty` / `presence_penalty` | 同名 | 映射（非官方，DTO 注释说明） | 结构体无此字段 → 丢弃 | 未找到 | |
| `enable_thinking` / `thinking_budget` | 同名 | 映射 | 未映射 | 未找到 | Qwen 专有 |
| `n`（>1） | — | **报错** | 未找到 | 未找到 | new-api: `n>1 is not supported in responses compatibility mode` |
| `stop` / `seed` / `logprobs` / `logit_bias` / `modalities` / `audio` | — | 丢弃 | 丢弃 | 丢弃 | |

#### 1.2 `tool_choice` 形态 —— 两个独立实现都做展平，sub2api 是缺陷

```
Chat:      {"type":"function","function":{"name":"X"}}
Responses: {"type":"function","name":"X"}
```

**new-api**（`to_oai_responses_req.go:318-354`）写明两种形态不同并转换，且**兼容两种输入**
（已平铺的 `m["name"]` 或嵌套的 `m["function"].name`）：

```go
} else if t, _ := m["type"].(string); t == "function" {
    // Chat: {"type":"function","function":{"name":"..."}}
    // Responses: {"type":"function","name":"..."}
    if name, ok := m["name"].(string); ok && name != "" {
```

**CPA**（`codex_openai_request.go:481-505`）同样展平，且额外处理 `custom` 类型：

```go
case tc.IsObject():
    tcType := tc.Get("type").String()
    if tcType == "function" || tcType == "custom" {
        name := tc.Get("name").String()
        if tcType == "function" {
            name = tc.Get("function.name").String()
        }
```

**sub2api**（`chatcompletions_to_responses.go:88-91`）注释说「already compatible format」直接透传：

```go
// tool_choice: already compatible format — pass through directly.
if len(req.ToolChoice) > 0 {
    out.ToolChoice = req.ToolChoice
}
```

**判定**：两个独立实现都做展平 → **必须展平**。sub2api 这处在收到 Chat 标准形态时会产出上游
认不出的 `tool_choice`，是缺陷（其测试未覆盖嵌套形态）。

#### 1.3 `reasoning_effort` → `reasoning.effort`：三种做法

| 项目 | effort | 伴生 `summary` | 缺省行为 |
|---|---|---|---|
| new-api | 经协议无关 Intent 抽提（七档 `none/minimal/low/medium/high/xhigh/max`） | `"detailed"`（none 档为 `""`） | 不补 |
| sub2api | 直接透传（注释声明四档 `low/medium/high/xhigh`，无校验） | `"auto"` | 不补 |
| CPA | 直接透传 | **不写** | **补 `"medium"`** |

CPA 还有一条注释明确否定了「effort 与 summary 耦合」：

```go
// OpenAI documents reasoning summaries as explicit opt-in output. Leave
// reasoning.summary to the source request's canonical summary intent instead
// of coupling it to reasoning effort.
out, _ = sjson.SetBytes(out, "include", []string{"reasoning.encrypted_content"})
```

**对本服务的含义**：CPA 的「补 `medium`」与 AGENTS.md「不替客户端做决定」冲突 —— 我们的原则是
下游没表态就留空、交给设置层兜底档。但「effort 与 summary 是否耦合」这条**CPA 的理由成立**：
summary 是输出的显式 opt-in，不该由 effort 推导。

#### 1.4 `max_output_tokens` 下限：三个值，都有成文理由

```go
// new-api:366-370 —— 不 clamp，16 的下限被注释掉
// OpenAI Responses API rejects max_output_tokens < 16 when explicitly provided.
//if maxOutputTokens > 0 && maxOutputTokens < 16 {
//	maxOutputTokens = 16
//}
```
```go
// sub2api:62-64 —— clamp 到 128（常量见 types.go:856-858）
if v < minMaxOutputTokens {
    v = minMaxOutputTokens
}
```
```rust
// cc-switch（Anthropic 侧，非 C2R）—— clamp 到 16（transform_responses.rs:27-31）
```

CPA **整个 max_output_tokens 映射被注释掉**，理由写在注释里：「Codex not support
temperature, top_p, top_k, max_output_tokens, so comment them」（`codex_openai_request.go:47-60`）。

**对本服务的含义**：我们已有 `MaxOutputTokensSetting`（预设最小 4K），C2R 侧是否还需要 clamp
待定；且 clamp 属「自动修正」，与「不替客户端做决定」有张力。

#### 1.5 `temperature` / `top_p` 是否剥离

| 项目 | 做法 |
|---|---|
| new-api | 无条件透传（`:398`/`:402`） |
| sub2api | 按 `isReasoningModel(model)` 剥离（`:42-47`）—— 推理模型不接受采样参数 |
| CPA | **不映射**（注释掉），理由是 Codex 不支持 |

**对本服务的含义**：sub2api 与 CPA 都按**模型名/上游特性**决定是否发采样参数。这与 AGENTS.md
明确反对的「按模型名前缀硬编码能力表并逐级降级」（new-api 那个反面案例）是同一类。
我们的原则是**原样发出，由上游用错误码回答**。

#### 1.6 `response_format` → `text.format`：两家实现相反

**new-api**（`to_oai_responses_req.go:41-77`）：外层字段优先，把内层 `json_schema` 合并进外层并删除嵌套键。

**sub2api**（`response_format.go:5-34`）：只取内层 `json_schema` 内容、**丢掉外层其余字段**；非 `json_schema` 类型原样返回。

**对本服务的含义**：若两者都要兼容需同时处理两种输入形态。

#### 1.7 tool `strict` 缺省值 —— CPA 独有的因果说明

```go
if v := fn.Get("strict"); v.Exists() {
    item, _ = sjson.SetBytes(item, "strict", v.Value())
} else {
    // Chat Completions defaults strict to false while the Responses API
    // defaults it to true, so an omitted value must be forwarded explicitly.
    item, _ = sjson.SetBytes(item, "strict", false)
}
```

**两侧默认值不同**：Chat 默认 `false`、Responses 默认 `true`。省略不是「什么都不说」，
而是**改变了语义**。new-api 缺省不写该键（`encode.go:128-132` 仅 `if != nil`），sub2api
填 `false` 但无此说明。**CPA 的注释是唯一说明。**

---

### 2. `store` / `include` 的强制注入

| 项目 | `store` | `include` |
|---|---|---|
| new-api | 透传客户端值 | 不设置 |
| sub2api | **强制 `false`** | **强制 `["reasoning.encrypted_content"]`** |
| CPA | **强制 `false`**（函数末尾无条件） | **强制 `["reasoning.encrypted_content"]`** |

sub2api 的理由写在函数头：

```go
// Responses API request. The upstream always streams, so Stream is forced to
// true. store is always false and reasoning.encrypted_content is always
// included so that the response translator has full context.
```

cc-switch 对同一约束给出**最完整的理由**（虽是 Anthropic 侧，但讲的是同一个模式）：

```rust
// - store: 必须显式为 false（ChatGPT 消费级后端不允许服务端持久化）
// - include: 必须包含 "reasoning.encrypted_content"，
//   否则多轮 reasoning 中间态会丢失（无服务端状态 + 无加密回传 = 上下文断链）
```

**两个独立实现（sub2api / CPA）都无条件强制**，只有 new-api 透传。

---

### 3. `system` / `developer` 落点 —— 第三种做法

**三家三种做法**，这是最大的语义分歧：

| 项目 | `system` 输入 | `developer` 输入 | `instructions` |
|---|---|---|---|
| new-api | 提升到顶层 `instructions`，**移出 input** | 与 system 同等对待（一起提升） | 多段用 `"\n\n"` 拼接 |
| sub2api | 保留为 input 里 `role: "system"` 的 message item | **落入 default → 当 `user`**（缺陷） | 不动 |
| **CPA** | **改写成 `role: "developer"`，留在 input** | 保持 `developer` | **显式初始化为 `""`，全程不写** |

**CPA 的做法与官方语义最贴** —— Responses 的 input 里确实允许 developer role，而 Chat 的
`system` 与 Responses 的 `developer` 是同一层级的东西。它把 `instructions` 初始化为 `""`
（`codex_openai_request.go:36`）后全程不写，是**刻意留空**：

```go
out := []byte(`{"instructions":""}`)
...
if role == "system" {
    msg, _ = sjson.SetBytes(msg, "role", "developer")
} else {
    msg, _ = sjson.SetBytes(msg, "role", role)
}
```

注意 CPA 的 `instructions` 提取代码**被整段注释掉了**（`:120-140`），说明作者试过提升路线
后改成了 developer 路线。

**对本服务的含义**：C2M 契约 §3.2 的既有选择是「`role: developer` → 顶层 `system`，与 system
同等对待」，但那是 `extractSystemPrompt` 做的。C2R 若走 developer 路线，需要确认
**由谁改 role**（翻译层还是既有支线），且 C2R 的 `MessagesSystemPromptStage` 之类支线
不适用于 Responses 方向 —— 需要一条新的 `ResponsesSystemPromptStage` 或让翻译层直接做。

---

### 4. 内容块与工具配对

#### 4.1 内容块白名单

| Chat 块 | new-api | sub2api | CPA |
|---|---|---|---|
| `text` | `input_text` / `output_text`（按 role） | 一律 `input_text` | `input_text` / `output_text`（按 role） |
| `image_url` | `input_image`（解包成纯 url 字符串，**丢 `detail`**） | `input_image`（丢 `detail`，跳过空 base64） | `input_image`（仅 `role == "user"`） |
| `file` | `input_file` 用 `"file"` 键（非官方） | `input_file` 用 `filename`/`file_data`/`file_id`（官方） | `input_file` 用 `file_data`/`filename`（官方） |
| `input_audio` | 支持 | 无分支 | 支持（仅 user） |
| `input_video` / `video_url` | `input_video`（阿里百炼扩展） | 无分支 | 未找到 |

**`image_url.detail` 三家都丢弃** —— 这是共识。

#### 4.2 工具调用配对修复 —— CPA 最完整

CPA 维护 `pendingToolCalls` 批次状态机：`tool` 消息必须与之前 assistant 的 `tool_calls`
**配对**才能产出 `function_call_output`，配不上就整条丢弃：

```go
pendingIndex := -1
for index := range pendingToolCalls {
    pendingCall := &pendingToolCalls[index]
    if pendingCall.consumed { continue }
    if toolCallID == "" || pendingCall.sourceCallID == toolCallID || pendingCall.callID == toolCallID {
        pendingIndex = index
        break
    }
}
if pendingIndex < 0 {
    continue     // 配不上就丢弃
}
```

它还处理：`call_id` 歧义（`ambiguousToolCallIDs`）、`custom` vs `function` 两种输出类型
（`custom_tool_call_output`）、批次边界由「新的对话消息」重置（`:194-196`）。

new-api 与 sub2api **都没有配对修复**（new-api 直接按 `tool_call_id` 转，缺 id 则降级为
user 消息带 `[tool_output_missing_call_id]` 前缀；sub2api 缺 id 则直接发空 `call_id`）。

**对本服务的含义**：C2M 契约 §3.5 已把「tool_use/tool_result 配对修复」列为必须实现项。
C2R 需要同类处理，且 CPA 的批次状态机是最完整的参照。

#### 4.3 assistant 消息

| 项目 | 空 content | `reasoning_content` | `tool_calls` |
|---|---|---|---|
| new-api | 仍发 `{role:"assistant", content:""}` | 无处理 | 展开为 `function_call` item（过滤 `type != "" && != "function"`） |
| sub2api | **不发 message**，只发 function_call | 包成 `<thinking>…</thinking>` 塞进 `output_text` | 展开（不过滤，`arguments` 空补 `"{}"`） |
| CPA | 未找到明确分支 | 未找到 | 展开（配 `pendingToolCalls`） |

---

### 5. 硬失败（报错）的情况

| 项目 | 报错条件 |
|---|---|
| new-api | 请求为 nil / `model` 为空 / **`n > 1`** / reasoning 冲突（`ErrEffortConflict`、`ErrUnsupportedEffort`、`ErrThinkingNotDisabled`）/ `tool_choice` 与 legacy `function_call` **同时存在** |
| sub2api | 仅三条：`convert function_call` 失败 / `parse user content` 失败 / content 形态无法解析。**几乎不会硬失败**（不认识的 assistant content 静默返回空） |
| CPA | 未找到显式报错路径（全 `sjson.SetBytes` 忽略 error） |

new-api 的具体消息（`to_oai_responses_req.go:81-88`）：

```go
if req == nil { return nil, errors.New("request is nil") }
if req.Model == "" { return nil, errors.New("model is required") }
if lo.FromPtrOr(req.N, 1) > 1 {
    return nil, fmt.Errorf("n>1 is not supported in responses compatibility mode")
}
```

**注意 sub2api 与 new-api 在 `tool_choice` + `function_call` 同时存在时策略相反**：
new-api **硬失败**（`tool_choice and legacy function_call cannot both be converted`），
sub2api **静默优先 `tool_choice`**（`else if` 短路）。

---

### 6. 思考回放缓存 —— CPA 独有，且是契约 §4.8 未决问题的唯一完整答案

**这是 CPA 相对前三个项目最重要的独有实现。**

`internal/cache/codex_reasoning_replay_cache.go` 实现了我们契约 §4.8 里「尚未决定」的
**「缓存明文」路线**，并给出完整的工程参数：

```go
// CodexReasoningReplayCacheTTL limits how long encrypted reasoning replay
// items stay in process memory.
CodexReasoningReplayCacheTTL = 1 * time.Hour

// CodexReasoningReplayCacheMaxEntries bounds process memory for replay
// continuity. Oldest entries are evicted first.
CodexReasoningReplayCacheMaxEntries = 10240

// CodexReasoningReplayCacheMaxTurnsPerEntry bounds cumulative state for one agent.
CodexReasoningReplayCacheMaxTurnsPerEntry = 256

// CodexReasoningReplayCacheMaxBytesPerEntry bounds cumulative serialized items for one agent.
CodexReasoningReplayCacheMaxBytesPerEntry = 16 << 20

// CodexReasoningReplayCacheEvictBatchSize leaves headroom after the cache
// reaches capacity so high write volume does not rescan the map every turn.
CodexReasoningReplayCacheEvictBatchSize = 128
```

外加 `homekv` 的 `KVCompareAndSwap` 做分布式一致性（`codexReasoningReplayKVClient`）。
按 agent 索引（`codexReasoningReplayEntries` 是 `map[string]entry`），条目内是 `[][]byte`
（reasoning items）。

这与契约 §4.8 记录的 sub2api 方案（`ReasoningContentByID` 钩子按 item id 索引）是**同一思路**，
但 CPA 给出了 sub2api 没有的容量与淘汰策略。

#### 对本服务的含义

契约 §4.8 的三条实测硬约束（`done` 才完整、密文绑定签发者、部分上游强索明文）在这里依然成立。
**但 C2R 请求侧不需要这个缓存** —— 已由真实抓包坐实（§10.1）：不签发密文的上游下，
Codex 回传的 `input` 里就是**明文 `reasoning_text`**，`encrypted_content` 为 `null`。
C2R 的输入又是 Chat 形态的 `messages`，下游手上有明文 `reasoning_content`，不需要代理代劳。

真正需要缓存的仍是 R2C（输入是 Responses 的加密思考），且**仅在「上游确实签发了密文」时**。

---

### 7. 候选方案汇总（不含结论）

落地 C2R 时需要逐项决策。下表列出各家的做法，**供决策时对照，不代表已选定**：

| # | 决策点 | new-api | sub2api | CPA | 与本服务原则的关系 |
|---|---|---|---|---|---|
| 1 | `system` 落点 | →`instructions` | 留 input `system` | →`developer` | 待定；需确认由谁改 role |
| 2 | `developer` 输入 | 同 system | 当 user（缺陷） | 保持 | — |
| 3 | `reasoning.summary` | `"detailed"` | `"auto"` | 不写 | CPA 的「不耦合 effort」理由成立 |
| 4 | effort 缺省 | 不补 | 不补 | 补 `medium` | ⚠ 与「不替客户端决定」冲突 |
| 5 | `tool_choice` 展平 | ✓ | ✗ | ✓ | **必须做**（两个独立实现都做） |
| 6 | tool `strict` 缺省 | 不写 | 填 `false` | 填 `false` + 因果注释 | 需决策（两侧默认值不同） |
| 7 | `store` / `include` | 透传 / 不设 | 强制 | 强制 | 待定（两家强制） |
| 8 | `max_output_tokens` 下限 | 不 clamp | clamp 128 | 不映射 | 待定；与「不自动修正」有张力 |
| 9 | `temperature`/`top_p` | 无条件透传 | 按模型剥离 | 不映射 | ⚠ 后两者与「不按模型名分支」冲突 |
| 10 | 工具配对修复 | 无 | 无 | 完整批次状态机 | **必须做**（C2M 契约 §3.5 同类要求） |
| 11 | 思考缓存 | 无 | 按 id 缓存明文 | 完整 TTL/容量/KV | **C2R 侧不需要**（§10.1 坐实）；R2C 待定 |
| 12 | `response_format` 层级合并 | 外层优先 | 只取内层 | 未找到 | 待定 |
| 13 | `n > 1` | 报错 | 未找到 | 未找到 | 待定 |
| 14 | `include` / `store` 的无状态前提 | 透传 | 强制 | 强制 | 待定（§10.1 表明上游不一定会用） |

#### 三条「不应照抄」的候选

CPA 是给 **Codex 号池**场景特化的，以下三条与 AGENTS.md 原则冲突：

1. **`reasoning.effort` 缺省补 `"medium"`** —— 属「替客户端做决定」；我们的设置层兜底档管这件事
2. **`temperature` / `top_p` 不映射** —— 按上游能力剥离，同「按模型名硬编码能力表」一类
3. **`max_output_tokens` 不映射** —— 同上，且我们已有 `MaxOutputTokensSetting`

---

### 8. 与既有契约的衔接点

| 契约位置 | 衔接内容 |
|---|---|
| 请求侧 §3.5 | 工具配对修复 —— C2R 需同类处理，CPA 的批次状态机是最完整参照 |
| 请求侧 §5.1 | 静默丢弃清单 —— C2R 需补一份自己的清单（Chat 侧字段在 Responses 侧无对应物的） |
| 请求侧 §5.2 | 不塞占位内容 —— 与 sub2api 的 `"(empty)"` / new-api 的 `"..."` 相反 |
| 请求侧 §5.3 | 不替客户端做决定 —— 见 §7 的三条「不应照抄」 |
| 请求侧 §6.1 | 报错而非静默修正 —— new-api 的 `n>1` 与 `tool_choice`+`function_call` 冲突可参照 |
| 请求侧 §7 | 请求翻译必须为响应侧留出口 —— C2R 同样需要 `translationContext` |
| 请求侧 §4.8 | 加密思考三约束 —— C2R 请求侧可能不触发；R2C 是真正的阻塞项 |
| 响应侧契约 | C2R 的响应侧（Responses 事件流 → Chat chunk）是另一半，需另立或补章节 |

---

### 9. 尚未决定的事项

- **`system` 落点** —— 三家三种做法（§3），实测又多出第四种：`instructions` 与
  `input` 的 `developer` **并存且承载不同内容**（§10.2）。需先定 C2R 的取向，
  再确认由谁做（翻译层还是新增一条 Responses 侧的 system 支线）。
- **`store` / `include` 是否强制** —— 两家强制、new-api 透传。实测表明**上游不一定会用**
  这两个字段（§10.1：`include` 索要了密文但上游根本不产出思考），因此
  「强制 `include`」的收益存疑；但那是「替客户端索取它没要的东西」，倾向不强制。
- **C2R 响应侧** —— 已拿到**三种形态**（§10.9 / 响应侧契约第 16 节）：纯文本、
  reasoning+文本、**reasoning+文本+工具调用**，`sequence_number` 都严格连续。
  三组事件（`output_text.*` / `reasoning_text.*` / `function_call_arguments.*`）齐全，
  字段差异也已记录。仍缺的是**错误/取消终态**（`response.failed` / `incomplete`）与
  多轮工具调用（多个 `function_call` item 并存）。
- **`max_output_tokens` 下限与 clamp 策略** —— 三家三个值（§1.4），且我们已有 4K 预设，
  需确认 C2R 侧是否还需要额外处理。实测两份请求都**未带** `max_output_tokens`。
- **`namespace` / `web_search` 工具怎么处理** —— 实测 Codex 的工具是三种形态
  （`function` / `namespace` / `web_search`，§10.3），后两种在 Chat 里**无对应物**。
  翻译时是丢弃、拍平还是保留，需定。
- **`function_call.id` / `message.id` 要不要造** —— 实测一半样本不造（§10.4），
  倾向不造；但需确认上游是否在某些场景下要求。

---

### 10. 真实抓包（2026-09-29）

前三节来自读源码。本节是**真实 Codex 客户端经过 CCS 打到第三方供应商**时抓到的报文，
四份请求 + 四份完整响应事件流。文件在工作区根目录（保留备查）：

| 文件 | 内容 |
|---|---|
| `stepfun-5-codex-toolcalls.json` | 请求，`step-5-preview`，30 项 input，含 6 轮工具调用 |
| `stepfun-5-codex-image.json` | 请求，`step-5-preview`，3 项 input，Codex 的「生成标题」子请求 |
| `gpt-6-astra-toolcalls.json` | 请求，`gpt-6-astra`，32 项 input，续轮（9 轮工具调用） |
| `gpt-6-astra-toolcalls-res.json` | 响应，69 条事件，纯文本 |
| `stepfun-5-codex-toolcalls-res.json` | 响应，119 条事件，reasoning + 文本 |
| `stepfun-5-codex-image-res.json` | 响应，112 条事件，纯文本 |
| `stepfun-5-codex-image-midway.json` | 响应，34 条事件，**reasoning + 文本 + `function_call`** |

#### 10.1 `encrypted_content` 在真实链路上的实际情形

**四份响应里 `encrypted_content` 一律为 `null`**，但 `reasoning` **是产出的** ——
取决于模型当轮是否思考：

| 响应样本 | 事件数 | reasoning 事件 | `reasoning_tokens` |
|---|---|---|---|
| stepfun 读图 | 34 | **13** | **35** |
| stepfun 工具链 | 119 | **38** | **129** |
| stepfun 标题 | 112 | 0 | 0 |
| astra 工具链 | 69 | 0 | 0 |

思考走独立 item + `reasoning_text` 内容块，**明文**下发：

```json
// response.output_item.done（output_index=0）
{ "id": "94353fb5f5fa5ea7", "type": "reasoning", "status": "completed",
  "summary": [],
  "content": [ { "type": "reasoning_text", "text": "…明文思考…" } ],
  "encrypted_content": null }
```

**关键结论：这份链路里根本不产生密文 —— 思考以明文下发，且 `encrypted_content` 恒为 `null`。**
即便请求方明确带了 `include: ["reasoning.encrypted_content"]`（四份请求都带了）。
Codex 回传时保持同一形态（明文 `content`、`encrypted_content: null`）。

**⚠ 这是该第三方供应商的行为，不是 Responses 协议的规定。** 官方签发密文，
所以契约 §4.8 的三条硬约束**在官方上游成立、在这类第三方上游不成立**。
「是否签发密文」完全取决于上游，**不能假定任何一种**。

**请求侧：Codex 回传的 `reasoning` item 是明文。**

```
reasoning 项: 6 个
  字段:  [type, summary, content, encrypted_content]
  encrypted_content: 全部 null
  summary: 全部为 []
  content: 6/6 非空，content[0].type = "reasoning_text"
```

```json
{ "type": "reasoning", "summary": [],
  "content": [{ "type": "reasoning_text", "text": "…明文思考…" }],
  "encrypted_content": null }
```

**这修正了请求侧契约 §4.8 的一处前提。** 那节记录「Codex 回传的 item 是
`{type, id, summary: [], encrypted_content}` —— 没有 `content`」，并据此推论
「Codex 手上只有密文，无法回放明文」。实测表明：

- `content` **有值且是明文**，`encrypted_content` 为 `null`；
- 原因是**这类上游不签发密文** —— 密文取决于上游行为，不是 Responses 协议的必然产物
  （四份响应都没签发，而请求方明确索要了）。

因此 §4.8 的三条硬约束（`done` 才完整、密文绑定签发者、部分上游强索明文）
**只在「上游确实签发密文」时成立**，而第三方供应商普遍不签发。

**结论**：C2R 请求侧**不需要思考缓存**。输入是 Chat 形态、下游手上有明文；
即便目标上游签发密文，那也是**响应侧**的事（且 R2C 才是输入密文的那一侧）。

#### 10.2 `instructions` 与 `input.developer` 并存，承载不同内容

对比两份请求（`input[0]` 的 developer 块**字节级相同**：2883 + 4100 + 1348 字符）：

| | `instructions` 长度 | `input` 内文本总量 | input 项数 |
|---|---|---|---|
| `stepfun-5-codex-toolcalls` | **21026**（Codex 全文系统提示） | 27225 | 30 |
| `gpt-6-astra-toolcalls` | **117**（仅一句 `You are Codex…`） | 10451 | 32 |

```text
stepfun（首轮）：instructions 放 21K 全文系统提示，input 里是 3 个结构化短块
astra（续轮）  ：instructions 只剩 117 字符，那 3 个短块仍在 input[0]
```

**`instructions` 与 `input` 的 `developer` 不是二选一，而是两个通道**：

- **`instructions`** —— 每轮重发的系统提示（长文本）
- **`input[].role == "developer"`** —— 结构化的指令块
  （`<skills_instructions>` / `<permissions instructions>` / `<collaboration_mode>`）

**这不改变 §3 里三家做法的对比**（new-api 提升到 `instructions`、sub2api 留 `system`、
CPA 改写为 `developer`），但说明**「只选一个通道」的做法都是简化**。

对 C2R 的含义：Chat 的 `messages[].role == "system"` 该走哪个通道，没有来自协议的判据。
Codex 能二分是因为它**自己生成**这些内容（它知道哪个是系统提示、哪个是结构化块）；
C2R 拿到的是下游给的普通消息，没有这个信息。

#### 10.3 工具是三种形态，不止 `function`

```json
{"type":"function",   "name":"exec_command",  "strict":false, "parameters":{…}}
{"type":"namespace",  "name":"multi_agent_v1","tools":[ {function…} ×5 ]}
{"type":"web_search", "external_web_access":false}
```

两份请求都是 `function=10, namespace=2, web_search=1`。`namespace` 内层是**扁平的**
function 列表（无 `function` 包裹层），且内层 `strict=false`。

`web_search` 是**服务端工具**，Chat 里无对应物；`namespace` 也是 Responses 特有形态。
**这两类在 C2R 里怎么处理（丢弃 / 拍平 / 保留）是新增的未决项**（§9）。

另注：`strict` 在两份样本里**全部是 `false`**，10 个顶层 function 无一缺失该字段。
结合 CPA 的注释（Chat 默认 `false`、Responses 默认 `true`），
**Chat 侧若省略 `strict`，翻译时必须显式写 `false`** —— 否则语义被改变。

#### 10.4 `id` 字段的有无：一半样本不造

| | `function_call.id` | `call_id` 前缀 | `function_call_output.id` | `message.id`（assistant） |
|---|---|---|---|---|
| astra | **9/9 带** `fc_…` | `call_…` | 9/9 带 `fco_…` | **9/9 带** `msg_…` |
| stepfun | **0/6 带** | `call_…` | 6/6 带 `fco_…` | **0/5 带** |

同一批工具、同一客户端，`function_call.id` 与 `message.id` 一个有一个没有。
**唯一恒定的是 `function_call_output.id`（两份都带 `fco_`）**。

含义：`id` 在 Responses 里是**可选**的。C2R 合成时**不应主动造**
`function_call.id` / `message.id`（两个真实样本有一个不造，说明上游不要求）。

#### 10.5 响应事件序列：两种形态

三份响应的 `sequence_number` **都是 0..n−1 严格连续**（无跳号）。

**形态 A：纯文本（69 / 112 条）**

```
response.created
response.in_progress
response.output_item.added        ← item.type=message, status=in_progress, content=[]
response.content_part.added       ← part.type=output_text, text=""
response.output_text.delta  × N
response.output_text.done
response.content_part.done
response.output_item.done         ← item.status=completed
response.completed
```

**形态 B：reasoning + 文本（119 条）** —— 多出前一半，两个 item 用**递增的 `output_index`**：

```
… output_item.added        ← output_index=0, item.type=reasoning
  reasoning_part.added     ← content_index=0, part.type=reasoning_text
  reasoning_text.delta ×35 ← 累加 494 字符 = done.text 长度（逐字节相等）
  reasoning_text.done
  reasoning_part.done
  output_item.done         ← output_index=0
  output_item.added        ← output_index=1, item.type=message
  content_part.added → output_text.delta ×71 → … → output_item.done
response.completed
```

**形态 C：reasoning + 文本 + `function_call`（34 条）** —— **三个 item 递增 `output_index`**：

```
output_item.added        ← output_index=0, item.type=reasoning
reasoning_part.added → reasoning_text.delta ×10 → reasoning_text.done → reasoning_part.done
output_item.done         ← output_index=0
output_item.added        ← output_index=1, item.type=message
content_part.added → output_text.delta ×4 → output_text.done → content_part.done
output_item.done         ← output_index=1
output_item.added        ← output_index=2, item.type=function_call
function_call_arguments.delta ×4   ← 参数分片，逐片不是合法 JSON
function_call_arguments.done       ← 完整 arguments + name
output_item.done         ← output_index=2
response.completed
```

**`function_call` 组的事件形状**（此前标注为缺失，现已拿到）：

```json
// output_item.added —— arguments 是空串（不是 null）
{ "type": "function_call", "id": "a8deb7f72148415f", "call_id": "call_ac5fd85d13aa8b5b",
  "name": "view_image", "namespace": null, "arguments": "", "status": "in_progress" }

// function_call_arguments.delta —— 字段最少的一组
{ "type": "response.function_call_arguments.delta", "delta": "{",
  "item_id": "a8deb7f72148415f", "output_index": 2, "sequence_number": 27 }

// function_call_arguments.done —— 比 delta 多 name 与 arguments，没有 delta
{ "type": "response.function_call_arguments.done",
  "arguments": "{\"path\": \"D:\\\\…\\\\image.png\"}", "name": "view_image",
  "item_id": "a8deb7f72148415f", "output_index": 2, "sequence_number": 31 }
```

**两组 delta 的字段差异是本样本最有价值的细节**：

| 事件 | 字段 |
|---|---|
| `output_text.delta` | `content_index` / `delta` / `item_id` / `logprobs` / `output_index` / `sequence_number` / `type` |
| `reasoning_text.delta` | 同上但**无 `logprobs`、无 `obfuscation`** |
| `function_call_arguments.delta` | **只有** `delta` / `item_id` / `output_index` / `sequence_number` / `type` —— **无 `content_index`** |

`function_call_arguments.delta` **没有 `content_index`**，因为 function_call item 没有
content 数组（它是 `arguments` 字符串）。合成 Chat chunk 时这一点决定
`tool_calls[].index` 从哪取。

**骨架规律**：`created → in_progress → (每个 item 一组: added → [part.added →] delta×N →
[done] → [part.done] → item.done) → completed`，item 的 `output_index` 递增（0, 1, 2…）。

**思考文本的分片是完整可累加的**（35 片 = 494 字符 = `done.text` 长度，逐字节相等），
**工具参数分片也是完整可累加的**（4 片拼出完整 JSON），但**每一片单独都不是合法 JSON** ——
与响应侧契约第 4.1 节（Anthropic 侧同一现象）一致。

其它字段观察：

| 项 | 值 |
|---|---|
| `sequence_number` | 四份样本都是 0..n−1 连续，无跳号 |
| `output_text.delta` 附加字段 | `logprobs:[]` + `obfuscation`（**仅 astra 有**，stepfun 三份都没有） |
| `response.created` vs `completed` 差异字段 | `status` / `completed_at` / `content_filters` / `output` / `service_tier` / `usage` |
| 上游改写 effort | astra：请求 `high` → 响应 `low`；stepfun：`high` → `high`（**不改写**） |
| `response.created` 顶层字段数 | stepfun **30** / astra **36**（**供应商相关**） |
| item `id` 形态 | stepfun：16 位十六进制裸串（`94353fb5f5fa5ea7`）；astra：`msg_` + 长 hex |
| `message.phase` | astra `"final_answer"`；stepfun **`null`**（字段存在但为空） |

#### 10.6 Codex 特有字段（Chat 里无对应物）

| 字段 | 出现位置 | 取值 |
|---|---|---|
| `phase` | 请求 input 的 assistant 消息 / 响应 item | 请求侧 `"commentary"`（9/9）；响应侧 `"final_answer"` |
| `client_metadata` | 请求顶层 | 7 个键，含 `x-codex-turn-metadata`（嵌套 JSON 串）等 |
| `prompt_cache_key` | 请求顶层 | 与 `thread_id` 同值的 UUID |
| `internal_chat_message_metadata_passthrough` | 响应 item | 上游私有，含 `create_time` / `turn_id` |

`phase` 是值得注意的：它区分「中间旁白」与「最终答复」，**Chat 协议里没有这个概念**。
C2R 若丢弃它，下游就失去了这个区分（Copilot / Codex 回放时可能行为不同）。

#### 10.7 两份请求的公共骨架（C2R 的目标形态）

顶层字段（三份请求一致）：

```json
{
  "model": "…",
  "input": [ … ],
  "instructions": "…",          // 长度可变（117 / 21026）
  "tools": [ … ],               // 可能为空
  "tool_choice": "auto",
  "parallel_tool_calls": true,
  "reasoning": { "effort": "high" },
  "store": false,
  "include": ["reasoning.encrypted_content"],
  "prompt_cache_key": "…",
  "client_metadata": { … },
  "stream": true
}
```

**没有**的字段：`max_output_tokens` / `temperature` / `top_p` / `previous_response_id`
（四份请求全为 `undefined`）。`text` 只在 image 样本出现（`json_schema` 子请求）。

input 项的类型集合：`message`（`developer` / `user` / `assistant`）、`reasoning`、
`function_call`、`function_call_output`。

#### 10.8 本轮修正的旧判断（摘要）

| # | 原判断 | 实测修正 |
|---|---|---|
| 1 | 契约 §4.8：Codex 回传的 item「没有 `content`」，只有密文 | **有 `content` 且是明文**，`encrypted_content` 恒为 `null`（第三方上游不签发密文） |
| 2 | §6：「C2R 可能是思考缓存的触发点」 | **C2R 不需要缓存**（输入是 Chat 明文）；只有「R2C + 上游确实签发密文」才需要 |
| 3 | §3：`system` 落点三家三选一 | 实测是**两个通道并存**（`instructions` + `input.developer`），「三选一」是简化 |
| 4 | §1.1：工具是 `{"type":"function","function":{…}}` 嵌套形态 | Codex 自己发的是**扁平**形态，且有 `namespace` / `web_search` 另两种类型 |
| 5 | 「`id` 由客户端生成」 | **一半样本不造** `function_call.id` / `message.id`；只有 `output.id` 恒带 |
| 6 | 响应侧契约 §9.2：「`reasoning_tokens` 拿不到」 | **只在 Anthropic 侧成立**；Responses 有 `usage.output_tokens_details.reasoning_tokens`，且**不进** `output_tokens` |
| 7 | §6 推测的「上游可能改写 effort」 | 实测**因供应商而异**：astra 把 `high` 降成 `low`；stepfun 保持原值。响应里的 `reasoning` 不是请求回显 |
| 8 | 「上游完全不产出思考」（我基于单份 astra 样本的错误结论） | **错误**。stepfun 产出思考（13 / 38 条 reasoning 事件），只是**以明文下发**。是否产出思考取决于模型当轮，是否签发密文取决于上游 |

#### 10.9 响应事件序列的三种形态（详见响应侧契约第 16 节）

四份响应的 `sequence_number` **都严格连续**（0..n−1），给出三种骨架：

| 形态 | 样本 | 事件数 | output items（`output_index`） |
|---|---|---|---|
| 纯文本 | astra / stepfun-image | 69 / 112 | `message` |
| reasoning + 文本 | stepfun-toolcalls | **119** | `reasoning`(0) + `message`(1) |
| reasoning + 文本 + 工具 | stepfun-midway | **34** | `reasoning`(0) + `message`(1) + `function_call`(2) |

**形态 C（含工具）是 C2R 最关键的一份** —— 它同时含三组事件，覆盖了 C2R 响应侧的
主要场景。工具组的骨架：

```
output_item.added (function_call, arguments="")
→ function_call_arguments.delta ×4   ← 每片单独不是合法 JSON
→ function_call_arguments.done       ← 完整 arguments + name
→ output_item.done
```

`response.completed.output` 是**三个 item 的完整数组**（reasoning / message / function_call），
与流式 item 一一对应 —— 非流式场景可直接用它。

reasoning 组的骨架：`output_item.added → reasoning_part.added →
reasoning_text.delta ×35 → reasoning_text.done → reasoning_part.done → output_item.done`。
**思考文本的分片完整可累加**（35 片 = 494 字符 = `done.text` 长度，逐字节相等），
**工具参数分片也能累加**（4 片拼出完整 JSON），但**单片不是合法 JSON**。

`reasoning` item 的形状（added 时 `content` 为 **`null`** 而非空数组）：

```json
// added
{ "id": "94353fb5f5fa5ea7", "type": "reasoning", "status": "in_progress",
  "summary": [], "content": null, "encrypted_content": null }
// done
{ "id": "94353fb5f5fa5ea7", "type": "reasoning", "status": "completed", "summary": [],
  "content": [ { "type": "reasoning_text", "text": "…明文…" } ],
  "encrypted_content": null }
```

**三种 item 在 added 时的「空值」形态各不相同**（合成时的坑）：

| item.type | `content` | `arguments` | `summary` |
|---|---|---|---|
| `reasoning` | **`null`** | — | `[]` |
| `message` | `[]` | — | — |
| `function_call` | — | **`""`** | — |

---

## C. C2R 去程 · 计划与实施记录


> **状态**：三阶段全部完成（2026-10-04：翻译器 + system 支线 + 实机验证）。去程可用；
> 回程（R2C）也已完成（2026-10-04 三家供应商实机验证 + §7.4 修复闭环，见
> [R2C-PLAN.md](#e-r2c-回程--计划与实施记录)）—— C2R 线路已端到端可用
>
> **需求背景**：让下游 Chat Completions 请求（`/v1/chat/completions`）能发往只支持
> Responses 的上游供应商。这是 protocol-translation 功能 C2R 方向的**前半（去程）**；
> 回程（R2C，Responses 事件流 → Chat chunk）是另一半，已由
> [R2C-PLAN.md](#e-r2c-回程--计划与实施记录) 完成。
>
> 相关：[C2R-RESEARCH.md](#b-c2r-去程--调研)（四项目对比 + 真实抓包，本文决策的事实基础）、
> [R2C-RESEARCH.md](#d-r2c-回程--调研)（回程翻译调研与决策，下一步的素材）、
> [../chat-messages/REQUEST-CONTRACT.md](../../reference/contracts/C2M-REQUEST-CONTRACT.md)
> （C2M 契约，翻译口径的先例）、
> [../chat-messages/RESPONSE-CONTRACT.md](../../reference/contracts/C2M-RESPONSE-CONTRACT.md) §16
> （C2R 响应事件序列的实测记录，回程的素材）。

---

### 0. 决策与理由（六项已定）
> **决策已归档**：[ADR-0004 C2R 去程翻译的六项决策](../../adr/adr-0004-c2r-request-translation.md)。
> 下表保留实现时的完整理由（含字段级细节），ADR 提供独立的决策记录与备选方案对比。


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

### 1. 范围与预期中间态

- **只做去程**：`ChatToResponsesRequestTranslator` + 一个新支线（见 §3）。
- **回程未实现期间的既定行为**：`TranslatorRegistry.findResponseTranslator(CHAT, RESPONSES)`
  未命中 → 编排层**原样透传**上游 SSE（不静默，留痕）。下游 Chat 客户端收到
  Responses 原生事件流、解析不了 —— 与当年「C2M 先做去程」时完全相同的中间态，
  目的是「先用真实上游验证请求是否被接受」（`TranslatorRegistry` 的 Javadoc 契约）。
  回程落地后这条链才端到端可用。

> **后记**：此中间态是本计划实施期间的真实状态，作为历史记录保留；
> 回程已于 [R2C-PLAN.md](#e-r2c-回程--计划与实施记录) 落地，该链路现已端到端可用。

---

### 2. 字段映射总表

#### 2.1 顶层标量

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

#### 2.2 `messages[]` → `input[]`（按 role 分派）

| Chat 消息 | Responses input item | 说明 |
|---|---|---|
| `role:"system"` | `{type:"message", role:"system", content:[…]}` | **role 不动**——改写 `developer` 是新支线的职责（§3.2）。位置与顺序保留 |
| `role:"user"` | `{type:"message", role:"user", content:[…]}` | 内容块映射见 §2.2.1 |
| `role:"assistant"`（纯文本） | `{type:"message", role:"assistant", content:[{type:"output_text",…}]}` | **不造 `id`**（决策 #5） |
| `role:"assistant"`（带 `tool_calls`） | 每个工具调用一个 `{type:"function_call", call_id, name, arguments}` item | `tool_calls[].id` → `call_id`；**不造 `id`**。若同一条消息还有文本，文本照常产 message item |
| `role:"assistant"`（带 `reasoning_content`） | 文本部分照常；`reasoning_content` **丢弃** | Chat 的明文思考无 Responses 请求侧对应物（`reasoning` item 是上游产出的，客户端回放靠密文或明文 text —— 下游 Chat 客户端本就不回放，见 BYOK 调查） |
| `role:"tool"` | `{type:"function_call_output", call_id, output:…}` | `tool_call_id` → `call_id`。**配对修复**：找不到前置 `function_call.call_id` 匹配时按 C2M 契约 §3.5 同类口径处理（CPA 批次状态机为参照：配不上则丢弃该条并留痕，不凭空造 `function_call` 去配） |

##### 2.2.1 内容块白名单（`content` 数组形态）

| Chat 块 | Responses 块 | 说明 |
|---|---|---|
| `{type:"text", text}` | `{type:"input_text"/"output_text", text}` | user/system → `input_text`，assistant → `output_text`（C2R-RESEARCH §4.1 两家一致） |
| `{type:"image_url", image_url:{url}}` | `{type:"input_image", image_url}` | 解包成纯 url 字符串；**丢 `detail`**（三家共识）；仅 user 消息 |
| `{type:"input_audio", …}` | `{type:"input_audio", …}` | 仅 user 消息 |
| `{type:"file", file:{…}}` | `{type:"input_file", …}` | `filename`/`file_data` 官方键 |
| `content` 为纯字符串 | 包成单元素 `input_text` 数组 | — |

不认识的块类型：**报错**（`RequestTranslationException`），不静默丢弃——与 C2M 契约
「不认识的形态报错」同口径。

#### 2.3 工具与 tool_choice

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

### 3. 组件与接线（总览）

#### 3.1 `ChatToResponsesRequestTranslator`（新，阶段一）

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

#### 3.2 `ResponsesSystemPromptStage`（新支线，阶段二）

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

#### 3.3 调度与设置层（零改动清单）

| 组件 | 改动 |
|---|---|
| `TranslatorRegistry` | 零（自动收集） |
| `ProtocolDispatchManager` | 零（`implementedRequestRoutes` 自动含新方向） |
| `RequestBodyAssembler` | 零（查表自动命中 `ResponsesSystemPromptStage` / 已有 `ResponsesThinkingStage`） |
| `ReasoningEffortSetting` | 零（`applyToResponses` 已就位，表态判定只看 `reasoning.effort`） |
| schema / 迁移 | 零（纯代码，无库变更） |

#### 3.4 既有测试受影响清单（深度探索核实）

| 测试 | 影响 | 阶段 |
|---|---|---|
| `RequestBodyStageSpringWiringTests` | `systemPromptStages` 从 1 变 2；`findSystemPromptStage(RESPONSES)` 从 empty 变 present；`otherProtocolsFindNothingYet` 用例需改（其 Javadoc 写着「另两条协议查不到是预期」的理由要同步改写） | 阶段二 |
| `ProtocolDispatchManagerTests` | 无需改——该文件用自构造的 `managerWithAllRoutesImplemented()` / `managerWith()`，**不依赖生产 Bean**，实现状态是显式注入的变量 | — |
| `ChatDispatchErrorSignalTests` / `RequestPipelineTests` | 若有「C2R 未实现报错」的断言需核查（探索未见直接引用，阶段一落地后跑全量确认） | 阶段一 |

---

### 4. 明确不做的事

| 不做 | 理由 |
|---|---|
| **R2C 回程翻译** | 独立下一步；调研与决策已完成（[R2C-RESEARCH.md](#d-r2c-回程--调研)），关键素材齐备（RESPONSE-CONTRACT §16 三种事件形态 + 三家联测）。去程先行是为用真实上游验证请求可被接受 |
| **思考缓存** | 实测不需要（§10.1：C2R 输入是 Chat 明文；第三方上游不签发密文）。真正需要它的是「R2C + 上游确实签发密文」，届时再议（CPA 的 TTL/容量参数是现成参照） |
| **`namespace` / `web_search` 工具合成** | 决策 #4：忠实搬运，Chat 里没有的不造 |
| **协议流转编排**（`ProtocolDispatchManager` 的 TODO） | 独立事项，有自己的规划；C2R 落地不依赖它（直连走规则 1 不读回退序） |
| **max tokens 支线化（RESPONSES 侧）** | 本轮在翻译器实现一次映射；`ResponsesMaxTokensStage` 是本方向的**新增件而非重构**——RESPONSES 线路（直连 + C2R 翻译后）目前完全吃不到 `MaxOutputTokensSetting` 的注入。与 `MessagesMaxTokensStage` 对称，独立一轮处理（见 §6.4） |
| **CHAT 直连的 max tokens 注入缺失** | 历史成因：曾以为 CHAT 路径无该字段；实际 Chat 协议有 `max_tokens`/`max_completion_tokens`，缺失属**语义缺失**而非无字段可注。刻意不在本分支修（独立一轮），见 §6.4 |
| **补默认 effort / 剥采样参数 / 跳过 max_output_tokens 映射** | C2R-RESEARCH §7 三条「不应照抄 CPA」 |
| **`reasoning.summary` 写值** | 决策 #6：显式 opt-in，不与 effort 耦合 |

---

### 5. 阶段划分与提交边界

三个阶段各自可独立提交、独立验证。顺序刻意如此：阶段一先让「请求能发成上游能懂的
形态」成立（验证手段是 mock 收到的 body + 原样透传的响应）；阶段二补 role 改写；
阶段三是真实 HTTP 的端到端确认。**不要把阶段一与二合并**——阶段一落地后全量测试
必须全绿（含既有 dispatch/wiring 断言），这是「翻译器正确」的完整证明；混进支线
后一个失败用例无法区分是翻译错还是支线错。

#### 阶段一：翻译器主体（核心，最大块）

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

#### 阶段二：`ResponsesSystemPromptStage` 支线（小）

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

#### 阶段三：实机联测（验证收尾）

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

### 6. 遗留与待确认

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

### 7. 实施记录（各阶段完成后追加）

#### 7.1 阶段一：翻译器主体（2026-10-04 完成）

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

#### 7.2 阶段二：ResponsesSystemPromptStage 支线（2026-10-04 完成）

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

#### 7.3 阶段三：实机联测（2026-10-04 完成）

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

---

## D. R2C 回程 · 调研


> **状态**：调研与决策完成（2026-10-04）：§6 倾向列即实现口径，§9 记录曾有分歧的决策。
> 实施时应把确定的部分并入
> [RESPONSE-CONTRACT.md](../../reference/contracts/C2M-RESPONSE-CONTRACT.md)（响应侧契约）
> 或另立 R2C 章节。
>
> 调研于 2026-10-04。四个项目均在工作区内（`references/new-api` / `references/sub2api` /
> `references/cc-switch` / `references/CLIProxyAPI`，都是独立参考项目，只读）。
>
> 上游事件形态的依据是本地实测（[samples/](../../reference/samples/) 七份抓包 + 2026-10-04
> 三家真实供应商联测记录，见 [PLAN.md](#c-c2r-去程--计划与实施记录) §7.3）。

R2C = 上游 OpenAI Responses 响应（非流式 JSON / SSE 事件流）→ 下游 OpenAI Chat
Completions 响应（`choices[0].message` / SSE chunk 流）。

**这是 C2R 的另一半**：C2R 去程只让请求能发出去，回程把 Responses 原生响应转回 Chat，
下游 Chat 客户端才能解析。**C2R 去程已实测可用（PLAN §7.3），响应目前是原样透传的中间态。**

---

### 0. 四个项目各自覆盖什么

**四个项目里，三家实现了我们的 R2C 方向**（new-api / sub2api / CPA），第四家 cc-switch 做的是
镜像方向（附带的乱序实测有独立价值）：

| 项目 | 非流式 | 流式 | 关键文件 | 备注 |
|---|---|---|---|---|
| **new-api**（Go） | ✅ | ✅ | `relaykit/relayconvert/internal/oai_responses/to_oai_chat_resp.go` + `to_oai_chat_stream_resp.go` | 实现最完整（含 annotation、pending 工具参数、缓冲累加器） |
| **sub2api**（Go） | ✅ | ✅ | `backend/internal/pkg/apicompat/responses_to_chatcompletions.go` | 「非流式但上游强制 SSE」有专门累加器 |
| **CPA**（Go） | ✅ | ✅ | `internal/translator/codex/openai/chat-completions/codex_openai_response.go` | 注册在 `(OpenAI, Codex)` 方向；「done 后晚到 arguments」不处理 |
| **cc-switch**（Rust） | ❌ 方向相反 | ❌ 方向相反 | 见下 | ⚠ 它的镜像方向 + 乱序实测有借鉴价值 |

#### 方向定位的三个坑

1. **CPA 的注册表方向是 `to → from`（反向）**。`Register(OpenAI, Codex, reqFn, respFn)`
   中的 `respFn` 实际是 `Codex → OpenAI` 方向（`sdk/translator/registry.go:239-243,281-285`）。
   要找 R2C 应看 `codex/openai/chat-completions/`（函数名 `ConvertCodexResponseToOpenAI`），
   而不是名字看起来更像的 `openai/openai/responses/`（那是反方向）。
2. **cc-switch 没有我们的 R2C 方向，但它有两个高价值的邻近实现**（方向核对结果）：
   - `transform_codex_chat.rs:266` `responses_to_chat_completions` —— **请求**侧（Responses 请求 → Chat 请求）
   - `transform_codex_chat.rs:1585` `chat_completion_to_response` + `streaming_codex_chat.rs:736`
     `create_responses_sse_stream_from_chat` —— **镜像响应**方向（Chat 响应 → Responses 响应）
   - **cc-switch 的设计是「Codex 客户端 ↔ Chat 上游」**（客户端是 Responses、上游是 Chat），
     与我们的 R2C（上游 Responses、下游 Chat）**恰好相反**。四个项目里**只有它专门处理
     「上游 Chat / 下游 Responses」**，其余三家处理「上游 Responses / 下游 Chat」
3. **但 cc-switch 的 `responses_late_arguments.rs` 仍是四家里唯一记录「上游乱序」的实测**
   （见 §5）—— 它服务的是 Responses 透传流（Codex 客户端直连 Responses 上游），
   与我们的 R2C 输入侧面对的是同一类上游行为，**乱序防护结论可直接借鉴**。

#### 本服务（COSP）的现状

- `TranslatorRegistry` 的 `(CHAT, RESPONSES)` 回程位**空着**，故当前响应原样透传（留痕）
- `ResponseProtocolTranslator` 接口就位（三个方法：`translateResponse` / `translateStream` /
  `translateChunksForLog`，签名见接口 Javadoc）
- 参照物：`MessagesToChatResponseTranslator` + `MessagesToChatStreamTranslator`
  （M2C 方向的同形实现，`M2CStreamState` 状态机）

---

### 1. 非流式转换（`response.output[]` → `choices[0].message`）

#### 1.1 字段映射总表

| Responses（上游） | Chat（下游） | new-api | sub2api | CPA | COSP 倾向 |
|---|---|---|---|---|---|
| `output[].type=="message"` 的 `content[].text`（`output_text`） | `message.content` | 拼接（多 item 用空行分隔） | 拼接（无分隔符） | 取**第一个** `output_text` | 拼接（见 §1.2） |
| `output[].type=="reasoning"` 的 `content[].reasoning_text`（或 `summary[]`） | `message.reasoning_content` | ✅ 独立字段 | ✅ 独立字段 | ✅ 独立字段 | ✅ 独立字段 |
| `output[].type=="function_call"` / `custom_tool_call` | `message.tool_calls[]` | ✅ | ✅ | ✅ | ✅ |
| `output[].type=="web_search_call"` | — | 未知 | **静默吞掉** | 未知 | 丢弃 |
| `status` / `incomplete_details.reason` | `finish_reason` | ✅（incomplete→length/content_filter） | ✅（同） | ✅（同） | ✅ |
| `usage` | `usage` | ✅ | ✅ | ✅ | ✅ |
| `id` | `id` | 原样（`resp_` 前缀保留） | 原样（空则生成 `chatcmpl-`） | 原样 | **原样**（§9 已定） |
| `model` | `model` | `resp.Model` | **入参 originalModel**（不用 resp.Model） | `resp.Model` | **回显下游带前缀名**（§9 已定，C2M 先例） |
| `created_at` | `created` | — | 忽略，用 `time.Now()` | 用 `resp.created_at` | **原样用 `created_at`**（§9 已定） |
| `output[].phase` | — | — | — | — | 丢弃（Chat 无对应物） |
| `output[].encrypted_content` | — | — | — | — | 丢弃（Chat 无对应物；R2C 输入本就不需要） |
| `annotations`（`url_citation`） | `message.annotations` | ✅ 转换 | — | — | **丢弃**（§9 已定，最小实现） |

#### 1.2 content 拼装：三家的分隔策略不同

| 项目 | 多 message item | 多个 `output_text` part |
|---|---|---|
| new-api | 空行分隔（`appendSeparatedText`，`to_oai_chat_resp.go:314`）——智能处理首尾换行，避免重复 | 直接拼接 |
| sub2api | **无分隔符**直接首尾相接 | 直接拼接 |
| CPA | 只取**第一个** `output_text`（`codex_openai_response.go:506`，`break`） | 只取第一片 |

**new-api 的 `appendSeparatedText` 设计精巧**（`:314-333`）：检查 builder 尾部的换行数 +
新文本头部的换行数，只补足到 2 个（不重复添加）。这是「多 segment 拼接」的成熟做法。

**对本服务的含义**：C2M 的响应侧契约已有类似决策（M2C 的 `text` 块拼接）。倾向
**拼接 + 空行分隔**（对齐 new-api）；CPA 的「只取第一片」在单 message 多 part 时会丢内容。

#### 1.3 reasoning 的读取路径

| 项目 | 读什么 |
|---|---|
| new-api | `reasoning_output_text`（`to_oai_chat_resp.go:290`）：**先看 `content[].text`（有则全取），为空才回落到 `summary[]`** |
| sub2api | 拼 `summary[]` 的 `summary_text` → `ReasoningContent` |
| CPA | **先 `summary[]` 取第一个，再 `content[]` 全取**（`codex_openai_response.go:475-497`），两路拼接 |

**三家在 `content` vs `summary` 的优先级上不一致**：
- new-api：content 优先，summary 兜底
- CPA：两个都取并拼接（先 summary 后 content）

**实测佐证**（PLAN §7.3 / C2R-RESEARCH §10）：三家真实供应商（mimo/stepfun/deepseek）都下发
**明文 `content[].reasoning_text`**，`summary` 为空数组。因此**结论：以 `content[]` 为主，
`summary[]` 兜底** —— 与 new-api 一致，且能兼容只发 summary 的上游。

#### 1.4 tool_calls 构造

| 项目 | id 来源 | index | arguments |
|---|---|---|---|
| new-api | `CallId`，空则回落 `ID` | **不设**（非流式不需要） | 原样 |
| sub2api | `CallID` | 不设 | 原样（`CallID` 空也不校验） |
| CPA | `call_id` | 不设 | 原样（`custom_tool_call` 取 `input`） |

**共识**：`id` 优先 `call_id`，空则回落 item `id`；非流式不带 `index`。arguments 全部原样搬运
（Responses 的 `arguments` 本就是 JSON 字符串，与 Chat 相同）。

**CPA 独有的两个处理**：
- 工具名**反向映射**：请求侧可能缩短过工具名，响应侧用原始请求映射还原（`buildReverseMapFromOriginalOpenAI`）。
  **本服务请求侧不做缩短**，故不需要。
- `custom_tool_call` 的 `applypatch.WrapInput` 包装（Codex 专有 scene）。**本服务不适用。**

#### 1.5 id / model / created 的取舍

**分歧点**：
- `id`：new-api/CPA 原样透传 `resp_`；sub2api 也透传，仅空时生成
- `model`：sub2api 用**入参 originalModel**（而非 `resp.Model`）—— 理由是上游可能改写模型名，
  下游应看到它请求的模型名
- `created`：sub2api 忽略 `created_at` 用当前时间；CPA 用 `created_at`

**C2M 的既有先例**（响应侧契约）：模型名必须回显**下游带前缀名**（`[provider] model`）。
R2C 应沿用同一口径 —— **model 用入参的下游模型名**（与 sub2api 一致）。
`id` 与 `created` 倾向原样透传（信息更真实）。

#### 1.6 annotations（`url_citation`）—— new-api 独有

new-api 把 Responses 的 `output_text.annotation.added` / `content[].annotations` 转成
Chat 的 `message.annotations`（`to_oai_chat_resp.go:137-181`），且转换 `url_citation` 形态：
`{type:"url_citation", ...}` → `{type:"url_citation", url_citation:{...}}`。

**对本服务的含义**：Copilot/Chat 客户端对 `annotations` 的消费存疑（非官方字段）。
倾向**丢弃**（保持最小实现），记录为「不做」。

---

### 2. 流式状态机（核心）

#### 2.1 三家共同的事件骨架

三家消费的事件集**高度一致**：

| Responses 事件 | new-api | sub2api | CPA |
|---|---|---|---|
| `response.created` | 缓存 ID/model/created，发首个 role chunk | 取 ID/model/service_tier，发 role chunk | **只缓存，不发 chunk** ⚠ |
| `response.in_progress` | 忽略 | 忽略 | 忽略 |
| `response.output_text.delta` | `delta.content` | `delta.content` | `delta.content` |
| `response.reasoning_text.delta` / `reasoning_summary_text.delta` | `delta.reasoning_content` | `delta.reasoning_content` | `delta.reasoning_content` |
| `response.reasoning_*.done` | 标记需要 `\n\n` 分隔（下一段前缀） | 返回 nil | 发固定 `"\n\n"` |
| `response.output_item.added`（function_call） | 注册工具，发含 index/id/name 的首个 tool_calls chunk | 同（`NextToolCallIndex++`） | 同（`FunctionCallIndex++`） |
| `response.function_call_arguments.delta` | 累加 + 发 delta | 累加 + 发 delta | 累加 + 发 delta |
| `response.function_call_arguments.done` | 前缀补齐（见 §2.4） | 前缀补齐 | 整段补发（若之前没发过） |
| `response.output_item.done`（tool） | 触发 flush | 同 | 同 |
| `response.completed` / `done` | 终态：finish_reason + usage | 同 | 同 |
| `response.incomplete` | 终态（映射 length/content_filter） | 同 | 同 |
| `response.failed` / `response.error` | **报错**（返回 Go error） | 上报 | **忽略**（executor 层已拦） |

**⚠ 首个 role chunk 的时机三家不同**：
- new-api / sub2api：`response.created` 时就发 `{role:"assistant", content:""}`
- CPA：**没有独立的 role-only chunk**，role 在**第一个有内容的事件**里带上

**对本服务的含义**：M2C 的既有先例是「首个 chunk 带 role」。C2M 实测的
`response.created` 事件三家供应商都有，**倾向 new-api/sub2api 的做法**（created 时发 role chunk），
但需考虑：若上游不发 `created`，要兜底在首个内容事件带 role（CPA 的做法更鲁棒）。

#### 2.2 状态对象

三家的状态字段高度一致：

| 字段类别 | new-api | sub2api | CPA |
|---|---|---|---|
| 元数据 | `ID/Model/Created/IncludeUsage/Usage` | `SentRole/SawToolCall/SawText/Finalized` | `ResponseID/CreatedAt/Model/ServiceTier` |
| 工具索引 | `nextToolIndex` + 三级映射（outputIndex/itemID/callID → key） | `NextToolCallIndex` + `OutputIndexToToolIndex` | `FunctionCallIndex` + 三级查找 |
| 参数缓冲 | `pendingArgsByOutputIndex`/`pendingArgsByItemID`（**处理乱序**） | `OutputIndexToArguments` | 无（不处理乱序） |
| 已发标记 | `sentStart/finalized/sawToolCall/hasSentText/hasSentReasoning` | `SentRole/SawToolCall/SawText/Finalized` | `ArgumentsEmitted/Done` per tool |

**new-api 的 pendingArgs 机制最完整**（见 §2.4）。

#### 2.3 tool_calls 的 index 映射 —— 三家都是「本地重编号」

**上游的 `output_index` 不直接透传到 Chat 的 `tool_calls[].index`**。三家都维护一个
**递增的本地计数器**：

| 项目 | 计数器 | 映射键 |
|---|---|---|
| new-api | `nextToolIndex`（从 0） | 三级：`output:{n}` / `item:{id}` / `call:{id}` |
| sub2api | `NextToolCallIndex`（从 0） | `OutputIndexToToolIndex`（output_index → 本地 index） |
| CPA | `FunctionCallIndex`（从 **-1**） | 三级：item_id / item.id / output_index |

**理由**（CPA 注释 `codex_openai_response.go:210-218`）：上游的 `output_index` 包含
reasoning/message 的占位（如 reasoning=0, message=1, function_call=2），而 Chat 的
`tool_calls[].index` 只在**工具调用数组内**编号（0, 1, 2…）。**两组编号必须解耦**，
否则 Chat 客户端看到 index 从 2 开始会困惑。

**三级查找**（应对话件字段缺失）：`output_index` 最可靠（所有事件都带），
`item_id`/`call_id` 兜底。new-api/CPA 都做了三级；sub2api 只做 output_index 一级。

#### 2.4 arguments 分片处理 —— 两派做法

**A 派（前缀补齐 / 三家里两家）**：new-api 与 sub2api 都在 arguments delta 到达时，
对比**已发送的位置**（`ArgsSentAt` / 已累积串），只发**新增部分**：

```go
// new-api to_oai_chat_stream_resp.go:474-486
argsDelta := explicitDelta
if argsDelta == "" && len(tool.Arguments) > tool.ArgsSentAt {
    argsDelta = tool.Arguments[tool.ArgsSentAt:]
}
```
- **优势**：`function_call_arguments.done` 携带完整参数时，若之前已逐片发过，done 只需补差量
  （甚至不发）——**不重复、不漏**

**B 派（一次性补发 / CPA）**：delta 逐个发，done 时若之前**没发过**则整段发：

```go
// CPA codex_openai_response.go:256-281
if state.Done || state.InputClosed || (state.ArgumentsEmitted && !state.Patch) {
    return [][]byte{}   // 已发过 → 丢弃 done
}
// 否则整段 arguments 一次性发
```

**new-api 的 pendingArgs（A 派 + 乱序防护）**：当 delta 到达但**找不到对应工具**时
（added 事件尚未到 / 或工具未知），先把 delta 存进 `pendingArgsByOutputIndex` /
`pendingArgsByItemID`；等工具注册时再把 pending 拼进去（`:462-467`）。

**实测佐证**：本地三家供应商（mimo/stepfun/deepseek）都发完整的
`added → delta ×N → done` 序列，done 的 arguments 与累加值一致（见 PLAN §7.3）。
**结论：A 派的「前缀补齐」是正确且鲁棒的**——正常流下 done 不发重复，乱序流下靠 pending 兜底。

#### 2.5 终止处理与兜底

| 项目 | completed/failed/incomplete | 无终态兜底 |
|---|---|---|
| new-api | `completed`/`done`/`incomplete` → finish_reason + usage；`failed`/`error` → **报错** | `FinalizeResponsesToChatStream` 显式补 finish chunk（幂等） |
| sub2api | `completed` → 置 Finalized；`done` 用 `tool_calls`/`stop` 兜底 | `FinalizeResponsesChatStream` 补 stop/tool_calls |
| CPA | **翻译函数不检测终态**，全在 executor 层 | executor 层的三层兜底（见 §4） |

**finish_reason 推导**（三家一致）：
- `completed` → `tool_calls`（若有工具调用）否则 `stop`
- `incomplete` + `max_output_tokens` → `length`；+ `content_filter` → `content_filter`
- 其它 / 无终态 → `stop`（new-api 用 `tool_calls`/`stop` 兜底）

**usage 收尾 chunk**：new-api 与 sub2api 都在 finish chunk 后发 `choices:[]` 的
usage-only chunk（仅 `IncludeUsage` 时）。CPA 把 usage 附在终态 chunk 上。

#### 2.6 三家都「不处理」的事

| 项 | 说明 |
|---|---|
| `logprobs` | 三家都不读不产出（Chat 侧的 `delta.logprobs` 空置） |
| `obfuscation` | 全仓无实现 |
| `encrypted_content` | 响应侧完全忽略（不提取、不缓存、不回放） |
| `sequence_number` | 丢弃（Chat 协议无此字段） |
| `output_item.done` 后**晚到**的 arguments | new-api/sub2api 靠 pending 部分处理；CPA 明确丢弃 |

**结论：这五项都不做**（除 late arguments 外——那由 §2.4 的 pending 机制覆盖）。

---

### 3. 非流式但上游强制 SSE 的路径（sub2api 独有）

**这是 sub2api 最有价值的独有设计。** 它的 OpenAI 网关**强制上游流式**
（`openai_gateway_chat_completions.go:887` 附近），非流式请求也要读完 SSE 再合成为非流式响应。

- `BufferedResponseAccumulator`（`responses_to_chatcompletions.go:488-649`）：
  `ProcessEvent` 逐事件累积成一个完整的 `ResponsesResponse`（含 output items、tools）
- `SupplementResponseOutput`（`:758`）：处理三种**坏形态**——
  终态 output 为空 / message 无文本 / 文本全空白 → 用流内累积文本回填
  （**否则客户端拿到空回复但 usage 照计费**，测试注释明确写了这条）
- `readOpenAICompatBufferedTerminal`：读完找终态事件

**对本服务的含义**：COSP 的 `AfterSend` 有流式/非流式两条独立路径，**不强制上游流式**
（`after/send/responses/GenericResponsesChatService` 支持非流式调用）。因此
**不需要这个累加器** —— 但「终态 output 为空时用流内累积回填」的**思想**值得借鉴
（见 §6 的兜底清单）。

---

### 4. 工程细节（调用链位置与错误处理）

#### 4.1 翻译器在链条中的位置（三家一致）

```
上游 SSE 行 → [executor/网关] 逐行扫描
              ├─ 事件级错误探测（failed/error → 转 HTTP 错误，可触发 failover）
              ├─ 空响应探测
              ├─ TranslateStream（本翻译：1 事件 → 0..N chunk）
              └─ 终态后 return；未收终态的 EOF → 报错
```

**关键**：**翻译函数看不到 `failed`/`error`** —— executor 在此之前就拦成错误。
CPA 的 `switch` 里没有 failed/error 分支是**设计使然**（`codex_executor_stream.go:198`）。

对本服务的含义：COSP 的 `UpstreamFailureClassifier` 已在更上游做了分类（`api/shared/`），
**回程翻译器应假设「交到手上的事件流是可翻译的」**，failed/error 由分类器处理。
但**为保险起见**，翻译器遇到 `failed`/`error` 事件应是**透传而非崩溃**（当前原样透传
路径已如此）。

#### 4.2 各家注释记录的 gotcha（值得抄的）

| 项目 | gotcha | 出处 |
|---|---|---|
| sub2api | `ChatFunctionCall.Name` 带 `omitempty`，保证 arguments delta **不带 `name:""`** —— 否则客户端把已累积的名字**覆盖成空**，工具分发失败 | `types.go:749-758` + 专项测试 |
| CPA | `cache_write_tokens` 必须**避 float 转换**（用 SetRaw 精确字节），否则大整数丢精度 | `codex_openai_response.go:694` + 测试（30 位数） |
| CPA | 非流式收尾 `content` **不得被空串覆盖成 null** —— 末尾空 message 不能打回已有 content | 测试 `:575-593` |
| CPA | `output_item.done` 回退时**只发参数**，避免 id/name 重复 | `:339-341` 注释 |
| cc-switch | **MiniMax 上游乱序实测**（见 §5） | `responses_late_arguments.rs:1-16` |
| new-api | 非流式响应头必须改回 `application/json`（上游 SSE 的 Content-Type 会污染） | `openai_gateway_chat_completions.go:626-631` |

---

### 5. cc-switch 的上游乱序实测记录（MiniMax，2026-10-03）

**这是四家里唯一记录「上游乱序」的实测，价值极高。** `responses_late_arguments.rs` 头部注释：

> MiniMax 的 `/v1/responses` 在长历史下（整段参数一次吐出时）会乱序：先发
> `function_call_arguments.done` 和 `output_item.done`，`arguments` 都是空串，
> 再发**唯一一个**带完整参数的 `function_call_arguments.delta`，最后 `response.completed`
> 里的参数是对的（实测 2026-10-03）。Codex 在 `output_item.done` 就定下调用，拿到空串，
> 工具全部报 `failed to parse function arguments`。
>
> 这里只动响应：**参数为空的那两个结束事件先扣住**，等之后第一个不是参数增量的事件到来时，
> 用累积的增量（或 `response.completed` 里的同 id 条目）补上参数再发。顺序正常的流里结束事件
> 本来就带参数，原样放行；真没有参数的调用，扣住的事件原样补发。**请求一个字节不改。**

**机制**：扣住（hold）参数为空的 `done` 事件 → 等下一个非参数增量事件 → 补齐后再发。

**对本服务的含义**：
- **本服务也需要这个防护**。COSP 的 R2C 若在 `output_item.done` 时就 flush 工具调用，
  遇到 MiniMax 这类乱序上游会拿到空参数
- 但**实现方式可以更简单**：因为 Chat 的 `tool_calls` 是**流式累积**的（客户端自己拼），
  COSP **不必在 done 时 flush** —— 只需保证 arguments 分片按序发出，`done` 只补差量
  （即 §2.4 的 A 派前缀补齐）。**A 派天然对乱序鲁棒**（不依赖 done 的完整性）
- **关键**：`finish_reason` 别提前发。若在 done 时发 finish chunk，后续 delta 就丢了

#### 5.1 new-api 的 pending 机制（另一个乱序处理）

new-api 的 `pendingArgsByOutputIndex` / `pendingArgsByItemID`（`:356-366`）：
delta 到达但工具未注册时先存 pending，工具注册时拼入。**这处理的是「delta 早于 added」
的乱序**，与 cc-switch 的「done 早于 delta」互补。

#### 5.2 CPA 的弱面（不要抄）

CPA 在 `output_item.done` 命中 state 后直接 `state.Done = true`，之后任何
`function_call_arguments.delta` 因 `state.Done` 直接丢弃（`:333`/`:238`）。
**如果 done 之后才到 arguments（cc-switch 记录的 MiniMax 情形），CPA 会丢参数** ——
这是 CPA 的已知弱面（代码注释未声明，从行为可推）。

**结论：不抄 CPA 的 `Done` 硬门**，改用 A 派前缀补齐（不设「关闭」状态）。

---

### 6. 候选方案汇总（四项目做法与倾向；倾向列已随 §9 全部定案）

| # | 决策点 | new-api | sub2api | CPA | 倾向 |
|---|---|---|---|---|---|
| 1 | content 多 item 拼接 | 空行分隔 | 无分隔符 | 只取第一个 | **空行分隔**（new-api） |
| 2 | reasoning 读取 | content 优先，summary 兜底 | summary | summary+content 拼接 | **content 优先，summary 兜底**（实测三家都发 content） |
| 3 | 首个 role chunk | `response.created` 时 | `response.created` 时 | 首个内容事件带 | **created 时**（兜底：若没有 created 则首个内容事件带） |
| 4 | tool index | 本地计数 0.. | 本地计数 0.. | 本地计数 0.. | **本地重编号**（三家共识） |
| 5 | 工具映射键 | 三级 | 一级 | 三级 | **三级**（output_index/item_id/call_id） |
| 6 | arguments 分片 | 前缀补齐 + pending | 前缀补齐 | 一次性补发 | **前缀补齐 + pending**（A 派） |
| 7 | done 后晚到 arguments | pending 部分处理 | 未处理 | 丢弃（弱面） | **不设 Done 门**（天然鲁棒） |
| 8 | finish_reason | completed→stop/tool_calls；incomplete→length/content_filter | 同 | 同 | **三家共识，直接采用** |
| 9 | usage 收尾 chunk | finish 后 usage-only chunk（choices:[]） | 同 | 附在终态 chunk | **独立 usage chunk + 收尾时统一发出**（对齐 C2M 先例，已核对；见 §9-1） |
| 10 | model 字段 | `resp.Model` | 入参 originalModel | `resp.Model` | **上游裸名**（2026-10-04 深度调研修正：原倾向「回显下游带前缀名」出自 M2C 契约 §7，但 M2C 代码实际透传上游名且三线路/直连口径一致；契约矛盾见 [R2C-PLAN.md](#e-r2c-回程--计划与实施记录) §6-1） |
| 11 | id / created | 原样 | id 原样、created 用 now | 原样 | **原样**（信息更真） |
| 12 | annotations | 转换 | 无 | 无 | **丢弃**（最小实现） |
| 13 | logprobs/obfuscation/encrypted_content/sequence_number | 都不处理 | 同 | 同 | **都不做**（三家共识） |
| 14 | 无终态兜底 | Finalize 显式补 chunk | 同 | executor 层 | **需要**（见 §7） |
| 15 | 空 content 不覆盖已有 | 有防护（CPA 测试） | — | 有防护 | **需要**（gotcha，见 §4.2） |

---

### 7. 实现健壮性清单（从三家 gotcha 提炼）

1. **名字只发一次**：`tool_calls[].function.name` 只在首个 chunk 带，后续 arguments delta
   不发 name（否则客户端把名字覆盖成空）—— **sub2api 专项测试钉住的坑**
2. **arguments 前缀补齐**：done 携带完整参数时只发未发过的部分（不重复）
3. **pending 兜底**：delta 早于 added 时先存待工具注册
4. **不设 Done 硬门**：done 后晚到 delta 仍能发（对乱序上游鲁棒）
5. **无终态兜底**：流结束未收 completed → 补 finish chunk（幂等，`finalized` 标志）
6. **空文本不覆盖**：非流式的 content 只在非空时写（否则会把已拼内容打回 null）
7. **usage 只在 `IncludeUsage` 时发**（对齐 Chat 语义；`stream_options.include_usage`）
8. **`response.failed`/`error` 透传而非崩溃**（分类器在更上游处理）

---

### 8. 与既有契约的衔接点

| 契约位置 | 衔接内容 |
|---|---|
| 响应侧契约（M2C）§12 | 翻译器套在上游服务**外侧**、`retryWhen` 之外 —— R2C 同位置 |
| 响应侧契约 §7 | `TranslationContext` 出口（wasStream / includeUsage）—— R2C 消费它决定 usage chunk |
| 响应侧契约 §16 | C2R 响应事件序列的**三种形态实测**（纯文本 / reasoning+文本 / +工具调用）—— R2C 的输入依据 |
| 请求侧契约 §3.5 | 工具配对修复 —— R2C 的**输出侧**需保证 tool_calls 配对正确（与请求侧对称） |
| `ResponseProtocolTranslator` 接口 | 三个方法：`translateResponse`（非流式）、`translateStream`（流式）、`translateChunksForLog`（落库批量重译，与流式共用同一状态机） |
| `M2CStreamState` | 同形参照：跨事件状态机，`payload` 组合、首 chunk/role/finish chunk 的形态 |

---

### 9. 已决定的事项（2026-10-04 定案）
> **决策已归档**：[ADR-0005 R2C 回程翻译的六项核心决策](../../adr/adr-0005-r2c-response-translation.md)。
> 下表保留实现口径与实测依据，ADR 记录曾有过分歧的决策与备选方案。


§6 的「倾向」列自本日起即为实现口径（含 content 空行分隔、reasoning 读取 content 优先
summary 兜底、工具三级映射键、前缀补齐、finish_reason 推导、model 回显下游名、
id/created 原样等）。以下记录曾有实质分歧的决策与理由。

| # | 决策点 | 结论 | 理由 |
|---|---|---|---|
| 1 | usage 收尾 chunk | **对齐 C2M 规范**：独立 usage chunk（`choices:[]`）、收尾时统一发出、**finish 在前 usage 在后**、仅 `IncludeUsage && hasUsage()` 时发 | 本服务「向上游发什么请求就保留该请求回传的 usage 形态」的既有规范；C2M 的 `usageFrame` Javadoc 记录了踩坑历史（跟着 finish 发会出两个 usage chunk = 双倍计费记录，且以抢跑值覆盖后续累积）。**落库层无需改动**：`usage_raw` 原样存档 + `ResponsesUsageParser` 已随 C2R 去程就位 |
| 2 | `IncludeUsage` 的传入路径 | 取 `TranslationContext.includeUsage`（C2R 去程记下的下游原值）；仅**流式**消费 | 该 record 的字段设计就是为「响应侧需要知道请求侧事实」——`includeUsage` 必须在 `stream_options` 被丢弃前记下 |
| 3 | `response.created` 缺失的兜底 | **混合方案**：created 到时发 role 帧；同时在首个内容事件（text/reasoning/tool delta）检查「role 未发则带上」 | created 时发语义清晰（new-api/sub2api）；补发检查借 CPA 的「不依赖 created」鲁棒性。两行成本防住一类不发 created 的上游 |
| 4 | `output_item.added` 缺失的兜底 | **pending 存留（new-api 派）**：delta 早于工具注册时先存 `pendingArgsByOutputIndex`/`pendingArgsByItemID`，注册时拼入 | 与「前缀补齐」同源，两者合起来即 new-api 的完整乱序方案；极简上游（直接发 delta 不发 added）也能收齐参数 |
| 5 | reasoning 的 `\n\n` 分隔 | **new-api 的「标记 + 差额」方案**：段结束事件（`reasoning_text.done` 等）只设 `needsReasoningBreak` 标记；下个 delta 到达时看其开头换行数，只补足到 2 个（本身以 `\n\n` 开头则不补） | done 时立即发固定 `\n\n`（CPA）会在「下个 delta 自带换行」时变成四个换行；标记 + 差额是唯一不产生多余空行的做法。sub2api 完全不加分隔会让多段思考糊在一起 |
| 6 | `output[].phase` / `encrypted_content` | **丢弃 + 日志留痕** | Chat 协议无承载位置（`reasoning_content` 是单字符串字段装不下密文；`phase` 无对应概念）。对齐 C2M 的 `redacted_thinking` 处置先例：吸收但不产帧，记 debug/warn 让「内容凭空变少」在日志里有痕迹 |

---

### 10. 与本服务实测的交叉验证（2026-10-04 三家联测）

PLAN §7.3 的三家供应商联测已在**透传**模式下拿到响应事件流，正好验证本文的事实基础：

| 实测观察（mimo / stepfun / deepseek） | 对 R2C 的含义 |
|---|---|
| 三家都发完整的 `added → delta ×N → done → item.done` 序列 | A 派前缀补齐可行；无乱序（但 MiniMax 需防护） |
| `function_call_arguments.done` 的 arguments 与 delta 累加一致 | done 只补差量（或空） |
| item `id` 形态三家不同（UUID / 16 位 hex / `fc_` 前缀） | **不能依赖 id 形态**，只用作映射键 |
| `call_id` 三家都带（`call_` / `call_00_` 前缀） | **配对键可靠**，用作 tool_calls.id |
| 参数分片粒度不同（stepfun 逐字符 / deepseek 单 delta 全量） | 分片处理必须与粒度无关（前缀补齐天然满足） |
| 顶层 `output_text`（便利字段）可能为空（stepfun） | **不要依赖它**，从 `output[].content[]` 取 |
| `reasoning_text` 明文下发（三家都如此） | R2C 的 reasoning 走 `content[].reasoning_text` |
| 事件字段集不同（deepseek 最全，含 `truncation` 等） | **不要依赖字段存在性**，缺失即跳过 |

---

## E. R2C 回程 · 计划与实施记录


> **状态**：阶段一、二、三全部完成（2026-10-04：三家供应商实机验证通过 + §7.4 双重修复闭环——C2R 去程 reasoning item 与控制器 DTO 字段，落库证据齐全）。C2R 线路端到端可用
>
> **需求背景**：把上游 Responses 响应（非流式 JSON / SSE 事件流）翻译回下游 Chat
> Completions 形态，让 C2R 线路（下游打 `/v1/chat/completions`、上游只支持 Responses）
> 端到端可用。C2R 去程已实测可用（[PLAN.md](#c-c2r-去程--计划与实施记录) §7.3），当前响应是原样透传的
> 半轮中间态（`AfterSend.warnIfHalfRound` 每次调用都在告警）。
>
> 相关：[R2C-RESEARCH.md](#d-r2c-回程--调研)（四项目对比 + §9 决策定案，本文决策的事实基础）、
> [C2R-RESEARCH.md](#b-c2r-去程--调研)（去程调研）、
> [../chat-messages/RESPONSE-CONTRACT.md](../../reference/contracts/C2M-RESPONSE-CONTRACT.md)
> （M2C 响应侧契约——**本方向最重要的同形先例**，§16 含 C2R 实测事件序列）。

---

### 0. 决策与理由
> **决策已归档**：[ADR-0005 R2C 回程翻译的六项核心决策](../../adr/adr-0005-r2c-response-translation.md)。
> 本节是 R2C 计划侧的决策与理由，与调研侧定案互为补充。


R2C-RESEARCH §9 已定案六项（usage 收尾对齐 C2M、IncludeUsage 取 TranslationContext、
created 混合兜底、added 缺失 pending 存留、reasoning 分隔用 new-api「标记+差额」、
phase/encrypted 丢弃留痕），§6 倾向列即实现口径。本节只记录**深度探索代码后新增**的
决策点与事实约束。

#### 0.1 架构事实（探索核实，直接决定实现形状）

| # | 事实 | 出处 | 对 R2C 的含义 |
|---|---|---|---|
| 1 | **上游终态事件以 `Terminal` 形态到达翻译器**。执行器用 `UpstreamEventClassifier.classify(…, RESPONSES, data)` 分类，7 种终态（`ResponsesStreamEvents.TERMINAL_OUTCOMES`）都归 Terminal，`data()` 携带原文 | `ResponsesStreamEvents` + `AfterSend` | 翻译器把 `map(UpstreamEvent::data)` 后的事件原文交给状态机，switch 按事件 type 分派——**Terminal 不是特殊输入**，与 Body 同一条路（M2C 同形：`message_stop` 也是这样进来） |
| 2 | **usage 是双通道**：`UpstreamEvent.usage()` 槽由执行器填（`ResponsesUsageParser`，记账用），翻译器「只传递不重算」（M2C 的 `carriedUsage` 模式）；出站给下游看的 OpenAI 形态 usage chunk 由状态机自算 | `MessagesToChatResponseTranslator.translateStream` 的注释 | R2C 照抄该模式：`doOnNext` 收集槽位值 → 末帧 `withUsage(carried)`；出站 usage 从终态事件原文提取 |
| 3 | **Responses 的 usage 只在终态事件一次给全**（不需要 Anthropic 那种跨事件合并；但仍兼容中途携带的上游——取最后一份非 null） | `ResponsesUsageParser` Javadoc + `GenericResponsesChatService:352-402` | 状态机持有一个「最后一份 usage 原文」，finalize 时转 OpenAI 形态 |
| 4 | **输出帧必须再过 classify**：`UpstreamEventClassifier.classify(objectMapper, CHAT, frame)` —— `[DONE]` 由此被分类为 Terminal，控制器据此收尾 | `MessagesToChatResponseTranslator.translateStream` 末行 | R2C 输出协议是 CHAT，复用同一行 |
| 5 | **`OpenAiResponseShapes` 的形状助手全套可复用**：`roleDelta` / `contentDelta` / `reasoningDelta` / `toolCallStartDelta` / `toolCallArgumentsDelta` / `finishDelta` / `chunk` / `DONE_SENTINEL` | `OpenAiResponseShapes` | R2C 零新形状——M2C 已把 Chat 侧形状收敛完 |
| 6 | **落库改写器已接线**：`AfterSend.chunkRewriterFor` 在回程命中时自动构造（调 `translateChunksForLog`），半轮态传 null 落上游原文 | `AfterSend` | R2C 落地后落库自动切换为「下游实际收到的 chunk + 逐事件产帧数」，零接线改动 |
| 7 | **空响应判定（`ResponsesContentDetector`）在翻译内侧**，读上游原生形态 | 响应侧契约 §12 | R2C 落地不影响判定与重试——它们先于翻译发生 |

#### 0.2 新增决策点（R2C-RESEARCH §9 未覆盖）

| # | 决策点 | 结论 | 理由 |
|---|---|---|---|
| 8 | **上游终态事件的吸收**：`completed` / `done` / `incomplete` 到达时**不透传原文**，转出一个 finish chunk；流结束时 `finalizeStream` 统一补 usage chunk + `[DONE]` | `[DONE]` 不绑任何事件、由流结束触发——M2C 类注释的既定原则（上游可能发完终态才关连接，也可能不发终态就断开）。终态原文对 Chat 客户端是无意义噪声 |
| 9 | **`response.failed` / `error` 事件的处置**：不产内容帧、记 warn 日志、按「无实质内容收尾」处理（finish_reason=`stop`）；**不转 onError 信号** | 这些事件到达翻译器时，空响应门已按原生形态判定过（`output` 为空 → 已卷入重试），能走到这里是重试耗尽放行的最终轮。转 onError 会在「SSE 头已发出」后触发二次分类，且 M2C 先例（`error` 事件只记录，处置在调用方）不这么做。Chat 协议没有失败事件概念，给下游一个结构完整的流 + 日志留痕是最不坏的选项。**与 `ResponsesStreamEvents.outcomeOf` 的 FAILURE 相位不冲突**：那是 ResponsesController（直连）的生命周期判定，翻译线路的生命周期由 Chat 侧出口管理 |
| 10 | **终态事件 output[] 的补发**（new-api `terminalOutputChunks`）：`completed`/`incomplete` 携带完整 `output[]`，若其中有**流内从未以 delta 发出**的内容（message 文本 / reasoning / function_call），补发对应 chunk | 「只发终态、不发 delta」的上游真实存在——`ResponsesContentDetector` 的 Javadoc 明确记录这类上游（终态事件的 `output[]` 是其唯一内容来源）。不补发的症状：下游收到空回复但 usage 照计费（sub2api 的 `SupplementResponseOutput` 注释钉住的坑）。**这同时是非流式翻译器的取数路径**——两者共用「从 output[] 提取」的代码 |
| 11 | **`output_item.added` 对 message / reasoning 不产帧**（只有 function_call 产） | M2C 同判：Chat 没有块生命周期概念，空 delta 帧是噪声（其 Javadoc 点名 new-api 的 fall-through 噪声帧是反面案例） |
| 12 | **非流式 content 空时给 `""` 而非 null** | M2C 非流式同口径（`content` 始终 put）；CPA 测试钉过「null 覆盖已有内容」的坑 |

---

### 1. 范围与验收

- **交付后 C2R 线路端到端可用**：Chat 客户端（Copilot）打 `/v1/chat/completions` →
  C2R 去程 → Responses 上游 → **R2C 回程** → 收到标准 Chat chunk 流 / JSON。
- 验收信号（实机）：
  1. `AfterSend.warnIfHalfRound` 的半轮告警**消失**（回程命中即不再触发）
  2. Chat 客户端能解析响应（不再看到 `object:"response"` 原文）
  3. 落库 chunk 列显示**下游实际收到的 Chat chunk**（不再只有上游事件栏）
  4. `downstream_protocol=CHAT`、`upstream_protocol=RESPONSES` 的调用恢复正常渲染

---

### 2. 组件与接线

#### 2.1 新增组件（`pipeline/protocol/translate/`，三件套对齐 M2C）

| 组件 | 可见性 | 职责 |
|---|---|---|
| `ResponsesToChatResponseTranslator` | `@Component`，声明 `(CHAT, RESPONSES)` | 门面：三个接口方法 + `Flux.defer` 内建状态 + `carriedUsage` + 输出 classify。**加 `@Component` 即自动进 `TranslatorRegistry` 回程表——`AfterSend` / `BeforeSend` / 调度器全部零改动**，半轮透传路径自动切换 |
| `ResponsesToChatNonStreamTranslator` | 包私有 | Responses 完整 JSON → Chat JSON（§0.1-10：从 `output[]` 提取，与流式终态补发共用提取代码） |
| `ResponsesToChatStreamTranslator` | 包私有 | 逐事件翻译（`translateEvent(data, state) → List<String>`）+ `finalizeStream(state)`；switch 按 18 种事件 type 分派（见 §3 事件表） |
| `R2CStreamState` | 包私有 | 跨事件状态（见 §2.2） |

**复用清单（零新建）**：`OpenAiResponseShapes`（全部形状）、`UpstreamEventClassifier`
（输出分类）、`TranslationContext`（includeUsage）、`ResponsesStreamEvents`（终态判定
——翻译器自己也需要判终态，复用这份清单而不是再写一份，理由同其 Javadoc「两个消费者」）。

#### 2.2 `R2CStreamState` 字段设计（对照 M2CStreamState + R2C-RESEARCH §2.2/§2.3）

| 字段 | 来源 | 说明 |
|---|---|---|
| `id` / `model` / `created` | M2C 同形 | created 时 adopt 上游 `response.id`；**model 用上游真实名**（占位用 `upstreamModel` 入参）——注意：这与 R2C-RESEARCH §6-10「回显下游带前缀名」**不冲突**，那里说的是非流式响应体的 `model` 字段（入参传下游名）；流式每帧的 model 与 M2C 同口径用上游名。**两处口径需在实现时统一核对**（见 §6-3） |
| `sentRole` | §9-3 混合方案 | created 时发 role 帧；首个内容事件检查未发则带上 |
| `nextToolIndex` + 三级映射（`outputIndexToKey` / `itemIDToKey` / `callIDToKey`） | §9-4 + 三家共识 | 本地重编号；`toolByKey` 持工具记录（CallID/Name/Index/Arguments/ArgsSentAt/NameSent） |
| `pendingArgsByOutputIndex` / `pendingArgsByItemID` | §9-4 | delta 早于 added 的存留，注册时拼入 |
| `needsReasoningBreak` | §9-5 | 「标记 + 差额」：done 设标记，下个 delta 按开头换行数补足到 2 |
| `sawToolCall` / `sawContent` / `finalized` | M2C 同形 | 三档收尾判定 + 幂等 |
| `lastUsageRaw` | §0.1-3 | 最后一份非 null 的 usage 原文（JsonNode），finalize 转 OpenAI 形态 |
| `includeUsage` | TranslationContext | 仅流式消费 |

#### 2.3 事件 → 动作总表（流式状态机的完整规格）

| 上游事件 | 动作 | 产帧数 |
|---|---|---|
| `response.created` | adopt id/model；发 role 帧（§9-3） | 1 |
| `response.in_progress` | 忽略 | 0 |
| `response.output_item.added`（function_call/custom_tool_call） | 注册工具（三级键 + pending 拼入）；发 tool start 帧（index/id/name，arguments=""） | 1 |
| `response.output_item.added`（message/reasoning/其它） | 忽略（§0.2-11） | 0 |
| `response.output_text.delta` | 发 content delta（前缀：role 未发则带上） | 1 |
| `response.reasoning_text.delta` / `reasoning_summary_text.delta` | 发 reasoning_content delta（前缀：needsReasoningBreak 差额补足 + role 检查） | 1 |
| `response.reasoning_text.done` / `reasoning_summary_text.done` | 设 needsReasoningBreak 标记（不发固定 `\n\n`） | 0 |
| `response.output_text.done` / `content_part.*` / `reasoning_part.*` | 忽略（内容已在 delta；块生命周期无意义） | 0 |
| `response.function_call_arguments.delta` / `custom_tool_call_input.delta` | 查工具（三级）→ 命中：累加 + 发差量帧；未命中：存 pending | 0/1 |
| `response.function_call_arguments.done` / `custom_tool_call_input.done` | 前缀补齐：对比已发长度，只发未发过的差量（不发 name） | 0/1 |
| `response.output_item.done`（function_call） | 若工具已发过参数则不发；否则用 done 携带的完整 arguments 补发 | 0/1 |
| `response.output_item.done`（其它类型） | 忽略 | 0 |
| `response.completed` / `response.done` | **吸收**：提取 usage → lastUsageRaw；终态 output[] 补发（§0.2-10）；发 finish chunk（§0.2-8） | 1..N |
| `response.incomplete` | 同上 + `incomplete_details.reason` 映射（`max_output_tokens`→`length`、`content_filter`→`content_filter`、其它→`stop`） | 1..N |
| `response.failed` / `error` | **不产内容帧**，warn 日志（§0.2-9） | 0 |
| `response.cancelled` / `canceled` | 同 failed 处置 | 0 |
| （流结束，concatWith finalizeStream） | 未发 finish 则按三档补（已发/有内容→`length`/无内容→`stop`）；usage chunk（includeUsage && hasUsage）；`[DONE]` | 0..3 |
| 未知事件 / 解析失败 | debug 日志跳过（M2C 同判：单事件失败不中断流） | 0 |

---

### 3. 明确不做的事

| 不做 | 理由 |
|---|---|
| `logprobs` / `obfuscation` / `encrypted_content` / `sequence_number` | 三家共识 + R2C-RESEARCH §2.6；Chat 无承载位置 |
| `annotations`（url_citation）转换 | §9 已定丢弃（最小实现）；new-api 独有，Copilot 不消费 |
| 思考缓存 | C2R 输入侧不需要（C2R-RESEARCH §10.1）；「R2C + 上游签发密文」场景在本方向是**输出侧**丢弃密文（§9-6），不构成缓存需求 |
| `sub2api` 的「非流式强制 SSE + 缓冲累加器」路径 | COSP 支持真非流式（`GenericResponsesChatService.invoke`），不需要 |
| `namespace` / `web_search_call` 工具输出的翻译 | Chat 无对应物（与 C2R 去程「不合成」对称）；`web_search_call` 吞掉并 debug 留痕（sub2api 同判） |
| 落库 / 调度 / 出口的任何改动 | 全部零改动（§0.1-6 接线已就位） |

---

### 4. 阶段划分与提交边界

三阶段各自独立提交、独立验证。**先非流式后流式**（M2C 先例：非流式无状态机，
先把字段映射、usage 换算、终态提取三件事钉死，流式复用其结论）。

#### 阶段一：非流式翻译器 + 门面

**改动**：新增 `ResponsesToChatNonStreamTranslator` + `ResponsesToChatResponseTranslator`
（门面先实现 `translateResponse`，另两个方法可先返回空实现/抛 Unsupported——
**注意**：门面注册进 Registry 后回程即「已命中」，流式也会走它，所以空实现必须是
「透传原文」而非抛异常，或者**本阶段先不注册**（阶段二一并注册）。
→ **取后者：阶段一不带 `@Component` 或注释掉，阶段二补上**——避免流式路径拿到一个
只会透传的假回程）。

**验证**：`ResponsesToChatNonStreamTranslatorTests` 全绿（用 `samples/` 三份非流式样本
+ deepseek 实测形态做 fixture）+ 全量绿。

**单测组织**（参照 `MessagesToChatNonStreamTranslator` 的测试）：
1. 纯文本 output → content
2. reasoning item（content 优先 / summary 兜底 / 两者皆有）
3. function_call → tool_calls（call_id 优先、name、arguments、多工具顺序）
4. 多 message item 空行分隔（`appendSeparatedText` 语义——**该助手若 M2C 未沉淀为
   共享工具，在本阶段提为 `OpenAiResponseShapes` 或独立助手**，流式终态补发复用）
5. status/incomplete_details → finish_reason 全分支
6. usage 换算（含 `output_tokens_details.reasoning_tokens` → `completion_tokens_details`）
7. model 回显下游名 / id 原样 / created 用 `created_at`
8. 空文本给 `""` 非 null（§0.2-12）

#### 阶段二：流式状态机 + 注册

**改动**：新增 `R2CStreamState` + `ResponsesToChatStreamTranslator`；门面补
`translateStream` / `translateChunksForLog` + `@Component` 注册。

**验证**：
1. `ResponsesToChatStreamTranslatorTests`：§2.3 事件表的每一行至少一条用例；
   重点组——
   - **三种形态回放**（`samples/` 的 stepfun-midway 形态 C / astra 纯文本 /
     stepfun-toolcalls 形态 B 的事件序列做 fixture，断言完整 chunk 序列）
   - 前缀补齐（delta 累加后 done 只发差量；done 先到 delta 后到的 MiniMax 乱序重放）
   - pending（delta 早于 added）
   - 三级键（item_id 缺失时 output_index 兜底等）
   - 终态补发（只发 completed 不发 delta 的上游 → output[] 内容补发）
   - failed/error 不产帧 + 三档收尾
   - reasoning 分隔的「标记+差额」（done 后 delta 自带 `\n\n` 开头时不重复补）
   - role 混合兜底（无 created 的事件流）
   - `[DONE]` 由流结束触发（无终态事件直接断流 → finalizeStream 补）
   - usage chunk 仅 includeUsage 时发、finish 在前 usage 在后
2. `translateChunksForLog` 与流式共用状态机（独立状态重放，M2C 注释的幂等要求）
3. 全量绿（**重点盯**：既有 `ChatDispatchErrorSignalTests` / 集成测试若断言
   「C2R 回程未实现告警」需同步更新——探索未见直接断言，落地后跑全量确认）

#### 阶段三：实机验证（需重启服务，用户手动）

**判据**（§1 验收信号）+ 三家供应商复测（mimo-tokenplan / stepfun / deepseek 各跑
非流式 + 流式 + 工具调用 + 多轮）：Chat 形态正确解析、reasoning_content 渲染、
工具调用可执行、落库 chunk 列为 Chat chunk、半轮告警消失。

---

### 5. 挑战与风险（深度调研结论）

| # | 挑战 | 风险等级 | 缓解 |
|---|---|---|---|
| 1 | **上游形态多样性**：id 形态（UUID/16 位 hex/`fc_` 前缀）、分片粒度（逐字符/单 delta 全量）、字段集（deepseek 最全）三家三样 | 高 | 已有原则：只依赖事件骨架 + `call_id` 配对语义（R2C-RESEARCH §10 对表）；绝不依赖字段存在性 |
| 2 | **乱序上游**（MiniMax 实测：done 先于 delta 且参数为空） | 高 | 前缀补齐 + pending 双机制（§9-4/§5）；用该场景做定向单测 |
| 3 | **model 字段的两处口径（已定案，§6-1）**：契约 §7「回显下游带前缀名」与代码「透传上游裸名」矛盾 | 低 | **保持现状（上游裸名）**：实测三家下游 agent 均无问题；改写需在回程前加主干且波及三线路+直连。契约勘误注记随阶段一顺手加 |
| 4 | **只发终态不发 delta 的上游**：流内零 delta，内容全在 `completed.output[]` | 中 | 终态补发（§0.2-10）+ 与非流式共用提取代码；`ResponsesContentDetector` 已确认这类上游存在 |
| 5 | **`response.failed` 的双重语义**：空响应门可能已把它重试掉；耗尽放行的最终轮它以 Terminal 到达翻译器 | 中 | §0.2-9 的既定处置（不产帧 + warn）；实机验证时用 mock 的 `fail-*` 场景确认不炸 |
| 6 | **帧数对等的落库重译**：`translateChunksForLog` 必须与出站共用同一状态机逻辑，否则日志帧数与实际下发不符 | 中 | M2C 模式：独立状态 + 同一 `translateEvent`/`finalizeStream` 重放；有既定测试模式可抄 |
| 7 | **门面注册的时序**：阶段一若提前注册，流式会拿到只会透传的假回程（比半轮告警更糟——静默） | 低 | 阶段一不注册（§4 已定）；阶段二连同流式一起上 |
| 8 | **与既有集成测试的交互**：`ChatCompletionHalfRoundTranslationTests` 这类「半轮态」命名的测试可能断言透传行为 | 低 | 阶段二全量跑时逐一核对该类测试；半轮语义对 `(CHAT, RESPONSES)` 消失、对其它未实现方向保留 |

---

### 6. 遗留与待确认（2026-10-04 全部定案）

1. **model 口径已定案：保持现状（上游裸名透传）**。决策依据：
   - 实测上 Copilot / Claude CLI / Codex 三家下游 agent 对裸名**均无问题**（多轮
     会话长期运行验证），契约 §7 担心的「下轮路由失败」未实际发生；
   - 若要改写，需在回程翻译器分支**之前**加一段主干专门改写下发 model——
     结构成本高且波及 M2C / 直连（三线路 + 直连必须同口径）；
   - 契约 §7 与代码的矛盾**不在本分支处理**，仅实施时在该节加一行勘误注记
     指向本条（矛盾详情见 §5-3）。
2. **`custom_tool_call` 已定案：按 function 翻译**——`id` 用 `call_id`、`name`
   照搬、`arguments` 取其 `input` 字段（字符串）。下游看到的是普通工具调用，
   可直接执行。流式事件 `custom_tool_call_input.delta/done` 与
   `function_call_arguments.delta/done` 同一处理路径（§2.3 已并入同两行）。
3. **`name:""` 覆盖坑已调研：COSP 天然免疫，无需防护**。sub2api 的坑源于
   **单一共享结构体**序列化所有 delta（Go 对空字符串默认输出 `"name":""`，
   客户端按 index 累积时覆盖首帧的真名字，其修复是 `omitempty`）。COSP 的
   `OpenAiResponseShapes` 用**两个独立构造方法**：`toolCallArgumentsDelta`
   构造的 function map 只有 `arguments` 键——`name` 键在路径上不存在
   （`LinkedHashMap` 只序列化 put 进去的键），两条构造路径物理隔离。

---

### 7. 实施记录（各阶段完成后追加）

#### 7.1 阶段一：非流式翻译器（2026-10-04 完成）

**落地内容**：
- `ResponsesToChatNonStreamTranslator`（`pipeline/protocol/translate/`，约 380 行含 Javadoc）
- `ResponsesToChatResponseTranslator` 门面：`translateResponse` 已实现（usage 槽位
  只传不重算）；**按计划未注册 `@Component`**——流式/落库方法抛
  `UnsupportedOperationException`（不可达：Registry 查不到本类），类注释写明
  阶段二补注解与实现
- `ResponsesToChatNonStreamTranslatorTests`：29 条，8 组 `@Nested`
  （message→content 6 / reasoning 4 / tool_calls 5 / finish_reason 5 / usage 3 /
  头部字段 2 / 丢弃与异常 3，外加分隔不叠加 1）
- 契约勘误：RESPONSE-CONTRACT §7 头部加勘误注记（指向本文 §6-1）
- 验证：新测试 29/29 绿；全量 `./mvnw surefire:test` **1419 条全绿**（未注册故
  对既有链路零影响——与预判一致）

**实现中的口径落点**（与 §0/§6 决策一一对应）：
- content 多 item/part 空行分隔 + `appendSeparated`（只补足到 2 个换行，不叠加）
- reasoning content 优先 summary 兜底（两者不拼接——拼接会把同一段思考发两遍）
- `custom_tool_call` 按 function 翻译、arguments 取 `input`（§6-2）
- usage **prompt=input 直接映射**（不加缓存——与 Anthropic 侧三项相加相反，
  Javadoc 里特意标注「绝不能照抄」）；reasoning_tokens 归位
  `completion_tokens_details`；details 只给非零份
- id 优先 `call_id` 空回落 item `id`；arguments 缺失补 `"{}"`；空 content 给 `""`
- 头部 id/model/created_at 全部透传（§6-1 定案）

**计划外发现**：无——本阶段所有坑（null 覆盖、分隔叠加、reasoning 重复）都已在
调研阶段预判并落进测试。

#### 7.2 阶段二：流式状态机 + 注册（2026-10-04 完成）

**落地内容**：
- `R2CStreamState`（约 300 行）：工具三级映射（output_index/item_id/call_id 共指一条
  记录）+ 本地重编号 + `argsSentAt` 前缀补齐位 + pending 存留 + reasoning「标记+差额」
  分隔 + 三档收尾标志
- `ResponsesToChatStreamTranslator`（约 420 行）：§2.3 事件表全量实现——18 行规格
  逐行落实（终态吸收、output[] 补发、failed/error 不产帧、usage 收尾统一发）
- `ResponsesToChatUsageConverter`：usage 换算从非流式私有方法提为共享类（流式收尾
  与非流式必须同口径——「一份实现、两个消费点」）
- 门面补 `translateStream`（M2C 同构：defer 内建状态 + carriedUsage + 输出 classify）
  与 `translateChunksForLog`（独立状态重放）；**补上 `@Component`——C2R 回程位即命中，
  半轮告警路径失效**
- `ResponsesToChatStreamTranslatorTests`：27 条，7 组 `@Nested`（骨架 4 / 内容增量 4 /
  工具 7 / 终态补发 3 / 失败终态 3 / usage 2 / 真实形态回放 2 / 落库 1，外加合计）
- 验证：新测试 27/27 绿；全量 `./mvnw surefire:test` **1446 条全绿**——注册后
  `TranslatorRegistry` 自动收集、既有集成测试（含 `ChatCompletionHalfRoundTranslationTests`）
  无一受影响，与「零接线改动」预判一致

**测试 fixture 的三类真实形态**：samples 形态 C 的 14 事件骨架（stepfun 逐字符分片）、
deepseek 官方（单 delta 全量参数）、MiniMax 乱序（done 先到参数空、完整参数在随后 delta）。

**计划外发现（实现期 bug，单测抓出）**：
- `registerTool` 初版只把工具记录存进**主键**（`call:`），而 `keyByItemId` 次级索引
  指向的 `item:` 键在 `toolsByKey` 里不存在——delta 事件（只带 `item_id`）三级回落
  查不到记录、参数进 pending 永远拼不上。修复：注册时写入**所有可用键**（三键共指
  同一记录）。**这正是三级映射设计的本意，初版实现漏了半边**——`nameOnlyOnFirstFrame`
  与 `doneSupplementsMissingRemainder` 两个用例把它打出来
- Java 文本块里嵌 JSON 字符串的引号转义是 `\\"`（文本块的 `\"` 产出裸引号 →
  JSON 非法 → 解析出空 delta）：probe 式二分（一次性探针测试打印真实帧）比盯
  行号快得多——异常行号指向**旧编译**的 class 曾误导排查方向

#### 7.3 阶段三：实机验证（2026-10-04 完成）

**三家供应商各三项（非流式全套 / 流式工具调用 / 多轮回传）全部通过**——
响应已是标准 `chat.completion` / `chat.completion.chunk`：

- **mimo-tokenplan / stepfun**：非流式 `reasoning_content` 渲染、`finish_reason`
  正确、usage 换算正确（`reasoning_tokens` 归位 `completion_tokens_details`、
  cached 归位 `prompt_tokens_details`）；流式 chunk 序列
  `role → reasoning ×N → tool[0] name → args → finish=tool_calls → [DONE]`、
  stepfun 逐字符参数分片正确透传、name 只发一次；多轮工具结果正确消化
- **deepseek 官方**：三项通过（首轮触发 §7.4 的硬约束③，修复后复测闭环），
  reasoning item 落库证据见 §7.4 复测定案
- **Copilot 真实使用**（连续工具调用会话）：正常工作——含 reasoning 回传的
  完整 BYOK 路径
- **§7.5 的 chunk 双份落库**亦在本阶段实机观测确认（77 上游事件 / 67 下游
  chunk / frameCounts 对齐）

**过程中发现的三个真问题**（§7.4 两个 + §7.5 一个）全部修复并复测闭环——
实机验证的价值直接兑现：三个都在「单测全绿」的盲区里。

#### 7.4 计划外：deepseek 硬约束③实测触发与修复（2026-10-04）

**现象**：deepseek 多轮回传报 400：
`The reasoning_text in the thinking mode must be passed back to the API`。

**根因**：C2R 去程的 `translateAssistant` 把下游回传的 `reasoning_content`
<strong>丢弃</strong>（阶段一依据「Chat 客户端不回放思考」的过时判断），而
deepseek 官方（思考模式对话式上游）要求历史 assistant 消息必须携带思考——
这正是 [REQUEST-CONTRACT §4.8](../../reference/contracts/C2M-REQUEST-CONTRACT.md)
<strong>硬约束③</strong>（sub2api 记录过同一错误原文），因供应商而异
（mimo/stepfun 不校验，deepseek 官方校验），不能假定任何一种。

**修复**：`translateAssistant` 把 `reasoning_content` 翻译为<strong>明文 reasoning
item</strong>（`content[].reasoning_text` + `encrypted_content: null`，契约 §4.8
记录的「上游不签发密文」填充方式），置于正文 item 之前（与上游产出顺序一致）。
与 R2C 响应侧构成<strong>往返闭环</strong>：R2C 给下游 `reasoning_content` →
下游回传 → C2R 还原 reasoning item——思考两侧不断链，且<strong>零缓存</strong>
（透传明文；§4.8 的「缓存明文」路线仅在「上游签发密文 + 下游只有密文」时需要，
本线路下游手上本来就有明文）。

**测试**：C2R 翻译器 46 条全绿（新增 reasoning item 翻译 / 空白省略 / 往返闭环
断言）；全量 <strong>1448 条全绿</strong>。

**复测与二次排障（同日，找到真正的根因）**：重启复测时 deepseek 第二轮返回 200，
但落库的上游 input 里<strong>没有 reasoning item</strong>——修复代码在实机上未生效。
逐层排除（规则组不命中 RESPONSES / 装配浅拷贝与清 null 都不删 item / 磁盘 class
是新版且服务启动晚于编译）后定位<strong>真凶</strong>：`OpenAiController` 用强类型
`OpenAiChatRequest` 反序列化再重建 Map，而 `Message` DTO <strong>没有
`reasoningContent` 字段</strong>——Copilot BYOK 回传的思考在<strong>控制器入口
被 Jackson 静默剥掉</strong>，翻译器从未收到过该字段。单测直调翻译器覆盖不到这一层
（R2C 实机排障暴露的测试盲区）。此前「400 → 200」的差异并非修复生效，而是复测构造
的场景恰好未触发 deepseek 的校验（400 的原始触发来自 Copilot 真实使用路径）。

**二次修复**：`OpenAiChatRequest.Message` 增加
`@JsonProperty("reasoning_content") private String reasoningContent`（含字段级
Javadoc 记录这次的坑）；`buildRequestBody` 无需改动（`objectMapper.convertValue`
序列化 DTO 时自动带上）。

**防漂移测试**：`OpenAiControllerTests.reasoningContentSurvivesControllerDeserialization`
——用 ArgumentCaptor 捕获 service 层收到的 Map，断言字段穿透「DTO 反序列化 +
Map 重建」两层。<strong>控制器层的贯通测试</strong>从此有先例可抄。

**最终验证**：C2R 翻译器 46 + 控制器 7（+1 新增）全绿；全量 <strong>1449 条全绿</strong>。
待再次重启后复测 deepseek 多轮——这次落库 input 应出现 reasoning item。

**复测定案（同日，重启后）**：deepseek 两轮全 200；<strong>落库 input 出现
reasoning item</strong>（`message/developer → user → reasoning → function_call →
function_call_output`，`content[].reasoning_text` 明文、位置在 function_call 之前、
`encrypted_content` 为 null 形态正确），上游正确消化并在回答中带出时区细节。
§7.4 的修复链至此<strong>全部闭环</strong>：DTO 字段 → 翻译器 reasoning item →
上游接受 → 落库证据齐全。

#### 7.5 计划外：流式 chunk 双份落库接线缺失（2026-10-04，用户发现）

**现象**：C2R 流式调用的落库 chunks 列只记了上游 Responses 事件（裸数组），
没有「下游实际收到的 Chat chunk」那份——跨协议时的<strong>双份留痕</strong>
（`ChunkLogPayload` 的「一列两形」设计：直连落裸数组、翻译落
`{translated, upstream, frameCounts}` 对象）在 R2C 线路未生效。

**根因**：`GenericResponsesChatService` 的三处落库
（成功收尾 / 每轮失败 / 错误响应头）把 `chunkRewriter` 参数<strong>硬编码成了
`null`</strong>——`invokeStream` 收到 `AfterSend.chunkRewriterFor` 构造的改写器
（C2R 回程注册后非 null），但 `responsesStream` 没往下传。Anthropic 侧
（M2C）同位置传的是参数本身，这是 Responses 执行器在「R2x 未实现」年代写下的
占位值，回程落地时漏接。

**修复**：`responsesStream` 签名补 `chunkRewriter` 参数，三处落库的 `null` 换成
参数；两个测试子类的直调 helper 同步补 `null`（直调无翻译，裸数组口径）。

**生效机制**（既有设计，零新逻辑）：`AfterSend.chunkRewriterFor` 在回程命中时
构造改写器 → 调 `translateChunksForLog`（§7.2 已实现）→ `ChunkLogPayload.from`
包装成双份对象 → 前端日志页双栏对照。改写失败时 `from` 自动退回上游原文
（裸数组），日志永不因翻译 bug 丢失。

**验证**：受影响四测试类 97 条全绿；全量 <strong>1449 条全绿</strong>。
增量编译坑复现一次（testCompile 报 "Nothing to compile" 而签名已变——
删 `target/test-classes` 强制重编译后真实错误才现形，与 java-tests
instructions 记录一致）。

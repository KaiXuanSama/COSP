# C2R 请求翻译调研（四个参考项目对比）

> **状态**：调研完成，**尚未实现**。本文只固定调研事实与候选方案，不含已落地的约束。
> 落地时应把确定的部分并入
> [request-contract.md](../chat-messages/request-contract.md)（请求侧契约）
> 或另立 C2R 章节。
>
> 调研于 2026-09-29。四个项目均在工作区内（`new-api/` / `sub2api/` / `cc-switch/` / `CLIProxyAPI/`，
> 都是独立参考项目，只读）。
>
> **§0–§9 是读源码得出的，§10 是真实抓包。** 后者修正了前九节的三处判断，
> 并修正了[请求侧契约](../chat-messages/request-contract.md) §4.8 的一处前提。

C2R = 下游 OpenAI Chat Completions → 上游 OpenAI Responses。
请求侧翻译；响应侧（Responses 事件流 → Chat chunk）是另一半，本文不覆盖。

---

## 0. 四个项目各自覆盖什么

| 项目 | C2R 请求侧 | 说明 |
|---|---|---|
| `new-api` | ✅ 完整 | `relaykit/relayconvert/internal/oai_chat/to_oai_responses_req.go` |
| `sub2api` | ✅ 完整 | `backend/internal/pkg/apicompat/chatcompletions_to_responses.go` |
| `cc-switch` | ❌ **没有** | 只做 Anthropic↔Responses（`transform_responses.rs`）与**反方向** R2C（`transform_codex_chat.rs`）。`/chat/completions` 入口（`handlers.rs:762`）只透传 |
| `CLIProxyAPI`（CPA） | ✅ 完整 | `internal/translator/codex/openai/chat-completions/codex_openai_request.go` |

### CPA 的方向命名容易误读

CPA 的注册表是 `Register(from, to, ...)`（`translator.go:24`），`Codex` 与 `OpenaiResponse` 是**两个不同标识符**（`constant/constant.go:14,23`）—— 但 Codex 用的就是 Responses 协议。所以：

```go
Register(OpenAI, Codex, ConvertOpenAIRequestToCodex, ...)                  // ← 这才是 C2R
Register(OpenaiResponse, OpenAI, ConvertOpenAIResponsesRequestToOpenAIChatCompletions, ...)  // R2C
```

`ConvertOpenAIRequestToCodex` 的文档注释直说：「Converts an OpenAI Chat Completions request JSON
into an OpenAI Responses API request JSON」（`codex_openai_request.go:16-18`）。
**它是四个项目里唯一 C2R 与 R2C 都实现的**，且是唯一为「Codex 号池」场景特化的。

### cc-switch 的 C2R 缺失是事实，不是遗漏

它的 Chat→Responses 只存在于**响应侧**（`streaming_codex_chat.rs:811`
`create_responses_sse_stream_from_chat_with_context`）。请求侧无对应实现。因此 C2R **只有三个参照**。

---

## 1. 字段映射对比

处置列：**映射** / **丢弃** / **报错** / **未找到**。

### 1.1 顶层标量

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

### 1.2 `tool_choice` 形态 —— 两个独立实现都做展平，sub2api 是缺陷

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

### 1.3 `reasoning_effort` → `reasoning.effort`：三种做法

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

### 1.4 `max_output_tokens` 下限：三个值，都有成文理由

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

### 1.5 `temperature` / `top_p` 是否剥离

| 项目 | 做法 |
|---|---|
| new-api | 无条件透传（`:398`/`:402`） |
| sub2api | 按 `isReasoningModel(model)` 剥离（`:42-47`）—— 推理模型不接受采样参数 |
| CPA | **不映射**（注释掉），理由是 Codex 不支持 |

**对本服务的含义**：sub2api 与 CPA 都按**模型名/上游特性**决定是否发采样参数。这与 AGENTS.md
明确反对的「按模型名前缀硬编码能力表并逐级降级」（new-api 那个反面案例）是同一类。
我们的原则是**原样发出，由上游用错误码回答**。

### 1.6 `response_format` → `text.format`：两家实现相反

**new-api**（`to_oai_responses_req.go:41-77`）：外层字段优先，把内层 `json_schema` 合并进外层并删除嵌套键。

**sub2api**（`response_format.go:5-34`）：只取内层 `json_schema` 内容、**丢掉外层其余字段**；非 `json_schema` 类型原样返回。

**对本服务的含义**：若两者都要兼容需同时处理两种输入形态。

### 1.7 tool `strict` 缺省值 —— CPA 独有的因果说明

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

## 2. `store` / `include` 的强制注入

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

## 3. `system` / `developer` 落点 —— 第三种做法

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

## 4. 内容块与工具配对

### 4.1 内容块白名单

| Chat 块 | new-api | sub2api | CPA |
|---|---|---|---|
| `text` | `input_text` / `output_text`（按 role） | 一律 `input_text` | `input_text` / `output_text`（按 role） |
| `image_url` | `input_image`（解包成纯 url 字符串，**丢 `detail`**） | `input_image`（丢 `detail`，跳过空 base64） | `input_image`（仅 `role == "user"`） |
| `file` | `input_file` 用 `"file"` 键（非官方） | `input_file` 用 `filename`/`file_data`/`file_id`（官方） | `input_file` 用 `file_data`/`filename`（官方） |
| `input_audio` | 支持 | 无分支 | 支持（仅 user） |
| `input_video` / `video_url` | `input_video`（阿里百炼扩展） | 无分支 | 未找到 |

**`image_url.detail` 三家都丢弃** —— 这是共识。

### 4.2 工具调用配对修复 —— CPA 最完整

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

### 4.3 assistant 消息

| 项目 | 空 content | `reasoning_content` | `tool_calls` |
|---|---|---|---|
| new-api | 仍发 `{role:"assistant", content:""}` | 无处理 | 展开为 `function_call` item（过滤 `type != "" && != "function"`） |
| sub2api | **不发 message**，只发 function_call | 包成 `<thinking>…</thinking>` 塞进 `output_text` | 展开（不过滤，`arguments` 空补 `"{}"`） |
| CPA | 未找到明确分支 | 未找到 | 展开（配 `pendingToolCalls`） |

---

## 5. 硬失败（报错）的情况

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

## 6. 思考回放缓存 —— CPA 独有，且是契约 §4.8 未决问题的唯一完整答案

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

### 对本服务的含义

契约 §4.8 的三条实测硬约束（`done` 才完整、密文绑定签发者、部分上游强索明文）在这里依然成立。
**但 C2R 请求侧不需要这个缓存** —— 已由真实抓包坐实（§10.1）：不签发密文的上游下，
Codex 回传的 `input` 里就是**明文 `reasoning_text`**，`encrypted_content` 为 `null`。
C2R 的输入又是 Chat 形态的 `messages`，下游手上有明文 `reasoning_content`，不需要代理代劳。

真正需要缓存的仍是 R2C（输入是 Responses 的加密思考），且**仅在「上游确实签发了密文」时**。

---

## 7. 候选方案汇总（不含结论）

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

### 三条「不应照抄」的候选

CPA 是给 **Codex 号池**场景特化的，以下三条与 AGENTS.md 原则冲突：

1. **`reasoning.effort` 缺省补 `"medium"`** —— 属「替客户端做决定」；我们的设置层兜底档管这件事
2. **`temperature` / `top_p` 不映射** —— 按上游能力剥离，同「按模型名硬编码能力表」一类
3. **`max_output_tokens` 不映射** —— 同上，且我们已有 `MaxOutputTokensSetting`

---

## 8. 与既有契约的衔接点

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

## 9. 尚未决定的事项

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

## 10. 真实抓包（2026-09-29）

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

### 10.1 `encrypted_content` 在真实链路上的实际情形

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

### 10.2 `instructions` 与 `input.developer` 并存，承载不同内容

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

### 10.3 工具是三种形态，不止 `function`

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

### 10.4 `id` 字段的有无：一半样本不造

| | `function_call.id` | `call_id` 前缀 | `function_call_output.id` | `message.id`（assistant） |
|---|---|---|---|---|
| astra | **9/9 带** `fc_…` | `call_…` | 9/9 带 `fco_…` | **9/9 带** `msg_…` |
| stepfun | **0/6 带** | `call_…` | 6/6 带 `fco_…` | **0/5 带** |

同一批工具、同一客户端，`function_call.id` 与 `message.id` 一个有一个没有。
**唯一恒定的是 `function_call_output.id`（两份都带 `fco_`）**。

含义：`id` 在 Responses 里是**可选**的。C2R 合成时**不应主动造**
`function_call.id` / `message.id`（两个真实样本有一个不造，说明上游不要求）。

### 10.5 响应事件序列：两种形态

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

### 10.6 Codex 特有字段（Chat 里无对应物）

| 字段 | 出现位置 | 取值 |
|---|---|---|
| `phase` | 请求 input 的 assistant 消息 / 响应 item | 请求侧 `"commentary"`（9/9）；响应侧 `"final_answer"` |
| `client_metadata` | 请求顶层 | 7 个键，含 `x-codex-turn-metadata`（嵌套 JSON 串）等 |
| `prompt_cache_key` | 请求顶层 | 与 `thread_id` 同值的 UUID |
| `internal_chat_message_metadata_passthrough` | 响应 item | 上游私有，含 `create_time` / `turn_id` |

`phase` 是值得注意的：它区分「中间旁白」与「最终答复」，**Chat 协议里没有这个概念**。
C2R 若丢弃它，下游就失去了这个区分（Copilot / Codex 回放时可能行为不同）。

### 10.7 两份请求的公共骨架（C2R 的目标形态）

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

### 10.8 本轮修正的旧判断（摘要）

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

### 10.9 响应事件序列的三种形态（详见响应侧契约第 16 节）

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

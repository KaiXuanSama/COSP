# R2C 回程翻译调研（四个参考项目对比）

> **状态**：调研与决策完成（2026-10-04）：§6 倾向列即实现口径，§9 记录曾有分歧的决策。
> 实施时应把确定的部分并入
> [RESPONSE-CONTRACT.md](../chat-messages/RESPONSE-CONTRACT.md)（响应侧契约）
> 或另立 R2C 章节。
>
> 调研于 2026-10-04。四个项目均在工作区内（`references/new-api` / `references/sub2api` /
> `references/cc-switch` / `references/CLIProxyAPI`，都是独立参考项目，只读）。
>
> 上游事件形态的依据是本地实测（[samples/](./samples/) 七份抓包 + 2026-10-04
> 三家真实供应商联测记录，见 [PLAN.md](./PLAN.md) §7.3）。

R2C = 上游 OpenAI Responses 响应（非流式 JSON / SSE 事件流）→ 下游 OpenAI Chat
Completions 响应（`choices[0].message` / SSE chunk 流）。

**这是 C2R 的另一半**：C2R 去程只让请求能发出去，回程把 Responses 原生响应转回 Chat，
下游 Chat 客户端才能解析。**C2R 去程已实测可用（PLAN §7.3），响应目前是原样透传的中间态。**

---

## 0. 四个项目各自覆盖什么

**四个项目里，三家实现了我们的 R2C 方向**（new-api / sub2api / CPA），第四家 cc-switch 做的是
镜像方向（附带的乱序实测有独立价值）：

| 项目 | 非流式 | 流式 | 关键文件 | 备注 |
|---|---|---|---|---|
| **new-api**（Go） | ✅ | ✅ | `relaykit/relayconvert/internal/oai_responses/to_oai_chat_resp.go` + `to_oai_chat_stream_resp.go` | 实现最完整（含 annotation、pending 工具参数、缓冲累加器） |
| **sub2api**（Go） | ✅ | ✅ | `backend/internal/pkg/apicompat/responses_to_chatcompletions.go` | 「非流式但上游强制 SSE」有专门累加器 |
| **CPA**（Go） | ✅ | ✅ | `internal/translator/codex/openai/chat-completions/codex_openai_response.go` | 注册在 `(OpenAI, Codex)` 方向；「done 后晚到 arguments」不处理 |
| **cc-switch**（Rust） | ❌ 方向相反 | ❌ 方向相反 | 见下 | ⚠ 它的镜像方向 + 乱序实测有借鉴价值 |

### 方向定位的三个坑

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

### 本服务（COSP）的现状

- `TranslatorRegistry` 的 `(CHAT, RESPONSES)` 回程位**空着**，故当前响应原样透传（留痕）
- `ResponseProtocolTranslator` 接口就位（三个方法：`translateResponse` / `translateStream` /
  `translateChunksForLog`，签名见接口 Javadoc）
- 参照物：`MessagesToChatResponseTranslator` + `MessagesToChatStreamTranslator`
  （M2C 方向的同形实现，`M2CStreamState` 状态机）

---

## 1. 非流式转换（`response.output[]` → `choices[0].message`）

### 1.1 字段映射总表

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

### 1.2 content 拼装：三家的分隔策略不同

| 项目 | 多 message item | 多个 `output_text` part |
|---|---|---|
| new-api | 空行分隔（`appendSeparatedText`，`to_oai_chat_resp.go:314`）——智能处理首尾换行，避免重复 | 直接拼接 |
| sub2api | **无分隔符**直接首尾相接 | 直接拼接 |
| CPA | 只取**第一个** `output_text`（`codex_openai_response.go:506`，`break`） | 只取第一片 |

**new-api 的 `appendSeparatedText` 设计精巧**（`:314-333`）：检查 builder 尾部的换行数 +
新文本头部的换行数，只补足到 2 个（不重复添加）。这是「多 segment 拼接」的成熟做法。

**对本服务的含义**：C2M 的响应侧契约已有类似决策（M2C 的 `text` 块拼接）。倾向
**拼接 + 空行分隔**（对齐 new-api）；CPA 的「只取第一片」在单 message 多 part 时会丢内容。

### 1.3 reasoning 的读取路径

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

### 1.4 tool_calls 构造

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

### 1.5 id / model / created 的取舍

**分歧点**：
- `id`：new-api/CPA 原样透传 `resp_`；sub2api 也透传，仅空时生成
- `model`：sub2api 用**入参 originalModel**（而非 `resp.Model`）—— 理由是上游可能改写模型名，
  下游应看到它请求的模型名
- `created`：sub2api 忽略 `created_at` 用当前时间；CPA 用 `created_at`

**C2M 的既有先例**（响应侧契约）：模型名必须回显**下游带前缀名**（`[provider] model`）。
R2C 应沿用同一口径 —— **model 用入参的下游模型名**（与 sub2api 一致）。
`id` 与 `created` 倾向原样透传（信息更真实）。

### 1.6 annotations（`url_citation`）—— new-api 独有

new-api 把 Responses 的 `output_text.annotation.added` / `content[].annotations` 转成
Chat 的 `message.annotations`（`to_oai_chat_resp.go:137-181`），且转换 `url_citation` 形态：
`{type:"url_citation", ...}` → `{type:"url_citation", url_citation:{...}}`。

**对本服务的含义**：Copilot/Chat 客户端对 `annotations` 的消费存疑（非官方字段）。
倾向**丢弃**（保持最小实现），记录为「不做」。

---

## 2. 流式状态机（核心）

### 2.1 三家共同的事件骨架

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

### 2.2 状态对象

三家的状态字段高度一致：

| 字段类别 | new-api | sub2api | CPA |
|---|---|---|---|
| 元数据 | `ID/Model/Created/IncludeUsage/Usage` | `SentRole/SawToolCall/SawText/Finalized` | `ResponseID/CreatedAt/Model/ServiceTier` |
| 工具索引 | `nextToolIndex` + 三级映射（outputIndex/itemID/callID → key） | `NextToolCallIndex` + `OutputIndexToToolIndex` | `FunctionCallIndex` + 三级查找 |
| 参数缓冲 | `pendingArgsByOutputIndex`/`pendingArgsByItemID`（**处理乱序**） | `OutputIndexToArguments` | 无（不处理乱序） |
| 已发标记 | `sentStart/finalized/sawToolCall/hasSentText/hasSentReasoning` | `SentRole/SawToolCall/SawText/Finalized` | `ArgumentsEmitted/Done` per tool |

**new-api 的 pendingArgs 机制最完整**（见 §2.4）。

### 2.3 tool_calls 的 index 映射 —— 三家都是「本地重编号」

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

### 2.4 arguments 分片处理 —— 两派做法

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

### 2.5 终止处理与兜底

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

### 2.6 三家都「不处理」的事

| 项 | 说明 |
|---|---|
| `logprobs` | 三家都不读不产出（Chat 侧的 `delta.logprobs` 空置） |
| `obfuscation` | 全仓无实现 |
| `encrypted_content` | 响应侧完全忽略（不提取、不缓存、不回放） |
| `sequence_number` | 丢弃（Chat 协议无此字段） |
| `output_item.done` 后**晚到**的 arguments | new-api/sub2api 靠 pending 部分处理；CPA 明确丢弃 |

**结论：这五项都不做**（除 late arguments 外——那由 §2.4 的 pending 机制覆盖）。

---

## 3. 非流式但上游强制 SSE 的路径（sub2api 独有）

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

## 4. 工程细节（调用链位置与错误处理）

### 4.1 翻译器在链条中的位置（三家一致）

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

### 4.2 各家注释记录的 gotcha（值得抄的）

| 项目 | gotcha | 出处 |
|---|---|---|
| sub2api | `ChatFunctionCall.Name` 带 `omitempty`，保证 arguments delta **不带 `name:""`** —— 否则客户端把已累积的名字**覆盖成空**，工具分发失败 | `types.go:749-758` + 专项测试 |
| CPA | `cache_write_tokens` 必须**避 float 转换**（用 SetRaw 精确字节），否则大整数丢精度 | `codex_openai_response.go:694` + 测试（30 位数） |
| CPA | 非流式收尾 `content` **不得被空串覆盖成 null** —— 末尾空 message 不能打回已有 content | 测试 `:575-593` |
| CPA | `output_item.done` 回退时**只发参数**，避免 id/name 重复 | `:339-341` 注释 |
| cc-switch | **MiniMax 上游乱序实测**（见 §5） | `responses_late_arguments.rs:1-16` |
| new-api | 非流式响应头必须改回 `application/json`（上游 SSE 的 Content-Type 会污染） | `openai_gateway_chat_completions.go:626-631` |

---

## 5. cc-switch 的上游乱序实测记录（MiniMax，2026-10-03）

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

### 5.1 new-api 的 pending 机制（另一个乱序处理）

new-api 的 `pendingArgsByOutputIndex` / `pendingArgsByItemID`（`:356-366`）：
delta 到达但工具未注册时先存 pending，工具注册时拼入。**这处理的是「delta 早于 added」
的乱序**，与 cc-switch 的「done 早于 delta」互补。

### 5.2 CPA 的弱面（不要抄）

CPA 在 `output_item.done` 命中 state 后直接 `state.Done = true`，之后任何
`function_call_arguments.delta` 因 `state.Done` 直接丢弃（`:333`/`:238`）。
**如果 done 之后才到 arguments（cc-switch 记录的 MiniMax 情形），CPA 会丢参数** ——
这是 CPA 的已知弱面（代码注释未声明，从行为可推）。

**结论：不抄 CPA 的 `Done` 硬门**，改用 A 派前缀补齐（不设「关闭」状态）。

---

## 6. 候选方案汇总（四项目做法与倾向；倾向列已随 §9 全部定案）

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
| 10 | model 字段 | `resp.Model` | 入参 originalModel | `resp.Model` | **入参下游模型名**（C2M 先例：回显带前缀名） |
| 11 | id / created | 原样 | id 原样、created 用 now | 原样 | **原样**（信息更真） |
| 12 | annotations | 转换 | 无 | 无 | **丢弃**（最小实现） |
| 13 | logprobs/obfuscation/encrypted_content/sequence_number | 都不处理 | 同 | 同 | **都不做**（三家共识） |
| 14 | 无终态兜底 | Finalize 显式补 chunk | 同 | executor 层 | **需要**（见 §7） |
| 15 | 空 content 不覆盖已有 | 有防护（CPA 测试） | — | 有防护 | **需要**（gotcha，见 §4.2） |

---

## 7. 实现健壮性清单（从三家 gotcha 提炼）

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

## 8. 与既有契约的衔接点

| 契约位置 | 衔接内容 |
|---|---|
| 响应侧契约（M2C）§12 | 翻译器套在上游服务**外侧**、`retryWhen` 之外 —— R2C 同位置 |
| 响应侧契约 §7 | `TranslationContext` 出口（wasStream / includeUsage）—— R2C 消费它决定 usage chunk |
| 响应侧契约 §16 | C2R 响应事件序列的**三种形态实测**（纯文本 / reasoning+文本 / +工具调用）—— R2C 的输入依据 |
| 请求侧契约 §3.5 | 工具配对修复 —— R2C 的**输出侧**需保证 tool_calls 配对正确（与请求侧对称） |
| `ResponseProtocolTranslator` 接口 | 三个方法：`translateResponse`（非流式）、`translateStream`（流式）、`translateChunksForLog`（落库批量重译，与流式共用同一状态机） |
| `M2CStreamState` | 同形参照：跨事件状态机，`payload` 组合、首 chunk/role/finish chunk 的形态 |

---

## 9. 已决定的事项（2026-10-04 定案）

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

## 10. 与本服务实测的交叉验证（2026-10-04 三家联测）

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

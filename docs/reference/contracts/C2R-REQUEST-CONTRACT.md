# 协议翻译契约（请求侧 · C2R 链路）

> **方向**：本契约覆盖 **C2R 链路（下游 /v1/chat/completions · 上游 Responses）** 的
> **请求去程**：下游 OpenAI Chat Completions 请求 → 上游 Responses 请求。
>
> **易混警告**：`C2M-REQUEST-CONTRACT.md` / `C2M-RESPONSE-CONTRACT.md` 覆盖 C2M 链路，
> `M2C-REQUEST-CONTRACT.md` / `M2C-RESPONSE-CONTRACT.md` 覆盖 M2C 链路。本文件只为 C2R 链路，
> 不要与其他链互相抄写，也不要续写本链响应（响应在 `C2R-RESPONSE-CONTRACT.md`）。
>
> 状态：**C2R 去程已实现并经三家真实上游实机验证，本链响应已实现**。本文件是请求字段
> 规格与测试矩阵来源；「必须 / 不要」是约束，标注实测日期的段落才是已验证事实。

**决策链**：[ADR-0004：C2R 去程翻译的六项决策](../../adr/adr-0004-c2r-request-translation.md)（已归档，本文为字段级细节）。
**相关**：[协议翻译四方向 RFC](../../working/rfcs/2026-09-03-protocol-translation.md)（C2R 调研/计划/实施记录）、
[响应侧契约](./C2R-RESPONSE-CONTRACT.md)、[M2C 请求契约](./M2C-REQUEST-CONTRACT.md)。

---

## 1. 方向与实现位置

翻译器 `ChatToResponsesRequestTranslator`（`@Component`，声明 `(CHAT, RESPONSES)`）套在
上游执行器外侧、`retryWhen` 之外；`TranslatorRegistry` / `ProtocolDispatchManager` 零改动即自动接通。

## 2. 已定案的六项核心决策（ADR-0004）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | system 落点 | 留在 `input`，role 改写为 `developer`（`ResponsesSystemPromptStage` 支线，传输前由 bodyProtocol 查表命中）；`instructions` 不写 |
| 2 | store / include | 强制注入：`store:false`、`include:["reasoning.encrypted_content"]`（无状态代理 + 为回程铺路） |
| 3 | max_output_tokens | 映射、不 clamp、不补默认：`max_completion_tokens` 优先于 `max_tokens`，值原样搬运 |
| 4 | 工具处理 | 忠实搬运：只翻译下游 Chat 实际传入的工具，不捏造、不凭空去除 |
| 5 | id 造不造 | 不造：`tool_calls[].id` → `function_call.call_id`；`tool` 消息 `tool_call_id` → `function_call_output.call_id` |
| 6 | effort / summary | `reasoning_effort` → `reasoning.effort`（改名后删原字段）；`reasoning.summary` 不写 |

**三条「不应照抄 CPA」同时生效**：不补默认 effort、不剥 `temperature`/`top_p`、不跳过 `max_output_tokens` 映射。

## 3. 字段映射总表

### 3.1 顶层标量

| Chat（下游） | Responses（上游） | 处置 |
|---|---|---|
| `model` | `model` | 原样 |
| `stream` | `stream` | 原样（`TranslationContext.wasStream` 记原始值） |
| `stream_options.include_usage` | —（丢弃） | 丢弃前记入 `TranslationContext.includeUsage`（Responses 的 SSE 自带 usage） |
| `max_completion_tokens` / `max_tokens` | `max_output_tokens` | 前者优先；只搬运不 clamp 不补默认 |
| `temperature` / `top_p` | 同名 | 原样搬运（不按模型剥离） |
| `reasoning_effort` | `reasoning.effort` | 改名后删原字段 |
| `tools` | `tools` | 嵌套→扁平，见 §3.3 |
| `tool_choice` | `tool_choice` | 展平，见 §3.3 |
| `parallel_tool_calls` | `parallel_tool_calls` | 原样 |
| `response_format` | `text.format` | 映射（外层 `type` + 内层 `json_schema` 合并；非 `json_schema` 类型原样放 `type`） |
| —（无） | `store` | 强制 `false` |
| —（无） | `include` | 强制 `["reasoning.encrypted_content"]` |
| —（无） | `instructions` | 不写 |
| `n` > 1 | — | 报错（`RequestTranslationException`） |
| `messages` | `input` | 见 §3.2 |

**静默丢弃清单**（Responses 无对应物）：`frequency_penalty`、`presence_penalty`、`logit_bias`、
`logprobs`、`top_logprobs`、`seed`、`user`、`stop`、`service_tier`、`store`（Chat 侧同名但语义不同）、
`function_call` / `functions`（legacy）。

> `stop`：Responses 无 `stop` 参数，静默丢弃。
> legacy `function_call` 与 `tool_choice` 并存时报错；仅 legacy `function_call` 单独出现时报错。

### 3.2 `messages[]` → `input[]`（按 role 分派）

| Chat 消息 | Responses input item | 说明 |
|---|---|---|
| `role:"system"` | `{type:"message", role:"system", content:[…]}` | role 不动——改写 `developer` 是支线职责，位置与顺序保留 |
| `role:"user"` | `{type:"message", role:"user", content:[…]}` | 内容块映射见 §3.2.1 |
| `role:"assistant"`（纯文本） | `{type:"message", role:"assistant", content:[{type:"output_text",…}]}` | 不造 `id` |
| `role:"assistant"`（带 `tool_calls`） | 每个工具调用一个 `{type:"function_call", call_id, name, arguments}` item | `tool_calls[].id` → `call_id`；不造 `id`；同条消息有文本则照常产 message item |
| `role:"assistant"`（带 `reasoning_content`） | 文本照常；`reasoning_content` 丢弃 | Chat 明文思考无 Responses 请求侧对应物 |
| `role:"tool"` | `{type:"function_call_output", call_id, output:…}` | `tool_call_id` → `call_id`；配对修复：找不到前置 `function_call.call_id` 匹配时丢弃该条并留痕，不凭空造 |

#### 3.2.1 内容块白名单

| Chat 块 | Responses 块 | 说明 |
|---|---|---|
| `{type:"text", text}` | `{type:"input_text"/"output_text", text}` | user/system → `input_text`，assistant → `output_text` |
| `{type:"image_url", image_url:{url}}` | `{type:"input_image", image_url}` | 解包成纯 url 字符串；丢 `detail`；仅 user 消息 |
| `{type:"input_audio", …}` | `{type:"input_audio", …}` | 仅 user 消息 |
| `{type:"file", file:{…}}` | `{type:"input_file", …}` | `filename`/`file_data` 官方键 |
| `content` 纯字符串 | 包成单元素 `input_text` 数组 | — |

不认识的块类型：报错（`RequestTranslationException`），不静默丢弃。

### 3.3 工具与 tool_choice

```jsonc
// Chat（嵌套）                          →  Responses（扁平）
{"type":"function",                         {"type":"function",
 "function":{"name":"exec_command",          "name":"exec_command",
   "parameters":{…},                          "parameters":{…},
   "strict":false}}                           "strict":false}
```

- `strict`：下游带了原样搬；缺省时显式写 `false`（两侧默认值相反，省略等于悄悄改语义）。
- `tool_choice` 展平：`{"type":"function","function":{"name":X}}` → `{"type":"function","name":X}`；
  `"auto"`/`"none"`/`"required"` 字符串形态原样。
- 不合成 `namespace` / `web_search`（那是 Responses 原生客户端形态，Chat 请求不会出现）。

## 4. 错误边界与不变量

- 白名单制：没被显式搬运的顶层字段一律丢弃；链条末尾只有 removeIf(isNull)，非 null 多余字段会原样发给上游然后 400。
- `RequestTranslationException` 带源字段路径；本地错误走既有 defer 信号：非流式 400，流式 HTTP200 + event:error。
- 不改变输入 Map 深层内容；同一输入产生确定性结果。
- 不补默认 effort、不剥采样参数、不跳过 max_output_tokens 映射。
- 主干顺序、鉴权、路由、重试与空响应预算不在此方向重定义。

## 5. 实现与验证状态

- 实现：`ChatToResponsesRequestTranslator` + `ResponsesSystemPromptStage` 支线。
- 自动化：单测覆盖顶层标量、消息映射、内容块、工具/tool_choice、硬失败与静默丢弃清单。
- 实机：三家供应商验证过（C2R 去程 + R2C 回程端到端，记录在 RFC 实施记录）。

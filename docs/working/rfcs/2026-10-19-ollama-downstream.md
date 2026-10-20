# RFC：Ollama 下游协议（O 轴）

- **日期**：2026-10-19（立项日）
- **状态**：**已定稿，实施待开始**（决策已晋升 [ADR-0011](../../adr/adr-0011-ollama-downstream.md)）
- **相关 ADR**：ADR-0011（Ollama 下游协议：只做下游、不做 X2O）
- **背景调研**：[OLLAMA-EXTENSION-REVIEW.md](../../reference/OLLAMA-EXTENSION-REVIEW.md)

> 官方 Ollama VS Code 扩展聊天走原生 `/api/chat`，COSP 只实现了发现端点
> （`/api/version`、`/api/tags`、`/api/show`），因此模型能列出但一发消息就 404。
> 本 RFC 规划 6 个 O 轴翻译方向与 `/api/chat` 端点，分三阶段实施。
> **O 轴天然不对称**：不存在 X2O 去程。

## 正文


> **状态**：**规划中，未开工**（2026-10-19 写成）。本文是需求与决策的留存，不是实施记录。
> 开工时本文转为 `PLAN.md` 并追加实施记录节；上线后另写 `SUMMARY.md`。
>
> **需求背景**：Ollama 官方已发布自己的 VS Code 扩展
> （[`ollama/ollama-vscode`](https://github.com/ollama/ollama-vscode)），它通过
> `vscode.lm.registerLanguageModelChatProvider` 注册 provider，聊天走 **Ollama 原生
> `/api/chat`**。COSP 目前只实现了 Ollama 的**模型发现**端点
> （`/api/version`、`/api/tags`、`/api/show`），**没有 `/api/chat`**。
> 因此把该扩展指向 COSP 时模型能列出来，但一发消息就 404。
>
> 相关：[protocol-translation/README.md](2026-09-03-protocol-translation.md#a-方向总览)（现有三协议方向矩阵）、
> [OLLAMA-EXTENSION-REVIEW.md](../../reference/OLLAMA-EXTENSION-REVIEW.md)（官方扩展的支持程度调研）、
> [REQUEST_PIPELINE.md](../../architecture/REQUEST_PIPELINE.md)（接入点与顺序）

---

## 1. 为什么要做，以及为什么可以缓做

### 现状缺口

COSP 当前的 Ollama 支持是**单向的**：

| 能力 | 端点 | 状态 |
|---|---|---|
| 模型发现 | `/api/version`、`/api/tags`、`/api/show` | ✅ 已实现（伪装成 Ollama 服务器） |
| 聊天 | **`/api/chat`** | ❌ **未实现** |

设计初衷是「让 Copilot 的内置 Ollama 集成发现模型，然后聊天走 OpenAI 兼容端点」——
那套用法成立的前提是**客户端愿意在发现之后换协议**。官方新扩展不换协议：
它用 `ollama` npm 客户端，发现和聊天都走 `/api/*`。

### 为什么不是紧急项

1. **内置 Ollama 集成已被官方标记 Deprecated**，活着的替代品是官方扩展。
   但 deprecated ≠ 立刻移除，且 COSP 的 `/v1/models` 已实现——把 COSP
   当作**自定义 OpenAI 兼容端点**配置可完整替代发现+聊天全链路，
   损失的只是「占 11434 端口零配置自动发现」的便利。
2. **O 轴天然不对称**：没有任何正经供应商会提供 Ollama 形态的**上游**接口
   （Ollama 服务端自己就提供 OpenAI 兼容的 `/v1/chat/completions`）。
   因此**不存在 X2O 去程**（把别的协议翻译成 Ollama 请求发给上游）——
   O 只可能是下游。这条约束把工作量从 12 个翻译器砍到 6 个。

### 做了能拿到什么

- 官方 Ollama 扩展可直接指向 COSP，**住进 VSCode 侧正在积极维护的那条管线**
  （`vscode.lm` provider 路径，与 BYOK 桥同族），deprecation 与否与 COSP 无关
- 多一个真实的下游协议接入点，验证「主干-支干」框架的第四个方向维度

---

## 2. 术语与方向命名

先钉住命名，避免与现有约定打架。

现有约定（见 [protocol-translation/README.md](2026-09-03-protocol-translation.md#a-方向总览) §命名约定）：
方向缩写 `C`=Chat Completions、`M`=Messages、`R`=Responses，
`X2Y` 读作「**下游 X 翻译到上游 Y**」。

新增：**`O` = Ollama 原生 `/api/chat`**。

由此得到 6 个方向，全部是 O 作下游：

| 方向 | 含义 | 备注 |
|---|---|---|
| **O2C** | 下游 Ollama → 上游 Chat | **近恒等**——Ollama 消息形态本就是 Chat 家族基因 |
| **C2O** | 上游 Chat → 下游 Ollama（回程） | 主要工作在**出口**（NDJSON 而非 SSE） |
| **O2M** | 下游 Ollama → 上游 Messages | 可参考现有 C2M |
| **M2O** | 上游 Messages → 下游 Ollama（回程） | 可参考现有 M2C |
| **O2R** | 下游 Ollama → 上游 Responses | 可参考现有 C2R |
| **R2O** | 上游 Responses → 下游 Ollama（回程） | 可参考现有 R2C |

> **「去程/回程」的既有语义**：去程 = 下游→上游（请求体翻译），
> 回程 = 上游→下游（响应翻译）。见 `TranslatorRegistry` 的两张表。
>
> **注意别被方向名误导**：`C2O` 是"回程"（上游 C 的响应翻译成下游 O 的响应），
> 但方向缩写写的是"从 C 到 O"——与 `C2M`（下游 C 去上游 M）的读法不同。
> 这是因为回程方向的缩写沿用「上游在下游前」的写法。实施时以
> `downstreamProtocol()` / `upstreamProtocol()` 两个声明方法为准，别靠缩写猜。

---

## 3. 分阶段规划

### 阶段一：O2C / C2O 一对 + `/api/chat` 端点（建议第一刀）

覆盖绝大多数场景——OpenAI 兼容上游是 COSP 的主力。

**改动清单**：

| # | 位置 | 改动 |
|---|---|---|
| 1 | `pipeline/protocol/WireProtocol` | 新增 `OLLAMA` 枚举值（全项目扇出最高，58 处引用会自然感知） |
| 2 | `pipeline/protocol/translate/` | 新增 `OllamaToChatRequestTranslator`（`@Component`，声明 `(OLLAMA, CHAT)`） |
| 3 | 同上 | 新增 `ChatToOllamaResponseTranslator`（`@Component`，声明 `(OLLAMA, CHAT)`） |
| 4 | `pipeline/entry/` | 新增 `OllamaChatService`（与现有三个 entry 同形：defer 包裹 → `ctx.forEndpoint(OLLAMA, …)` → `RequestPipeline.execute(ctx)`） |
| 5 | `api/ollama/OllamaApiController` | 新增 `POST /api/chat` |
| 6 | `infrastructure/web/GatewayAuthFilter` | ⚠️ **把 `/api/chat` 加进 `PROTECTED_PATHS`**（漏加在未开鉴权时完全无症状，见 AGENTS.md） |
| 7 | `pipeline/before/requestbody/` | 请求体支线：`think` 字段 → thinking 步骤；`options.max_tokens` → max_tokens 步骤 |
| 8 | `pipeline/after/send/` | 可能需要新的执行器或复用现有（取决于上游协议） |

**出口层的特殊处理**：Ollama 流式是 **NDJSON**（换行分隔 JSON，每行一个对象，
末行 `{"done":true}`），不是 SSE。这是三协议之外第一个非 SSE 下游——
出口按下游协议分岔正是 `api/` 轴的职责，但需要确认现有响应渲染路径
（`ResponsesController` / `OpenAiController` 的流式写法）能否承载。

### 阶段二：O2M / M2O

Anthropic 上游。消息域转换可大量参考 C2M/M2C 的现成实现。

### 阶段三：O2R / R2O

Responses 上游。可参考 C2R/R2C，注意 reasoning item 的往返闭环。

> **可以只做阶段一就停**：「去程/回程独立缺省」的设计意味着未实现的
> `(OLLAMA, MESSAGES)` / `(OLLAMA, RESPONSES)` 方向会自动落到
> 「原样透传 + 告警」中间态，不会阻塞阶段一上线。

---

## 4. 形态差异：Ollama 与 Chat 的实质区别

虽然 O2C 号称"近恒等"，但仍有多处细节需要显式处理。

### 4.1 请求侧

| Ollama | Chat | 处理 |
|---|---|---|
| `messages[].role` | 同（`system`/`user`/`assistant`/`tool`） | 直通 |
| `messages[].content` | 同（字符串） | 直通 |
| `messages[].images` | `content` 数组里的 `image_url` | **需转换**：base64 数组 → data URL |
| `messages[].tool_calls[]` | 同（`id`/`function.name`/`function.arguments`） | 直通（注意 arguments 是对象 vs 字符串的差异） |
| `messages[].tool_call_id` | `tool_call_id` | 直通 |
| `tools[]` | 同（`type:function` + `function`） | 直通 |
| `stream` | 同 | 直通 |
| `think` | 无对应 | 映射到 thinking 步骤（`reasoning_effort` / `thinking`） |
| `options.{temperature,num_ctx,max_tokens,…}` | 顶层散字段 | **需摊平**：`options` 嵌套 → 顶层 |
| （无） | `stream_options.include_usage` | 需按需注入 |
| `format`（JSON schema） | `response_format` | 需转换（如支持） |

**空 schema 坑**：官方扩展已实测踩过——无参工具的空 schema `{}` 会被 Ollama
重新序列化成 `{type:'', properties:null}` 导致模板解析拒绝。COSP 作为
Ollama **服务端**时是接收方，需注意客户端可能发来哪种形态。

### 4.2 响应侧——NDJSON 是最大的差异

| 项 | Chat（SSE） | Ollama（NDJSON） |
|---|---|---|
| 流格式 | `data: {...}\n\n` + `data: [DONE]` | 每行一个完整 JSON 对象 |
| 正文增量 | `choices[0].delta.content` | `message.content` |
| 思考增量 | `delta.reasoning_content` | `message.thinking` |
| 工具调用 | `delta.tool_calls[]`（增量拼接） | `message.tool_calls[]`（**整条给出**，不增量） |
| 结束标记 | `finish_reason` + `[DONE]` | `done: true` 单行 |
| 用量 | `usage.{prompt,completion,total}_tokens` | `prompt_eval_count` / `eval_count`（拆在多个 chunk） |
| 非流式 | 单个 `chat.completion` 对象 | 单个对象（`done:true`，无流） |

**关键不确定性**：
1. **工具调用不增量**——Ollama 的 `tool_calls` 是一次性完整给出的。
   若上游是 Chat/Responses（增量给出），回程需要**缓冲到完整再发**，
   这与 R2C 的 `pending`/`argsSentAt` 机制方向相反（那边是补齐差量，
   这边是攒完整）。这是 C2O/M2O/R2O 的真正难点。
2. **用量拆散**——`prompt_eval_count` 与 `eval_count` 出现在不同 chunk，
   需累计到末行。

### 4.3 模型发现侧的既有实现

`/api/tags`、`/api/show` 已实现，但需复核它们是否满足官方扩展的需求：

- 扩展读 `show.capabilities`（`tools`/`vision`/`thinking`）判定工具与图片支持
- 扩展读 `show.thinking` 取可用 thinking 档位（0.0.12 起）
- 扩展读 context length 做上下文检查，低于阈值会警告

现有实现返回的是 `MINIMUM_CONTEXT_LENGTH = 8192` 与 `nano_llm` 兜底——
需确认这些值与扩展的期望一致，否则可能被判定为「机器上下文太小」而拒绝。

---

## 5. 结构合规检查表

开工前对照 [pipeline/README.md](../../../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/README.md)
的四轴框架，确保不越轴：

| 检查项 | 期望 |
|---|---|
| 翻译器落在 `pipeline/protocol/translate/` | ✅ 翻译是数据流的一部分 |
| 以 `@Component` + 声明方向接入 | ✅ `TranslatorRegistry` 零改动 |
| `entry/OllamaChatService` 用 defer 包裹 | ✅ 与三端点同形 |
| `/api/chat` 进 `PROTECTED_PATHS` | ⚠️ **必查** |
| 主干五个基座类零改动 | `RequestPipeline` / `ctx` / `BeforeSend` / `AfterSend` / `UpstreamCallRunner` |
| 依赖方向 | 新文件只能 import `pipeline` + `protocol`，不得反向依赖 `observability`/`control`/`api`/`infrastructure` |
| NDJSON 出口在 `api/` 层 | 出口按下游协议分岔是 `api/` 的职责，不要把 NDJSON 拼装塞进 `pipeline/` |
| `WireProtocol` 新增值的影响面 | 检查所有 `switch (protocol)` 是否有 `default`（无 default 会编译失败，这是好事） |

---

## 6. 待确认问题

1. **官方扩展是否需要 `/api/ps`、`/api/embed` 等其它端点？**
   本次调研只看了 `chatFetch.ts` / `provider.ts` / `convert.ts`，
   未穷举扩展的全部请求。开工前应抓一次真实扩展的请求日志。
2. **`/api/chat` 的鉴权形态**——Ollama 原生无鉴权概念。COSP 的
   `GatewayAuthFilter` 用的是 Bearer/自定义头；扩展支持配置 `headers`，
   需确认能否携带 COSP 的鉴权头。
3. **非流式请求**（`stream:false`）扩展是否会发？`invokeStream` 之外是否有
   `invoke` 路径需要支持。
4. **`keep_alive`、`options.num_ctx` 等 Ollama 特有字段**是忽略还是透传？
   若透传给上游 Chat 会被拒（未知字段），需显式丢弃。
5. **`think` 的取值域**——布尔 vs 命名档位（`low`/`medium`/`high`），
   与各上游 thinking 参数的映射表需逐家确认。

---

## 7. 不做的事

- **不做 X2O 去程**（把 C/M/R 请求翻译成 Ollama `/api/chat` 发给上游）。
  理由：没有正经供应商提供 Ollama 形态的上游接口；本地 Ollama 自带 OpenAI 兼容端点，
  按普通 OpenAI 兼容供应商配置即可。
- **不做 `/api/generate`**（补全式单轮接口）。扩展只用 `/api/chat`。
- **不做 `/api/embed` / `/api/pull` / `/api/create`** 等运维端点。
  它们是 Ollama 的模型管理能力，与代理职责无关。
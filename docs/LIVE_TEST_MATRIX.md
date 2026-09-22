# 实机验证矩阵

> **本文的用途**：列出**值得在真机上跑一遍的验证项**，供未来（阶段 3.6 正式收口、或任何改动
> 触及这些路径时）按表执行。
>
> **为什么要有这张表**：单测覆盖的是「判定与算子序列」，覆盖不到的是**真实链路上的端到端形状**
> —— 下游客户端的协议要求、SSE 信封、Copilot 的实际行为。项目里五套 mock 已经把这些场景
> 都造好了，但**场景与 mock 的对应关系散在五份 README 里**，因此每次要验证时都要重新翻一遍。
>
> **本文只列「测什么、用哪个 mock、看什么」**，不复制各 mock 的启动细节（那些在各 README）。

---

## 0. 已实测通过的部分（不必重跑）

2026-09-22，用户实测：**自动重试机制**（429 / 5xx / 网络中断）、**翻译**、**直连请求**三条路径正常。

---

## 1. 五套 mock 的分工

| mock | 模拟什么 | 端口 | 何时用 |
|---|---|---|---|
| [`mock-upstream`](../tools/mock-upstream/README.md) | 上游 **OpenAI 流式** | 8081 | 验 Chat 线路的流式行为 |
| [`mock-nonstream`](../tools/mock-nonstream/README.md) | 上游 **OpenAI 非流式** | 8082 | 验 Chat 线路的非流式行为 |
| [`mock-anthropic`](../tools/mock-anthropic/README.md) | 上游 **Anthropic**（流式+非流式） | 8083 | 验 MESSAGES 线路 / C2M |
| [`mock-toolorder`](../tools/mock-toolorder/README.md) | 工具调用顺序边界 | 8084 | 工具调用专项 |
| [`mock-cosp`](../tools/mock-cosp/README.md) | **COSP 自己**（供下游直连） | 11333 | 验下游入站报文（含翻译后的形态） |

**模型名不能撞**：`ProviderRouteResolver` 对**无前缀**模型名要求唯一匹配，重名会导致路由不到。
各 mock 的模型名已刻意错开（`normal` / `ns-*` / `at-*`）。

---

## 2. 验证矩阵

**优先级说明**：P0 = 本次 3.6 改动的直接验收项；P1 = 3.6 改动可能波及但未系统走过；
P2 = 既有能力，回归时可抽查。

### A. 空响应兜底（P0 —— 3.6b 的直接验收，尚未实机验证）

3.6b 把空响应拦截从 9 份副本收归 `EmptyResponseGate`，**只跑了单测**。这是本阶段最该补的一项。

| # | 上游协议 | 形态 | mock + 模型名 | 期望 |
|---|---|---|---|---|
| A1 | Chat | 流式 | `mock-upstream` / `empty-stream` | Toast 显示「正在重试（第 N 次）」，预算耗尽后放行 |
| A2 | Chat | 流式 | `mock-upstream` / `empty-body` | 同上（0 帧 body 也判空） |
| A3 | Chat | 非流式 | `mock-nonstream` / `ns-empty-body` | 同上，耗尽后放行空 body |
| A4 | Chat | 非流式 | `mock-nonstream` / `ns-empty-choices` | 同上（`choices: []`） |
| A5 | Anthropic | 流式 | `mock-anthropic` / `at-empty-stream` | 同上 |
| A6 | Anthropic | 非流式 | `mock-anthropic` / `at-empty-body` | 同上 |
| **A7** | 任意 | 任意 | `empty-usage-zero` 系（三个 mock 各一个） | **usage 不是判据** —— 全 0 usage 仍应判空 |
| **A8** | Chat | 流式 | `mock-upstream` / **`empty-tool-call`** | **对照组**：纯工具调用**不**判空，零重试、快速返回 |
| **A9** | Chat | 非流式 | `mock-nonstream` / **`ns-tool-call`** | 同上 |
| **A10** | Chat | 非流式 | `mock-nonstream` / **`ns-reasoning-only`** | 同上 + reasoning fallback 应生效（`content` 被思考内容填充） |
| **A11** | Anthropic | 流式 | `mock-anthropic` / **`at-thinking-only`** | **对照组**：纯思考块不判空 |
| A12 | Chat | 非流式 | `mock-nonstream` / `ns-malformed-json` | **解析失败保守放行** —— 不判空、原样透传 |
| A13 | Anthropic | 流式 | `mock-anthropic` / `at-stream-malformed` | 同上 |

> **A8–A11 是最容易漏的一类**：它们验证「不该重试的没重试」。
> 若这些也开始重试，说明判定把「无正文」误当成了「空」——而那时**空响应兜底本身看起来是正常的**。
>
> **A12/A13 同理**：宁可放行一个没见过的格式，也不要因结构陌生就判空重试。

### B. 重试与放行（P0 —— 3.6c 的直接验收）

3.6c 收归了 `UpstreamAutoRetry`（规格）、`UpstreamSilentRetry`（静默重试）、
`UpstreamRetryPolicy.findWebResponseException`（解包），并**统一了 429 的日志**。

| # | 场景 | mock + 模型名 | 期望 |
|---|---|---|---|
| B1 | 5xx 自动重试中途成功 | `mock-upstream` / `retry-then-succeed`（另有 `ns-` / `at-` 两份） | RETRYING → CONNECTED → COMPLETED |
| B2 | 401 快速失败不重试 | `mock-upstream` / `error-401` | FAILED，**无 RETRYING** |
| **B3** | **429 会重试且日志带 `Retry-After`** | ⚠️ **无 mock，需临时加** | 见 §3 |
| **B4** | **静默重试（流式）** | 任一流式模型 + Toast 右键「静默重试」 | 上游被重新请求，下游 SSE 不中断、内容续在同一条流上 |
| **B5** | **静默重试不消耗自动预算** | 同上，连点多次 | 每次都能触发；自动预算不受影响 |
| **B6** | **静默重试对非流式不可用** | 任一非流式模型 | 菜单项**不显示**（`v-if="menuTarget.stream"`）—— 这是预期，不是缺口 |
| B7 | 传输截断按网络失败重试 | `mock-upstream` / —（`ns-truncated` 在 mock-nonstream） | 日志状态码 `-1`，可重试 |
| **B8** | **空响应与 5xx 共用同一份预算** | 交替场景（需临时改 mock） | 不出现「两套额度」—— 见 §3 |

> **B6 值得记一笔**：403/401 不重试是**自动重试**的判据（`isRetryableStatus` 白名单只有
> `429 / 5xx / 400`），而**静默重试不看状态码**——它只响应人工信号，唯一的门槛是「流式」。
> 两条路径常被混为一件，见 §4「常见误解」。

### C. 三条协议 × 两态的直连主路径（P1）

| # | 上游协议 | 形态 | mock + 模型名 | 期望 |
|---|---|---|---|---|
| C1 | Chat | 流式 | `mock-upstream` / `normal` | RECEIVED → CONNECTED → CHUNK → COMPLETED 全链路 |
| C2 | Chat | 非流式 | `mock-nonstream` / `ns-normal` | 200 透传，用量入库（`api_call_usage`） |
| C3 | Anthropic | 流式 | `mock-anthropic` / `at-normal` | 事件序列原样透传 |
| C4 | Anthropic | 非流式 | `mock-anthropic` / `at-normal`（`stream:false`） | 同上 |

### D. C2M 跳协议翻译（P1 —— 3.6 未直接改动，但响应侧被 gate 收编过）

**C2M** = 下游 OpenAI、上游 Anthropic。配置方式：供应商的协议集合只勾 MESSAGES。

| # | 场景 | 入口 | mock 模型名 | 期望 |
|---|---|---|---|---|
| D1 | C2M 流式 | Copilot（`/v1/chat/completions`） | `mock-anthropic` / `at-normal` | OpenAI chunk 形态，**不是** Anthropic 事件 |
| D2 | C2M 非流式 | 同上 | 同上（`stream:false`） | 同上 |
| **D3** | **C2M 工具调用参数分片** | 同上 | `at-tool-split-args`（30+ 片） | 拼接后为合法 JSON，index 恒为 0 |
| **D4** | **C2M 多工具分片** | 同上 | `at-tool-multi-split` | tool index **稠密重映射**为 0/1/2 |
| D5 | C2M 工具参数交错 | 同上 | `at-tool-interleaved` | 两段各自拼接，互不污染 |
| D6 | C2M 工具无参数 | 同上 | `at-tool-no-args` | 不凭空补 `{}` |
| **D7** | **`finish_reason` 覆盖** | 同上 | `at-tool-then-max-tokens` / `at-tool-then-context-exceeded` | `tool_calls`（**不是** `length`） |
| **D8** | **C2M 空响应** | 同上 | `at-empty-content` / `at-empty-stream` | 兜底生效（翻译器在重试**外侧**，判定用上游原生形态） |

> **D7 的后果最严重**：若是 `length`，Copilot 会判定回答被截断、**放弃执行已经拿到的完整工具调用**
> 并结束对话，**且全链路无任何报错**。测这个场景时看 Copilot 是否真的执行了工具。
>
> **D8 要注意**：翻译器套在上游服务**外侧**因而在 `retryWhen` 之外 ——
> 判空用的是上游原生形态。若 D8 表现异常，先查这一点。

### E. 下游入站报文（P2）

用 `mock-cosp`（端口 11333）把下游客户端指过来，看它**真实发出的**请求头与请求体。

| # | 场景 | 期望 |
|---|---|---|
| E1 | Copilot 模型发现 | `GET /api/version`、`GET /api/tags`、`POST /api/show` 被调用 |
| E2 | Copilot 聊天 | 打 `/v1/chat/completions`，终端高亮 `authorization` / `x-api-key` |
| E3 | Claude 系客户端 | 打 `/v1/messages`，带 `anthropic-version` |
| E4 | Responses 客户端 | 打 `/v1/responses`，事件序列**一帧都不能省** |

### F. 连接生命周期与下游中断（P2）

| # | 场景 | mock 模型名 | 期望 |
|---|---|---|---|
| F1 | 发完 + `[DONE]` 但不关 TCP | `done-no-close` | COMPLETED 不等连接关闭 |
| F2 | 发完直接关连接、无 `[DONE]` | `no-done-close` | TCP 关闭兜底完成 |
| F3 | 首字前卡住 | `hang-first-byte` | Toast 停在「等待首字」，右键可断连 |
| F4 | 产出后永久停滞 | `stall-forever` | 右键断连 → ABORTED |
| F5 | 停滞 35s 后恢复 | `stall-recover` | 停滞期可断连；恢复后继续 CHUNK |

---

## 3. 需要先补 mock 的两项

| 项 | 缺什么 | 补法建议 |
|---|---|---|
| **B3（429）** | 五个 mock **都没有 429 场景** | 在 `mock-upstream` 加一个 `error-429` 模型名，回 429 + `Retry-After: 5`。这是唯一能验证「`Retry-After` 出现在日志」的方式 —— 而那条日志是 3.6c-2 的行为变更之一 |
| **B8（预算不叠加）** | 需要有节奏地交替失败 | 加一个「空响应 → 500 → 空响应 → 正常」的序列场景 |

> **B3 值得优先补**：3.6c-2 把 429 的日志特化从「只在 Chat」扩到三条线路，
> 那是**行为变更**，而当前**没有任何 mock 能验证它**。单测只到「429 会重试」为止
> （日志文案按项目约定不测，见 `docs/KNOWN_DEBT.md` 与 `UpstreamCallReporterTests`）。

---

## 4. 常见误解（避免走弯路）

**误解一：「403 应该重试」** —— 不对。`UpstreamRetryPolicy.isRetryableStatus` 的白名单是
`429 / 5xx / 400`，401/403 是**确定性错误**：请求内容未变，重试只是白等 62 秒然后仍然失败。
这与 `ProviderRequestHeaderService` 的「上游不认头名就回 401/403，本服务**不做**自动换头重试」
是同一条原则 —— 让排查指向凭据／配置，而不是让代码悄悄换个做法掩盖它。

**误解二：「静默重试和自动重试是一回事」** —— 不是。项目里「重试」指三个独立功能：

| | 触发者 | 预算 | 判据 |
|---|---|---|---|
| 自动重试 | COSP 自己 | 消耗 `retry_max_attempts` | 失败类型（见上） |
| 静默重试 | **人**在管理后台点 | **不消耗** | **不看状态码**，只要求流式 |

**误解三：「静默重试对 403 不可用」** —— 恰好相反：静默重试不看状态码，
**403 的流式调用可以点静默重试**；不能用的只有**非流式**（没有在途连接可续，
注册发生在流式线路内）。

**误解四：「`-1` 占位值就是空响应」** —— 不是。`api_call_log` 里状态码 `-1` 是
**非 HTTP 异常**的统称，可能来自连接失败、DNS、TLS、截断或空响应耗尽。
区分方法：看响应头是否为 `{}`。

---

## 5. 执行顺序建议

若要完整跑一遍（约 1–2 小时，含 mock 起停与配置切换）：

1. **先补 §3 的两个 mock 场景**（否则 B3/B8 无覆盖）
2. **A 组**（空响应，P0）—— 3.6b 的核心验收，且 A8–A11 对照组最容易漏
3. **B 组**（重试与放行，P0）—— 3.6c 的核心验收
4. **C/D 组**（三协议直连 + C2M）—— 主路径回归
5. **E/F 组**（入站报文 + 生命周期）—— 抽查即可

**每换一个 mock 都要改供应商配置**（Base URL + 模型名），因此把同一 mock 的场景连着跑完。

---

## 6. 相关文档

| 主题 | 文件 |
|---|---|
| 各 mock 的启动与场景细节 | `tools/mock-*/README.md` |
| 跳协议契约（C2M 请求侧） | [PROTOCOL_TRANSLATION_CONTRACT.md](./PROTOCOL_TRANSLATION_CONTRACT.md) |
| 跳协议契约（响应侧） | [PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md](./PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md) |
| 上游适配史与取舍 | [PROVIDER_ADAPTATIONS.md](./PROVIDER_ADAPTATIONS.md) |
| 已知技术债与刻意不做的取舍 | [KNOWN_DEBT.md](./KNOWN_DEBT.md) |

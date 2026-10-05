# 实机验证矩阵

> **本文的用途**：列出**值得在真机上跑一遍的验证项**，供未来按表执行。
>
> **为什么要有这张表**：单测覆盖的是「判定与算子序列」，覆盖不到的是**真实链路上的端到端形状**
> —— 下游客户端的协议要求、SSE 信封、Copilot 的实际行为。mock 已经把场景都造好了，
> 但「**该看什么**」不适合塞进 mock 的 README，因此单列一份。
>
> **本文只列「测什么、用哪个场景、看什么」**，不复制 mock 的启动细节。

---

## 1. 两套 mock 的分工

| mock | 模拟什么 | 端口 | 何时用 |
|---|---|---|---|
| [`mock-upstream`](../../tools/mock-upstream/README.md) | 上游供应商（**三协议合一**） | 8081 | 验 Chat / Messages / Responses 三条上游链路 |
| [`mock-cosp`](../../tools/mock-cosp/README.md) | **COSP 自己**（供下游直连） | 11333 | 验下游入站报文（含翻译后的形态） |

```bash
cd frontend
./node/npm run mock              # 上游 mock（全部协议与场景）
./node/npm run mock:cosp         # 下游嗅探 mock
```

**上游协议由端点路径决定，不由模型名决定** —— 同一个场景名在 `/chat/completions`
与 `/messages` 上都能打，行为按该协议的形态呈现。完整场景清单见
[`mock-upstream/README.md`](../../tools/mock-upstream/README.md) §5。

**场景名自带期望**：`blank-*` 期望判空兜底、`pass-*` 期望放行、`retry-*` 期望重试、
`fail-*` 期望快速失败、`phase-*` 验相位、`cancel-*` 验中断、`translate-*` 验翻译形态。

**模式不适用时回 422**：某场景只在一种传输模式下有意义时，打另一种会收到
**422 + 说明**（而不是静默照做）。因此下表标注「流式」的行，打非流式会得到 422 ——
这是预期，不是缺口。**为何用 422**：可重试白名单是 `429 / 5xx / 400`，
回 400 或 501 会让 COSP 白等 62 秒才看到「模式不支持」。

---

## 2. 验证矩阵

**优先级说明**：P0 = 收归类改动的直接验收项（单测只到「判定正确」，端到端形状未验）；
P1 = 主路径回归；P2 = 既有能力，回归时可抽查。

### A. 空响应兜底（P0 —— 收归的直接验收，尚未实机验证）

空响应拦截收归 `EmptyResponseGate` 后**只跑了单测**。这是最该补的一项。

| # | 协议 | 模式 | 场景名 | 期望 |
|---|---|---|---|---|
| A1 | Chat | 流式 | `blank-empty-content` | Toast 显示「正在重试（第 N 次）」，预算耗尽后放行 |
| A2 | Chat | 流式 | `blank-empty-body` | 同上（0 帧 body 也判空） |
| A3 | Chat | 非流式 | `blank-empty-body` | 同上，耗尽后放行空 body |
| A4 | Chat | 非流式 | `blank-empty-choices` | 同上（`choices: []`） |
| A5 | Messages | 流式 | `blank-empty-content` | 同上 |
| A6 | Messages | 非流式 | `blank-empty-body` | 同上 |
| **A7** | 任意 | 任意 | `blank-zero-usage` | **usage 不是判据** —— 全 0 usage 仍应判空 |
| **A8** | Chat | 流式 | **`pass-tool-call`** | **对照组**：纯工具调用**不**判空，零重试、快速返回 |
| **A9** | Chat | 非流式 | **`pass-tool-call`** | 同上 |
| **A10** | Chat | 非流式 | **`pass-reasoning-only`** | 同上 + reasoning fallback 应生效（`content` 被思考内容填充） |
| **A11** | Messages | 流式 | **`pass-thinking-only`** | **对照组**：纯思考块不判空 |
| A12 | Chat | 非流式 | `pass-malformed` | **解析失败保守放行** —— 不判空、原样透传 |
| A13 | Messages | 流式 | `pass-malformed` | 同上 |
| **A14** | Chat | 非流式 | `pass-sse-body` | 无视 `stream=false` 仍回 SSE → 文本原样透传、不判空 |

> **A8–A11 是最容易漏的一类**：它们验证「不该重试的没重试」。
> 若这些也开始重试，说明判定把「无正文」误当成了「空」—— 而那时**空响应兜底本身看起来是正常的**。
>
> **A12–A14 同理**：宁可放行一个没见过的格式，也不要因结构陌生就判空重试。

### B. 重试与放行（P0 —— 收归的直接验收）

重试的三件事各在一处：`UpstreamAutoRetry`（规格）、`control/CallResendLoop`（静默重试）、
`UpstreamRetryPolicy.findWebResponseException`（解包）。429 的日志三条线路共用同一份。

| # | 场景 | 场景名 | 期望 |
|---|---|---|---|
| B1 | 5xx 自动重试中途成功 | `retry-recover`（三协议各自可打） | RETRYING → CONNECTED → COMPLETED |
| B2 | 401 快速失败不重试 | `fail-401` | FAILED，**无 RETRYING** |
| **B3** | **429 会重试且日志带 `Retry-After`** | `retry-429` | 见 §3 —— **本轮新补** |
| **B4** | **静默重试（流式）** | 任一流式场景 + Toast 右键「静默重试」 | 上游被重新请求，下游 SSE 不中断、内容续在同一条流上 |
| **B5** | **静默重试不消耗自动预算** | 同上，连点多次 | 每次都能触发；自动预算不受影响 |
| **B6** | **静默重试对非流式不可用** | 任一非流式场景 | 菜单项**不显示**（`v-if="menuTarget.stream"`）—— 这是预期，不是缺口 |
| B7 | 传输截断按网络失败重试 | `retry-truncated`（两态三协议均可） | 日志状态码 `-1`，可重试 |
| **B8** | **空响应与 5xx 共用同一份预算** | `blank-empty-content` 与 `retry-5xx` 交替 | 不出现「两套额度」—— 见 §3 |

> **B6 值得记一笔**：403/401 不重试是**自动重试**的判据（`isRetryableStatus` 白名单只有
> `429 / 5xx / 400`），而**静默重试不看状态码** —— 它只响应人工信号，唯一的门槛是「流式」。
> 两条路径常被混为一件，见 §4「常见误解」。

### C. 三条协议 × 两态的直连主路径（P1）

| # | 协议 | 模式 | 场景名 | 期望 |
|---|---|---|---|---|
| C1 | Chat | 流式 | `baseline-normal` | RECEIVED → CONNECTED → CHUNK → COMPLETED 全链路 |
| C2 | Chat | 非流式 | `baseline-normal` | 200 透传，用量入库（`api_call_usage`） |
| C3 | Messages | 流式 | `baseline-normal` | 事件序列原样透传 |
| C4 | Messages | 非流式 | `baseline-normal` | 同上 |
| C5 | Messages | 两态 | `baseline-thinking-text` | 思考与正文交替，两段都到达下游 |
| C6 | 两协议 | 两态 | `baseline-tool-call` | 工具调用原样透传，下游能执行工具 |

### D. 跳协议翻译（P1）

**C2M** = 下游 OpenAI、上游 Anthropic。配置方式：供应商的协议集合只勾 MESSAGES。

| # | 场景 | 入口 | 场景名 | 期望 |
|---|---|---|---|---|
| D1 | C2M 流式 | Copilot（`/v1/chat/completions`） | `baseline-normal` | OpenAI chunk 形态，**不是** Anthropic 事件 |
| D2 | C2M 非流式 | 同上 | `baseline-normal` | 同上 |
| **D3** | **C2M 工具调用参数分片** | 同上 | `translate-tool-split-args`（30+ 片） | 拼接后为合法 JSON，index 恒为 0 |
| **D4** | **C2M 多工具分片** | 同上 | `translate-tool-multi-split` | tool index **稠密重映射**为 0/1/2 |
| D5 | C2M 工具参数交错 | 同上 | `translate-tool-interleaved` | 两段各自拼接，互不污染 |
| D6 | C2M 工具无参数 | 同上 | `translate-tool-no-args` | 不凭空补 `{}` |
| **D7** | **`finish_reason` 覆盖** | 同上 | `translate-finish-reason` / `-nonstandard` | `tool_calls`（**不是** `length`） |
| **D8** | **C2M 空响应** | 同上 | `blank-empty-content` | 兜底生效（翻译器在重试**外侧**，判定用上游原生形态） |

> **D7 的后果最严重**：若是 `length`，Copilot 会判定回答被截断、**放弃执行已经拿到的完整工具调用**
> 并结束对话，**且全链路无任何报错**。测这个场景时看 Copilot 是否真的执行了工具。
>
> **D8 要注意**：翻译器套在上游服务**外侧**因而在 `retryWhen` 之外 ——
> 判空用的是上游原生形态。若 D8 表现异常，先查这一点。

> `translate-*` 全部只在 `/messages` 上有实现（它们复现的是 Anthropic 专有形状）。
> 往 `/chat/completions` 打会得到 422 —— 那是**指引**（该改协议），不是故障。

### E. 下游入站报文（P2）

用 `mock-cosp`（端口 11333）把下游客户端指过来，看它**真实发出的**请求头与请求体。

| # | 场景 | 期望 |
|---|---|---|
| E1 | Copilot 模型发现 | `GET /api/version`、`GET /api/tags`、`POST /api/show` 被调用 |
| E2 | Copilot 聊天 | 打 `/v1/chat/completions`，终端高亮 `authorization` / `x-api-key` |
| E3 | Claude 系客户端 | 打 `/v1/messages`，带 `anthropic-version` |
| E4 | Responses 客户端 | 打 `/v1/responses`，事件序列**一帧都不能省** |

### F. 连接生命周期与下游中断（P2）

| # | 场景 | 场景名 | 期望 |
|---|---|---|---|
| F1 | 发完 + 结束标记但不关 TCP | `phase-done-early` | COMPLETED 不等连接关闭（Layer 1） |
| F2 | 发完直接关连接、无结束标记 | `phase-eof-fallback` | TCP 关闭兜底完成（Layer 2） |
| F3 | 建立连接后不吐数据 | `cancel-hang` | Toast 停在「等待首字」，右键可断连 → ABORTED |
| F4 | 产出后永久停滞 | `cancel-stall` | 右键断连 → ABORTED |
| F5 | 停滞 35s 后恢复 | `cancel-stall-resume` | 停滞期可断连；恢复后继续 CHUNK → COMPLETED |
| F6 | 延迟 5s 才吐首字、随后停滞 | `cancel-stall-delayed` | 等待首字与产出后停滞**两个阶段**都可断连 |
| F7 | 每 3s 一个 chunk、持续较久 | `phase-slow-steady` | Toast 的 chunk 计数持续更新 |
| F8 | 延迟 5s 后正常返回 | `phase-slow` | 不被误判为停滞，取消仍可用 |

> **F1/F2 是两层完成判定的对照组**：F1 有结束标记、连接不关（Layer 1 应立刻收尾），
> F2 无结束标记、连接关了（Layer 2 兜底）。两者若表现一致，说明某一层没起作用。

---

## 3. mock 侧的已知边界

### 已补上的缺口（本轮）

| 原缺口 | 现状 |
|---|---|
| **无 429 场景** —— 旧四个 mock 都没有，是 `Retry-After` 日志唯一无法验证的原因 | `retry-429` 带 `Retry-After: 5` |
| **Chat 侧无流式截断** —— 传输截断只在非流式 mock 有 | `retry-truncated` 两态三协议通用 |
| **Chat 侧无流式 reasoning 帧** —— 思考链场景一直无法在 Chat 线上复现 | `pass-reasoning-only` 两态均有 |
| **上游 Responses 协议无 mock** | ⏳ 下一轮补 `protocols/responses.js`；当前 `/responses` 回 422 说明 |

### 刻意不做

| 项 | 原因 |
|---|---|
| `blank-empty-choices` 的流式实现 | 流式下 `choices` 逐帧到达，一帧空数组不是有意义的一档。打流式会得到 422 说明该用非流式 |
| `pass-sse-body` 的流式实现 | 它验证的是「非流式路径对未知 body 类型的宽容度」，流式下该形状不存在 |

### 有意的行为变更（与旧 mock 不同）

| 项 | 旧行为 | 新行为 |
|---|---|---|
| `stream` 字段 | `mock-upstream` 完全忽略、一律回 SSE | 按真实语义：缺省与 `false` 走非流式 |
| `stream=true` 打非流式场景 | `mock-nonstream` 回 400 | 回 **200**（两态共用的场景）或 **422**（仅非流式场景） |
| 端口 | 8081 / 8082 / 8083 / 8084 各一个 | 统一 **8081**；旧配置需改 Base URL |

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

**误解五：「422 是 mock 坏了」** —— 不是。422 是 mock 在说「这个场景在你选的
协议/模式组合下没有实现」，响应体里写了该换成什么。它是**指引**，不是故障。
相反，如果它回 400 或 501，COSP 会先白等 62 秒重试 —— 那才是麻烦。

---

## 5. 执行顺序建议

若要完整跑一遍（约 1–2 小时，含起停与配置切换）：

1. **先跑机械自检**：`cd frontend; .\node\npm.cmd run mock:selfcheck` ——
   遍历全部「场景 × 协议 × 模式」组合，确认没有意外的状态码（约 1 分钟）
2. **A 组**（空响应，P0）—— 收归的核心验收，且 A8–A11 对照组最容易漏
3. **B 组**（重试与放行，P0）—— 收归的核心验收
4. **C/D 组**（三协议直连 + C2M）—— 主路径回归
5. **E/F 组**（入站报文 + 生命周期）—— 抽查即可

**换供应商配置只需改一次**：只有一个 mock、一个端口，「每换一个 mock 都要改配置」
这条负担消失了。但**协议集合要按被测线路勾**（验 C2M 时只勾 MESSAGES）。

---

## 6. 相关文档

| 主题 | 文件 |
|---|---|
| mock 的启动、场景清单、新旧名对照 | [`../tools/mock-upstream/README.md`](../../tools/mock-upstream/README.md) |
| 下游嗅探 mock | [`../tools/mock-cosp/README.md`](../../tools/mock-cosp/README.md) |
| 跳协议契约（请求侧） | [REQUEST-CONTRACT.md](../features/protocol-translation/chat-messages/REQUEST-CONTRACT.md) |
| 跳协议契约（响应侧） | [RESPONSE-CONTRACT.md](../features/protocol-translation/chat-messages/RESPONSE-CONTRACT.md) |
| 上游适配史与取舍 | [ADAPTATIONS.md](../architecture/ADAPTATIONS.md) |
| 已知技术债与刻意不做的取舍 | [KNOWN_DEBT.md](../architecture/KNOWN_DEBT.md) |

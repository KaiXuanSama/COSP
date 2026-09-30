# COSP 模拟上游供应商（三协议合一）

一个零依赖的 Node 脚本，提供 **OpenAI Chat / Anthropic Messages / OpenAI Responses**
三个端点，通过**场景名**触发各种上游行为，用于在真实 HTTP + SSE 连接下验证 COSP 的
完整处理链路。`/responses` 当前返回 422（实现在下一轮）。

```bash
cd frontend
./node/npm run mock              # 启动，默认 http://localhost:8081
./node/npm run mock:selfcheck    # 自检：遍历全部「场景 × 协议 × 模式」组合
```

或直接 `node tools/mock-upstream/mock-upstream.js`。

---

## 1. 它替掉了什么

合并前有四个独立脚本，各占一个端口，按**四种互不相同的轴**切分：

| 旧 mock | 端口 | 切分轴 |
|---|---|---|
| `mock-upstream` | 8081 | **传输形态** —— 只做 Chat 流式 |
| `mock-nonstream` | 8082 | **传输形态** —— 只做 Chat 非流式 |
| `mock-anthropic` | 8083 | **协议** —— Messages 两态合并 |
| `mock-toolorder` | 8084 | **场景维度** —— 工具调用与正文的顺序 |

四种轴混用必然看起来乱，而且同一场景在三处各写一份
（`error-500` / `error-401` / `retry-then-succeed` / `hang-response` / `slow-response`
各三份，共 15 处等价代码）。现在合到一个服务、一个端口。

> **`mock-cosp` 不在合并之列**，它模拟的是 **COSP 自己**（供下游客户端直连以嗅探入站报文），
> 与「模拟上游」是正交职责。见 [`../mock-cosp/README.md`](../mock-cosp/README.md)。

## 2. 文件布局

```
mock-upstream/
├── mock-upstream.js       入口：HTTP server · 路由 · /v1/models · 422 兜底
├── smoke-test.js          自检：遍历全部组合，报告状态码与首帧
├── lib/
│   ├── http.js            机制：SSE 写帧 · 读 body · 日志 · 错误响应 · 422
│   └── transport.js       协议无关场景：错误码 / 重试 / 截断 / 挂起 / 延迟
├── protocols/
│   ├── chat.js            OpenAI Chat 形态的场景
│   ├── messages.js        Anthropic Messages 形态的场景
│   └── responses.js       （待实现）
└── README.md
```

**为何分这三层**：场景按**协议形态**与**传输行为**两个轴切分 —— 前者每个协议必须各写一份
（帧形状完全不同），后者三协议逐字相同（HTTP 层的事）。把传输行为独立成一层，
消掉了那 15 处重复。

## 3. 协议由端点路径决定，不由模型名决定

```
POST http://localhost:8081/chat/completions  + model=baseline-normal  → chat.js
POST http://localhost:8081/messages          + model=baseline-normal  → messages.js
POST http://localhost:8081/responses         + model=baseline-normal  → （下一轮）
```

**同一个场景名在多个端点上都能打**，行为按该协议的形态呈现。因此：

- `/v1/models` 只列**一份去重清单**（三协议场景名的并集）
- 模型名不再需要 `ns-` / `at-` / `to-` 这类跨进程避重前缀 —— 那类前缀的唯一用途
  是绕开「无前缀模型名要求唯一匹配」的路由约束，合到一个服务后约束自然消失

**Base URL 填 `http://localhost:8081`（不带 `/v1`）** —— COSP 往 Base URL 上拼接的正是
`chat/completions` / `messages` / `responses`，与真实供应商一致。

## 4. 场景命名口径

**名字描述「被测的 COSP 判定点」，不描述 mock 自己的动作。**
域前缀即期望 —— 不用翻文档就知道该看什么：

| 域 | 期望 | 对应 COSP 的什么 |
|---|---|---|
| `baseline-*` | 全链路正常，不触发特殊判定 | 端到端基准 |
| `blank-*` | **判空** → 兜底重试 | `EmptyResponseGate` |
| `pass-*` | **放行**（对照组，不该判空） | 同上，反向 |
| `retry-*` | 自动重试 | `UpstreamRetryPolicy` + `UpstreamAutoRetry` |
| `fail-*` | 快速失败，**不**重试 | 同上，反向 |
| `phase-*` | 相位正确 / 计数持续推进 | `CallPhase` + `StreamLifecycle` |
| `cancel-*` | 停滞期可中断 | 控制面（右键断连） |
| `translate-*` | 翻译后形态正确 | `pipeline/protocol/translate/` |

### 传输模式的差异体现在响应码，不体现在命名

有些场景只在一种模式下有意义（`cancel-stall` 对非流式无意义，`pass-sse-body` 对流式无意义）。
这类组合**不改名字**，而是**回 422 并说明原因**：

```json
{ "error": { "message": "mock 场景 cancel-stall 在当前「协议 × 传输模式」组合下没有实现。该场景只有非流式实现 —— 把 stream 设为 true，或换用两态都支持的场景（如 baseline-normal / blank-empty-content）。", "type": "mock_error", "code": 422 } }
```

**为何是 422 而不是 400 / 501**：

| 候选 | 为什么不行 |
|---|---|
| **400** | `UpstreamRetryPolicy.isRetryableStatus` 白名单是 `429 / 5xx / 400` —— 400 会触发重试，默认 5 次 × 2s/30s 退避 = **白等 62 秒**才看到「模式不支持」 |
| **501** | 语义上最贴切（「未实现」），但**它是 5xx**，`is5xxServerError()` 命中 → 同样白等 62 秒 |
| **422** ✅ | 三者都不沾 → COSP 快速失败，body 经 `FailureKind.UPSTREAM_HTTP` 原样透传给下游 |

另有一条更本质的理由：**mock 自己的「不支持」不是上游语义**。真实 OpenAI / Anthropic
几乎不用 422，因此日志里一出现 422 就知道是 mock 在说话，不会与「上游真的报错」混淆。

> **例外**：`anthropic-version` 缺失时仍回 **400**（与官方 API 一致），尽管它同样会触发
> 重试。那是**忠实复现真实上游** —— COSP 对真实 Anthropic 也会白等，不该被 mock 掩盖。

### 未知场景名不是错误

请求带一个未登记的场景名时，**回落到 `baseline-normal` 并打一条日志** ——
便于随手用一个名字试探连通性。但如果那个名字**在别的协议上有登记**，
则回 422 并指出该去哪个端点（那几乎肯定是打错了协议）。

## 5. 场景清单

图例：**CH** = `/chat/completions`，**MS** = `/messages`；`S` = 流式，`J` = 非流式。

### baseline —— 常规形态

| 场景 | CH | MS | 期望 |
|---|:--:|:--:|---|
| `baseline-normal` | S J | S J | RECEIVED → CONNECTED → CHUNK → COMPLETED，用量入库 |
| `baseline-tool-call` | S J | S J | 工具调用原样透传，下游能执行工具 |
| `baseline-thinking-text` | — | S J | 思考与正文交替，两段都到达下游 |
| `baseline-content-first` | S J | S J | 正文在前时工具仍被执行（顺序基准） |
| `baseline-tool-first` | S J | S J | 工具在前时工具仍被执行 |

> **顺序场景的来历**：`baseline-*-first` 这对场景来自已合并的 `mock-toolorder`。
> 它的调研结论是**顺序已被排除** —— 四种组合各跑两轮，Copilot 全部正常解析并执行工具。
> 因此现在的作用是**回归基准**：已知正常的形态仍然正常。
>
> 工具参数刻意切成**两片**且切点落在值内部；`filePath` / `startLine` / `endLine`
> 在 `read_file` 的 schema 里都是 required —— **曾因只发 `{"filePath":"README.md"}`
> （缺字段 + 相对路径）**，导致下游必然以参数校验失败告终、把「顺序」这个唯一变量彻底淹没。
> 用 `MOCK_UPSTREAM_FILE` 可指定一个存在的绝对路径。

### blank —— 期望判空并兜底重试

| 场景 | CH | MS | 期望 |
|---|:--:|:--:|---|
| `blank-empty-content` | S J | S J | 信封合法但载荷为空 → 判空重试，耗尽后放行 |
| `blank-empty-body` | S J | S J | 200 + 0 字节 body → 同上 |
| `blank-empty-choices` | J | — | `choices: []` → 同上（流式下 `choices` 逐帧到达，空数组不是有意义的一档） |
| `blank-zero-usage` | S J | S J | 空内容 + 全 0 usage → **usage 不是判据**，仍应判空 |

### pass —— 期望放行（对照组）

**这一组最容易漏**：它们验证「不该重试的没重试」。若这些也开始重试，说明判定把
「无正文」误当成了「空」—— 而那时空响应兜底本身看起来是正常的。

| 场景 | CH | MS | 期望 |
|---|:--:|:--:|---|
| `pass-tool-call` | S J | S J | 纯工具调用是实质载荷，零重试、快速返回 |
| `pass-reasoning-only` | S J | — | 纯思考链；非流式侧另应触发 reasoning fallback（`content` 被填入） |
| `pass-thinking-only` | — | S J | 纯 thinking 块，零重试 |
| `pass-malformed` | S J | S J | 残缺 JSON → **解析失败保守放行**，原样透传 |
| `pass-sse-body` | J | — | 无视 `stream=false` 仍回 SSE，文本原样透传 |

### retry —— 期望自动重试

| 场景 | CH | MS | 期望 |
|---|:--:|:--:|---|
| `retry-5xx` | S J | S J | RETRYING → 按预算 |
| `retry-429` | S J | S J | 同上，且响应带 `Retry-After: 5`，日志应显示它 |
| `retry-truncated` | S J | S J | 声明 Content-Length 后只写一半就断 socket → 网络类失败，日志状态码 `-1` |
| `retry-recover` | S J | S J | 前 2 次 500、第 3 次正常 → 重试中途成功 |

### fail —— 期望快速失败

| 场景 | CH | MS | 期望 |
|---|:--:|:--:|---|
| `fail-401` | S J | S J | FAILED，**无 RETRYING**（401/403 是确定性错误，重试只是白等） |

### phase —— 相位与计数

| 场景 | CH | MS | 期望 |
|---|:--:|:--:|---|
| `phase-done-early` | S | S | 发完结束标记但保持 TCP 不关 → COMPLETED **不等**连接关闭（Layer 1） |
| `phase-eof-fallback` | S | S | 发完内容直接关连接、无结束标记 → TCP 关闭兜底完成（Layer 2） |
| `phase-slow` | S J | S J | 延迟 5s 后正常返回，不被误判为停滞 |
| `phase-slow-steady` | S | S | 每 3s 一个 chunk，Toast 计数持续更新 |

### cancel —— 停滞期可中断

| 场景 | CH | MS | 期望 |
|---|:--:|:--:|---|
| `cancel-hang` | S J | S J | 建立连接后不吐任何数据 → Toast 停在「等待首字」，右键可断连 → ABORTED |
| `cancel-stall` | S | S | 吐若干 chunk 后永久停滞 → 停滞期可断连 |
| `cancel-stall-delayed` | S | S | 延迟 5s 才吐首字，随后永久停滞 → **两个阶段**都可断连 |
| `cancel-stall-resume` | S | S | 停滞 35s 后恢复 → 停滞期可断连；恢复后继续 CHUNK → COMPLETED |

> `cancel-hang` 的两态机制不同：流式会先写响应头（再补一个 SSE 注释帧 flush），
> 于是 COSP 进入 **CONNECTED** 相位；非流式没有「首字」概念，连头都不写，
> 客户端处于**纯等待**状态（`Mono.firstWithSignal` 的取消路径）。

### translate —— 跳协议翻译（仅 MESSAGES）

| 场景 | 期望 |
|---|---|
| `translate-tool-split-args` | 参数切成 30+ 片、转义序列被拦腰截断 → 拼接后为合法 JSON，index 恒为 0 |
| `translate-tool-multi-split` | 三工具各分片、block index 从 1 起 → tool index **稠密重映射**为 0/1/2 |
| `translate-tool-interleaved` | 两工具参数分片交错 → 各自独立拼接，互不污染 |
| `translate-tool-no-args` | 零个 `input_json_delta` → 不凭空补 `{}`，也不丢工具 |
| `translate-finish-reason` | 完整工具调用 + `stop_reason: max_tokens` → `finish_reason` 必须是 **`tool_calls`** |
| `translate-finish-reason-nonstandard` | 同上但用非标 `model_context_window_exceeded`（线上实测形态） |

> **`translate-finish-reason` 的后果最严重**：若下游收到 `length`，Copilot 会判定回答被截断、
> **放弃执行已经拿到的完整工具调用**并结束对话，**且全链路无任何报错**。
> 工具块在这里是**正常闭合**的，所以「截断」这个结论只可能来自 stop_reason 的直译。

## 6. 新旧场景名对照

合并期间两套名字都有据可查。

| 新名 | 覆盖的旧名 |
|---|---|
| `baseline-normal` | `normal`, `ns-normal`, `at-normal` |
| `baseline-tool-call` | `at-tool-use` |
| `baseline-thinking-text` | `at-thinking-text` |
| `baseline-content-first` | `to-content-first` |
| `baseline-tool-first` | `to-tool-first` |
| `blank-empty-content` | `empty-stream`, `ns-empty-content`, `at-empty-content`, `at-empty-stream` |
| `blank-empty-body` | `empty-body`, `ns-empty-body`, `at-empty-body` |
| `blank-empty-choices` | `ns-empty-choices` |
| `blank-zero-usage` | `empty-usage-zero`, `ns-empty-usage-zero`, `at-empty-usage-zero`, `at-empty-stream-usage-zero` |
| `pass-tool-call` | `empty-tool-call`, `ns-tool-call` |
| `pass-reasoning-only` | `ns-reasoning-only` |
| `pass-thinking-only` | `at-thinking-only` |
| `pass-malformed` | `ns-malformed-json`, `at-malformed-json`, `at-stream-malformed` |
| `pass-sse-body` | `ns-sse-despite-nonstream` |
| `retry-5xx` | `error-500`, `ns-error-500`, `at-error-500` |
| `retry-429` | ★ 新增（旧四个 mock 都没有 429 场景） |
| `retry-truncated` | `ns-truncated`, `at-truncated` |
| `retry-recover` | `retry-then-succeed`, `ns-retry-then-succeed`, `at-retry-then-succeed` |
| `fail-401` | `error-401`, `ns-error-401`, `at-error-401` |
| `phase-done-early` | `done-no-close` |
| `phase-eof-fallback` | `no-done-close` |
| `phase-slow` | `ns-slow-response`, `at-slow-response` |
| `phase-slow-steady` | `slow-steady` |
| `cancel-hang` | `hang-first-byte`, `ns-hang-response`, `at-hang-response` |
| `cancel-stall` | `stall-forever` |
| `cancel-stall-delayed` | `delayed-stall-forever` |
| `cancel-stall-resume` | `stall-recover` |
| `translate-tool-split-args` | `at-tool-split-args` |
| `translate-tool-multi-split` | `at-tool-multi-split` |
| `translate-tool-interleaved` | `at-tool-interleaved` |
| `translate-tool-no-args` | `at-tool-no-args` |
| `translate-finish-reason` | `at-tool-then-max-tokens` |
| `translate-finish-reason-nonstandard` | `at-tool-then-context-exceeded` |

### 这次合并顺带补上的缺口

| 缺口 | 现状 |
|---|---|
| 无 429 场景（`LIVE_TEST_MATRIX.md` §3 已记） | `retry-429` 带 `Retry-After` |
| Chat 侧无流式截断 | `retry-truncated` 覆盖 |
| Chat 侧无流式 reasoning 帧 | `pass-reasoning-only` 覆盖（`mock-nonstream` README 已记这一点） |
| 上游 Responses 协议无 mock | `protocols/responses.js` 槽位已留，路由回 422 说明 |

### 有意的行为变更

1. **`stream` 字段被认真对待**：旧 `mock-upstream` 完全忽略它、一律回 SSE。
   新实现按真实语义 —— 缺省与 `false` 走非流式。影响很小（Copilot 恒发 `stream:true`），
   但它让「哪个场景走哪条路」从隐式变显式。
   > 旧 `mock-nonstream` 的「`stream=true` 就回 400」这道免费断言随之消失 ——
   > 真正的组合不支持现在由 422 表达。
2. **不再有 `mock:nonstream` / `mock:anthropic` / `mock:toolorder` 三个 script**，
   端口 8082 / 8083 / 8084 不再监听。**已存的供应商配置需改成 8081**。

## 7. 自检脚本

`smoke-test.js` 遍历全部「场景 × 协议 × 模式」组合，报告状态码与耗时，
并断言机械不变式（不支持的组合必须回 422、场景故意返回的错误码不算故障）。

```bash
node smoke-test.js                    # 26 个场景（跳过耗时的）
node smoke-test.js --slow             # 全部 33 个
node smoke-test.js --port 8081        # 换端口
node smoke-test.js --timeout 5000     # 换单请求超时
```

输出示例（`·` 200 / `4` 422 / `E` 场景预期的错误码 / `T` 超时 / `!` 需排查）：

```
baseline-normal                      ·200     5ms  ·200     4ms  ·200     6ms  ·200     8ms
translate-tool-split-args            4422     3ms  4422     3ms  ·200     5ms  ·200     3ms
retry-429                            E429     3ms  E429     4ms  E429     4ms  E429     4ms
```

> **它为什么存在**：mock 不参与自动化测试，合并重构**没有回归网兜底**。
> 这个脚本是「逐场景对拍」的机械化版本 —— 它不判断内容对不对（那需要人看），
> 只保证「每一格都被走到过、且没有意外的状态码」。

`--slow` 会把耗时场景（`cancel-*` / `phase-slow*` / `phase-done-early`）也算进来，
那些在脚本里只会等到超时 —— 它们本来就该在 UI 上手工观察取消行为。

## 8. 可调参数

| 环境变量 | 作用 | 默认 |
|---|---|---|
| `MOCK_UPSTREAM_PORT` | 监听端口（`MOCK_PORT` 兼容旧用法） | `8081` |
| `MOCK_UPSTREAM_INTERVAL_MS` | 顺序场景（`baseline-*-first`）的帧间隔，调大可在 UI 上肉眼看清顺序 | `120` |
| `MOCK_UPSTREAM_FILE` | 顺序场景的工具目标文件（**必须是绝对路径**） | 仓库根的 `README.md` |

其余时长常量在 `lib/transport.js` 与各 `protocols/*.js` 顶部的「可调参数」区。

## 9. 相关文档

| 主题 | 文件 |
|---|---|
| 下游嗅探 mock | [`../mock-cosp/README.md`](../mock-cosp/README.md) |
| 实机验证矩阵（测什么、看什么） | [`../../docs/LIVE_TEST_MATRIX.md`](../../docs/reference/LIVE_TEST_MATRIX.md) |
| 跳协议契约（请求侧） | [`../../docs/PROTOCOL_TRANSLATION_CONTRACT.md`](../../docs/features/protocol-translation/chat-messages/request-contract.md) |
| 跳协议契约（响应侧） | [`../../docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md`](../../docs/features/protocol-translation/chat-messages/response-contract.md) |
| 上游适配史与取舍 | [`../../docs/PROVIDER_ADAPTATIONS.md`](../../docs/features/provider-integration/ADAPTATIONS.md) |
| 出站与入站鉴权头设计 | [`../../docs/AUTH_HEADER_ASSEMBLY.md`](../../docs/architecture/AUTH_HEADER_ASSEMBLY.md) |

# COSP 模拟上游供应商（Anthropic）

一个零依赖的 Node 脚本，提供 Anthropic Messages API 的 `POST /v1/messages`，
通过**模型名**触发正常、空响应、传输截断、错误码、重试中途成功等场景，
用于在真实 HTTP / SSE 连接下验证 COSP 的 Anthropic 上游链路。

## 与另外四个 mock 的分工

| 工具 | 模拟谁 | 协议 | npm script | 端口 |
| --- | --- | --- | --- | --- |
| `mock-upstream` | 上游供应商（流式） | OpenAI | `mock:stream` | 8081 |
| `mock-nonstream` | 上游供应商（非流式） | OpenAI | `mock:nonstream` | 8082 |
| `mock-anthropic` | 上游供应商（两种模式） | **Anthropic** | `mock:anthropic` | 8083 |
| `mock-toolorder` | 上游供应商（工具调用顺序专项） | OpenAI + Anthropic | `mock:toolorder` | 8084 |
| `mock-cosp` | COSP 自己 | Ollama / OpenAI | `mock:cosp` | 11333 |

**本 mock 不按模式拆成两个服务**，与 OpenAI 侧的做法刻意不同。原因是协议差异：
OpenAI 的流式与非流式是两个形状（`chat.completion.chunk` vs `chat.completion`），
而 Anthropic 两种模式共用同一端点 `POST /v1/messages`，只是响应形态不同
（事件流 vs 单个 `message`）。拆开反而会让「同一场景在两种模式下的对照」跨文件，
而那正是最常要比对的东西。

## 启动

```bash
cd frontend
./node/npm run mock:anthropic
```

或直接：

```bash
node tools/mock-anthropic/mock-anthropic.js
```

默认监听 `http://localhost:8083`（可用 `MOCK_ANTHROPIC_PORT` 覆盖）。
端口与另外两个上游 mock 错开，三者可同时运行。

## 严格校验 anthropic-version

本 mock 会检查 `anthropic-version` 请求头，缺失或不等于 `2023-06-01` 时返回 **400**。

这是给 COSP 侧的一道免费断言：该头是 Anthropic 官方 API 的**必需头**，
若 COSP 忘记发送，在真实上游那里也会 400 —— 让 mock 同样响亮地失败，
可以在本地就暴露问题，而不是等接了真实供应商才发现。

## 在 COSP 中接入

1. 管理后台新增供应商，**Base URL** 填 `http://localhost:8083/v1`，API Key 随意（mock 不校验）。
2. 拉取模型或手动添加下表模型名并启用。

> Base URL 要带 `/v1`：当前 `GenericAnthropicChatService` 保留 Base URL 自带路径再接
> `/messages`，即最终请求 `http://localhost:8083/v1/messages`。这与 Anthropic 官方
> 及实测的中转站形态一致。

## 触发方式

Anthropic 端点**不能靠在 Copilot 里选模型触发** —— Copilot 走的是 OpenAI / Ollama 协议。
只能手打 HTTP 到 COSP，或用 Claude Desktop 之类的 Anthropic 客户端。

### 非流式（PowerShell）

```powershell
curl.exe -s -X POST http://localhost:11434/v1/messages `
  -H "Content-Type: application/json" `
  -d '{\"model\":\"[mock-anthropic] at-normal\",\"max_tokens\":1024,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}'
```

### 流式

```powershell
curl.exe -N -s -X POST http://localhost:11434/v1/messages `
  -H "Content-Type: application/json" `
  -d '{\"model\":\"[mock-anthropic] at-normal\",\"max_tokens\":1024,\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}'
```

### A2O 跨协议（下游 OpenAI、上游 Anthropic）

上面两条打的是 `/v1/messages`，**上下游都是 Anthropic，不经过翻译层**。
要验证 A2O 响应翻译，必须打 `/v1/chat/completions` —— 下游说 OpenAI，
`ProtocolDispatchManager` 才会挂上翻译器：

**PowerShell 会把内联 JSON 里的引号搞坏**，写文件再 `--data-binary` 才可靠，
且要在仓库根目录执行：

```powershell
Set-Location "d:\KaiXuan\Desktop\copilot-ollama-proxy-springboot"
'{"model":"[mock-anthropic] at-tool-split-args","stream":true,"messages":[{"role":"user","content":"go"}]}' |
  Set-Content -Encoding utf8 target\a2o-split.json
curl.exe -N -s -X POST http://localhost:11434/v1/chat/completions `
  -H "Content-Type: application/json" --data-binary "@target/a2o-split.json"
```

把 `at-tool-split-args` 换成 `at-tool-multi-split` / `at-tool-interleaved` /
`at-tool-no-args` 即可跑完四个场景。

> 该 provider 的 `supported_protocols` 必须包含 `ANTHROPIC`，否则调度器会直接
> 报「不支持该线路」而非走翻译。

### 绕过 COSP 直接打 mock

调试 mock 本身的形状时更快，但要自己带版本头：

```powershell
curl.exe -s -X POST http://localhost:8083/v1/messages `
  -H "Content-Type: application/json" -H "anthropic-version: 2023-06-01" `
  -d '{\"model\":\"at-normal\",\"max_tokens\":1024,\"messages\":[]}'
```

## 模型清单

模型名统一带 `at-` 前缀，与流式的 `normal` / 非流式的 `ns-` 都不撞 ——
`ProviderRouteResolver` 对不带前缀的模型名要求**唯一匹配**，重名会导致三边都路由不到。

| 模型名 | 行为 | 期望的 COSP 反应 |
| --- | --- | --- |
| `at-normal` | 按 `stream` 返回正常 message / 完整事件序列 | 200 原样透传 |
| `at-thinking-text` | `thinking` + `text` 两个 content block | 不兜底；usage 跨事件合并正确 |
| `at-tool-use` | 纯 `tool_use`，无正文 | **不**兜底（对照组） |
| `at-tool-split-args` | 单工具，参数切成 30+ 片 | A2O 拼接后为合法 JSON，index 恒为 0 |
| `at-tool-multi-split` | 三工具分片，block index 从 1 起 | tool index 稠密重映射为 0/1/2 |
| `at-tool-interleaved` | 两工具参数分片交错 | 两段各自独立拼接，互不污染 |
| `at-tool-no-args` | 工具无参数，零个 `input_json_delta` | 只有一个带 `name` 的帧，不凭空补 `{}` |
| `at-thinking-only` | 纯 `thinking`，无正文 | **不**兜底（对照组） |
| `at-empty-content` | 非流式 `content: []` / 流式仅控制事件 | 空响应兜底，自动重发 |
| `at-empty-usage-zero` | 空内容 + 全 0 usage | 空响应兜底（**usage 不是判据**） |
| `at-empty-body` | 非流式 200 + 0 字节 body | 兜底，耗尽后放行空 body |
| `at-empty-stream` | 流式仅 `message_start` / `message_stop` | 空响应兜底，自动重发 |
| `at-empty-stream-usage-zero` | 空事件流 + 全 0 usage | 空响应兜底 |
| `at-malformed-json` | 非流式残缺 JSON | **不**兜底（解析失败保守放行） |
| `at-stream-malformed` | 流式残缺事件 JSON | **不**兜底（同上） |
| `at-truncated` | 声明 `Content-Length` 后写一半断 socket | 网络类失败，可重试 |
| `at-error-500` | Anthropic 风格 500 | RETRYING（应重试） |
| `at-error-401` | Anthropic 风格 401 | FAILED（快速失败，不重试） |
| `at-retry-then-succeed` | 前 2 次 500，第 3 次正常 | 重试中途成功 |
| `at-hang-response` | 长时间不返回（默认 10 分钟） | 右键 Toast 断连 → ABORTED |
| `at-slow-response` | 延迟 5s 后返回 | 正常完成，仅耗时长 |

## Anthropic 事件序列的形状

流式响应是**带 `event:` 类型的事件流**，而非 OpenAI 那样的单一 chunk 序列：

```text
event: message_start
data: {"type":"message_start","message":{"id":"...","usage":{"input_tokens":24,...}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"..."}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":36}}

event: message_stop
data: {"type":"message_stop"}
```

三处与 OpenAI 的关键差异，也是 COSP 侧必须独立实现的原因：

- **`event:` 名不可省** —— Anthropic 客户端是状态机，靠事件类型驱动。
  只发 `data:` 会让 Claude Desktop 之类的客户端完全无响应且不报错。
- **流结束标记是 `message_stop`**，不是 `[DONE]`。
- **usage 分散在两个事件里** —— `input_tokens` 在 `message_start`、
  `output_tokens` 在 `message_delta`。本 mock 的 `message_delta` 刻意**只带
  `output_tokens`**（真实上游即如此），因此能验证 COSP 的合并是「非 null 覆盖」
  而非「相加」或「整份替换」：若实现写成整份替换，输入 token 会丢。

## 工具参数分片（A2O 翻译的重点）

一个 `tool_use` 块的参数 JSON 通过 `input_json_delta` 逐片发出，
**每一片单独都不是合法 JSON**：

```text
event: content_block_start
data: {"type":"content_block_start","index":2,
       "content_block":{"type":"tool_use","id":"toolu_x","name":"create_file","input":{}}}

event: content_block_delta
data: {"type":"content_block_delta","index":2,
       "delta":{"type":"input_json_delta","partial_json":"{\"filePath\": \"d:\\\\a"}}

event: content_block_delta
data: {"type":"content_block_delta","index":2,
       "delta":{"type":"input_json_delta","partial_json":".py\", \"content\": \"..."}}
```

下游 OpenAI 客户端靠拼接 `delta.tool_calls[].function.arguments` 还原完整参数，
因此翻译层必须保证每一片都落在**同一个** `tool_calls[].index` 上且顺序不变。

**为什么需要这组 mock 场景**：真实上游对「切不切、怎么切」没有共识。
MiMo 实测把 6269 字符的参数塞在**单个** `input_json_delta` 里一次发完，
Anthropic 官方则会切成多片。近期全部带工具调用的 A→O 日志里
`maxDeltasPerBlock` 恒为 1 —— 也就是说**光靠 MiMo 压不到多片路径**，
提示词写得再长也没用。这四个场景是本地唯一能触发该形态的手段。

四个场景各自压的东西：

| 模型名 | 形状 | 压什么 |
| --- | --- | --- |
| `at-tool-split-args` | 单工具，参数按 12 字符切成 30+ 片 | 拼接正确性；转义序列跨片边界 |
| `at-tool-multi-split` | 三工具，block index **1/2/3**（0 给 thinking） | 双索引域重映射为稠密 0/1/2 |
| `at-tool-interleaved` | 两工具的分片交错发送 | index 映射是否依赖隐式「当前活跃块」 |
| `at-tool-no-args` | 零个 `input_json_delta` | 不凭空补 `{}`，也不丢掉整个工具调用 |

三处刻意的设计：

- **切片不避开转义序列**。`\"` 与 `\uXXXX` 被拦腰截断，是最容易暴露
  「谁在中途试图解析单片」的形状。正确实现应把每片当作不透明字节原样转发。
- **`at-tool-multi-split` 把工具挤到 block index 1 起**。直接透传 block index
  会产出从 1 开始的稀疏 `tool_calls` 数组，下游拼不出第一个工具。
- **交错场景是实现健壮性探针，不是协议合规性测试**。Anthropic 官方是块顺序完成的，
  交错在实践中未观测到；但「按 block index 查表路由」本应天然支持它，
  若某个实现用了「当前活跃块」这类隐式状态，交错会立刻把两个工具的参数搅在一起。

非流式对照（同名模型 + `stream: false`）返回完整的 `input` 对象，
**没有分片概念**。它的用途是提供基准：流式拼接的结果必须与非流式的
`input` 序列化后一致。

### 验证方法

打完之后在管理后台的调用日志里展开该行，用「块显示」看两栏对照，
或直接取 `chunks` 字段做拼接校验：把同一 `tool_calls[].index` 的
`arguments` 按帧顺序连起来，应能 `JSON.parse` 成功，且等于上游
所有 `partial_json` 的顺序拼接。

## 空响应兜底的判定口径

与 OpenAI 侧共享**类别定义**，仅取值路径不同：没出现过正文（`text`）、
思考链（`thinking` / `redacted_thinking`）、工具调用（`tool_use`）任一者即判空。

- `at-tool-use` / `at-thinking-only` 是**对照组**，它们没有正文但有实质载荷，
  不该被重发。若它们也触发重试，说明判定把「无正文」误当成了「空」。
- `at-empty-usage-zero` 里的全 0 usage **不是判据**，只是伴随现象。
- `at-malformed-json` / `at-stream-malformed` 检验「解析失败保守放行」：
  宁可放行一个没见过的格式，也不要因为结构陌生就判空重试。
- 兜底重发与 429 / 5xx / 网络中断**共用同一份预算**（`RetryPolicyService` 的
  `retry_max_attempts`，与 OpenAI 侧同一个配置项）。`at-retry-then-succeed` 验证这一点。

## 流式兜底的一处行为差异

Anthropic 流式**不设** OpenAI 侧那种「开闸前缓存不下发」的 gate：
客户端是状态机，扣住 `message_start` 会让它无法初始化。
因此判定改为「边下发边记录是否见过实质载荷」，整轮结束才判空 ——
代价是空响应那一轮的事件已经流到下游了。这是有意的取舍。

## 可调参数

`mock-anthropic.js` 顶部「可调参数」区：

- `PORT` / `MOCK_ANTHROPIC_PORT`：监听端口（默认 8083）
- `NORMAL_DELTA_INTERVAL_MS`：正常流的 delta 间隔（默认 50ms）
- `HANG_RESPONSE_MS`：`at-hang-response` 挂起时长（默认 10 分钟）
- `SLOW_RESPONSE_MS`：`at-slow-response` 延迟（默认 5s）
- `RETRY_SUCCESS_ATTEMPT` / `RETRY_RESET_MS`：重试场景的成功次序与计数器清零时长
- `REQUIRED_ANTHROPIC_VERSION`：要求的版本头值

## 说明

- 纯 Node 内置 `http` 模块，零依赖，不需要 `npm install`。
- 与另外三个 mock 刻意不共享工具模块：可共享的只有 `log` 与 `readJsonBody` 十几行，
  而事件构造在各协议下完全不同。抽公共模块会牺牲「单文件零依赖、拷走就能跑」的特性。
- 未知模型名一律按 `at-normal` 处理，方便随手测试。
- 手动验证工具，不参与自动化测试。

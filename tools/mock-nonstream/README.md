# COSP 模拟上游供应商（非流式）

一个零依赖的 Node 脚本，提供 OpenAI 兼容的 `/v1/models` 与 `/v1/chat/completions` 端点，
**只服务 `stream=false` 的请求**，通过模型名触发各种非流式上游行为，
用于在真实 HTTP 连接下物理复现 COSP 非流式路径的各种边界（空响应 / 残缺 JSON / 截断 / 错误码等）。

## 与另外两个 mock 的分工

| 工具 | 模拟谁 | 抓什么 | 启动 |
| --- | --- | --- | --- |
| `mock-upstream` | 上游供应商（**流式**） | COSP → 供应商的出站行为，SSE 帧序列 | `npm run mock:stream` |
| `mock-nonstream` | 上游供应商（**非流式**） | COSP → 供应商的出站行为，单个 JSON 响应 | `npm run mock:nonstream` |
| `mock-cosp` | COSP 自己 | Copilot → COSP 的入站请求头 | `npm run mock:cosp` |

为什么非流式要单独一个服务而不是在流式 mock 里按 `stream` 分支：**两种模式的病态形状几乎不重叠**。
流式的「吐了几个 chunk 后停住」在非流式里根本不存在（响应是一次性的，没有「中途」），
反过来「0 字节 body」「JSON 截断」「无视 `stream=false` 回 SSE」也是非流式独有。
合在一起会让两套场景清单互相污染，各自的 switch 里塞满对另一模式无意义的 case。

## 启动

沿用前端的启动方式（复用捆绑的 Node，无需全局安装）：

```bash
cd frontend
./node/npm run mock:nonstream
```

或直接用 Node 跑：

```bash
node tools/mock-nonstream/mock-nonstream.js
```

默认监听 `http://localhost:8082`（可用环境变量 `MOCK_NONSTREAM_PORT` 覆盖）。
端口特意与流式 mock 的 8081 错开，两个 mock 可同时运行。

> 端口选择注意：不要用 9090。它是 Clash / mihomo 等代理软件的 external-controller 默认端口，
> 请求会被代理控制面接管并返回 404，而非到达本 mock。

## 严格校验 stream

本 mock 与另外两个最大的行为差异：**收到 `stream: true` 直接返回 400**，并在终端打印警告。

`mock-upstream` 和 `mock-cosp` 都静默忽略 `stream` 字段，照样返回 SSE ——
这会让「拿它们验证非流式」看起来能跑其实无效。这里让它响亮地失败，
等于给 COSP 侧免费加了一道「有没有误发流式」的断言：
若 COSP 本该发非流式却发了 `stream=true`，你会立刻看到 400 而不是一个假通过。

## 怎么触发（重点）

非流式**不能靠在 Copilot 里选模型触发** —— Copilot 基本只发流式请求。
只能手打 HTTP 到 COSP，且 `GatewayAuthFilter` 拦 `POST /v1/chat/completions`，必须带下游 API Key。

### 1. 在 COSP 中接入

1. 打开 COSP 管理后台，新增供应商，**Base URL** 填 `http://localhost:8082`，API Key 随意（mock 不校验）。
2. 拉取模型或手动添加下表中的模型名并启用。
3. 记下该供应商的 `provider_key`，下面请求里用 `[provider-key] ns-xxx` 形式精确路由。
   模型名已带 `ns-` 前缀，与流式 mock 的场景名不撞，不带前缀也能唯一匹配。

### 2. 直接打 COSP（PowerShell）

```powershell
$key = '你的下游 API Key'
curl.exe -s -X POST http://localhost:11434/v1/chat/completions `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer $key" `
  -d '{\"model\":\"ns-normal\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}'
```

### 3. 也可以绕过 COSP 直接打 mock

调试 mock 本身的形状时更快，不需要 API Key：

```powershell
curl.exe -s -X POST http://localhost:8082/v1/chat/completions `
  -H "Content-Type: application/json" `
  -d '{\"model\":\"ns-normal\",\"stream\":false,\"messages\":[]}'
```

## 模型清单

| 模型名 | 行为 | 期望的 COSP 反应 |
| --- | --- | --- |
| `ns-normal` | 完整 `message.content` + 真实 usage | 200 透传，用量入库 |
| `ns-empty-content` | 200 + 合法 JSON，`content` 为空串 | 空响应兜底，自动重发 |
| `ns-empty-usage-zero` | 空正文 + 全 0 usage | 空响应兜底（全 0 usage 不是判据） |
| `ns-empty-choices` | 200 + `choices` 为空数组 | 空响应兜底 |
| `ns-empty-body` | 200 + **0 字节** body | 空响应兜底，耗尽后放行空 body |
| `ns-tool-call` | 纯工具调用，无正文 | **不**兜底（对照组） |
| `ns-reasoning-only` | 只有 `reasoning_content` 无 `content` | **不**兜底（对照组），且应触发 reasoning fallback |
| `ns-malformed-json` | 200 + 残缺 JSON 文本 | **不**兜底（解析失败保守放行） |
| `ns-truncated` | 声明 `Content-Length` 后只写一半就断 socket | 网络类失败，可重试 |
| `ns-sse-despite-nonstream` | 无视 `stream=false`，回 `text/event-stream` | 200 原样透传 SSE 文本，**不**兜底（`toEntity(String)` 收得下任意文本类型，JSON 解析失败按保守放行） |
| `ns-error-500` | 返回 500 | RETRYING（应重试） |
| `ns-error-401` | 返回 401 | FAILED（快速失败，不重试） |
| `ns-retry-then-succeed` | 前 2 次 500，第 3 次正常 | 重试中途成功 |
| `ns-hang-response` | 长时间不返回（默认 10 分钟） | 右键 Toast 断连 → ABORTED |
| `ns-slow-response` | 延迟 5s 后返回完整响应 | 正常完成，仅耗时长 |

## 非流式响应的形状

与流式 chunk 的三处结构性差异，也正是 COSP 侧判定器必须另开入口的原因：

- `object` 是 `chat.completion` 而非 `chat.completion.chunk`；
- 载荷在 `choices[].message` 而非 `choices[].delta`；
- `usage` 是**顶层字段**而非尾帧夹带。

```json
{
  "id": "chatcmpl-mock-ns-1786444800000",
  "object": "chat.completion",
  "created": 1786444800,
  "model": "ns-normal",
  "choices": [
    { "index": 0, "message": { "role": "assistant", "content": "……" }, "finish_reason": "stop" }
  ],
  "usage": { "prompt_tokens": 26, "completion_tokens": 41, "total_tokens": 67 }
}
```

好消息是 `message` 与 `delta` 的**字段名完全相同**（`content` / 5 个 reasoning 别名 / `tool_calls`），
差别只在外层那个 key。COSP 侧因此共用同一个内层判定方法（`payloadHasContent`），
两条路径的口径一致不靠纪律维持而靠同一份代码。

## 已验证的行为基线

一轮实测的结论，可作为回归对照。**关键在于三个对照组必须快速返回** ——
它们若也开始重试，说明判定把「无正文」误当成了「空」：

| 场景 | 实测 | 含义 |
| --- | --- | --- |
| `ns-normal` | 200，59ms | 主路径正常 |
| `ns-tool-call` | 200，71ms，零重试 | 对照组：工具调用是实质载荷 |
| `ns-reasoning-only` | 200，59ms，零重试，`content` 已被思考内容填充 | 对照组 + fallback 生效 |
| `ns-malformed-json` | 200，35ms，零重试，原样透传 | 解析失败保守放行 |
| `ns-sse-despite-nonstream` | 200，6ms，零重试，SSE 文本原样透传 | 同上（SSE 文本解析必然失败） |
| `ns-empty-content` | 按预算重试，Toast 显示「正在重试（第 N 次）」 | 兜底生效 |
| `ns-empty-body` | 重试满预算 → 放行空 body（200 / 0 字节） | 兜底 + 耗尽放行 |
| `ns-truncated` | 按预算重试，日志状态码 `-1` | 传输截断属可重试网络失败 |

> `ns-truncated` 在日志里显示为「空响应」，那是 `-1` 占位值的展示问题（多种非 HTTP 异常共用），
> 不代表它走了空响应判定。排查时看响应头是否为 `{}` 来区分。

## 空响应兜底的判定口径

与流式完全一致：**一轮上游往返里从未出现过实质载荷**（正文 `content`、思考链 5 个兼容字段之一、
工具调用 `tool_calls`）即判为空响应。切一下 `stream` 开关结论就不同，那才是真的怪。

- `ns-empty-usage-zero` 里的全 0 usage **不是判据**，只是伴随现象。
- `ns-tool-call` / `ns-reasoning-only` 是**对照组**，有实质载荷，不该被重发。
- `ns-malformed-json` 检验「解析失败保守放行」：宁可放行一个没见过的格式，
  也不要因为结构陌生就判空重试。
- 兜底重发与 429 / 5xx / 网络中断**共用同一份预算**（`buildRetrySpec`），不是第二套重试实现。
  `ns-retry-then-succeed` 正是用来验证这一点的。

## 可调参数

所有时长常量在 `mock-nonstream.js` 顶部的「可调参数」区：

- `PORT` / `MOCK_NONSTREAM_PORT`：监听端口（默认 8082）
- `HANG_RESPONSE_MS`：`ns-hang-response` 挂起时长（默认 10 分钟）
- `SLOW_RESPONSE_MS`：`ns-slow-response` 延迟（默认 5s）
- `RETRY_SUCCESS_ATTEMPT` / `RETRY_RESET_MS`：`ns-retry-then-succeed` 的成功次序与计数器清零时长

## 说明

- 纯 Node 内置 `http` 模块，零依赖，不需要 `npm install`。
- 与流式 mock 刻意不共享工具模块：可共享的只有 `log` 和 `readJsonBody` 十几行，
  而帧构造在两种模式下根本不同。抽公共模块会牺牲「单文件零依赖、拷走就能跑」的特性。
- 未知模型名一律按 `ns-normal` 处理，方便随手测试。
- 手动验证工具，不参与自动化测试。

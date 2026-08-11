# COSP 模拟上游供应商

一个零依赖的 Node 脚本，提供 OpenAI 兼容的 `/v1/models` 与 `/v1/chat/completions` 端点，
通过**模型名**触发各种上游行为，用于在真实 HTTP + SSE 连接下物理复现 COSP 的各种调用生命周期
Toast 状态（正常 / 卡首字 / 停滞 / 不关连接 / 错误码等），而不依赖真实上游 API。

## 启动

沿用前端的启动方式（复用捆绑的 Node，无需全局安装）：

```bash
cd frontend
./node/npm run mock:stream
```

或直接用 Node 跑：

```bash
node tools/mock-upstream/mock-upstream.js
```

默认监听 `http://localhost:8081`（可用环境变量 `MOCK_PORT` 覆盖）。启动后终端会打印可用模型清单，
每个请求的连接、首字、chunk、结束等阶段都会打日志，状态一目了然。

> 端口选择注意：不要用 9090。它是 Clash / mihomo 等代理软件的 external-controller 默认端口，
> 请求会被代理控制面接管并返回 404，而非到达本 mock。若 8081 也被占用，用 `MOCK_PORT` 换一个空闲端口。

## 在 COSP 中接入

1. 打开 COSP 管理后台。
2. 新增一个供应商，**Base URL** 填 `http://localhost:8081`，API Key 随意（mock 不校验）。
3. 拉取模型或手动添加下表中的模型名，启用后即可在 Copilot 里选中对应模型触发该场景。

## 模型清单（每个映射一种边界场景）

| 模型名 | 行为 | 验证的 Toast 状态 |
| --- | --- | --- |
| `normal` | 正常流式，完整 chunk + `[DONE]` + 关连接 | RECEIVED → CONNECTED → CHUNK → COMPLETED 全链路 |
| `hang-first-byte` | CONNECTED 后卡住不吐首字（默认 10 分钟） | 等待首字（右键断连 → ABORTED） |
| `stall-recover` | 吐若干 chunk 后停滞 35s，再继续到结束 | 停滞期间右键可断连；恢复后继续 CHUNK → COMPLETED |
| `stall-forever` | 吐若干 chunk 后永久停滞（不关连接） | 停滞期间右键断连 → ABORTED 静默断连 |
| `delayed-stall-forever` | 延迟 5s 才吐首字，随后吐若干 chunk 并永久停滞 | 等待首字与产出后停滞两个阶段均可右键断连 |
| `done-no-close` | 发完内容 + `[DONE]`，但保持 TCP 不关闭 | Layer 1（`[DONE]` 触发 COMPLETED，不等连接关闭） |
| `no-done-close` | 发完内容后直接关连接，不发 `[DONE]` | Layer 2（TCP 关闭兜底完成） |
| `error-500` | 返回 500 | RETRYING（COSP 应重试） |
| `error-401` | 返回 401 | FAILED（快速失败，不重试） |
| `slow-steady` | 每 3s 一个 chunk，持续较久 | 长连接下 Toast 持续更新 chunk 计数 |
| `retry-then-succeed` | 前 2 次请求返回 500，第 3 次正常回复 | 异常重试中途成功（RETRYING → CONNECTED → COMPLETED） |
| `empty-stream` | 200 + 仅 role/finish/`[DONE]`，无内容 | 空响应兜底（RETRYING，自动重发） |
| `empty-usage-zero` | 空流但带全 0 usage | 空响应兜底（全 0 usage 仍算空，自动重发） |
| `empty-tool-call` | 纯工具调用流 | **不**触发兜底（对照场景：工具调用不算空） |
| `empty-body` | 200 但响应体 0 帧 | 空响应兜底（空 body 也判空，自动重发） |

### 空响应兜底的判定口径

四个 `empty-*` 场景验证的是同一条规则：**一轮上游往返里从未出现过带实质载荷的 delta**
（正文 `content`、思考链 5 个兼容字段之一、工具调用 `tool_calls`），即判为空响应。

- `empty-usage-zero` 里的全 0 usage **不是判据**，只是伴随现象 —— 该场景之所以被判空，
  纯粹因为它本来就没有内容。不少中转站正常回复也不吐 usage 或吐全 0，把 usage 当条件会误伤它们。
- `empty-tool-call` 是**对照组**：它没有正文，但有工具调用，因此不该触发兜底。
  若这个场景也被重发，说明判定逻辑把「无正文」误当成了「空」。
- 兜底重发与 429 / 5xx / 网络中断**共用同一份 5 次预算**（`buildRetrySpec`），
  不是第二套重试实现。耗尽后把最后一轮的帧原样放行给下游。

判定实现见 `UpstreamChunkContentDetector`，接线点在 `AbstractUpstreamChatService#chatCompletionStream`
的 gate（`retryWhen` 内侧）。非流式走同一份内层判定但另有入口，场景见
[tools/mock-nonstream/README.md](../mock-nonstream/README.md)。

## 可调参数

所有时长常量在 `mock-upstream.js` 顶部的“可调参数”区，直接改即可：

- `PORT` / `MOCK_PORT`：监听端口（默认 8081；避开 9090，那是 Clash/mihomo 控制面默认端口）
- `HANG_FIRST_BYTE_MS`：`hang-first-byte` 卡首字时长（默认 10 分钟）
- `STALL_RECOVER_MS`：`stall-recover` 停滞时长（默认 35s）
- `STALL_AFTER_CHUNKS`：停滞前先吐的 chunk 数
- `NORMAL_CHUNK_INTERVAL_MS` / `NORMAL_CHUNK_COUNT`：正常流的间隔与数量
- `SLOW_STEADY_INTERVAL_MS` / `SLOW_STEADY_CHUNK_COUNT`：slow-steady 的间隔与数量

## 说明

- 纯 Node 内置 `http` 模块，零依赖，不需要 `npm install`。
- 挂起类场景（`hang-first-byte` / `stall-forever`）用异步定时驱动，挂着的连接几乎零成本，可同时开多个观察多并发 Toast 堆叠。
- 未知模型名一律按 `normal` 处理，方便随手测试。

# COSP 最小实现 Mock —— 下游请求头嗅探器

一个零依赖的 Node 脚本，模拟 **COSP 自己**对外的最小契约端点，用于验证
**下游客户端把本服务当作 Ollama / OpenAI / Anthropic 端点时，发起的聊天请求会不会带 `Authorization` 头**。

这个结论直接决定「下游 API Key」能不能走标准 Bearer 认证方案。

> 与其它 mock 的区别：
> - `mock-upstream` 模拟**上游供应商（流式）**，供 COSP 转发请求过去（抓 COSP → 供应商的出站行为，SSE 帧序列）。
> - `mock-nonstream` 模拟**上游供应商（非流式）**，供 COSP 转发请求过去（抓 COSP → 供应商的出站行为，单个 JSON 响应）。
> - `mock-anthropic` 模拟**上游 Anthropic 供应商**，供 COSP 的 Messages 线路转发过去。
> - `mock-cosp` 模拟 **COSP 自己**，供 Copilot / Claude 系客户端直接连接（抓下游 → COSP 的入站请求头）。

## 启动

复用前端捆绑的 Node（无需全局安装）：

```bash
cd frontend
./node/npm run mock:cosp
```

或直接用 Node 跑：

```bash
node tools/mock-cosp/mock-cosp.js
```

默认监听 `http://localhost:11333`（可用环境变量 `MOCK_COSP_PORT` 覆盖）。

## 用法

1. 把下游客户端指向 `http://localhost:11333`：
   - **Copilot**：把 Ollama 端点指向该地址（Copilot 支持自定义 Ollama 端点，且可配置多个），聊天打 `/v1/chat/completions`。
   - **Claude 系客户端**：把 `ANTHROPIC_BASE_URL` 指向该地址，聊天打 `/v1/messages`。
   - **手工验证**：用 `curl` 直接打任一聊天端点。
2. 观察**模型发现阶段**的请求头：Copilot 会先打 `GET /api/tags`、`POST /api/show`。
3. 在模型选择器里选中 `mock-sniffer`（或任意模型名，mock 只把它回显进响应），发起一次对话。
4. 看终端每次请求末尾的 **`★ 认证相关头`** 提示：
   - 若出现 `authorization` → 客户端能带 Bearer，**方案 2（config 表单 Key + 标准 Bearer 认证）可行**。
   - 若始终缺失 → 需要退到 query param 或路径前缀等替代方案。
5. **额外验证**：可以尝试在客户端配置里手动添加 API Key / 自定义 header 字段，再观察请求头是否随之出现，确认「手动加字段能否透传」。

## 端点

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/api/version` | Ollama 版本探测 |
| `GET` | `/api/tags` | 模型发现，返回一个 `mock-sniffer` 模型 |
| `POST` | `/api/show` | 模型能力查询 |
| `GET` | `/v1/models` | OpenAI 模型列表 |
| `POST` | `/v1/chat/completions` | Chat 线路，顺序回复几个 chunk + `[DONE]` |
| `POST` | `/v1/messages` | Messages 线路，按 Anthropic 事件序列回复 |
| `POST` | `/v1/responses` | Responses 线路，按 OpenAI Responses 事件序列回复 |

### 三个聊天端点的行为

都只做「**收下 → 打印 → 按该协议原样回包**」，**不做任何协议翻译** —— 本 mock 的职责是
嗅探下游真实发出的报文，翻译会把要观察的对象本身改动掉。各端点按各自协议应答，
下游拿到合法响应才会走完流程；否则它会在请求头打印出来之前就先报协议错断开。

`stream` 显式为 `true` 时走 SSE，否则返回单个 JSON 对象（与真实 API 的默认值一致）。
非流式分支主要给 `curl` 手工验证用，Copilot 与 Claude 系客户端默认都走流式。

| 端点 | 流式事件序列 |
| --- | --- |
| `/v1/chat/completions` | N × `data:` chunk + `finish_reason: stop` + `[DONE]` |
| `/v1/messages` | `message_start` → `content_block_start` → N × `content_block_delta` → `content_block_stop` → `message_delta` → `message_stop` |
| `/v1/responses` | `response.created` → `response.in_progress` → `response.output_item.added` → `response.content_part.added` → N × `response.output_text.delta` → `response.output_text.done` → `response.content_part.done` → `response.output_item.done` → `response.completed` |

Responses 那串事件**一帧都不能省**：中间每一帧都携带下游初始化 UI 所需的结构，
缺 `response.created` 时客户端拿不到 response id 就无法开始渲染；少了 `response.completed`
这个终态，下游只能等 TCP 关闭才收尾。

## 可调参数

`mock-cosp.js` 顶部「可调参数」区：

- `PORT` / `MOCK_COSP_PORT`：监听端口（默认 11333）
- `MODEL_NAME`：暴露的 mock 模型名（默认 `mock-sniffer`）
- `REPLY_TOKENS`：聊天回复的 chunk 内容片段（三个端点共用同一份文本）
- `CHUNK_INTERVAL_MS`：chunk 间隔（默认 120ms）

## 说明

- 纯 Node 内置 `http` 模块，零依赖，不需要 `npm install`。
- 每个请求都会**完整打印方法、路径、全部请求头**，POST 还会**完整打印请求体**（不截断 ——
  system 提示词、tools 定义、思考参数都藏在请求体里，截断会让「谁发了什么」变成猜谜）。
- 认证相关头（`authorization` / `api-key` / `x-api-key` / `token` 等）会单独高亮，便于一眼定位。

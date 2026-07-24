# COSP 最小实现 Mock —— 下游请求头嗅探器

一个零依赖的 Node 脚本，模拟 **COSP 自己**对外的最小契约端点，用于验证
**GitHub Copilot 把本服务当作 Ollama 端点时，发起 OpenAI 聊天请求会不会带 `Authorization` 头**。

这个结论直接决定「下游 API Key」能不能走标准 Bearer 认证方案。

> 与 `tools/mock-upstream` 的区别：
> - `mock-upstream` 模拟**上游供应商**，供 COSP 转发请求过去（抓 COSP → 供应商的出站行为）。
> - `mock-cosp` 模拟 **COSP 自己**，供 Copilot 直接连接（抓 Copilot → COSP 的入站请求头）。

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

1. 在 VS Code 中把 Copilot 的 **Ollama 端点**指向 `http://localhost:11333`。Copilot 支持自定义 Ollama 端点，且可配置多个。
2. 观察**模型发现阶段**的请求头：Copilot 会先打 `GET /api/tags`、`POST /api/show`。
3. 在模型选择器里选中 `mock-sniffer`，发起一次对话，触发 `POST /v1/chat/completions`。
4. 看终端每次请求末尾的 **`★ 认证相关头`** 提示：
   - 若出现 `authorization` → 客户端能带 Bearer，**方案 2（config 表单 Key + 标准 Bearer 认证）可行**。
   - 若始终缺失 → 需要退到 query param 或路径前缀等替代方案。
5. **额外验证**：可以尝试在 VS Code 的 Ollama 供应商配置里手动添加 API Key / 自定义 header 字段，再观察请求头是否随之出现，确认「手动加字段能否透传」。

## 端点

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/api/version` | Ollama 版本探测 |
| `GET` | `/api/tags` | 模型发现，返回一个 `mock-sniffer` 模型 |
| `POST` | `/api/show` | 模型能力查询 |
| `GET` | `/v1/models` | OpenAI 模型列表 |
| `POST` | `/v1/chat/completions` | 顺序回复几个 chunk + `[DONE]` + 关连接 |

## 可调参数

`mock-cosp.js` 顶部「可调参数」区：

- `PORT` / `MOCK_COSP_PORT`：监听端口（默认 11333）
- `MODEL_NAME`：暴露的 mock 模型名（默认 `mock-sniffer`）
- `REPLY_TOKENS`：聊天回复的 chunk 内容片段
- `CHUNK_INTERVAL_MS`：chunk 间隔（默认 120ms）

## 说明

- 纯 Node 内置 `http` 模块，零依赖，不需要 `npm install`。
- 每个请求都会**完整打印方法、路径、全部请求头**，POST 还会打印请求体（超 500 字截断）。
- 认证相关头（`authorization` / `api-key` / `token` 等）会单独高亮，便于一眼定位。

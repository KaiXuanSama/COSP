---
applyTo: "**/api/ollama/**,**/protocol/ollama/**,**/provider/**/discovery/**,**/application/ollama/**"
description: "Ollama 协议层开发指南。Use when: 修改 Ollama API 端点、协议 DTO、流式翻译器、协议转换器，或任何涉及 /api/tags, /api/show, /api/chat, /api/version 的代码。"
---

# Ollama 协议层开发指南

## 架构认知

Copilot 连接后的调用时序：`GET /api/version` → `GET /api/tags` → `POST /api/show` → `POST /v1/chat/completions`。  
**注意**：Copilot 实际聊天走 OpenAI 协议（`/v1/chat/completions`），不走 `/api/chat`。Ollama 端点仅用于模型发现和能力查询。

## 调用链

```
OllamaApiController → CompositeOllamaService → OllamaServiceResolver → {Provider}DiscoveryService
                                                                         └── AbstractDiscoveryService（统一处理 tags/show/能力声明）
```

## 四端点契约

| 端点 | 关键行为 | Copilot 依赖字段 |
|------|----------|-----------------|
| `GET /api/version` | 返回 `{"version": "x.y.z"}`，从 DB `app_config.fake_version` 或 yml 默认值 | 版本号决定协议特性 |
| `GET /api/tags` | 聚合所有已启用供应商的模型，模型名格式 `[Provider] model` | `capabilities` 列表 |
| `POST /api/show` | 返回 `num_ctx`、`context_length`、`capabilities` | `{arch}.context_length` 决定上下文窗口 |
| `POST /api/chat` | NDJSON 流式（`stream=true`）或单 JSON（`stream=false`） | `done`、`done_reason`、`tool_calls` |

## 关键规则

1. **能力声明必须从 DB 读取** — 使用 `buildCapabilitiesFromDb()`，禁止硬编码 `List.of("completion", "tools")`
2. **`num_ctx` 不能为 4096** — Copilot 会计算 `maxInputTokens = context_length - maxOutputTokens`，4096 导致 maxInputTokens=0
3. **`/api/show` 的 `model_info` 必须包含 `{arch}.context_length`** — Copilot 用此字段决定上下文窗口大小
4. **OllamaStreamTranslator 必须使用 `TranslateSession`** — 每个请求 `newSession()`，翻译器本身无状态
5. **增量 tool_call arguments 不是合法 JSON** — 必须作为字符串累积，在 `finish_reason=tool_calls` 时一次性反序列化

## OllamaProtocolConverter 注意事项

- 通过 `Support` record 注入运行时能力（modelResolver、maxTokensResolver）
- 通过 `customizeOpenAiRequest` BiConsumer 钩子注入 provider 特有字段
- messages 中的 `images` 字段需转换为 OpenAI 的 `image_url` content block 格式
- `options.num_predict` → OpenAI `max_tokens`（默认 8192）

## OllamaStreamTranslator 状态机

```
OpenAI SSE chunk → translate(session, chunk, model) → List<OllamaChatResponse>

状态流转:
  content delta   → 累积到 textBuffer, 输出增量 chunk (done=false)
  reasoning delta → 累积到 reasoningBuffer, 输出 thinking chunk
  tool_call       → 累积 name + arguments 字符串
  finish=stop     → 输出 completion (done=true, done_reason="stop")
  finish=tool_calls → 反序列化 arguments, 输出 completion (done=true, done_reason="tool_calls")
  [DONE]          → 同 finish=stop 兜底
```

## 兜底模型 `nano_llm`

当数据库无任何启用模型时，`tags` 返回 `nano_llm`（context_length=4096, caps=[completion, tools]）。`show` 端点对 `nano_llm` 直接构造响应，不经过 provider 链。

## 测试

集成测试文件：`OllamaApiControllerStreamingTests.java`  
测试 stream=true 的 NDJSON 格式正确性、tool_calls 结构、done/done_reason 字段。

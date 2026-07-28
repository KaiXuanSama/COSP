---
applyTo: "**/api/ollama/**,**/protocol/ollama/**,**/provider/**/discovery/**,**/application/ollama/**"
description: "Ollama 模型发现协议开发指南。Use when: 修改 /api/version、/api/tags、/api/show 或其 DTO、模型目录与发现服务。"
---

# Ollama 模型发现协议指南

## 范围

Ollama 层只用于模型发现：`GET /api/version`、`GET /api/tags`、`POST /api/show`。实际聊天由 OpenAI 协议 `/v1/chat/completions` 处理，不能新增 `/api/chat`、NDJSON 聊天翻译器或相关状态机。

调用链：

```text
/api/show           -> ModelDiscoveryService -> ProviderRouteResolver -> GenericDiscoveryService
/api/tags           -> ModelCatalogService（直接聚合数据库中启用的模型）
/api/version        -> app_config.fake_version，回退 ollama.version
```

注意 `ModelDiscoveryService` 当前只有 `showModel`，模型列表不经过它。

## 契约

| 端点 | 关键字段 |
|---|---|
| `/api/version` | `version`，优先读取 `app_config.fake_version` |
| `/api/tags` | 模型名称、能力和上下文信息 |
| `/api/show` | `parameters.num_ctx`、`model_info.{arch}.context_length`、`capabilities` |

模型名可使用 `[provider-key] model-name`。`display_name` 只服务管理界面，不参与路由。

## 规则

1. 模型能力由 `provider_model.caps_tools` 与 `caps_vision` 读取，禁止硬编码。
2. 上下文窗口必须至少为 8192，避免 Copilot 计算出零可用输入窗口。
3. `tags` 与 `show` 必须从 `ResolvedProviderRoute` 中使用已解析的供应商与模型，不能重新扫描或猜测供应商。
4. 无启用模型时维持现有 `nano_llm` 兜底行为。
5. DTO 保持在 `protocol` 层，不引入 application/provider 依赖。

## 测试

覆盖版本读取、模型列表、带前缀模型详情、能力声明和上下文窗口。模型发现变更不应改变 `/v1/chat/completions` 的 SSE 契约。

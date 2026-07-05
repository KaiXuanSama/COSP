---
name: cosp-add-provider-skill
description: "为 COSP（Copilot Ollama SpringBoot Proxy）项目新增第三方 AI 模型供应商。适用场景：用户需要接入新的第三方 LLM API 服务作为本代理的供应商。"
---

# COSP 供应商接入指南

本 Skill 指导 AI 编码代理完成将新的第三方 AI 模型供应商接入 COSP 项目的完整流程。

## 项目概述

COSP 是一个 Spring Boot 代理，它将上游 LLM API 供应商（例如 DeepSeek、MiMo、LongCat、SenseNova、Uumit、Agnes、Zhipu）对 GitHub Copilot 伪装成本地 Ollama 服务器。采用统一端口架构：

- **端口 11434** — 统一端口，同时承载 Ollama 兼容 API（`/api/chat`、`/api/tags`、`/api/show`、`/api/version`）、OpenAI 兼容 API（`/v1/chat/completions`、`/v1/models`）和管理后台（Vue 3 SPA）

### 两条核心调用链

```
1. Ollama 协议链（模型发现）：Controller → CompositeOllamaService → OllamaServiceResolver → {Provider}DiscoveryService
2. OpenAI 协议链（实际聊天）：Controller → CompositeUpstreamChatService → UpstreamChatServiceResolver → {Provider}OpenAiChatService
```

**关键认知：** Copilot 通过 Ollama 协议发现模型，但**实际聊天请求走 `/v1/chat/completions`（OpenAI 协议）**，而非 `/api/chat`。

## 前置知识

在开始前，请确保理解：

- Java 21、Spring Boot 3.5、WebFlux（Reactor）
- Maven 构建系统
- 项目的五层架构：`api → application → provider → infrastructure` + `protocol`（纯 DTO）
- Provider 内部组件模式：DiscoveryService（模型发现，继承 `AbstractDiscoveryService`）+ OpenAiChatService（聊天执行，继承 `AbstractUpstreamChatService`）

## 逐步集成指南

### 第 1 步：创建 Provider 包结构

在 `src/main/java/com/kaixuan/copilot_ollama_proxy/provider/` 下创建以下目录结构：

```
{provider_name}/
├── discovery/
│   └── {ProviderName}DiscoveryService.java
└── openai/
    └── {ProviderName}OpenAiChatService.java
```

将 `{provider_name}` 替换为小写的供应商 key，`{ProviderName}` 替换为帕斯卡命名的名称。

### 第 2 步：实现 `{ProviderName}DiscoveryService`

位置：`src/main/java/.../provider/{provider_name}/discovery/{ProviderName}DiscoveryService.java`

此类继承 `AbstractDiscoveryService`，提供模型发现阶段的 Provider 元数据。

**强制构造函数模式：**
```java
public {ProviderName}DiscoveryService(
        RuntimeProviderCatalog runtimeProviderCatalog,
        @Value("${{{provider_name}}.default-model:{default-model}}") String fallbackDefaultModel) {
    super(runtimeProviderCatalog, fallbackDefaultModel);
}
```

**必须重写的方法：**
```java
@Override
public String getProviderKey() { return "{provider_key}"; }

@Override
protected String providerFormat() { return "{provider_key}"; }

@Override
protected String providerFamily() { return "{ProviderDisplayName}"; }

@Override
protected List<String> providerFamilies() { return List.of("{ProviderDisplayName}"); }

@Override
protected String providerParameterSize() { return "Flash"; } // 或适当的大小

@Override
protected String providerLicense() { return "Proprietary"; } // 按需调整
```

基类 `AbstractDiscoveryService` 已统一处理 `listModels()`、`showModel()`、`supportsModel()`、`buildCapabilitiesFromDb()` —— 子类只需提供元数据。
```java
private List<String> buildCapabilitiesFromDb(String resolvedModel) {
    List<String> caps = new ArrayList<>();
    caps.add("completion");
    try {
        var model = requireModelConfiguration(resolvedModel);
        if (model.capsTools()) caps.add("tools");
        if (model.capsVision()) caps.add("vision");
    } catch (Exception e) {
        log.warn("{ProviderDisplayName} 模型 [{}] 能力读取失败，仅使用默认 completion 能力", resolvedModel);
    }
    return caps;
}
```

**注意：** 基类 `AbstractDiscoveryService` 已提供 `buildCapabilitiesFromDb()` —— 子类无需实现。

**基类已处理 `listModels()`、`showModel()`—— 子类不需要覆写这些方法。**

### 第 3 步：实现 `{ProviderName}OpenAiChatService`

位置：`src/main/java/.../provider/{provider_name}/openai/{ProviderName}OpenAiChatService.java`

此类继承 `AbstractUpstreamChatService`，处理直接的 OpenAI 兼容聊天补全。

**强制构造函数：**
```java
public {ProviderName}OpenAiChatService(
        RuntimeProviderCatalog runtimeProviderCatalog,
        @Value("${{{provider_name}}.default-model:{default-model}}") String fallbackDefaultModel,
        ObjectMapper objectMapper) {
    super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
}
```

**必须重写的方法：**
```java
@Override
public String getProviderKey() { return "{provider_key}"; }

@Override
protected String providerDisplayName() { return "{ProviderDisplayName}"; }

@Override
protected String defaultBaseUrl() { return "{default_base_url_with_path}"; } // 例如 "https://api.example.com/v1"

@Override
protected String normalizeBaseUrl(String rawBaseUrl) {
    return rawBaseUrl.replaceAll("/+$", ""); // 仅去除尾部斜杠
}

@Override
protected void applyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
    // 实现认证头逻辑
}

@Override
protected String chatCompletionsUri() { return "/chat/completions"; } // 固定为 /chat/completions
```

**可选重写的方法（供应商特有逻辑）：**

- `customizeRequestBody(Map<String, Object> body, String resolvedModel)` — 注入供应商特有字段或修改请求体
- `onRawStreamChunk(String rawChunkJson)` — 拦截流式 chunk 进行特殊处理（如 reasoning_content 缓存、XML 工具调用检测）
- `onStreamFinish(String chunkId, String model, StringBuilder reasoningBuffer, boolean contentEmitted)` — 处理流结束

### 第 4 步：在路由解析器中注册

解析器使用 Spring 的 `List<OllamaService>` 和 `List<UpstreamChatService>` 自动注入。**解析器类无需修改代码**——只需用 `@Service` 注解你的服务类即可，Spring Boot 会自动发现它们。

### 第 5 步：在 application.yml 中添加配置

添加默认模型回退条目：
```yaml
{provider_name}:
  default-model: {default_model_name}
```

### 第 6 步：在 AdminPageController 中注册

**`src/main/java/.../api/AdminPageController.java` 中的两个位置：**

1. **`supportsProviderKey()` 方法** — 添加你的供应商 key：
```java
case "{provider_key}" -> true;
```

2. **`applyModelDiscoveryAuthHeaders()` 方法** — 添加认证头逻辑：
```java
case "{provider_key}" -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
```

### 第 7 步：添加前端供应商注册

在 `frontend/src/views/Settings.vue` 中，向 `providerMeta` 字典添加供应商元数据：
```typescript
{provider_key}: {
  displayName: '{ProviderDisplayName}',
  colorClass: 'accent',
  apiUrlPlaceholder: '{default_base_url}',
  docsUrl: '{docs_url}',
}
```

### 第 8 步：添加 Spring 配置元数据

在 `src/main/resources/META-INF/additional-spring-configuration-metadata.json` 中添加：
```json
{
  "name": "{provider_name}.default-model",
  "type": "java.lang.String",
  "description": "A description for '{provider_name}.default-model'"
}
```

### 第 9 步：编写测试

遵循项目的测试约定：
- 使用 `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `WebTestClient` 进行集成测试
- 使用 `@MockBean` 模拟外部依赖
- 测试方法使用 camelCase 长名描述行为
- 同时测试流式和非流式路径
- 测试模型发现端点（`/api/tags`、`/api/show`）
- 同时测试 Ollama 协议端点和 OpenAI 协议端点

## Provider 组件模式（架构约束）

每个 Provider 必须包含两个服务组件，各自继承对应基类：

| 组件 | 基类 | 职责 |
|------|------|------|
| **发现服务**（`{Provider}DiscoveryService`） | `AbstractDiscoveryService` | 模型发现：`/api/tags`、`/api/show` 端点，提供 Provider 元数据（family、license 等） |
| **聊天服务**（`{Provider}OpenAiChatService`） | `AbstractUpstreamChatService` | 聊天执行：`/v1/chat/completions` 端点，SSE 流式管道、重试、思考链缓存 |

基类职责：
- `AbstractDiscoveryService` 统一处理模型列表构建、`showModel` 模板、能力声明读取
- `AbstractUpstreamChatService` 统一处理 WebClient 构建、鉴权、重试策略、SSE 解包、思考链缓存（追踪/持久化/注入）
- 子类仅需覆写 Provider 特化点（鉴权方式、请求体定制等）

## 常见陷阱

1. **工具调用参数累积**：在 `OllamaStreamTranslator` 中，增量 `arguments` 片段不是合法 JSON——`objectMapper.readValue()` 始终会失败。应将 arguments 作为字符串累积，在 `finish_reason=tool_calls` 时一次性反序列化。

2. **400 错误重试**：`AbstractUpstreamChatService` 和 `OpenAiTransportClient` 对所有 400 错误重试（包括认证失败）。考虑仅对特定可重试的 400 场景重试。

3. **上下文窗口陷阱（4096）**：当 Copilot 中 `context_length=4096` 时，`maxInputTokens=0`，导致模型被认为无可用输入窗口。

4. **能力声明**：禁止硬编码能力列表（如 `List.of("completion", "tools")`），禁止通过模型名称推断能力（如 `if (model.contains("Omni"))`）。必须通过 `buildCapabilitiesFromDb()` 从数据库读取。

5. **Base URL 规范化**：统一处理尾部斜杠、`/v1` 重复以及供应商特有的路径追加。

## 最小可工作示例：DeepSeek 供应商

DeepSeek 供应商实现是推荐的最小参考实现。代码位置：
- `provider/deepseek/discovery/DeepSeekDiscoveryService.java` —— 纯元数据，无自定义逻辑
- `provider/deepseek/openai/DeepSeekOpenAiChatService.java` —— 所有行为均继承自基类

DeepSeek 使用 Bearer Token 认证，从数据库读取能力声明，思考链缓存处理全部委托给基类。
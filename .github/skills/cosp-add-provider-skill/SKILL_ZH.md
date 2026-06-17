---
name: cosp-add-provider-skill
description: "为 COSP（Copilot Ollama SpringBoot Proxy）项目新增第三方 AI 模型供应商。适用场景：用户需要接入新的第三方 LLM API 服务作为本代理的供应商。"
---

# COSP 供应商接入指南

本 Skill 指导 AI 编码代理完成将新的第三方 AI 模型供应商接入 COSP 项目的完整流程。

## 项目概述

COSP 是一个 Spring Boot 代理，它将上游 LLM API 供应商（例如 DeepSeek、MiMo、LongCat、SenseNova、Uumit）对 GitHub Copilot 伪装成本地 Ollama 服务器。采用双端口架构：

- **端口 11434** — Ollama 兼容 API（`/api/chat`、`/api/tags`、`/api/show`、`/api/version`）
- **端口 80** — 管理后台（Vue 3 SPA）

### 两条核心调用链

```
1. Ollama 协议链（模型发现）：Controller → CompositeOllamaService → OllamaServiceResolver → {Provider}OllamaService
2. OpenAI 协议链（实际聊天）：Controller → CompositeUpstreamChatService → UpstreamChatServiceResolver → {Provider}OpenAiChatService
```

**关键认知：** Copilot 通过 Ollama 协议发现模型，但**实际聊天请求走 `/v1/chat/completions`（OpenAI 协议）**，而非 `/api/chat`。

## 前置知识

在开始前，请确保理解：

- Java 21、Spring Boot 3.5、WebFlux（Reactor）
- Maven 构建系统
- 项目的五层架构：`api → application → provider → infrastructure` + `protocol`（纯 DTO）
- Provider 内部组件模式：Transport Client + Protocol Converter + Stream Translator + Service（均在 `provider/` 下）

## 逐步集成指南

### 第 1 步：创建 Provider 包结构

在 `src/main/java/com/kaixuan/copilot_ollama_proxy/provider/` 下创建以下目录结构：

```
{provider_name}/
├── ollama/
│   └── {ProviderName}OllamaService.java
└── openai/
    └── {ProviderName}OpenAiChatService.java
```

将 `{provider_name}` 替换为小写的供应商 key，`{ProviderName}` 替换为帕斯卡命名的名称。

### 第 2 步：实现 `{ProviderName}OllamaService`

位置：`src/main/java/.../provider/{provider_name}/ollama/{ProviderName}OllamaService.java`

此类继承 `AbstractRuntimeCatalogOllamaService`，负责模型发现阶段的 Ollama→OpenAI 协议转换。

**强制构造函数模式：**
```java
public {ProviderName}OllamaService(
        RuntimeProviderCatalog runtimeProviderCatalog,
        @Value("${{{provider_name}}.default-model:{default-model}}") String fallbackDefaultModel,
        ObjectMapper objectMapper,
        WebClient.Builder webClientBuilder) {
    super(runtimeProviderCatalog, fallbackDefaultModel);
    // 1. 创建传输客户端
    this.transportClient = new OpenAiTransportClient(runtimeProviderCatalog, webClientBuilder,
        new OpenAiTransportClient.Config(
            "{provider_key}",           // 供应商 key
            "{default_base_url}",       // 默认 Base URL
            "{chat_completions_uri}",   // 例如 "/v1/chat/completions"
            this::applyAuthHeaders,     // 认证头注入器
            this::normalizeBaseUrl      // Base URL 规范化器
        ));
    // 2. 创建协议转换器
    this.protocolConverter = new OllamaProtocolConverter(objectMapper);
    // 3. 创建 Support 记录（方法引用）
    this.protocolSupport = new OllamaProtocolConverter.Support(
        this::resolveRequestModel,
        this::resolveMaxTokens,
        this::extractStringContent,
        this::currentTimestamp);
    // 4. 创建流式翻译器
    this.streamTranslator = new OllamaStreamTranslator(objectMapper,
        new OllamaStreamTranslator.Support(
            this::createStreamingChunk,
            this::createStreamingCompletion));
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

**认证与 Base URL 模式：**

| 模式 | 实现 |
|------|------|
| Bearer Token | `(headers, apiKey) -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)` |
| 自定义 Header | `(headers, apiKey) -> headers.set("api-key", apiKey)` |
| 去除 `/v1` 后缀 | `raw -> { String url = raw.replaceAll("/+$", ""); return url.endsWith("/v1") ? url.substring(0, url.length() - 3) : url; }` |
| 追加 `/openai` | `raw -> raw.replaceAll("/+$", "") + "/openai"` |
| 确保 `/v1` 结尾 | `raw -> { String n = raw.trim().replaceAll("/+$", ""); return n.endsWith("/v1") ? n : n + "/v1"; }` |

**能力声明**必须从数据库读取。使用以下标准模式：
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

**chat 和 chatStream 方法**在所有供应商中遵循相同模式：
```java
@Override
public Mono<OllamaChatResponse> chat(OllamaChatRequest request) {
    if (request.isStream())
        return Mono.error(new UnsupportedOperationException("Use chatStream() for streaming"));
    Map<String, Object> openAiRequest = convertOllamaToOpenAi(request);
    return transportClient.sendChatCompletion(openAiRequest)
        .map(respJson -> convertOpenAiToOllama(respJson, request.getModel()));
}

@Override
public Flux<OllamaChatResponse> chatStream(OllamaChatRequest request) {
    Map<String, Object> openAiRequest = convertOllamaToOpenAi(request);
    openAiRequest.put("stream", true);
    var session = streamTranslator.newSession();
    return transportClient.streamChatCompletion(openAiRequest)
        .concatMap(chunk -> Flux.fromIterable(
            streamTranslator.translate(session, chunk, request.getModel())));
}
```

### 第 3 步：实现 `{ProviderName}OpenAiChatService`

位置：`src/main/java/.../provider/{provider_name}/openai/{ProviderName}OpenAiChatService.java`

此类继承 `AbstractOpenAiCompatibleUpstreamChatService`，处理直接的 OpenAI 兼容聊天补全。

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
protected String defaultBaseUrl() { return "{default_base_url}"; }

@Override
protected String normalizeBaseUrl(String rawBaseUrl) {
    // 实现 URL 规范化逻辑
}

@Override
protected void applyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
    // 实现认证头逻辑
}

@Override
protected String chatCompletionsUri() { return "{chat_completions_uri}"; }
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

每个 Provider 必须拆分为四个职责清晰的组件——**不允许混职责**：

| 组件 | 职责 |
|------|------|
| **传输客户端**（`OpenAiTransportClient`） | HTTP 交互：WebClient 构建、认证头、重试策略、SSE 解包 |
| **协议转换器**（`OllamaProtocolConverter`） | 非流式协议映射：请求体转换、响应体重构 |
| **流式翻译器**（`OllamaStreamTranslator`） | 流式状态机：增量 chunk 翻译、工具调用累积 |
| **服务**（`{Provider}OllamaService` / `{Provider}OpenAiChatService`） | 编排：组装转换器、翻译器和传输客户端。不包含协议细节 |

规则：
- **Service** 只做编排，不直接操作 JSON 字段
- **Converter** 只做映射，不维护状态
- **Translator** 只做流式状态累积，不发 HTTP 请求
- **Transport Client** 只做 HTTP 交互，不知道协议转换逻辑

## 常见陷阱

1. **工具调用参数累积**：在 `OllamaStreamTranslator` 中，增量 `arguments` 片段不是合法 JSON——`objectMapper.readValue()` 始终会失败。应将 arguments 作为字符串累积，在 `finish_reason=tool_calls` 时一次性反序列化。

2. **400 错误重试**：`AbstractOpenAiCompatibleUpstreamChatService` 和 `OpenAiTransportClient` 对所有 400 错误重试（包括认证失败）。考虑仅对特定可重试的 400 场景重试。

3. **上下文窗口陷阱（4096）**：当 Copilot 中 `context_length=4096` 时，`maxInputTokens=0`，导致模型被认为无可用输入窗口。

4. **能力声明**：禁止硬编码能力列表（如 `List.of("completion", "tools")`），禁止通过模型名称推断能力（如 `if (model.contains("Omni"))`）。必须通过 `buildCapabilitiesFromDb()` 从数据库读取。

5. **Base URL 规范化**：统一处理尾部斜杠、`/v1` 重复以及供应商特有的路径追加。

## 最小可工作示例：Uumit 供应商

Uumit 供应商实现是推荐的最小参考实现。代码位置：
- `provider/uumit/ollama/UumitOllamaService.java`
- `provider/uumit/openai/UumitOpenAiChatService.java`

Uumit 使用 Bearer Token 认证，从 Base URL 中去除了 `/v1` 后缀，使用 `/v1/chat/completions` 端点，并从数据库读取能力声明。
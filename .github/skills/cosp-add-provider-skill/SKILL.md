---
name: cosp-add-provider-skill
description: "Add a new upstream AI model provider (vendor) to the COSP (Copilot Ollama SpringBoot Proxy) project. Use when: the user wants to integrate a new third-party LLM API service as a provider in this proxy."
---

# COSP Provider Integration Skill

This skill guides an AI coding agent through the complete process of adding a new third-party AI model provider to the COSP project.

## Overview

COSP is a Spring Boot proxy that makes upstream LLM API providers (e.g., DeepSeek, MiMo, LongCat, SenseNova, Uumit) appear as a local Ollama server to GitHub Copilot. It operates on a dual-port architecture:

- **Port 11434** — Ollama-compatible API (`/api/chat`, `/api/tags`, `/api/show`, `/api/version`)
- **Port 80** — Admin dashboard (Vue 3 SPA)

### Two Core Call Chains

```
1. Ollama protocol chain (model discovery): Controller → CompositeOllamaService → OllamaServiceResolver → {Provider}OllamaService
2. OpenAI protocol chain (actual chat):       Controller → CompositeUpstreamChatService → UpstreamChatServiceResolver → {Provider}OpenAiChatService
```

**Key insight:** Copilot discovers models via the Ollama protocol, but **actual chat requests go through `/v1/chat/completions` (OpenAI protocol)**, not `/api/chat`.

## Prerequisites

Before starting, ensure you understand:

- Java 21, Spring Boot 3.5, WebFlux (Reactor)
- Maven build system
- The project's five-layer architecture: `api → application → provider → infrastructure` + `protocol` (DTOs only)
- Provider internal component pattern: Transport Client + Protocol Converter + Stream Translator + Service (all four in `provider/`)

## Step-by-Step Integration Guide

### Step 1: Create Provider Package Structure

Create the following directory structure under `src/main/java/com/kaixuan/copilot_ollama_proxy/provider/`:

```
{provider_name}/
├── ollama/
│   └── {ProviderName}OllamaService.java
└── openai/
    └── {ProviderName}OpenAiChatService.java
```

Replace `{provider_name}` with the lowercase provider key and `{ProviderName}` with the PascalCase name.

### Step 2: Implement `{ProviderName}OllamaService`

Location: `src/main/java/.../provider/{provider_name}/ollama/{ProviderName}OllamaService.java`

This class extends `AbstractRuntimeCatalogOllamaService` and handles the Ollama→OpenAI protocol translation for model discovery.

**Mandatory constructor pattern:**
```java
public {ProviderName}OllamaService(
        RuntimeProviderCatalog runtimeProviderCatalog,
        @Value("${{{provider_name}}.default-model:{default-model}}") String fallbackDefaultModel,
        ObjectMapper objectMapper,
        WebClient.Builder webClientBuilder) {
    super(runtimeProviderCatalog, fallbackDefaultModel);
    // 1. Create Transport Client
    this.transportClient = new OpenAiTransportClient(runtimeProviderCatalog, webClientBuilder,
        new OpenAiTransportClient.Config(
            "{provider_key}",           // provider key
            "{default_base_url}",       // default base URL
            "{chat_completions_uri}",   // e.g., "/v1/chat/completions"
            this::applyAuthHeaders,     // auth header injector
            this::normalizeBaseUrl      // base URL normalizer
        ));
    // 2. Create Protocol Converter
    this.protocolConverter = new OllamaProtocolConverter(objectMapper);
    // 3. Create Support record with method references
    this.protocolSupport = new OllamaProtocolConverter.Support(
        this::resolveRequestModel,
        this::resolveMaxTokens,
        this::extractStringContent,
        this::currentTimestamp);
    // 4. Create Stream Translator
    this.streamTranslator = new OllamaStreamTranslator(objectMapper,
        new OllamaStreamTranslator.Support(
            this::createStreamingChunk,
            this::createStreamingCompletion));
}
```

**Required overrides:**
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
protected String providerParameterSize() { return "Flash"; } // or appropriate size

@Override
protected String providerLicense() { return "Proprietary"; } // adjust as needed
```

**Authentication & Base URL patterns:**

| Pattern | Implementation |
|---------|---------------|
| Bearer Token | `(headers, apiKey) -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)` |
| Custom Header | `(headers, apiKey) -> headers.set("api-key", apiKey)` |
| Strip `/v1` suffix | `raw -> { String url = raw.replaceAll("/+$", ""); return url.endsWith("/v1") ? url.substring(0, url.length() - 3) : url; }` |
| Append `/openai` | `raw -> raw.replaceAll("/+$", "") + "/openai"` |
| Ensure `/v1` suffix | `raw -> { String n = raw.trim().replaceAll("/+$", ""); return n.endsWith("/v1") ? n : n + "/v1"; }` |

**Capability declarations** MUST be read from the database. Use this standard pattern:
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

**Chat and chatStream methods** follow an identical pattern across all providers:
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

### Step 3: Implement `{ProviderName}OpenAiChatService`

Location: `src/main/java/.../provider/{provider_name}/openai/{ProviderName}OpenAiChatService.java`

This class extends `AbstractOpenAiCompatibleUpstreamChatService` and handles direct OpenAI-compatible chat completions.

**Mandatory constructor:**
```java
public {ProviderName}OpenAiChatService(
        RuntimeProviderCatalog runtimeProviderCatalog,
        @Value("${{{provider_name}}.default-model:{default-model}}") String fallbackDefaultModel,
        ObjectMapper objectMapper) {
    super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
}
```

**Required overrides:**
```java
@Override
public String getProviderKey() { return "{provider_key}"; }

@Override
protected String providerDisplayName() { return "{ProviderDisplayName}"; }

@Override
protected String defaultBaseUrl() { return "{default_base_url}"; }

@Override
protected String normalizeBaseUrl(String rawBaseUrl) {
    // Implement URL normalization logic
}

@Override
protected void applyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
    // Implement auth header logic
}

@Override
protected String chatCompletionsUri() { return "{chat_completions_uri}"; }
```

**Optional overrides for provider-specific logic:**

- `customizeRequestBody(Map<String, Object> body, String resolvedModel)` — inject provider-specific fields or modify the request body before sending
- `onRawStreamChunk(String rawChunkJson)` — intercept streaming chunks for special handling (e.g., reasoning_content caching, XML tool call detection)
- `onStreamFinish(String chunkId, String model, StringBuilder reasoningBuffer, boolean contentEmitted)` — handle stream completion

### Step 4: Register in Resolver Services

The resolvers use Spring's `List<OllamaService>` and `List<UpstreamChatService>` auto-injection. **No code changes needed** in the resolver classes — simply annotating your services with `@Service` is sufficient. Spring Boot will automatically pick them up.

### Step 5: Add Configuration in application.yml

Add a default model fallback entry:
```yaml
{provider_name}:
  default-model: {default_model_name}
```

### Step 6: Register in AdminPageController

**Two locations in `src/main/java/.../api/AdminPageController.java`:**

1. **`supportsProviderKey()` method** — add your provider key:
```java
case "{provider_key}" -> true;
```

2. **`applyModelDiscoveryAuthHeaders()` method** — add authentication header logic:
```java
case "{provider_key}" -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
```

### Step 7: Add Frontend Provider Registration

In `frontend/src/views/Settings.vue`, add provider metadata to the `providerMeta` dictionary:
```typescript
{provider_key}: {
  displayName: '{ProviderDisplayName}',
  colorClass: 'accent',
  apiUrlPlaceholder: '{default_base_url}',
  docsUrl: '{docs_url}',
}
```

### Step 8: Add Spring Configuration Metadata

In `src/main/resources/META-INF/additional-spring-configuration-metadata.json`, add:
```json
{
  "name": "{provider_name}.default-model",
  "type": "java.lang.String",
  "description": "A description for '{provider_name}.default-model'"
}
```

### Step 9: Write Tests

Follow the project's test conventions:
- Use `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `WebTestClient` for integration tests
- Use `@MockBean` to mock external dependencies
- Use camelCase long names describing behavior for test methods
- Test both streaming and non-streaming paths
- Test model discovery endpoints (`/api/tags`, `/api/show`)
- Test both Ollama protocol endpoints and OpenAI protocol endpoints

## Provider Component Pattern (Architecture Constraint)

Each provider MUST split into four components with clear responsibilities — **no mixing of concerns**:

| Component | Responsibility |
|-----------|---------------|
| **Transport Client** (`OpenAiTransportClient`) | HTTP interaction: WebClient building, auth headers, retry policies, SSE unpacking |
| **Protocol Converter** (`OllamaProtocolConverter`) | Non-streaming protocol mapping: request body conversion, response body reconstruction |
| **Stream Translator** (`OllamaStreamTranslator`) | Streaming state machine: incremental chunk translation, tool call accumulation |
| **Service** (`{Provider}OllamaService` / `{Provider}OpenAiChatService`) | Orchestration: assembles converter, translator, and transport client. Contains no protocol details |

Rules:
- **Service** does orchestration only, no direct JSON field manipulation
- **Converter** does mapping only, no state management
- **Translator** does streaming state accumulation only, no HTTP requests
- **Transport Client** does HTTP interaction only, knows nothing about protocol conversion

## Common Pitfalls

1. **Tool call argument accumulation**: In `OllamaStreamTranslator`, incremental `arguments` fragments are not valid JSON — `objectMapper.readValue()` will always fail. Accumulate arguments as a string and deserialize once when `finish_reason=tool_calls`.

2. **400 error retry**: `AbstractOpenAiCompatibleUpstreamChatService` and `OpenAiTransportClient` retry ALL 400 errors (including auth failures). Consider filtering to retry only specific 400 scenarios.

3. **Context window trap (4096)**: When `context_length=4096` in Copilot, `maxInputTokens=0`, making the model appear to have no usable input window.

4. **Capability declaration**: Never hardcode capability lists (like `List.of("completion", "tools")`) and never infer capabilities from model names (like `if (model.contains("Omni"))`). Always read from the database via `buildCapabilitiesFromDb()`.

5. **Base URL normalization**: Handle trailing slashes, `/v1` duplication, and provider-specific path appendage consistently.

## Minimal Working Example: Uumit Provider

The Uumit provider implementation is the recommended minimal reference. Find the code at:
- `provider/uumit/ollama/UumitOllamaService.java`
- `provider/uumit/openai/UumitOpenAiChatService.java`

Uumit uses Bearer Token auth, strips `/v1` from Base URL, uses `/v1/chat/completions` endpoint, and reads capabilities from the database.
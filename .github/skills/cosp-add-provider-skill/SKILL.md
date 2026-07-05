---
name: cosp-add-provider-skill
description: "Add a new upstream AI model provider (vendor) to the COSP (Copilot Ollama SpringBoot Proxy) project. Use when: the user wants to integrate a new third-party LLM API service as a provider in this proxy."
---

# COSP Provider Integration Skill

This skill guides an AI coding agent through the complete process of adding a new third-party AI model provider to the COSP project.

## Overview

COSP is a Spring Boot proxy that makes upstream LLM API providers (e.g., DeepSeek, MiMo, LongCat, SenseNova, Uumit, Agnes, Zhipu) appear as a local Ollama server to GitHub Copilot. It uses a unified port architecture:

- **Port 11434** — Unified port for Ollama-compatible API (`/api/chat`, `/api/tags`, `/api/show`, `/api/version`), OpenAI-compatible API (`/v1/chat/completions`, `/v1/models`), and admin dashboard (Vue 3 SPA)

### Two Core Call Chains

```
1. Ollama protocol chain (model discovery): Controller → CompositeOllamaService → OllamaServiceResolver → {Provider}DiscoveryService
2. OpenAI protocol chain (actual chat):       Controller → CompositeUpstreamChatService → UpstreamChatServiceResolver → {Provider}OpenAiChatService
```

**Key insight:** Copilot discovers models via the Ollama protocol, but **actual chat requests go through `/v1/chat/completions` (OpenAI protocol)**, not `/api/chat`.

## Prerequisites

Before starting, ensure you understand:

- Java 21, Spring Boot 3.5, WebFlux (Reactor)
- Maven build system
- The project's five-layer architecture: `api → application → provider → infrastructure` + `protocol` (DTOs only)
- Provider internal component pattern: DiscoveryService (model discovery, extends `AbstractDiscoveryService`) + OpenAiChatService (chat execution, extends `AbstractUpstreamChatService`)

## Step-by-Step Integration Guide

### Step 1: Create Provider Package Structure

Create the following directory structure under `src/main/java/com/kaixuan/copilot_ollama_proxy/provider/`:

```
{provider_name}/
├── discovery/
│   └── {ProviderName}DiscoveryService.java
└── openai/
    └── {ProviderName}OpenAiChatService.java
```

Replace `{provider_name}` with the lowercase provider key and `{ProviderName}` with the PascalCase name.

### Step 2: Implement `{ProviderName}DiscoveryService`

Location: `src/main/java/.../provider/{provider_name}/discovery/{ProviderName}DiscoveryService.java`

This class extends `AbstractDiscoveryService` and provides provider metadata for model discovery.

**Mandatory constructor pattern:**
```java
public {ProviderName}DiscoveryService(
        RuntimeProviderCatalog runtimeProviderCatalog,
        @Value("${{{provider_name}}.default-model:{default-model}}") String fallbackDefaultModel) {
    super(runtimeProviderCatalog, fallbackDefaultModel);
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

The base class `AbstractDiscoveryService` already handles `listModels()`, `showModel()`, `supportsModel()`, and `buildCapabilitiesFromDb()` — subclasses only provide metadata.
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

**Note:** The `AbstractDiscoveryService` base class already provides `buildCapabilitiesFromDb()` — subclasses do not need to implement it.

**The base class handles `listModels()`, `showModel()`, `chat()`, and `chatStream()` — subclasses do NOT override these.**

### Step 3: Implement `{ProviderName}OpenAiChatService`

Location: `src/main/java/.../provider/{provider_name}/openai/{ProviderName}OpenAiChatService.java`

This class extends `AbstractUpstreamChatService` and handles direct OpenAI-compatible chat completions.

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
protected String defaultBaseUrl() { return "{default_base_url_with_path}"; } // e.g., "https://api.example.com/v1"

@Override
protected String normalizeBaseUrl(String rawBaseUrl) {
    return rawBaseUrl.replaceAll("/+$", ""); // Just strip trailing slashes
}

@Override
protected void applyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
    // Implement auth header logic
}

@Override
protected String chatCompletionsUri() { return "/chat/completions"; } // Always /chat/completions
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

Each provider MUST have two service components, each extending its respective base class:

| Component | Base Class | Responsibility |
|-----------|-----------|---------------|
| **Discovery Service** (`{Provider}DiscoveryService`) | `AbstractDiscoveryService` | Model discovery: `/api/tags`, `/api/show` endpoints. Provides provider metadata (family, license, etc.) |
| **Chat Service** (`{Provider}OpenAiChatService`) | `AbstractUpstreamChatService` | Chat execution: `/v1/chat/completions` endpoint. SSE streaming pipeline, retry, reasoning cache |

Base class responsibilities:
- `AbstractDiscoveryService` handles model list building, `showModel` template, capability declarations from DB
- `AbstractUpstreamChatService` handles WebClient building, auth, retry policies, SSE unpacking, reasoning chain cache (tracking/persistence/injection)
- Subclasses only override provider-specific customization points (auth method, request body customization, etc.)

## Common Pitfalls

1. **Tool call argument accumulation**: In `OllamaStreamTranslator`, incremental `arguments` fragments are not valid JSON — `objectMapper.readValue()` will always fail. Accumulate arguments as a string and deserialize once when `finish_reason=tool_calls`.

2. **400 error retry**: `AbstractUpstreamChatService` and `OpenAiTransportClient` retry ALL 400 errors (including auth failures). Consider filtering to retry only specific 400 scenarios.

3. **Context window trap (4096)**: When `context_length=4096` in Copilot, `maxInputTokens=0`, making the model appear to have no usable input window.

4. **Capability declaration**: Never hardcode capability lists (like `List.of("completion", "tools")`) and never infer capabilities from model names (like `if (model.contains("Omni"))`). Always read from the database via `buildCapabilitiesFromDb()`.

5. **Base URL normalization**: Handle trailing slashes, `/v1` duplication, and provider-specific path appendage consistently.

## Minimal Working Example: DeepSeek Provider

The DeepSeek provider implementation is the recommended minimal reference. Find the code at:
- `provider/deepseek/discovery/DeepSeekDiscoveryService.java` — pure metadata, zero custom logic
- `provider/deepseek/openai/DeepSeekOpenAiChatService.java` — all behavior inherited from base class

DeepSeek uses Bearer Token auth, reads capabilities from the database, and delegates all reasoning cache handling to the base class.
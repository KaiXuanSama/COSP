package com.kaixuan.copilot_ollama_proxy.api.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.openai.CompositeUpstreamChatService;
import com.kaixuan.copilot_ollama_proxy.application.catalog.ModelCatalogService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.ApiUsageCollector;
import com.kaixuan.copilot_ollama_proxy.protocol.openai.OpenAiChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.openai.OpenAiModelsResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.openai.OpenAiModelsResponse.ModelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAI 兼容 API 控制器 —— 处理 Copilot 发出的 OpenAI 格式请求。
 * <p>
 * 端点：POST /v1/chat/completions
 * <p>
 * 控制器只负责 HTTP 层（请求接收、响应封装、SSE 流转发），
 * 协议转换逻辑由 {@link UpstreamChatService} 的实现类处理。
 */
@RestController
public class OpenAiController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiController.class);

    private final CompositeUpstreamChatService upstreamChatService;
    private final ObjectMapper objectMapper;
    private final ApiUsageCollector apiUsageCollector;
    private final ModelCatalogService modelCatalogService;

    /**
     * 构造函数注入 CompositeUpstreamChatService、ObjectMapper、ApiUsageCollector 和 ModelCatalogService。
     * @param upstreamChatService 上游聊天服务组合
     * @param objectMapper JSON 对象映射器
     * @param apiUsageCollector API 使用量收集器
     * @param modelCatalogService 模型目录服务，用于获取可用模型列表
     */
    public OpenAiController(CompositeUpstreamChatService upstreamChatService, ObjectMapper objectMapper, ApiUsageCollector apiUsageCollector, ModelCatalogService modelCatalogService) {
        this.upstreamChatService = upstreamChatService;
        this.objectMapper = objectMapper;
        this.apiUsageCollector = apiUsageCollector;
        this.modelCatalogService = modelCatalogService;
    }

    /**
     * 获取可用模型列表。
     * <p>
     * 通过 {@link ModelCatalogService} 读取所有已启用服务商及其已启用模型，
     * 返回符合 OpenAI API 规范的模型列表。
     * <p>
     * 端点：GET /v1/models
     * <p>
     * 响应格式：
     * <pre>
     * {
     *   "object": "list",
     *   "data": [
     *     {
     *       "id": "model-id",
     *       "object": "model",
     *       "created": 1686935002,
     *       "owned_by": "provider-key"
     *     }
     *   ]
     * }
     * </pre>
     * @return OpenAiModelsResponse 包含所有可用模型的列表
     */
    @GetMapping(value = "/v1/models")
    public Mono<OpenAiModelsResponse> listModels() {
        return Mono.fromCallable(() -> {
            long defaultCreated = System.currentTimeMillis() / 1000;
            List<ModelData> models = modelCatalogService.listAvailableModels().stream()
                    .map(m -> new ModelData(m.prefixedName(), defaultCreated, m.displayKey()))
                    .toList();
            return new OpenAiModelsResponse(models);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 处理聊天完成请求，支持流式和非流式两种模式。
     * @param request OpenAI 格式的聊天请求
     * @return 统一返回 Mono ResponseEntity；非流式 body 为完整 JSON 字符串，
     *         流式 body 为 ServerSentEvent 流（text/event-stream）。
     */
    @PostMapping(value = "/v1/chat/completions")
    public Mono<ResponseEntity<?>> chatCompletions(@RequestBody OpenAiChatRequest request) {
        Map<String, Object> requestBody = buildRequestBody(request);

        // 流式：将 SSE 流作为 ResponseEntity 的 body 返回，由 WebFlux 框架托管背压、取消与超时。
        if (request.isStream()) {
            Flux<ServerSentEvent<String>> stream = streamResponse(requestBody, request.getModel());
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header("Cache-Control", "no-cache")
                    .body(stream));
        }

        // 非流式：获取完整响应后提取 usage 进行记录，并返回给客户端。
        return upstreamChatService.chatCompletion(requestBody, request.getModel()).doOnNext(this::recordUsage)
                .<ResponseEntity<?>>map(openAiJson -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(openAiJson))
                .onErrorResume(ex -> {
                    if (isClientDisconnect(ex)) {
                        return Mono.empty();
                    }
                    // 透传上游错误响应（重试耗尽时 WebClientResponseException 被包装在 RetryExhaustedException 中，需要解包）
                    WebClientResponseException responseException = findWebResponseException(ex);
                    if (responseException != null) {
                        log.warn("上游 API 返回错误 [{}] {}: {}", request.getModel(), responseException.getStatusCode().value(), responseException.getResponseBodyAsString());
                        return Mono.just(ResponseEntity.status(responseException.getStatusCode().value())
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(responseException.getResponseBodyAsString()));
                    }
                    log.warn("上游 API 调用失败 [{}]: {} ({})", request.getModel(), extractRootCause(ex), extractRequestUrl(ex));
                    return Mono.just(ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"error\":{\"message\":\"无法连接到上游服务\",\"type\":\"upstream_error\"}}"));
                });
    }

    /**
     * 处理流式响应，将上游服务的 SSE 片段映射为 ServerSentEvent 逐个下发给客户端，
     * 并在完成时记录累计的 token 使用量。
     *
     * 相比旧的 SseEmitter 手动订阅模型，这里直接返回 Flux，由 WebFlux 框架托管
     * 背压、取消和超时，无需手动管理 Disposable 与回调。
     *
     * @param requestBody 请求体内容，已转换为 Map 格式
     * @param model 模型名称
     * @return ServerSentEvent 流
     */
    private Flux<ServerSentEvent<String>> streamResponse(Map<String, Object> requestBody, String model) {
        AtomicInteger streamInputTokens = new AtomicInteger(0);
        AtomicInteger streamOutputTokens = new AtomicInteger(0);

        return upstreamChatService.chatCompletionStream(requestBody, model)
                .doOnNext(chunk -> accumulateStreamUsage(chunk, streamInputTokens, streamOutputTokens))
                .map(chunk -> ServerSentEvent.builder(chunk).build())
                .doOnComplete(() -> apiUsageCollector.record(streamInputTokens.get(), streamOutputTokens.get()))
                .onErrorResume(error -> {
                    if (isClientDisconnect(error)) {
                        return Flux.empty();
                    }
                    // 透传上游错误响应（解包重试耗尽包装）
                    WebClientResponseException responseException = findWebResponseException(error);
                    if (responseException != null) {
                        log.warn("上游 API 返回错误 [{}] {}: {}", model, responseException.getStatusCode().value(), responseException.getResponseBodyAsString());
                        return Flux.just(ServerSentEvent.<String>builder(responseException.getResponseBodyAsString()).event("error").build());
                    }
                    log.warn("上游 API 调用失败 [{}]: {} ({})", model, extractRootCause(error), extractRequestUrl(error));
                    return Flux.just(ServerSentEvent.<String>builder("{\"error\":{\"message\":\"无法连接到上游服务\",\"type\":\"upstream_error\"}}").event("error").build());
                });
    }

    /**
     * 构建请求体，将 OpenAiChatRequest 中的字段转换为 Map 格式，适配上游服务的请求要求。
     * @param request OpenAiChatRequest 对象，包含模型名称、消息列表、工具调用信息等字段
     * @return Map<String, Object> 格式的请求体，包含模型名称、消息列表、工具调用信息等字段，适配上游服务的请求要求
     */
    private Map<String, Object> buildRequestBody(OpenAiChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.put("top_p", request.getTopP());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        body.put("stream", request.isStream());
        body.put("messages", objectMapper.convertValue(request.getMessages(), List.class));
        if (request.getTools() != null) {
            body.put("tools", objectMapper.convertValue(request.getTools(), List.class));
        }
        if (request.getToolChoice() != null) {
            body.put("tool_choice", objectMapper.convertValue(request.getToolChoice(), Object.class));
        }
        if (request.getN() != null) {
            body.put("n", request.getN());
        }
        if (request.getStreamOptions() != null) {
            body.put("stream_options", objectMapper.convertValue(request.getStreamOptions(), Object.class));
        }
        return body;
    }

    /**
     * 从非流式响应 JSON 中提取 usage 并记录。
     * @param openAiJson 非流式响应的 JSON 字符串，包含 usage 字段
     */
    private void recordUsage(String openAiJson) {
        try {
            JsonNode root = objectMapper.readTree(openAiJson);
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                int inputTokens = usage.path("prompt_tokens").asInt(0);
                int outputTokens = usage.path("completion_tokens").asInt(0);
                apiUsageCollector.record(inputTokens, outputTokens);
            }
        } catch (Exception e) {
            log.warn("非流式响应 usage 提取失败: {}", e.getMessage());
        }
    }

    /**
     * 从流式 SSE chunk 中累加 token 数。
     * 流式响应中 usage 可能出现在最后一个 content chunk 或单独的 usage chunk 中。
     * @param chunk SSE chunk 字符串
     * @param inputTokens 输入 token 累加器
     * @param outputTokens 输出 token 累加器
     */
    private void accumulateStreamUsage(String chunk, AtomicInteger inputTokens, AtomicInteger outputTokens) {
        try {
            JsonNode root = objectMapper.readTree(chunk);
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                inputTokens.set(usage.path("prompt_tokens").asInt(0));
                outputTokens.set(usage.path("completion_tokens").asInt(0));
            }
        } catch (Exception e) {
            // 流式 chunk 可能不是合法 JSON（如 [DONE]），忽略
        }
    }

    /**
     * 判断异常是否由客户端断开连接引起，常见的异常类型包括 AsyncRequestNotUsableException、ClientAbortException、EOFException 等。
     * @param throwable 异常对象
     * @return 如果异常或其原因链中包含客户端断开连接的异常类型，则返回 true；否则返回 false
     */
    private boolean isClientDisconnect(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String simpleName = current.getClass().getSimpleName();
            // AbortedException：Reactor Netty 客户端断开；其余为通用断连异常名
            if ("AbortedException".equals(simpleName) || "ClientAbortException".equals(simpleName)
                    || "EOFException".equals(simpleName) || "AsyncRequestNotUsableException".equals(simpleName)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 从异常链中查找 WebClientResponseException。
     * 重试耗尽时，原始异常被包装在 RetryExhaustedException 中，需要递归解包。
     */
    private org.springframework.web.reactive.function.client.WebClientResponseException findWebResponseException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof org.springframework.web.reactive.function.client.WebClientResponseException responseException) {
                return responseException;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 从异常链中提取最底层的有意义错误信息，过滤掉 Reactor/Netty 内部异常。
     * 例如 DNS 解析失败会提取 "Failed to resolve 'api.kimi.com'"。
     */
    private String extractRootCause(Throwable throwable) {
        Throwable deepest = throwable;
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
            String msg = current.getMessage();
            // 跳过无意义的包装异常（Reactor、Netty 内部）
            if (msg != null && !msg.isBlank() && !msg.startsWith("Retries exhausted")) {
                deepest = current;
            }
        }
        String msg = deepest.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : deepest.getClass().getSimpleName();
    }

    /**
     * 从异常中提取请求 URL（如有）。
     * WebClientRequestException 的 message 中通常包含目标 URL。
     */
    private String extractRequestUrl(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof org.springframework.web.reactive.function.client.WebClientRequestException requestEx) {
                java.net.URI uri = requestEx.getUri();
                if (uri != null) return uri.toString();
            }
            // 从 Reactor checkpoint 中提取 URL
            String msg = current.getMessage();
            if (msg != null && msg.contains("Request to POST ")) {
                int start = msg.indexOf("Request to POST ") + 16;
                int end = msg.indexOf(" ", start);
                if (end < 0) end = msg.indexOf("]", start);
                if (end < 0) end = msg.length();
                return msg.substring(start, end).trim();
            }
            current = current.getCause();
        }
        return "unknown";
    }
}

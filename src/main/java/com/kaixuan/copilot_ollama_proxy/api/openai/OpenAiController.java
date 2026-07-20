package com.kaixuan.copilot_ollama_proxy.api.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kaixuan.copilot_ollama_proxy.application.openai.ChatCompletionService;
import com.kaixuan.copilot_ollama_proxy.application.catalog.AvailableModel;
import com.kaixuan.copilot_ollama_proxy.application.catalog.ModelCatalogService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.ApiUsageCollector;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import com.kaixuan.copilot_ollama_proxy.protocol.openai.OpenAiChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.openai.OpenAiModelsResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.openai.OpenAiModelsResponse.ModelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAI 兼容 API 控制器 —— 处理 Copilot 发出的 OpenAI 格式请求。
 * <p>
 * 端点：POST /v1/chat/completions
 * <p>
 * 控制器只负责 HTTP 层（请求接收、响应封装、SSE 流转发），
 * 应用层负责将模型解析为供应商路由，并交由统一 Generic 执行器处理。
 */
@RestController
public class OpenAiController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiController.class);

    /** CHUNK 生命周期事件的推送节流间隔（毫秒），避免长响应把 SSE 通道打爆。首个 chunk 不受此限制。 */
    private static final long CHUNK_PUSH_INTERVAL_MS = 200L;

    private final ChatCompletionService chatCompletionService;
    private final ObjectMapper objectMapper;
    private final ApiUsageCollector apiUsageCollector;
    private final ModelCatalogService modelCatalogService;
    private final CallLifecyclePublisher callLifecyclePublisher;
    private final String readmeHost;
    private final int serverPort;

    /**
    * 构造函数注入 ChatCompletionService、ObjectMapper、ApiUsageCollector、ModelCatalogService
     * 以及 README 模型所需的主机地址和服务端口。
     *
    * @param chatCompletionService 聊天补全应用服务
     * @param objectMapper JSON 对象映射器
     * @param apiUsageCollector API 使用量收集器
     * @param modelCatalogService 模型目录服务，用于获取可用模型列表
     * @param readmeHost README 模型输出配置中的主机地址（环境变量 README_HOST，默认 localhost）
     * @param serverPort 服务器监听端口（环境变量 SERVER_PORT，默认 11434）
     */
    public OpenAiController(ChatCompletionService chatCompletionService, ObjectMapper objectMapper,
                            ApiUsageCollector apiUsageCollector, ModelCatalogService modelCatalogService,
                            CallLifecyclePublisher callLifecyclePublisher,
                            @Value("${readme.host:localhost}") String readmeHost,
                            @Value("${server.port:11434}") int serverPort) {
        this.chatCompletionService = chatCompletionService;
        this.objectMapper = objectMapper;
        this.apiUsageCollector = apiUsageCollector;
        this.callLifecyclePublisher = callLifecyclePublisher;
        this.readmeHost = readmeHost;
        this.serverPort = serverPort;
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
    public Mono<ResponseEntity<?>> chatCompletions(@RequestBody OpenAiChatRequest request,
                                                   @RequestHeader HttpHeaders requestHeaders) {
        // 拦截兜底模型 nano_llm：无任何供应商启用时返回引导信息，避免调用上游 API
        if ("nano_llm".equals(request.getModel())) {
            return Mono.just(buildNanoLlmResponse(request.isStream()));
        }

        // 拦截虚拟模型 readme：输出当前所有已启用模型的 chatLanguageModels.json 配置
        if ("readme".equalsIgnoreCase(request.getModel())) {
            return Mono.just(buildReadmeResponse(request.isStream()));
        }

        Map<String, Object> requestBody = buildRequestBody(request);

        // 每次调用生成唯一 requestId，作为生命周期 Toast 的分组 key（每条 Reactor 订阅链一个，天然会话隔离）。
        String requestId = UUID.randomUUID().toString();
        String model = request.getModel();
        boolean stream = request.isStream();

        // RECEIVED：下游请求已被代理接收（虚拟模型拦截之后、真正调上游之前），同步发出。
        callLifecyclePublisher.publish(CallLifecycleEvent.of(requestId, CallPhase.RECEIVED, model, stream));

        // 流式：将 SSE 流作为 ResponseEntity 的 body 返回，由 WebFlux 框架托管背压、取消与超时。
        if (stream) {
            Flux<ServerSentEvent<String>> streamBody = streamResponse(requestBody, model, requestHeaders, requestId);
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header("Cache-Control", "no-cache")
                    .body(streamBody));
        }

        // 非流式：获取完整响应后提取 usage 进行记录，并返回给客户端。
        return chatCompletionService.chatCompletion(requestBody, model, requestHeaders)
                // CONNECTED：已向上游发起调用，正在等待响应。
                .doOnSubscribe(subscription -> callLifecyclePublisher.publish(
                        CallLifecycleEvent.of(requestId, CallPhase.CONNECTED, model, stream)))
                .doOnNext(this::recordUsage)
                // COMPLETED：非流式无 chunk 计数，最终计数为 0。
                .doOnNext(json -> callLifecyclePublisher.publish(
                        CallLifecycleEvent.of(requestId, CallPhase.COMPLETED, model, stream, 0)))
                .<ResponseEntity<?>>map(openAiJson -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(openAiJson))
                .onErrorResume(ex -> {
                    if (isClientDisconnect(ex)) {
                        // CANCELED：客户端主动断连，发出终态让 Toast 收尾淡出，避免僵尸 Toast。
                        callLifecyclePublisher.publish(CallLifecycleEvent.of(requestId, CallPhase.CANCELED, model, stream));
                        return Mono.empty();
                    }
                    // FAILED：上游错误或连接失败（客户端主动断连已在上面 return，不计入）。
                    callLifecyclePublisher.publish(CallLifecycleEvent.of(requestId, CallPhase.FAILED, model, stream));
                    // 透传上游错误响应（重试耗尽时 WebClientResponseException 被包装在 RetryExhaustedException 中，需要解包）
                    WebClientResponseException responseException = findWebResponseException(ex);
                    if (responseException != null) {
                        log.warn("上游 API 返回错误 [{}] {}: {}", model, responseException.getStatusCode().value(), responseException.getResponseBodyAsString());
                        return Mono.just(ResponseEntity.status(responseException.getStatusCode().value())
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(responseException.getResponseBodyAsString()));
                    }
                    log.warn("上游 API 调用失败 [{}]: {} ({})", model, extractRootCause(ex), extractRequestUrl(ex));
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
    private Flux<ServerSentEvent<String>> streamResponse(Map<String, Object> requestBody, String model,
                                                          HttpHeaders requestHeaders, String requestId) {
        AtomicInteger streamInputTokens = new AtomicInteger(0);
        AtomicInteger streamOutputTokens = new AtomicInteger(0);
        // chunkCount 记录累计 chunk 数；lastPushAt 用于对 CHUNK 事件做时间节流，避免长响应产生每秒上百 SSE 帧。
        AtomicInteger chunkCount = new AtomicInteger(0);
        AtomicLong lastChunkPushAt = new AtomicLong(0L);

        return chatCompletionService.chatCompletionStream(requestBody, model, requestHeaders)
                // CONNECTED：已向上游发起调用，正在等待首字响应。
                .doOnSubscribe(subscription -> callLifecyclePublisher.publish(
                        CallLifecycleEvent.of(requestId, CallPhase.CONNECTED, model, true)))
                .doOnNext(chunk -> {
                    accumulateStreamUsage(chunk, streamInputTokens, streamOutputTokens);
                    publishChunkThrottled(requestId, model, chunkCount.incrementAndGet(), lastChunkPushAt);
                })
                .map(chunk -> ServerSentEvent.builder(chunk).build())
                .doOnComplete(() -> {
                    apiUsageCollector.record(streamInputTokens.get(), streamOutputTokens.get());
                    // COMPLETED：带最终精确 chunk 总数，纠正节流期间可能漏推的中间计数。
                    callLifecyclePublisher.publish(
                            CallLifecycleEvent.of(requestId, CallPhase.COMPLETED, model, true, chunkCount.get()));
                })
                .onErrorResume(error -> {
                    if (isClientDisconnect(error)) {
                        // CANCELED：客户端主动断连，发出终态让 Toast 收尾淡出，避免僵尸 Toast。
                        callLifecyclePublisher.publish(CallLifecycleEvent.of(requestId, CallPhase.CANCELED, model, true, chunkCount.get()));
                        return Flux.empty();
                    }
                    // FAILED：上游错误或连接失败（客户端主动断连已在上面 return，不计入）。
                    callLifecyclePublisher.publish(CallLifecycleEvent.of(requestId, CallPhase.FAILED, model, true));
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
     * 对 CHUNK 生命周期事件做时间节流后推送。
     *
     * <p>首个 chunk（{@code count == 1}）立即推送——这是"首字到达"的关键状态转换，
     * 前端 Toast 据此从"等待首字响应"切换到"已产生 chunk"。之后每 {@link #CHUNK_PUSH_INTERVAL_MS}
     * 毫秒最多推一次最新计数，避免长响应把 SSE 通道打爆。最终精确计数由 COMPLETED 事件兜底。
     *
     * @param requestId    调用唯一标识
     * @param model        模型名称
     * @param count        当前累计 chunk 数
     * @param lastPushAt   上次推送时间戳（毫秒）的原子引用，用于节流判定
     */
    private void publishChunkThrottled(String requestId, String model, int count, AtomicLong lastPushAt) {
        long now = System.currentTimeMillis();
        long previous = lastPushAt.get();
        boolean firstChunk = count == 1;
        if (firstChunk || now - previous >= CHUNK_PUSH_INTERVAL_MS) {
            if (lastPushAt.compareAndSet(previous, now)) {
                callLifecyclePublisher.publish(
                        CallLifecycleEvent.of(requestId, CallPhase.CHUNK, model, true, count));
            }
        }
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
     * 为兜底模型 nano_llm 构建引导响应。
     * 当系统没有任何供应商启用时，tags 接口会返回 nano_llm，
     * 下游选中该模型聊天时，直接返回配置引导信息，不调用上游 API。
     */
    private ResponseEntity<?> buildNanoLlmResponse(boolean stream) {
        String guideMessage = "当前没有配置任何 AI 供应商。请打开 [COSP管理后台](http://localhost:11434) ，默认账号密码均为 root ，"
                + "添加并启用至少一个供应商及其 API Key，然后重新连接 Copilot。";
        String chunkId = "chatcmpl-nano-" + System.currentTimeMillis();
        long created = System.currentTimeMillis() / 1000;

        if (stream) {
            // 构建标准 OpenAI SSE 流式响应：content chunk + finish chunk + [DONE]
            String contentChunk = "{\"id\":\"" + chunkId + "\",\"object\":\"chat.completion.chunk\",\"created\":" + created
                    + ",\"model\":\"nano_llm\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\""
                    + guideMessage.replace("\"", "\\\"") + "\"},\"finish_reason\":null}]}";
            String finishChunk = "{\"id\":\"" + chunkId + "\",\"object\":\"chat.completion.chunk\",\"created\":" + created
                    + ",\"model\":\"nano_llm\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";

            Flux<ServerSentEvent<String>> sseStream = Flux.just(
                    ServerSentEvent.builder(contentChunk).build(),
                    ServerSentEvent.builder(finishChunk).build(),
                    ServerSentEvent.builder("[DONE]").build()
            );
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header("Cache-Control", "no-cache")
                    .body(sseStream);
        }

        // 非流式：完整 OpenAI JSON 响应
        String json = "{\"id\":\"" + chunkId + "\",\"object\":\"chat.completion\",\"created\":" + created
                + ",\"model\":\"nano_llm\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                + guideMessage.replace("\"", "\\\"") + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
    }

    /**
     * 为虚拟模型 readme 构建配置输出响应。
     *
     * 读取当前所有已启用模型，生成符合 chatLanguageModels.json 格式的 JSON 配置，
     * 以 Markdown 代码块形式作为聊天回复返回给用户，方便直接复制粘贴到 VS Code 配置中。
     */
    private ResponseEntity<?> buildReadmeResponse(boolean stream) {
        List<AvailableModel> models = modelCatalogService.listAvailableModels();
        String baseUrl = "http://" + readmeHost + ":" + serverPort + "/v1";

        // 构建 models 数组
        List<Map<String, Object>> modelEntries = new ArrayList<>();
        for (AvailableModel m : models) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", m.prefixedName());
            entry.put("name", m.prefixedName());
            entry.put("url", baseUrl);
            entry.put("toolCalling", m.capsTools());
            entry.put("vision", m.capsVision());
            if (m.contextSize() > 0) {
                entry.put("maxInputTokens", m.contextSize());
            }
            if (m.maxOutputTokens() > 0) {
                entry.put("maxOutputTokens", m.maxOutputTokens());
            }
            modelEntries.add(entry);
        }

        // 构建外层 chatLanguageModels 条目
        Map<String, Object> providerEntry = new LinkedHashMap<>();
        providerEntry.put("name", "COSP");
        providerEntry.put("vendor", "customendpoint");
        providerEntry.put("apiType", "chat-completions");
        providerEntry.put("models", modelEntries);

        List<Map<String, Object>> config = List.of(providerEntry);

        // 序列化为格式化 JSON
        String jsonContent;
        try {
            jsonContent = objectMapper.copy()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(config);
        } catch (Exception e) {
            log.warn("README 模型 JSON 序列化失败: {}", e.getMessage());
            jsonContent = "[]";
        }

        String markdownContent = "以下是当前 COSP 的 `chatLanguageModels.json` 配置，"
                + "复制到 VS Code 的 `chatLanguageModels.json` 中即可使用：\n\n"
                + "```json\n" + jsonContent + "\n```";

        String chunkId = "chatcmpl-readme-" + System.currentTimeMillis();
        long created = System.currentTimeMillis() / 1000;

        if (stream) {
            // 使用 ObjectMapper 安全转义 content 中的特殊字符
            String escapedContent;
            try {
                escapedContent = objectMapper.writeValueAsString(markdownContent);
                // writeValueAsString 返回带双引号的字符串，去掉首尾引号以嵌入 JSON
                escapedContent = escapedContent.substring(1, escapedContent.length() - 1);
            } catch (Exception e) {
                escapedContent = markdownContent.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            }

            String contentChunk = "{\"id\":\"" + chunkId + "\",\"object\":\"chat.completion.chunk\",\"created\":" + created
                    + ",\"model\":\"readme\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\""
                    + escapedContent + "\"},\"finish_reason\":null}]}";
            String finishChunk = "{\"id\":\"" + chunkId + "\",\"object\":\"chat.completion.chunk\",\"created\":" + created
                    + ",\"model\":\"readme\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";

            Flux<ServerSentEvent<String>> sseStream = Flux.just(
                    ServerSentEvent.builder(contentChunk).build(),
                    ServerSentEvent.builder(finishChunk).build(),
                    ServerSentEvent.builder("[DONE]").build()
            );
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header("Cache-Control", "no-cache")
                    .body(sseStream);
        }

        // 非流式
        String escapedContent;
        try {
            escapedContent = objectMapper.writeValueAsString(markdownContent);
            escapedContent = escapedContent.substring(1, escapedContent.length() - 1);
        } catch (Exception e) {
            escapedContent = markdownContent.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }

        String json = "{\"id\":\"" + chunkId + "\",\"object\":\"chat.completion\",\"created\":" + created
                + ",\"model\":\"readme\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                + escapedContent + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
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

package com.kaixuan.copilot_ollama_proxy.api.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kaixuan.copilot_ollama_proxy.api.shared.NonStreamLifecycle;
import com.kaixuan.copilot_ollama_proxy.api.shared.StreamLifecycle;
import com.kaixuan.copilot_ollama_proxy.api.shared.UpstreamErrorRenderer;
import com.kaixuan.copilot_ollama_proxy.api.shared.UpstreamFailureClassifier;
import com.kaixuan.copilot_ollama_proxy.pipeline.entry.ChatCompletionService;
import com.kaixuan.copilot_ollama_proxy.application.catalog.AvailableModel;
import com.kaixuan.copilot_ollama_proxy.application.catalog.ModelCatalogService;
import com.kaixuan.copilot_ollama_proxy.observability.record.ApiUsageDailyService;
import com.kaixuan.copilot_ollama_proxy.control.CallCancellationRegistry;
import com.kaixuan.copilot_ollama_proxy.observability.publisher.CallLifecyclePublisher;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    private final ChatCompletionService chatCompletionService;
    private final ObjectMapper objectMapper;
    private final ApiUsageDailyService apiUsageCollector;
    private final ModelCatalogService modelCatalogService;
    private final CallLifecyclePublisher callLifecyclePublisher;
    private final CallCancellationRegistry callCancellationRegistry;
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
                            ApiUsageDailyService apiUsageCollector, ModelCatalogService modelCatalogService,
                            CallLifecyclePublisher callLifecyclePublisher,
                            CallCancellationRegistry callCancellationRegistry,
                            @Value("${readme.host:localhost}") String readmeHost,
                            @Value("${server.port:11434}") int serverPort) {
        this.chatCompletionService = chatCompletionService;
        this.objectMapper = objectMapper;
        this.apiUsageCollector = apiUsageCollector;
        this.callLifecyclePublisher = callLifecyclePublisher;
        this.callCancellationRegistry = callCancellationRegistry;
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

        // 非流式：收尾协议（注册取消 → firstWithSignal → 记 usage → COMPLETED
        // → 三分支错误 → doOnCancel → doFinally）收归 NonStreamLifecycle ——
        // 三条端点此前各写一份逐字相同的实现。本处只交代「哪条链」与「错误长什么样」。
        return NonStreamLifecycle.attach(
                chatCompletionService.chatCompletion(requestBody, model, requestHeaders, requestId),
                new NonStreamLifecycle.CallContext(requestId, model, stream,
                        callLifecyclePublisher, callCancellationRegistry, apiUsageCollector, log),
                ex -> openAiErrorResponse(ex, model));
    }

    /**
     * 处理流式响应，将上游服务的 SSE 片段映射为 ServerSentEvent 逐个下发给客户端，
     * 并在完成时记录累计的 token 使用量。
     *
     * <p>相比旧的 SseEmitter 手动订阅模型，这里直接返回 Flux，由 WebFlux 框架托管
     * 背压、取消和超时，无需手动管理 Disposable 与回调。
     *
     * <h2>职责分界（阶段 6 步 3 后）</h2>
     * 本方法只交待<strong>三件事</strong>：拉哪条链、每帧长什么样、错误长什么样。
     * 逐帧副作用（累积 usage、判终止、数 CHUNK）与收尾协议（取消 / 终止 / 心跳 / 清理）
     * 都在 {@link StreamLifecycle#stream} 里 —— 三条端点共用那一份。
     *
     * @param requestBody 请求体内容，已转换为 Map 格式
     * @param model 模型名称
     * @return ServerSentEvent 流
     */
    private Flux<ServerSentEvent<String>> streamResponse(Map<String, Object> requestBody, String model,
                                                          HttpHeaders requestHeaders, String requestId) {
        return StreamLifecycle.stream(
                chatCompletionService.chatCompletionStream(requestBody, model, requestHeaders, requestId),
                new StreamLifecycle.CallContext(requestId, model,
                        new AtomicInteger(0), new AtomicBoolean(false), new AtomicBoolean(false),
                        new AtomicReference<>(null),
                        callLifecyclePublisher, callCancellationRegistry, apiUsageCollector, log),
                // Layer 1 相位：恒为 COMPLETED —— Chat 的终止标记 [DONE] 不携带结局信息。
                event -> CallPhase.COMPLETED,
                // Chat 不回填 SSE 的 event 名：OpenAI 客户端只认 data，这是本端点与另两条的差异之一。
                event -> ServerSentEvent.builder(event.data()).build(),
                // 流式与非流式共用同一套 body 形状 —— Chat 的错误体两条路径同形
                // （Anthropic 同理；只有 Responses 刻意不同，见那边的注释）。
                error -> UpstreamErrorRenderer.streamBody(error, model, log, BODIES));
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
     * 把调用失败渲染成<strong>非流式</strong>响应。
     *
     * <h2>分工</h2>
     * 「这是什么失败」交给 {@link UpstreamFailureClassifier}（三条线路共用一份判定）；
     * 「状态码与日志」交给 {@link UpstreamErrorRenderer}（同样三条共用）；
     * <strong>「长什么样」留在本类</strong> —— 错误 JSON 骨架是<strong>出口</strong>，由下游协议决定。
     *
     * <p>状态码分层的理由（400 vs 502、上游 HTTP 原样透传）见渲染器的 Javadoc。
     */
    private ResponseEntity<?> openAiErrorResponse(Throwable ex, String model) {
        return UpstreamErrorRenderer.response(ex, model, log, BODIES);
    }

    /**
     * OpenAI 形态的错误体 —— 本端点自备（出口判据：形状由下游协议决定）。
     *
     * <p>{@code type} 取值分两档：400 类用 {@code invalid_request_error}，
     * 502 类用 {@code upstream_error}。
     *
     * <p>是<strong>实例字段</strong>而非 static：它要调 {@link #openAiErrorBody}，
     * 而后者用注入的 {@code objectMapper} 序列化。
     */
    private final UpstreamErrorRenderer.ErrorBodies BODIES =
            new UpstreamErrorRenderer.ErrorBodies() {
                @Override
                public String badRequest(String message) {
                    return openAiErrorBody(message, "invalid_request_error");
                }

                @Override
                public String upstreamError(String message) {
                    return openAiErrorBody(message, "upstream_error");
                }
            };

    /**
     * OpenAI 风格错误体。
     *
     * <p>走序列化而不拼字符串：message 可能来自异常消息，内容不可控；一个引号或换行
     * 就能把错误体本身变成非法 JSON，而客户端解析失败后看到的是一个完全无关的错误。
     */
    private String openAiErrorBody(String message, String type) {
        java.util.Map<String, Object> error = new java.util.LinkedHashMap<>();
        error.put("message", message);
        error.put("type", type);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", error);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            return "{\"error\":{\"message\":\"上游调用失败\",\"type\":\"upstream_error\"}}";
        }
    }
}

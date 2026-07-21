package com.kaixuan.copilot_ollama_proxy.api.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kaixuan.copilot_ollama_proxy.application.openai.ChatCompletionService;
import com.kaixuan.copilot_ollama_proxy.application.catalog.AvailableModel;
import com.kaixuan.copilot_ollama_proxy.application.catalog.ModelCatalogService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.ApiUsageCollector;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallCancellationRegistry;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.provider.CallCanceledException;
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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * 首字后 chunk 间空闲超此毫秒数即发 STALLED 警告（非终态，可恢复）。
     *
     * <p>仅作为提示：告知前端「上游停滞」并放开手动断连控件，由用户决定是否中止。
     * 后端<strong>不再</strong>自动断连——因为工具调用等场景可能把大块内容塞进单个 chunk 导致
     * 首字后长时间阻塞，自动断连会误杀正常请求。是否断连交给用户判断。
     */
    private static final long STREAM_STALL_WARNING_MS = 30_000L;

    private final ChatCompletionService chatCompletionService;
    private final ObjectMapper objectMapper;
    private final ApiUsageCollector apiUsageCollector;
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
                            ApiUsageCollector apiUsageCollector, ModelCatalogService modelCatalogService,
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

        // 非流式：获取完整响应后提取 usage 进行记录，并返回给客户端。
        // CONNECTED 现由 provider 层在上游响应真正到达时发出（更准确），此处不再乐观发出。
        // 注册取消信号：外部点击取消时，cancelSignal 正常 complete，firstWithSignal 会抛 CallCanceledException 中止 chat 链。
        Mono<String> cancelSignal = callCancellationRegistry.register(requestId)
                .then(Mono.error(new CallCanceledException()));
        return Mono.firstWithSignal(
                        chatCompletionService.chatCompletion(requestBody, model, requestHeaders, requestId),
                        cancelSignal)
                .doOnNext(this::recordUsage)
                // COMPLETED：非流式无 chunk 计数，最终计数为 0。
                .doOnNext(json -> callLifecyclePublisher.publish(
                        CallLifecycleEvent.of(requestId, CallPhase.COMPLETED, model, stream, 0)))
                .<ResponseEntity<?>>map(openAiJson -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(openAiJson))
                .onErrorResume(ex -> {
                    // ABORTED：管理后台主动取消，静默断开连接（不注入错误体），下游 Copilot 自行处理。
                    if (ex instanceof CallCanceledException) {
                        callLifecyclePublisher.publish(CallLifecycleEvent.of(requestId, CallPhase.ABORTED, model, stream));
                        log.info("调用被主动取消 [{}] {}", model, requestId);
                        return Mono.empty();
                    }
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
                })
                // CANCELED：下游（Copilot）主动断连是 Reactor 的 cancel 信号，onErrorResume 捕获不到，
                // 必须用 doOnCancel 感知，否则不发终态事件 → inFlight 记录永久留存 → 僵尸 toast。
                .doOnCancel(() -> {
                    callLifecyclePublisher.publish(CallLifecycleEvent.of(requestId, CallPhase.CANCELED, model, stream));
                    log.info("下游主动断连 [{}] {}", model, requestId);
                })
                // 无论正常结束、失败还是取消，都清理注册表，避免内存泄漏。
                .doFinally(signal -> callCancellationRegistry.remove(requestId));
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
        // chunkCount 记录累计 chunk 数，每个 chunk 到达即推一次 CHUNK 事件，让 Toast 计数逐个跟手更新。
        AtomicInteger chunkCount = new AtomicInteger(0);
        // canceled 标志：外部主动取消时置位，用于在流结束后区分 ABORTED 与正常 COMPLETED。
        java.util.concurrent.atomic.AtomicBoolean canceled = new java.util.concurrent.atomic.AtomicBoolean(false);
        // completed 标志：Layer 1（[DONE] 语义信号）与 Layer 2（doOnComplete TCP 关闭）去重，谁先到谁发 COMPLETED。
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        // 首字后的空闲看门狗状态：
        // lastChunkAt 记录最近一次 chunk 到达的纳秒时间戳（<0 表示尚未收到首字，不计时——尊重「首字可无限等」）。
        // stalledEmitted 保证 STALLED 警告只发一次（除非收到新 chunk 后重置）。
        java.util.concurrent.atomic.AtomicLong lastChunkAt = new java.util.concurrent.atomic.AtomicLong(-1L);
        java.util.concurrent.atomic.AtomicBoolean stalledEmitted = new java.util.concurrent.atomic.AtomicBoolean(false);

        // 注册取消信号：外部点击取消时 cancelSignal 正常 complete，takeUntilOther 会中止上游流。
        // 取消行为对下游一律静默断连（不注入错误帧）：无论首字前还是首字后停滞取消，语义一致，
        // 下游 Copilot 自行处理断连（工具调用整块 chunk 阻塞时，注入错误/硬超时都可能导致重复消耗，故不做）。
        Mono<Void> cancelSignal = callCancellationRegistry.register(requestId)
                .doOnSuccess(v -> canceled.set(true));

        // 首字后空闲看门狗（独立订阅，不并入返回的数据流，以免 interval 永不完成而污染数据流的完成信号）。
        // 每秒检查距上次 chunk 的间隔，仅在收到首字后（lastChunkAt >= 0）生效——尊重「首字可无限等」：
        // 停滞超 STREAM_STALL_WARNING_MS 发 STALLED 警告（非终态，可恢复），仅提示，不主动断连。
        // 是否断连交由用户在前端手动决定（避免误杀工具调用整块 chunk 的合理长阻塞）。
        // 在 doOnSubscribe 启动、doFinally 释放，随请求生命周期存活。
        java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable> watchdogRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        Runnable startWatchdog = () -> watchdogRef.set(Flux.interval(Duration.ofSeconds(1))
                .subscribe(tick -> {
                    long last = lastChunkAt.get();
                    if (last < 0 || completed.get() || canceled.get()) {
                        return;
                    }
                    long idleMs = (System.nanoTime() - last) / 1_000_000L;
                    if (idleMs >= STREAM_STALL_WARNING_MS && stalledEmitted.compareAndSet(false, true)) {
                        // 停滞警告：发 STALLED（非终态，inFlight 保留），提示连接停滞但仍可恢复。
                        // 不主动断连——上游可能在为工具调用组装整块 chunk（如创建文件），属合理长阻塞。
                        callLifecyclePublisher.publish(
                                CallLifecycleEvent.of(requestId, CallPhase.STALLED, model, true, chunkCount.get()));
                    }
                }));

        // CONNECTED 现由 provider 层在上游响应真正到达时发出（更准确），此处不再乐观发出。
        return chatCompletionService.chatCompletionStream(requestBody, model, requestHeaders, requestId)
                .doOnNext(chunk -> {
                    accumulateStreamUsage(chunk, streamInputTokens, streamOutputTokens);
                    // Layer 1（语义信号优先）：收到 [DONE] 即认定上游内容已发完，立即 finalize，
                    // 不必等上游关闭 TCP 连接。修复「上游发完 [DONE] 却不断连，Toast 永远悬挂在 CHUNK」的偶发 bug。
                    // [DONE] 是协议终止标记，不计入 chunk 数。
                    if ("[DONE]".equals(chunk)) {
                        finalizeStreamCompletion(requestId, model, chunkCount.get(), completed,
                                streamInputTokens, streamOutputTokens);
                        return;
                    }
                    // 收到内容 chunk：刷新看门狗时间戳，并清除 STALLED 警告标志（若之前停滞过，现在恢复了）。
                    lastChunkAt.set(System.nanoTime());
                    if (stalledEmitted.compareAndSet(true, false)) {
                        // 从 STALLED 恢复：重新发一次 CHUNK 让前端退回正常态（chunkCount 会在下面自增）。
                        log.debug("流式调用从停滞恢复 [{}] {}", model, requestId);
                    }
                    // 每个 chunk 都推一次 CHUNK 事件（不节流）。单次响应 chunk 数通常不过数百，SSE 开销可接受。
                    callLifecyclePublisher.publish(
                            CallLifecycleEvent.of(requestId, CallPhase.CHUNK, model, true, chunkCount.incrementAndGet()));
                })
                .map(chunk -> ServerSentEvent.builder(chunk).build())
                // 订阅时启动首字后空闲看门狗（独立订阅，见上方 startWatchdog）。
                .doOnSubscribe(sub -> startWatchdog.run())
                .takeUntilOther(cancelSignal)
                // 取消时静默断连：只发 ABORTED 终态事件，不向下游注入任何错误帧，下游自行处理断连。
                .concatWith(Flux.defer(() -> {
                    if (canceled.get()) {
                        callLifecyclePublisher.publish(CallLifecycleEvent.of(requestId, CallPhase.ABORTED, model, true, chunkCount.get()));
                        log.info("流式调用被主动取消，静默断连 [{}] {}", model, requestId);
                    }
                    return Flux.<ServerSentEvent<String>>empty();
                }))
                .doOnComplete(() -> {
                    // 取消时不发 COMPLETED（已由 concatWith 发 ABORTED）。
                    if (canceled.get()) {
                        return;
                    }
                    // Layer 2（TCP/SSE 连接关闭兜底）：上游未发 [DONE] 就直接关连接时，靠这里 finalize。
                    // 若 Layer 1 已在收到 [DONE] 时 finalize，completed 标志会让这里成为 no-op（去重）。
                    finalizeStreamCompletion(requestId, model, chunkCount.get(), completed,
                            streamInputTokens, streamOutputTokens);
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
                })
                // CANCELED：下游（Copilot）主动断连是 Reactor 的 cancel 信号，onErrorResume 捕获不到，
                // 必须用 doOnCancel 感知。管理员取消走 takeUntilOther→concatWith 正常 complete（不触发此处），
                // 正常/失败结束也走 complete/error，故此处只会在「下游真断连」时命中。
                // 用 canceled/completed 守卫兜底：若终态已发出则不重复发，避免多条终态事件。
                .doOnCancel(() -> {
                    if (canceled.get() || completed.get()) {
                        return;
                    }
                    callLifecyclePublisher.publish(CallLifecycleEvent.of(requestId, CallPhase.CANCELED, model, true, chunkCount.get()));
                    log.info("下游主动断连，静默收尾 [{}] {}", model, requestId);
                })
                // 无论正常结束、失败还是取消，都清理注册表与看门狗，避免内存/定时器泄漏。
                .doFinally(signal -> {
                    callCancellationRegistry.remove(requestId);
                    reactor.core.Disposable wd = watchdogRef.get();
                    if (wd != null) {
                        wd.dispose();
                    }
                });
    }

    /**
     * 流式完成收尾：记录 usage 并发出 COMPLETED 事件。
     *
     * <p>由三层完成判定的前两层共用（Layer 1 收到 {@code [DONE]}、Layer 2 上游关闭连接），
     * 用 {@code completed} 标志 CAS 去重，保证只 finalize 一次——谁先到谁发，另一层成为 no-op。
     * 这样既能在「上游发完 [DONE] 却不断连」时立即收尾（修复 Toast 悬挂），
     * 也能在「上游不发 [DONE] 直接断连」时靠 Layer 2 兜底。
     *
     * @param requestId    调用唯一标识
     * @param model        模型名称
     * @param finalChunks  最终 chunk 总数（[DONE] 不计入）
     * @param completed    完成去重标志（CAS）
     * @param inputTokens  累计输入 token
     * @param outputTokens 累计输出 token
     */
    private void finalizeStreamCompletion(String requestId, String model, int finalChunks,
                                          java.util.concurrent.atomic.AtomicBoolean completed,
                                          AtomicInteger inputTokens, AtomicInteger outputTokens) {
        // CAS 去重：只有第一个到达的层能 finalize，另一层直接返回。
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        apiUsageCollector.record(inputTokens.get(), outputTokens.get());
        // COMPLETED：带最终精确 chunk 总数作为兜底，确保前端计数与实际一致。
        callLifecyclePublisher.publish(
                CallLifecycleEvent.of(requestId, CallPhase.COMPLETED, model, true, finalChunks));
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

package com.kaixuan.copilot_ollama_proxy.api.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.api.shared.StreamLifecycle;
import com.kaixuan.copilot_ollama_proxy.api.shared.UpstreamFailureClassifier;
import com.kaixuan.copilot_ollama_proxy.application.anthropic.MessagesService;
import com.kaixuan.copilot_ollama_proxy.control.CallCancellationRegistry;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicMessagesRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import com.kaixuan.copilot_ollama_proxy.control.CallCanceledException;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamEventClassifier;
import com.kaixuan.copilot_ollama_proxy.upstream.send.messages.AnthropicUsageParser;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.ApiUsageCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Anthropic Messages API 控制器 —— 处理下游以 Anthropic 协议发来的请求。
 *
 * <p>端点：{@code POST /v1/messages}
 *
 * <p>与 {@code OpenAiController} 的职责边界相同：只管 HTTP 层（接收、封装、SSE 转发），
 * 路由与协议调度在应用层，上游执行在 provider 层。
 *
 * <h2>与 OpenAI 端点的两处语义差异</h2>
 * <ol>
 *   <li><strong>SSE 帧带 {@code event:} 类型</strong> —— Anthropic 客户端是状态机，
 *       靠事件类型驱动，不能只发 data。故转发时要从事件 JSON 的 {@code type} 字段
 *       取出类型回填到 SSE 的 event 名。</li>
 *   <li><strong>流结束标记是 {@code message_stop} 而非 {@code [DONE]}</strong> ——
 *       完成判定的 Layer 1 据此触发。</li>
 * </ol>
 *
 * <h2>虚拟模型不在此端点提供</h2>
 * {@code nano_llm} / {@code readme} 是 OpenAI 端点的引导机制（供 Copilot 在无供应商时
 * 看到提示），Anthropic 客户端不需要，故本端点不做拦截 —— 少一处需要同步维护的分支。
 */
@RestController
public class AnthropicController {

    private static final Logger log = LoggerFactory.getLogger(AnthropicController.class);

    private final MessagesService messagesService;
    private final ObjectMapper objectMapper;
    private final ApiUsageCollector apiUsageCollector;
    private final CallLifecyclePublisher callLifecyclePublisher;
    private final CallCancellationRegistry callCancellationRegistry;

    public AnthropicController(MessagesService messagesService, ObjectMapper objectMapper,
                               ApiUsageCollector apiUsageCollector,
                               CallLifecyclePublisher callLifecyclePublisher,
                               CallCancellationRegistry callCancellationRegistry) {
        this.messagesService = messagesService;
        this.objectMapper = objectMapper;
        this.apiUsageCollector = apiUsageCollector;
        this.callLifecyclePublisher = callLifecyclePublisher;
        this.callCancellationRegistry = callCancellationRegistry;
    }

    /**
     * 处理 Messages 请求，支持流式与非流式。
     *
     * @return 统一返回 {@code Mono<ResponseEntity<?>>}；非流式 body 为完整 JSON 字符串，
     *         流式 body 为 {@code ServerSentEvent} 流
     */
    @PostMapping(value = "/v1/messages")
    public Mono<ResponseEntity<?>> messages(@RequestBody AnthropicMessagesRequest request,
                                            @RequestHeader HttpHeaders requestHeaders) {
        Map<String, Object> requestBody = buildRequestBody(request);

        String requestId = UUID.randomUUID().toString();
        String model = request.getModel();
        boolean stream = request.isStream();

        // RECEIVED：下游请求已被代理接收，同步发出。
        callLifecyclePublisher.publish(CallLifecycleEvent.of(requestId, CallPhase.RECEIVED, model, stream));

        if (stream) {
            Flux<ServerSentEvent<String>> streamBody =
                    streamResponse(requestBody, model, requestHeaders, requestId);
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header("Cache-Control", "no-cache")
                    .body(streamBody));
        }

        // 非流式：注册取消信号，外部点击取消时抛 CallCanceledException 中止链。
        Mono<String> cancelSignal = callCancellationRegistry.register(requestId)
                .then(Mono.error(new CallCanceledException()));
        return Mono.firstWithSignal(
                        // 出口处拆包：主干是统一形态（UpstreamEvent），本端点下游要的是裸 JSON。
                        messagesService.messages(requestBody, model, requestHeaders, requestId)
                                .map(UpstreamEvent::data),
                        cancelSignal)
                .doOnNext(this::recordUsage)
                // COMPLETED：非流式无事件计数，最终计数为 0（前端已按 stream 分支处理文案）。
                .doOnNext(json -> callLifecyclePublisher.publish(
                        CallLifecycleEvent.of(requestId, CallPhase.COMPLETED, model, stream, 0)))
                .<ResponseEntity<?>>map(json -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON).body(json))
                .onErrorResume(ex -> {
                    if (ex instanceof CallCanceledException) {
                        callLifecyclePublisher.publish(
                                CallLifecycleEvent.of(requestId, CallPhase.ABORTED, model, stream));
                        log.info("调用被主动取消 [{}] {}", model, requestId);
                        return Mono.empty();
                    }
                    if (UpstreamFailureClassifier.isClientDisconnect(ex)) {
                        callLifecyclePublisher.publish(
                                CallLifecycleEvent.of(requestId, CallPhase.CANCELED, model, stream));
                        return Mono.empty();
                    }
                    callLifecyclePublisher.publish(
                            CallLifecycleEvent.of(requestId, CallPhase.FAILED, model, stream));
                    return Mono.just(errorResponse(ex, model));
                })
                // CANCELED：下游断连是 Reactor 的 cancel 信号，onErrorResume 捕获不到。
                .doOnCancel(() -> {
                    callLifecyclePublisher.publish(
                            CallLifecycleEvent.of(requestId, CallPhase.CANCELED, model, stream));
                    log.info("下游主动断连 [{}] {}", model, requestId);
                })
                .doFinally(signal -> callCancellationRegistry.remove(requestId));
    }

    /**
     * 处理流式响应，把上游事件流映射为带 {@code event:} 类型的 SSE 帧下发。
     *
     * <p>完成判定沿用 OpenAI 端点的两层结构：
     * Layer 1 收到 {@code message_stop} 即 finalize（不必等 TCP 关闭），
     * Layer 2 上游关闭连接时兜底，用 CAS 去重保证只 finalize 一次。
     *
     * <h2>职责分界</h2>
     * 本方法只负责<strong>把上游事件映射成带 event 名的 SSE 帧</strong>。
     * 其后的收尾协议在 {@link StreamLifecycle#attach} 里；
     * 本端点提供两个回调：Layer 2 的 finalize，以及 Anthropic 形态的 error 帧。
     */
    private Flux<ServerSentEvent<String>> streamResponse(Map<String, Object> requestBody, String model,
                                                          HttpHeaders requestHeaders, String requestId) {
        AtomicInteger eventCount = new AtomicInteger(0);
        AtomicBoolean canceled = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);
        // 流式 usage 跨事件累积：input 来自 message_start、output 来自 message_delta。
        AtomicReference<UsageTokens> usage = new AtomicReference<>(UsageTokens.EMPTY);

        Mono<Void> cancelSignal = callCancellationRegistry.register(requestId)
                .doOnSuccess(v -> canceled.set(true));

        // 数据流终止信号，心跳据此停止 —— 否则 interval 永不完成，merge 永不完成。
        Sinks.Empty<Void> streamEnd = Sinks.empty();

        Flux<ServerSentEvent<String>> mappedBody =
                messagesService.messagesStream(requestBody, model, requestHeaders, requestId)
                // 分类已由上游执行器完成：本层只读 isTerminal()。
                .doOnNext(upstreamEvent -> {
                    String event = upstreamEvent.data();
                    accumulateUsage(event, usage);
                    // Layer 1：message_stop 是协议终止标记，不计入事件数。
                    if (upstreamEvent.isTerminal()) {
                        finalizeCompletion(requestId, model, eventCount.get(), completed, usage);
                        return;
                    }
                    callLifecyclePublisher.publish(CallLifecycleEvent.of(
                            requestId, CallPhase.CHUNK, model, true, eventCount.incrementAndGet()));
                })
                // event 类型必须回填：Anthropic 客户端靠它驱动状态机，只发 data 无法解析。
                .map(upstreamEvent -> {
                    String event = upstreamEvent.data();
                    String type = extractEventType(event);
                    ServerSentEvent.Builder<String> builder = ServerSentEvent.builder(event);
                    if (type != null) {
                        builder.event(type);
                    }
                    return builder.build();
                });

        return StreamLifecycle.attach(mappedBody, cancelSignal, streamEnd,
                new StreamLifecycle.CallContext(requestId, model, eventCount, canceled, completed,
                        callLifecyclePublisher, callCancellationRegistry, log),
                // Layer 2：上游未发 message_stop 就关连接时靠这里兜底。
                () -> finalizeCompletion(requestId, model, eventCount.get(), completed, usage),
                // 错误以 Anthropic 的 error 事件形态下发，客户端才能识别。
                error -> ServerSentEvent.<String>builder(errorEventBody(error, model))
                        .event("error").build());
    }

    /**
     * 流式完成收尾：记录 usage 并发 COMPLETED。
     *
     * <p>CAS 去重，保证 Layer 1 与 Layer 2 只有先到的那个生效。
     */
    private void finalizeCompletion(String requestId, String model, int finalEvents,
                                    AtomicBoolean completed, AtomicReference<UsageTokens> usage) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        UsageTokens tokens = usage.get();
        apiUsageCollector.record(tokens.promptOrZero(), tokens.completionOrZero());
        callLifecyclePublisher.publish(
                CallLifecycleEvent.of(requestId, CallPhase.COMPLETED, model, true, finalEvents));
    }

    /**
     * 把 DTO 还原成出站请求体 Map。
     *
     * <p>显式字段先放，再合入 {@code additionalProperties} —— 后者承载所有未建模字段
     * （{@code temperature} / {@code tools} / {@code thinking} 等），
     * 这样无需逐个建模即可透传。
     *
     * <p>{@code stream} 不在此处设置：由上游服务按调用入口决定
     * （{@code prepareRequestBody} 会覆写），避免下游误传导致模式不符。
     */
    private Map<String, Object> buildRequestBody(AnthropicMessagesRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        if (request.getMessages() != null) {
            body.put("messages", request.getMessages());
        }
        if (request.getSystem() != null) {
            body.put("system", request.getSystem());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        // 未建模字段原样透传。放在最后，但不覆盖上面的显式字段 —— 两者本不重叠
        // （重叠的键已被 DTO 的 setter 消费，不会进 additionalProperties）。
        body.putAll(request.getAdditionalProperties());
        return body;
    }

    /**
     * 从事件 JSON 取出 {@code type} 作为 SSE 的 event 名。
     *
     * <p>这里曾有一个 {@code isMessageStop}，用于判「是不是终止事件」。
     * 它已删除：那个判断上移到了上游执行器的 {@code UpstreamEvent} 分类
     * —— 在那里按<strong>上游协议</strong>判，翻译路线下才判得对。
     * 而本方法只服务「把 event 名回填给客户端」，与协议判定无关，故保留。
     *
     * <p>实现委托给 {@link UpstreamEventClassifier#extractEventType}：
     * 它曾与 ResponsesController 的同名方法逐字相同（第三份在分类器里）。
     * 三份同样的解析逻辑属于「会静默分叉」的东西 ——
     * 某一处改了边界条件（如改成也认数字型 type）而另外两处没改，
     * 症状是「一个端点的 event 名回填正常、另一个不正常」，极难归因。
     * 本方法因此退化为适配器：把本类的 objectMapper 绑给它。
     */
    private String extractEventType(String event) {
        return UpstreamEventClassifier.extractEventType(objectMapper, event);
    }

    /** 从流式事件累积 usage（跨事件合并，见 {@link AnthropicUsageParser#merge}）。 */
    private void accumulateUsage(String event, AtomicReference<UsageTokens> usage) {
        String raw = AnthropicUsageParser.extractUsageRawJson(objectMapper, event);
        if (raw == null) {
            return;
        }
        usage.set(AnthropicUsageParser.merge(usage.get(),
                AnthropicUsageParser.parseUsageObject(objectMapper, raw)));
    }

    /** 从非流式响应提取 usage 并记入日聚合。 */
    private void recordUsage(String json) {
        String raw = AnthropicUsageParser.extractUsageRawJson(objectMapper, json);
        if (raw == null) {
            return;
        }
        UsageTokens tokens = AnthropicUsageParser.parseUsageObject(objectMapper, raw);
        if (!tokens.isEmpty()) {
            apiUsageCollector.record(tokens.promptOrZero(), tokens.completionOrZero());
        }
    }

    /**
     * 把调用失败渲染成<strong>非流式</strong>响应。
     *
     * <p>分类交给 {@link UpstreamFailureClassifier}（三条线路共用一份判定）；
     * 错误 JSON 骨架留在本类 —— 那是<strong>出口</strong>，Anthropic 比 OpenAI 系
     * 多一层 {@code "type":"error"}，客户端据此区分错误帧与内容帧。
     *
     * <p>状态码分两层：四个「上游没被连上」的类别回 400（改配置 / 改请求 / 改模型名即可，
     * 与上游可用性无关，5xx 会诱导无意义的重试），其余回 502。
     * 上游 HTTP 错误原样透传状态码与错误体。
     */
    private ResponseEntity<?> errorResponse(Throwable ex, String model) {
        UpstreamFailureClassifier.Failure failure = UpstreamFailureClassifier.classify(ex);
        switch (failure.kind()) {
            case PROTOCOL_UNSUPPORTED -> {
                log.warn("协议不可用 [{}]: {}", model, failure.message());
                return badRequest(failure.message());
            }
            case NO_SUPPORTED_PROTOCOL -> {
                log.warn("供应商未配置任何协议 [{}]: {}", model, failure.message());
                return badRequest(failure.message());
            }
            case UNRESOLVED_MODEL_ROUTE -> {
                log.warn("模型未解析到供应商 [{}]: {}", model, failure.message());
                return badRequest(failure.message());
            }
            case UPSTREAM_HTTP -> {
                WebClientResponseException upstream = failure.asHttpFailure();
                log.warn("上游 API 返回错误 [{}] {}: {}", model,
                        upstream.getStatusCode().value(), upstream.getResponseBodyAsString());
                return ResponseEntity.status(upstream.getStatusCode().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(upstream.getResponseBodyAsString());
            }
            default -> {
                log.warn("上游 API 调用失败 [{}]: {}", model, ex.getMessage());
                return ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON)
                        .body(anthropicErrorBody("无法连接到上游服务"));
            }
        }
    }

    /** 400 + Anthropic 风格错误体。三个「上游没被连上」类别共用这一形。 */
    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.status(400).contentType(MediaType.APPLICATION_JSON)
                .body(anthropicErrorBody(message));
    }

    /**
     * 构造流式错误事件的 body，透传上游错误体。
     *
     * <p>与非流式共用同一份分类，仅送出口不同（流式状态码已定，只能靠事件体表达）。
     */
    private String errorEventBody(Throwable error, String model) {
        UpstreamFailureClassifier.Failure failure = UpstreamFailureClassifier.classify(error);
        return switch (failure.kind()) {
            case PROTOCOL_UNSUPPORTED -> {
                log.warn("协议不可用 [{}]: {}", model, failure.message());
                yield anthropicErrorBody(failure.message());
            }
            case NO_SUPPORTED_PROTOCOL -> {
                log.warn("供应商未配置任何协议 [{}]: {}", model, failure.message());
                yield anthropicErrorBody(failure.message());
            }
            case UNRESOLVED_MODEL_ROUTE -> {
                log.warn("模型未解析到供应商 [{}]: {}", model, failure.message());
                yield anthropicErrorBody(failure.message());
            }
            case UPSTREAM_HTTP -> {
                WebClientResponseException upstream = failure.asHttpFailure();
                log.warn("上游 API 返回错误 [{}] {}: {}", model,
                        upstream.getStatusCode().value(), upstream.getResponseBodyAsString());
                yield upstream.getResponseBodyAsString();
            }
            default -> {
                log.warn("上游 API 调用失败 [{}]: {}", model, error.getMessage());
                yield anthropicErrorBody("无法连接到上游服务");
            }
        };
    }

    /**
     * Anthropic 风格的错误体。
     *
     * <p>与 OpenAI 的 {@code {"error":{"message":...,"type":...}}} 结构相似但
     * {@code type} 取值不同（Anthropic 用 {@code api_error} / {@code overloaded_error} 等），
     * 且外层多一个 {@code "type":"error"} 标识 —— 客户端据此区分错误帧与内容帧。
     */
    private String anthropicErrorBody(String message) {
        // 走序列化而不拼字符串：message 可能来自异常消息（如协议不可用那条），
        // 内容不可控；一个引号或换行就能把错误体本身变成非法 JSON，
        // 而客户端解析失败后看到的是一个完全无关的错误。
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "api_error");
        error.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", error);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            return "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"上游调用失败\"}}";
        }
    }
}

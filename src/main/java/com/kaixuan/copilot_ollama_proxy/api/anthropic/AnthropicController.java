package com.kaixuan.copilot_ollama_proxy.api.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.anthropic.MessagesService;
import com.kaixuan.copilot_ollama_proxy.application.protocol.NoSupportedProtocolException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallCancellationRegistry;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicMessagesRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import com.kaixuan.copilot_ollama_proxy.provider.CallCanceledException;
import com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic.AnthropicUsageParser;
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

import java.time.Duration;
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

    /**
     * SSE 心跳周期，与 OpenAI 端点同值。
     *
     * <p>理由相同：空闲连接上 {@code channelInactive} 检测不可靠，上游等待首字或退避期间
     * 服务端一个字节都不写，下游断开可能要等到上游产生响应才被发现。周期写注释帧使
     * 写失败路径能及时兜底。
     */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);

    /** Anthropic 流的结束事件类型。等价于 OpenAI 的 {@code [DONE]}。 */
    private static final String EVENT_MESSAGE_STOP = "message_stop";

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
                        messagesService.messages(requestBody, model, requestHeaders, requestId),
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
                    if (isClientDisconnect(ex)) {
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

        Flux<ServerSentEvent<String>> streamBody =
                messagesService.messagesStream(requestBody, model, requestHeaders, requestId)
                .doOnNext(event -> {
                    accumulateUsage(event, usage);
                    // Layer 1：message_stop 是协议终止标记，不计入事件数。
                    if (isMessageStop(event)) {
                        finalizeCompletion(requestId, model, eventCount.get(), completed, usage);
                        return;
                    }
                    callLifecyclePublisher.publish(CallLifecycleEvent.of(
                            requestId, CallPhase.CHUNK, model, true, eventCount.incrementAndGet()));
                })
                // event 类型必须回填：Anthropic 客户端靠它驱动状态机，只发 data 无法解析。
                .map(event -> {
                    String type = extractEventType(event);
                    ServerSentEvent.Builder<String> builder = ServerSentEvent.builder(event);
                    if (type != null) {
                        builder.event(type);
                    }
                    return builder.build();
                })
                .takeUntilOther(cancelSignal)
                .concatWith(Flux.defer(() -> {
                    if (canceled.get()) {
                        callLifecyclePublisher.publish(CallLifecycleEvent.of(
                                requestId, CallPhase.ABORTED, model, true, eventCount.get()));
                        log.info("流式调用被主动取消，静默断连 [{}] {}", model, requestId);
                    }
                    return Flux.<ServerSentEvent<String>>empty();
                }))
                .doOnComplete(() -> {
                    if (canceled.get()) {
                        return;
                    }
                    // Layer 2：上游未发 message_stop 就关连接时靠这里兜底。
                    finalizeCompletion(requestId, model, eventCount.get(), completed, usage);
                })
                .onErrorResume(error -> {
                    if (isClientDisconnect(error)) {
                        callLifecyclePublisher.publish(CallLifecycleEvent.of(
                                requestId, CallPhase.CANCELED, model, true, eventCount.get()));
                        return Flux.empty();
                    }
                    callLifecyclePublisher.publish(
                            CallLifecycleEvent.of(requestId, CallPhase.FAILED, model, true));
                    // 错误以 Anthropic 的 error 事件形态下发，客户端才能识别。
                    return Flux.just(ServerSentEvent.<String>builder(errorEventBody(error, model))
                            .event("error").build());
                })
                .doOnCancel(() -> {
                    if (canceled.get() || completed.get()) {
                        return;
                    }
                    callLifecyclePublisher.publish(CallLifecycleEvent.of(
                            requestId, CallPhase.CANCELED, model, true, eventCount.get()));
                    log.info("下游主动断连，静默收尾 [{}] {}", model, requestId);
                })
                .doFinally(signal -> {
                    callCancellationRegistry.remove(requestId);
                    streamEnd.tryEmitEmpty();
                });

        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<String>builder().comment("keep-alive").build())
                .takeUntilOther(streamEnd.asMono());

        return Flux.merge(streamBody, heartbeat);
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

    /** 从事件 JSON 取出 {@code type} 作为 SSE 的 event 名。 */
    private String extractEventType(String event) {
        try {
            var node = objectMapper.readTree(event);
            var type = node.get("type");
            return type != null && type.isTextual() ? type.asText() : null;
        } catch (Exception exception) {
            return null;
        }
    }

    /** 是否流结束事件。 */
    private boolean isMessageStop(String event) {
        return EVENT_MESSAGE_STOP.equals(extractEventType(event));
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

    /** 构造非流式错误响应，透传上游状态码与错误体。 */
    private ResponseEntity<?> errorResponse(Throwable ex, String model) {
        ProtocolTranslationNotSupportedException protocolException = findProtocolException(ex);
        if (protocolException != null) {
            log.warn("协议不可用 [{}]: {}", model, protocolException.getMessage());
            return ResponseEntity.status(400).contentType(MediaType.APPLICATION_JSON)
                    .body(anthropicErrorBody(protocolException.getMessage()));
        }
        // 协议一个都没勾：同样是本地配置问题，不能落进 502 兜底。
        NoSupportedProtocolException noProtocol = findNoSupportedProtocolException(ex);
        if (noProtocol != null) {
            log.warn("供应商未配置任何协议 [{}]: {}", model, noProtocol.getMessage());
            return ResponseEntity.status(400).contentType(MediaType.APPLICATION_JSON)
                    .body(anthropicErrorBody(noProtocol.getMessage()));
        }
        WebClientResponseException responseException = findWebResponseException(ex);
        if (responseException != null) {
            log.warn("上游 API 返回错误 [{}] {}: {}", model,
                    responseException.getStatusCode().value(), responseException.getResponseBodyAsString());
            return ResponseEntity.status(responseException.getStatusCode().value())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(responseException.getResponseBodyAsString());
        }
        log.warn("上游 API 调用失败 [{}]: {}", model, ex.getMessage());
        return ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON)
                .body(anthropicErrorBody("无法连接到上游服务"));
    }

    /** 构造流式错误事件的 body，透传上游错误体。 */
    private String errorEventBody(Throwable error, String model) {
        ProtocolTranslationNotSupportedException protocolException = findProtocolException(error);
        if (protocolException != null) {
            log.warn("协议不可用 [{}]: {}", model, protocolException.getMessage());
            return anthropicErrorBody(protocolException.getMessage());
        }
        NoSupportedProtocolException noProtocol = findNoSupportedProtocolException(error);
        if (noProtocol != null) {
            log.warn("供应商未配置任何协议 [{}]: {}", model, noProtocol.getMessage());
            return anthropicErrorBody(noProtocol.getMessage());
        }
        WebClientResponseException responseException = findWebResponseException(error);
        if (responseException != null) {
            log.warn("上游 API 返回错误 [{}] {}: {}", model,
                    responseException.getStatusCode().value(), responseException.getResponseBodyAsString());
            return responseException.getResponseBodyAsString();
        }
        log.warn("上游 API 调用失败 [{}]: {}", model, error.getMessage());
        return anthropicErrorBody("无法连接到上游服务");
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

    /** 判断异常是否由客户端断连引起。 */
    private boolean isClientDisconnect(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String simpleName = current.getClass().getSimpleName();
            if ("AbortedException".equals(simpleName) || "ClientAbortException".equals(simpleName)
                    || "EOFException".equals(simpleName) || "AsyncRequestNotUsableException".equals(simpleName)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 递归解包 WebClientResponseException（重试耗尽时被包进 RetryExhaustedException）。 */
    private WebClientResponseException findWebResponseException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WebClientResponseException responseException) {
                return responseException;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 递归解包协议不可用异常。
     *
     * <p>必须在解包 {@link WebClientResponseException} <strong>之前</strong>判定：
     * 本异常代表「请求根本没发出去」，若落入兜底分支会被译成「无法连接到上游服务」，
     * 而上游并未被尝试连接 —— 这会把排查方向指往网络与上游可用性，
     * 而真正要改的是供应商的协议勾选。
     *
     * <p>用 400 而非 502：失败源于本地配置与请求的组合，不是网关上游故障，
     * 且重试多少次结果都一样—— 5xx 会诱导客户端重试。
     */
    private ProtocolTranslationNotSupportedException findProtocolException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ProtocolTranslationNotSupportedException protocolException) {
                return protocolException;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 递归解包「供应商一个协议都没勾」异常。
     *
     * <p>不直接 catch {@code IllegalStateException}：那会把任何库抛的同类异常
     * 一并译成「协议没配」，那种误导比笼统的 500 更难排查。
     */
    private NoSupportedProtocolException findNoSupportedProtocolException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof NoSupportedProtocolException noProtocol) {
                return noProtocol;
            }
            current = current.getCause();
        }
        return null;
    }
}

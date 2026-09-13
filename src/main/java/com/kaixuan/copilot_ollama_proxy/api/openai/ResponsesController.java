package com.kaixuan.copilot_ollama_proxy.api.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.openai.ResponsesService;
import com.kaixuan.copilot_ollama_proxy.application.protocol.NoSupportedProtocolException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.ApiUsageCollector;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallCancellationRegistry;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import com.kaixuan.copilot_ollama_proxy.protocol.openai.ResponsesRequest;
import com.kaixuan.copilot_ollama_proxy.provider.CallCanceledException;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.ResponsesStreamEvents;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.ResponsesUsageParser;
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
 * OpenAI Responses API 控制器 —— 处理下游以 Responses 协议发来的请求。
 *
 * <p>端点：{@code POST /v1/responses}
 *
 * <p>与另两个协议控制器的职责边界相同：只管 HTTP 层（接收、封装、SSE 转发），
 * 路由与协议调度在应用层，上游执行在 provider 层。
 *
 * <h2>与 Chat 端点的两处语义差异，与 Anthropic 端点一致</h2>
 * <ol>
 *   <li><strong>SSE 帧带 {@code event:} 类型</strong> —— Responses 客户端是状态机，
 *       靠事件类型驱动，不能只发 data。故转发时要从事件 JSON 的 {@code type} 字段
 *       取出类型回填到 SSE 的 event 名。</li>
 *   <li><strong>流结束标记不是 {@code [DONE]}</strong> —— 而是一组终态事件，
 *       见 {@link ResponsesStreamEvents}。完成判定的 Layer 1 据此触发。</li>
 * </ol>
 *
 * <h2>与 Anthropic 端点的一处差异：终止标记不止一个</h2>
 * Anthropic 只有 {@code message_stop}，Chat 只有 {@code [DONE]}，而 Responses 的终止有
 * 多种成因、各自一个事件名（正常完成、达到上限、失败、取消……），且 {@code cancelled} /
 * {@code canceled} 两种拼法实测都存在。清单集中在 {@link ResponsesStreamEvents} ——
 * 那里也是空响应判定的终态来源，两处共用一份避免漂移。
 *
 * <h2>虚拟模型不在此端点提供</h2>
 * {@code nano_llm} / {@code readme} 是 Chat 端点的引导机制（供 Copilot 在无供应商时
 * 看到提示），Responses 客户端不需要，故本端点不做拦截 —— 少一处需要同步维护的分支。
 */
@RestController
public class ResponsesController {

    private static final Logger log = LoggerFactory.getLogger(ResponsesController.class);

    /**
     * SSE 心跳周期，与另两个端点同值。
     *
     * <p>理由相同：空闲连接上 {@code channelInactive} 检测不可靠，上游等待首字或退避期间
     * 服务端一个字节都不写，下游断开可能要等到上游产生响应才被发现。周期写注释帧使
     * 写失败路径能及时兜底。
     */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);

    private final ResponsesService responsesService;
    private final ObjectMapper objectMapper;
    private final ApiUsageCollector apiUsageCollector;
    private final CallLifecyclePublisher callLifecyclePublisher;
    private final CallCancellationRegistry callCancellationRegistry;

    public ResponsesController(ResponsesService responsesService, ObjectMapper objectMapper,
                               ApiUsageCollector apiUsageCollector,
                               CallLifecyclePublisher callLifecyclePublisher,
                               CallCancellationRegistry callCancellationRegistry) {
        this.responsesService = responsesService;
        this.objectMapper = objectMapper;
        this.apiUsageCollector = apiUsageCollector;
        this.callLifecyclePublisher = callLifecyclePublisher;
        this.callCancellationRegistry = callCancellationRegistry;
    }

    /**
     * 处理 Responses 请求，支持流式与非流式。
     *
     * @return 统一返回 {@code Mono<ResponseEntity<?>>}；非流式 body 为完整 JSON 字符串，
     *         流式 body 为 {@code ServerSentEvent} 流
     */
    @PostMapping(value = "/v1/responses")
    public Mono<ResponseEntity<?>> responses(@RequestBody ResponsesRequest request,
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
                        responsesService.responses(requestBody, model, requestHeaders, requestId),
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
     * <p>完成判定沿用另两个端点的两层结构：
     * Layer 1 收到终态事件即 finalize（不必等 TCP 关闭），
     * Layer 2 上游关闭连接时兜底，用 CAS 去重保证只 finalize 一次。
     */
    private Flux<ServerSentEvent<String>> streamResponse(Map<String, Object> requestBody, String model,
                                                          HttpHeaders requestHeaders, String requestId) {
        AtomicInteger eventCount = new AtomicInteger(0);
        AtomicBoolean canceled = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);
        // 流式 usage 只在终态事件出现一次，因此这里是「最后一份非 null 胜出」而非累积 ——
        // 与 Anthropic 端点需要跨事件 merge 的形态不同。
        AtomicReference<UsageTokens> usage = new AtomicReference<>(UsageTokens.EMPTY);

        Mono<Void> cancelSignal = callCancellationRegistry.register(requestId)
                .doOnSuccess(v -> canceled.set(true));

        // 数据流终止信号，心跳据此停止 —— 否则 interval 永不完成，merge 永不完成。
        Sinks.Empty<Void> streamEnd = Sinks.empty();

        Flux<ServerSentEvent<String>> streamBody =
                responsesService.responsesStream(requestBody, model, requestHeaders, requestId)
                .doOnNext(event -> {
                    recordStreamUsage(event, usage);
                    // Layer 1：终态事件是协议终止标记，不计入事件数。
                    if (isTerminalEvent(event)) {
                        finalizeCompletion(requestId, model, eventCount.get(), completed, usage);
                        return;
                    }
                    callLifecyclePublisher.publish(CallLifecycleEvent.of(
                            requestId, CallPhase.CHUNK, model, true, eventCount.incrementAndGet()));
                })
                // event 类型必须回填：Responses 客户端靠它驱动状态机，只发 data 无法解析。
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
                    // Layer 2：上游未发任何终态事件就关连接时靠这里兜底。
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
                    // 错误以 Responses 的 error 事件形态下发，客户端才能识别。
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
     * （{@code instructions} / {@code reasoning} / {@code tools} / {@code include} 等），
     * 这样无需逐个建模即可透传。
     *
     * <p>{@code stream} 不在此处设置：由上游服务按调用入口决定
     * （{@code prepareRequestBody} 会覆写），避免下游误传导致模式不符。
     */
    private Map<String, Object> buildRequestBody(ResponsesRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        if (request.getInput() != null) {
            body.put("input", request.getInput());
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

    /** 是否流的终态事件。清单与空响应判定共用 {@link ResponsesStreamEvents}。 */
    private boolean isTerminalEvent(String event) {
        return ResponsesStreamEvents.isTerminal(extractEventType(event));
    }

    /**
     * 从流式事件提取 usage。
     *
     * <p>「最后一份非 null 胜出」而非跨事件合并：Responses 的 usage 挂在终态事件的
     * {@code response.usage} 下、一次给全。若某个上游中途也带一份，终态那份才是结算值。
     * 这与 Anthropic 端点必须 {@code merge}（输入与输出分散在两个事件）形成对比。
     */
    private void recordStreamUsage(String event, AtomicReference<UsageTokens> usage) {
        String raw = ResponsesUsageParser.extractUsageRawJson(objectMapper, event);
        if (raw == null) {
            return;
        }
        UsageTokens tokens = ResponsesUsageParser.parseUsageObject(objectMapper, raw);
        if (!tokens.isEmpty()) {
            usage.set(tokens);
        }
    }

    /** 从非流式响应提取 usage 并记入日聚合。 */
    private void recordUsage(String json) {
        String raw = ResponsesUsageParser.extractUsageRawJson(objectMapper, json);
        if (raw == null) {
            return;
        }
        UsageTokens tokens = ResponsesUsageParser.parseUsageObject(objectMapper, raw);
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
                    .body(errorBody(protocolException.getMessage()));
        }
        // 协议一个都没勾：同样是本地配置问题，不能落进 502 兜底。
        NoSupportedProtocolException noProtocol = findNoSupportedProtocolException(ex);
        if (noProtocol != null) {
            log.warn("供应商未配置任何协议 [{}]: {}", model, noProtocol.getMessage());
            return ResponseEntity.status(400).contentType(MediaType.APPLICATION_JSON)
                    .body(errorBody(noProtocol.getMessage()));
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
                .body(errorBody("无法连接到上游服务"));
    }

    /**
     * 构造流式错误<strong>事件</strong>的 body。
     *
     * <h2>与非流式刻意不共用同一个 JSON 骨架</h2>
     * 非流式的错误是一个普通 HTTP 响应体，用 Chat 形态
     * （{@code {"error":{...}}}）合理 —— 两者同为 OpenAI 系，客户端错误解析代码通常共用。
     * 但流式的错误是一个<strong>协议事件</strong>，得守事件的契约：Responses 客户端是
     * 事件状态机，靠 {@code type} 分派。本方法上方那段 {@code map} 正是在把 JSON 的
     * {@code type} 回填到 SSE 的 {@code event:} 行 —— 而 Chat 形态的错误体<strong>顶层
     * 没有 {@code type}</strong>，只认 JSON 的客户端会把这帧当成无法分派的脏数据。
     *
     * <p>症状因此不是 400/502，而是<strong>流挂住、界面转圈不动</strong> ——
     * 错误信息其实已经送到，只是客户端不认。排查时极易误判成超时或网络问题。
     *
     * <p>此前这里直接调 {@code errorBody}，就是踩在「一个方法服务两条契约不同的路」上。
     *
     * @see #streamErrorBody(String) 官方 {@code ResponseErrorEvent} 的形态
     */
    private String errorEventBody(Throwable error, String model) {
        ProtocolTranslationNotSupportedException protocolException = findProtocolException(error);
        if (protocolException != null) {
            log.warn("协议不可用 [{}]: {}", model, protocolException.getMessage());
            return streamErrorBody(protocolException.getMessage());
        }
        NoSupportedProtocolException noProtocol = findNoSupportedProtocolException(error);
        if (noProtocol != null) {
            log.warn("供应商未配置任何协议 [{}]: {}", model, noProtocol.getMessage());
            return streamErrorBody(noProtocol.getMessage());
        }
        WebClientResponseException responseException = findWebResponseException(error);
        if (responseException != null) {
            String upstreamBody = responseException.getResponseBodyAsString();
            log.warn("上游 API 返回错误 [{}] {}: {}", model,
                    responseException.getStatusCode().value(), upstreamBody);
            // 上游原文优先，但必须能被状态机分派 —— 直连 Responses 上游的 4xx 通常是
            // REST 错误体（Chat 形态、顶层无 type），原样下发等于制造一帧脏数据。
            // 已是合法 error 事件的（少数上游把 SSE 错误帧当响应体返回）则原样透传，
            // 那才是最准确的信息。
            return isResponsesErrorEvent(upstreamBody)
                    ? upstreamBody
                    : streamErrorBody(upstreamMessageOf(upstreamBody, responseException));
        }
        log.warn("上游 API 调用失败 [{}]: {}", model, error.getMessage());
        return streamErrorBody("无法连接到上游服务");
    }

    /**
     * 官方 {@code ResponseErrorEvent} 形态的错误事件体。
     *
     * <p>字段照官方定义逐字写 —— {@code ResponseErrorEvent object { code, message,
     * param, sequence_number, type }}，且 {@code type} 恒为 {@code "error"}。
     * 注意它是<strong>扁平</strong>结构，不是 Chat 那样把内容包进一个 {@code error}
     * 对象里，也不是 Anthropic 那样「顶层 type + 嵌套 error」。三条线路的错误形态
     * 两两不同，这就是不能共用骨架的根据。
     *
     * <p>{@code code} 与 {@code param} 按官方定义可为 null，本地错误无从填充，
     * 显式写 null 而非省略 —— 客户端按定义读这两个键时，缺键与 null 的处理可能不同。
     *
     * <p>不带 {@code sequence_number}：那是上游对自己事件流的编号，本地生成的错误帧
     * 没有真实序号可填，编一个反而会与上游已发出的编号冲突。官方把它列为必有字段，
     * 但缺一个编号比给一个假编号安全 —— 客户端拿它做去重或排序时，假值会造成错序。
     */
    private String streamErrorBody(String message) {
        // 走序列化而不拼字符串：理由同 errorBody —— message 内容不可控。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("code", null);
        body.put("message", message);
        body.put("param", null);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            return "{\"type\":\"error\",\"code\":null,\"message\":\"上游调用失败\",\"param\":null}";
        }
    }

    /** 上游错误体是否已经是一帧合法的 Responses {@code error} 事件。 */
    private boolean isResponsesErrorEvent(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.isObject() && "error".equals(text(root, "type"));
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * 从上游错误体里取出可读消息，取不到时退回状态码描述。
     *
     * <p>上游原文不能整体丢弃 —— 它往往是唯一说明「为什么失败」的信息
     * （余额不足、模型不存在、限流）。这里把它塞进符合协议的外壳里，
     * 既让客户端能分派，又不丢诊断信息。
     */
    private String upstreamMessageOf(String body, WebClientResponseException exception) {
        int status = exception.getStatusCode().value();
        if (body == null || body.isBlank()) {
            return "上游返回错误 " + status;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            // Chat / Responses 形态：{"error":{"message":...}}；也兼容顶层 message。
            String message = text(root.path("error"), "message");
            if (message == null) {
                message = text(root, "message");
            }
            if (message != null && !message.isBlank()) {
                return message;
            }
        } catch (Exception exception1) {
            // 非 JSON（HTML 错误页等）：原文可能很长且不可读，只带状态码更有用。
        }
        return "上游返回错误 " + status;
    }

    /** 读一个字符串字段；非对象或非文本返回 null。 */
    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /**
     * <strong>非流式</strong>的错误响应体。
     *
     * <p>结构与 Chat 的 {@code {"error":{"message":...,"type":...}}} 相同 ——
     * 两者同为 OpenAI 系，客户端的错误解析代码通常共用。因此这里<strong>不</strong>照抄
     * Anthropic 那个外层多一个 {@code "type":"error"} 的形态。
     *
     * <p><strong>流式不要复用本方法</strong>：那条路要的是官方
     * {@code ResponseErrorEvent}（扁平、顶层带 {@code type}），见
     * {@link #streamErrorBody(String)}。这两条路的契约不同，共用会让流式帧无法被
     * 事件状态机分派。
     */
    private String errorBody(String message) {
        // 走序列化而不拼字符串：message 可能来自异常消息（如协议不可用那条），
        // 内容不可控；一个引号或换行就能把错误体本身变成非法 JSON，
        // 而客户端解析失败后看到的是一个完全无关的错误。
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", "api_error");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            return "{\"error\":{\"message\":\"上游调用失败\",\"type\":\"api_error\"}}";
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
     * 且重试多少次结果都一样 —— 5xx 会诱导客户端重试。
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
     * 递归解包「供应商未声明任何协议」异常。
     *
     * <p>与协议不可用同一处境：本地配置问题、重试无用、必须与上游故障区分开。
     * 单独一个类型是为了给出不同的可操作消息（那条指向「去勾选协议」）。
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

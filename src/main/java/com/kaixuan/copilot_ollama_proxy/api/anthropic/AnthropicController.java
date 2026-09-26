package com.kaixuan.copilot_ollama_proxy.api.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.api.shared.NonStreamLifecycle;
import com.kaixuan.copilot_ollama_proxy.api.shared.StreamLifecycle;
import com.kaixuan.copilot_ollama_proxy.api.shared.UpstreamErrorRenderer;
import com.kaixuan.copilot_ollama_proxy.api.shared.UpstreamFailureClassifier;
import com.kaixuan.copilot_ollama_proxy.api.shared.UsageAccounting;
import com.kaixuan.copilot_ollama_proxy.pipeline.entry.MessagesService;
import com.kaixuan.copilot_ollama_proxy.control.CallCancellationRegistry;
import com.kaixuan.copilot_ollama_proxy.observability.publisher.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicMessagesRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEventClassifier;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.observability.record.ApiUsageDailyService;
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
    private final ApiUsageDailyService apiUsageCollector;
    private final CallLifecyclePublisher callLifecyclePublisher;
    private final CallCancellationRegistry callCancellationRegistry;

    public AnthropicController(MessagesService messagesService, ObjectMapper objectMapper,
                               ApiUsageDailyService apiUsageCollector,
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

        // 非流式：收尾协议收归 NonStreamLifecycle —— 三条端点此前各写一份逐字相同的实现。
        // 本处只交代「哪条链」与「错误长什么样」。
        return NonStreamLifecycle.attach(
                messagesService.messages(requestBody, model, requestHeaders, requestId),
                new NonStreamLifecycle.CallContext(requestId, model, stream,
                        callLifecyclePublisher, callCancellationRegistry, apiUsageCollector, log),
                ex -> errorResponse(ex, model));
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
        AtomicReference<UsageTokens> usage = new AtomicReference<>(null);

        Mono<Void> cancelSignal = callCancellationRegistry.register(requestId)
                .doOnSuccess(v -> canceled.set(true));

        // 数据流终止信号，心跳据此停止 —— 否则 interval 永不完成，merge 永不完成。
        Sinks.Empty<Void> streamEnd = Sinks.empty();

        Flux<ServerSentEvent<String>> mappedBody =
                messagesService.messagesStream(requestBody, model, requestHeaders, requestId)
                // 分类已由上游执行器完成：本层只读 isTerminal()。
                .doOnNext(upstreamEvent -> {
                    accumulateUsage(upstreamEvent, usage);
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
        // 流式恒记（无 usage 记 0,0）—— 两档语义与理由见 UsageAccounting 的类注释。
        UsageAccounting.recordStream(apiUsageCollector, usage);
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
     * （主干装配的 {@code writeProtocolFields} 会覆写），避免下游误传导致模式不符。
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

    /**
     * 从流式事件取 usage —— 实现见 {@link UsageAccounting#accumulate}。
     *
     * <p>跨事件合并（input 来自 {@code message_start}、output 来自 {@code message_delta}，
     * 且只有正数才覆盖）已由上游执行器的 {@code AnthropicUsageParser#merge} 完成并挂在事件上。
     * 出口不必知道这条协议规则 —— 那是 7b-1 消除的那份重复。
     */
    private void accumulateUsage(UpstreamEvent event, AtomicReference<UsageTokens> usage) {
        UsageAccounting.accumulate(event, usage);
    }

    /**
     * 把调用失败渲染成<strong>非流式</strong>响应。
     *
     * <h2>分工</h2>
     * 分类交给 {@link UpstreamFailureClassifier}；状态码与日志交给
     * {@link UpstreamErrorRenderer}；<strong>body 骨架留在本类</strong> ——
     * Anthropic 比 OpenAI 系多一层 {@code "type":"error"}，客户端据此区分错误帧与内容帧。
     *
     * <p>状态码分层的理由（400 vs 502、上游 HTTP 原样透传）见渲染器的 Javadoc。
     */
    private ResponseEntity<?> errorResponse(Throwable ex, String model) {
        return UpstreamErrorRenderer.response(ex, model, log, BODIES);
    }

    /**
     * Anthropic 形态的错误体 —— 本端点自备。
     *
     * <p><strong>与非流式/流式共用同一形</strong>：Anthropic 的流式错误事件与非流式错误体
     * 都是 {@code {"type":"error","error":{...}}}（这是它与 Responses 的差异 ——
     * 后者两者刻意不同）。因此这里只有一个 {@code badRequest}/{@code upstreamError} 实现，
     * 供两条路径共用。
     */
    private final UpstreamErrorRenderer.ErrorBodies BODIES =
            new UpstreamErrorRenderer.ErrorBodies() {
                @Override
                public String badRequest(String message) {
                    return anthropicErrorBody(message);
                }

                @Override
                public String upstreamError(String message) {
                    return anthropicErrorBody(message);
                }
            };

    /**
     * 构造流式错误事件的 body，透传上游错误体。
     *
     * <p>与非流式共用同一份分类，仅送出口不同（流式状态码已定，只能靠事件体表达）。
     */
    private String errorEventBody(Throwable error, String model) {
        return UpstreamErrorRenderer.streamBody(error, model, log, BODIES);
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

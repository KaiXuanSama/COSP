package com.kaixuan.copilot_ollama_proxy.upstream.send.responses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.AuthHeaderSetting;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ReasoningEffortSetting;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil;
import com.kaixuan.copilot_ollama_proxy.control.CallRetryRegistry;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import com.kaixuan.copilot_ollama_proxy.upstream.ChunkLogPayload;
import com.kaixuan.copilot_ollama_proxy.upstream.EmptyUpstreamResponseException;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamAutoRetry;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamEventClassifier;
import com.kaixuan.copilot_ollama_proxy.upstream.send.UpstreamExecutor;
import com.kaixuan.copilot_ollama_proxy.upstream.content.ContentDetectorRegistry;
import com.kaixuan.copilot_ollama_proxy.upstream.content.ContentDetectorStage;
import com.kaixuan.copilot_ollama_proxy.upstream.EmptyResponseGate;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamCallReporter;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamRetryPolicy;
import com.kaixuan.copilot_ollama_proxy.control.CallResendLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 通用 <strong>Responses</strong> 上游服务 —— 对接 OpenAI Responses API 协议的供应商。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>实现</strong>（RESPONSES） · 位置：{@code upstream/send/responses/}
 * 步骤「发送」—— 独立类，<strong>不继承</strong> {@link AbstractUpstreamChatService}
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>为何与另两个上游服务平级，而不复用它们</h2>
 * 三条线路的 Reactor 链体<strong>结构不同</strong>：
 * <ul>
 *   <li>{@code AbstractUpstreamChatService}（Chat）的实质载荷判定取值路径是
 *       {@code choices[].delta}；</li>
 *   <li>{@code GenericAnthropicChatService} 的事件形态是
 *       {@code message_start} / {@code content_block_*} / {@code message_delta}；</li>
 *   <li>本类的事件形态是十余种 {@code type} 加按 {@code output_index} 分项的 item，
 *       且终态事件有多个、语义不同。</li>
 * </ul>
 * 三者的空响应取值路径、usage 提取、终态判定全部不同。
 *
 * <p><strong>空响应 gate 的语义三条线一致（都扣住）</strong>，机制本身已收归
 * {@link EmptyResponseGate}（阶段 3.6b）—— 扣住-释放的完整理由见那个类的类注释。
 * 曾经这里不扣帧、只记「是否见过载荷」，
 * 理由是「客户端是事件状态机，扣住 {@code response.created} 会让它无法初始化」——
 * 该理由不成立：扣住是暂时的，开闸时整批按序释放，下游看到的是完整合法前缀。
 * 而不扣帧会导致重试时下游收到重复的 {@code response.created}、
 * 以及耗尽时把已流走的事件再放行一遍。
 *
 * <p>那么为何不继承 Anthropic 那份？因为「结构同构」不等于「实现可共享」：
 * 两者的请求体准备、空响应取值路径、usage 提取、终态判定全部不同，
 * 抽出去只剩一个空壳加一堆钩子 —— 模板方法反而让实现者看不见自己正在依赖什么。
 * 这与 Anthropic 当初不继承 Chat 是同一个判断。
 *
 * <p>空响应拦截的<strong>机制</strong>现已共享（{@link EmptyResponseGate}），
 * 但<strong>接线顺序在三处仍各写一遍</strong>。这是<strong>有意接受</strong>的：
 * 接线顺序写出来带注释比藏在基类里更可审。<strong>抽取的触发信号是三侧出现口径差异</strong>
 * （那才说明有一侧被遗忘了），而不是「现在结构相似所以应该合并」。
 *
 * <h2>与另两侧共享的策略（不可各自为政）</h2>
 * <ul>
 *   <li>重试预算 —— 同样从 {@link RetryPolicyService} 读 {@code retry_max_attempts}，
 *       空响应同样包成 {@link EmptyUpstreamResponseException} 走同一条 {@code retryWhen}，
 *       因此「重试次数」在三个协议上仍然只有一个配置来源；</li>
 *   <li>三类实质载荷的类别定义 —— 见 {@link ResponsesContentDetector}；</li>
 *   <li>解析失败保守放行；</li>
 *   <li>{@code publishCallRecorded()} 的 finally 语义；</li>
 *   <li>{@link UsageTokens} 输出契约与 null / 0 的区分；</li>
 *   <li>状态码 {@code -1} 作为非 HTTP 异常的占位值。</li>
 * </ul>
 *
 * <h2>请求体的协议差异：比另两条线路少得多</h2>
 * 本类的 {@link #prepareRequestBody} 只做四件事（改模型名、设 {@code stream}、
 * 注入思考深度、执行规则），而 Anthropic 那份还要提取 system、补 {@code max_tokens}、
 * 协调两个思考维度。原因是<strong>下游与上游说的是同一种协议</strong>——
 * 直连不需要任何形态转换。
 *
 * <p>两个刻意<strong>不做</strong>的注入见 {@link #prepareRequestBody}：
 * {@code max_output_tokens} 与 Anthropic 的思考方式。
 */
@Service
public class GenericResponsesChatService implements UpstreamExecutor {

    private static final Logger log = LoggerFactory.getLogger(GenericResponsesChatService.class);

    /** SSE 场景下，每个 data 字段的原始字符串类型引用。 */
    private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final ObjectMapper objectMapper;
    private final ProviderRequestHeaderService providerRequestHeaderService;
    private final RequestBodyRuleEngine requestBodyRuleEngine;

    /**
     * 内容检测器的查表 —— 空响应拦截的判据来源（阶段 3.6b）。
     *
     * <p>放构造器而非可选 setter：本表未命中是<strong>报错</strong>而非跳过，
     * 漏注入会让整条线路的空响应兼底直接失效。与另两个执行器同一取舍。
     */
    private final ContentDetectorRegistry contentDetectorRegistry;

    private ApiCallLogService apiCallLog;
    private ApiCallUsageService apiCallUsage;
    private CallLifecycleNotifier lifecycleNotifier;
    /** 静默重试协调器，由 Spring 可选注入；管理后台右键 Toast 触发时重新发起当前上游请求。 */
    private CallRetryRegistry callRetryRegistry;
    private RetryPolicyService retryPolicyService;
    private WebClient.Builder webClientBuilder = WebClient.builder();
    private HttpClient httpClient = HttpClient.create();

    public GenericResponsesChatService(ObjectMapper objectMapper,
                                       ProviderRequestHeaderService providerRequestHeaderService,
                                       RequestBodyRuleEngine requestBodyRuleEngine,
                                       ContentDetectorRegistry contentDetectorRegistry) {
        this.objectMapper = objectMapper;
        this.providerRequestHeaderService = providerRequestHeaderService;
        this.requestBodyRuleEngine = requestBodyRuleEngine;
        this.contentDetectorRegistry = contentDetectorRegistry;
    }

    @Autowired(required = false)
    public void setApiCallLog(ApiCallLogService apiCallLog) {
        this.apiCallLog = apiCallLog;
    }

    @Autowired(required = false)
    public void setApiCallUsage(ApiCallUsageService apiCallUsage) {
        this.apiCallUsage = apiCallUsage;
    }

    @Autowired(required = false)
    public void setLifecycleNotifier(CallLifecycleNotifier lifecycleNotifier) {
        this.lifecycleNotifier = lifecycleNotifier;
    }

    @Autowired(required = false)
    public void setCallRetryRegistry(CallRetryRegistry callRetryRegistry) {
        this.callRetryRegistry = callRetryRegistry;
    }

    @Autowired(required = false)
    public void setRetryPolicyService(RetryPolicyService retryPolicyService) {
        this.retryPolicyService = retryPolicyService;
    }

    @Autowired(required = false)
    public void setWebClientBuilder(WebClient.Builder webClientBuilder) {
        if (webClientBuilder != null) {
            this.webClientBuilder = webClientBuilder;
        }
    }

    @Autowired(required = false)
    public void setHttpClient(HttpClient httpClient) {
        if (httpClient != null) {
            this.httpClient = httpClient;
        }
    }

    // ==================== 主干 send 插槽 ====================

    /** 本执行器服务的上游协议 —— <strong>查表键</strong>。 */
    @Override
    public WireProtocol protocol() {
        return WireProtocol.RESPONSES;
    }

    /**
     * 主干 send 插槽（非流式）。
     *
     * <p>参数全部来自 ctx。Responses 直连无落库改写（R2x / x2R 未实现），
     * 故 {@code chunkRewriter} 被忽略。
     */
    @Override
    public Mono<UpstreamEvent> invoke(RequestPipelineContext ctx,
                                      Function<List<String>, ChunkLogPayload> chunkRewriter) {
        return responses(ctx.body(), ctx.model(), ctx.provider(), ctx.downstreamHeaders(),
                ctx.requestId(), ctx);
    }

    /** 主干 send 插槽（流式），理由同 {@link #invoke}。 */
    @Override
    public Flux<UpstreamEvent> invokeStream(RequestPipelineContext ctx,
                                            Function<List<String>, ChunkLogPayload> chunkRewriter) {
        return responsesStream(ctx.body(), ctx.model(), ctx.provider(), ctx.downstreamHeaders(),
                ctx.requestId(), ctx);
    }

    // ==================== 非流式 ====================

    /**
     * 发送一次非流式 Responses 请求。
     *
     * <h2>接线顺序的关键约束（与另两侧逐条相同）</h2>
     * 空响应判定必须夹在 {@code doOnNext} 落库<strong>之后</strong>、
     * {@code .map(取 body)} <strong>之前</strong>：
     * <ul>
     *   <li>在落库之后 —— 保证「上游到底返回了什么」在日志里可查，
     *       否则判空重发后什么证据都没留下；</li>
     *   <li>在取 body 之前 —— 空 body 时 {@code getBody()} 为 null，
     *       Reactor 不允许 null 会抛 NPE，而 NPE 既不在可重试判定范围内、
     *       也已错过 {@code retryWhen} 的位置，最终会落到控制器的 502 分支，
     *       报出「无法连接到上游服务」这种与事实相反的错误。</li>
     * </ul>
     * 这条约束是 Chat 侧用一个真实缺陷换来的，此处必须同样成立。
     */
    protected Mono<UpstreamEvent> responses(Map<String, Object> request, String model,
                                            ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                            String requestId, RequestPipelineContext ctx) {
        // stream 取自 ctx（3.5a）：主干按 ctx.stream() 选了本方法，故这里二者必定一致。
        // ⚠️ 这条一致性依赖调用方守规矩：直接调本方法而 ctx 里 stream=false 会走错路且不响。
        //    该不变式在 3.5b（两态合链）后自然消失（与 executeStream 同属搁置项）。
        boolean stream = ctx.stream();
        Map<String, Object> requestBody = prepareRequestBody(request, stream, model, provider);
        log.info("{} Responses 上游，模型: {}, 流式: {}", provider.providerKey(), requestBody.get("model"), stream);

        // 拦截是否介入：请求级事实，故在 defer 之外算一次 —— 重试不改变它的值。
        // 判定机制收归 EmptyResponseGate（阶段 3.6b），检测器按上游协议查表。
        EmptyResponseGate<String> gate = new EmptyResponseGate<>(ctx.shouldApplyEmptyResponseGate());
        EmptyResponseGate.CallContext callCtx = new EmptyResponseGate.CallContext(provider.providerKey(), model, requestId);
        ContentDetectorStage detector = contentDetectorRegistry.require(ctx.upstreamProtocol());

        String providerKey = provider.providerKey();
        String modelName = (String) requestBody.get("model");
        Map<String, String> reqHeaders = new LinkedHashMap<>();
        AtomicLong attemptStart = new AtomicLong(System.currentTimeMillis());

        return Mono.defer(() -> {
                    attemptStart.set(System.currentTimeMillis());
                    return buildWebClient(reqHeaders, provider, downstreamHeaders, stream)
                            .post().uri(responsesUri()).bodyValue(requestBody).retrieve()
                            .toEntity(String.class);
                })
                // 成功往返：立即落一条成功记录（在 retry 上游，每次往返各自记录）。
                .doOnNext(entity -> {
                    log.debug("{} 响应: {}", providerKey, entity.getBody());
                    // CONNECTED：非流式无首字概念，完整响应到达即视为已连接。
                    publishLifecycle(CallLifecycleEvent.of(requestId, CallPhase.CONNECTED, modelName, false));
                    Map<String, String> respHeaders = new LinkedHashMap<>();
                    entity.getHeaders().forEach((k, v) -> respHeaders.put(k, String.join(", ", v)));
                    Long logId = saveNonStreamLog(providerKey, modelName, reqHeaders, requestBody, respHeaders,
                            entity.getStatusCode().value(), entity.getBody(), attemptStart.get(), ctx);
                    // ttfb 传 null：非流式没有首字概念，与另两侧一致。
                    saveUsage(logId, providerKey, modelName, stream,
                            ResponsesUsageParser.extractUsageRawJson(objectMapper, entity.getBody()),
                            null);
                })
                // 失败往返：每次失败（含被 retry 吞掉的中间失败）都各自落一条。
                .doOnError(e -> {
                    WebClientResponseException responseException = UpstreamRetryPolicy.findWebResponseException(e);
                    if (responseException != null) {
                        Map<String, String> errHeaders = new LinkedHashMap<>();
                        responseException.getHeaders().forEach((k, v) -> errHeaders.put(k, String.join(", ", v)));
                        saveNonStreamLog(providerKey, modelName, reqHeaders, requestBody, errHeaders,
                                responseException.getStatusCode().value(),
                                responseException.getResponseBodyAsString(), attemptStart.get(), ctx);
                        publishCallRecorded();
                    } else {
                        // 状态码 -1：非 HTTP 异常的占位值，与另两侧同一约定。
                        saveNonStreamLog(providerKey, modelName, reqHeaders, requestBody, Map.of(), -1, null,
                                attemptStart.get(), ctx);
                        publishCallRecorded();
                    }
                })
                // 空响应兜底：转成异常以复用下方同一条 retryWhen 的预算。
                // 判定机制收归 EmptyResponseGate（阶段 3.6b）—— 三条线路同源。
                .flatMap(entity -> EmptyResponseGate
                        .checkNonStream(entity.getBody(), gate.active(), detector, log, callCtx)
                        .thenReturn(entity))
                .retryWhen(buildRetrySpec("responses", provider, requestId, modelName, stream))
                // 空 body 已在上面被判空转成异常，此处 getBody() 不会为 null。
                .map(entity -> entity.getBody())
                // 空响应重试耗尽：放行最后一轮的原始 body，保持「透传上游真实返回」语义。
                // 解包与告警文案收归 EmptyResponseGate（机制共用），本处只负责「怎么放行」。
                .onErrorResume(error -> {
                    Optional<List<String>> exhausted = EmptyResponseGate.exhaustedFrames(error);
                    if (exhausted.isEmpty()) {
                        return Mono.error(error);
                    }
                    List<String> frames = exhausted.get();
                    EmptyResponseGate.logExhaustedPassthrough(log, callCtx, frames.size());
                    return Mono.just(frames.isEmpty() ? "" : frames.get(0));
                })
                // 包装成统一形态：非流式在本形态下就是「恰有一个元素的流」。
                // 直接用 body 而不走分类器：非流式的响应体里不存在协议级终止标记，
                // 「说完了」由流的 onComplete 表达 —— 这是已确定的事实，不必运行时再判一次。
                .map(UpstreamEvent::body);
    }

    // ==================== 流式 ====================

    /**
     * 发送一次流式 Responses 请求。
     *
     * <h2>空响应 gate 与另两条线同构（扣住-释放）</h2>
     * 机制已收归 {@link EmptyResponseGate}（阶段 3.6b），三条线路共用一份实现 ——
     * 本方法只提供扣住的元素类型与检测器（查表得来）。
     *
     * <p>曾经这里<strong>不扣帧</strong>、只记「是否见过实质载荷」，理由是
     * 「下游客户端是事件状态机，扣住 {@code response.created} 会让它无法初始化」。
     * 该理由<strong>不成立</strong>：扣住是暂时的，开闸时整批按序释放，
     * 下游看到的是一个完整合法前缀，只是晚了一点（那段时间由心跳保活）。
     * 而不扣帧会导致重试时下游收到重复的 {@code response.created}，
     * 以及耗尽时把已流走的事件再放行一遍。
     *
     * <h2>手动（静默）重试已接入</h2>
     * {@code CallRetryRegistry} + {@code takeUntilOther}，与另两侧同构。
     *
     * <p>Anthropic 侧曾因「一个未收到终止事件的序列后接一个全新的开始事件，对严格客户端
     * 是否合法尚未验证」而暂缓接入，后来按功能完整性优先补上了。此处直接接入：
     * 前端菜单项的条件只看是否流式、看不到上游协议，不接会让它表现为
     * 「点了没反应、无任何报错」—— 那比理论风险更明确地有害。
     */
    protected Flux<UpstreamEvent> responsesStream(Map<String, Object> request, String model,
                                                   ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                                   String requestId, RequestPipelineContext ctx) {
        // stream 取自 ctx（3.5a）—— 它是请求级事实，与「哪条链被调了」同源，不留字面量。
        // （3.5b 两态合链已搁置，故此处不再以后续步骤为由。）
        boolean stream = ctx.stream();
        Map<String, Object> requestBody = prepareRequestBody(request, stream, model, provider);
        log.info("{} Responses 上游，模型: {}, 流式: {}", provider.providerKey(), requestBody.get("model"), stream);

        // 拦截是否介入：请求级事实，故在 defer 之外算一次 —— 重试不改变它的值。
        // 机制收归 EmptyResponseGate（阶段 3.6b）：闸门状态与缓存帧都在它那里，
        // 本类只负责每轮 defer 内调 reset()。
        EmptyResponseGate<String> gate = new EmptyResponseGate<>(ctx.shouldApplyEmptyResponseGate());
        EmptyResponseGate.CallContext callCtx = new EmptyResponseGate.CallContext(provider.providerKey(), model, requestId);
        ContentDetectorStage detector = contentDetectorRegistry.require(ctx.upstreamProtocol());

        String providerKey = provider.providerKey();
        String modelName = (String) requestBody.get("model");
        Map<String, String> reqHeaders = new LinkedHashMap<>();
        List<String> logChunks = new CopyOnWriteArrayList<>();
        AtomicReference<Map<String, String>> capturedRespHeaders = new AtomicReference<>(Map.of());
        AtomicReference<Integer> capturedStatusCode = new AtomicReference<>(0);
        AtomicLong attemptStart = new AtomicLong(System.currentTimeMillis());
        AtomicLong ttfbMs = new AtomicLong(-1);
        // 本轮的 usage 原文。与 Anthropic 侧不同，这里不需要跨事件合并也不需要挑选：
        // Responses 的 usage 只在终态事件里出现一次、一次给全。仍用「最后一份非 null」
        // 而非「第一份」—— 若某个上游在中途也带 usage，终态那份才是结算值。
        AtomicReference<String> usageRaw = new AtomicReference<>(null);
        // 空响应耗尽放行标记：该轮已在 doOnError 落过库，收尾处据此跳过，避免重复记录。
        AtomicBoolean emptyResponsePassthrough = new AtomicBoolean(false);

        Flux<String> attempt = Flux.defer(() -> {
                    // 每轮往返起点重置：使每条日志只反映该次往返，不跨重试累加。
                    // 状态在 defer 内重置而非声明处初始化 —— retryWhen 会重订阅，
                    // 若不重置，第二轮会带着第一轮的 gate 状态与事件记录。
                    attemptStart.set(System.currentTimeMillis());
                    logChunks.clear();
                    ttfbMs.set(-1);
                    usageRaw.set(null);
                    // 闸门状态同理每轮重置（机制在 EmptyResponseGate 里，理由见它自己的注释）。
                    gate.reset();
                    return buildWebClient(reqHeaders, provider, downstreamHeaders, stream)
                            .post().uri(responsesUri()).bodyValue(requestBody)
                            .exchangeToFlux(response -> {
                                Map<String, String> respHeaders = new LinkedHashMap<>();
                                response.headers().asHttpHeaders()
                                        .forEach((k, v) -> respHeaders.put(k, String.join(", ", v)));
                                capturedRespHeaders.set(respHeaders);
                                capturedStatusCode.set(response.statusCode().value());
                                if (response.statusCode().isError()) {
                                    return response.bodyToMono(String.class).flatMapMany(errorBody -> {
                                        log.warn("{} 上游返回错误响应 {}: {}", providerKey,
                                                response.statusCode().value(), errorBody);
                                        saveStreamLogWithError(providerKey, modelName, reqHeaders, requestBody,
                                                respHeaders, response.statusCode().value(), List.of(),
                                                respHeaders, response.statusCode().value(), errorBody,
                                                attemptStart.get(), ctx);
                                        publishCallRecorded();
                                        return Flux.error(new WebClientResponseException(
                                                response.statusCode().value(), "上游错误响应", null,
                                                errorBody.getBytes(), null));
                                    });
                                }
                                // CONNECTED：收到非错误响应头的那一刻。
                                publishLifecycle(CallLifecycleEvent.of(requestId, CallPhase.CONNECTED, model, true));
                                return response.bodyToFlux(STRING_SSE_TYPE);
                            });
                })
                .mapNotNull(ServerSentEvent::data)
                .filter(data -> !data.isBlank() && !"null".equals(data))
                .doOnNext(data -> {
                    if (ttfbMs.get() < 0) {
                        ttfbMs.set(System.currentTimeMillis() - attemptStart.get());
                    }
                    log.debug("{} 上游事件: {}", providerKey, data);
                    // usage 只在终态事件出现，但仍无条件尝试提取：某些上游中途也带一份，
                    // 后到的覆盖先到的，终态那份最终胜出。
                    String extracted = ResponsesUsageParser.extractUsageRawJson(objectMapper, data);
                    if (extracted != null) {
                        usageRaw.set(extracted);
                    }
                    logChunks.add(data);
                })
                // ── 空响应 gate ──────────────────────────────────────────────
                // 机制收归 EmptyResponseGate（阶段 3.6b）：扣住 / 整批释放 / 轮末判空
                // 三条线路共用一份实现，本类只提供检测器（查表得来）。
                // 挂在 retryWhen 内侧，因此每轮重订阅各自独立判定；闸门状态已在上面的 defer 内 reset。
                // 完整理由（为何必须扣住、为何不会破坏客户端状态机）见那个类的类注释。
                .transform(flux -> gate.gate(flux, Function.identity(), detector, log, callCtx))
                // 网络类失败往返：错误响应已在 exchangeToFlux 分支落库，
                // 此处用 UpstreamRetryPolicy.findWebResponseException == null 排除以免重复。
                .doOnError(e -> {
                    Optional<List<String>> exhausted = EmptyResponseGate.exhaustedFrames(e);
                    if (exhausted.isPresent()) {
                        saveStreamLog(providerKey, modelName, reqHeaders, requestBody,
                                capturedRespHeaders.get(), capturedStatusCode.get(),
                                exhausted.get(), attemptStart.get(), ctx);
                        publishCallRecorded();
                        return;
                    }
                    if (UpstreamRetryPolicy.findWebResponseException(e) == null) {
                        int statusCode = capturedStatusCode.get() == 0 ? -1 : capturedStatusCode.get();
                        saveStreamLog(providerKey, modelName, reqHeaders, requestBody,
                                capturedRespHeaders.get(), statusCode, List.copyOf(logChunks),
                                attemptStart.get(), ctx);
                        publishCallRecorded();
                    }
                })
                .retryWhen(buildRetrySpec("responsesStream", provider, requestId, model, stream))
                // 空响应耗尽：放行最后一轮的事件，与其他失败「耗尽后透传最后一次响应」一致。
                // 解包与告警文案收归 EmptyResponseGate（机制共用），本处只负责「怎么放行」。
                .onErrorResume(error -> {
                    Optional<List<String>> exhausted = EmptyResponseGate.exhaustedFrames(error);
                    if (exhausted.isEmpty()) {
                        return Flux.error(error);
                    }
                    List<String> frames = exhausted.get();
                    EmptyResponseGate.logExhaustedPassthrough(log, callCtx, frames.size());
                    emptyResponsePassthrough.set(true);
                    return Flux.fromIterable(frames);
                });

        // 静默重试循环：机制收归 CallResendLoop（阶段 3.6c-1），三条线路共用一份实现。
        // 形态归一是<strong>协议关联</strong>的一步，故留在本类、接在循环之外 ——
        // 位置等价：静默重发的那一轮产物也是循环输出的一部分，同样经过分类。
        Flux<UpstreamEvent> attemptLoop = CallResendLoop
                .loop(attempt, callRetryRegistry, requestId, "Responses", log, model)
                // 形态归一：把清洗后的事件分成「载荷」与「终止标记」两态。
                // 按<strong>上游协议</strong>分类 —— 本类发的就是 Responses 的事件。
                .map(data -> UpstreamEventClassifier.classify(
                        objectMapper, WireProtocol.RESPONSES, data));

        return attemptLoop
                // 成功往返收尾：仅在非错误终结时落一条成功记录。
                .doFinally(signal -> {
                    if (callRetryRegistry != null && requestId != null) {
                        callRetryRegistry.remove(requestId);
                    }
                    if (emptyResponsePassthrough.get()) {
                        return;
                    }
                    if (signal != SignalType.ON_ERROR) {
                        int statusCode = capturedStatusCode.get();
                        if (statusCode == 0 && logChunks.isEmpty()) {
                            statusCode = -1;
                        }
                        Long logId = saveStreamLog(providerKey, modelName, reqHeaders, requestBody,
                                capturedRespHeaders.get(), statusCode, logChunks, attemptStart.get(), ctx);
                        long ttfb = ttfbMs.get();
                        saveUsage(logId, providerKey, modelName, stream, usageRaw.get(),
                                ttfb < 0 ? null : (int) ttfb);
                    }
                });
    }

    // ==================== 请求构造 ====================

    /**
     * Responses 端点路径。
     *
     * <p>与 Anthropic 侧同一全局规则：<strong>保留</strong>数据库 Base URL 自带的路径，
     * 再接 {@code /responses}。已有供应商的 Base URL 通常是 {@code .../v1}，
     * 因而实际请求为 {@code .../v1/responses} —— 与官方端点一致。
     *
     * @see #normalizeResponsesBaseUrl
     */
    private String responsesUri() {
        return "/responses";
    }

    /**
     * 归一化 Responses 上游 Base URL，但<strong>不裁切路径</strong>。
     *
     * <p>取值来自 {@link ProviderRuntimeConfiguration#resolveResponsesBaseUrl()}：优先用
     * {@code provider_config.responses_base_url}，未配置时回退到 {@code base_url}。
     *
     * <p>独立成列而非从 base_url 推导，理由与 Anthropic 端点相同（中转站把端点摆在哪里
     * 不可预测，任何全局推导规则都只对某一批供应商成立）。但<strong>常态不同</strong>：
     * 多数中转站根本没有 Responses 端点，因此「留空回退 base_url」在这条线路上是
     * 常见情形而非例外 —— 那时请求会打到一个不存在的路径并得到 404，而那正是
     * 让用户感知到「这家不支持」并取消勾选的方式。
     */
    private String normalizeResponsesBaseUrl(ProviderRuntimeConfiguration provider) {
        return providerRequestHeaderService.normalizeBaseUrl(provider.resolveResponsesBaseUrl());
    }

    /**
     * 构建 WebClient 并抓取出站请求头快照。
     *
     * <p>结构与另两侧同形（两级抓取：WebClient 过滤器记录规则头，
     * Reactor Netty 的 {@code doAfterRequest} 再用传输层快照覆盖，
     * 因此日志里能看到 User-Agent、Host 等底层补入的头）。
     * 刻意不抽公共方法：它依赖三个注入字段，抽出去要传三个参数或再造一个 Bean，
     * 而本身只有二十行。
     *
     * <p>本方法比 Anthropic 那份短，唯一原因是这条线路<strong>不需要</strong>
     * {@code anthropic-version} 那样的协议必需头。鉴权头不在此列 —— 它的头名由
     * {@link AuthHeaderSetting} 这个供应商级配置决定，与协议无关，因此这条线路上出站的
     * 也可能是 {@code x-api-key}（依据见
     * {@code ProviderRequestHeaderService.applyAuthenticationHeaders}）。
     */
    private WebClient buildWebClient(Map<String, String> capturedHeaders,
                                     ProviderRuntimeConfiguration provider,
                                     HttpHeaders downstreamHeaders, boolean stream) {
        String apiKey = provider.apiKey();
        String normalizedUrl = normalizeResponsesBaseUrl(provider);
        HttpClient capturingHttpClient = httpClient.doAfterRequest((request, connection) -> {
            HttpHeaders transportHeaders = new HttpHeaders();
            request.requestHeaders().forEach(entry ->
                    transportHeaders.add(entry.getKey(), entry.getValue()));
            providerRequestHeaderService.mergeLogSnapshot(capturedHeaders, transportHeaders);
        });

        return webClientBuilder.clone()
                .clientConnector(new ReactorClientHttpConnector(capturingHttpClient))
                .baseUrl(normalizedUrl)
                .defaultHeaders(headers -> {
                    // 复用共享的请求头装配（下游头透传白名单、hop-by-hop 排除、鉴权头装配、
                    // 供应商头规则含 {apiKey} 占位与删除标记）。
                    // 出站鉴权头由供应商级配置决定，与本服务的协议无关 —— 「走 OpenAI 系」
                    // 不代表该发 Bearer，头名取决于用户配的「取下游 / 取设置」与承载方式。
                    providerRequestHeaderService.applyHeaders(
                            headers, downstreamHeaders, apiKey, provider.headerRulesJson(), stream,
                            AuthHeaderSetting.parse(provider.authHeaderJson(), objectMapper));
                })
                .filter((request, next) -> {
                    capturedHeaders.clear();
                    capturedHeaders.putAll(providerRequestHeaderService.createLogSnapshot(request.headers()));
                    return next.exchange(request);
                }).build();
    }

    /**
     * 准备 Responses 请求体。
     *
     * <h2>比另两条线路少得多，因为直连不需要形态转换</h2>
     * 只做四件事：改模型名（剥供应商前缀）、设 {@code stream}、注入思考深度、执行规则。
     * Anthropic 那份还要提取 system 到顶层、补必填的 {@code max_tokens}、协调两个思考维度，
     * 那些都是「下游说 Chat、上游说 Anthropic」留下的债 —— 这里下游与上游说同一种协议。
     *
     * <h2>两个刻意不做的注入</h2>
     * <ol>
     *   <li><strong>不注入 {@code max_output_tokens}。</strong>
     *       {@code MaxOutputTokensSetting} 只有 Anthropic 线路消费，因为那条线路
     *       {@code max_tokens} <strong>必填</strong>、不补就发不出去。Responses 的
     *       {@code max_output_tokens} 与 Chat 的 {@code max_tokens} 一样是<strong>可选</strong>的，
     *       接上会给所有「下游没带」的调用凭空补一个上限 —— 而 Copilot 通常就是不带。
     *       <p>这一点很容易顺手接上（字段名就叫 {@code max_output_tokens}，看起来天造地设），
     *       所以在这里写清为什么不接，而不是留个空白让人补。</li>
     *   <li><strong>不注入 Anthropic 的思考方式。</strong>
     *       {@code AnthropicThinkingSetting} 写的是 {@code thinking} 对象，那是 Anthropic
     *       的形态，Responses 协议里没有这个字段。思考深度已由
     *       {@link ReasoningEffortSetting#applyToResponses} 写成 {@code reasoning.effort}，
     *       而这条线路上不存在第二个思考维度需要协调 —— 也因此这里不需要 Anthropic 侧
     *       那套「深度先、方式后、off 档跳过方式」的顺序约束。</li>
     * </ol>
     *
     * <h2>请求体转换规则的执行位置</h2>
     * 规则在协议字段注入<strong>之后</strong>执行，但在
     * {@code removeIf(Objects::isNull)} <strong>之前</strong>：
     * <ul>
     *   <li>在注入之后 —— 规则的字段路径是照最终发往上游的形态写的，
     *       若在注入前执行，用户看到的预览与实际请求体结构不一致；</li>
     *   <li>在清洗之前 —— 规则可能把某个字段显式设为 null 表达「删掉它」，
     *       最终清洗必须是链条的最后一步。</li>
     * </ul>
     *
     * <p>协议筛选由引擎完成：只有声明适用 {@link WireProtocol#RESPONSES} 的规则组才会执行。
     * 库里那些照 Chat 或 Anthropic 结构写的规则不会在此静默匹配失败。
     */
    private Map<String, Object> prepareRequestBody(Map<String, Object> request, boolean stream,
                                                   String model, ProviderRuntimeConfiguration provider) {
        // 阶段序列 —— 比另两条线路短，因为直连不需要形态转换：
        //   1. 复制           copyRequestBody     主干会逐阶段改写 body
        //   2. 解析模型名     resolveModel        后续阶段都要用它查配置
        //   3. 写协议字段     writeProtocolFields 主干自己决定的 model 与 stream
        //   4. 思考深度       applyReasoningEffort 写 reasoning.effort（Responses 的形态）
        //   5. 请求体规则     applyBodyRules      协议筛选由引擎完成
        //   6. 清 null        removeNullFields    必须是最后一步
        Map<String, Object> body = copyRequestBody(request);
        String resolvedModel = resolveModel(body.get("model"), model);
        writeProtocolFields(body, resolvedModel, stream);
        applyReasoningEffort(body, resolvedModel, provider);
        applyBodyRules(body, provider);
        removeNullFields(body);
        return body;
    }

    /**
     * 阶段 1：复制请求体 —— 理由同另两条线路。
     */
    private static Map<String, Object> copyRequestBody(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    /**
     * 阶段 3：写入主干自己决定的协议字段 —— 理由同另两条线路。
     */
    private static void writeProtocolFields(Map<String, Object> body, String resolvedModel, boolean stream) {
        body.put("model", resolvedModel);
        body.put("stream", stream);
    }

    /**
     * 阶段 4：思考深度。
     *
     * <p>本线路写的是 Responses 的 {@code reasoning.effort}（形态与另两条线路都不同），
     * 由 {@link ReasoningEffortSetting#applyToResponses} 负责。
     *
     * <p>这里<strong>不需要</strong> Anthropic 那套「深度先、方式后、off 档跳过方式」的
     * 顺序约束：本线路上不存在第二个思考维度需要协调，理由见本方法原先的 javadoc
     * （「两个刻意不做的注入」已在 {@code prepareRequestBody} 的序列说明中保留）。
     */
    private void applyReasoningEffort(Map<String, Object> body, String resolvedModel,
                                      ProviderRuntimeConfiguration provider) {
        resolveReasoningEffort(resolvedModel, provider).applyToResponses(body);
    }

    /**
     * 阶段 6：清掉所有值为 {@code null} 的字段 —— <strong>必须是链条的最后一步</strong>。
     *
     * <p>理由同另两条线路：规则可能把字段显式设为 null 表达「删掉它」。
     */
    private static void removeNullFields(Map<String, Object> body) {
        body.values().removeIf(Objects::isNull);
    }

    /**
     * 从运行时模型配置中读取思考深度设置。
     *
     * <p>与另两条线路读的是<strong>同一列</strong>（{@code provider_model.reasoning_effort}）、
     * 同一份解析与同一套四档语义，只有出站的字段名与形态不同。因此此处不引入
     * 第二份配置 —— 用户在界面上看到的就是一个模型一个档位，无论它走哪条线路。
     *
     * <p>模型名查不到时用 {@link ReasoningEffortSetting#defaults()}（medium + 兜底），
     * 与另两侧同一形状。
     */
    private ReasoningEffortSetting resolveReasoningEffort(String resolvedModel,
                                                         ProviderRuntimeConfiguration provider) {
        for (var candidate : provider.models()) {
            if (resolvedModel.equals(candidate.modelName())) {
                return ReasoningEffortSetting.parse(candidate.reasoningEffort(), objectMapper);
            }
        }
        return ReasoningEffortSetting.defaults();
    }

    /**
     * 执行适用于 Responses 线路的请求体规则组。
     *
     * <p>引擎返回新 Map 而非原地修改，这里原地替换内容以保留调用方持有的引用。
     */
    private void applyBodyRules(Map<String, Object> body, ProviderRuntimeConfiguration provider) {
        RequestBodyRuleEngine.TransformResult result = requestBodyRuleEngine.transform(
                body, provider.bodyRulesJson(), WireProtocol.RESPONSES);
        body.clear();
        body.putAll(result.output());
        for (RequestBodyRuleEngine.TransformWarning warning : result.warnings()) {
            log.warn("[Responses] 请求体规则已跳过: ruleId={}, path={}, message={}",
                    warning.ruleId(), warning.fieldPath(), warning.message());
        }
    }

    /**
     * 剥离供应商前缀，取真实上游模型名。与另两侧同一工具、同一顺序。
     *
     * <p>优先用请求体里的 {@code model}，缺失时回退到路由解析出的那个；两者都空则返回空串
     * —— 不返回 null，那会让 {@code body.put("model", ...)} 塞进一个 null 并在
    /**
     * 解析出真实的上游模型名（剥除供应商前缀）。与另两条线路同一语义。
     *
     * <p>两个来源不是「回退关系」，是同一个值的两条路：{@code requestModel} 来自请求体、
     * {@code routedModel} 来自路由解析，生产路径上二者同值，测试常只给其中一个。
     * 从前的第三层（可枚举的默认模型名）已在 3.3a 剔除 —— 理由见
     * {@code AbstractUpstreamChatService.resolveModel} 的注释。
     *
     * @param requestModel 请求体里的模型名，可能为 null
     * @param routedModel  路由解析出的模型名，可能为 null
     * @return 剥除供应商前缀后的真实模型名
     */
    private String resolveModel(Object requestModel, String routedModel) {
        String model;
        if (requestModel instanceof String value && !value.isBlank()) {
            model = value;
        } else if (routedModel != null && !routedModel.isBlank()) {
            model = routedModel;
        } else {
            // 不可达：路由层已拦。保留显式抛错而非凭空返回，理由同 Chat 侧。
            throw new IllegalArgumentException("请求缺少 model：路由层应已拒绝，不应到达此处");
        }
        return ModelNameUtil.parse(model).modelName();
    }

    // ==================== 重试 ====================

    /**
     * 构造重试规格。
     *
     * <h2>它只做三件事：取时长、报标识、委托</h2>
     * 规格本身（读配置 + {@code Retry.backoff} + {@code filter} + {@code doBeforeRetry}
     * 里的 RETRYING 事件与日志）已收归 {@link UpstreamAutoRetry}（阶段 3.6c-2），
     * 三条线路共用一份。本方法保留为<strong>适配器</strong>：把本类的注入字段、
     * 自己的 logger、自己那两个<strong>退避覆盖点</strong>绑给那个类。
     *
     * <p>原本它只与另两侧共享「次数来自同一个配置项」这一条约束；
     * 现连实现也共用。顺带补齐了一处<strong>抄漏</strong>：429 / {@code Retry-After}
     * 的日志特化此前只在 Chat，而 429 是 HTTP 层事实、不是协议差异 ——
     * 理由详见 {@link UpstreamAutoRetry} 的类注释。
     */
    private Retry buildRetrySpec(String method, ProviderRuntimeConfiguration provider,
                                 String requestId, String model, boolean stream) {
        return UpstreamAutoRetry.build(
                new UpstreamAutoRetry.CallContext(method, "Responses", provider.providerKey(),
                        requestId, model, stream),
                retryPolicyService, lifecycleNotifier,
                retryFirstBackoff(), retryMaxBackoff(), log);
    }

    /** 首次退避时长。可覆盖以便测试压缩等待，理由同另两侧。 */
    protected Duration retryFirstBackoff() {
        return Duration.ofSeconds(2);
    }

    /** 退避上限。可覆盖以便测试压缩等待。 */
    protected Duration retryMaxBackoff() {
        return Duration.ofSeconds(30);
    }


    // ==================== 落库与观测 ====================

    /**
     * 非流式落库。
     *
     * <h2>下游协议从 ctx 取，不再是硬编码常量</h2>
     * 它曾读一个写死 {@code RESPONSES} 的常量 —— 那对直连正确，但
     * <strong>C2R / R2C 落地后会静默记错</strong>：协议列仍写 RESPONSES，
     * 而日志里看不出这是一次跳协议调用。两列分居两处（常量与实际路由）时，
     * 不一致不会报错，只会让查询「按协议筛」时少掉那些行。
     *
     * <p>改为从 ctx 取后，它<strong>自动正确</strong> —— 无论直连还是将来的翻译路线。
     * 上游协议仍恒为 {@code RESPONSES}（本执行器只打那个端点）。
     *
     * <p>本方法不收 chunk 改写器参数：Responses 目前只有直连一条路、
     * 没有需要改写的帧。跨协议落地时按 Anthropic 侧的形状补
     * （{@code docs/RESPONSES_PROTOCOL_PLAN.md} §14.7 记的正是这个取舍）。
     */
    private Long saveNonStreamLog(String providerKey, String modelName, Map<String, String> reqHeaders,
                                  Map<String, Object> requestBody, Map<String, String> respHeaders,
                                  int statusCode, String responseBody, long startTime,
                                  RequestPipelineContext ctx) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveNonStream(providerKey, modelName,
                ctx.downstreamProtocol().name(), WireProtocol.RESPONSES.name(),
                reqHeaders, requestBody, respHeaders,
                statusCode, responseBody, duration);
    }

    /** 流式落库。chunk 原样记录 —— 直连路线下游收到的就是这些事件。协议从 ctx 取。 */
    private Long saveStreamLog(String providerKey, String modelName, Map<String, String> reqHeaders,
                               Map<String, Object> requestBody, Map<String, String> respHeaders,
                               int statusCode, List<String> chunks, long startTime,
                               RequestPipelineContext ctx) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveStream(providerKey, modelName,
                ctx.downstreamProtocol().name(), WireProtocol.RESPONSES.name(),
                reqHeaders, requestBody, respHeaders,
                statusCode, ChunkLogPayload.direct(chunks), duration);
    }

    private Long saveStreamLogWithError(String providerKey, String modelName, Map<String, String> reqHeaders,
                                        Map<String, Object> requestBody, Map<String, String> respHeaders,
                                        int statusCode, List<String> chunks, Map<String, String> errorHeaders,
                                        int errorCode, String errorBody, long startTime,
                                        RequestPipelineContext ctx) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveStreamWithError(providerKey, modelName,
                ctx.downstreamProtocol().name(), WireProtocol.RESPONSES.name(),
                reqHeaders, requestBody, respHeaders, statusCode,
                ChunkLogPayload.direct(chunks), errorHeaders,
                errorCode, errorBody, duration);
    }

    /**
     * 用量写入。
     *
     * <p>与 Anthropic 侧不同，这里<strong>只有一个入口</strong>：Responses 的 usage
     * 一次给全，不需要「流式传已合并的 tokens、非流式解析单份」那两个重载。
     *
     * <p>{@code publishCallRecorded()} 放在 finally：无论用量是否实际写入，
     * 落库流程走完即宣告记录就绪。与另两侧同一语义 —— 失败调用与上游未返回 usage
     * 的调用本就不写用量行，若按「两张表都写了」判定，这些记录永远不会实时出现在前端。
     */
    private void saveUsage(Long logId, String providerKey, String modelName, boolean stream,
                           String usageRaw, Integer ttfbMs) {
        try {
            if (apiCallUsage == null) return;
            if (usageRaw == null) return;
            apiCallUsage.save(logId, providerKey, modelName, stream, usageRaw,
                    ResponsesUsageParser.parseUsageObject(objectMapper, usageRaw), ttfbMs);
        } finally {
            publishCallRecorded();
        }
    }

    /**
     * 发布调用记录变更信号 —— 实现见 {@link UpstreamCallReporter#publishCallRecorded}。
     * 本方法只留作适配器（绑定本类的可选字段与 logger）。
     */
    private void publishCallRecorded() {
        UpstreamCallReporter.publishCallRecorded(log, apiCallLog);
    }

    /**
     * best-effort 发出一个生命周期事件 —— 实现见 {@link UpstreamCallReporter#publishLifecycle}。
     * 本方法只留作适配器。
     */
    private void publishLifecycle(CallLifecycleEvent event) {
        UpstreamCallReporter.publishLifecycle(log, lifecycleNotifier, event);
    }
}

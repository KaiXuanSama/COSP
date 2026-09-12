package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ReasoningEffortSetting;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallRetryRegistry;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import com.kaixuan.copilot_ollama_proxy.provider.DownstreamLogView;
import com.kaixuan.copilot_ollama_proxy.provider.EmptyUpstreamResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用 <strong>Responses</strong> 上游服务 —— 对接 OpenAI Responses API 协议的供应商。
 *
 * <h2>为何与另两个上游服务平级，而不复用它们</h2>
 * 三条线路的 Reactor 链体<strong>结构不同</strong>：
 * <ul>
 *   <li>{@code AbstractUpstreamChatService}（Chat）流式用一道 gate 做缓存-释放，
 *       在「确认非空」之前扣住帧不下发；</li>
 *   <li>{@code GenericAnthropicChatService} 不能扣帧（客户端是事件状态机，
 *       扣住 {@code message_start} 会让它无法初始化），改为「边下发边记录是否见过载荷」；</li>
 *   <li>本类与 Anthropic 同构 —— Responses 客户端同样是事件状态机，
 *       扣住 {@code response.created} 一样会破坏它。</li>
 * </ul>
 *
 * <p>那么为何不继承 Anthropic 那份？因为「结构同构」不等于「实现可共享」：
 * 两者的请求体准备、空响应取值路径、usage 提取、终态判定全部不同，
 * 抽出去只剩一个空壳加一堆钩子 —— 模板方法反而让实现者看不见自己正在依赖什么。
 * 这与 Anthropic 当初不继承 Chat 是同一个判断。
 *
 * <p>代价是接线顺序在三处各写一遍。这是<strong>有意接受</strong>的：接线顺序写出来
 * 带注释比藏在基类里更可审。<strong>抽取的触发信号是三侧出现口径差异</strong>
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
public class GenericResponsesChatService {

    private static final Logger log = LoggerFactory.getLogger(GenericResponsesChatService.class);

    /** SSE 场景下，每个 data 字段的原始字符串类型引用。 */
    private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final ObjectMapper objectMapper;
    private final ProviderRequestHeaderService providerRequestHeaderService;
    private final RequestBodyRuleEngine requestBodyRuleEngine;

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
                                       RequestBodyRuleEngine requestBodyRuleEngine) {
        this.objectMapper = objectMapper;
        this.providerRequestHeaderService = providerRequestHeaderService;
        this.requestBodyRuleEngine = requestBodyRuleEngine;
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

    // ==================== 对外入口 ====================

    /**
     * 直连落库视图：上下游协议相同、chunk 不改写。
     *
     * <p>当前<strong>只有</strong>这一个视图。C2R / R2C 翻译尚未实现，因此没有任何调用方
     * 需要声明「下游其实是另一个协议」。翻译落地时按 Anthropic 侧的形状加带视图的重载 ——
     * 那边的四个重载正是为此存在。
     */
    private static final DownstreamLogView DIRECT_VIEW =
            DownstreamLogView.direct(WireProtocol.RESPONSES.name());

    /** 非流式，接受应用层已解析的路由。 */
    public Mono<String> responses(Map<String, Object> request, ResolvedProviderRoute route,
                                  HttpHeaders downstreamHeaders, String requestId) {
        return responses(request, route.model(), route.provider(), downstreamHeaders, requestId);
    }

    /** 流式，接受应用层已解析的路由。 */
    public Flux<String> responsesStream(Map<String, Object> request, ResolvedProviderRoute route,
                                        HttpHeaders downstreamHeaders, String requestId) {
        return responsesStream(request, route.model(), route.provider(), downstreamHeaders, requestId);
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
    protected Mono<String> responses(Map<String, Object> request, String model,
                                     ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                     String requestId) {
        Map<String, Object> requestBody = prepareRequestBody(request, false, model, provider);
        log.info("{} Responses 上游，模型: {}, 流式: false", provider.providerKey(), requestBody.get("model"));

        String providerKey = provider.providerKey();
        String modelName = (String) requestBody.get("model");
        Map<String, String> reqHeaders = new LinkedHashMap<>();
        AtomicLong attemptStart = new AtomicLong(System.currentTimeMillis());

        return Mono.defer(() -> {
                    attemptStart.set(System.currentTimeMillis());
                    return buildWebClient(reqHeaders, provider, downstreamHeaders, false)
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
                            entity.getStatusCode().value(), entity.getBody(), attemptStart.get());
                    // ttfb 传 null：非流式没有首字概念，与另两侧一致。
                    saveUsage(logId, providerKey, modelName, false,
                            ResponsesUsageParser.extractUsageRawJson(objectMapper, entity.getBody()),
                            null);
                })
                // 失败往返：每次失败（含被 retry 吞掉的中间失败）都各自落一条。
                .doOnError(e -> {
                    WebClientResponseException responseException = findWebResponseException(e);
                    if (responseException != null) {
                        Map<String, String> errHeaders = new LinkedHashMap<>();
                        responseException.getHeaders().forEach((k, v) -> errHeaders.put(k, String.join(", ", v)));
                        saveNonStreamLog(providerKey, modelName, reqHeaders, requestBody, errHeaders,
                                responseException.getStatusCode().value(),
                                responseException.getResponseBodyAsString(), attemptStart.get());
                        publishCallRecorded();
                    } else {
                        // 状态码 -1：非 HTTP 异常的占位值，与另两侧同一约定。
                        saveNonStreamLog(providerKey, modelName, reqHeaders, requestBody, Map.of(), -1, null,
                                attemptStart.get());
                        publishCallRecorded();
                    }
                })
                // 空响应兜底：转成异常以复用下方同一条 retryWhen 的预算。
                .flatMap(entity -> {
                    String body = entity.getBody();
                    if (ResponsesContentDetector.hasMeaningfulPayload(objectMapper, body)) {
                        return Mono.just(entity);
                    }
                    log.warn("{} 上游空响应（无正文/思考链/工具调用），body 长度 {}，将按重试预算重发 [{}] {}",
                            providerKey, body == null ? 0 : body.length(), model, requestId);
                    return Mono.error(new EmptyUpstreamResponseException(
                            body == null ? List.of() : List.of(body)));
                })
                .retryWhen(buildRetrySpec("responses", provider, requestId, modelName, false))
                // 空 body 已在上面被判空转成异常，此处 getBody() 不会为 null。
                .map(entity -> entity.getBody())
                // 空响应重试耗尽：放行最后一轮的原始 body，保持「透传上游真实返回」语义。
                .onErrorResume(error -> {
                    EmptyUpstreamResponseException emptyResponse = findEmptyUpstreamException(error);
                    if (emptyResponse == null) {
                        return Mono.error(error);
                    }
                    List<String> frames = emptyResponse.bufferedFrames();
                    log.warn("{} 上游空响应重试耗尽，放行最后一轮的响应体给下游 [{}] {}",
                            providerKey, model, requestId);
                    return Mono.just(frames.isEmpty() ? "" : frames.get(0));
                });
    }

    // ==================== 流式 ====================

    /**
     * 发送一次流式 Responses 请求。
     *
     * <h2>不设 gate 的缓存-释放机制，与 Anthropic 侧同一理由</h2>
     * Chat 侧需要 gate 是因为要在「确认非空」之前扣住帧不下发，而 Responses 的事件流
     * <strong>必须按序完整下发</strong> —— 下游客户端是状态机，扣住
     * {@code response.created} 会让它无法初始化。
     *
     * <p>因此改为「边下发边记录是否见过实质载荷」，整轮结束后若一次都没见过就抛异常触发重试。
     * 代价是空响应那一轮的事件已经流到下游了；但那正是耗尽后本来也要做的事
     * （透传上游真实返回），而重发的新一轮会追加在后面。
     *
     * <h2>手动（静默）重试已接入</h2>
     * {@code CallRetryRegistry} + {@code takeUntilOther}，与另两侧同构。
     *
     * <p>Anthropic 侧曾因「一个未收到终止事件的序列后接一个全新的开始事件，对严格客户端
     * 是否合法尚未验证」而暂缓接入，后来按功能完整性优先补上了。此处直接接入：
     * 前端菜单项的条件只看是否流式、看不到上游协议，不接会让它表现为
     * 「点了没反应、无任何报错」—— 那比理论风险更明确地有害。
     */
    protected Flux<String> responsesStream(Map<String, Object> request, String model,
                                            ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                            String requestId) {
        Map<String, Object> requestBody = prepareRequestBody(request, true, model, provider);
        log.info("{} Responses 上游，模型: {}, 流式: true", provider.providerKey(), requestBody.get("model"));

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
        // 本轮是否见过实质载荷。整轮为 false 即判空响应。
        AtomicBoolean sawPayload = new AtomicBoolean(false);
        // 空响应耗尽放行标记：该轮已在 doOnError 落过库，收尾处据此跳过，避免重复记录。
        AtomicBoolean emptyResponsePassthrough = new AtomicBoolean(false);

        Flux<String> attempt = Flux.defer(() -> {
                    // 每轮往返起点重置：使每条日志只反映该次往返，不跨重试累加。
                    // 状态在 defer 内重置而非声明处初始化 —— retryWhen 会重订阅，
                    // 若不重置，第二轮会带着第一轮的 sawPayload 与 chunk 记录。
                    attemptStart.set(System.currentTimeMillis());
                    logChunks.clear();
                    ttfbMs.set(-1);
                    usageRaw.set(null);
                    sawPayload.set(false);
                    return buildWebClient(reqHeaders, provider, downstreamHeaders, true)
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
                                                attemptStart.get());
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
                    // 实质载荷判定：逐事件看，一轮内有一次为真即够。
                    if (!sawPayload.get() && ResponsesContentDetector.eventHasPayload(objectMapper, data)) {
                        sawPayload.set(true);
                    }
                    // usage 只在终态事件出现，但仍无条件尝试提取：某些上游中途也带一份，
                    // 后到的覆盖先到的，终态那份最终胜出。
                    String extracted = ResponsesUsageParser.extractUsageRawJson(objectMapper, data);
                    if (extracted != null) {
                        usageRaw.set(extracted);
                    }
                    logChunks.add(data);
                })
                // 轮末综合判定：整轮从未见过实质载荷即为空响应。
                // 放在 concatWith 而非 doFinally —— 只有前者能把错误信号注入流中。
                // 此处也覆盖「0 事件」的情形：一个事件都没来，sawPayload 自然为假。
                .concatWith(Flux.defer(() -> {
                    if (sawPayload.get()) {
                        return Flux.<String>empty();
                    }
                    log.warn("{} 上游空响应（无正文/思考链/工具调用），事件数 {}，将按重试预算重发 [{}] {}",
                            providerKey, logChunks.size(), model, requestId);
                    return Flux.error(new EmptyUpstreamResponseException(List.copyOf(logChunks)));
                }))
                // 网络类失败往返：错误响应已在 exchangeToFlux 分支落库，
                // 此处用 findWebResponseException == null 排除以免重复。
                .doOnError(e -> {
                    EmptyUpstreamResponseException emptyResponse = findEmptyUpstreamException(e);
                    if (emptyResponse != null) {
                        saveStreamLog(providerKey, modelName, reqHeaders, requestBody,
                                capturedRespHeaders.get(), capturedStatusCode.get(),
                                emptyResponse.bufferedFrames(), attemptStart.get());
                        publishCallRecorded();
                        return;
                    }
                    if (findWebResponseException(e) == null) {
                        int statusCode = capturedStatusCode.get() == 0 ? -1 : capturedStatusCode.get();
                        saveStreamLog(providerKey, modelName, reqHeaders, requestBody,
                                capturedRespHeaders.get(), statusCode, List.copyOf(logChunks),
                                attemptStart.get());
                        publishCallRecorded();
                    }
                })
                .retryWhen(buildRetrySpec("responsesStream", provider, requestId, model, true))
                // 空响应耗尽：放行最后一轮的事件，与其他失败「耗尽后透传最后一次响应」一致。
                .onErrorResume(error -> {
                    EmptyUpstreamResponseException emptyResponse = findEmptyUpstreamException(error);
                    if (emptyResponse == null) {
                        return Flux.error(error);
                    }
                    log.warn("{} 上游空响应重试耗尽，放行最后一轮的 {} 个事件给下游 [{}] {}",
                            providerKey, emptyResponse.bufferedFrames().size(), model, requestId);
                    emptyResponsePassthrough.set(true);
                    return Flux.fromIterable(emptyResponse.bufferedFrames());
                });

        // 静默重试循环：与另两侧同构。把重试信号挂到整轮尝试（含 retryWhen 的 backoff 等待）上，
        // 使请求进行中与退避等待两个阶段都能被信号中断。被中断的轮次以无值完成收场，
        // 若标志为真则递归重发；每次重发都注册新鲜的信号，因此可以连续点击。
        // 下游断连时整个链被取消，递归随之终止。
        //
        // 不消耗 retryWhen 的预算：那条预算属于「COSP 自己判定的失败」，
        // 而这里是管理员的显式意图，两者不该互相挤占。
        AtomicBoolean silentRetryRequested = new AtomicBoolean(false);
        AtomicReference<Flux<String>> attemptLoopRef = new AtomicReference<>();
        Flux<String> attemptLoop = Flux.defer(() -> {
                    Mono<Void> silentRetrySignal = callRetryRegistry == null || requestId == null
                            ? Mono.never()
                            : callRetryRegistry.register(requestId)
                                    .doOnSuccess(v -> silentRetryRequested.set(true));
                    return attempt.takeUntilOther(silentRetrySignal);
                })
                .concatWith(Flux.defer(() -> {
                    if (silentRetryRequested.compareAndSet(true, false)) {
                        log.info("静默重试：重新发起 Responses 上游请求 [{}] {}", model, requestId);
                        return attemptLoopRef.get();
                    }
                    return Flux.<String>empty();
                }));
        attemptLoopRef.set(attemptLoop);

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
                                capturedRespHeaders.get(), statusCode, logChunks, attemptStart.get());
                        long ttfb = ttfbMs.get();
                        saveUsage(logId, providerKey, modelName, true, usageRaw.get(),
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
     * <p>Responses 与 Chat 同为 OpenAI 系，因此鉴权用 {@code Authorization: Bearer}
     * 且<strong>不发</strong> {@code anthropic-version} —— 这两点都由
     * {@code ProviderRequestHeaderService.applyAuthenticationHeaders} 按出站协议自动处理，
     * 本类不需要额外补任何协议头。这也是本方法比 Anthropic 那份短的唯一原因。
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
                    // 复用共享的请求头装配（下游头透传白名单、hop-by-hop 排除、按出站协议
                    // 装配鉴权头、供应商头规则含 {apiKey} 占位与删除标记）。
                    // 出站协议恒为 RESPONSES：本服务只打 Responses 端点，因此鉴权装配
                    // 写 Authorization: Bearer 并删掉 x-api-key —— 后者在这条链路上是噪音，
                    // 可能来自下游透传（某些客户端按 Anthropic 惯例发它）。
                    providerRequestHeaderService.applyHeaders(
                            headers, downstreamHeaders, apiKey, provider.headerRulesJson(), stream,
                            WireProtocol.RESPONSES);
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
        Map<String, Object> body = new LinkedHashMap<>(request);
        String resolvedModel = resolveModel(body.get("model"), model);
        body.put("model", resolvedModel);
        body.put("stream", stream);

        resolveReasoningEffort(resolvedModel, provider).applyToResponses(body);

        applyBodyRules(body, provider);

        body.values().removeIf(Objects::isNull);
        return body;
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
     * 末尾清洗时被移除，上游收到一个没有 model 字段的请求体，报错指向别处。
     */
    private String resolveModel(Object requestModel, String fallbackModel) {
        String model;
        if (requestModel instanceof String value && !value.isBlank()) {
            model = value;
        } else if (fallbackModel != null && !fallbackModel.isBlank()) {
            model = fallbackModel;
        } else {
            return "";
        }
        return ModelNameUtil.parse(model).modelName();
    }

    // ==================== 重试 ====================

    /**
     * 构造重试规格。
     *
     * <p>预算来自 {@link RetryPolicyService}，与另两侧<strong>同一个配置来源</strong>：
     * 正数为次数、{@code 0} 不重试、{@code -1} 无限。
     * 这是「重试次数只有一个来源」这条约束在三条线路上的延续 ——
     * 实现各写一份，但配置来源不分叉。
     */
    private Retry buildRetrySpec(String method, ProviderRuntimeConfiguration provider,
                                 String requestId, String model, boolean stream) {
        int configured = retryPolicyService != null
                ? retryPolicyService.getMaxAttempts()
                : RetryPolicyService.DEFAULT_MAX_ATTEMPTS;
        long maxAttempts = RetryPolicyService.toReactorMaxAttempts(configured);
        boolean unlimited = configured == RetryPolicyService.UNLIMITED_MAX_ATTEMPTS;
        return Retry.backoff(maxAttempts, retryFirstBackoff()).maxBackoff(retryMaxBackoff())
                .filter(GenericResponsesChatService::isRetryableFailure)
                .doBeforeRetry(signal -> {
                    int attempt = (int) (signal.totalRetries() + 1);
                    publishLifecycle(CallLifecycleEvent.retrying(requestId, model, stream, attempt));
                    log.warn("[{}] {} Responses 调用失败，重试第 {}/{} 次: {}", method, provider.providerKey(),
                            attempt, unlimited ? -1 : maxAttempts, signal.failure().getMessage());
                });
    }

    /** 首次退避时长。可覆盖以便测试压缩等待，理由同另两侧。 */
    protected Duration retryFirstBackoff() {
        return Duration.ofSeconds(2);
    }

    /** 退避上限。可覆盖以便测试压缩等待。 */
    protected Duration retryMaxBackoff() {
        return Duration.ofSeconds(30);
    }

    /**
     * 可重试判定 —— 与另两侧同一口径（四类可恢复失败）。
     *
     * <p>这段逻辑纯粹基于异常类型与 HTTP 状态码，本身与协议无关，与
     * {@code AbstractUpstreamChatService} 和 {@code GenericAnthropicChatService} 里那两份
     * 目前逐字节相同。刻意不抽公共工具：抽取要改动两条已验证的线路，
     * 而三份相同的代价只是重复。<strong>抽取的触发信号是三侧出现口径差异</strong>
     * （那才说明有一侧被遗忘了），而不是「现在三份一样所以应该合并」。
     */
    private static boolean isRetryableFailure(Throwable failure) {
        if (failure instanceof WebClientRequestException) {
            return true;
        }
        if (hasSslHandshakeFailure(failure)) {
            return true;
        }
        if (failure instanceof WebClientResponseException responseException) {
            return isRetryableStatus(responseException.getStatusCode())
                    || hasNetworkCause(responseException);
        }
        // 空响应：复用同一份预算，使重试次数只有一个来源。
        return failure instanceof EmptyUpstreamResponseException;
    }

    /** 429 限速、5xx 服务端错误、400（容忍上游临时抽风）视为可重试。 */
    private static boolean isRetryableStatus(HttpStatusCode status) {
        int code = status.value();
        return code == 429 || status.is5xxServerError() || code == 400;
    }

    private static boolean hasNetworkCause(Throwable throwable) {
        Throwable cause = throwable.getCause();
        while (cause != null) {
            if (cause instanceof java.io.IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static boolean hasSslHandshakeFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof javax.net.ssl.SSLException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 递归解包 WebClientResponseException（retryWhen 耗尽时被包进 RetryExhaustedException）。 */
    private static WebClientResponseException findWebResponseException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WebClientResponseException responseException) {
                return responseException;
            }
            current = current.getCause();
        }
        return null;
    }

    /** 递归解包空响应异常，理由同上。 */
    private static EmptyUpstreamResponseException findEmptyUpstreamException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof EmptyUpstreamResponseException emptyResponse) {
                return emptyResponse;
            }
            current = current.getCause();
        }
        return null;
    }

    // ==================== 落库与观测 ====================

    /**
     * 非流式落库。
     *
     * <p>上下游协议都写 {@code RESPONSES}：当前只有直连一条路，没有翻译路线需要区分。
     * C2R / R2C 落地时按 Anthropic 侧的形状引入 {@link DownstreamLogView} 参数 ——
     * 那时下游协议由视图给出，而上游协议仍恒为 {@code RESPONSES}。
     */
    private Long saveNonStreamLog(String providerKey, String modelName, Map<String, String> reqHeaders,
                                  Map<String, Object> requestBody, Map<String, String> respHeaders,
                                  int statusCode, String responseBody, long startTime) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveNonStream(providerKey, modelName,
                DIRECT_VIEW.downstreamProtocol(), WireProtocol.RESPONSES.name(),
                reqHeaders, requestBody, respHeaders,
                statusCode, responseBody, duration);
    }

    /** 流式落库。chunk 原样记录 —— 直连路线下游收到的就是这些事件。 */
    private Long saveStreamLog(String providerKey, String modelName, Map<String, String> reqHeaders,
                               Map<String, Object> requestBody, Map<String, String> respHeaders,
                               int statusCode, List<String> chunks, long startTime) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveStream(providerKey, modelName,
                DIRECT_VIEW.downstreamProtocol(), WireProtocol.RESPONSES.name(),
                reqHeaders, requestBody, respHeaders,
                statusCode, DIRECT_VIEW.viewChunks(chunks), duration);
    }

    private Long saveStreamLogWithError(String providerKey, String modelName, Map<String, String> reqHeaders,
                                        Map<String, Object> requestBody, Map<String, String> respHeaders,
                                        int statusCode, List<String> chunks, Map<String, String> errorHeaders,
                                        int errorCode, String errorBody, long startTime) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveStreamWithError(providerKey, modelName,
                DIRECT_VIEW.downstreamProtocol(), WireProtocol.RESPONSES.name(),
                reqHeaders, requestBody, respHeaders, statusCode,
                DIRECT_VIEW.viewChunks(chunks), errorHeaders,
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

    private void publishCallRecorded() {
        if (apiCallLog == null) return;
        try {
            apiCallLog.publishCallRecorded();
        } catch (Exception e) {
            log.warn("发布调用记录变更信号失败: {}", e.getMessage());
        }
    }

    private void publishLifecycle(CallLifecycleEvent event) {
        if (lifecycleNotifier == null) {
            return;
        }
        try {
            lifecycleNotifier.publish(event);
        } catch (Exception e) {
            log.debug("生命周期事件发布失败（已忽略）: {}", e.getMessage());
        }
    }
}

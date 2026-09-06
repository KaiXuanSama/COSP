package com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.AnthropicThinkingSetting;
import com.kaixuan.copilot_ollama_proxy.application.runtime.MaxOutputTokensSetting;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ReasoningEffortSetting;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用 Anthropic 上游服务 —— 对接 Anthropic Messages API 协议的供应商。
 *
 * <h2>为何与 {@code AbstractUpstreamChatService} 平级而非继承它</h2>
 * 两种协议的 Reactor 链体<strong>结构不同</strong>：OpenAI 流式用一道 gate 做
 * 缓存-释放，Anthropic 需要在一轮内累积「是否见过实质载荷」并维护 block 状态。
 * 若强行抽公共父类，那两个方法会退化成一堆钩子 —— 模板方法反而让子类作者
 * 看不见自己正在依赖什么。
 *
 * <p>代价是接线顺序在两处各写一遍。这是<strong>有意接受</strong>的：接线顺序
 * 写出来带注释比藏在基类里更可审。其中最贵的一条约束是空响应判定的位置，
 * 见 {@link #chatCompletion} 的说明。
 *
 * <h2>与 OpenAI 侧共享的策略（不可各自为政）</h2>
 * <ul>
 *   <li>重试预算 —— 同样从 {@link RetryPolicyService} 读 {@code retry_max_attempts}，
 *       空响应同样包成 {@link EmptyUpstreamResponseException} 走同一条 {@code retryWhen}，
 *       因此「重试次数」在两个协议上仍然只有一个配置来源；</li>
 *   <li>三类实质载荷的类别定义 —— 见 {@link AnthropicContentDetector}；</li>
 *   <li>解析失败保守放行；</li>
 *   <li>{@code publishCallRecorded()} 的 finally 语义；</li>
 *   <li>{@link UsageTokens} 输出契约与 null / 0 的区分；</li>
 *   <li>状态码 {@code -1} 作为非 HTTP 异常的占位值。</li>
 * </ul>
 *
 * <h2>请求体的协议差异</h2>
 * Anthropic 与 OpenAI 的请求体有三处硬差异，见 {@link #prepareRequestBody}：
 * {@code system} 是顶层字段而非 {@code messages} 里的一条、{@code max_tokens} 必填、
 * 思考用 {@code thinking} 对象（方式）加顶层 {@code output_config.effort}（深度）
 * 而非单个 {@code reasoning_effort} 字符串。
 */
@Service
public class GenericAnthropicChatService {

    private static final Logger log = LoggerFactory.getLogger(GenericAnthropicChatService.class);

    /** SSE 场景下，每个 data 字段的原始字符串类型引用。 */
    private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    /**
     * Anthropic API 版本头。这是<strong>必需</strong>的请求头，缺失时官方 API 返回 400。
     *
     * <p>值固定而非可配：它标识的是「本代理按哪一版协议构造请求」，属于代码事实
     * 而非用户偏好。升级协议版本必然伴随代码改动，那时一并改这里。
     */
    private static final String ANTHROPIC_VERSION_HEADER = "anthropic-version";
    private static final String ANTHROPIC_VERSION_VALUE = "2023-06-01";


    private final ObjectMapper objectMapper;
    private final ProviderRequestHeaderService providerRequestHeaderService;
    private final RequestBodyRuleEngine requestBodyRuleEngine;

    private ApiCallLogService apiCallLog;
    private ApiCallUsageService apiCallUsage;
    private CallLifecycleNotifier lifecycleNotifier;
    private RetryPolicyService retryPolicyService;
    private WebClient.Builder webClientBuilder = WebClient.builder();
    private HttpClient httpClient = HttpClient.create();

    public GenericAnthropicChatService(ObjectMapper objectMapper,
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
     * 本次调用的落库视图。
     *
     * <h2>为何用 ThreadLocal 而不是方法参数
     * —— 并不是，看下面</h2>
     * 并非 ThreadLocal。视图通过重载方法传入，默认值为直连视图，
     * 因此现有调用方（包括测试）无需改动。
     */
    private static final DownstreamLogView DIRECT_VIEW =
            DownstreamLogView.direct(WireProtocol.ANTHROPIC.name());

    /** 非流式，接受应用层已解析的路由。直连路线。 */
    public Mono<String> messages(Map<String, Object> request, ResolvedProviderRoute route,
                                 HttpHeaders downstreamHeaders, String requestId) {
        return messages(request, route, downstreamHeaders, requestId, DIRECT_VIEW);
    }

    /**
     * 非流式，带落库视图。翻译路线用这个重载告知「下游其实是另一个协议」。
     */
    public Mono<String> messages(Map<String, Object> request, ResolvedProviderRoute route,
                                 HttpHeaders downstreamHeaders, String requestId,
                                 DownstreamLogView logView) {
        return messages(request, route.model(), route.provider(), downstreamHeaders, requestId, logView);
    }

    /** 流式，接受应用层已解析的路由。直连路线。 */
    public Flux<String> messagesStream(Map<String, Object> request, ResolvedProviderRoute route,
                                       HttpHeaders downstreamHeaders, String requestId) {
        return messagesStream(request, route, downstreamHeaders, requestId, DIRECT_VIEW);
    }

    /** 流式，带落库视图。 */
    public Flux<String> messagesStream(Map<String, Object> request, ResolvedProviderRoute route,
                                       HttpHeaders downstreamHeaders, String requestId,
                                       DownstreamLogView logView) {
        return messagesStream(request, route.model(), route.provider(), downstreamHeaders, requestId, logView);
    }

    // ==================== 非流式 ====================

    /**
     * 发送一次非流式 Messages 请求。
     *
     * <h2>接线顺序的关键约束</h2>
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
     * 这条约束是 OpenAI 侧用一个真实缺陷换来的，此处必须同样成立。
     */
    protected Mono<String> messages(Map<String, Object> request, String model,
                                    ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                    String requestId) {
        return messages(request, model, provider, downstreamHeaders, requestId, DIRECT_VIEW);
    }

    protected Mono<String> messages(Map<String, Object> request, String model,
                                    ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                    String requestId, DownstreamLogView logView) {
        Map<String, Object> requestBody = prepareRequestBody(request, false, model, provider);
        log.info("{} Anthropic 上游，模型: {}, 流式: false", provider.providerKey(), requestBody.get("model"));

        String providerKey = provider.providerKey();
        String modelName = (String) requestBody.get("model");
        Map<String, String> reqHeaders = new LinkedHashMap<>();
        AtomicLong attemptStart = new AtomicLong(System.currentTimeMillis());

        return Mono.defer(() -> {
                    attemptStart.set(System.currentTimeMillis());
                    return buildWebClient(reqHeaders, provider, downstreamHeaders, false)
                            .post().uri(messagesUri()).bodyValue(requestBody).retrieve()
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
                            entity.getStatusCode().value(), entity.getBody(), attemptStart.get(), logView);
                    // ttfb 传 null：非流式没有首字概念，与 OpenAI 侧一致。
                    saveUsage(logId, providerKey, modelName, false,
                            AnthropicUsageParser.extractUsageRawJson(objectMapper, entity.getBody()),
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
                                responseException.getResponseBodyAsString(), attemptStart.get(), logView);
                        publishCallRecorded();
                    } else {
                        // 状态码 -1：非 HTTP 异常的占位值，与 OpenAI 侧同一约定。
                        saveNonStreamLog(providerKey, modelName, reqHeaders, requestBody, Map.of(), -1, null,
                                attemptStart.get(), logView);
                        publishCallRecorded();
                    }
                })
                // 空响应兜底：转成异常以复用下方同一条 retryWhen 的预算。
                .flatMap(entity -> {
                    String body = entity.getBody();
                    if (AnthropicContentDetector.hasMeaningfulPayload(objectMapper, body)) {
                        return Mono.just(entity);
                    }
                    log.warn("{} 上游空响应（无正文/思考链/工具调用），body 长度 {}，将按重试预算重发 [{}] {}",
                            providerKey, body == null ? 0 : body.length(), model, requestId);
                    return Mono.error(new EmptyUpstreamResponseException(
                            body == null ? List.of() : List.of(body)));
                })
                .retryWhen(buildRetrySpec("messages", provider, requestId, modelName, false))
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
     * 发送一次流式 Messages 请求。
     *
     * <h2>与 OpenAI 流式的结构差异</h2>
     * 不设 gate 的缓存-释放机制。OpenAI 侧需要它是因为要在「确认非空」之前扣住帧不下发，
     * 而 Anthropic 的事件流<strong>必须按序完整下发</strong> —— 下游客户端是状态机，
     * 扣住 {@code message_start} 会让它无法初始化。
     *
     * <p>因此改为「边下发边记录是否见过实质载荷」，整轮结束后若一次都没见过就抛异常触发重试。
     * 代价是空响应那一轮的事件已经流到下游了；但那正是耗尽后本来也要做的事
     * （透传上游真实返回），而重发的新一轮会追加在后面 —— 对 Anthropic 客户端而言，
     * 一个未收到 {@code message_stop} 的消息序列后接新序列，是可判别的。
     *
     * <p>手动重试（{@code CallRetryRegistry} + {@code takeUntilOther}）本阶段不接：
     * 它需要在流中途切断并重发，而 Anthropic 客户端对「序列被截断后重新开始」的容忍度
     * 尚未验证。留到翻译层阶段一并处理。
     */
    protected Flux<String> messagesStream(Map<String, Object> request, String model,
                                          ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                          String requestId) {
        return messagesStream(request, model, provider, downstreamHeaders, requestId, DIRECT_VIEW);
    }

    protected Flux<String> messagesStream(Map<String, Object> request, String model,
                                          ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                          String requestId, DownstreamLogView logView) {
        Map<String, Object> requestBody = prepareRequestBody(request, true, model, provider);
        log.info("{} Anthropic 上游，模型: {}, 流式: true", provider.providerKey(), requestBody.get("model"));

        String providerKey = provider.providerKey();
        String modelName = (String) requestBody.get("model");
        Map<String, String> reqHeaders = new LinkedHashMap<>();
        List<String> logChunks = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<Map<String, String>> capturedRespHeaders = new AtomicReference<>(Map.of());
        AtomicReference<Integer> capturedStatusCode = new AtomicReference<>(0);
        AtomicLong attemptStart = new AtomicLong(System.currentTimeMillis());
        AtomicLong ttfbMs = new AtomicLong(-1);
        // 本轮累积的 usage：input_tokens 来自 message_start、output_tokens 来自 message_delta，
        // 必须跨事件合并才完整。
        AtomicReference<UsageTokens> usageAccumulator = new AtomicReference<>(UsageTokens.EMPTY);
        // 存档用的 usage 原文。取「信息量最大」的那一份而非最后一份，理由见
        // pickRicherUsageRaw —— 有的上游每个事件都带 usage，且尾事件全零。
        AtomicReference<String> archivedUsageRaw = new AtomicReference<>(null);
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
                    usageAccumulator.set(UsageTokens.EMPTY);
                    archivedUsageRaw.set(null);
                    sawPayload.set(false);
                    return buildWebClient(reqHeaders, provider, downstreamHeaders, true)
                            .post().uri(messagesUri()).bodyValue(requestBody)
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
                                                attemptStart.get(), logView);
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
                    if (!sawPayload.get() && AnthropicContentDetector.eventHasPayload(objectMapper, data)) {
                        sawPayload.set(true);
                    }
                    // usage 跨事件合并：message_start 给输入、message_delta 给输出。
                    String rawUsage = AnthropicUsageParser.extractUsageRawJson(objectMapper, data);
                    if (rawUsage != null) {
                        archivedUsageRaw.set(pickRicherUsageRaw(archivedUsageRaw.get(), rawUsage));
                        usageAccumulator.set(AnthropicUsageParser.merge(usageAccumulator.get(),
                                AnthropicUsageParser.parseUsageObject(objectMapper, rawUsage)));
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
                                emptyResponse.bufferedFrames(), attemptStart.get(), logView);
                        publishCallRecorded();
                        return;
                    }
                    if (findWebResponseException(e) == null) {
                        int statusCode = capturedStatusCode.get() == 0 ? -1 : capturedStatusCode.get();
                        saveStreamLog(providerKey, modelName, reqHeaders, requestBody,
                                capturedRespHeaders.get(), statusCode, List.copyOf(logChunks),
                                attemptStart.get(), logView);
                        publishCallRecorded();
                    }
                })
                .retryWhen(buildRetrySpec("messagesStream", provider, requestId, model, true))
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

        return attempt
                // 成功往返收尾：仅在非错误终结时落一条成功记录。
                .doFinally(signal -> {
                    if (emptyResponsePassthrough.get()) {
                        return;
                    }
                    if (signal != SignalType.ON_ERROR) {
                        int statusCode = capturedStatusCode.get();
                        if (statusCode == 0 && logChunks.isEmpty()) {
                            statusCode = -1;
                        }
                        Long logId = saveStreamLog(providerKey, modelName, reqHeaders, requestBody,
                                capturedRespHeaders.get(), statusCode, logChunks, attemptStart.get(), logView);
                        long ttfb = ttfbMs.get();
                        saveUsage(logId, providerKey, modelName, true, archivedUsageRaw.get(),
                                ttfb < 0 ? null : (int) ttfb, usageAccumulator.get());
                    }
                });
    }

    // ==================== 请求构造 ====================

    /**
     * Messages 端点路径。
     *
     * <p>当前全局规则是<strong>保留</strong>数据库 Base URL 自带的路径，
     * 再接 {@code /messages}：已有供应商的 Base URL 通常是 {@code .../v1}，
     * 因而实际请求为 {@code .../v1/messages}。这与 Anthropic 官方及 tokenrhythm
     * 的实测端点一致。
     *
     * @see #normalizeAnthropicBaseUrl
     */
    private String messagesUri() {
        return "/messages";
    }

    /**
     * 归一化 Anthropic 上游 Base URL，但<strong>不裁切路径</strong>。
     *
     * <p>原先的乐观规则会把尾部 {@code /v1} 剥掉（{@code .../v1 → ...}），再接
     * {@code /messages}；对 tokenrhythm 实测得到站点根路径的 405，而其 Anthropic
     * 端点实际是 {@code POST /v1/messages}。因此规则改为完整保留数据库中的路径，
     * 只做已有的尾斜杠归一化。
     *
     * <h2>V8.8 起地址来源可由用户指定</h2>
     * 取值来自 {@link ProviderRuntimeConfiguration#resolveAnthropicBaseUrl()}：优先用
     * {@code provider_config.anthropic_base_url}，未配置时回退到 {@code base_url}
     * （即 V8.8 之前的行为）。
     *
     * <p>之所以要独立成列而不是继续从 OpenAI 地址推导：中转站把 Anthropic 端点摆在哪里
     * 是不可预测的 —— 有的在 {@code /v1/messages}，有的在根路径，有的换了子域名。
     * 任何全局推导规则都只是对某一批供应商成立，遇到不符合的就是「配了却调不通」，
     * 而用户从界面上看不出代码在背后做了什么拼接，无从排查。给出一列让他显式声明，
     * 猜错的可能性归零。
     */
    private String normalizeAnthropicBaseUrl(ProviderRuntimeConfiguration provider) {
        return providerRequestHeaderService.normalizeBaseUrl(provider.resolveAnthropicBaseUrl());
    }

    /**
     * 构建 WebClient 并抓取出站请求头快照。
     *
     * <p>结构与 OpenAI 侧同形（两级抓取：WebClient 过滤器记录规则头，
     * Reactor Netty 的 {@code doAfterRequest} 再用传输层快照覆盖，
     * 因此日志里能看到 User-Agent、Host 等底层补入的头）。
     * 刻意不抽公共方法：它依赖三个注入字段，抽出去要传三个参数或再造一个 Bean，
     * 而本身只有二十行。
     *
     * <p>Anthropic 特有的两点：必须带 {@code anthropic-version} 头；
     * 认证头是 {@code x-api-key} 而非 {@code Authorization: Bearer}（见下）。
     */
    private WebClient buildWebClient(Map<String, String> capturedHeaders,
                                     ProviderRuntimeConfiguration provider,
                                     HttpHeaders downstreamHeaders, boolean stream) {
        String apiKey = provider.apiKey();
        String normalizedUrl = normalizeAnthropicBaseUrl(provider);
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
                    // 复用共享的请求头装配（下游头透传白名单、hop-by-hop 排除、
                    // 供应商头规则含 {apiKey} 占位与删除标记）——
                    // 这些与协议无关，两侧必须同口径。
                    providerRequestHeaderService.applyHeaders(
                            headers, downstreamHeaders, apiKey, provider.headerRulesJson(), stream);
                    // Anthropic 必需的版本头。放在 applyHeaders 之后，
                    // 使供应商头规则仍可覆盖它（某些中转站要求特定版本）。
                    if (!headers.containsKey(ANTHROPIC_VERSION_HEADER)) {
                        headers.set(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION_VALUE);
                    }
                    // TODO 认证头形态尚未实测确认。Anthropic 官方用 x-api-key，
                    //  而多数 OpenAI 兼容中转站转发 Anthropic 时沿用 Authorization: Bearer。
                    //  此处同时给两个：applyHeaders 已设好 Bearer，这里补一个 x-api-key，
                    //  让两类上游都能通过。待实测后收敛为单一形态，或做成可配置。
                    //  同时给两个的风险是某些严格的上游会因为多余的头而拒绝 —— 若遇到，
                    //  优先怀疑这里。
                    if (!headers.containsKey("x-api-key") && apiKey != null && !apiKey.isBlank()) {
                        headers.set("x-api-key", apiKey);
                    }
                })
                .filter((request, next) -> {
                    capturedHeaders.clear();
                    capturedHeaders.putAll(providerRequestHeaderService.createLogSnapshot(request.headers()));
                    return next.exchange(request);
                }).build();
    }

    /**
     * 准备 Anthropic 请求体。
     *
     * <h2>三处与 OpenAI 的硬差异</h2>
     * <ol>
     *   <li><strong>{@code system} 是顶层字段</strong> —— OpenAI 把它作为
     *       {@code messages} 里 {@code role: system} 的一条，Anthropic 不接受那种形态，
     *       必须提取出来。多条 system 消息按顺序拼接。</li>
     *   <li><strong>{@code max_tokens} 必填</strong> —— 缺失时上游返回 400。
     *       按模型配置的 {@code max_output_tokens} 注入（覆写 / 兜底两档），
     *       模型未配置时用 {@link MaxOutputTokensSetting#defaults()}。</li>
     *   <li><strong>思考方式用 {@code thinking} 对象</strong> ——
     *       按模型配置的 {@code thinking_mode} 与 {@code thinking_budget_tokens} 注入
     *       （覆写 / 兜底 / 透传三档），模型未配置时用
     *       {@link AnthropicThinkingSetting#defaults()}。
     *       <p>思考<strong>深度</strong>是另一维，写成顶层
     *       {@code output_config.effort}（而非 OpenAI 的 {@code reasoning_effort}），
     *       四档注入模式与 OpenAI 侧同一套，见
     *       {@link ReasoningEffortSetting#applyToAnthropic}。</li>
     * </ol>
     *
     * <h2>两个思考维度的施加顺序不可交换</h2>
     * <strong>深度先、方式后</strong>，且深度写了
     * {@code thinking:{"type":"disabled"}} 时跳过方式。两者都会写 {@code thinking}，
     * 而它们对那个字段的权限不对等：深度只在 {@code off} 档动它（五档里没有
     * 「不思考」，只能借这个字段表达），方式则把它当作自己的主场。
     *
     * <p>先方式后深度会让深度的兜底档把方式刚写的 {@code thinking} 误认为
     * 「下游已表态」，于是自己不再注入 —— 一个下游从未发过的字段反而封住了
     * 用户配的档位。不跳过方式则相反：方式的覆写档会把 {@code disabled} 改写成
     * {@code adaptive}，用户配的「关闭思考」被静默丢弃。前端的
     * {@code anthropicThinkingLockedByEffort} 置灰就是同一条规则的界面表达。
     *
     * <h2>请求体转换规则的执行位置</h2>
     * 规则在协议归一化<strong>之后</strong>执行（{@code system} 已提到顶层、
     * {@code max_tokens} 已补齐），因为规则的字段路径是照最终发往上游的形态写的 ——
     * 若在归一化前执行，用户看到的预览与实际请求体结构不一致。
     *
     * <p>但要在 {@code removeIf(Objects::isNull)} 之前：规则可能把某个字段显式设为 null，
     * 而 Anthropic 对多余的 null 字段并不宽容，最终清洗必须是链条的最后一步。
     *
     * <p>协议筛选由引擎完成：只有声明适用 {@link WireProtocol#ANTHROPIC} 的规则组才会执行。
     * 库里那些照 OpenAI 结构写的旧规则被归一为「仅 OPENAI」，因此不会在此静默匹配失败。
     */
    private Map<String, Object> prepareRequestBody(Map<String, Object> request, boolean stream,
                                                   String model, ProviderRuntimeConfiguration provider) {
        Map<String, Object> body = new LinkedHashMap<>(request);
        String resolvedModel = resolveModel(body.get("model"), model);
        body.put("model", resolvedModel);
        body.put("stream", stream);

        extractSystemPrompt(body);
        ensureMaxTokens(body, resolvedModel, provider);

        // 思考两维：深度先、方式后，且 off 档写了 disabled 时跳过方式。
        // 顺序与跳过的理由见本方法的 javadoc。
        boolean thinkingDisabled = resolveReasoningEffort(resolvedModel, provider).applyToAnthropic(body);
        if (!thinkingDisabled) {
            resolveThinking(resolvedModel, provider).applyTo(body);
        }

        // reasoning_effort 是 OpenAI 的字段名；O2A 翻译器把它映射到 output_config.effort
        // 后刻意保留了一份兼容副本，供上面两个设置层判定「下游已表态」。
        // 因此这一行必须在设置层之后：提前剥会让兜底档把一个已表态的请求当成未表态，
        // 静默退化成覆写档。
        body.remove("reasoning_effort");

        applyBodyRules(body, provider);

        body.values().removeIf(Objects::isNull);
        return body;
    }

    /**
     * 从运行时模型配置中读取思考方式设置。
     *
     * <p>找不到匹配的模型时返回 {@link AnthropicThinkingSetting#defaults()}
     * （adaptive + 兜底 + 未设置预算），与 {@link #resolveMaxOutputTokens} 同一形状。
     * 这个默认值<strong>就是</strong> V10 之前那段硬编码的行为，因此升级前后的
     * 出站请求体完全一致。
     */
    private AnthropicThinkingSetting resolveThinking(String resolvedModel,
                                                    ProviderRuntimeConfiguration provider) {
        for (var model : provider.models()) {
            if (resolvedModel.equals(model.modelName())) {
                return AnthropicThinkingSetting.parse(
                        model.thinkingMode(), model.thinkingBudgetTokens(), objectMapper);
            }
        }
        return AnthropicThinkingSetting.defaults();
    }

    /**
     * 从运行时模型配置中读取思考深度设置。
     *
     * <p>与 OpenAI 侧读的是<strong>同一列</strong>（{@code provider_model.reasoning_effort}）、
     * 同一份解析与同一套四档语义，只有出站的字段名与形态不同。因此此处不引入
     * 第二份配置 —— 用户在界面上看到的就是一个模型一个档位，无论它走哪条线路。
     *
     * <p>模型名查不到时用 {@link ReasoningEffortSetting#defaults()}（medium + 兜底），
     * 与 OpenAI 侧 {@code resolveReasoningEffort} 同一形状。
     */
    private ReasoningEffortSetting resolveReasoningEffort(String resolvedModel,
                                                        ProviderRuntimeConfiguration provider) {
        for (var model : provider.models()) {
            if (resolvedModel.equals(model.modelName())) {
                return ReasoningEffortSetting.parse(model.reasoningEffort(), objectMapper);
            }
        }
        return ReasoningEffortSetting.defaults();
    }

    /**
     * 执行适用于 Anthropic 线路的请求体规则组。
     *
     * <p>引擎返回新 Map 而非原地修改，这里原地替换内容以保留调用方持有的引用。
     */
    private void applyBodyRules(Map<String, Object> body, ProviderRuntimeConfiguration provider) {
        RequestBodyRuleEngine.TransformResult result = requestBodyRuleEngine.transform(
                body, provider.bodyRulesJson(), WireProtocol.ANTHROPIC);
        body.clear();
        body.putAll(result.output());
        for (RequestBodyRuleEngine.TransformWarning warning : result.warnings()) {
            log.warn("[Anthropic] 请求体规则已跳过: ruleId={}, path={}, message={}",
                    warning.ruleId(), warning.fieldPath(), warning.message());
        }
    }

    /**
     * 把 {@code messages} 里的 system 消息提取到顶层 {@code system} 字段。
     *
     * <p>若请求已带顶层 {@code system}，则保留它并把 messages 里的追加在后面 ——
     * 下游可能两种形态都用了，丢掉任何一份都会改变语义。
     */
    @SuppressWarnings("unchecked")
    private void extractSystemPrompt(Map<String, Object> body) {
        if (!(body.get("messages") instanceof List<?> rawMessages)) {
            return;
        }
        StringBuilder systemText = new StringBuilder();
        if (body.get("system") instanceof String existing && !existing.isBlank()) {
            systemText.append(existing);
        }
        List<Object> kept = new java.util.ArrayList<>();
        for (Object item : rawMessages) {
            if (item instanceof Map<?, ?> raw && "system".equals(raw.get("role"))) {
                String text = stringifyContent(((Map<String, Object>) raw).get("content"));
                if (text != null && !text.isBlank()) {
                    if (!systemText.isEmpty()) {
                        systemText.append("\n\n");
                    }
                    systemText.append(text);
                }
                continue;
            }
            kept.add(item);
        }
        body.put("messages", kept);
        if (!systemText.isEmpty()) {
            body.put("system", systemText.toString());
        }
    }

    /**
     * 把 message 的 content 转成纯文本。
     *
     * <p>content 可能是字符串，也可能是 OpenAI 多模态那种
     * {@code [{"type":"text","text":"..."}]} 数组 —— 后者取出所有 text 片段拼接。
     * 非文本片段（图片等）在 system 提示词里没有意义，忽略。
     */
    private String stringifyContent(Object content) {
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> parts) {
            StringBuilder builder = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map && "text".equals(map.get("type"))
                        && map.get("text") instanceof String text) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            }
            return builder.toString();
        }
        return null;
    }

    /**
     * 按模型配置的注入模式落定 {@code max_tokens}。
     *
     * <h2>为何这一步不能省</h2>
     * Anthropic 把 {@code max_tokens} 列为<strong>必填</strong>，缺失时上游直接 400。
     * 而 OpenAI 侧它是可选的，Copilot 之类的下游通常不带 —— 于是必须在这里补齐。
     *
     * <h2>三个步骤的顺序有讲究</h2>
     * <ol>
     *   <li><strong>别名归一化</strong>：下游可能用 OpenAI 的 {@code max_completion_tokens}。
     *       必须在注入之前搬到正名上，否则 {@code OVERRIDE} 模式写好 {@code max_tokens} 后，
     *       那个别名字段仍会留在请求体里一起发给上游。</li>
     *   <li><strong>清掉非法值</strong>：{@code 0}、负数、非数字都视为「没带」。
     *       {@link MaxOutputTokensSetting#applyTo} 的兜底档只看 {@code containsKey}，
     *       留着一个 {@code "max_tokens": 0} 会让它认为下游表达过意见而放行 —— 然后上游 400。</li>
     *   <li><strong>按模式注入</strong>：交给 {@code applyTo}，覆写档无条件写、兜底档只补缺。</li>
     * </ol>
     *
     * <h2>兜底档在这条线路上的实际作用</h2>
     * Anthropic 客户端直连时几乎总会自带 {@code max_tokens}（协议必填），因此兜底档很少触发；
     * 真正有用的是<strong>覆写档</strong> —— 它能把下游请求的上限统一压到这里配置的值。
     * 但兜底档仍不能省：跨协议来的请求（OpenAI 形态的下游打到 Anthropic 供应商）就是靠它补齐的。
     */
    private void ensureMaxTokens(Map<String, Object> body, String resolvedModel,
                                 ProviderRuntimeConfiguration provider) {
        normalizeMaxTokensAlias(body);
        resolveMaxOutputTokens(resolvedModel, provider).applyTo(body);
    }

    /**
     * 把 OpenAI 的 {@code max_completion_tokens} 搬到 Anthropic 的正名上，并清掉非法值。
     *
     * <p>别名无论合法与否都会被移除：它不是 Anthropic 协议的字段，留着只会让上游困惑。
     */
    private void normalizeMaxTokensAlias(Map<String, Object> body) {
        Object alias = body.remove("max_completion_tokens");
        if (!(body.get("max_tokens") instanceof Number existing) || existing.intValue() <= 0) {
            body.remove("max_tokens");
            if (alias instanceof Number aliasValue && aliasValue.intValue() > 0) {
                body.put("max_tokens", aliasValue.intValue());
            }
        }
    }

    /**
     * 从运行时模型配置中读取最大输出设置。
     *
     * <p>找不到匹配的模型时返回 {@link MaxOutputTokensSetting#defaults()}（4K + 兜底），
     * 与 OpenAI 侧 {@code resolveReasoningEffort} 同一形状。这里的兜底比思考深度那个安全得多 ——
     * 给一个未配置的模型注入 {@code max_tokens} 不会改变语义，而缺了它这条线路根本发不出去。
     *
     * <p>线性查找而非建 Map：模型数量是个位到几十的量级，且这个方法每轮请求只调一次。
     */
    private MaxOutputTokensSetting resolveMaxOutputTokens(String resolvedModel,
                                                         ProviderRuntimeConfiguration provider) {
        for (var model : provider.models()) {
            if (resolvedModel.equals(model.modelName())) {
                return MaxOutputTokensSetting.parse(model.maxOutputTokens(), objectMapper);
            }
        }
        return MaxOutputTokensSetting.defaults();
    }

    /*
     * ========================================================================
     * 思考**深度**在 Anthropic 线路上的形态选择：已落地的决定与遗留代价
     * ========================================================================
     *
     * 深度与思考**方式**（AnthropicThinkingSetting，见 resolveThinking）是两个正交维度：
     * 方式管「预算怎么算」（adaptive / enabled+budget），深度管「想多深」（档位字符串）。
     * 两者都已接入持久化并生效 —— 方式自 V10、深度走 ReasoningEffortSetting.applyToAnthropic。
     *
     * ## 出站形态：output_config.effort
     *
     * 三个候选：
     *
     *   output_config:{"effort":"..."}                   —— 顶层字段，4.6+，五档
     *   thinking:{"type":"enabled","budget_tokens":N}    —— 4.6 弃用，4.7+ 直接 400
     *   thinking:{"type":"disabled"}                     —— 只能表达「不思考」
     *
     * 选了第一个。第二个要求把档位换算成 token 预算，而请求侧契约第 4.4 节已据「三个参考
     * 项目的换算表互不相同、反向阈值也互不相同」决定不做这个换算 —— 换算被排除后，
     * output_config.effort 是唯一自洽的落点。第三个只覆盖 off 档，因此它只作为 off 档的
     * 出站形态，见 writeConfiguredAnthropicEffort。
     *
     * ## 遗留代价：4.5 及更早的模型不认识 output_config
     *
     * 那些模型会以错误码回答。这**不修**，与「不做自动降级、不按模型名猜能力」一致：
     *
     *   new-api（relaykit/relayconvert/reasoning/claude.go）按模型名前缀硬编码八个能力
     *   维度来选形态，且内含逐级降级（xhigh 不支持就退 max 再退 high）。中转站会改模型名，
     *   前缀匹配大面积失效；而本服务的原则是发出用户配置的东西。
     *
     * 若将来要支持老模型，正确做法是让形态选择**可配置**（参考 cc-switch 的
     * thinkingLevelMap：字符串=实际发送值 / null=该档明确不可用 / 键缺失=用上游默认），
     * 而不是从模型名推导。那需要一列新的模型配置，属于后续版本。
     *
     * ## 不要顺手做的事
     *
     * new-api 在思考开启时会清掉采样参数（temperature/top_p/top_k），因为
     * Anthropic 对此有硬约束。那是它的选择；本服务若要做，也应当是显式配置项，
     * 而不是在翻译过程里静默改写用户的请求。
     */

    /** 剥离供应商前缀，取真实上游模型名。与 OpenAI 侧同一工具。 */
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

    // ==================== 重试（策略与 OpenAI 侧同源） ====================

    /**
     * 构建重试策略。
     *
     * <p>次数来自 {@link RetryPolicyService} 的 {@code retry_max_attempts} ——
     * <strong>与 OpenAI 侧同一个配置项</strong>，管理后台改一次两个协议同时生效。
     * 这是「重试次数只有一个来源」这条约束在跨协议后的延续：实现各写一份，
     * 但配置来源不分叉。
     */
    private Retry buildRetrySpec(String method, ProviderRuntimeConfiguration provider,
                                 String requestId, String model, boolean stream) {
        int configured = retryPolicyService != null
                ? retryPolicyService.getMaxAttempts()
                : RetryPolicyService.DEFAULT_MAX_ATTEMPTS;
        long maxAttempts = RetryPolicyService.toReactorMaxAttempts(configured);
        boolean unlimited = configured == RetryPolicyService.UNLIMITED_MAX_ATTEMPTS;
        return Retry.backoff(maxAttempts, retryFirstBackoff()).maxBackoff(retryMaxBackoff())
                .filter(GenericAnthropicChatService::isRetryableFailure)
                .doBeforeRetry(signal -> {
                    int attempt = (int) (signal.totalRetries() + 1);
                    publishLifecycle(CallLifecycleEvent.retrying(requestId, model, stream, attempt));
                    log.warn("[{}] {} Anthropic 调用失败，重试第 {}/{} 次: {}", method, provider.providerKey(),
                            attempt, unlimited ? -1 : maxAttempts, signal.failure().getMessage());
                });
    }

    /** 首次退避时长。可覆盖以便测试压缩等待，理由同 OpenAI 侧。 */
    protected Duration retryFirstBackoff() {
        return Duration.ofSeconds(2);
    }

    /** 退避上限。可覆盖以便测试压缩等待。 */
    protected Duration retryMaxBackoff() {
        return Duration.ofSeconds(30);
    }

    /**
     * 可重试判定 —— 与 OpenAI 侧同一口径（四类可恢复失败）。
     *
     * <p>这段逻辑纯粹基于异常类型与 HTTP 状态码，本身与协议无关。
     * 本阶段按保守方式复制一份而不抽公共工具：抽取要改动刚验证过的 OpenAI 链路，
     * 而两边都稳定之后再合并的成本更低。若此处与 OpenAI 侧出现口径差异，
     * 那才是真正该抽取的信号。
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
     * 落库。
     *
     * <h2>上游协议恒为 ANTHROPIC，下游协议由视图给出</h2>
     * 直连时两者相同；翻译路线下游是 OPENAI，因此日志里能看出
     * 这是一次跳协议调用。
     */
    private Long saveNonStreamLog(String providerKey, String modelName, Map<String, String> reqHeaders,
                                  Map<String, Object> requestBody, Map<String, String> respHeaders,
                                  int statusCode, String responseBody, long startTime,
                                  DownstreamLogView logView) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveNonStream(providerKey, modelName,
                logView.downstreamProtocol(), WireProtocol.ANTHROPIC.name(),
                reqHeaders, requestBody, respHeaders,
                statusCode, responseBody, duration);
    }

    /**
     * 流式落库。
     *
     * <p>chunk 过一道视图改写：翻译路线下日志要记<strong>下游实际收到的</strong>
     * OpenAI chunk，而不是上游的 Anthropic 事件 —— 否则排查「客户端为何解析失败」
     * 时，日志里没有客户端真正看到的东西。
     */
    private Long saveStreamLog(String providerKey, String modelName, Map<String, String> reqHeaders,
                               Map<String, Object> requestBody, Map<String, String> respHeaders,
                               int statusCode, List<String> chunks, long startTime,
                               DownstreamLogView logView) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveStream(providerKey, modelName,
                logView.downstreamProtocol(), WireProtocol.ANTHROPIC.name(),
                reqHeaders, requestBody, respHeaders,
                statusCode, logView.viewChunks(chunks), duration);
    }

    private Long saveStreamLogWithError(String providerKey, String modelName, Map<String, String> reqHeaders,
                                        Map<String, Object> requestBody, Map<String, String> respHeaders,
                                        int statusCode, List<String> chunks, Map<String, String> errorHeaders,
                                        int errorCode, String errorBody, long startTime,
                                        DownstreamLogView logView) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveStreamWithError(providerKey, modelName,
                logView.downstreamProtocol(), WireProtocol.ANTHROPIC.name(),
                reqHeaders, requestBody, respHeaders, statusCode,
                logView.viewChunks(chunks), errorHeaders,
                errorCode, errorBody, duration);
    }

    /** 非流式用量写入：从原始 usage JSON 解析。 */
    private void saveUsage(Long logId, String providerKey, String modelName, boolean stream,
                           String usageRaw, Integer ttfbMs) {
        saveUsage(logId, providerKey, modelName, stream, usageRaw, ttfbMs,
                AnthropicUsageParser.parseUsageObject(objectMapper, usageRaw));
    }

    /**
     * 用量写入。
     *
     * <p>流式传入已跨事件合并好的 {@code tokens}，非流式由上面的重载解析单份 usage。
     * 分成两个入口是因为流式的 {@code input_tokens} 与 {@code output_tokens}
     * 来自不同事件，只解析最后一份会丢掉输入 token。
     *
     * <h2>三个 token 列过 {@code logView}，{@code usage_raw} 不过</h2>
     * 两者是两种数据：{@code usage_raw} 是<strong>上游原始报文</strong>的存档，
     * 改写它等于销毁证据；而三个 token 列是<strong>跨协议共用的归一化度量</strong>，
     * 前端与概览页求和都按下游协议解读它们。因此同一行里同时留着上游原文与
     * 下游口径的指标是有意的 —— 详见 {@link DownstreamLogView#viewUsage}。
     *
     * <p>{@code publishCallRecorded()} 放在 finally：无论用量是否实际写入，
     * 落库流程走完即宣告记录就绪。与 OpenAI 侧同一语义 —— 失败调用与上游未返回 usage
     * 的调用本就不写用量行，若按「两张表都写了」判定，这些记录永远不会实时出现在前端。
     */
    private void saveUsage(Long logId, String providerKey, String modelName, boolean stream,
                           String usageRaw, Integer ttfbMs, UsageTokens tokens) {
        try {
            if (apiCallUsage == null) return;
            if (usageRaw == null) return;
            apiCallUsage.save(logId, providerKey, modelName, stream, usageRaw, tokens, ttfbMs);
        } finally {
            publishCallRecorded();
        }
    }

    /**
     * 在两份 usage 原文里挑「信息量更大」的那一份存档。
     *
     * <h2>为何不能直接取最后一份</h2>
     * 存在这样的上游：<strong>每一个</strong>流式事件都带完整的 usage 对象，但只有少数几个
     * 带真实数字，其余（含最后的 {@code message_stop}）全是 {@code 0}。直接取最后一份会让
     * {@code usage_raw} 存下一份全零报文 —— 实测到过的缺陷。
     *
     * <p>判据取<strong>四个输入输出字段的正值个数</strong>，相等时保留先到的那一份
     * （更靠前的事件通常字段更全，例如 {@code message_start} 带 {@code cache_*}）。
     * 不比较数值大小：那会在多轮 {@code message_delta} 里挑出「输出最多」的一帧，
     * 而我们要的是「哪一帧最能说明这次调用」。
     *
     * <h2>它与三个 token 列的关系</h2>
     * 两者独立：三列由 {@link AnthropicUsageParser#merge} 跨事件合并而来，本方法只决定
     * <strong>存档哪一份原文</strong>。因此即使这里挑错，三列仍然正确 ——
     * 但存档是查证的唯一依据，挑错等于让证据失效。
     *
     * @param current 已存档的原文；{@code null} 表示还没有
     * @param incoming 新到的原文，非 null
     * @return 应当存档的那一份
     */
    private String pickRicherUsageRaw(String current, String incoming) {
        if (current == null) {
            return incoming;
        }
        return countPositiveUsageFields(incoming) > countPositiveUsageFields(current)
                ? incoming : current;
    }

    /** 数一份 usage 原文里有几个输入输出字段是正数。解析失败记 0（保守，不覆盖已有存档）。 */
    private int countPositiveUsageFields(String usageRaw) {
        try {
            JsonNode usage = objectMapper.readTree(usageRaw);
            int positives = 0;
            for (String field : List.of("input_tokens", "output_tokens",
                    "cache_read_input_tokens", "cache_creation_input_tokens")) {
                JsonNode value = usage.get(field);
                if (value != null && value.isNumber() && value.asLong() > 0) {
                    positives++;
                }
            }
            return positives;
        } catch (Exception exception) {
            return 0;
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

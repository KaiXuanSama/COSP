package com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
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
 * 思考深度用 {@code thinking} 对象而非 {@code reasoning_effort} 字符串。
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

    /**
     * {@code max_tokens} 缺失时的兜底值。
     *
     * <p>Anthropic 要求该字段必填，而 OpenAI 侧它是可选的 —— 下游若不带，
     * 直接转发会被上游拒绝。优先取模型配置的 {@code max_output_tokens}，
     * 都拿不到才用此值。
     */
    private static final int FALLBACK_MAX_TOKENS = 4096;

    private final ObjectMapper objectMapper;
    private final ProviderRequestHeaderService providerRequestHeaderService;

    private ApiCallLogService apiCallLog;
    private ApiCallUsageService apiCallUsage;
    private CallLifecycleNotifier lifecycleNotifier;
    private RetryPolicyService retryPolicyService;
    private WebClient.Builder webClientBuilder = WebClient.builder();
    private HttpClient httpClient = HttpClient.create();

    public GenericAnthropicChatService(ObjectMapper objectMapper,
                                       ProviderRequestHeaderService providerRequestHeaderService) {
        this.objectMapper = objectMapper;
        this.providerRequestHeaderService = providerRequestHeaderService;
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

    /** 非流式，接受应用层已解析的路由。 */
    public Mono<String> messages(Map<String, Object> request, ResolvedProviderRoute route,
                                 HttpHeaders downstreamHeaders, String requestId) {
        return messages(request, route.model(), route.provider(), downstreamHeaders, requestId);
    }

    /** 流式，接受应用层已解析的路由。 */
    public Flux<String> messagesStream(Map<String, Object> request, ResolvedProviderRoute route,
                                       HttpHeaders downstreamHeaders, String requestId) {
        return messagesStream(request, route.model(), route.provider(), downstreamHeaders, requestId);
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
                            entity.getStatusCode().value(), entity.getBody(), attemptStart.get());
                    // ttfb 传 null：非流式没有首字概念，与 OpenAI 侧一致。
                    saveUsage(logId, providerKey, modelName, false,
                            AnthropicUsageParser.extractUsageRawJson(objectMapper, entity.getBody()), null);
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
                        // 状态码 -1：非 HTTP 异常的占位值，与 OpenAI 侧同一约定。
                        saveNonStreamLog(providerKey, modelName, reqHeaders, requestBody, Map.of(), -1, null,
                                attemptStart.get());
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
        AtomicReference<String> lastUsageRaw = new AtomicReference<>(null);
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
                    lastUsageRaw.set(null);
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
                    if (!sawPayload.get() && AnthropicContentDetector.eventHasPayload(objectMapper, data)) {
                        sawPayload.set(true);
                    }
                    // usage 跨事件合并：message_start 给输入、message_delta 给输出。
                    String rawUsage = AnthropicUsageParser.extractUsageRawJson(objectMapper, data);
                    if (rawUsage != null) {
                        lastUsageRaw.set(rawUsage);
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
                                emptyResponse.bufferedFrames(), attemptStart.get());
                        publishCallRecorded();
                        return;
                    }
                    if (findWebResponseException(e) == null) {
                        int statusCode = capturedStatusCode.get() == 0 ? -1 : capturedStatusCode.get();
                        saveStreamLog(providerKey, modelName, reqHeaders, requestBody,
                                capturedRespHeaders.get(), statusCode, List.copyOf(logChunks), attemptStart.get());
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
                                capturedRespHeaders.get(), statusCode, logChunks, attemptStart.get());
                        long ttfb = ttfbMs.get();
                        saveUsage(logId, providerKey, modelName, true, lastUsageRaw.get(),
                                ttfb < 0 ? null : (int) ttfb, usageAccumulator.get());
                    }
                });
    }

    // ==================== 请求构造 ====================

    /**
     * Messages 端点路径。
     *
     * <p>数据库里存的 Base URL 目前都带 {@code /v1} 后缀（OpenAI 的惯例），
     * 而多数中转站的 Anthropic 端点是在<strong>根路径</strong>下暴露 {@code /messages}，
     * 故此处先按「Base URL 去掉 {@code /v1} 后直接接 {@code /messages}」处理。
     *
     * @see #normalizeAnthropicBaseUrl
     */
    private String messagesUri() {
        return "/messages";
    }

    /**
     * 把 OpenAI 惯例的 Base URL 调整成 Anthropic 端点的基址。
     *
     * <p>TODO 上游 Anthropic 端点是否带 {@code /v1} 前缀尚未实测确认，此处先剥掉尾部的
     *  {@code /v1}（形如 {@code https://agentrouter.org/v1} → {@code https://agentrouter.org}，
     *  再接 {@code /messages}）。这是多数中转站的做法，但<strong>Anthropic 官方是
     *  {@code /v1/messages}</strong>，两者并不一致。
     *  待验证各中转站的真实形态后，应在 {@code provider_config} 增加独立的 Anthropic
     *  Base URL 列（或端点路径列）由用户显式配置，而不是在代码里猜。
     *  在此之前：若某供应商的 Anthropic 端点确实需要 {@code /v1}，本方法会把它剥掉
     *  导致 404 —— 该失败有完整日志（请求 URL 可查），不会静默。
     */
    private String normalizeAnthropicBaseUrl(String baseUrl) {
        String normalized = providerRequestHeaderService.normalizeBaseUrl(baseUrl);
        if (normalized.endsWith("/v1")) {
            return normalized.substring(0, normalized.length() - "/v1".length());
        }
        return normalized;
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
        String normalizedUrl = normalizeAnthropicBaseUrl(provider.baseUrl());
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
     *       优先取模型配置的 {@code max_output_tokens}，再退到
     *       {@link #FALLBACK_MAX_TOKENS}。</li>
     *   <li><strong>思考深度用 {@code thinking} 对象</strong> ——
     *       而非 OpenAI 的 {@code reasoning_effort} 字符串。本阶段只做剥离，
     *       不做映射（见下方 TODO）。</li>
     * </ol>
     *
     * <p>请求体转换规则（{@code provider_request_transform.body_rules_json}）
     * <strong>本阶段不应用</strong>：那些规则是照 OpenAI 请求体的路径写的
     * （如 {@code ./messages[*]/content}），作用在 Anthropic 请求体上多数匹配不到，
     * 静默失效比报错更难排查。见下方 TODO。
     */
    private Map<String, Object> prepareRequestBody(Map<String, Object> request, boolean stream,
                                                   String model, ProviderRuntimeConfiguration provider) {
        Map<String, Object> body = new LinkedHashMap<>(request);
        String resolvedModel = resolveModel(body.get("model"), model);
        body.put("model", resolvedModel);
        body.put("stream", stream);

        extractSystemPrompt(body);
        ensureMaxTokens(body, resolvedModel, provider);

        // TODO reasoning_effort → thinking 的映射尚未实现。Anthropic 用
        //  {"thinking": {"type": "enabled", "budget_tokens": N}} 而非字符串档位，
        //  且 budget_tokens 必须小于 max_tokens。本阶段先剥掉该字段避免上游 400，
        //  待确认各中转站是否支持 thinking 参数后再补映射。
        //  影响：配置了思考深度的模型，走 Anthropic 协议时该设置暂时不生效。
        body.remove("reasoning_effort");

        // TODO 请求体转换规则（body_rules_json）本阶段不应用于 Anthropic 请求。
        //  RequestBodyRuleEngine 本身是协议无关的纯 JSON 路径操作，能跑；
        //  但库里已有的规则都是照 OpenAI 请求体结构写的（system 在 messages 里、
        //  无 max_tokens 等），作用在 Anthropic 请求体上会静默匹配不到 —— 不报错，
        //  只是什么都没做，这比报错更难排查。
        //  待 provider_config 能区分协议后，规则也应按协议分开存储与编辑。

        body.values().removeIf(Objects::isNull);
        return body;
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
     * 确保 {@code max_tokens} 存在 —— Anthropic 要求必填，缺失时上游返回 400。
     *
     * <p>TODO 取不到模型配置的输出上限，只能用固定兜底值 {@link #FALLBACK_MAX_TOKENS}。
     *  {@code provider_model.max_output_tokens} 这一列在数据库里是有的（默认 128000），
     *  但没进 {@link ProviderRuntimeModel} 运行时快照（它只带 contextSize / caps / effort）。
     *  待往快照里补上该字段后，改为优先取模型自己的上限。
     *  影响：配置了较大输出上限的模型，走 Anthropic 协议时会被限制在 4096，
     *  长回复可能被截断（{@code stop_reason: "max_tokens"}）—— 这在日志里可见，不会静默。
     */
    @SuppressWarnings("unused") // resolvedModel / provider 留待上述 TODO 落地后用于查模型上限
    private void ensureMaxTokens(Map<String, Object> body, String resolvedModel,
                                 ProviderRuntimeConfiguration provider) {
        if (body.get("max_tokens") instanceof Number existing && existing.intValue() > 0) {
            return;
        }
        // OpenAI 侧的同义字段，下游可能用它。
        if (body.remove("max_completion_tokens") instanceof Number alias && alias.intValue() > 0) {
            body.put("max_tokens", alias.intValue());
            return;
        }
        body.put("max_tokens", FALLBACK_MAX_TOKENS);
    }

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

    private Long saveNonStreamLog(String providerKey, String modelName, Map<String, String> reqHeaders,
                                  Map<String, Object> requestBody, Map<String, String> respHeaders,
                                  int statusCode, String responseBody, long startTime) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveNonStream(providerKey, modelName, reqHeaders, requestBody, respHeaders,
                statusCode, responseBody, duration);
    }

    private Long saveStreamLog(String providerKey, String modelName, Map<String, String> reqHeaders,
                               Map<String, Object> requestBody, Map<String, String> respHeaders,
                               int statusCode, List<String> chunks, long startTime) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveStream(providerKey, modelName, reqHeaders, requestBody, respHeaders,
                statusCode, chunks, duration);
    }

    private Long saveStreamLogWithError(String providerKey, String modelName, Map<String, String> reqHeaders,
                                        Map<String, Object> requestBody, Map<String, String> respHeaders,
                                        int statusCode, List<String> chunks, Map<String, String> errorHeaders,
                                        int errorCode, String errorBody, long startTime) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveStreamWithError(providerKey, modelName, reqHeaders, requestBody,
                respHeaders, statusCode, chunks, errorHeaders, errorCode, errorBody, duration);
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
     * <p>{@code publishCallRecorded()} 放在 finally：无论用量是否实际写入，
     * 落库流程走完即宣告记录就绪。与 OpenAI 侧同一语义 —— 失败调用与上游未返回 usage
     * 的调用本就不写用量行，若按「两张表都写了」判定，这些记录永远不会实时出现在前端。
     */
    private void saveUsage(Long logId, String providerKey, String modelName, boolean stream,
                           String usageRaw, Integer ttfbMs, UsageTokens tokens) {
        try {
            if (apiCallUsage == null) return;
            if (usageRaw == null) return;
            apiCallUsage.save(logId, providerKey, modelName, stream, usageRaw,
                    tokens == null ? UsageTokens.EMPTY : tokens, ttfbMs);
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

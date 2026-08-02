package com.kaixuan.copilot_ollama_proxy.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil;
import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageParser;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用 OpenAI 上游执行管道。
 *
 * 本类负责上游响应清洗、请求转换、重试和调用日志。
 *
 * 上游清洗（{@link #normalizeUpstreamChunk}）：
 *   将各上游供应商返回的格式不一致的 SSE chunk 统一为内部标准 OpenAI 格式。
 *   包括：统一 reasoning 字段名（5 种 → reasoning_content）、清理空值/空 tool_calls、
 *   统一 finish_reason 等。
 *
 * 中枢处理（在 {@link #chatCompletionStream} 的 Reactor 管道中完成）：
 *   基于清洗后的统一格式进行：reasoning fallback
 *   （无正文时回退用思考内容作为回复）、API 调用日志记录。
 *
 * 运行时配置（API Key、Base URL、模型列表）由调用方显式传入。
 */
public abstract class AbstractUpstreamChatService {

    /** SSE 场景下，每个 data 字段的原始字符串类型引用。 */
    private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE = new ParameterizedTypeReference<>() {
    };

    /** 子类可直接使用的 logger，自动绑定到实际子类的类名。 */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** Jackson 对象映射器，用于 SSE chunk 的 JSON 解析与序列化。 */
    protected final ObjectMapper objectMapper;

    /** 当请求中未指定模型时回退使用的默认模型名称。 */
    private final String fallbackDefaultModel;
    private final ProviderRequestHeaderService providerRequestHeaderService;

    /** API 调用日志写入服务，由子类 Spring Bean 通过 setter 注入。 */
    private ApiCallLogService apiCallLog;

    /** API 调用 token 用量写入服务，由 Spring 可选注入；写入独立于日志的 api_call_usage 表。 */
    private ApiCallUsageService apiCallUsage;

    /** 调用生命周期事件通知器，由 Spring 可选注入；用于发出 CONNECTED / RETRYING 等 provider 层观测点。 */
    private CallLifecycleNotifier lifecycleNotifier;

    /**
     * 全局 WebClient.Builder，由 Spring 通过 setter 注入。
     * 该 Builder 在 WebClientConfig 中配置了 JDK 系统 DNS 解析器，
     * 避免 Netty 默认异步解析器在 Windows 上的间歇性 DNS 解析失败。
     */
    private WebClient.Builder webClientBuilder = WebClient.builder();
    private HttpClient httpClient = HttpClient.create();

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

    /**
     * @param objectMapper Jackson 对象映射器
     * @param fallbackDefaultModel 当请求中未指定模型时使用的默认模型名称
     */
    protected AbstractUpstreamChatService(ObjectMapper objectMapper, String fallbackDefaultModel,
                                          ProviderRequestHeaderService providerRequestHeaderService) {
        this.objectMapper = objectMapper;
        this.fallbackDefaultModel = fallbackDefaultModel;
        this.providerRequestHeaderService = providerRequestHeaderService;
    }

    /**
     * 发送一次非流式 Chat Completions 请求。
     *
     * 请求体会经过 {@link #prepareRequestBody} 处理，包括模型名称解析、
     * stream 标志设置和子类的自定义字段注入。
     *
     * @param openAiRequest 原始 OpenAI 格式请求体
     * @param model 请求中指定的模型名称
     * @return 上游返回的原始 OpenAI JSON 响应字符串
     */
    protected Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model,
                                          ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                          String requestId) {
        Map<String, Object> requestBody = prepareRequestBody(openAiRequest, false, model, provider);
        log.info("{} OpenAI 上游，模型: {}, 流式: false", provider.providerKey(), requestBody.get("model"));

        String providerKey = provider.providerKey();
        String modelName = (String) requestBody.get("model");
        Map<String, String> reqHeaders = new LinkedHashMap<>();
        // 每次上游往返（含重试）各自计时并落库：往返开始时刷新起点，使每条日志的 duration 反映该次往返本身。
        AtomicLong attemptStart = new AtomicLong(System.currentTimeMillis());

        return Mono.defer(() -> {
                    attemptStart.set(System.currentTimeMillis());
                    return buildWebClientWithHeaders(reqHeaders, provider, downstreamHeaders, false)
                            .post().uri(chatCompletionsUri()).bodyValue(requestBody).retrieve()
                            .toEntity(String.class);
                })
                // 成功往返：立即落一条成功记录（在 retry 上游，每次往返各自记录）。
                .doOnNext(entity -> {
                    log.debug("{} 响应: {}", provider.providerKey(), entity.getBody());
                    // CONNECTED：上游完整响应已到达（非流式无首字概念，响应到达即视为已连接）。
                    publishLifecycle(CallLifecycleEvent.of(requestId, CallPhase.CONNECTED, modelName, false));
                    Map<String, String> respHeaders = new LinkedHashMap<>();
                    entity.getHeaders().forEach((k, v) -> respHeaders.put(k, String.join(", ", v)));
                    Long logId = saveNonStreamLog(providerKey, modelName, reqHeaders, requestBody, respHeaders,
                            entity.getStatusCode().value(), entity.getBody(), attemptStart.get());
                    // 成功往返：从响应体提取 usage 写入独立用量表（非流式无首字概念，ttfb 传 null）。
                    saveUsageIfPresent(logId, providerKey, modelName, false, entity.getBody(), null);
                })
                // 失败往返：每次失败（含被 retry 吞掉的中间失败）都各自落一条（在 retry 上游）。
                .doOnError(e -> {
                    WebClientResponseException responseException = findWebResponseException(e);
                    if (responseException != null) {
                        Map<String, String> errHeaders = new LinkedHashMap<>();
                        responseException.getHeaders().forEach((k, v) -> errHeaders.put(k, String.join(", ", v)));
                        saveNonStreamLog(providerKey, modelName, reqHeaders, requestBody, errHeaders,
                                responseException.getStatusCode().value(), responseException.getResponseBodyAsString(), attemptStart.get());
                    } else {
                        saveNonStreamLog(providerKey, modelName, reqHeaders, requestBody, Map.of(), -1, null, attemptStart.get());
                    }
                })
                // 重试挂在落库下游：中间失败已在上面各自记录，此处仅负责重订阅。
                .retryWhen(buildRetrySpec("chatCompletion", provider, requestId, modelName, false))
                .map(entity -> entity.getBody());
    }

    /**
     * 发送一次流式 Chat Completions 请求。
     *
     * 该方法会把上游返回的 SSE data 解包为纯 JSON 字符串流，
     * 并在流式过程中处理 reasoning_content 的提取与回退：
     * 如果模型只输出了思考内容而没有正文，则在流末尾自动把思考内容作为回复输出。
     *
     * @param openAiRequest 原始 OpenAI 格式请求体
     * @param model 请求中指定的模型名称
     * @return 按顺序发出的 chunk JSON 字符串，最后一个元素为 "[DONE]"
     */
    protected Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model,
                                                 ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                                 String requestId) {
        Map<String, Object> requestBody = prepareRequestBody(openAiRequest, true, model, provider);
        log.info("{} OpenAI 上游，模型: {}, 流式: true", provider.providerKey(), requestBody.get("model"));

        String providerKey = provider.providerKey();
        String modelName = (String) requestBody.get("model");
        Map<String, String> reqHeaders = new LinkedHashMap<>();
        // 本次往返的清洗后 chunk：成功往返落库用；每次往返（defer 重订阅）在起点清空，只反映该次往返。
        List<String> logChunks = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<Map<String, String>> capturedRespHeaders = new AtomicReference<>(Map.of());
        AtomicReference<Integer> capturedStatusCode = new AtomicReference<>(0);
        // 每次上游往返（含重试）各自计时并落库：往返开始时刷新起点，使每条日志的 duration 反映该次往返本身。
        AtomicLong attemptStart = new AtomicLong(System.currentTimeMillis());

        AtomicBoolean contentEmitted = new AtomicBoolean(false);
        StringBuilder reasoningBuffer = new StringBuilder();
        AtomicReference<String> chunkId = new AtomicReference<>("chatcmpl-unknown");
        // 首字响应时长：上游首个 chunk 到达时记 now - attemptStart；-1 表示尚未测得。
        // 语义为"首 chunk"而非"首正文"，故纯思考、纯工具调用等无正文响应同样能测得。
        // 每次往返（defer 重订阅）在起点重置，使 ttfb 反映最终成功往返的首字延迟（语义2）。
        AtomicLong ttfbMs = new AtomicLong(-1);
        // 本次往返的 usage 原始 JSON：从上游原始 chunk 提取，成功收尾写用量表。往返起点清空。
        AtomicReference<String> usageRaw = new AtomicReference<>(null);
        return Flux.defer(() -> {
                    // 本次往返起点：重置计时与 chunk 收集，使每条日志只反映该次往返（不跨重试累加）。
                    attemptStart.set(System.currentTimeMillis());
                    logChunks.clear();
                    ttfbMs.set(-1);
                    usageRaw.set(null);
                    return buildWebClientWithHeaders(reqHeaders, provider, downstreamHeaders, true)
                            .post().uri(chatCompletionsUri()).bodyValue(requestBody)
                            .exchangeToFlux(response -> {
                                Map<String, String> respHeaders = new LinkedHashMap<>();
                                response.headers().asHttpHeaders().forEach((k, v) -> respHeaders.put(k, String.join(", ", v)));
                                capturedRespHeaders.set(respHeaders);
                                capturedStatusCode.set(response.statusCode().value());
                                // 检查是否为错误响应（4xx/5xx）
                                if (response.statusCode().isError()) {
                                    return response.bodyToMono(String.class).flatMapMany(errorBody -> {
                                        log.warn("{} 上游返回错误响应 {}: {}", provider.providerKey(), response.statusCode().value(), errorBody);
                                        // 失败往返：即时落一条错误记录（retry 上游，每次往返各自记录，无 chunk）。
                                        saveStreamLogWithError(providerKey, modelName, reqHeaders, requestBody,
                                                respHeaders, response.statusCode().value(), List.of(),
                                                respHeaders, response.statusCode().value(), errorBody, attemptStart.get());
                                        return Flux.error(new WebClientResponseException(
                                                response.statusCode().value(), "上游错误响应", null, errorBody.getBytes(), null));
                                    });
                                }
                                // CONNECTED：真正收到上游非错误响应头的那一刻，此时才准确表示"已连接，等待首字"。
                                // 放在此处而非控制器 doOnSubscribe，可覆盖 A1/A2（订阅即谎报"已连接"）。
                                // 重试时每次成功拿到响应头都会重新发一次，属预期行为。
                                publishLifecycle(CallLifecycleEvent.of(requestId, CallPhase.CONNECTED, model, true));
                                return response.bodyToFlux(STRING_SSE_TYPE);
                            });
                })
                // 网络类失败往返（无上游错误响应，如连接失败 / HTTP 200 后流中途断开）：即时落一条记录。
                // 错误响应（4xx/5xx）已在 exchangeToFlux 分支落库，此处用 findWebResponseException==null 排除以免重复。
                .doOnError(e -> {
                    if (findWebResponseException(e) == null) {
                        int statusCode = capturedStatusCode.get() == 0 ? -1 : capturedStatusCode.get();
                        saveStreamLog(providerKey, modelName, reqHeaders, requestBody,
                                capturedRespHeaders.get(), statusCode, List.copyOf(logChunks), attemptStart.get());
                    }
                })
                // 重试挂在落库下游：中间失败已在上游各自记录，此处仅负责重订阅。
                .retryWhen(buildRetrySpec("chatCompletionStream", provider, requestId, model, true)).mapNotNull(ServerSentEvent::data).filter(chunk -> !chunk.isBlank() && !"null".equals(chunk))
                .doOnNext(raw -> {
                    log.debug("{} 上游原始: {}", provider.providerKey(), raw);
                    // 首字打点：上游首个 chunk 到达即记时长（语义为"首 chunk"，不区分其载荷形态）。
                    // 打在最上游的原始 chunk 处，因此纯思考（仅 reasoning_content）、纯工具调用
                    // （仅 tool_calls）等无正文响应同样能测得首字，不依赖 contentEmitted。
                    // 每次往返（defer 重订阅）已在起点重置，故反映最终成功往返的首字延迟。
                    if (ttfbMs.get() < 0) {
                        ttfbMs.set(System.currentTimeMillis() - attemptStart.get());
                    }
                    // 从上游原始 chunk 提取 usage 原始 JSON（通常在尾 chunk）；有则记录供成功收尾落库。
                    String rawUsage = UsageParser.extractUsageRawJson(objectMapper, raw);
                    if (rawUsage != null) {
                        usageRaw.set(rawUsage);
                    }
                }).concatMap(chunk -> {
                    String normalizedChunk = normalizeUpstreamChunk(chunk, contentEmitted, reasoningBuffer, chunkId);
                    if (isTerminalChunk(normalizedChunk)) {
                        // 仅当 contentEmitted=false 且 reasoningBuffer 非空时触发 reasoning fallback
                        if (isStopFinishReason(normalizedChunk) && !contentEmitted.get() && !reasoningBuffer.isEmpty()) {
                            log.warn("模型未输出正文，回退使用思考内容作为回复 (长度: {})", reasoningBuffer.length());
                            String fallbackContent = buildFallbackContentChunk(chunkId.get(), model, reasoningBuffer.toString());
                            String fallbackFinish = buildFallbackFinishChunk(chunkId.get(), model);
                            return Flux.just(fallbackContent, fallbackFinish);
                        }
                    }
                    return Flux.just(normalizedChunk);
                }).doOnNext(chunk -> {
                    log.debug("{} 上游清洗: {}", provider.providerKey(), chunk);
                    logChunks.add(chunk);
                })
                // 成功往返收尾：仅在非错误终结（complete / cancel）时落一条成功记录。
                // 失败往返（错误响应 / 网络失败）已在 retry 上游即时落库，此处 ON_ERROR 不重复。
                .doFinally(signal -> {
                    if (signal != SignalType.ON_ERROR) {
                        int statusCode = capturedStatusCode.get();
                        if (statusCode == 0 && logChunks.isEmpty()) {
                            statusCode = -1;
                        }
                        Long logId = saveStreamLog(providerKey, modelName, reqHeaders, requestBody,
                                capturedRespHeaders.get(), statusCode, logChunks, attemptStart.get());
                        // 写入时序 A（串联）：仅成功且有 usage 时写用量表；log_id 拿不到则降级为孤儿行。
                        long ttfb = ttfbMs.get();
                        saveUsage(logId, providerKey, modelName, true, usageRaw.get(),
                                ttfb < 0 ? null : (int) ttfb);
                    }
                });
    }

    /**
     * 构建 WebClient，并在 WebClient 与 Reactor Netty 两个层级记录出站请求头。
     *
     * WebClient 过滤器先记录默认头、转换规则头和请求级头；Reactor Netty 的
     * doOnRequest 随后用传输层快照覆盖它，因此生产日志还包含 User-Agent、Host
     * 等由底层 HTTP 客户端最后补入的头。重试时快照更新为最后一次实际尝试。
     *
     * 注意：此处用注入的 {@code httpClient} 派生 capturingHttpClient 并覆盖了
     * webClientBuilder 自带的 connector，因此实际的 DNS 解析行为由注入的
     * {@code httpClient}（WebClientConfig 中配置了 JDK 系统解析器的全局 Bean）决定，
     * 而非 webClientBuilder 内部的 connector。修改 DNS 规避策略时应改 httpClient Bean，
     * 只改 webClientBuilder 的 connector 不会在这条链上生效。
     *
     * @param capturedHeaders 用于存放最终请求头安全快照的 Map
     * @return 配置好的 WebClient 实例
     */
    protected WebClient buildWebClientWithHeaders(Map<String, String> capturedHeaders,
                                                  ProviderRuntimeConfiguration provider,
                                                  HttpHeaders downstreamHeaders, boolean stream) {
        String apiKey = provider.apiKey();
        String baseUrl = provider.baseUrl().isBlank() ? defaultBaseUrl() : provider.baseUrl();
        String normalizedUrl = providerRequestHeaderService.normalizeBaseUrl(baseUrl);
        HttpClient capturingHttpClient = httpClient.doAfterRequest((request, connection) -> {
            HttpHeaders transportHeaders = new HttpHeaders();
            request.requestHeaders().forEach(entry ->
                transportHeaders.add(entry.getKey(), entry.getValue()));
            providerRequestHeaderService.mergeLogSnapshot(capturedHeaders, transportHeaders);
        });

        return webClientBuilder.clone()
            .clientConnector(new ReactorClientHttpConnector(capturingHttpClient))
            .baseUrl(normalizedUrl).defaultHeaders(headers -> {
                providerRequestHeaderService.applyHeaders(
                    headers, downstreamHeaders, apiKey, provider.headerRulesJson(), stream);
        }).filter((request, next) -> {
            capturedHeaders.clear();
            capturedHeaders.putAll(providerRequestHeaderService.createLogSnapshot(request.headers()));
            return next.exchange(request);
        }).build();
    }

    /**
    * 准备请求体，解析模型名称，设置流式标志，并应用当前供应商的请求体规则。
     * @param openAiRequest 请求体的初始 Map 结构
     * @param stream 是否启用流式响应
     * @param model 模型名称
     * @return 最终准备好的请求体 Map 结构，已经解析了模型名称并设置了流式标志
     */
    protected Map<String, Object> prepareRequestBody(Map<String, Object> openAiRequest, boolean stream, String model,
                                                      ProviderRuntimeConfiguration provider) {
        Map<String, Object> body = new LinkedHashMap<>(openAiRequest);
        String resolvedModel = resolveModel(body.get("model"), model);
        body.put("model", resolvedModel);
        body.put("stream", stream);
        // 如果请求中没有指定 reasoning_effort，从模型配置中读取
        if (!body.containsKey("reasoning_effort")) {
            String effort = resolveReasoningEffort(resolvedModel, provider);
            if (effort != null) {
                body.put("reasoning_effort", effort);
            } else {
                body.remove("reasoning_effort");
            }
        }
        body.values().removeIf(Objects::isNull);
        customizeRequestBody(body, resolvedModel, provider);
        return body;
    }

    /**
     * 保存非流式调用日志。
     *
     * @return 新插入日志行的自增 id；日志未启用或写入失败时返回 null
     */
    private Long saveNonStreamLog(String providerKey, String modelName, Map<String, String> reqHeaders, Map<String, Object> requestBody, Map<String, String> respHeaders, int statusCode, String responseBody, long startTime) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveNonStream(providerKey, modelName, reqHeaders, requestBody, respHeaders, statusCode, responseBody, duration);
    }

    /**
     * 保存流式调用日志。
     *
     * @return 新插入日志行的自增 id；日志未启用或写入失败时返回 null
     */
    private Long saveStreamLog(String providerKey, String modelName, Map<String, String> reqHeaders, Map<String, Object> requestBody, Map<String, String> respHeaders, int statusCode, List<String> chunks, long startTime) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveStream(providerKey, modelName, reqHeaders, requestBody, respHeaders, statusCode, chunks, duration);
    }

    /**
     * 保存流式调用日志（含错误信息）。
     * 当流式响应过程中发生错误且重试耗尽时，将错误响应体保存到非流式响应列。
     */
    private Long saveStreamLogWithError(String providerKey, String modelName, Map<String, String> reqHeaders, Map<String, Object> requestBody,
                                        Map<String, String> respHeaders, int statusCode, List<String> chunks,
                                        Map<String, String> errorHeaders, int errorCode, String errorBody, long startTime) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveStreamWithError(providerKey, modelName, reqHeaders, requestBody,
                respHeaders, statusCode, chunks, errorHeaders, errorCode, errorBody, duration);
    }

    /**
     * 从完整响应体提取 usage 并写入用量表（非流式用）。
     *
     * <p>写入时序 A（串联）：仅当响应体含合法 usage 对象时写一行；无 usage 则不写（方案 a）。
     * usageRaw 从响应体提取的 usage 对象原始 JSON（零损失兜底），token 经共用解析器按存在性提取。
     * {@code logId} 为软链接：拿到则关联，拿不到（日志写入失败）传 null 写孤儿行。
     *
     * @param logId    api_call_log 自增 id；可为 null
     * @param stream   是否流式
     * @param fullBody 完整响应体 JSON
     * @param ttfbMs   首字响应时长；非流式传 null
     */
    private void saveUsageIfPresent(Long logId, String providerKey, String modelName, boolean stream,
                                    String fullBody, Integer ttfbMs) {
        if (apiCallUsage == null) return;
        String usageRaw = UsageParser.extractUsageRawJson(objectMapper, fullBody);
        if (usageRaw == null) return; // 无 usage：不写（方案 a）
        UsageTokens tokens = UsageParser.parseUsageObject(objectMapper, usageRaw);
        apiCallUsage.save(logId, providerKey, modelName, stream, usageRaw, tokens, ttfbMs);
    }

    /**
     * 用已提取的 usage 原始 JSON 写入用量表（流式用）。
     *
     * <p>流式链路已在原始 chunk 阶段提取好 usage 对象 JSON（{@code usageRaw}）。
     * 写入时序 A：仅当 usageRaw 非 null 时写一行；无 usage 不写（方案 a）。
     *
     * @param logId    api_call_log 自增 id；可为 null（软链接，拿不到写孤儿行）
     * @param usageRaw 已提取的 usage 对象原始 JSON；null 表示本次往返无 usage
     * @param ttfbMs   首字响应时长；未测得传 null
     */
    private void saveUsage(Long logId, String providerKey, String modelName, boolean stream,
                           String usageRaw, Integer ttfbMs) {
        if (apiCallUsage == null) return;
        if (usageRaw == null) return; // 无 usage：不写（方案 a）
        UsageTokens tokens = UsageParser.parseUsageObject(objectMapper, usageRaw);
        apiCallUsage.save(logId, providerKey, modelName, stream, usageRaw, tokens, ttfbMs);
    }

    /**
     * 从异常链中查找 WebClientResponseException。
     * 重试耗尽时原始异常被包装在 RetryExhaustedException 中，需要递归解包。
     */
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
     * 从运行时模型配置中读取思考深度。如果未找到，返回 medium。
     */
    private String resolveReasoningEffort(String resolvedModel, ProviderRuntimeConfiguration provider) {
        for (var m : provider.models()) {
            if (resolvedModel.equals(m.modelName())) {
                String effort = m.reasoningEffort();
                if (effort == null || effort.isBlank() || "none".equalsIgnoreCase(effort.trim())) {
                    return null;
                }
                return effort.toLowerCase();
            }
        }
        return "medium";
    }

    /**
     * 子类可以重写此方法在请求体中添加特定的字段或格式转换，例如将模型名称转换为特定服务识别的格式。
     * <p>
     * @param body 请求体的 Map 结构，子类可以直接修改该 Map 来添加或修改字段
     * @param resolvedModel 已经解析出的模型名称，子类可以根据该名称来决定是否进行特定的字段添加或格式转换
     */
    protected void customizeRequestBody(Map<String, Object> body, String resolvedModel,
                                        ProviderRuntimeConfiguration provider) {
    }

    /**
     * 解析请求中的模型名称，如果请求中没有指定模型或指定的模型名称无效，则使用提供的 fallbackModel 进行回退，如果 fallbackModel 也无效则使用全局默认模型。
     * <p>
     * 如果模型名称包含供应商前缀（如 {@code [DeepSeek]deepseek-v4-flash}），会自动去除前缀后再返回。
     * @param requestModel 请求中指定的模型名称，可能为 null 或空字符串
     * @param fallbackModel 提供的回退模型名称，可能为 null 或空字符串
     * @return 最终解析出的模型名称，保证不为 null 或空字符串，且不含供应商前缀
     */
    protected String resolveModel(Object requestModel, String fallbackModel) {
        String model;
        if (requestModel instanceof String value && !value.isBlank()) {
            model = value;
        } else if (fallbackModel != null && !fallbackModel.isBlank()) {
            model = fallbackModel;
        } else {
            return fallbackDefaultModel;
        }
        // 去除供应商前缀（如 [DeepSeek]deepseek-v4-flash → deepseek-v4-flash）
        var parsed = ModelNameUtil.parse(model);
        return parsed.modelName();
    }

    /**
     * 构建 OpenAI 上游的统一重试策略。
     *
     * 覆盖四类可恢复场景：
     * 1. 429 上游限速（使用指数退避，避免加重上游压力）
     * 2. 5xx 服务端错误
     * 3. 可重试的 400 错误
     * 4. 网络层异常：包括 WebClientRequestException（连接建立失败）
     *    以及 WebClientResponseException 的 cause chain 中的 IOException
     *    （如 SocketException: Connection reset，即 HTTP 200 但 SSE 流中途断开）
     *
     * @param method 调用方方法名，用于日志区分重试来源
     * @param requestId 本次调用唯一标识，用于发出 RETRYING 生命周期事件
     * @param model 模型名称（含前缀），用于 RETRYING 事件展示
     * @param stream 是否流式请求
     * @return 配置好的 Retry 实例
     */
    protected Retry buildRetrySpec(String method, ProviderRuntimeConfiguration provider,
                                   String requestId, String model, boolean stream) {
        return Retry.backoff(5, Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(30))
                .filter(ex -> ((ex instanceof WebClientResponseException responseException) && (responseException.getStatusCode().value() == 429 || responseException.getStatusCode().is5xxServerError()
                        || responseException.getStatusCode().value() == 400 || hasNetworkCause(responseException))) || ex instanceof WebClientRequestException
                        || hasSslHandshakeFailure(ex))
                .doBeforeRetry(signal -> {
                    int attempt = (int) (signal.totalRetries() + 1);
                    // RETRYING：让前端 Toast 从“已连接/等待中”切换到“上游异常，正在重试（第N次）”，
                    // 避免重试期间静默卡顿让用户误以为卡死。
                    publishLifecycle(CallLifecycleEvent.retrying(requestId, model, stream, attempt));
                    if (signal.failure() instanceof WebClientResponseException responseException && responseException.getStatusCode().value() == 429) {
                        String retryAfter = responseException.getHeaders().getFirst("Retry-After");
                        log.warn("[{}] {} API 限速 (429)，重试第 {} 次{}", method, provider.providerKey(), attempt, retryAfter != null ? "，Retry-After: " + retryAfter + "s" : "");
                    } else {
                        log.warn("[{}] {} API 调用失败，重试第 {} 次: {}", method, provider.providerKey(), attempt, signal.failure().getMessage());
                    }
                });
    }

    /**
     * best-effort 发出一个生命周期事件；notifier 未注入（如单元测试）或发布异常时静默跳过，
     * 绝不影响正在进行的聊天数据流。
     */
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

    /**
     * 判断异常的 cause chain 中是否包含网络层异常（IOException 及其子类，如 SocketException）。
     *
     * 这类异常通常表现为 HTTP 200 但 SSE 流中途断开，需要重试。
     */
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

    /**
     * 判断异常的 cause chain 中是否包含 SSL/TLS 握手失败。
     *
     * SSL 握手异常通常由 Netty 的 DecoderException 包裹 SSLHandshakeException，
     * 不属于 WebClientRequestException 也不属于 WebClientResponseException，
     * 需要单独判断以支持重试。
     */
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

    /**
     * 提供默认的 Base URL，当运行时配置中未指定地址时使用。
     *
     * @return 默认 Base URL，以协议开头，不含路径后缀
     */
    protected abstract String defaultBaseUrl();

    /**
     * 提供 Chat Completions 端点的 URI 路径。
     *
     * @return 端点 URI，如 "/v1/chat/completions"
     */
    protected abstract String chatCompletionsUri();

    /**
     * 上游清洗 —— 对上游返回的原始 SSE chunk 做统一标准化。
     *
     * 本方法属于三阶段管道的第一阶段（上游清洗），目的是将各上游供应商返回的
     * 格式不一致的 chunk 统一为本服务内部约定的 OpenAI 标准格式，以便后续阶段
     * （中枢处理：reasoning 累积/缓存/fallback/日志）能基于统一格式工作。
     *
     * 清洗内容：
     * 1. 统一 reasoning 字段名：thinking / reasoning / reasoning_text / cot_summary → reasoning_content
     * 2. 统一 finish_reason：空字符串 → null
     * 3. 清理空 tool_calls（[] / null → 删除）
     * 4. 递归剪枝空值（null / "" / [] / 空 Map）
     *
     * 注意：此方法不做"下游格式化"，清洗后的 chunk 仍然是 OpenAI 格式。
     * 下游序列化（OpenAI passthrough 或 Ollama 结构转换）由 Controller 层负责。
     *
     * @param chunkJson 原始 SSE data 的 JSON 字符串
     * @param contentEmitted 是否已经输出过正文 content
     * @param reasoningBuffer 累积 reasoning_content 的缓冲区
     * @param chunkId 当前流的 chunk ID 引用
     * @return 清洗后的 chunk JSON 字符串
     */
    @SuppressWarnings("unchecked")
    private String normalizeUpstreamChunk(String chunkJson, AtomicBoolean contentEmitted, StringBuilder reasoningBuffer, AtomicReference<String> chunkId) {
        try {
            if ("[DONE]".equals(chunkJson)) {
                return chunkJson;
            }
            Map<String, Object> chunk = objectMapper.readValue(chunkJson, Map.class);

            // 记录 chunk ID，供 reasoning fallback 构建伪 chunk 时保持一致性。
            Object id = chunk.get("id");
            if (id instanceof String idStr && !idStr.isEmpty()) {
                chunkId.set(idStr);
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
            if (choices == null || choices.isEmpty()) {
                return chunkJson;
            }

            Map<String, Object> choice = choices.get(0);
            Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
            boolean preserveEmptyDelta = delta != null;
            boolean preserveNullFinishReason = false;

            // 统一 finish_reason：空字符串 → null
            Object finishReasonObj = choice.get("finish_reason");
            if (finishReasonObj instanceof String finishReason && finishReason.isBlank()) {
                choice.put("finish_reason", null);
                preserveNullFinishReason = true;
            } else if (choice.containsKey("finish_reason") && finishReasonObj == null) {
                preserveNullFinishReason = true;
            }

            if (delta != null) {
                normalizeDelta(delta, reasoningBuffer);

                // 标记是否已经输出过正文 content，用于判断是否需要 reasoning fallback。
                Object contentObj = delta.get("content");
                if (contentObj instanceof String content && !content.isEmpty()) {
                    contentEmitted.set(true);
                }

                // 如果 delta 规范化后为空，后续 prune 后再恢复为空对象，作为标准结束 chunk 形式
            }

            pruneEmptyValues(chunk);

            // 保留结构性字段：中间 chunk 的 finish_reason:null 不应被删除
            if (preserveNullFinishReason && !choice.containsKey("finish_reason")) {
                choice.put("finish_reason", null);
            }
            // 保留结构性字段：结束 chunk / 空 delta chunk 应保留 delta:{}
            if (preserveEmptyDelta && !choice.containsKey("delta")) {
                choice.put("delta", new LinkedHashMap<String, Object>());
            }

            return objectMapper.writeValueAsString(chunk);
        } catch (Exception exception) {
            return chunkJson;
        }
    }

    /**
     * 统一规范化 delta 字段。
     */
    private void normalizeDelta(Map<String, Object> delta, StringBuilder reasoningBuffer) {
        // 统一 reasoning 字段名到 reasoning_content
        String reasoning = extractReasoning(delta);
        if (reasoning != null && !reasoning.isBlank()) {
            reasoningBuffer.append(reasoning);
            delta.put("reasoning_content", reasoning);
        }

        // 没有真实工具调用时，绝不保留 tool_calls（尤其不能保留 []）
        Object toolCallsObj = delta.get("tool_calls");
        if (!(toolCallsObj instanceof List<?> toolCalls) || !isMeaningfulToolCalls(toolCalls)) {
            delta.remove("tool_calls");
        }

        // 删除空字段（null / "" / []）
        pruneEmptyValues(delta);
    }

    /**
     * 从多个兼容字段中提取思考内容，并统一成 reasoning_content。
     */
    private String extractReasoning(Map<String, Object> delta) {
        String[] keys = {"reasoning_content", "reasoning_text", "reasoning", "thinking", "cot_summary"};
        for (String key : keys) {
            Object value = delta.get(key);
            if (value instanceof String str && !str.isBlank()) {
                // 清理旧字段，只保留 reasoning_content
                for (String k : keys) {
                    if (!"reasoning_content".equals(k)) {
                        delta.remove(k);
                    }
                }
                return str;
            }
        }
        // 如果都为空，也要清理旧字段名，避免带着空串出去
        for (String k : keys) {
            if (!"reasoning_content".equals(k)) {
                delta.remove(k);
            }
        }
        return null;
    }

    /**
     * 判断 tool_calls 是否真的有意义（至少有一个非空元素）。
     */
    private boolean isMeaningfulToolCalls(List<?> toolCalls) {
        if (toolCalls.isEmpty()) {
            return false;
        }
        for (Object item : toolCalls) {
            if (item instanceof Map<?, ?> map && !map.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 递归删除 Map/List 中的空值：null、空字符串（""）、空列表、空 Map。
     *
     * 注意：只删除真正的空字符串（isEmpty），不删除仅包含空白字符的字符串（isBlank），
     * 因为空格（" "）、换行（"\n"）、制表符（"\t"）等在 content 中是有意义的内容，
     * 对 Markdown 格式（列表缩进、段落分隔、代码块）至关重要。
     */
    @SuppressWarnings("unchecked")
    private void pruneEmptyValues(Object node) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            List<String> keysToRemove = new ArrayList<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                Object value = entry.getValue();
                pruneEmptyValues(value);
                if (value == null
                        || (value instanceof String str && str.isEmpty())
                        || (value instanceof List<?> list && list.isEmpty())
                        || (value instanceof Map<?, ?> childMap && childMap.isEmpty())) {
                    keysToRemove.add(entry.getKey());
                }
            }
            for (String key : keysToRemove) {
                map.remove(key);
            }
        } else if (node instanceof List<?> rawList) {
            List<Object> list = (List<Object>) rawList;
            list.removeIf(item -> {
                pruneEmptyValues(item);
                return item == null
                        || (item instanceof String str && str.isEmpty())
                        || (item instanceof List<?> childList && childList.isEmpty())
                        || (item instanceof Map<?, ?> childMap && childMap.isEmpty());
            });
        }
    }

    /**
     * 判断是否为需要触发收尾逻辑的终止 chunk。
     */
    @SuppressWarnings("unchecked")
    private boolean isTerminalChunk(String chunkJson) {
        if ("[DONE]".equals(chunkJson)) {
            return false;
        }
        try {
            Map<String, Object> chunk = objectMapper.readValue(chunkJson, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
            if (choices == null || choices.isEmpty()) {
                return false;
            }
            Object finishReason = choices.get(0).get("finish_reason");
            return "stop".equals(finishReason) || "tool_calls".equals(finishReason);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isStopFinishReason(String chunkJson) {
        return hasFinishReason(chunkJson, "stop");
    }

    private boolean hasFinishReason(String chunkJson, String expected) {
        try {
            if ("[DONE]".equals(chunkJson)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> chunk = objectMapper.readValue(chunkJson, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
            if (choices == null || choices.isEmpty()) {
                return false;
            }
            Object finishReason = choices.get(0).get("finish_reason");
            return expected.equals(finishReason);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构建 reasoning fallback 的正文 chunk。
     *
     * 当模型只输出了思考内容而没有正文时，用思考内容构造一个伪 content delta，
     * 使客户端看到的回复内容就是模型的思考过程。
     *
     * @param id 当前流的 chunk ID
     * @param model 模型名称
     * @param reasoningContent 累积的思考内容
     * @return OpenAI chunk JSON 字符串
     */
    private String buildFallbackContentChunk(String id, String model, String reasoningContent) {
        try {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);

            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("role", "assistant");
            delta.put("content", reasoningContent);

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", null);

            chunk.put("choices", List.of(choice));
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception exception) {
            return "{}";
        }
    }

    /**
     * 构建 reasoning fallback 的 finish chunk。
     *
     * 紧跟在 {@link #buildFallbackContentChunk} 之后发出，标记流的结束。
     *
     * @param id 当前流的 chunk ID
     * @param model 模型名称
     * @return OpenAI chunk JSON 字符串，finish_reason 为 "stop"
     */
    private String buildFallbackFinishChunk(String id, String model) {
        try {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);

            Map<String, Object> delta = new LinkedHashMap<>();

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", "stop");

            chunk.put("choices", List.of(choice));
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception exception) {
            return "{}";
        }
    }

}
package com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.AnthropicThinkingSetting;
import com.kaixuan.copilot_ollama_proxy.application.runtime.AuthHeaderSetting;
import com.kaixuan.copilot_ollama_proxy.application.runtime.MaxOutputTokensSetting;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ReasoningEffortSetting;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallRetryRegistry;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import com.kaixuan.copilot_ollama_proxy.provider.ChunkLogPayload;
import com.kaixuan.copilot_ollama_proxy.provider.EmptyUpstreamResponseException;
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamEventClassifier;
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamExecutor;
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamCallReporter;
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamRetryPolicy;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ContentDetectorRegistry;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ContentDetectorStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.EmptyResponseGate;
import com.kaixuan.copilot_ollama_proxy.provider.stage.RequestBodyStageRegistry;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用 Anthropic 上游服务 —— 对接 Anthropic Messages API 协议的供应商。
 *
 * <h2>为何与 {@code AbstractUpstreamChatService} 平级而非继承它</h2>
 * 两种协议的 Reactor 链体<strong>结构不同</strong>：Chat 的事件形态是
 * {@code choices[].delta}，Anthropic 是 {@code message_start} /
 * {@code content_block_*} / {@code message_delta} 并需维护 block 状态。
 * 若强行抽公共父类，那两个方法会退化成一堆钩子 —— 模板方法反而让子类作者
 * 看不见自己正在依赖什么。
 *
 * <p><strong>空响应 gate 的语义两条线一致（都扣住）</strong>，机制本身已收归
 * {@link EmptyResponseGate}（阶段 3.6b）—— 扣住-释放的完整理由见那个类的类注释。
 * 曾经这里不扣帧、只记「是否见过实质载荷」，
 * 理由是「客户端是事件状态机，扣住 {@code message_start} 会让它无法初始化」——
 * 该理由不成立：扣住是暂时的，开闸时整批释放，下游看到的是完整合法前缀。
 * 而不扣帧会导致重试时下游收到重复的 {@code message_start}、
 * 以及耗尽时把已流走的事件再放行一遍。
 *
 * <p><strong>接线顺序在两处仍各写一遍</strong>。这是<strong>有意接受</strong>的：
 * 接线顺序写出来带注释比藏在基类里更可审。其中最贵的一条约束是空响应判定的位置，
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
public class GenericAnthropicChatService implements UpstreamExecutor {

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

    /**
     * 请求体支线的查表 —— 三个协议特定步骤按 {@code ctx.bodyProtocol()} 查实现。
     *
     * <h2>为何是构造器参数而不是可选 setter</h2>
     * 项目里其它可选依赖（日志、用量、WebClient）用 {@code @Autowired(required = false)}，
     * 因为「未注入」是合法状态（测试直接 new 时不需要它们）。
     * <strong>本字段不同</strong>：它是主干上的查表入口，缺失意味着三个协议特定步骤
     * 全部静默跳过 —— 而「未命中即跳过」在查表语义下是<strong>正常结果</strong>，
     * 于是「装配漏了」与「该协议没这个步骤」在行为上无从区分。
     *
     * <p>因此把它放进构造器：漏传会<strong>编译失败</strong>，而不是悄悄少做三步。
     * 这也符合「不做安全跳过」的取舍 —— 安全跳过会产出永远为真的测试。
     */
    private final RequestBodyStageRegistry requestBodyStageRegistry;

    /**
     * 内容检测器的查表 —— 空响应拦截的判据来源（阶段 3.6b）。
     *
     * <p>与 {@link #requestBodyStageRegistry} 同一取舍：放构造器而非可选 setter。
     * 本表未命中是<strong>报错</strong>而非跳过，因此漏注入的后果比漏一个支线更重 ——
     * 整条线路的空响应兼底会直接失效。
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

    public GenericAnthropicChatService(ObjectMapper objectMapper,
                                       ProviderRequestHeaderService providerRequestHeaderService,
                                       RequestBodyRuleEngine requestBodyRuleEngine,
                                       RequestBodyStageRegistry requestBodyStageRegistry,
                                       ContentDetectorRegistry contentDetectorRegistry) {
        this.objectMapper = objectMapper;
        this.providerRequestHeaderService = providerRequestHeaderService;
        this.requestBodyRuleEngine = requestBodyRuleEngine;
        this.requestBodyStageRegistry = requestBodyStageRegistry;
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
        return WireProtocol.MESSAGES;
    }

    /**
     * 主干 send 插槽（非流式）。
     *
     * <p>参数全部来自 ctx。{@code chunkRewriter} 是本路线<strong>真正会用</strong>的那个 ——
     * 它由主干按回程翻译器构造，故留在签名上（3.3d-3 的 D2）。
     */
    @Override
    public Mono<UpstreamEvent> invoke(RequestPipelineContext ctx,
                                      Function<List<String>, ChunkLogPayload> chunkRewriter) {
        return messages(ctx.body(), ctx.model(), ctx.provider(), ctx.downstreamHeaders(),
                ctx.requestId(), chunkRewriter, ctx);
    }

    /** 主干 send 插槽（流式），理由同 {@link #invoke}。 */
    @Override
    public Flux<UpstreamEvent> invokeStream(RequestPipelineContext ctx,
                                            Function<List<String>, ChunkLogPayload> chunkRewriter) {
        return messagesStream(ctx.body(), ctx.model(), ctx.provider(), ctx.downstreamHeaders(),
                ctx.requestId(), chunkRewriter, ctx);
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
    protected Mono<UpstreamEvent> messages(Map<String, Object> request, String model,
                                           ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                           String requestId, Function<List<String>, ChunkLogPayload> chunkRewriter,
                                           RequestPipelineContext ctx) {
        // stream 取自 ctx（3.5a）：主干按 ctx.stream() 选了本方法，故这里二者必定一致。
        // ⚠️ 这条一致性依赖调用方守规矩：直接调本方法而 ctx 里 stream=false 会走错路且不响。
        //    该不变式在 3.5b（两态合链）后自然消失（与 executeStream 同属搁置项）。
        boolean stream = ctx.stream();
        Map<String, Object> requestBody = prepareRequestBody(request, stream, model, provider, ctx);
        log.info("{} Anthropic 上游，模型: {}, 流式: {}", provider.providerKey(), requestBody.get("model"), stream);

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
                            entity.getStatusCode().value(), entity.getBody(), attemptStart.get(), ctx);
                    // ttfb 传 null：非流式没有首字概念，与 OpenAI 侧一致。
                    saveUsage(logId, providerKey, modelName, stream,
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
                                responseException.getResponseBodyAsString(), attemptStart.get(), ctx);
                        publishCallRecorded();
                    } else {
                        // 状态码 -1：非 HTTP 异常的占位值，与 OpenAI 侧同一约定。
                        saveNonStreamLog(providerKey, modelName, reqHeaders, requestBody, Map.of(), -1, null,
                                attemptStart.get(), ctx);
                        publishCallRecorded();
                    }
                })
                // 空响应兜底：转成异常以复用下方同一条 retryWhen 的预算。
                // 判定机制收归 EmptyResponseGate（阶段 3.6b）—— 三条线路同源，
                // 因此不会出现「切一下 stream 开关，同一个上游故障的结论就不同」。
                .flatMap(entity -> EmptyResponseGate
                        .checkNonStream(entity.getBody(), gate.active(), detector, log, callCtx)
                        .thenReturn(entity))
                .retryWhen(buildRetrySpec("messages", provider, requestId, modelName, stream))
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
     * 发送一次流式 Messages 请求。
     *
     * <h2>空响应 gate 与 OpenAI 侧同构（扣住-释放）</h2>
     * 机制已收归 {@link EmptyResponseGate}（阶段 3.6b），三条线路共用一份实现 ——
     * 本方法只提供<strong>扣住的元素类型</strong>与<strong>检测器</strong>。
     *
     * <p>曾经这里<strong>不扣帧</strong>、只记「是否见过实质载荷」，理由是
     * 「客户端是事件状态机，扣住 {@code message_start} 会让它无法初始化」。
     * 该理由<strong>不成立</strong>：扣住是暂时的，开闸时整批按序释放，
     * 下游看到的是一个完整合法前缀，只是晚了一点（那段时间由心跳保活）。
     * 而不扣帧会导致重试时下游收到重复的 {@code message_start}，
     * 以及耗尽时把已流走的事件再放行一遍。
     *
     * <h2>手动（静默）重试已接入</h2>
     * {@code CallRetryRegistry} + {@code takeUntilOther}，与 OpenAI 侧同构。
     *
     * <p>曾经刻意不接，理由是「Anthropic 客户端是事件状态机：一个未收到
     * {@code message_stop} 的序列后接一个全新的 {@code message_start}，对严格客户端
     * 是否合法尚未验证」。那个顾虑<strong>只对 ANTHROPIC → ANTHROPIC 直连成立</strong>；
     * C2M 路线（下游 OpenAI、上游 Anthropic）的下游拿到的是翻译后的 OpenAI chunk，
     * 与 OpenAI 直连的重试语义完全一致，本就不受这条限制约束。
     *
     * <p>现按功能完整性优先，两条路线一并接入：不接的那一侧会让前端菜单项
     * （条件只看是否流式，看不到上游协议）表现为「点了没反应、无任何报错」，
     * 那比直连场景下的理论风险更明确地有害。
     */
    protected Flux<UpstreamEvent> messagesStream(Map<String, Object> request, String model,
                                                 ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                                 String requestId, Function<List<String>, ChunkLogPayload> chunkRewriter,
                                                 RequestPipelineContext ctx) {
        // stream 取自 ctx（3.5a）—— 它是请求级事实，与「哪条链被调了」同源，不留字面量。
        // （3.5b 两态合链已搁置，故此处不再以后续步骤为由。）
        boolean stream = ctx.stream();
        Map<String, Object> requestBody = prepareRequestBody(request, stream, model, provider, ctx);
        log.info("{} Anthropic 上游，模型: {}, 流式: {}", provider.providerKey(), requestBody.get("model"), stream);

        // 拦截是否介入：请求级事实，故在 defer 之外算一次 —— 重试不改变它的值。
        // 见 RequestPipelineContext 的「生命周期」注释：写进 defer 里会让半实现态在第二轮又走回判空重试。
        // 机制收归 EmptyResponseGate（阶段 3.6b）：闸门状态与缓存帧都在它那里，
        // 本类只负责每轮 defer 内调 reset()。
        EmptyResponseGate<String> gate = new EmptyResponseGate<>(ctx.shouldApplyEmptyResponseGate());
        EmptyResponseGate.CallContext callCtx = new EmptyResponseGate.CallContext(provider.providerKey(), model, requestId);
        ContentDetectorStage detector = contentDetectorRegistry.require(ctx.upstreamProtocol());

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
        // 存档用的 usage 原文。挑「信息量最大」的那一份而非最后一份，打平时取结算态
        // （message_delta），理由见 pickRicherUsageRaw —— 有的上游每个事件都带 usage，
        // 且尾事件全零；也不跨事件拼字段，那会造出上游从未发出过的报文。
        AtomicReference<String> archivedUsageRaw = new AtomicReference<>(null);
        // 空响应耗尽放行标记：该轮已在 doOnError 落过库，收尾处据此跳过，避免重复记录。
        AtomicBoolean emptyResponsePassthrough = new AtomicBoolean(false);

        Flux<String> attempt = Flux.defer(() -> {
                    // 每轮往返起点重置：使每条日志只反映该次往返，不跨重试累加。
                    // 状态在 defer 内重置而非声明处初始化 —— retryWhen 会重订阅，
                    // 若不重置，第二轮会带着第一轮的 gate 状态与事件记录。
                    attemptStart.set(System.currentTimeMillis());
                    logChunks.clear();
                    ttfbMs.set(-1);
                    usageAccumulator.set(UsageTokens.EMPTY);
                    archivedUsageRaw.set(null);
                    // 闸门状态同理每轮重置（机制在 EmptyResponseGate 里，理由见它自己的注释）。
                    gate.reset();
                    return buildWebClient(reqHeaders, provider, downstreamHeaders, stream)
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
                                                attemptStart.get(), chunkRewriter, ctx);
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
                    // usage 跨事件合并：message_start 给输入、message_delta 给输出。
                    String rawUsage = AnthropicUsageParser.extractUsageRawJson(objectMapper, data);
                    if (rawUsage != null) {
                        archivedUsageRaw.set(pickRicherUsageRaw(archivedUsageRaw.get(), rawUsage,
                                isSettlementEvent(data)));
                        usageAccumulator.set(AnthropicUsageParser.merge(usageAccumulator.get(),
                                AnthropicUsageParser.parseUsageObject(objectMapper, rawUsage)));
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
                // 此处用 findWebResponseException == null 排除以免重复。
                .doOnError(e -> {
                    EmptyUpstreamResponseException emptyResponse = UpstreamRetryPolicy.findEmptyUpstreamException(e);
                    if (emptyResponse != null) {
                        saveStreamLog(providerKey, modelName, reqHeaders, requestBody,
                                capturedRespHeaders.get(), capturedStatusCode.get(),
                                emptyResponse.bufferedFrames(), attemptStart.get(), chunkRewriter, ctx);
                        publishCallRecorded();
                        return;
                    }
                    if (findWebResponseException(e) == null) {
                        int statusCode = capturedStatusCode.get() == 0 ? -1 : capturedStatusCode.get();
                        saveStreamLog(providerKey, modelName, reqHeaders, requestBody,
                                capturedRespHeaders.get(), statusCode, List.copyOf(logChunks),
                                attemptStart.get(), chunkRewriter, ctx);
                        publishCallRecorded();
                    }
                })
                .retryWhen(buildRetrySpec("messagesStream", provider, requestId, model, stream))
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

        // 静默重试循环：与 OpenAI 侧同构。把重试信号挂到<strong>整轮尝试</strong>
        // （含 retryWhen 的 backoff 等待）上，使请求进行中与退避等待两个阶段都能被信号中断。
        // 被中断的轮次以无值完成收场，若标志为真则递归重发；每次重发都注册新鲜的信号，
        // 因此可以连续点击。下游断连时整个链被取消，递归随之终止。
        //
        // 不消耗 retryWhen 的预算：那条预算属于「COSP 自己判定的失败」，
        // 而这里是管理员的显式意图，两者不该互相挤占。
        AtomicBoolean silentRetryRequested = new AtomicBoolean(false);
        AtomicReference<Flux<UpstreamEvent>> attemptLoopRef = new AtomicReference<>();
        Flux<UpstreamEvent> attemptLoop = Flux.defer(() -> {
                    Mono<Void> silentRetrySignal = callRetryRegistry == null || requestId == null
                            ? Mono.never()
                            : callRetryRegistry.register(requestId)
                                    .doOnSuccess(v -> silentRetryRequested.set(true));
                    return attempt.takeUntilOther(silentRetrySignal)
                            // 形态归一：把清洗后的事件分成「载荷」与「终止标记」两态。
                            // 按<strong>上游协议</strong>分类 —— 本类发的就是 Anthropic 的事件。
                            // 放在此处而非更外层：静默重发的那一轮也要经过分类。
                            .map(data -> UpstreamEventClassifier.classify(
                                    objectMapper, WireProtocol.MESSAGES, data));
                })
                .concatWith(Flux.defer(() -> {
                    if (silentRetryRequested.compareAndSet(true, false)) {
                        log.info("静默重试：重新发起 Anthropic 上游请求 [{}] {}", model, requestId);
                        return attemptLoopRef.get();
                    }
                    return Flux.<UpstreamEvent>empty();
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
                                capturedRespHeaders.get(), statusCode, logChunks, attemptStart.get(),
                                chunkRewriter, ctx);
                        long ttfb = ttfbMs.get();
                        saveUsage(logId, providerKey, modelName, stream, archivedUsageRaw.get(),
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
     * <p>Anthropic 唯一特有的是必须带 {@code anthropic-version} 头。鉴权头<strong>不</strong>
     * 属于这一类：它由 {@link AuthHeaderSetting} 这个供应商级配置决定头名，与本服务走哪个
     * 协议无关（依据与反面证据见
     * {@code ProviderRequestHeaderService.applyAuthenticationHeaders}）。
     * 因此这条线路上出站的可能是 {@code x-api-key}，也可能是 {@code Authorization: Bearer}。
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
                    // 复用共享的请求头装配（下游头透传白名单、hop-by-hop 排除、鉴权头装配、
                    // 供应商头规则含 {apiKey} 占位与删除标记）。
                    // 出站鉴权头由供应商级配置决定，与本服务的协议无关 —— 「走 Anthropic」
                    // 不代表该发 x-api-key，头名取决于用户配的「取下游 / 取设置」与承载方式。
                    providerRequestHeaderService.applyHeaders(
                            headers, downstreamHeaders, apiKey, provider.headerRulesJson(), stream,
                            AuthHeaderSetting.parse(provider.authHeaderJson(), objectMapper));
                    // Anthropic 必需的版本头。放在 applyHeaders 之后，
                    // 使供应商头规则仍可覆盖它（某些中转站要求特定版本）。
                    if (!headers.containsKey(ANTHROPIC_VERSION_HEADER)) {
                        headers.set(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION_VALUE);
                    }
                })
                .filter((request, next) -> {
                    capturedHeaders.clear();
                    capturedHeaders.putAll(providerRequestHeaderService.createLogSnapshot(request.headers()));
                    return next.exchange(request);
                }).build();
    }

    /**
     * 准备 Anthropic 请求体 —— <strong>一条显式阶段序列</strong>。
     *
     * <h2>阶段序列（顺序有语义）</h2>
     * <pre>
     * 1. 复制           copyRequestBody          主干会逐阶段改写 body
     * 2. 解析模型名     resolveModel             后续阶段都要用它查配置
     * 3. 写协议字段     writeProtocolFields      主干自己决定的 model 与 stream
     * 4. 协议归一化     normalizeRequest         system 提取 + max_tokens 补齐
     * 5. 思考两维       applyThinkingDimensions  <b>深度先、方式后</b>，off 档跳过方式
     * 6. 剥兼容副本     dropReasoningEffortAlias 必须在阶段 5 <b>之后</b>
     * 7. 请求体规则     applyBodyRules           协议筛选由引擎完成
     * 8. 清 null        removeNullFields         <b>必须是最后一步</b>
     * </pre>
     * 本线路的阶段最多：阶段 4 与阶段 5/6 都是「下游说 Chat、上游说 Anthropic」留下的债。
     *
     * <h2>三处与 OpenAI 的硬差异（阶段 4/5 的内容）</h2>
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
     * <h2>两个思考维度的施加顺序不可交换（阶段 5）</h2>
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
     * <h2>请求体转换规则的执行位置（阶段 7）</h2>
     * 规则在协议归一化<strong>之后</strong>执行（{@code system} 已提到顶层、
     * {@code max_tokens} 已补齐），因为规则的字段路径是照最终发往上游的形态写的 ——
     * 若在归一化前执行，用户看到的预览与实际请求体结构不一致。
     *
     * <p>但要在 {@code removeIf(Objects::isNull)} 之前：规则可能把某个字段显式设为 null，
     * 而 Anthropic 对多余的 null 字段并不宽容，最终清洗必须是链条的最后一步。
     *
     * <p>协议筛选由引擎完成：只有声明适用 {@link WireProtocol#MESSAGES} 的规则组才会执行。
     * 库里那些照 OpenAI 结构写的旧规则被归一为「仅 OPENAI」，因此不会在此静默匹配失败。
     */
    private Map<String, Object> prepareRequestBody(Map<String, Object> request, boolean stream,
                                                   String model, ProviderRuntimeConfiguration provider,
                                                   RequestPipelineContext ctx) {
        // 阶段序列 —— 顺序有语义，逐步理由见各阶段方法自己的注释：
        //   1. 复制                 copyRequestBody         主干会逐阶段改写 body
        //   2. 解析模型名           resolveModel            后续阶段都要用它查配置
        //   3. 写协议字段           writeProtocolFields     主干自己决定的 model 与 stream
        //   4. system 抬升          【支线】按 bodyProtocol 查表，未命中即跳过
        //   5. max_tokens 补齐      【支线】同上
        //   6. 思考注入             【支线】同上（含剥 reasoning_effort 兼容副本）
        //   7. 请求体规则           applyBodyRules          主干（协议差异在规则数据里）
        //   8. 清 null              removeNullFields        必须是最后一步
        //
        // 阶段 6 早先拆成「思考两维」与「剥兼容副本」两步，现已合并：那个副本的生命周期
        // **完全由思考注入支配**（供其判定「下游已表态」），是它的内部临时产物。
        //
        // 查表键用 **bodyProtocol** 而非 upstreamProtocol：它描述的是「手里这份 body 长什么样」，
        // 而三个支线读写的正是 body 的字段形态。
        // ⚠️ 3.4 已把 translate 移进主干，二者**已经分道扬镳**：端点建 ctx 时 bodyProtocol
        //    初值 = downstream（body 还是下游形态），主干 translate 才把它换成上游形态。
        //    因此 C2M 路线下查表拿到的是**上游**实现，而直连时两者恒等 —— 与本节语义一致。
        //    （注释原文写「当前两者恒等……等 3.4 后分道扬镳」，那是在 3.4 之前写的，已过期。）
        Map<String, Object> body = copyRequestBody(request);
        String resolvedModel = resolveModel(body.get("model"), model);
        writeProtocolFields(body, resolvedModel, stream);

        WireProtocol bodyProtocol = ctx.bodyProtocol();
        // 未命中即跳过：这表达「这种协议没有这个步骤」（如 Chat 不需要抬升 system），
        // 是合法结果而非错误。装配漏了则由 RequestBodyStageSpringWiringTests 的结构断言兜住。
        requestBodyStageRegistry.findSystemPromptStage(bodyProtocol)
                .ifPresent(stage -> stage.apply(body));
        requestBodyStageRegistry.findMaxTokensStage(bodyProtocol)
                .ifPresent(stage -> stage.apply(body, resolvedModel, provider));
        requestBodyStageRegistry.findThinkingStage(bodyProtocol)
                .ifPresent(stage -> stage.apply(body, resolvedModel, provider));

        applyBodyRules(body, provider);
        removeNullFields(body);
        return body;
    }

    /**
     * 阶段 1：复制请求体 —— 理由同 OpenAI 侧。
     */
    private static Map<String, Object> copyRequestBody(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    /**
     * 阶段 3：写入主干自己决定的协议字段 —— 理由同 OpenAI 侧。
     */
    private static void writeProtocolFields(Map<String, Object> body, String resolvedModel, boolean stream) {
        body.put("model", resolvedModel);
        body.put("stream", stream);
    }

    /**
     * 阶段 7：清掉所有值为 {@code null} 的字段 —— <strong>必须是链条的最后一步</strong>。
     *
     * <p>理由与 OpenAI 侧相同：规则可能把字段显式设为 null，而 Anthropic
     * 对多余的 null 字段并不宽容。
     */
    private static void removeNullFields(Map<String, Object> body) {
        body.values().removeIf(Objects::isNull);
    }

    /**
     * 执行适用于 Anthropic 线路的请求体规则组。
     *
     * <p>引擎返回新 Map 而非原地修改，这里原地替换内容以保留调用方持有的引用。
     *
     * <p><strong>本步骤是主干而非支线</strong>：协议差异在**规则数据**里
     * （{@code groups[].protocols}），引擎只是照着筛 —— 加一个协议不需要改代码，
     * 只需写一条新规则。按方向文档 §2.1 的判据（差异是数据 → 主干）它属主干。
     */
    private void applyBodyRules(Map<String, Object> body, ProviderRuntimeConfiguration provider) {
        RequestBodyRuleEngine.TransformResult result = requestBodyRuleEngine.transform(
                body, provider.bodyRulesJson(), WireProtocol.MESSAGES);
        body.clear();
        body.putAll(result.output());
        for (RequestBodyRuleEngine.TransformWarning warning : result.warnings()) {
            log.warn("[Anthropic] 请求体规则已跳过: ruleId={}, path={}, message={}",
                    warning.ruleId(), warning.fieldPath(), warning.message());
        }
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

    /**
     * 解析出真实的上游模型名（剥除供应商前缀）。与另两条线路同一语义。
     *
     * <h2>两个来源不是「回退关系」，是同一个值的两条路</h2>
     * {@code requestModel} 来自请求体（控制器会写进去），{@code routedModel} 来自路由解析。
     * 生产路径上二者同值；测试常只给其中一个（本类的测试构造的请求体只有 {@code messages}，
     * 模型名完全来自路由参数），故两个都要接受。
     *
     * <h2>剔除了的是第三层：可枚举的默认模型名（3.3a）</h2>
     * 从前这里还接受一个 {@code fallbackModel}，并在两者都为空时返回空串。
     * 那是「特定供应商」时代的产物（当年有可枚举的回退模型名），现在是范式供应商，
     * 那些名字一个都不存在了。现在两者都为空时<strong>显式抛错</strong>：
     * 「下游必须携带 model」由路由层保证并报 400。
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
                .filter(UpstreamRetryPolicy::isRetryableFailure)
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

    // ==================== 落库与观测 ====================

    /**
     * 落库。
     *
     * <h2>上游协议恒为 ANTHROPIC，下游协议从 ctx 取</h2>
     * 直连时两者相同；翻译路线下游是 CHAT，因此日志里能看出
     * 这是一次跳协议调用。
     *
     * <p><strong>下游协议的家是 ctx，不是参数</strong>：它曾由一个
     * {@code DownstreamLogView} 参数携带，而那个字段与 {@code ctx.downstreamProtocol()}
     * 在所有调用点<strong>恒等</strong>。两个家意味着两处可能不一致，因此它已被删除
     * （3.3d-3，{@code docs/KNOWN_DEBT.md} 第十二条）。
     */
    private Long saveNonStreamLog(String providerKey, String modelName, Map<String, String> reqHeaders,
                                  Map<String, Object> requestBody, Map<String, String> respHeaders,
                                  int statusCode, String responseBody, long startTime,
                                  RequestPipelineContext ctx) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveNonStream(providerKey, modelName,
                ctx.downstreamProtocol().name(), WireProtocol.MESSAGES.name(),
                reqHeaders, requestBody, respHeaders,
                statusCode, responseBody, duration);
    }

    /**
     * 流式落库。
     *
     * <p>chunk 过一道改写器：翻译路线下日志要记<strong>下游实际收到的</strong>
     * OpenAI chunk，而不是上游的 Anthropic 事件 —— 否则排查「客户端为何解析失败」
     * 时，日志里没有客户端真正看到的东西。直连时改写器为 {@code null}，
     * 落上游原文（裸数组）。退回语义见 {@link ChunkLogPayload#from}。
     */
    private Long saveStreamLog(String providerKey, String modelName, Map<String, String> reqHeaders,
                               Map<String, Object> requestBody, Map<String, String> respHeaders,
                               int statusCode, List<String> chunks, long startTime,
                               Function<List<String>, ChunkLogPayload> chunkRewriter,
                               RequestPipelineContext ctx) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveStream(providerKey, modelName,
                ctx.downstreamProtocol().name(), WireProtocol.MESSAGES.name(),
                reqHeaders, requestBody, respHeaders,
                statusCode, ChunkLogPayload.from(chunkRewriter, chunks), duration);
    }

    private Long saveStreamLogWithError(String providerKey, String modelName, Map<String, String> reqHeaders,
                                        Map<String, Object> requestBody, Map<String, String> respHeaders,
                                        int statusCode, List<String> chunks, Map<String, String> errorHeaders,
                                        int errorCode, String errorBody, long startTime,
                                        Function<List<String>, ChunkLogPayload> chunkRewriter,
                                        RequestPipelineContext ctx) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveStreamWithError(providerKey, modelName,
                ctx.downstreamProtocol().name(), WireProtocol.MESSAGES.name(),
                reqHeaders, requestBody, respHeaders, statusCode,
                ChunkLogPayload.from(chunkRewriter, chunks), errorHeaders,
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
     * <h2>三个 token 列过改写器，{@code usage_raw} 不过</h2>
     * 两者是两种数据：{@code usage_raw} 是<strong>上游原始报文</strong>的存档，
     * 改写它等于销毁证据；而三个 token 列是<strong>跨协议共用的归一化度量</strong>，
     * 前端与概览页求和都按下游协议解读它们。因此同一行里同时留着上游原文与
     * 下游口径的指标是有意的。
     *
     * <p>换算本身不在落库层：它只依赖上游协议（{@code AnthropicUsageParser} 完成），
     * 与下游是谁无关 —— 直连与翻译两条线路因此拿到同一口径。
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
     * 在两份 usage 原文里挑一份存档。<strong>永远是「挑一份」，不跨事件拼字段</strong>。
     *
     * <h2>为何不能直接取最后一份</h2>
     * 存在这样的上游：<strong>每一个</strong>流式事件都带完整的 usage 对象，但只有少数几个
     * 带真实数字，其余（含最后的 {@code message_stop}）全是 {@code 0}。直接取最后一份会让
     * {@code usage_raw} 存下一份全零报文 —— 实测到过的缺陷。
     *
     * <p>因此第一判据是<strong>四个输入输出字段的正值个数</strong>：严格更多才替换。
     * 不比较数值大小 —— 那会在多轮 {@code message_delta} 里挑出「输出最多」的一帧，
     * 而我们要的是「哪一帧最能说明这次调用」。
     *
     * <h2>正值个数相等时优先结算态（{@code message_delta}）</h2>
     * 实测样本：{@code message_start} 给
     * {@code {input_tokens:8077, cache_creation_input_tokens:45772}}（2 个正值），
     * {@code message_delta} 给 {@code {output_tokens:43, cache_creation_input_tokens:45772}}
     * （同样 2 个正值）。两份各缺一半，纯按个数打平。
     *
     * <p>此时取 {@code message_delta}：{@code message_start} 是<strong>预算/预估</strong>态
     * （请求刚被接收，输出还没产生），{@code message_delta} 是<strong>结算</strong>态。
     * 存档的用途是查证「这次调用最终算了多少」，结算态更贴近这个问题。
     * 打平且都不是 {@code message_delta} 时保留先到的那一份（不做无意义的抖动）。
     *
     * <h2>为何不把两个事件的字段合并成一份「完整」原文</h2>
     * 因为同名字段在两个事件里可以给出<strong>不同的值</strong>，合并会造出一份上游从未
     * 发出过的报文。cc-switch 记录的 Qwen / MiniMax 形态就是这样：{@code message_start}
     * 报 {@code input_tokens=200000 / cache_read=180000}，{@code message_delta} 改报
     * {@code 80000 / 120000} —— 两份各自<strong>自洽（配套）</strong>，逐字段挑「较大者」
     * 会拼出一份两头不搭的账。存档的价值恰恰在于它是上游原话，一旦拼接就不再是证据。
     *
     * <p>三个 token 列<strong>确实</strong>跨事件合并（{@link AnthropicUsageParser#merge}），
     * 那是度量列、要的是完整数字；存档要的是原文。两者职责不同，所以规则也不同。
     *
     * <h2>官方 Claude 形态尚未一手验证</h2>
     * 目前掌握的 {@code message_start} / {@code message_delta} 形态全部来自第三方
     * Anthropic 兼容端点（DeepSeek、MiMo、Qwen、MiniMax）与中转项目的测试夹具，
     * 各家并不一致：有的 {@code message_delta} 只带 {@code output_tokens}，
     * 有的带完整四项。因此这里选的是<strong>保守策略</strong>（挑一份 + 打平取结算态），
     * 而不是依赖任何一家的形态假设。拿到官方 key 的一手抓包后可以重新评估。
     *
     * <h2>它与三个 token 列的关系</h2>
     * 两者独立：三列由 {@link AnthropicUsageParser#merge} 跨事件合并而来，本方法只决定
     * <strong>存档哪一份原文</strong>。因此即使这里挑错，三列仍然正确 ——
     * 但存档是查证的唯一依据，挑错等于让证据失效。
     *
     * @param current 已存档的原文；{@code null} 表示还没有
     * @param incoming 新到的原文，非 null
     * @param incomingIsSettlement {@code incoming} 是否来自 {@code message_delta}
     * @return 应当存档的那一份
     */
    private String pickRicherUsageRaw(String current, String incoming, boolean incomingIsSettlement) {
        if (current == null) {
            return incoming;
        }
        int incomingPositives = countPositiveUsageFields(incoming);
        int currentPositives = countPositiveUsageFields(current);
        if (incomingPositives > currentPositives) {
            return incoming;
        }
        if (incomingPositives == currentPositives && incomingIsSettlement) {
            return incoming;
        }
        return current;
    }

    /**
     * 事件是否是结算态（{@code message_delta}）。
     *
     * <p>只认这一个类型：{@code message_start} 是预算态，{@code content_block_*} 与
     * {@code message_stop} 上的 usage 是某些上游「每事件都带一份」的副产物，都不是结算。
     * 解析失败按「不是结算」处理 —— 保守，不因一份读不懂的报文替换已有存档。
     */
    private boolean isSettlementEvent(String eventJson) {
        try {
            JsonNode type = objectMapper.readTree(eventJson).get("type");
            return type != null && "message_delta".equals(type.asText());
        } catch (Exception exception) {
            return false;
        }
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

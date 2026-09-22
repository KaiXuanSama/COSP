package com.kaixuan.copilot_ollama_proxy.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.AuthHeaderSetting;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ReasoningEffortSetting;
import com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil;
import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallRetryRegistry;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.OpenAiContentDetector;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.OpenAiUsageParser;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ChunkStageRegistry;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ContentDetectorRegistry;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ContentDetectorStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.EmptyResponseGate;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ReasoningFallbackStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.UpstreamChunkNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用 OpenAI 上游执行管道。
 *
 * 本类负责上游响应清洗、请求转换、重试和调用日志。
 *
 * 上游清洗（{@link UpstreamChunkNormalizer}）：
 *   将各上游供应商返回的格式不一致的 SSE chunk 统一为内部标准 OpenAI 格式。
 *   包括：统一 reasoning 字段名（5 种 → reasoning_content）、清理空值/空 tool_calls、
 *   统一 finish_reason 等。
 *
 * 中枢处理（在 {@link #chatCompletionStream} 的 Reactor 管道中完成）：
 *   基于清洗后的统一格式进行：reasoning fallback
 *   （无正文时回退用思考内容作为回复，见 {@link ReasoningFallback}）、API 调用日志记录。
 *
 * 运行时配置（API Key、Base URL、模型列表）由调用方显式传入。
 *
 * <h2>两条重试通道</h2>
 * 「静默」指重试发生在 COSP 内部、下游连接保持打开、Copilot 全程无感知。两条通道都是静默的，
 * 区别只在触发者与是否消耗预算：
 * <table border="1">
 *   <caption>重试通道对照</caption>
 *   <tr><th>通道</th><th>触发者</th><th>消耗 5 次预算</th><th>实现位置</th></tr>
 *   <tr><td>异常重试</td><td>COSP 自身（上游失败 / 空响应）</td><td>是</td>
 *       <td>{@link UpstreamAutoRetry} 构造的 {@code retryWhen}</td></tr>
 *   <tr><td>手动重试</td><td>用户在管理后台右键 Toast</td><td>否</td>
 *       <td>{@link UpstreamSilentRetry}（{@code CallRetryRegistry} + {@code takeUntilOther}）</td></tr>
 * </table>
 * 手动重试不消耗预算是有意的：那是用户可感知的主动操作，不该挤占自动恢复的余量。
 *
 * <h2>空响应兜底</h2>
 * 第三方中转站偶发 HTTP 200 但内容全空的响应（无正文、无思考链、无工具调用，
 * 有时连 usage 与 {@code [DONE]} 都没有）。管道在 {@code retryWhen} 内侧设一道 gate：
 * 开闸前逐帧缓存不下发，出现实质载荷即整批释放并当轮不再拦截；整轮未开闸则抛
 * {@link EmptyUpstreamResponseException}，走上面那条<strong>同一份</strong>重试预算。
 * 耗尽后把最后一轮的帧原样放行给下游，与其他失败的耗尽行为保持一致。
 * 判定口径见 {@link OpenAiContentDetector}。
 */
public abstract class AbstractUpstreamChatService {

    /** SSE 场景下，每个 data 字段的原始字符串类型引用。 */
    private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE = new ParameterizedTypeReference<>() {
    };

    /** 子类可直接使用的 logger，自动绑定到实际子类的类名。 */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** Jackson 对象映射器，用于 SSE chunk 的 JSON 解析与序列化。 */
    protected final ObjectMapper objectMapper;

    private final ProviderRequestHeaderService providerRequestHeaderService;

    /** API 调用日志写入服务，由子类 Spring Bean 通过 setter 注入。 */
    private ApiCallLogService apiCallLog;

    /** API 调用 token 用量写入服务，由 Spring 可选注入；写入独立于日志的 api_call_usage 表。 */
    private ApiCallUsageService apiCallUsage;

    /** 调用生命周期事件通知器，由 Spring 可选注入；用于发出 CONNECTED / RETRYING 等 provider 层观测点。 */
    private CallLifecycleNotifier lifecycleNotifier;

    /** 静默重试协调器，由 Spring 可选注入；管理后台右键 Toast 触发时，重新发起当前上游请求。 */
    private CallRetryRegistry callRetryRegistry;

    /**
     * 重试次数策略，由 Spring 可选注入；未注入（如单元测试）时回退到默认 5 次。
     * 只经 {@link #buildRetrySpec} 转交给 {@link UpstreamAutoRetry} ——
     * 三个执行器都从同一个 {@code RetryPolicyService} 读，因此「重试次数」只有这一个来源。
     */
    private RetryPolicyService retryPolicyService;

    /**
     * 流式 chunk 支线的查表 —— 按 {@code ctx.upstreamProtocol()} 查归一与 fallback 实现。
     *
     * <h2>3.1 的双路形态已收成单路</h2>
     * 3.1 时本类持有两个「注入的实现」字段并硬编码筛 {@code CHAT}，调用点写成
     * 「有注入就走它、没注入回退静态工具」——那是主干还没成形时的权宜之计：
     * 两条路按设计逐字等价，于是<strong>装配断了行为完全不变</strong>。
     *
     * <p>现在主干与 {@code ctx} 都在了，硬编码与双路都可以去掉：
     * 键从 ctx 取、未命中即跳过（表达「这种协议没这一步」）。
     *
     * <h2>为何是构造器参数</h2>
     * 与 {@code GenericAnthropicChatService} 的 registry 同一取向：它缺失意味着
     * 归一与 fallback 全部静默跳过，而「未命中即跳过」是<strong>正常语义</strong> ——
     * 于是「装配漏了」与「该协议没这一步」在行为上无从区分。放进构造器 → 漏传编译失败。
     */
    private final ChunkStageRegistry chunkStageRegistry;

    /**
     * 内容检测器的查表 —— 空响应拦截的判据来源（阶段 3.6b）。
     *
     * <p>与 {@link #chunkStageRegistry} 同一取舍（放构造器而非可选 setter），
     * 但后果更重：本表未命中是<strong>报错</strong>而非跳过 ——
     * 漏注入会让整条线路的空响应兼底直接失效。
     */
    private final ContentDetectorRegistry contentDetectorRegistry;

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

    /**
     * @param objectMapper Jackson 对象映射器
     * @param providerRequestHeaderService 出站请求头装配服务
     * @param chunkStageRegistry 流式 chunk 支线的查表
     * @param contentDetectorRegistry 内容检测器的查表（空响应拦截判据）
     */
    protected AbstractUpstreamChatService(ObjectMapper objectMapper,
                                          ProviderRequestHeaderService providerRequestHeaderService,
                                          ChunkStageRegistry chunkStageRegistry,
                                          ContentDetectorRegistry contentDetectorRegistry) {
        this.objectMapper = objectMapper;
        this.providerRequestHeaderService = providerRequestHeaderService;
        this.chunkStageRegistry = chunkStageRegistry;
        this.contentDetectorRegistry = contentDetectorRegistry;
    }

    /**
     * 发送一次非流式 Chat Completions 请求。
     *
     * 请求体会经过 {@link #prepareRequestBody} 处理，包括模型名称解析、
     * stream 标志设置和子类的自定义字段注入。
     *
     * <h2>空响应兜底</h2>
     * 与流式共用同一份重试预算：空响应被包成 {@link EmptyUpstreamResponseException} 抛出，
     * 走的是下方同一条 {@code retryWhen}，而那条规格由 {@link UpstreamAutoRetry} 统一构造，
     * 因此「重试次数」在三条线路上始终只有一个来源。判定挂在 {@code retryWhen} <strong>内侧</strong>（在 {@code doOnNext} 落库之后）
     * 才能触发重发；耗尽后由 {@code onErrorResume} 把最后一轮的原始 body 放行给下游，
     * 与其他失败「耗尽后透传最后一次响应」保持一致。
     *
     * <p>非流式无需流式那套 gate 的缓存-释放机制：一次拿到完整 body 直接判即可。
     * 落库也比流式省事 —— 流式的帧被 gate 拦在上游、{@code logChunks} 是空的，
     * 必须靠异常携带缓存帧才能落库；非流式的 {@code saveNonStreamLog} 已经把完整 body 写进去了。
     *
     * <p>上下文决定空响应拦截是否介入 —— 判据与理由见
     * {@link RequestPipelineContext#shouldApplyEmptyResponseGate()}。
     *
     * @param openAiRequest 原始 OpenAI 格式请求体
     * @param model 请求中指定的模型名称
     * @param ctx 本次请求的管道上下文，由编排层在组装期填好
     * @return 统一形态的上游响应（单个 {@link UpstreamEvent.Body}）
     */
    protected Mono<UpstreamEvent> chatCompletion(Map<String, Object> openAiRequest, String model,
                                                 ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                                 String requestId, RequestPipelineContext ctx) {
        // stream 取自 ctx（3.5a）：主干按 ctx.stream() 选了本方法，故这里二者必定一致。
        // 写成字面量会让「哪条链被调了」与「body 里的 stream」两处各自为政。
        // ⚠️ 这条一致性依赖调用方守规矩：直接调本方法而 ctx 里 stream=false 会走错路且不响。
        //    该不变式在 3.5b（两态合链）后自然消失（与 executeStream 同属搁置项）。
        boolean stream = ctx.stream();
        Map<String, Object> requestBody = prepareRequestBody(openAiRequest, stream, model, provider);
        log.info("{} OpenAI 上游，模型: {}, 流式: {}", provider.providerKey(), requestBody.get("model"), stream);

        // 拦截是否介入：请求级事实，故在 defer 之外算一次 —— 重试不改变它的值。
        // 见 RequestPipelineContext 的「生命周期」注释：写进 defer 里会让半实现态在第二轮又走回判空重试。
        // 判定机制收归 EmptyResponseGate（阶段 3.6b），检测器按上游协议查表。
        EmptyResponseGate<String> gate = new EmptyResponseGate<>(ctx.shouldApplyEmptyResponseGate());
        EmptyResponseGate.CallContext callCtx = new EmptyResponseGate.CallContext(
                provider.providerKey(), model, requestId);
        ContentDetectorStage detector = contentDetectorRegistry.require(ctx.upstreamProtocol());

        String providerKey = provider.providerKey();
        String modelName = (String) requestBody.get("model");
        Map<String, String> reqHeaders = new LinkedHashMap<>();
        // 每次上游往返（含重试）各自计时并落库：往返开始时刷新起点，使每条日志的 duration 反映该次往返本身。
        AtomicLong attemptStart = new AtomicLong(System.currentTimeMillis());

        // 非流式在本形态下就是「恰有一个元素的流」——统一后主干只需面对一种输入。
        return Mono.defer(() -> {
                    attemptStart.set(System.currentTimeMillis());
                    return buildWebClientWithHeaders(reqHeaders, provider, downstreamHeaders, stream)
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
                    saveUsageIfPresent(logId, providerKey, modelName, stream, entity.getBody(), null);
                })
                // 失败往返：每次失败（含被 retry 吞掉的中间失败）都各自落一条（在 retry 上游）。
                .doOnError(e -> {
                    WebClientResponseException responseException = UpstreamRetryPolicy.findWebResponseException(e);
                    if (responseException != null) {
                        Map<String, String> errHeaders = new LinkedHashMap<>();
                        responseException.getHeaders().forEach((k, v) -> errHeaders.put(k, String.join(", ", v)));
                        saveNonStreamLog(providerKey, modelName, reqHeaders, requestBody, errHeaders,
                                responseException.getStatusCode().value(), responseException.getResponseBodyAsString(), attemptStart.get());
                        // 失败往返不写用量行，故落库流程到此即完 —— 直接宣告就绪，
                        // 否则错误行永远不会实时出现在前端。
                        publishCallRecorded();
                    } else {
                        saveNonStreamLog(providerKey, modelName, reqHeaders, requestBody, Map.of(), -1, null, attemptStart.get());
                        publishCallRecorded();
                    }
                })
                // ── 空响应兜底 ────────────────────────────────────────────────
                // 挂在 retryWhen 内侧、doOnNext 落库之后：落库先行保证「上游到底返回了什么」
                // 在日志里可查，判定随后才有资格触发重发。
                // 机制收归 EmptyResponseGate（阶段 3.6b）：判定、转异常、跳过留痕都在它那里。
                .flatMap(entity -> EmptyResponseGate
                        .checkNonStream(entity.getBody(), gate.active(), detector, log, callCtx)
                        .thenReturn(entity))
                // 重试挂在落库下游：中间失败已在上面各自记录，此处仅负责重订阅。
                .retryWhen(buildRetrySpec("chatCompletion", provider, requestId, modelName, stream))
                // 取出响应体。空 body 场景已在上面被判空转成异常，走不到这里，
                // 故此处不会再出现 getBody() 为 null 导致 Reactor 抛 NPE 的情况 ——
                // 那个 NPE 曾让「200 + 空 body」被误报成「无法连接到上游服务」的 502。
                .map(entity -> entity.getBody())
                // 空响应重试耗尽：把最后一轮的原始 body 原样放行给下游，与其他失败
                // 「耗尽后透传最后一次响应」一致 —— 至少让下游看到上游真实返回了什么。
                // 解包与告警文案收归 EmptyResponseGate（机制共用），本处只负责「怎么放行」。
                // 该轮已在上面的 doOnNext 落过库，此处不重复落库。
                .onErrorResume(error -> {
                    Optional<List<String>> exhausted = EmptyResponseGate.exhaustedFrames(error);
                    if (exhausted.isEmpty()) {
                        return Mono.error(error);
                    }
                    List<String> frames = exhausted.get();
                    EmptyResponseGate.logExhaustedPassthrough(log, callCtx, frames.size());
                    // 空 body 场景 frames 为空：给下游一个空响应体，保持「透传上游真实返回」语义。
                    return Mono.just(frames.isEmpty() ? "" : frames.get(0));
                })
                // reasoning 清洗与 fallback：与流式对齐，统一 5 个兼容字段名到 reasoning_content，
                // 并在「只有思考链没正文」时把思考内容转为正文。
                // 放在兜底之后：判定看的是上游原始形态（与流式 gate 判原始帧同理），
                // 清洗只影响交给下游的内容。
                //
                // 归一化排在耗尽放行<strong>之后</strong>（与重构前一致）：那条路径放的也是上游 body，
                // 同样要过一遍清洗。若把它挪到前面，耗尽后透传的内容会绕开清洗。
                //
                // 末尾包装成统一形态：非流式在本形态下就是「恰有一个元素的流」。
                // 直接用 {@code body} 而不走 {@code UpstreamEventClassifier.classify}：
                // 非流式的响应体里不存在协议级终止标记（三个协议都是），
                // 「说完了」由流的 onComplete 表达 —— 这是已确定的事实，不必运行时再判一次。
                .map(body -> UpstreamEvent.body(normalizeNonStreamResponse(body, model)));
    }

    /**
     * 清洗非流式响应：统一 reasoning 字段名，并在只有思考链时回退为正文。
     *
     * <p>与流式的 {@link UpstreamChunkNormalizer#normalize} 对齐，但简单得多 ——
     * 流式要跨帧累积 {@code contentEmitted} / {@code reasoningBuffer} 才能在流末判断
     * 是否需要 fallback，非流式一次就拿到完整 {@code message}，判断是当场完成的。
     *
     * <p><strong>为何本方法留在本类而不随清洗一起搬走</strong>：
     * 它遍历<strong>所有</strong> {@code choices}（流式只看第一个），
     * 且 fallback 是把思考内容填进已有的 {@code message.content}（流式是补一对伪 chunk）。
     * 两者形状不同，共用不了 {@code UpstreamChunkNormalizer} 的入口；
     * 能共用的只有「别名字段统一」这一小段，已改为调它的
     * {@link UpstreamChunkNormalizer#extractReasoning} 与
     * {@link UpstreamChunkNormalizer#hasReasoningAliasKey}。
     *
     * <p>解析失败原样返回：与判定器「结构未知保守放行」同一取向 —— 透传对上游格式差异
     * 免疫是非流式当前的优势，不该因为清洗而引入结构约束。
     *
     * @param body  上游原始响应体
     * @param model 模型名（仅用于日志）
     * @return 清洗后的 JSON 字符串；无需改动或解析失败时返回原串
     */
    @SuppressWarnings("unchecked")
    private String normalizeNonStreamResponse(String body, String model) {
        if (body == null || body.isBlank()) {
            return body;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(body, Map.class);
            Object choicesObj = root.get("choices");
            if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
                return body;
            }
            boolean changed = false;
            for (Object choiceObj : choices) {
                if (!(choiceObj instanceof Map<?, ?> choiceRaw)) {
                    continue;
                }
                Map<String, Object> choice = (Map<String, Object>) choiceRaw;
                if (!(choice.get("message") instanceof Map<?, ?> messageRaw)) {
                    continue;
                }
                Map<String, Object> message = (Map<String, Object>) messageRaw;
                // 先记下是否带别名字段：extractReasoning 会顺手移除它们（含值为空的），
                // 那本身就是一次改动，漏记会让清洗结果不被写回。
                boolean hadAliasKey = UpstreamChunkNormalizer.hasReasoningAliasKey(message);
                // 统一 reasoning 字段名到 reasoning_content（上游各家命名不统一）。
                String reasoning = UpstreamChunkNormalizer.extractReasoning(message);
                if (reasoning != null && !reasoning.isBlank()) {
                    message.put("reasoning_content", reasoning);
                    changed = true;
                } else if (hadAliasKey) {
                    changed = true;
                }
                // reasoning fallback：只有思考链没有正文时，把思考内容作为回复输出，
                // 否则下游会看到一次「空回复」。与流式同一策略。
                boolean contentEmpty = !(message.get("content") instanceof String content) || content.isEmpty();
                if (contentEmpty && reasoning != null && !reasoning.isBlank()) {
                    log.warn("模型未输出正文，回退使用思考内容作为回复 (长度: {}) [{}]", reasoning.length(), model);
                    message.put("content", reasoning);
                    changed = true;
                }
            }
            return changed ? objectMapper.writeValueAsString(root) : body;
        } catch (Exception exception) {
            // 结构未知：原样透传，不因清洗失败影响正常响应。
            return body;
        }
    }

    /**
     * 发送一次流式 Chat Completions 请求。
     *
     * 该方法会把上游返回的 SSE data 解包为纯 JSON 字符串流，
     * 并在流式过程中处理 reasoning_content 的提取与回退：
     * 如果模型只输出了思考内容而没有正文，则在流末尾自动把思考内容作为回复输出。
     *
     * <p>上下文决定空响应拦截是否介入 —— 判据与理由见
     * {@link RequestPipelineContext#shouldApplyEmptyResponseGate()}。
     *
     * @param openAiRequest 原始 OpenAI 格式请求体
     * @param model 请求中指定的模型名称
     * @param ctx 本次请求的管道上下文，由编排层在组装期填好
     * @return 按顺序发出的 chunk JSON 字符串，最后一个元素为 "[DONE]"
     */
    protected Flux<UpstreamEvent> chatCompletionStream(Map<String, Object> openAiRequest, String model,
                                                       ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                                       String requestId, RequestPipelineContext ctx) {
        // stream 取自 ctx（3.5a）—— 它是请求级事实，与「哪条链被调了」同源，不留字面量。
        // （3.5b 两态合链已搁置，故此处不再以后续步骤为由。）
        boolean stream = ctx.stream();
        Map<String, Object> requestBody = prepareRequestBody(openAiRequest, stream, model, provider);
        log.info("{} OpenAI 上游，模型: {}, 流式: {}", provider.providerKey(), requestBody.get("model"), stream);

        // 拦截是否介入：请求级事实，故在 defer 之外算一次 —— 重试不改变它的值。
        // 见 RequestPipelineContext 的「生命周期」注释：写进 defer 里会让半实现态在第二轮又走回判空重试。
        // 机制收归 EmptyResponseGate（阶段 3.6b）：闸门状态与缓存帧都在它那里，
        // 本类只负责每轮 defer 内调 reset()。
        //
        // ⚠️ 本线路的闸门元素是 {@code ServerSentEvent<String>} 而非裸 data ——
        // 因为 gate 挂在下面 {@code mapNotNull(ServerSentEvent::data)} 之前，
        // 扣住的是整个 SSE 信封（保留 event/id/retry 字段）。
        // EmptyResponseGate 为此做成泛型，用 dataOf 取出判定用的字符串、元素原样扣放。
        EmptyResponseGate<ServerSentEvent<String>> gate =
                new EmptyResponseGate<>(ctx.shouldApplyEmptyResponseGate());
        EmptyResponseGate.CallContext callCtx = new EmptyResponseGate.CallContext(
                provider.providerKey(), model, requestId);
        ContentDetectorStage detector = contentDetectorRegistry.require(ctx.upstreamProtocol());

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
        // 空响应耗尽放行标记：该轮已在 doOnError 落过库，收尾处据此跳过，避免同一轮记两条。
        AtomicBoolean emptyResponsePassthrough = new AtomicBoolean(false);

        // 单次上游尝试：状态由本类维护（重置计时、清收集、重置闸门），
        // 中断与再发起由 UpstreamSilentRetry 负责（见下方循环）。
        Flux<ServerSentEvent<String>> rawAttempt = Flux.defer(() -> {
                    // 本次往返起点：重置计时与 chunk 收集，使每条日志只反映该次往返（不跨重试累加）。
                    attemptStart.set(System.currentTimeMillis());
                    logChunks.clear();
                    ttfbMs.set(-1);
                    usageRaw.set(null);
                    // 闸门状态同理每轮重置（机制在 EmptyResponseGate 里，理由见它自己的注释）。
                    gate.reset();
                    return buildWebClientWithHeaders(reqHeaders, provider, downstreamHeaders, stream)
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
                                        // 失败往返不写用量行，落库流程到此即完。
                                        publishCallRecorded();
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
                // ── 空响应 gate ──────────────────────────────────────────────
                // 机制收归 EmptyResponseGate（阶段 3.6b）：扣住 / 整批释放 / 轮末判空
                // 三条线路共用一份实现，本类只提供检测器与「怎么从帧里取 data」。
                // 挂在 retryWhen 内侧，因此每轮重订阅各自独立判定；闸门状态已在上面的 defer 内 reset。
                // 与另两条的唯一差别：本线路扣的是 SSE 信封（见上方 gate 声明处的说明），
                // 故 dataOf 传 ServerSentEvent::data（另两条传 Function.identity()）。
                //
                // 首字打点必须在 gate <strong>之前</strong>：语义是"首 chunk"而非"首正文" ——
                // 只要上游吐了帧就算测得，不因该帧被 gate 暂扣而延后。
                // （另两条线路的打点同样在各自 gate 之前，位置一致。）
                .doOnNext(frame -> {
                    if (ttfbMs.get() < 0) {
                        ttfbMs.set(System.currentTimeMillis() - attemptStart.get());
                    }
                })
                .transform(flux -> gate.gate(flux, ServerSentEvent::data, detector, log, callCtx))
                // 网络类失败往返（无上游错误响应，如连接失败 / HTTP 200 后流中途断开）：即时落一条记录。
                // 错误响应（4xx/5xx）已在 exchangeToFlux 分支落库，
                // 此处用 UpstreamRetryPolicy.findWebResponseException == null 排除以免重复。
                .doOnError(e -> {
                    Optional<List<String>> exhausted = EmptyResponseGate.exhaustedFrames(e);
                    if (exhausted.isPresent()) {
                        // 空响应往返：帧被 gate 拦在上游，logChunks 是空的 —— 必须改用异常携带的缓存帧落库，
                        // 否则日志只剩「200 且零 chunk」，恰恰在最该看清上游吐了什么的场景下什么都看不到。
                        saveStreamLog(providerKey, modelName, reqHeaders, requestBody,
                                capturedRespHeaders.get(), capturedStatusCode.get(),
                                exhausted.get(), attemptStart.get());
                        // 空响应往返不写用量行（无 usage 可言），落库流程到此即完。
                        publishCallRecorded();
                        return;
                    }
                    if (UpstreamRetryPolicy.findWebResponseException(e) == null) {
                        int statusCode = capturedStatusCode.get() == 0 ? -1 : capturedStatusCode.get();
                        saveStreamLog(providerKey, modelName, reqHeaders, requestBody,
                                capturedRespHeaders.get(), statusCode, List.copyOf(logChunks), attemptStart.get());
                        publishCallRecorded();
                    }
                })
                // 异常重试：空响应与 429 / 5xx / 网络中断共用这一条预算 —— 空响应被包成
                // EmptyUpstreamResponseException 抛出，UpstreamRetryPolicy.isRetryableFailure 认它，
                // 因此无需第二套重试实现。
                // 与之相对，手动静默重试走 takeUntilOther 的正常完成，不经过 retryWhen，故不消耗预算。
                .retryWhen(buildRetrySpec("chatCompletionStream", provider, requestId, model, stream))
                // 空响应重试耗尽：把最后一轮被拦下的帧原样放给下游，与其他失败「耗尽后透传最后一次响应」
                // 保持一致 —— 至少让下游看到上游真实返回了什么，而不是收到一个 500。
                // 该轮已在上面的 doOnError 落库，故放行后由 emptyResponsePassthrough 让收尾跳过重复落库。
                // 注意用 UpstreamRetryPolicy.findEmptyUpstreamException 解包而非按类型匹配：
                // retryWhen 耗尽时原异常被包进 RetryExhaustedException，onErrorResume(Class) 匹配不到。
                .onErrorResume(error -> {
                    EmptyUpstreamResponseException emptyResponse = UpstreamRetryPolicy.findEmptyUpstreamException(error);
                    if (emptyResponse == null) {
                        return Flux.error(error);
                    }
                    log.warn("{} 上游空响应重试耗尽，放行最后一轮的 {} 帧给下游 [{}] {}",
                            provider.providerKey(), emptyResponse.bufferedFrames().size(), model, requestId);
                    emptyResponsePassthrough.set(true);
                    return Flux.fromIterable(emptyResponse.bufferedFrames())
                            .map(data -> ServerSentEvent.builder(data).build());
                });

        // 静默重试循环：机制收归 UpstreamSilentRetry（阶段 3.6c-1），三条线路共用一份实现。
        // 本线路是唯一传 {@code ServerSentEvent} 的（与 EmptyResponseGate 泛型同理）——
        // 循环挂在 mapNotNull 之前，而另两条挂在其后。
        Flux<ServerSentEvent<String>> attemptLoop =
                UpstreamSilentRetry.loop(rawAttempt, callRetryRegistry, requestId, "OpenAI", log, model);

        return attemptLoop
                .mapNotNull(ServerSentEvent::data).filter(chunk -> !chunk.isBlank() && !"null".equals(chunk))
                .doOnNext(raw -> {
                    log.debug("{} 上游原始: {}", provider.providerKey(), raw);
                    // 首字打点已移到空响应 gate 处（retryWhen 内侧、拦截判定之前），
                    // 以免被 gate 暂扣的帧让 ttfb 虚高。语义仍是"首 chunk"而非"首正文"，
                    // 故纯思考、纯工具调用等无正文响应同样能测得。
                    // 从上游原始 chunk 提取 usage 原始 JSON（通常在尾 chunk）；有则记录供成功收尾落库。
                    String rawUsage = OpenAiUsageParser.extractUsageRawJson(objectMapper, raw);
                    if (rawUsage != null) {
                        usageRaw.set(rawUsage);
                    }
                }).concatMap(chunk -> {
                    // 上游形态归一：统一 reasoning 字段名 / finish_reason / 剪空。
                    // 【支线】按**上游协议**查表 —— 归一的输入是上游原始 chunk，因此
                    // 「上游发来什么形态」才是判据。用 bodyProtocol 会在 C2M 下错：
                    // 那时 body 已是 Anthropic 形态，而归一处理的帧属于上游协议。
                    // 未命中即跳过 —— 表达「这种协议没有归一这一步」（Anthropic / Responses
                    // 的事件结构本就由各自协议规定，不存在同义字段名问题）。
                    String normalizedChunk = chunkStageRegistry.findNormalizer(ctx.upstreamProtocol())
                            .map(stage -> stage.normalize(chunk, contentEmitted, reasoningBuffer, chunkId))
                            .orElse(chunk);
                    // reasoning fallback：只有思考链没有正文时，用思考内容补一对伪 chunk。
                    // 触发判定（含「纯工具调用不触发」）见 ReasoningFallbackStage / ReasoningFallback。
                    // 同样按上游协议查表、未命中即跳过。
                    ReasoningFallbackStage fallbackStage =
                            chunkStageRegistry.findFallback(ctx.upstreamProtocol()).orElse(null);
                    if (fallbackStage != null
                            && fallbackStage.shouldFallback(normalizedChunk, contentEmitted, reasoningBuffer)) {
                        log.warn("模型未输出正文，回退使用思考内容作为回复 (长度: {})", reasoningBuffer.length());
                        List<String> frames = fallbackStage.buildFallbackFrames(
                                chunkId.get(), model, reasoningBuffer.toString());
                        return Flux.fromIterable(frames);
                    }
                    return Flux.just(normalizedChunk);
                }).doOnNext(chunk -> {
                    log.debug("{} 上游清洗: {}", provider.providerKey(), chunk);
                    logChunks.add(chunk);
                })
                // 形态归一：把清洗后的字符串分成「载荷」与「终止标记」两态。
                // 放在清洗<strong>之后</strong>：清洗会改写 chunk（含它内部的 [DONE] 直通分支），
                // 分类必须看最终要下发的那份内容。
                .map(chunk -> UpstreamEventClassifier.classify(objectMapper, WireProtocol.CHAT, chunk))
                // 成功往返收尾：仅在非错误终结（complete / cancel）时落一条成功记录。
                // 失败往返（错误响应 / 网络失败）已在 retry 上游即时落库，此处 ON_ERROR 不重复。
                .doFinally(signal -> {
                    if (callRetryRegistry != null && requestId != null) {
                        callRetryRegistry.remove(requestId);
                    }
                    // 空响应耗尽放行：该轮已在 doOnError 用缓存帧落过库，此处再落一条会重复。
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
                        // 写入时序 A（串联）：仅成功且有 usage 时写用量表；log_id 拿不到则降级为孤儿行。
                        long ttfb = ttfbMs.get();
                        saveUsage(logId, providerKey, modelName, stream, usageRaw.get(),
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
                // 出站鉴权头由供应商级配置决定，与本管道的协议无关 —— 头名取决于用户配的
                // 「取下游 / 取设置」与承载方式，而不是「这里走 Chat」。
                providerRequestHeaderService.applyHeaders(
                    headers, downstreamHeaders, apiKey, provider.headerRulesJson(), stream,
                    AuthHeaderSetting.parse(provider.authHeaderJson(), objectMapper));
        }).filter((request, next) -> {
            capturedHeaders.clear();
            capturedHeaders.putAll(providerRequestHeaderService.createLogSnapshot(request.headers()));
            return next.exchange(request);
        }).build();
    }

    /**
    * 准备请求体 —— <strong>一条显式阶段序列</strong>。
     *
     * <h2>阶段序列（顺序有语义）</h2>
     * <pre>
     * 1. 复制           copyRequestBody        主干会逐阶段改写 body
     * 2. 解析模型名     resolveModel           后续阶段都要用它查配置
     * 3. 写协议字段     writeProtocolFields    主干自己决定的 model 与 stream
     * 4. 思考深度       applyReasoningEffort   覆写 / 兜底 / 透传 / 删除四档
     * 5. 协议特定步骤   customizeRequestBody   子类钩子（本线路是请求体规则）
     * 6. 清 null        removeNullFields       <b>必须是最后一步</b>
     * </pre>
     * 三条线路共用阶段 1/2/3/6（形状相同），差异在中间：本线路（Chat）只有思考深度与规则；
     * {@code GenericAnthropicChatService} 多一步协议归一化（system 提取 + max_tokens 补齐）
     * 与思考的第二维；{@code GenericResponsesChatService} 与本节一致。
     *
     * <h2>为何 null 清洗必须在规则之后</h2>
     * 两件事都依赖这个顺序：
     * <ol>
     *   <li><strong>规则产生的 null 不能发给上游。</strong>「设置字段值」留空即置 null，
     *       若先清洗后执行规则，那个 null 会原样出站；而部分上游对多余的 null 字段并不宽容。</li>
     *   <li><strong>规则看到的输入要与编辑器预览一致。</strong>预览里规则直接作用于用户粘贴的
     *       请求体，不做任何 null 剥离；若运行时先清洗，同一条 {@code exists} 条件就会
     *       「预览命中、线上不命中」—— 预览一旦会说谎，它的全部价值就没了。</li>
     * </ol>
     *
     * <p>与 {@code GenericAnthropicChatService.prepareRequestBody} 的顺序保持一致 ——
     * 两侧都是「协议归一化 → 规则 → null 清洗」。这不是巧合而是必须：同一条规则在两条线路上
     * 应当产生同一种结果，否则「换个协议试试」会得到无法解释的差异。
     *
     * @param openAiRequest 请求体的初始 Map 结构
     * @param stream 是否启用流式响应
     * @param model 模型名称
     * @return 最终准备好的请求体 Map 结构，已经解析了模型名称并设置了流式标志
     */
    protected Map<String, Object> prepareRequestBody(Map<String, Object> openAiRequest, boolean stream, String model,
                                                      ProviderRuntimeConfiguration provider) {
        // 阶段序列 —— 顺序有语义，逐步理由见各阶段方法自己的注释：
        //   1. 复制           copyRequestBody       主干会逐阶段改写 body
        //   2. 解析模型名     resolveModel          后续阶段都要用它查配置
        //   3. 写协议字段     writeProtocolFields   主干自己决定的 model 与 stream
        //   4. 思考深度       applyReasoningEffort  覆写 / 兜底 / 透传 / 删除四档
        //   5. 协议特定步骤   customizeRequestBody  子类钩子，本线路是请求体规则
        //   6. 清 null        removeNullFields      必须是最后一步
        Map<String, Object> body = copyRequestBody(openAiRequest);
        String resolvedModel = resolveModel(body.get("model"), model);
        writeProtocolFields(body, resolvedModel, stream);
        applyReasoningEffort(body, resolvedModel, provider);
        customizeRequestBody(body, resolvedModel, provider);
        removeNullFields(body);
        return body;
    }

    /**
     * 阶段 1：复制请求体。
     *
     * <p>主干会逐阶段改写它（写模型名、设 stream、注入思考、执行规则），
     * 因此不能把调用方持有的那个 Map 直接交出去 —— 那会让一次请求的准备过程
     * 污染调用方的数据。
     */
    private static Map<String, Object> copyRequestBody(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    /**
     * 阶段 3：写入主干自己决定的协议字段。
     *
     * <p>两者都是「主干对上游的陈述」而非「下游说了什么」：模型名已剥前缀
     * （{@link #resolveModel} 的结果），stream 由入口方法按调用的是流式还是非流式给出。
     */
    private static void writeProtocolFields(Map<String, Object> body, String resolvedModel, boolean stream) {
        body.put("model", resolvedModel);
        body.put("stream", stream);
    }

    /**
     * 阶段 4：思考深度。
     *
     * <p>按模型配置的注入模式处理：覆写 / 兜底 / 透传 / 删除四档。
     * 本线路写的是 OpenAI 的 {@code reasoning_effort} 与 {@code thinking}
     * （{@code off} 档写 {@code thinking:{"type":"disabled"}}）——
     * 两者成对操作的理由见 {@link ReasoningEffortSetting#applyTo}。
     */
    private void applyReasoningEffort(Map<String, Object> body, String resolvedModel,
                                      ProviderRuntimeConfiguration provider) {
        resolveReasoningEffort(resolvedModel, provider).applyTo(body);
    }

    /**
     * 阶段 6：清掉所有值为 {@code null} 的字段 —— <strong>必须是链条的最后一步</strong>。
     *
     * <p>它的位置由两件事共同决定，两步理由见 {@link #prepareRequestBody} 的 javadoc：
     * 规则产生的 {@code null} 不能出站，且规则看到的输入要与编辑器预览逐字节一致。
     */
    private static void removeNullFields(Map<String, Object> body) {
        body.values().removeIf(Objects::isNull);
    }

    /**
     * 保存非流式调用日志。
     *
     * @return 新插入日志行的自增 id；日志未启用或写入失败时返回 null
     */
    private Long saveNonStreamLog(String providerKey, String modelName, Map<String, String> reqHeaders, Map<String, Object> requestBody, Map<String, String> respHeaders, int statusCode, String responseBody, long startTime) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveNonStream(providerKey, modelName, reqHeaders, requestBody, respHeaders,
                statusCode, responseBody, duration);
    }

    /**
     * 保存流式调用日志。
     *
     * @return 新插入日志行的自增 id；日志未启用或写入失败时返回 null
     */
    private Long saveStreamLog(String providerKey, String modelName, Map<String, String> reqHeaders, Map<String, Object> requestBody, Map<String, String> respHeaders, int statusCode, List<String> chunks, long startTime) {
        if (apiCallLog == null) return null;
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveStream(providerKey, modelName, reqHeaders, requestBody, respHeaders,
                statusCode, chunks, duration);
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
        try {
            if (apiCallUsage == null) return;
            String usageRaw = OpenAiUsageParser.extractUsageRawJson(objectMapper, fullBody);
            if (usageRaw == null) return; // 无 usage：不写（方案 a）
            UsageTokens tokens = OpenAiUsageParser.parseUsageObject(objectMapper, usageRaw);
            apiCallUsage.save(logId, providerKey, modelName, stream, usageRaw, tokens, ttfbMs);
        } finally {
            // finally 语义：无论用量是否实际写入，用量流程走完即宣告该次调用的记录就绪。
            publishCallRecorded();
        }
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
        try {
            if (apiCallUsage == null) return;
            if (usageRaw == null) return; // 无 usage：不写（方案 a）
            UsageTokens tokens = OpenAiUsageParser.parseUsageObject(objectMapper, usageRaw);
            apiCallUsage.save(logId, providerKey, modelName, stream, usageRaw, tokens, ttfbMs);
        } finally {
            // finally 语义：无论用量是否实际写入，用量流程走完即宣告该次调用的记录就绪。
            publishCallRecorded();
        }
    }

    /**
     * 从运行时模型配置中读取思考深度设置。
     *
     * <h2>模型未配置时给默认值而非跳过</h2>
     * 找不到匹配的模型仍返回 {@link ReasoningEffortSetting#defaults()}（中等档位 + 透传），
     * 与旧实现的硬编码 {@code "medium"} 保持一致。这个兜底值是可疑的 —— 对一个未配置的、
     * 可能根本不是思考模型的模型名，凭空注入 {@code reasoning_effort} 未必正确 ——
     * 但改变它会影响所有「模型名带前缀但库里查不到」的调用，不属于本次改动范围。
     */
    private ReasoningEffortSetting resolveReasoningEffort(String resolvedModel,
                                                         ProviderRuntimeConfiguration provider) {
        for (var m : provider.models()) {
            if (resolvedModel.equals(m.modelName())) {
                return ReasoningEffortSetting.parse(m.reasoningEffort(), objectMapper);
            }
        }
        return ReasoningEffortSetting.defaults();
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
    /**
     * 解析出真实的上游模型名（剥除供应商前缀）。
     *
     * <h2>两个来源不是「回退关系」，是同一个值的两条路</h2>
     * <ul>
     *   <li>{@code requestModel} —— 请求体里的 {@code model}。控制器会写进去，
     *       所以生产路径上它必定存在；</li>
     *   <li>{@code routedModel} —— 路由（{@code ProviderRouteResolver}）解析出的模型名。
     *       生产路径上它与 {@code requestModel} 同值（控制器写的就是下游传来的那个模型名）。</li>
     * </ul>
     * 之所以两个都留：测试常直接调 {@code prepareRequestBody} 并只给其中一个 ——
     * 例如 {@code GenericAnthropicChatServiceTests} 构造的请求体只有 {@code messages}，
     * 模型名完全来自路由参数。两个来源互补，不是先后关系。
     *
     * <h2>已剔除的是第三层：可枚举的默认模型名（3.3a）</h2>
     * 从前两个来源都为空时还会回退到一个 {@code fallbackDefaultModel} 字段。
     * 那是「特定供应商」时代的产物 —— 当年有可枚举的回退模型名；现在是范式供应商，
     * 那些名字一个都不存在了，字段的值退化成空串却留在代码里。
     *
     * <p>现在两者都为空时<strong>显式抛错</strong>：「下游必须携带 model」由路由层保证并报 400，
     * 这是众多多供应商代理的常规实现。若将来路由改成宽容模式，这里会立刻响，
     * 而不是把一个空模型名发给上游（那会让上游报一个指向别处的错）。
     *
     * @param requestModel 请求体里的模型名，可能为 null
     * @param routedModel  路由解析出的模型名，可能为 null
     * @return 剥除供应商前缀后的真实模型名
     */
    protected String resolveModel(Object requestModel, String routedModel) {
        String model;
        if (requestModel instanceof String value && !value.isBlank()) {
            model = value;
        } else if (routedModel != null && !routedModel.isBlank()) {
            model = routedModel;
        } else {
            // 不可达：路由层已在更早的一步拒绝（它先按「模型名可解析」筛过）。
            throw new IllegalArgumentException("请求缺少 model：路由层应已拒绝，不应到达此处");
        }
        // 去除供应商前缀（如 [DeepSeek]deepseek-v4-flash → deepseek-v4-flash）
        return ModelNameUtil.parse(model).modelName();
    }

    /**
     * 构建 OpenAI 上游的重试策略。
     *
     * <h2>它只做三件事：取时长、报标识、委托</h2>
     * 规格本身（读配置 + {@code Retry.backoff} + {@code filter} + {@code doBeforeRetry}
     * 里的 RETRYING 事件与日志）已收归 {@link UpstreamAutoRetry}（阶段 3.6c-2），
     * 三条线路共用一份。本方法保留为<strong>适配器</strong>：
     * 把本类的注入字段（策略服务、通知器）、自己的 logger、自己那两个
     * <strong>退避覆盖点</strong>绑给那个类 —— 于是两个调用点（非流式 / 流式）一行未改。
     *
     * <p>适配器若与签名不符会<strong>编译失败</strong>，属于「安全的重复」；
     * 被抽走的是会<em>静默分叉</em>的东西（judgment、算子序列、日志文案）。
     * 这条区分是判断「该不该抽」的实际依据，与 {@link UpstreamCallReporter} 同一取向。
     *
     * <h2>退避时长为何仍是本类的 protected 方法</h2>
     * 见 {@link #retryFirstBackoff()} 的说明 —— 它们是 7 个测试子类的
     * <strong>覆盖点</strong>，不是可收归的逻辑。
     */
    protected Retry buildRetrySpec(String method, ProviderRuntimeConfiguration provider,
                                   String requestId, String model, boolean stream) {
        return UpstreamAutoRetry.build(
                new UpstreamAutoRetry.CallContext(method, "OpenAI", provider.providerKey(),
                        requestId, model, stream),
                retryPolicyService, lifecycleNotifier,
                retryFirstBackoff(), retryMaxBackoff(), log);
    }

    /**
     * 首次重试前的退避时长，随重试次数指数增长（2s → 4s → 8s …），上限见 {@link #retryMaxBackoff()}。
     *
     * <h2>为何做成可覆盖方法而非常量</h2>
     * 退避是<strong>真实的时钟等待</strong>：一次耗尽 5 次重试的调用要等
     * {@code 2+4+8+16+32 = 62} 秒。测试若按生产值等待，单个「重试到耗尽」的用例就占
     * 一分钟以上，而它要验证的是「重试了几次、按什么条件重试」—— 退避时长本身不是被测行为。
     * 故测试子类覆盖成毫秒级，把等待压缩掉而不改变重试次数与判定逻辑。
     *
     * <p>退避策略本身仍有覆盖：由专门的用例用 Reactor 虚拟时间断言时长序列，
     * 那种方式不消耗真实时间。
     *
     * <p>不用配置项承载：这是上游友好度的工程默认值，不属于用户可调策略
     * （用户可调的是<em>次数</em>，见 {@code RetryPolicyService}）。做成方法而非字段，
     * 使覆盖点显式且不需要构造函数改签名。
     */
    protected Duration retryFirstBackoff() {
        return Duration.ofSeconds(2);
    }

    /**
     * 退避时长上限，指数增长到此值后不再翻倍。
     *
     * 无限重试模式下这个上限才是稳态间隔 —— 没有它，指数增长会让间隔迅速膨胀到不可接受。
     * 可覆盖的理由同 {@link #retryFirstBackoff()}。
     */
    protected Duration retryMaxBackoff() {
        return Duration.ofSeconds(30);
    }

    /**
     * best-effort 发出一个生命周期事件。
     *
     * <p>实现已抽到 {@link UpstreamCallReporter#publishLifecycle}（与另两个执行器共用）。
     * 本方法保留为<strong>适配器</strong>：把本类的可选注入字段与本类的 logger 绑给它。
     * 保留的理由是那 28 个调用点读起来不该变 —— 而适配器不符签名会编译失败，
     * 属于「安全的重复」，与被抽走的「会静默分叉的逻辑」不是一回事。
     */
    private void publishLifecycle(CallLifecycleEvent event) {
        UpstreamCallReporter.publishLifecycle(log, lifecycleNotifier, event);
    }

    /**
     * 发布「调用记录已就绪」信号。
     *
     * <p>实现已抽到 {@link UpstreamCallReporter#publishCallRecorded}，
     * 时序契约的完整理由（为何在编排层而非 INSERT 内部、为何是 finally 语义、
     * 一次调用可能发多次）已随之搬到那个方法上。方法保留为适配器。
     */
    private void publishCallRecorded() {
        UpstreamCallReporter.publishCallRecorded(log, apiCallLog);
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

}
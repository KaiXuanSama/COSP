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
import com.kaixuan.copilot_ollama_proxy.provider.stage.ChunkNormalizeStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ReasoningFallback;
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
 *       <td>{@link #buildRetrySpec} 的 {@code retryWhen}</td></tr>
 *   <tr><td>手动重试</td><td>用户在管理后台右键 Toast</td><td>否</td>
 *       <td>{@code CallRetryRegistry} + {@code takeUntilOther}</td></tr>
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
     * 只在 {@link #buildRetrySpec} 一处使用，保证重试次数只有一个来源。
     */
    private RetryPolicyService retryPolicyService;

    /**
     * chunk 归一支线的 Chat 实现，由 Spring 从集合注入里筛出（见 {@link #setChunkNormalizeStages}）。
     *
     * <h2>为何是「可选 + 回退静态工具」的过渡形态</h2>
     * Stage 3.1 只做「先成形」：让归一具备被查表的接口形状，但<strong>调用点仍固定用 Chat 实现</strong>，
     * 不按 {@code bodyProtocol} 运行时查表（那要 3.3 抽出主干才有键）。
     * <ul>
     *   <li><strong>生产</strong>：Spring 注入 {@code List<ChunkNormalizeStage>}，本类筛出
     *       {@link WireProtocol#CHAT} 那个存于此字段，调用它；</li>
     *   <li><strong>单测</strong>：7 个测试子类直接 {@code new}、不走 Spring，此字段为 null，
     *       调用点回退到静态工具 {@link UpstreamChunkNormalizer}。两条路逻辑完全相同
     *       （Chat 实现本就只转调那个静态工具），故零行为变更、既有单测一行未改。</li>
     * </ul>
     * 用 {@code List} 筛而非直接注入单个：防「将来多一个协议实现时，基类按单类型注入报多候选」。
     */
    private ChunkNormalizeStage chunkNormalizeStage;

    /** reasoning fallback 支线的 Chat 实现，注入与回退方式同 {@link #chunkNormalizeStage}。 */
    private ReasoningFallbackStage reasoningFallbackStage;

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

    /**
     * 从集合注入里筛出 Chat 的 chunk 归一支线。
     *
     * <p>收 {@code List} 而非单个：归一支线未来可能不止一个协议实现，按单类型注入会在
     * 那时报「多候选」。当前只有 Chat 一个实现，筛出它即可；筛不到（无实现）则保持 null，
     * 调用点回退静态工具。查表接线（按 {@code bodyProtocol} 运行时选）留待 3.3。
     */
    @Autowired(required = false)
    public void setChunkNormalizeStages(java.util.List<ChunkNormalizeStage> stages) {
        this.chunkNormalizeStage = stages == null ? null : stages.stream()
                .filter(stage -> stage.protocol() == WireProtocol.CHAT)
                .findFirst().orElse(null);
    }

    /** 从集合注入里筛出 Chat 的 reasoning fallback 支线，理由同 {@link #setChunkNormalizeStages}。 */
    @Autowired(required = false)
    public void setReasoningFallbackStages(java.util.List<ReasoningFallbackStage> stages) {
        this.reasoningFallbackStage = stages == null ? null : stages.stream()
                .filter(stage -> stage.protocol() == WireProtocol.CHAT)
                .findFirst().orElse(null);
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
     */
    protected AbstractUpstreamChatService(ObjectMapper objectMapper,
                                          ProviderRequestHeaderService providerRequestHeaderService) {
        this.objectMapper = objectMapper;
        this.providerRequestHeaderService = providerRequestHeaderService;
    }

    /**
     * 发送一次非流式 Chat Completions 请求。
     *
     * 请求体会经过 {@link #prepareRequestBody} 处理，包括模型名称解析、
     * stream 标志设置和子类的自定义字段注入。
     *
     * <h2>空响应兜底</h2>
     * 与流式共用同一份重试预算：空响应被包成 {@link EmptyUpstreamResponseException} 抛出，
     * 走的是下方同一条 {@code retryWhen}，因此「重试次数」始终只有 {@link #buildRetrySpec}
     * 一个来源。判定挂在 {@code retryWhen} <strong>内侧</strong>（在 {@code doOnNext} 落库之后）
     * 才能触发重发；耗尽后由 {@code onErrorResume} 把最后一轮的原始 body 放行给下游，
     * 与其他失败「耗尽后透传最后一次响应」保持一致。
     *
     * <p>非流式无需流式那套 gate 的缓存-释放机制：一次拿到完整 body 直接判即可。
     * 落库也比流式省事 —— 流式的帧被 gate 拦在上游、{@code logChunks} 是空的，
     * 必须靠异常携带缓存帧才能落库；非流式的 {@code saveNonStreamLog} 已经把完整 body 写进去了。
     *
     * @param openAiRequest 原始 OpenAI 格式请求体
     * @param model 请求中指定的模型名称
     * @return 统一形态的上游响应（单个 {@link UpstreamEvent.Body}）
     */
    protected Mono<UpstreamEvent> chatCompletion(Map<String, Object> openAiRequest, String model,
                                                 ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                                 String requestId) {
        return chatCompletion(openAiRequest, model, provider, downstreamHeaders, requestId,
                legacyContext(openAiRequest, provider, downstreamHeaders, requestId));
    }

    /**
     * 带管道上下文的{@link #chatCompletion}重载。
     *
     * <p>上下文决定空响应拦截是否介入 —— 判据与理由见
     * {@link RequestPipelineContext#shouldApplyEmptyResponseGate()}。
     * 不带上下文的旧重载等价于「直连」：照常拦截。
     *
     * @param ctx 本次请求的管道上下文，由编排层在组装期填好
     */
    protected Mono<UpstreamEvent> chatCompletion(Map<String, Object> openAiRequest, String model,
                                                 ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                                 String requestId, RequestPipelineContext ctx) {
        Map<String, Object> requestBody = prepareRequestBody(openAiRequest, false, model, provider);
        log.info("{} OpenAI 上游，模型: {}, 流式: false", provider.providerKey(), requestBody.get("model"));

        // 拦截是否介入：请求级事实，故在 defer 之外算一次 —— 重试不改变它的值。
        // 见 RequestPipelineContext 的「生命周期」注释：写进 defer 里会让半实现态在第二轮又走回判空重试。
        boolean gateActive = ctx.shouldApplyEmptyResponseGate();

        String providerKey = provider.providerKey();
        String modelName = (String) requestBody.get("model");
        Map<String, String> reqHeaders = new LinkedHashMap<>();
        // 每次上游往返（含重试）各自计时并落库：往返开始时刷新起点，使每条日志的 duration 反映该次往返本身。
        AtomicLong attemptStart = new AtomicLong(System.currentTimeMillis());

        // 非流式在本形态下就是「恰有一个元素的流」——统一后主干只需面对一种输入。
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
                // 转成异常而非直接返回，是为了复用下方 retryWhen 的同一份预算 ——
                // UpstreamRetryPolicy.isRetryableFailure 已认 EmptyUpstreamResponseException，无需第二套重试实现。
                .flatMap(entity -> {
                    String body = entity.getBody();
                    if (!gateActive) {
                        // 半轮实现态：响应是上游协议的形态，本端点的判据对它没有意义 ——
                        // 跳过拦截，原样放行。开发者要的正是这批帧本身。
                        log.debug("{} 空响应拦截已跳过（回程翻译未实现），原样放行上游响应 [{}] {}",
                                provider.providerKey(), model, requestId);
                        return Mono.just(entity);
                    }
                    if (OpenAiContentDetector.hasMeaningfulNonStreamPayload(objectMapper, body)) {
                        return Mono.just(entity);
                    }
                    log.warn("{} 上游空响应（无正文/思考链/工具调用），body 长度 {}，将按重试预算重发 [{}] {}",
                            provider.providerKey(), body == null ? 0 : body.length(), model, requestId);
                    // 携带原始 body：耗尽后要原样放行给下游。空 body 用空列表表示。
                    return Mono.error(new EmptyUpstreamResponseException(
                            body == null ? List.of() : List.of(body)));
                })
                // 重试挂在落库下游：中间失败已在上面各自记录，此处仅负责重订阅。
                .retryWhen(buildRetrySpec("chatCompletion", provider, requestId, modelName, false))
                // 取出响应体。空 body 场景已在上面被判空转成异常，走不到这里，
                // 故此处不会再出现 getBody() 为 null 导致 Reactor 抛 NPE 的情况 ——
                // 那个 NPE 曾让「200 + 空 body」被误报成「无法连接到上游服务」的 502。
                .map(entity -> entity.getBody())
                // 空响应重试耗尽：把最后一轮的原始 body 原样放行给下游，与其他失败
                // 「耗尽后透传最后一次响应」一致 —— 至少让下游看到上游真实返回了什么。
                // 用 UpstreamRetryPolicy.findEmptyUpstreamException 解包而非按类型匹配：
                // retryWhen 耗尽时原异常被包进 RetryExhaustedException，onErrorResume(Class) 匹配不到。
                // 该轮已在上面的 doOnNext 落过库，此处不重复落库。
                .onErrorResume(error -> {
                    EmptyUpstreamResponseException emptyResponse = UpstreamRetryPolicy.findEmptyUpstreamException(error);
                    if (emptyResponse == null) {
                        return Mono.error(error);
                    }
                    List<String> frames = emptyResponse.bufferedFrames();
                    log.warn("{} 上游空响应重试耗尽，放行最后一轮的响应体给下游 [{}] {}",
                            provider.providerKey(), model, requestId);
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
     * @param openAiRequest 原始 OpenAI 格式请求体
     * @param model 请求中指定的模型名称
     * @return 按顺序发出的 chunk JSON 字符串，最后一个元素为 "[DONE]"
     */
    protected Flux<UpstreamEvent> chatCompletionStream(Map<String, Object> openAiRequest, String model,
                                                       ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                                       String requestId) {
        return chatCompletionStream(openAiRequest, model, provider, downstreamHeaders, requestId,
                legacyContext(openAiRequest, provider, downstreamHeaders, requestId));
    }

    /**
     * 带管道上下文的{@link #chatCompletionStream}重载。
     *
     * <p>上下文决定空响应拦截是否介入 —— 判据与理由见
     * {@link RequestPipelineContext#shouldApplyEmptyResponseGate()}。
     * 不带上下文的旧重载等价于「直连」：照常拦截。
     *
     * @param ctx 本次请求的管道上下文，由编排层在组装期填好
     */
    protected Flux<UpstreamEvent> chatCompletionStream(Map<String, Object> openAiRequest, String model,
                                                       ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                                       String requestId, RequestPipelineContext ctx) {
        Map<String, Object> requestBody = prepareRequestBody(openAiRequest, true, model, provider);
        log.info("{} OpenAI 上游，模型: {}, 流式: true", provider.providerKey(), requestBody.get("model"));

        // 拦截是否介入：请求级事实，故在 defer 之外算一次 —— 重试不改变它的值。
        // 见 RequestPipelineContext 的「生命周期」注释：写进 defer 里会让半实现态在第二轮又走回判空重试。
        boolean gateActive = ctx.shouldApplyEmptyResponseGate();

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
        // 静默重试标志：上游尝试被重试信号中断时置位，收尾处据此重新发起一轮。
        AtomicBoolean silentRetryRequested = new AtomicBoolean(false);
        // 空响应兜底 gate 的两个状态，每轮往返在起点重置：
        // gateOpen —— 闸门是否<strong>已开</strong>（开了就逐帧直接放行，不再扣住）。
        // heldFrames —— 开闸前被拦下的原始帧，开闸时整批放行；到轮末仍未开闸则随异常带出。
        //
        // ⚠️ 初始值取 {@code !gateActive} 而不是 {@code gateActive} —— 两者是<strong>相反</strong>的概念：
        //   gateActive：拦截机制<em>要不要生效</em>（生效时就是要扣住帧）
        //   gateOpen  ：闸门<em>当前是不是开的</em>（开着就不再扣）
        // 拦截被跳过时（半轮实现态，见 RequestPipelineContext.shouldApplyEmptyResponseGate）
        // 闸门直接置为常开：每帧原路放行、轮末也不会抛空响应异常。
        // 这样「跳过」不需要在算子链里插分支，也不必让 retryWhen 知道这件事。
        AtomicBoolean gateOpen = new AtomicBoolean(!gateActive);
        List<ServerSentEvent<String>> heldFrames = new java.util.concurrent.CopyOnWriteArrayList<>();
        // 空响应重试耗尽后的放行标记：该轮已在 doOnError 落过库，收尾处据此跳过，避免同一轮记两条。
        AtomicBoolean emptyResponsePassthrough = new AtomicBoolean(false);

        // 单次上游尝试：每次订阅都注册新鲜的静默重试信号并把自己挂在信号上，
        // 被触发时取消当前 WebClient 请求（无值完成），由外层循环决定是否重发。
        Flux<ServerSentEvent<String>> rawAttempt = Flux.defer(() -> {
                    // 本次往返起点：重置计时与 chunk 收集，使每条日志只反映该次往返（不跨重试累加）。
                    attemptStart.set(System.currentTimeMillis());
                    logChunks.clear();
                    ttfbMs.set(-1);
                    usageRaw.set(null);
                    // 重置为「本轮拦截是否生效」而不是硬编码 false ——
                    // 拦截被跳过时（半轮实现态）每轮闸门都必须常开，否则第二轮又会走回判空重试。
                    // 注意取反：gateActive 是「拦截要生效」，而闸门开着意味着「不再扣帧」。
                    gateOpen.set(!gateActive);
                    heldFrames.clear();
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
                // 挂在 retryWhen <strong>内侧</strong>，因此每轮重订阅各自独立判定。
                // 开闸前逐帧缓存不下发；一旦出现实质载荷（正文/思考链/工具调用）立即整批释放，
                // 之后当轮不再拦截（gateOpen 常真，热路径只多一次 volatile 读）。
                .concatMap(frame -> {
                    // 首字打点放在此处而非 gate 下游：保持"首 chunk"语义 —— 只要上游吐了帧就算测得，
                    // 不因该帧被 gate 暂扣而延后。每轮往返已在起点重置。
                    if (ttfbMs.get() < 0) {
                        ttfbMs.set(System.currentTimeMillis() - attemptStart.get());
                    }
                    if (gateOpen.get()) {
                        return Flux.just(frame);
                    }
                    if (OpenAiContentDetector.hasMeaningfulPayload(objectMapper, frame.data())) {
                        gateOpen.set(true);
                        // 整批释放：缓存帧按到达顺序在前，当前帧在后，下游看到的顺序与上游一致。
                        List<ServerSentEvent<String>> released = new ArrayList<>(heldFrames);
                        heldFrames.clear();
                        released.add(frame);
                        return Flux.fromIterable(released);
                    }
                    heldFrames.add(frame);
                    return Flux.empty();
                })
                // 轮末综合判定：整轮从未开闸即为空响应，抛信号异常交给下游 retryWhen 按预算重试。
                // 放在 concatWith 而非 doFinally，是因为只有前者能把错误信号注入流中。
                // 此处也覆盖"0 帧空 body"：一帧都没来，gate 自然没开。
                .concatWith(Flux.defer(() -> {
                    if (gateOpen.get()) {
                        return Flux.<ServerSentEvent<String>>empty();
                    }
                    List<String> emptyFrames = heldFrames.stream()
                            .map(ServerSentEvent::data)
                            .filter(Objects::nonNull)
                            .toList();
                    log.warn("{} 上游空响应（无正文/思考链/工具调用），拦截帧数 {}，将按重试预算重发 [{}] {}",
                            provider.providerKey(), emptyFrames.size(), model, requestId);
                    return Flux.error(new EmptyUpstreamResponseException(emptyFrames));
                }))
                // 网络类失败往返（无上游错误响应，如连接失败 / HTTP 200 后流中途断开）：即时落一条记录。
                // 错误响应（4xx/5xx）已在 exchangeToFlux 分支落库，此处用 findWebResponseException==null 排除以免重复。
                .doOnError(e -> {
                    EmptyUpstreamResponseException emptyResponse = UpstreamRetryPolicy.findEmptyUpstreamException(e);
                    if (emptyResponse != null) {
                        // 空响应往返：帧被 gate 拦在上游，logChunks 是空的 —— 必须改用异常携带的缓存帧落库，
                        // 否则日志只剩「200 且零 chunk」，恰恰在最该看清上游吐了什么的场景下什么都看不到。
                        saveStreamLog(providerKey, modelName, reqHeaders, requestBody,
                                capturedRespHeaders.get(), capturedStatusCode.get(),
                                emptyResponse.bufferedFrames(), attemptStart.get());
                        // 空响应往返不写用量行（无 usage 可言），落库流程到此即完。
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
                // 异常重试：空响应与 429 / 5xx / 网络中断共用这一条预算 —— 空响应被包成
                // EmptyUpstreamResponseException 抛出，UpstreamRetryPolicy.isRetryableFailure 认它，
                // 因此无需第二套重试实现。
                // 与之相对，手动静默重试走 takeUntilOther 的正常完成，不经过 retryWhen，故不消耗预算。
                .retryWhen(buildRetrySpec("chatCompletionStream", provider, requestId, model, true))
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

        // 静默重试循环：把重试信号挂到<strong>整轮尝试</strong>（含 retryWhen 的 backoff 等待）上，
        // 使请求进行中与退避等待两个阶段都能被信号中断。被中断的轮次（无值完成）若标志为真则重发。
        // 每次重发都重新注册新鲜的信号，连续点击可连续触发；下游断连时整个链被取消，递归随之终止。
        AtomicReference<Flux<ServerSentEvent<String>>> attemptLoopRef = new AtomicReference<>();
        Flux<ServerSentEvent<String>> attemptLoop = Flux.defer(() -> {
                    Mono<Void> silentRetrySignal = callRetryRegistry == null || requestId == null
                            ? Mono.never()
                            : callRetryRegistry.register(requestId)
                                    .doOnSuccess(v -> silentRetryRequested.set(true));
                    return rawAttempt.takeUntilOther(silentRetrySignal);
                })
                .concatWith(Flux.defer(() -> {
                    if (silentRetryRequested.compareAndSet(true, false)) {
                        log.info("静默重试：重新发起上游请求 [{}] {}", model, requestId);
                        return attemptLoopRef.get();
                    }
                    return Flux.<ServerSentEvent<String>>empty();
                }));
        attemptLoopRef.set(attemptLoop);

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
                    // Stage 3.1：优先走注入的 Chat 支线（生产），未注入（单测直接 new）回退静态工具 ——
                    // 两条路逻辑相同（Chat 支线只转调那个静态工具），故零行为变更。
                    String normalizedChunk = chunkNormalizeStage != null
                            ? chunkNormalizeStage.normalize(chunk, contentEmitted, reasoningBuffer, chunkId)
                            : UpstreamChunkNormalizer.normalize(objectMapper, chunk, contentEmitted, reasoningBuffer, chunkId);
                    // reasoning fallback：只有思考链没有正文时，用思考内容补一对伪 chunk。
                    // 触发判定（含「纯工具调用不触发」）见 ReasoningFallbackStage / ReasoningFallback。
                    boolean fallback = reasoningFallbackStage != null
                            ? reasoningFallbackStage.shouldFallback(normalizedChunk, contentEmitted, reasoningBuffer)
                            : ReasoningFallback.shouldFallback(objectMapper, normalizedChunk, contentEmitted, reasoningBuffer);
                    if (fallback) {
                        log.warn("模型未输出正文，回退使用思考内容作为回复 (长度: {})", reasoningBuffer.length());
                        List<String> frames = reasoningFallbackStage != null
                                ? reasoningFallbackStage.buildFallbackFrames(chunkId.get(), model, reasoningBuffer.toString())
                                : ReasoningFallback.buildFallbackFrames(objectMapper, chunkId.get(), model, reasoningBuffer.toString());
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
    * 准备请求体，解析模型名称，设置流式标志，并应用当前供应商的请求体规则。
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
        Map<String, Object> body = new LinkedHashMap<>(openAiRequest);
        String resolvedModel = resolveModel(body.get("model"), model);
        body.put("model", resolvedModel);
        body.put("stream", stream);
        // 思考深度按模型配置的注入模式处理：覆写 / 透传 / 删除。
        resolveReasoningEffort(resolvedModel, provider).applyTo(body);
        customizeRequestBody(body, resolvedModel, provider);
        body.values().removeIf(Objects::isNull);
        return body;
    }

    /**
     * 为不带上下文的旧重载拼一个最小上下文。
     *
     * <p>这些调用方（单元测试、内部兼容重载）不涉及翻译，因此两侧协议相同 ——
     * 于是 {@code shouldApplyEmptyResponseGate()} 判为「照常拦截」，
     * 与原先的 {@code PipelineExecution.empty()} 完全一致。
     *
     * <p>这是 <strong>3.3b-1 的过渡便利</strong>：本步只把执行器的第 6 个参数类型
     * 从 {@code PipelineExecution} 换成 {@code RequestPipelineContext}，
     * 其余一概不动。旧重载的去留是 <strong>3.3b-2 要问用户的</strong>决定。
     */
    private RequestPipelineContext legacyContext(Map<String, Object> body,
                                                 ProviderRuntimeConfiguration provider,
                                                 HttpHeaders downstreamHeaders, String requestId) {
        return RequestPipelineContext.of(body, WireProtocol.CHAT, WireProtocol.CHAT,
                provider, downstreamHeaders, requestId, null);
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
     * 构建 OpenAI 上游的统一重试策略。
     *
     * <p>重试次数固定为 5（首次请求外再试 5 次），指数退避 2 秒起、上限 30 秒。
     * 是否重试由 {@link UpstreamRetryPolicy#isRetryableFailure} 裁决，覆盖四类可恢复场景：
     * <ol>
     *   <li>429 上游限速（指数退避避免加重上游压力）；</li>
     *   <li>5xx 服务端错误；</li>
     *   <li>可重试的 400 错误；</li>
     *   <li>网络层异常 —— 连接建立失败（{@link WebClientRequestException}）、
     *       HTTP 200 后 SSE 流中途断开（cause chain 中的 {@code IOException}）、
     *       TLS 握手失败（cause chain 中的 {@code SSLException}）。</li>
     * </ol>
     * 其余错误（如 401/403 等确定性 4xx）不重试 —— 请求内容未变，重试结果必然相同。
     *
     * <h2>重试次数来源</h2>
     * 次数取自 {@code app_config} 的 {@code retry_max_attempts}（管理后台可改，改完即时生效）：
     * 正数为具体次数，{@code 0} 不重试，{@code -1} 无限重试。未注入策略服务时（单元测试）
     * 回退到 {@link RetryPolicyService#DEFAULT_MAX_ATTEMPTS}。
     *
     * <p>注意 {@code 0} 与「不加 retryWhen」并不完全等价：{@code filter} 与
     * {@code doBeforeRetry} 依旧挂着，只是永远不会触发重订阅，异常照常透传。保留这条链
     * 而不做分支，是为了让重试次数始终只有这一个来源。
     *
     * <h2>退避时长</h2>
     * 首次退避 {@link #retryFirstBackoff()}、上限 {@link #retryMaxBackoff()}，两者均可被
     * 子类覆盖以便测试压缩等待，见那两个方法的说明。
     *
     * @param method 调用方方法名，用于日志区分重试来源
     * @param requestId 本次调用唯一标识，用于发出 RETRYING 生命周期事件
     * @param model 模型名称（含前缀），用于 RETRYING 事件展示
     * @param stream 是否流式请求
     * @return 配置好的 Retry 实例
     */
    protected Retry buildRetrySpec(String method, ProviderRuntimeConfiguration provider,
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
                    // RETRYING：让前端 Toast 从“已连接/等待中”切换到“上游异常，正在重试（第N次）”，
                    // 避免重试期间静默卡顿让用户误以为卡死。无限模式下前端拿 total=-1 以示无上限。
                    publishLifecycle(CallLifecycleEvent.retrying(requestId, model, stream, attempt));
                    logRetryAttempt(method, provider, signal, attempt, unlimited ? -1 : (int) maxAttempts);
                });
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
     * 记录一次重试的日志。
     *
     * <p>429 单独区分：限速是上游明确的节流信号，附带其 {@code Retry-After} 头
     * 便于人工判断退避是否符合预期；其余失败只记异常消息。
     *
     * @param method 调用方方法名
     * @param provider 供应商配置（取 providerKey 用于日志区分）
     * @param signal 本次重试信号（含失败异常）
     * @param attempt 当前重试序号，从 1 起
     * @param maxAttempts 本次调用的重试上限；{@code -1} 表示无限
     */
    private void logRetryAttempt(String method, ProviderRuntimeConfiguration provider,
                                 Retry.RetrySignal signal, int attempt, int maxAttempts) {
        String budget = maxAttempts < 0 ? "∞" : String.valueOf(maxAttempts);
        if (signal.failure() instanceof WebClientResponseException responseException
                && responseException.getStatusCode().value() == 429) {
            String retryAfter = responseException.getHeaders().getFirst("Retry-After");
            log.warn("[{}] {} API 限速 (429)，重试第 {}/{} 次{}", method, provider.providerKey(), attempt, budget,
                    retryAfter != null ? "，Retry-After: " + retryAfter + "s" : "");
        } else {
            log.warn("[{}] {} API 调用失败，重试第 {}/{} 次: {}", method, provider.providerKey(), attempt, budget,
                    signal.failure().getMessage());
        }
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
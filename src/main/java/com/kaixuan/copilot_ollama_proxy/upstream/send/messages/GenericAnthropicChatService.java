package com.kaixuan.copilot_ollama_proxy.upstream.send.messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.control.CallRetryRegistry;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import com.kaixuan.copilot_ollama_proxy.upstream.ChunkLogPayload;
import com.kaixuan.copilot_ollama_proxy.upstream.EmptyUpstreamResponseException;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamAutoRetry;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamEventClassifier;
import com.kaixuan.copilot_ollama_proxy.upstream.send.AttemptContext;
import com.kaixuan.copilot_ollama_proxy.upstream.send.UpstreamCallRunner;
import com.kaixuan.copilot_ollama_proxy.upstream.send.UpstreamExecutor;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamCallReporter;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamRetryPolicy;
import com.kaixuan.copilot_ollama_proxy.upstream.content.ContentDetectorRegistry;
import com.kaixuan.copilot_ollama_proxy.upstream.content.ContentDetectorStage;
import com.kaixuan.copilot_ollama_proxy.upstream.EmptyResponseGate;
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
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用 Anthropic 上游服务 —— 对接 Anthropic Messages API 协议的供应商。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>实现</strong>（MESSAGES） · 位置：{@code upstream/send/messages/}
 * 步骤「发送」—— 独立类，<strong>不继承</strong> {@link AbstractUpstreamChatService}
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
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
 * Anthropic 与 OpenAI 的请求体有三处硬差异，现由请求体支线承担（阶段 4 刀 1 起装配收归
 * 主干 {@link com.kaixuan.copilot_ollama_proxy.upstream.requestbody.RequestBodyAssembler}，
 * 协议特定三步走 {@code RequestBodyStageRegistry}）：
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

    private final ObjectMapper objectMapper;
    private final ProviderRequestHeaderService providerRequestHeaderService;

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

    public GenericAnthropicChatService(ObjectMapper objectMapper,
                                       ProviderRequestHeaderService providerRequestHeaderService,
                                       ContentDetectorRegistry contentDetectorRegistry) {
        this.objectMapper = objectMapper;
        this.providerRequestHeaderService = providerRequestHeaderService;
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
        // 请求体已由主干的 RequestBodyAssembler 装配好（阶段 4 刀 1），直接取用。
        Map<String, Object> requestBody = ctx.body();
        log.info("{} Anthropic 上游，模型: {}, 流式: {}", provider.providerKey(), requestBody.get("model"), stream);

        // 拦截是否介入：请求级事实，故在 defer 之外算一次 —— 重试不改变它的值。
        // 判定机制收归 EmptyResponseGate（阶段 3.6b），检测器按上游协议查表。
        EmptyResponseGate<String> gate = new EmptyResponseGate<>(ctx.shouldApplyEmptyResponseGate());
        EmptyResponseGate.CallContext callCtx = new EmptyResponseGate.CallContext(provider.providerKey(), model, requestId);
        ContentDetectorStage detector = contentDetectorRegistry.require(ctx.upstreamProtocol());

        String providerKey = provider.providerKey();
        String modelName = (String) requestBody.get("model");
        Map<String, String> reqHeaders = new LinkedHashMap<>();
        // 流级状态收归 AttemptContext（阶段 4 刀 2）：非流式只用到 attemptStart。
        AttemptContext attempt = new AttemptContext();

        // 外层骨架（defer → 落库 → 判空 → retryWhen → 取 body → 耗尽放行 → 包装）收归主干 runner；
        // 本方法只提供协议特定的 transport（含 URI）、成功/失败落库、以及「body 转统一形态」这三段闭包。
        // chunkRewriter 在非流式不参与（无帧可改写），与另两侧一致。
        return UpstreamCallRunner.runNonStream(attempt, gate, detector, callCtx,
                buildRetrySpec("messages", provider, requestId, modelName, stream), log,
                new UpstreamCallRunner.NonStreamPipeline(
                        () -> buildWebClient(reqHeaders, ctx)
                                .post().uri(messagesUri()).bodyValue(requestBody).retrieve()
                                .toEntity(String.class),
                        // 成功往返：立即落一条成功记录（在 retry 上游，每次往返各自记录）。
                        entity -> {
                            log.debug("{} 响应: {}", providerKey, entity.getBody());
                            // CONNECTED：非流式无首字概念，完整响应到达即视为已连接。
                            publishLifecycle(CallLifecycleEvent.of(requestId, CallPhase.CONNECTED, modelName, false));
                            Map<String, String> respHeaders = new LinkedHashMap<>();
                            entity.getHeaders().forEach((k, v) -> respHeaders.put(k, String.join(", ", v)));
                            Long logId = UpstreamCallRunner.saveNonStreamLog(apiCallLog, ctx, reqHeaders, requestBody,
                                    respHeaders, entity.getStatusCode().value(), entity.getBody(), attempt.attemptStart());
                            // ttfb 传 null：非流式没有首字概念，与 OpenAI 侧一致。
                            saveUsage(logId, providerKey, modelName, stream,
                                    AnthropicUsageParser.extractUsageRawJson(objectMapper, entity.getBody()),
                                    null);
                        },
                        // 失败往返：每次失败（含被 retry 吞掉的中间失败）都各自落一条。
                        e -> {
                            WebClientResponseException responseException = UpstreamRetryPolicy.findWebResponseException(e);
                            if (responseException != null) {
                                Map<String, String> errHeaders = new LinkedHashMap<>();
                                responseException.getHeaders().forEach((k, v) -> errHeaders.put(k, String.join(", ", v)));
                                UpstreamCallRunner.saveNonStreamLog(apiCallLog, ctx, reqHeaders, requestBody, errHeaders,
                                        responseException.getStatusCode().value(),
                                        responseException.getResponseBodyAsString(), attempt.attemptStart());
                                publishCallRecorded();
                            } else {
                                // 状态码 -1：非 HTTP 异常的占位值，与 OpenAI 侧同一约定。
                                UpstreamCallRunner.saveNonStreamLog(apiCallLog, ctx, reqHeaders, requestBody, Map.of(),
                                        -1, null, attempt.attemptStart());
                                publishCallRecorded();
                            }
                        },
                        // 包装成统一形态：非流式在本形态下就是「恰有一个元素的流」。
                        // 直接用 body 而不走分类器：非流式的响应体里不存在协议级终止标记，
                        // 「说完了」由流的 onComplete 表达 —— 这是已确定的事实，不必运行时再判一次。
                        UpstreamEvent::body));
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
        // 请求体已由主干的 RequestBodyAssembler 装配好（阶段 4 刀 1），直接取用。
        Map<String, Object> requestBody = ctx.body();
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
        // 流级状态收归 AttemptContext（阶段 4 刀 2）：计时 / chunk 收集 / 首字 / 耗尽标记。
        AttemptContext attempt = new AttemptContext();
        // 本轮累积的 usage：input_tokens 来自 message_start、output_tokens 来自 message_delta，
        // 必须跨事件合并才完整。它是<strong>协议特有</strong>的流级态（Anthropic 独有的跨事件合并），
        // 故留本方法闭包、不进 AttemptContext。
        AtomicReference<UsageTokens> usageAccumulator = new AtomicReference<>(UsageTokens.EMPTY);
        // 存档用的 usage 原文。挑「信息量最大」的那一份而非最后一份，打平时取结算态
        // （message_delta），理由见 pickRicherUsageRaw —— 有的上游每个事件都带 usage，
        // 且尾事件全零；也不跨事件拼字段，那会造出上游从未发出过的报文。
        AtomicReference<String> archivedUsageRaw = new AtomicReference<>(null);

        // transport：defer 内每轮重置协议特有的 usage 累积（AttemptContext 由 runner 重置），
        // 建 WebClient + 发送 + preGate 处理（mapNotNull/filter/首字打点/usage 合并/chunk 记录）。
        UpstreamCallRunner.StreamPipeline<String> pipeline = new UpstreamCallRunner.StreamPipeline<>(
                () -> {
                    usageAccumulator.set(UsageTokens.EMPTY);
                    archivedUsageRaw.set(null);
                    return buildWebClient(reqHeaders, ctx)
                            .post().uri(messagesUri()).bodyValue(requestBody)
                            .exchangeToFlux(response -> {
                                Map<String, String> respHeaders = new LinkedHashMap<>();
                                response.headers().asHttpHeaders()
                                        .forEach((k, v) -> respHeaders.put(k, String.join(", ", v)));
                                attempt.captureResponse(respHeaders, response.statusCode().value());
                                if (response.statusCode().isError()) {
                                    return response.bodyToMono(String.class).flatMapMany(errorBody -> {
                                        log.warn("{} 上游返回错误响应 {}: {}", providerKey,
                                                response.statusCode().value(), errorBody);
                                        UpstreamCallRunner.saveStreamLogWithError(apiCallLog, ctx, reqHeaders, requestBody,
                                                respHeaders, response.statusCode().value(), List.of(),
                                                respHeaders, response.statusCode().value(), errorBody,
                                                attempt.attemptStart(), chunkRewriter);
                                        publishCallRecorded();
                                        return Flux.error(new WebClientResponseException(
                                                response.statusCode().value(), "上游错误响应", null,
                                                errorBody.getBytes(), null));
                                    });
                                }
                                // CONNECTED：收到非错误响应头的那一刻。
                                publishLifecycle(CallLifecycleEvent.of(requestId, CallPhase.CONNECTED, model, true));
                                return response.bodyToFlux(STRING_SSE_TYPE);
                            })
                            .mapNotNull(ServerSentEvent::data)
                            .filter(data -> !data.isBlank() && !"null".equals(data))
                            .doOnNext(data -> {
                                attempt.recordFirstByteIfAbsent();
                                log.debug("{} 上游事件: {}", providerKey, data);
                                // usage 跨事件合并：message_start 给输入、message_delta 给输出。
                                String rawUsage = AnthropicUsageParser.extractUsageRawJson(objectMapper, data);
                                if (rawUsage != null) {
                                    archivedUsageRaw.set(pickRicherUsageRaw(archivedUsageRaw.get(), rawUsage,
                                            isSettlementEvent(data)));
                                    usageAccumulator.set(AnthropicUsageParser.merge(usageAccumulator.get(),
                                            AnthropicUsageParser.parseUsageObject(objectMapper, rawUsage)));
                                }
                                attempt.addChunk(data);
                            });
                },
                // gate 挂在 mapNotNull 之后，扣的已是裸 data，故 dataOf = identity。
                Function.identity(),
                // 每轮失败落库：空响应往返用异常携带的缓存帧落库，网络类失败用捕获的 chunk。
                e -> {
                    EmptyUpstreamResponseException emptyResponse = UpstreamRetryPolicy.findEmptyUpstreamException(e);
                    if (emptyResponse != null) {
                        UpstreamCallRunner.saveStreamLog(apiCallLog, ctx, reqHeaders, requestBody,
                                attempt.respHeaders(), attempt.statusCode(),
                                emptyResponse.bufferedFrames(), attempt.attemptStart(), chunkRewriter);
                        publishCallRecorded();
                        return;
                    }
                    // 错误响应已在 exchangeToFlux 分支落库，此处按异常类型排除以免重复。
                    if (UpstreamRetryPolicy.findWebResponseException(e) == null) {
                        int statusCode = attempt.statusCode() == 0 ? -1 : attempt.statusCode();
                        UpstreamCallRunner.saveStreamLog(apiCallLog, ctx, reqHeaders, requestBody,
                                attempt.respHeaders(), statusCode, attempt.chunksSnapshot(),
                                attempt.attemptStart(), chunkRewriter);
                        publishCallRecorded();
                    }
                },
                // 耗尽放行：Anthropic 扣的是裸 data，还原恒等（Chat 才要重建 SSE 信封）。
                Flux::fromIterable,
                // postLoop：静默重发循环之后按上游协议分类 —— 本类发的就是 Anthropic 的事件。
                loop -> loop.map(data -> UpstreamEventClassifier.classify(
                        objectMapper, WireProtocol.MESSAGES, data)),
                // 成功收尾落库 + 用量写入（流式传已跨事件合并好的 tokens）。
                () -> {
                    int statusCode = attempt.statusCode();
                    if (statusCode == 0 && attempt.noChunks()) {
                        statusCode = -1;
                    }
                    Long logId = UpstreamCallRunner.saveStreamLog(apiCallLog, ctx, reqHeaders, requestBody,
                            attempt.respHeaders(), statusCode, attempt.chunks(), attempt.attemptStart(), chunkRewriter);
                    long ttfb = attempt.ttfb();
                    saveUsage(logId, providerKey, modelName, stream, archivedUsageRaw.get(),
                            ttfb < 0 ? null : (int) ttfb, usageAccumulator.get());
                });

        return UpstreamCallRunner.runStream(attempt, gate, detector, callCtx,
                buildRetrySpec("messagesStream", provider, requestId, model, stream),
                callRetryRegistry, "Anthropic", log, pipeline);
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
     * <p>地址解析（读 {@code anthropic_base_url} 回退 base_url）自阶段 4 刀 3 B 移至
     * {@code MessagesOutboundStage.resolveBaseUrl}，随出站装配一起上移发送前块。
     */
    private String messagesUri() {
        return "/messages";
    }

    /**
     * 构建 WebClient 并抓取出站请求头快照。
     *
     * <p><strong>出站头与地址已由发送前块装配好（阶段 4 刀 3 B）</strong>：本方法直接铺
     * {@code ctx.outboundHeaders()} 与 {@code ctx.outboundBaseUrl()}。三层头装配、地址解析
     * （{@code anthropic_base_url} 回退 base_url）与 {@code anthropic-version} 头现由
     * {@code OutboundRequestAssembler} + {@code MessagesOutboundStage} 在发送前完成。
     *
     * <p>结构与另两侧同形（两级抓取：WebClient 过滤器记录已装配好的头，
     * Reactor Netty 的 {@code doAfterRequest} 再用传输层快照覆盖，
     * 因此日志里能看到 User-Agent、Host 等底层补入的头）。抓取要等真正发出请求那一刻，故留发送后块。
     */
    private WebClient buildWebClient(Map<String, String> capturedHeaders, RequestPipelineContext ctx) {
        HttpClient capturingHttpClient = httpClient.doAfterRequest((request, connection) -> {
            HttpHeaders transportHeaders = new HttpHeaders();
            request.requestHeaders().forEach(entry ->
                    transportHeaders.add(entry.getKey(), entry.getValue()));
            providerRequestHeaderService.mergeLogSnapshot(capturedHeaders, transportHeaders);
        });

        return webClientBuilder.clone()
                .clientConnector(new ReactorClientHttpConnector(capturingHttpClient))
                .baseUrl(ctx.outboundBaseUrl())
                // 出站头已由发送前块 OutboundRequestAssembler 装配好（含 anthropic-version），这里只铺进去。
                .defaultHeaders(headers -> headers.addAll(ctx.outboundHeaders()))
                .filter((request, next) -> {
                    capturedHeaders.clear();
                    capturedHeaders.putAll(providerRequestHeaderService.createLogSnapshot(request.headers()));
                    return next.exchange(request);
                }).build();
    }

    /*
     * ========================================================================
     * 思考**深度**在 Anthropic 线路上的形态选择：已落地的决定与遗留代价
     * ========================================================================
     *
     * 深度与思考**方式**（AnthropicThinkingSetting）是两个正交维度：
     * 方式管「预算怎么算」（adaptive / enabled+budget），深度管「想多深」（档位字符串）。
     * 两者都已接入持久化并生效 —— 方式自 V10、深度走 ReasoningEffortSetting.applyToAnthropic。
     * 实现现由 requestbody/thinking 支线（MessagesThinkingStage → AnthropicThinkingNormalizer）
     * 承载；本执行器不再持有请求体装配（阶段 4 刀 1，装配收归主干 RequestBodyAssembler）。
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
     * 出站形态，见 ReasoningEffortSetting.applyToAnthropic。
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


    // ==================== 重试（策略与另两侧同源） ====================

    /**
     * 构建重试策略。
     *
     * <h2>它只做三件事：取时长、报标识、委托</h2>
     * 规格本身（读配置 + {@code Retry.backoff} + {@code filter} + {@code doBeforeRetry}
     * 里的 RETRYING 事件与日志）已收归 {@link UpstreamAutoRetry}（阶段 3.6c-2），
     * 三条线路共用一份。本方法保留为<strong>适配器</strong>：把本类的注入字段、
     * 自己的 logger、自己那两个<strong>退避覆盖点</strong>绑给那个类。
     *
     * <p>原本它只与 OpenAI 侧共享「次数来自同一个配置项」这一条约束；
     * 现连实现也共用。顺带补齐了一处<strong>抄漏</strong>：429 / {@code Retry-After}
     * 的日志特化此前只在 Chat，而 429 是 HTTP 层事实、不是协议差异 ——
     * 理由详见 {@link UpstreamAutoRetry} 的类注释。
     */
    private Retry buildRetrySpec(String method, ProviderRuntimeConfiguration provider,
                                 String requestId, String model, boolean stream) {
        return UpstreamAutoRetry.build(
                new UpstreamAutoRetry.CallContext(method, "Anthropic", provider.providerKey(),
                        requestId, model, stream),
                retryPolicyService, lifecycleNotifier,
                retryFirstBackoff(), retryMaxBackoff(), log);
    }

    /** 首次退避时长。可覆盖以便测试压缩等待，理由同 OpenAI 侧。 */
    protected Duration retryFirstBackoff() {
        return Duration.ofSeconds(2);
    }

    /** 退避上限。可覆盖以便测试压缩等待。 */
    protected Duration retryMaxBackoff() {
        return Duration.ofSeconds(30);
    }

    // ==================== 落库与观测 ====================

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

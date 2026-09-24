package com.kaixuan.copilot_ollama_proxy.upstream.send.responses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.AuthHeaderSetting;
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
import com.kaixuan.copilot_ollama_proxy.upstream.content.ContentDetectorRegistry;
import com.kaixuan.copilot_ollama_proxy.upstream.content.ContentDetectorStage;
import com.kaixuan.copilot_ollama_proxy.upstream.EmptyResponseGate;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamCallReporter;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamRetryPolicy;
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
import java.util.Optional;
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
        // 请求体已由主干的 RequestBodyAssembler 装配好（阶段 4 刀 1），直接取用。
        Map<String, Object> requestBody = ctx.body();
        log.info("{} Responses 上游，模型: {}, 流式: {}", provider.providerKey(), requestBody.get("model"), stream);

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
        return UpstreamCallRunner.runNonStream(attempt, gate, detector, callCtx,
                buildRetrySpec("responses", provider, requestId, modelName, stream), log,
                new UpstreamCallRunner.NonStreamPipeline(
                        () -> buildWebClient(reqHeaders, provider, downstreamHeaders, stream)
                                .post().uri(responsesUri()).bodyValue(requestBody).retrieve()
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
                            // ttfb 传 null：非流式没有首字概念，与另两侧一致。
                            saveUsage(logId, providerKey, modelName, stream,
                                    ResponsesUsageParser.extractUsageRawJson(objectMapper, entity.getBody()),
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
                                // 状态码 -1：非 HTTP 异常的占位值，与另两侧同一约定。
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
        // 请求体已由主干的 RequestBodyAssembler 装配好（阶段 4 刀 1），直接取用。
        Map<String, Object> requestBody = ctx.body();
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
        // 流级状态收归 AttemptContext（阶段 4 刀 2）：计时 / chunk 收集 / 首字 / 耗尽标记。
        AttemptContext attempt = new AttemptContext();
        // 本轮的 usage 原文。与 Anthropic 侧不同，这里不需要跨事件合并也不需要挑选：
        // Responses 的 usage 只在终态事件里出现一次、一次给全。仍用「最后一份非 null」
        // 而非「第一份」—— 若某个上游在中途也带 usage，终态那份才是结算值。
        // 它是<strong>协议特有</strong>的流级态，故留本方法闭包、不进 AttemptContext。
        AtomicReference<String> usageRaw = new AtomicReference<>(null);

        // transport：defer 内每轮重置 usageRaw（AttemptContext 由 runner 重置），
        // 建 WebClient + 发送 + preGate 处理（mapNotNull/filter/首字打点/usage 提取/chunk 记录）。
        UpstreamCallRunner.StreamPipeline<String> pipeline = new UpstreamCallRunner.StreamPipeline<>(
                () -> {
                    usageRaw.set(null);
                    return buildWebClient(reqHeaders, provider, downstreamHeaders, stream)
                            .post().uri(responsesUri()).bodyValue(requestBody)
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
                                                attempt.attemptStart(), null);
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
                                // usage 只在终态事件出现，但仍无条件尝试提取：某些上游中途也带一份，
                                // 后到的覆盖先到的，终态那份最终胜出。
                                String extracted = ResponsesUsageParser.extractUsageRawJson(objectMapper, data);
                                if (extracted != null) {
                                    usageRaw.set(extracted);
                                }
                                attempt.addChunk(data);
                            });
                },
                // gate 挂在 mapNotNull 之后，扣的已是裸 data，故 dataOf = identity。
                Function.identity(),
                // 每轮失败落库：错误响应已在 exchangeToFlux 分支落库，此处按异常类型排除以免重复。
                e -> {
                    Optional<List<String>> exhausted = EmptyResponseGate.exhaustedFrames(e);
                    if (exhausted.isPresent()) {
                        UpstreamCallRunner.saveStreamLog(apiCallLog, ctx, reqHeaders, requestBody,
                                attempt.respHeaders(), attempt.statusCode(),
                                exhausted.get(), attempt.attemptStart(), null);
                        publishCallRecorded();
                        return;
                    }
                    if (UpstreamRetryPolicy.findWebResponseException(e) == null) {
                        int statusCode = attempt.statusCode() == 0 ? -1 : attempt.statusCode();
                        UpstreamCallRunner.saveStreamLog(apiCallLog, ctx, reqHeaders, requestBody,
                                attempt.respHeaders(), statusCode, attempt.chunksSnapshot(),
                                attempt.attemptStart(), null);
                        publishCallRecorded();
                    }
                },
                // 耗尽放行：Responses 扣的是裸 data，还原恒等（Chat 才要重建 SSE 信封）。
                Flux::fromIterable,
                // postLoop：静默重发循环之后按上游协议分类 —— 本类发的就是 Responses 的事件。
                loop -> loop.map(data -> UpstreamEventClassifier.classify(
                        objectMapper, WireProtocol.RESPONSES, data)),
                // 成功收尾落库 + 用量写入。
                () -> {
                    int statusCode = attempt.statusCode();
                    if (statusCode == 0 && attempt.noChunks()) {
                        statusCode = -1;
                    }
                    Long logId = UpstreamCallRunner.saveStreamLog(apiCallLog, ctx, reqHeaders, requestBody,
                            attempt.respHeaders(), statusCode, attempt.chunks(), attempt.attemptStart(), null);
                    long ttfb = attempt.ttfb();
                    saveUsage(logId, providerKey, modelName, stream, usageRaw.get(),
                            ttfb < 0 ? null : (int) ttfb);
                });

        return UpstreamCallRunner.runStream(attempt, gate, detector, callCtx,
                buildRetrySpec("responsesStream", provider, requestId, model, stream),
                callRetryRegistry, "Responses", log, pipeline);
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

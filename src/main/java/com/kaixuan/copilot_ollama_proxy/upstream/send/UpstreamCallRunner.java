package com.kaixuan.copilot_ollama_proxy.upstream.send;

import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.control.CallResendLoop;
import com.kaixuan.copilot_ollama_proxy.control.CallRetryRegistry;
import com.kaixuan.copilot_ollama_proxy.upstream.ChunkLogPayload;
import com.kaixuan.copilot_ollama_proxy.upstream.EmptyResponseGate;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.upstream.content.ContentDetectorStage;
import org.slf4j.Logger;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.retry.Retry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 上游一次调用的<strong>编排骨架</strong> —— 主干后半段的家（阶段 4 刀 2）。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：主干<strong>编排器</strong>（无状态静态工具） · 位置：{@code upstream/send/}
 * 步骤「发送」的外层骨架 —— 三条执行器共用它串起
 * defer → gate → retryWhen → 耗尽放行 → 静默重发 → 落库
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 *
 * <h2>它为什么存在（控制流被扳正）</h2>
 * 阶段 4 §4.0 认定：{@code send} 插槽<strong>吞掉了主干的后半段</strong> ——
 * 请求往返、空响应兜底、自动重试、落库这些<strong>协议无关</strong>的编排，
 * 本该由主干持有，却在三个执行器里各写一遍（控制流是反的：插槽持流程、主干供工具）。
 * 本类把那段<strong>外层骨架</strong>收归一处，执行器退化成把<strong>协议特定闭包</strong>
 * 交出去的薄适配器（{@link StreamPipeline} / {@link NonStreamPipeline}）。
 *
 * <h2>它<strong>无状态、纯静态</strong>：依赖全部按调用传入</h2>
 * 与 {@link com.kaixuan.copilot_ollama_proxy.upstream.UpstreamCallReporter} /
 * {@link CallResendLoop} / {@link com.kaixuan.copilot_ollama_proxy.upstream.UpstreamAutoRetry} 同一形状：
 * 不做 Spring Bean、不持有任何字段。{@code apiCallLog} / {@code callRetryRegistry} 等是
 * <strong>各执行器</strong>的可选注入字段，由执行器在调用时作为参数传进来。
 *
 * <p>为何不持有这些依赖：若本类也持有，则该 Bean 会有两个持有者（执行器一份、本类一份），
 * 需要保证二者同步 —— 收益为零、风险非零（{@code UpstreamCallReporter} 的类注释已论证同一点）。
 * 执行器的 setter 因此<strong>一个都不用改</strong>，现有测试子类无需任何额外注入。
 *
 * <h2>落库 9→2（{@code KNOWN_DEBT} 第十条在此塌缩）</h2>
 * 三个执行器此前各有一族 {@code saveNonStreamLog} / {@code saveStreamLog} /
 * {@code saveStreamLogWithError}（两态 × 三协议 = 9 份），差异<strong>只有两处</strong>：
 * 上游协议常量、以及 {@code ChunkLogPayload.from(rewriter, chunks)} vs {@code .direct(chunks)}
 * （而 {@code from} 在 {@code rewriter == null} 时本就退回 {@code direct}，两条路等价）。
 * 收归本类后：上游协议<strong>从 {@code ctx.upstreamProtocol()} 取</strong>——
 * Chat 侧原先硬编码 {@code DEFAULT_PROTOCOL="CHAT"} 的脆弱性（C2M 落地后会静默记错）随之消失。
 * usage 落库<strong>不</strong>收归（三条各有自己的 {@code UsageParser}，Anthropic 还要跨事件
 * merge），仍留执行器。
 *
 * <h2>为何是编排器而非模板方法基类</h2>
 * 三条线路仍<strong>平级、各自持有协议特定闭包</strong>，本类只提供外层骨架 ——
 * 这是<strong>组合</strong>不是继承（{@code KNOWN_DEBT} 第四条与本类不矛盾）。
 * 差别就在控制流归谁：会静默分叉的编排由一处持有（本类），
 * 协议特定的 transport / usage 解析 / 帧分类仍是各协议一份（闭包，编译期可见依赖）。
 */
public final class UpstreamCallRunner {

    private UpstreamCallRunner() {
    }

    // ==================== 流式编排 ====================

    /**
     * 流式一次调用的外层骨架：
     * {@code defer(reset+transport) → gate → onAttemptError → retryWhen → 耗尽放行 → 静默重发循环 → postLoop → doFinally}。
     *
     * <p>元素类型 {@code <T>} 是<strong>闸门处</strong>的类型：Chat 扣 {@code ServerSentEvent<String>}
     * 信封（gate 在 {@code mapNotNull} 之前），另两条扣裸 {@code String}（gate 在其后）。
     * 因此本方法泛型化，{@link StreamPipeline#dataOf()} 负责从 {@code T} 取判定字符串。
     *
     * <h2>顺序有语义，逐条对齐重构前</h2>
     * <ol>
     *   <li>{@code defer} 内先 {@code attempt.resetForAttempt()} + {@code gate.reset()} ——
     *       {@code retryWhen} 会重订阅，不重置会带着上一轮的状态；</li>
     *   <li>{@code transport} 返回<strong>已完成 preGate 处理</strong>的帧流（Chat 只有 ttfb 打点，
     *       另两条含 {@code mapNotNull}/filter/usage 提取/chunk 记录）——
     *       因此闸门看到的就是 {@code T}；</li>
     *   <li>{@code onAttemptError} 在 gate 之后、retry 之前落每轮失败日志；</li>
     *   <li>耗尽放行由 {@code exhaustedToElements} 把缓存的 data 还原成 {@code T}
     *       （Chat 要重建 SSE 信封，另两条恒等）；</li>
     *   <li>静默重发循环包在最外，{@code postLoop} 做协议特定的收尾（分类，Chat 另加清洗/fallback）；</li>
     *   <li>{@code doFinally} 摘除重试注册 + 成功收尾落库（耗尽放行轮已落过库，跳过）。</li>
     * </ol>
     *
     * @param attempt           本次调用的流级状态容器（每轮 {@code defer} 内重置）
     * @param gate              空响应闸门（已按 {@code active} 构造；本方法在 defer 内 reset 它）
     * @param detector          本次上游协议的内容检测器
     * @param callCtx           日志所需的调用标识（providerKey / model / requestId）
     * @param retrySpec         执行器构造好的重试规格（退避覆盖点仍在执行器）
     * @param callRetryRegistry 静默重发注册表（执行器的可选注入字段，可为 null）
     * @param protocolLabel     静默重发日志用的协议名（"OpenAI" / "Anthropic" / "Responses"）
     * @param log               执行器自己的 logger（保留各类日志归属）
     * @param pipeline          协议特定闭包集合
     * @return 统一形态的上游事件流
     */
    public static <T> Flux<UpstreamEvent> runStream(AttemptContext attempt,
                                                    EmptyResponseGate<T> gate,
                                                    ContentDetectorStage detector,
                                                    EmptyResponseGate.CallContext callCtx,
                                                    Retry retrySpec,
                                                    CallRetryRegistry callRetryRegistry,
                                                    String protocolLabel,
                                                    Logger log,
                                                    StreamPipeline<T> pipeline) {
        Flux<T> attemptFlux = Flux.defer(() -> {
                    attempt.resetForAttempt();
                    gate.reset();
                    return pipeline.transport().get();
                })
                .transform(flux -> gate.gate(flux, pipeline.dataOf(), detector, log, callCtx))
                .doOnError(pipeline.onAttemptError())
                .retryWhen(retrySpec)
                .onErrorResume(error -> {
                    Optional<List<String>> exhausted = EmptyResponseGate.exhaustedFrames(error);
                    if (exhausted.isEmpty()) {
                        return Flux.error(error);
                    }
                    List<String> frames = exhausted.get();
                    EmptyResponseGate.logExhaustedPassthrough(log, callCtx, frames.size());
                    attempt.markEmptyResponsePassthrough();
                    return pipeline.exhaustedToElements().apply(frames);
                });

        Flux<T> loop = CallResendLoop.loop(attemptFlux, callRetryRegistry,
                callCtx.requestId(), protocolLabel, log, callCtx.model());

        return pipeline.postLoop().apply(loop)
                .doFinally(signal -> {
                    if (callRetryRegistry != null && callCtx.requestId() != null) {
                        callRetryRegistry.remove(callCtx.requestId());
                    }
                    // 空响应耗尽放行：该轮已在 onErrorResume/doOnError 落过库，此处再落一条会重复。
                    if (attempt.isEmptyResponsePassthrough()) {
                        return;
                    }
                    if (signal != SignalType.ON_ERROR) {
                        pipeline.onSuccessFinalize().run();
                    }
                });
    }

    // ==================== 非流式编排 ====================

    /**
     * 非流式一次调用的外层骨架：
     * {@code defer(transport) → doOnNext 落库 → doOnError 落库 → 判空 → retryWhen → 取 body → 耗尽放行 → bodyToEvent}。
     *
     * <p>非流式<strong>没有</strong>静默重发循环（那是流式独有），故不收 {@code callRetryRegistry}。
     *
     * <h2>判空的位置约束（三条线路逐字相同的那条硬约束）</h2>
     * 判空夹在 {@code doOnNext} 落库<strong>之后</strong>、取 body <strong>之前</strong>：
     * 落库先行保证「上游到底返回了什么」在日志里可查；取 body 前判空避免空 body 走到
     * {@code getBody()} 抛 NPE（那个 NPE 会被误报成「无法连接到上游服务」的 502）。
     *
     * @param attempt   本次调用的流级状态容器（非流式只用到 attemptStart）
     * @param gate      空响应闸门（非流式只读 {@code active()}，无缓存-释放）
     * @param detector  本次上游协议的内容检测器
     * @param callCtx   日志所需的调用标识
     * @param retrySpec 执行器构造好的重试规格
     * @param log       执行器自己的 logger
     * @param pipeline  协议特定闭包集合
     * @return 统一形态的上游响应（单个 {@link UpstreamEvent.Body}）
     */
    public static Mono<UpstreamEvent> runNonStream(AttemptContext attempt,
                                                   EmptyResponseGate<String> gate,
                                                   ContentDetectorStage detector,
                                                   EmptyResponseGate.CallContext callCtx,
                                                   Retry retrySpec,
                                                   Logger log,
                                                   NonStreamPipeline pipeline) {
        return Mono.defer(() -> {
                    attempt.markAttemptStart();
                    return pipeline.transport().get();
                })
                .doOnNext(pipeline.onSuccess())
                .doOnError(pipeline.onError())
                .flatMap(entity -> EmptyResponseGate
                        .checkNonStream(entity.getBody(), gate.active(), detector, log, callCtx)
                        .thenReturn(entity))
                .retryWhen(retrySpec)
                .map(ResponseEntity::getBody)
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
                .map(pipeline.bodyToEvent());
    }

    // ==================== 落库 9→2 ====================

    /**
     * 非流式落库。上游协议从 {@code ctx.upstreamProtocol()} 取（不再硬编码常量）。
     *
     * @param apiCallLog 执行器的可选注入字段（可为 null → 直接返回 null，日志未启用）
     * @return 新插入日志行的自增 id；日志未启用或写入失败时返回 null
     */
    public static Long saveNonStreamLog(ApiCallLogService apiCallLog, RequestPipelineContext ctx,
                                        Map<String, String> reqHeaders, Map<String, Object> requestBody,
                                        Map<String, String> respHeaders, int statusCode, String responseBody,
                                        long startTime) {
        if (apiCallLog == null) {
            return null;
        }
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveNonStream(ctx.provider().providerKey(), modelOf(requestBody),
                ctx.downstreamProtocol().name(), ctx.upstreamProtocol().name(),
                reqHeaders, requestBody, respHeaders, statusCode, responseBody, duration);
    }

    /**
     * 流式落库。chunk 过一道改写器：跨协议时记下游实际收到的 chunk，直连时 {@code rewriter == null}
     * 退回上游原文（裸数组）—— 退回语义见 {@link ChunkLogPayload#from}。
     *
     * @return 新插入日志行的自增 id；日志未启用或写入失败时返回 null
     */
    public static Long saveStreamLog(ApiCallLogService apiCallLog, RequestPipelineContext ctx,
                                     Map<String, String> reqHeaders, Map<String, Object> requestBody,
                                     Map<String, String> respHeaders, int statusCode, List<String> chunks,
                                     long startTime, Function<List<String>, ChunkLogPayload> chunkRewriter) {
        if (apiCallLog == null) {
            return null;
        }
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveStream(ctx.provider().providerKey(), modelOf(requestBody),
                ctx.downstreamProtocol().name(), ctx.upstreamProtocol().name(),
                reqHeaders, requestBody, respHeaders, statusCode,
                ChunkLogPayload.from(chunkRewriter, chunks), duration);
    }

    /**
     * 流式错误落库：流式过程中发生错误且重试耗尽时，把错误响应体记入非流式响应列。
     *
     * @return 新插入日志行的自增 id；日志未启用或写入失败时返回 null
     */
    public static Long saveStreamLogWithError(ApiCallLogService apiCallLog, RequestPipelineContext ctx,
                                              Map<String, String> reqHeaders, Map<String, Object> requestBody,
                                              Map<String, String> respHeaders, int statusCode, List<String> chunks,
                                              Map<String, String> errorHeaders, int errorCode, String errorBody,
                                              long startTime, Function<List<String>, ChunkLogPayload> chunkRewriter) {
        if (apiCallLog == null) {
            return null;
        }
        long duration = System.currentTimeMillis() - startTime;
        return apiCallLog.saveStreamWithError(ctx.provider().providerKey(), modelOf(requestBody),
                ctx.downstreamProtocol().name(), ctx.upstreamProtocol().name(),
                reqHeaders, requestBody, respHeaders, statusCode,
                ChunkLogPayload.from(chunkRewriter, chunks), errorHeaders, errorCode, errorBody, duration);
    }

    /** 从请求体取上游模型名（已由主干装配写好）。 */
    private static String modelOf(Map<String, Object> requestBody) {
        Object model = requestBody.get("model");
        return model instanceof String value ? value : null;
    }

    // ==================== 协议特定闭包集合 ====================

    /**
     * 流式的协议特定闭包。
     *
     * @param transport            {@code defer} 内调用，返回<strong>已完成 preGate 处理</strong>的帧流
     *                             （含 {@code buildWebClient}、{@code exchangeToFlux}、错误分支落库、
     *                             CONNECTED 事件，以及各线路的 preGate 步骤）
     * @param dataOf               从帧取判定字符串（Chat 传 {@code ServerSentEvent::data}，另两条传 {@code identity}）
     * @param onAttemptError       每轮失败落库（gate 之后、retry 之前）
     * @param exhaustedToElements  把耗尽缓存的 data 还原成 {@code T}（Chat 重建 SSE 信封，另两条恒等）
     * @param postLoop             静默重发循环之后的协议特定收尾（分类；Chat 另加清洗/fallback）
     * @param onSuccessFinalize    成功收尾落库 + 用量写入
     */
    public record StreamPipeline<T>(Supplier<Flux<T>> transport,
                                    Function<T, String> dataOf,
                                    Consumer<Throwable> onAttemptError,
                                    Function<List<String>, Flux<T>> exhaustedToElements,
                                    Function<Flux<T>, Flux<UpstreamEvent>> postLoop,
                                    Runnable onSuccessFinalize) {
    }

    /**
     * 非流式的协议特定闭包。
     *
     * @param transport   {@code defer} 内调用，返回 {@code Mono<ResponseEntity<String>>}
     * @param onSuccess   成功往返落库 + 用量写入 + CONNECTED 事件
     * @param onError     失败往返落库
     * @param bodyToEvent 响应体转统一形态（Responses/Anthropic 直接包，Chat 先做非流式清洗）
     */
    public record NonStreamPipeline(Supplier<Mono<ResponseEntity<String>>> transport,
                                    Consumer<ResponseEntity<String>> onSuccess,
                                    Consumer<Throwable> onError,
                                    Function<String, UpstreamEvent> bodyToEvent) {
    }
}

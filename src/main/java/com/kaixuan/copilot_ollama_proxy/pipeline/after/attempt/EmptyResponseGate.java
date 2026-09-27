package com.kaixuan.copilot_ollama_proxy.pipeline.after.attempt;

import com.kaixuan.copilot_ollama_proxy.pipeline.after.content.ContentDetectorStage;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 空响应拦截的<strong>机制</strong> —— 三个上游协议共用一份实现。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：主干<strong>机制</strong> · 位置：{@code pipeline/after/}（层根）
 * 步骤「空响应拦截」—— 检测器（支线）作参数传入，故机制本身留在主干、不进 {@code content/}
 * <p>完整步骤树见 {@code pipeline/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>它替代了什么</h2>
 * 这个机制原先在三个执行器里<strong>各有一份</strong>
 * （9 份 = 流式 gate / 非流式一次判 / 耗尽放行，每协议三份），
 * 而其中<strong>唯一的协议关联点只有检测器</strong> ——
 * 检测器已做成支线（{@link ContentDetectorStage}），于是机制本身可以收归一处。
 *
 * <h2>两种形态共用一个类，但机制确实不同</h2>
 * <ul>
 *   <li><strong>流式</strong>（{@link #gate}）：逐事件判，且必须<strong>先扣住</strong>再决定放行。
 *       扣住才保证「未见载荷」等价于「下游什么都没收到」，否则重试时下游会收到重复的
 *       开场事件（如 Anthropic 的 {@code message_start} 每条消息只有一次，
 *       客户端状态机会被搅乱），且耗尽时会把已流走的事件再放一遍。</li>
 *   <li><strong>非流式</strong>（{@link #checkNonStream}）：一次拿到完整 body，直接判即可 ——
 *       没有「扣」可言。</li>
 * </ul>
 *
 * <h2>为何是泛型 {@code <T>}：三份的帧类型本来就不一样</h2>
 * 这是个容易漏掉的细节 —— 三份 gate 扣的东西<strong>并非同一种</strong>：
 *
 * <table>
 *   <caption>各执行器扣住的元素类型</caption>
 *   <tr><th>执行器</th><th>{@code T}</th><th>原因</th></tr>
 *   <tr><td>Chat</td><td>{@code ServerSentEvent<String>}</td>
 *       <td>它的 gate 挂在 {@code mapNotNull(ServerSentEvent::data)} <strong>之前</strong>，
 *           扣的是整个 SSE 信封</td></tr>
 *   <tr><td>Anthropic / Responses</td><td>{@code String}</td>
 *       <td>挂在 {@code mapNotNull} <strong>之后</strong>，扣的已是裸 data</td></tr>
 * </table>
 *
 * <p>强制统一成一种会<strong>改变行为</strong>：把 Chat 的 gate 挪到信封拆解之后，
 * 释放时就只能重建信封（{@code ServerSentEvent.builder(data).build()}），
 * 而原信封可能带 {@code event} / {@code id} / {@code retry} 字段 —— 那些会丢。
 * 因此本类保持泛型：用 {@code dataOf} 取出「用于判定的字符串」，元素本身原样扣住、原样放行。
 *
 * <h2>「跳过拦截」的表达方式：常开闸门，而非在链上插分支</h2>
 * 半轮实现态（回程翻译未接）下不该拦截。流式侧的实现方式是构造时把闸门置为<strong>常开</strong>
 * （{@code open = !active}）：每帧原路放行、轮末也不抛空响应异常。
 * 这样「跳过」不需要在算子链里插条件分支，也不必让 {@code retryWhen} 知道这件事。
 *
 * <p>⚠️ {@code active}（拦截<em>要不要生效</em>）与 {@code open}（闸门<em>是不是开的</em>）
 * 是<strong>相反</strong>的概念。初值写成 {@code active} 会让两个方向同时失败 ——
 * 既有测试全挂 + 新测试也挂，那是「取反写错」的指纹。
 *
 * <h2>它是请求级的，但每轮往返要重置</h2>
 * 本类在 {@code defer} <strong>之外</strong>创建（{@code active} 是请求级事实，
 * 重试不改变它），而 {@link #reset()} 在 {@code defer} <strong>之内</strong>调用 ——
 * {@code retryWhen} 会重订阅，不重置会让第二轮带着第一轮的闸门状态与缓存帧，
 * 症状是「重试之后判定再也不会触发」。见 {@code RequestPipelineContext} 的「生命周期」注释。
 *
 * <h2>日志文案统一了（这里唯一可观测的差异）</h2>
 * 三条线路原本各有措辞略有差异的同义日志（「拦截帧数」vs「事件数」、
 * 「放行最后一轮的 N 帧」vs「N 个事件」）。收归后统一为一套 ——
 * <strong>日志文案属于「会静默分叉的东西」，应当收归</strong>。
 * 这是本类唯一可观测的差异，只影响日志文本、不影响功能。
 */
public final class EmptyResponseGate<T> {

    /** 日志所需的调用标识。 */
    public record CallContext(String providerKey, String model, String requestId) {
    }

    /** 拦截是否生效（请求级事实，重试不改变）。 */
    private final boolean active;

    /** 闸门当前是否已开（开着就不再扣帧）。 */
    private final AtomicBoolean open;

    /** 开闸前拦下的原始帧；开闸时整批放行，轮末仍未开闸则随异常带出。 */
    private final List<T> heldFrames = new CopyOnWriteArrayList<>();

    /**
     * @param active 拦截是否生效。取 {@code ctx.shouldApplyEmptyResponseGate()}；
     *               半轮实现态下为 {@code false}，闸门常开、整轮放行
     */
    public EmptyResponseGate(boolean active) {
        this.active = active;
        // 取反：active 是「拦截要生效」，而闸门开着意味着「不再扣帧」。
        this.open = new AtomicBoolean(!active);
    }

    /**
     * 每轮往返起点重置 —— 必须放在 {@code defer} 内。
     *
     * <p>不重置会让第二轮带着第一轮的闸门状态与缓存帧。
     */
    public void reset() {
        open.set(!active);
        heldFrames.clear();
    }

    /** 拦截是否生效。非流式侧要据此决定是否走「已跳过」分支。 */
    public boolean active() {
        return active;
    }

    // ==================== 流式 ====================

    /**
     * 形态一：流式逐帧拦截。
     *
     * <p>把它套在帧流上（元素类型与调用方一致，位置见类注释的类型表），它做三件事：
     * <ol>
     *   <li>闸门未开时逐帧<strong>扣住</strong>（不下发）；</li>
     *   <li>遇到带实质载荷的帧时<strong>整批释放</strong> —— 缓存帧按到达顺序在前、
     *       当前帧在后，因此下游看到的是一个完整合法的前缀，只是晚了一点；</li>
     *   <li>轮末若从未开闸，抛 {@link EmptyUpstreamResponseException} 并
     *       <strong>携带被扣帧的 data</strong>，交给下游 {@code retryWhen} 按同一份预算重试。</li>
     * </ol>
     *
     * <p>为何用 {@code concatMap} + {@code concatWith} 而不是 {@code doFinally}：
     * 只有前者能把错误信号<strong>注入流中</strong>，从而被 {@code retryWhen} 接住。
     *
     * <p>也覆盖「0 帧空 body」：一帧都没来，闸门自然没开，轮末判定即空响应。
     *
     * @param frames   上游帧流
     * @param dataOf   从帧里取出「用于判定的字符串」；Chat 传 {@code ServerSentEvent::data}，
     *                 另两条传 {@code Function.identity()}
     * @param detector 本次上游协议的检测器（来自 {@code ContentDetectorRegistry}）
     * @param log      用于告警的 logger
     * @param ctx      调用标识
     * @return 拦截后的帧流：开闸后原样透传；整轮无载荷时以异常终止
     */
    public Flux<T> gate(Flux<T> frames, Function<T, String> dataOf,
                        ContentDetectorStage detector, Logger log, CallContext ctx) {
        return frames.concatMap(frame -> {
                    if (open.get()) {
                        return Flux.just(frame);
                    }
                    if (detector.eventHasPayload(dataOf.apply(frame))) {
                        open.set(true);
                        // 整批释放：缓存帧按到达顺序在前，当前帧在后 —— 与上游顺序一致。
                        List<T> released = new ArrayList<>(heldFrames);
                        heldFrames.clear();
                        released.add(frame);
                        return Flux.fromIterable(released);
                    }
                    heldFrames.add(frame);
                    return Flux.empty();
                })
                .concatWith(Flux.defer(() -> {
                    if (open.get()) {
                        return Flux.empty();
                    }
                    log.warn("{} 上游空响应（无正文/思考链/工具调用），拦截条数 {}，将按重试预算重发 [{}] {}",
                            ctx.providerKey(), heldFrames.size(), ctx.model(), ctx.requestId());
                    return Flux.error(new EmptyUpstreamResponseException(heldData(dataOf)));
                }));
    }

    /** 被扣帧的 data 列表（按到达顺序，跳过 null）—— 随异常带出，供落库与耗尽放行使用。 */
    private List<String> heldData(Function<T, String> dataOf) {
        return heldFrames.stream()
                .map(dataOf)
                .filter(Objects::nonNull)
                .toList();
    }

    // ==================== 非流式 ====================

    /**
     * 形态二：非流式一次判定。
     *
     * <p>判据与流式同源（同一个检测器的「整轮判」入口），
     * 因此不会出现「切一下 stream 开关，同一个上游故障的结论就不同」。
     *
     * <p>转成异常而非直接返回，是为了复用调用方那条 {@code retryWhen} 的同一份预算 ——
     * {@code UpstreamRetryPolicy.isRetryableFailure} 已认
     * {@link EmptyUpstreamResponseException}，无需第二套重试实现。
     *
     * <p>调用方用法：
     * {@code .flatMap(entity -> EmptyResponseGate.checkNonStream(
     *     entity.getBody(), gate.active(), detector, log, callCtx).thenReturn(entity))}
     *
     * @param body     上游返回的完整响应体，可能为 null
     * @param active   拦截是否生效；{@code false} 时留痕并放行（半轮实现态）
     * @param detector 本次上游协议的检测器
     * @param log      用于告警的 logger
     * @param ctx      调用标识
     * @return 通过判定时为空（调用方继续原链）；判空时为携带原始 body 的异常信号
     */
    public static Mono<Void> checkNonStream(String body, boolean active, ContentDetectorStage detector,
                                            Logger log, CallContext ctx) {
        if (!active) {
            // 半轮实现态：响应是上游协议的形态，本端点的判据对它没有意义 ——
            // 跳过拦截，原样放行。开发者要的正是这批帧本身。
            // 留痕不可省：缺了它会让人误以为拦截跑过了。
            log.debug("{} 空响应拦截已跳过（回程翻译未实现），原样放行上游响应 [{}] {}",
                    ctx.providerKey(), ctx.model(), ctx.requestId());
            return Mono.empty();
        }
        if (detector.hasMeaningfulPayload(body)) {
            return Mono.empty();
        }
        log.warn("{} 上游空响应（无正文/思考链/工具调用），body 长度 {}，将按重试预算重发 [{}] {}",
                ctx.providerKey(), body == null ? 0 : body.length(), ctx.model(), ctx.requestId());
        // 携带原始 body：耗尽后要原样放行给下游。空 body 用空列表表示。
        return Mono.error(new EmptyUpstreamResponseException(
                body == null ? List.of() : List.of(body)));
    }

    // ==================== 耗尽放行 ====================

    /**
     * 形态三：重试耗尽后取出该放行的帧。
     *
     * <h2>为何必须解包而不是按类型匹配</h2>
     * {@code retryWhen} 耗尽时原异常已被包进 {@code RetryExhaustedException}，
     * 因此 {@code onErrorResume(EmptyUpstreamResponseException.class)} <strong>匹配不到</strong>。
     * 统一走 {@link UpstreamRetryPolicy#findEmptyUpstreamException} 递归解包。
     *
     * <p>调用方拿到空 {@code Optional} 时应<strong>继续抛原异常</strong>
     * （那是别的失败，不归本机制管）。
     *
     * @param error 终止信号携带的异常
     * @return 是空响应耗尽则为「该放行的 data 列表」；否则为空
     */
    public static Optional<List<String>> exhaustedFrames(Throwable error) {
        EmptyUpstreamResponseException emptyResponse =
                UpstreamRetryPolicy.findEmptyUpstreamException(error);
        return emptyResponse == null
                ? Optional.empty()
                : Optional.of(emptyResponse.bufferedFrames());
    }

    /**
     * 空响应耗尽放行的告警。
     *
     * <p>与 {@link #exhaustedFrames} 配对：那个决定「放行什么」，这个负责「留下什么痕迹」。
     * 分开是因为两种形态要放的<strong>载体</strong>不同（响应体 vs 帧序列），
     * 而「放行了几条、给谁」这件事本身同义。
     */
    public static void logExhaustedPassthrough(Logger log, CallContext ctx, int count) {
        log.warn("{} 上游空响应重试耗尽，放行最后一轮的 {} 条给下游 [{}] {}",
                ctx.providerKey(), count, ctx.model(), ctx.requestId());
    }
}

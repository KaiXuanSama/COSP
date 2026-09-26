package com.kaixuan.copilot_ollama_proxy.api.shared;

import com.kaixuan.copilot_ollama_proxy.control.CallCanceledException;
import com.kaixuan.copilot_ollama_proxy.control.CallCancellationRegistry;
import com.kaixuan.copilot_ollama_proxy.observability.publisher.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.observability.record.ApiUsageDailyService;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import org.slf4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * 三条端点共用的<strong>非流式收尾协议</strong> —— {@link StreamLifecycle} 的孪生。
 *
 * <h2>它处理的是一段什么样的链</h2>
 * 主干交出「恰好一个元素」的 {@code Mono<UpstreamEvent>}（非流式就是「一个元素的流」），
 * 出口要把它变成 {@code Mono<ResponseEntity<?>>}，并在沿途完成：
 * <ol>
 *   <li>注册取消信号 —— 管理后台取消时中止本次调用；</li>
 *   <li>{@code firstWithSignal} 让上游结果与取消信号<strong>赛跑</strong>；</li>
 *   <li>读事件上的 usage 槽记账（{@link UsageAccounting}）；</li>
 *   <li>发 {@code COMPLETED} 终态；</li>
 *   <li>{@code onErrorResume} 把失败分成三档（ABORTED / CANCELED / FAILED）；</li>
 *   <li>{@code doOnCancel} 感知下游断连；</li>
 *   <li>{@code doFinally} 清理注册表。</li>
 * </ol>
 *
 * <h2>为何要收归一处</h2>
 * 三条端点的非流式尾部此前<strong>近乎逐字重复</strong>（约 45 行 × 3），
 * 差异只有「调哪个 Service」与「调哪个错误渲染器」。这与流式侧抽
 * {@link StreamLifecycle} 之前的情形一模一样 —— 那份抽了，这份从来没有。
 * 重复本身不是问题，问题是它会<strong>静默分叉</strong>：某个终止情形漏改一处，
 * 症状是「这条线路的 Toast 永远挂在 CHUNK」。
 *
 * <h2>与 {@link StreamLifecycle} 的差异是<strong>两态本质差异</strong>，不要抹平</h2>
 * <table border="1">
 *   <caption>两态在收尾上的真实分歧</caption>
 *   <tr><th></th><th>流式</th><th>非流式</th></tr>
 *   <tr><td>取消怎么被检测到</td>
 *       <td>{@code takeUntilOther(cancelSignal)} —— 流<strong>正常完成</strong>，
 *           靠 {@code canceled} 标志区分</td>
 *       <td>{@code firstWithSignal} —— 取消信号<strong>抛
 *           {@link CallCanceledException}</strong>，靠 {@code instanceof} 识别</td></tr>
 *   <tr><td>产物</td><td>{@code Flux<ServerSentEvent<String>>}</td>
 *       <td>{@code Mono<ResponseEntity<?>>}（状态码还能设）</td></tr>
 *   <tr><td>心跳 / 逐帧计数</td><td>要</td><td>不要</td></tr>
 *   <tr><td>错误怎么送出去</td><td>error 帧（状态码在第一帧就提交了）</td>
 *       <td>错误响应体 + 状态码</td></tr>
 * </table>
 *
 * <p>因此本类<strong>不</strong>试图与流式合并成一个六合一入口 —— 那会把上面这些差异
 * 压成 context 里的标志位，而读的人将看不出某条线走的是哪一支。
 * 「取消检测」这一条尤其不能抽：它由 {@code Flux} / {@code Mono} 的形态决定。
 *
 * <h2>为什么回调只有「错误渲染器」一个</h2>
 * 流式那边有两个（{@code layer2Finalize} + {@code errorFrame}），因为它的完成判定分
 * Layer 1 / Layer 2 且帧形态因协议而异。非流式<strong>没有第一层</strong> ——
 * 「上游说完了」由 {@code Mono} 的 {@code onComplete} 表达，是唯一的信号，
 * 所以终态恒为 {@code COMPLETED}，不需要回调。
 *
 * <p>剩下唯一的协议特定物就是「失败长什么样」（三套骨架刻意不同，见
 * {@link UpstreamErrorRenderer}），那正是本类的参数。
 *
 * <h2>本类为何无状态、纯静态</h2>
 * 与 {@link StreamLifecycle} / {@link UsageAccounting} / {@link UpstreamErrorRenderer}
 * 同一取向：不做 Spring Bean、不持有字段。<strong>logger 走 ctx</strong> 是为了让三条日志
 * 保持各自的<strong>端点归属</strong>（{@code c.k.c.api.anthropic.AnthropicController}），
 * 按端点过滤日志的人才能看到它们。
 *
 * @see StreamLifecycle
 * @see UpstreamErrorRenderer
 */
public final class NonStreamLifecycle {

    private NonStreamLifecycle() {
    }

    /**
     * 收尾协议需要的全部上下文。
     *
     * <h2>为何是参数包而不是散开的形参</h2>
     * 与 {@link StreamLifecycle.CallContext} 同一理由：调用点读起来是
     * 「用这些状态包住这次调用」，而不是在十个裸参数里找哪个是哪个。
     *
     * <h2>它不是一个值对象</h2>
     * 分量全是不可变引用（除 logger 外都是协作对象），但本记录<strong>只承载</strong>，
     * 不承载状态 —— 非流式没有需要跨算子共享的计数器（那正是它与流式的差别）。
     *
     * @param requestId           本次调用唯一标识，用于事件分组与注册表清理
     * @param model               模型名（含前缀），仅用于日志与事件展示
     * @param stream              是否为流式；非流式路径恒为 {@code false}，
     *                            显式传入而不硬编码 —— 它是事件契约的一部分，
     *                            且让本类不替调用方做假设
     * @param lifecyclePublisher  生命周期事件发布器
     * @param cancellationRegistry 取消注册表，收尾时必须清理以免内存泄漏
     * @param usageCollector      日聚合写入端口（读事件上的 usage 槽后记账）
     * @param log                 端点自己的 logger，保持日志归属
     */
    public record CallContext(
            String requestId,
            String model,
            boolean stream,
            CallLifecyclePublisher lifecyclePublisher,
            CallCancellationRegistry cancellationRegistry,
            ApiUsageDailyService usageCollector,
            Logger log) {
    }

    /**
     * 把非流式收尾协议挂到主干的产物上。
     *
     * @param upstream      主干交出的响应（恰一个元素的流，形态未拆包）
     * @param ctx           收尾上下文，见 {@link CallContext}
     * @param errorResponse 把失败渲染成<strong>本协议</strong>的响应（含状态码）。
     *                      三条端点的骨架刻意不同 —— Chat 与 Responses 用
     *                      {@code {"error":{...}}}，Anthropic 多一层 {@code "type":"error"}。
     *                      通常直接传 {@link UpstreamErrorRenderer#response} 的偏应用。
     * @return 可直接作为控制器返回值的响应
     */
    public static Mono<ResponseEntity<?>> attach(
            Mono<UpstreamEvent> upstream,
            CallContext ctx,
            Function<Throwable, ResponseEntity<?>> errorResponse) {

        // 注册取消信号：管理后台点击取消时它正常 complete，firstWithSignal 会抛
        // CallCanceledException 中止本次调用。取消权限不分阶段 —— 前端右键 Toast 即可随时断连。
        Mono<String> cancelSignal = ctx.cancellationRegistry().register(ctx.requestId())
                .then(Mono.error(new CallCanceledException()));

        return Mono.firstWithSignal(
                        // 出口处拆包：主干是统一形态（UpstreamEvent），本端点下游要的是裸 JSON。
                        // usage 记账必须发生在取 data() 之前 —— 它读的是事件上的槽。
                        upstream.doOnNext(event -> UsageAccounting.recordNonStream(
                                        ctx.usageCollector(), event))
                                .map(UpstreamEvent::data),
                        cancelSignal)
                // COMPLETED：非流式无帧计数，最终计数为 0（前端已按 stream 分支处理文案）。
                .doOnNext(json -> ctx.lifecyclePublisher().publish(
                        CallLifecycleEvent.of(ctx.requestId(), CallPhase.COMPLETED,
                                ctx.model(), ctx.stream(), 0)))
                .<ResponseEntity<?>>map(json -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON).body(json))
                .onErrorResume(ex -> {
                    // ABORTED：管理后台主动取消，静默断开连接（不注入错误体），下游自行处理。
                    if (ex instanceof CallCanceledException) {
                        ctx.lifecyclePublisher().publish(CallLifecycleEvent.of(
                                ctx.requestId(), CallPhase.ABORTED, ctx.model(), ctx.stream()));
                        ctx.log().info("调用被主动取消 [{}] {}", ctx.model(), ctx.requestId());
                        return Mono.empty();
                    }
                    if (UpstreamFailureClassifier.isClientDisconnect(ex)) {
                        // CANCELED：客户端主动断连，发出终态让 Toast 收尾淡出，避免僵尸 Toast。
                        ctx.lifecyclePublisher().publish(CallLifecycleEvent.of(
                                ctx.requestId(), CallPhase.CANCELED, ctx.model(), ctx.stream()));
                        return Mono.empty();
                    }
                    // FAILED：上游错误或连接失败（客户端主动断连已在上面 return，不计入）。
                    ctx.lifecyclePublisher().publish(CallLifecycleEvent.of(
                            ctx.requestId(), CallPhase.FAILED, ctx.model(), ctx.stream()));
                    return Mono.just(errorResponse.apply(ex));
                })
                // CANCELED：下游（Copilot）主动断连是 Reactor 的 cancel 信号，
                // onErrorResume 捕获不到，必须用 doOnCancel 感知 ——
                // 否则不发终态事件 → inFlight 记录永久留存 → 僵尸 toast。
                .doOnCancel(() -> {
                    ctx.lifecyclePublisher().publish(CallLifecycleEvent.of(
                            ctx.requestId(), CallPhase.CANCELED, ctx.model(), ctx.stream()));
                    ctx.log().info("下游主动断连 [{}] {}", ctx.model(), ctx.requestId());
                })
                // 无论正常结束、失败还是取消，都清理注册表，避免内存泄漏。
                .doFinally(signal -> ctx.cancellationRegistry().remove(ctx.requestId()));
    }
}

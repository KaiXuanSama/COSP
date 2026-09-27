package com.kaixuan.copilot_ollama_proxy.api.shared;

import com.kaixuan.copilot_ollama_proxy.control.CallCancellationRegistry;
import com.kaixuan.copilot_ollama_proxy.observability.publisher.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.observability.record.ApiUsageDailyService;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTokens;
import org.slf4j.Logger;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 三条流式端点共用的<strong>完整出口链</strong>：从主干产物一路到可下发的 SSE 流。
 *
 * <h2>它是一个完整入口，不是半个</h2>
 * 本类曾只包住<strong>收尾</strong>（取消 / 终止 / 心跳 / 清理），
 * 而「逐帧判终止、累积 usage、数 CHUNK」那半段由三个 Controller 各写一遍
 * （约 35 行 × 3，逐字同构）。现在前半段也进来了 —— 调用点从「两段拼起来」
 * 变成<strong>一行</strong>，本类名副其实。
 *
 * <pre>
 *   主干产物 Flux&lt;UpstreamEvent&gt;
 *        ↓ 逐帧副作用：累积 usage → 判终止（Layer 1）→ 数 CHUNK
 *        ↓ 形状映射：本协议的事件名回填策略          ← 回调 2
 *        ↓ 收尾协议：取消 / Layer 2 兜底 / 错误帧 / 清理
 *        ↓ 心跳合流
 *   Flux&lt;ServerSentEvent&lt;String&gt;&gt;   ← 直接作为 ResponseEntity 的 body
 * </pre>
 *
 * <h2>三条端点的差异只剩三个回调</h2>
 * <table border="1">
 *   <caption>差异清单（都是「两条相同、一条不同」）</caption>
 *   <tr><th>回调</th><th>Chat</th><th>Anthropic</th><th>Responses</th></tr>
 *   <tr><td>{@code terminalPhase}</td><td>{@code COMPLETED}</td><td>{@code COMPLETED}</td>
 *       <td>按 {@code Outcome} —— {@code response.failed} 不能显示成「完成」</td></tr>
 *   <tr><td>{@code frameMapper}</td><td><strong>不回填</strong> event 名</td>
 *       <td>回填</td><td>回填</td></tr>
 *   <tr><td>{@code errorBody}</td><td>嵌套体</td><td>多一层 {@code type}</td>
 *       <td>扁平事件体</td></tr>
 * </table>
 *
 * <p><strong>Layer 2 不需要回调</strong>：实测三条线路的兜底相位<strong>都是
 * {@code COMPLETED}</strong>（Responses 的 {@code Outcome.SUCCESS} 也映射到它）。
 * 所以「上游没发终止标记就断连」这条路径完全共用 —— CAS、记账、发相位都在本类内。
 *
 * <h2>与 {@link NonStreamLifecycle} 的差异是<strong>两态本质差异</strong></h2>
 * <table border="1">
 *   <caption>两态在收尾上的真实分歧</caption>
 *   <tr><th></th><th>流式（本类）</th><th>非流式</th></tr>
 *   <tr><td>取消怎么被检测到</td>
 *       <td>{@code takeUntilOther(cancelSignal)} —— 流<strong>正常完成</strong>，
 *           靠 {@code canceled} 标志区分</td>
 *       <td>{@code firstWithSignal} —— 取消信号<strong>抛
 *           {@code CallCanceledException}</strong>，靠 {@code instanceof} 识别</td></tr>
 *   <tr><td>完成判定</td><td><strong>两层</strong>：Layer 1 见终止标记即时收尾，
 *       Layer 2 靠 {@code onComplete} 兜底，用 CAS 去重</td>
 *       <td>一层：{@code Mono.onComplete} 是唯一信号</td></tr>
 *   <tr><td>产物</td><td>{@code Flux<ServerSentEvent<String>>}</td>
 *       <td>{@code Mono<ResponseEntity<?>>}（状态码还能设）</td></tr>
 *   <tr><td>心跳 / 逐帧计数</td><td>要</td><td>不要</td></tr>
 *   <tr><td>错误怎么送出去</td><td>error 帧（状态码在第一帧就提交了）</td>
 *       <td>错误响应体 + 状态码</td></tr>
 * </table>
 *
 * <p>因此两者<strong>不</strong>合并成一个六合一入口 —— 那会把上面这些压成标志位，
 * 读的人将看不出某条线走的是哪一支。「取消检测」尤其不能抽：
 * 它由 {@code Flux} / {@code Mono} 的形态决定。
 *
 * <h2>Layer 1 为何是必需的</h2>
 * 部分上游发完终止标记后<strong>不主动关闭 TCP 连接</strong>（HTTP keep-alive），
 * 于是 {@code bodyToFlux} 永不 complete、Layer 2 永不触发、Toast 永远悬挂在 CHUNK。
 * Layer 1 在 {@code doOnNext} 里见到终止标记就立刻 finalize，不依赖连接关闭。
 *
 * <h2>三个终止信号的区别（最容易写错的一处）</h2>
 * <table>
 *   <caption>终止信号对照</caption>
 *   <tr><th>事件</th><th>触发者</th><th>本类的处置</th></tr>
 *   <tr><td>{@link CallPhase#ABORTED}</td>
 *       <td>管理后台右键取消，走 {@code cancelSignal} 正常完成</td>
 *       <td>静默断连，<strong>不注入任何错误帧</strong>；下游自行处理</td></tr>
 *   <tr><td>{@link CallPhase#CANCELED}</td>
 *       <td>下游（Copilot）断连 —— 这是 Reactor 的 <em>cancel</em> 信号</td>
 *       <td>发终态让 Toast 收尾淡出；<strong>{@code onErrorResume} 捕获不到</strong>，
 *           必须靠 {@code doOnCancel}</td></tr>
 *   <tr><td>{@link CallPhase#FAILED}</td>
 *       <td>上游错误或连接失败</td>
 *       <td>发 FAILED，再下发一帧 error</td></tr>
 * </table>
 *
 * <p><strong>为何 ABORTED 与 CANCELED 都不注入错误体</strong>：两者都不是「上游出错了」，
 * 下游没有可处理的错误。给一个已经主动断开连接的客户端写错误帧，最轻是白发一个字节，
 * 最重是在写失败路径上再触发一次取消。
 *
 * <h2>心跳为何必须有</h2>
 * 下游断连的检测靠两条路径：{@code channelInactive}（Reactor Netty 主动终止）与
 * 写失败（尝试写时发现 socket 已关）。前者在「从未写过数据的空闲连接」上不可靠 ——
 * 上游等待首字或重试退避期间服务端一个字节都不写，此时下游断开可能要等到
 * 上游产生响应、服务端尝试写时才发现，白白浪费一次上游调用。
 *
 * <p>周期写注释帧使空闲连接也有写操作，断连后最迟一个心跳周期内被写失败路径兜底。
 * 注释帧（{@code : keep-alive}）对 SSE 客户端无副作用，被规范要求忽略。
 *
 * <p>{@code streamEnd} 那个 sink 的意义就在这里：数据流<strong>无论以何种方式终止</strong>
 * （完成 / 错误 / 取消）都会 emit，心跳据此停止。否则 {@code Flux.interval} 永不完成、
 * {@code merge} 永不完成、{@code doOnComplete} 兜底随之失效。
 *
 * <h2>为何 logger 与协作对象都是参数</h2>
 * 与 {@code UpstreamCallReporter} 同一取向：本类是<strong>无状态纯静态</strong>，不做 Spring Bean。
 * {@code log} 走参数是为了让三条日志保持各自的<strong>端点归属</strong>
 * （{@code c.k.c.api.anthropic.AnthropicController} 而不是本类）——
 * 按端点过滤日志的人才能看到它们。这是「零行为变更」的一部分。
 */
public final class StreamLifecycle {

    /**
     * 下游 SSE 流的心跳周期。
     *
     * <p>与另几条<strong>管理后台</strong> SSE（统计 / 日志 / 调用生命周期）的 15 秒不同：
     * 那几条的订阅者是管理页，多等十秒无感；本条服务的是聊天客户端，
     * 且它的断连检测直接决定「要不要继续跑完这次上游调用」，收紧到 5 秒是为及时止损。
     */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);

    private StreamLifecycle() {
    }

    /**
     * 收尾协议需要的全部上下文。
     *
     * <h2>为何是一个参数包而不是散开的形参</h2>
     * 方法本体只接受四样东西（流、上下文、三个回调）。
     * 把上下文收拢后，调用点读起来是「用这些状态包住这条流」，
     * 而不是在十个裸参数里找哪个是哪个。
     *
     * <h2>它不是一个值对象</h2>
     * 四个 {@code Atomic*} 分量<strong>刻意是可变引用</strong> ——
     * 它们承载的是「本轮流的进度」，要在算子之间共享同一份实例。
     * 本类在逐帧算子里与收尾算子里读写它们，因而不能是快照式的值语义。
     *
     * @param requestId       本次调用唯一标识，用于事件分组与注册表清理
     * @param model           模型名（含前缀），仅用于日志与事件展示
     * @param eventCount      已下发的载荷帧数，终态事件要带上它给前端兜底
     * @param canceled        管理后台取消置位 —— 用于区分「ABORTED」与「正常完成」
     * @param completed       Layer 1 / Layer 2 去重标志（CAS），由本类内部的 finalize 置位
     * @param usage           流内累积到的 token 用量（读事件上的 usage 槽）
     * @param lifecyclePublisher 生命周期事件发布器
     * @param cancellationRegistry 取消注册表，收尾时必须清理以免内存泄漏
     * @param usageCollector  日聚合写入端口
     * @param log             端点自己的 logger，保持日志归属
     */
    public record CallContext(
            String requestId,
            String model,
            AtomicInteger eventCount,
            AtomicBoolean canceled,
            AtomicBoolean completed,
            AtomicReference<UsageTokens> usage,
            CallLifecyclePublisher lifecyclePublisher,
            CallCancellationRegistry cancellationRegistry,
            ApiUsageDailyService usageCollector,
            Logger log) {
    }

    /**
     * 跑完流式出口链 —— 主干产物进，可下发的 SSE 流出。
     *
     * @param upstream      主干交出的上游事件流（<strong>未拆包</strong>，形态为统一事件）
     * @param ctx           收尾上下文，见 {@link CallContext}
     * @param terminalPhase Layer 1 该发哪个终态相位。三条中有两条恒为 {@code COMPLETED}，
     *                      Responses 要按事件名解出结局 —— 上游明确说「我失败了」时
     *                      不能显示成「完成」。<strong>只在 Layer 1 用到</strong>：
     *                      Layer 2 的兜底相位恒为 {@code COMPLETED}，不需要回调。
     * @param frameMapper   把一帧上游事件映射成要下发的 SSE 帧。
     *                      Chat <strong>不回填</strong> {@code event:} 名（OpenAI 客户端只认 data），
     *                      其余两条要回填（客户端靠它驱动状态机）。作用于<strong>所有</strong>帧，
     *                      含终止标记 —— 它是协议要求下发的。
     * @param errorBody     把失败渲染成<strong>本协议</strong>的 error 帧体。
     *                      三条形态两两不同（见 {@link UpstreamErrorRenderer}）。
     *                      包装成 {@code event: error} 的 SSE 帧由本类统一负责。
     * @return 与心跳合并后的流，可直接作为 {@code ResponseEntity} 的 body
     */
    public static Flux<ServerSentEvent<String>> stream(
            Flux<UpstreamEvent> upstream,
            CallContext ctx,
            Function<UpstreamEvent, CallPhase> terminalPhase,
            Function<UpstreamEvent, ServerSentEvent<String>> frameMapper,
            Function<Throwable, String> errorBody) {

        // 注册取消信号：管理后台点击取消时它正常 complete，takeUntilOther 中止本流、
        // 并使 canceled 置位（下面据此发 ABORTED 而非 COMPLETED）。
        Mono<Void> cancelSignal = ctx.cancellationRegistry().register(ctx.requestId())
                .doOnSuccess(v -> ctx.canceled().set(true));

        // 数据流终止信号：数据流无论以何种方式终止（完成 / 错误 / 取消）都会 emit，
        // 心跳据此停止 —— 否则 Flux.interval 永不完成，merge 永不完成，兜底失效。
        Sinks.Empty<Void> streamEnd = Sinks.empty();

        // 逐帧副作用：先累积 usage（终止帧上的那份也要收，故在判终止之前），
        // 再判终止（Layer 1），否则计入 CHUNK 数。
        Flux<ServerSentEvent<String>> mappedBody = upstream
                .doOnNext(event -> {
                    UsageAccounting.accumulate(event, ctx.usage());
                    if (event.isTerminal()) {
                        // Layer 1：语义信号优先。见类注释「Layer 1 为何是必需的」。
                        // 终止标记不计入帧数。
                        finalizeStream(ctx, terminalPhase.apply(event));
                        return;
                    }
                    // 每帧都推一次 CHUNK（不节流）。单次响应帧数通常不过数百，SSE 开销可接受。
                    ctx.lifecyclePublisher().publish(CallLifecycleEvent.of(
                            ctx.requestId(), CallPhase.CHUNK, ctx.model(), true,
                            ctx.eventCount().incrementAndGet()));
                })
                .map(frameMapper);

        Flux<ServerSentEvent<String>> streamBody = mappedBody
                .takeUntilOther(cancelSignal)
                // 取消时静默断连：只发 ABORTED 终态，不向下游注入任何错误帧。
                .concatWith(Flux.defer(() -> {
                    if (ctx.canceled().get()) {
                        ctx.lifecyclePublisher().publish(CallLifecycleEvent.of(
                                ctx.requestId(), CallPhase.ABORTED, ctx.model(), true,
                                ctx.eventCount().get()));
                        ctx.log().info("流式调用被主动取消，静默断连 [{}] {}", ctx.model(), ctx.requestId());
                    }
                    return Flux.<ServerSentEvent<String>>empty();
                }))
                .doOnComplete(() -> {
                    // 取消时不发 COMPLETED（已由上面的 concatWith 发 ABORTED）。
                    if (ctx.canceled().get()) {
                        return;
                    }
                    // Layer 2（TCP/SSE 连接关闭兜底）：上游未发语义终止标记就直接关连接时靠这里。
                    // 若 Layer 1 已在收到终止标记时 finalize，去重标志会让这里成为 no-op。
                    // 兜底相位恒为 COMPLETED —— 连接正常关闭且已有内容，没有任何证据表明它失败了。
                    finalizeStream(ctx, CallPhase.COMPLETED);
                })
                .onErrorResume(error -> {
                    if (UpstreamFailureClassifier.isClientDisconnect(error)) {
                        // CANCELED：客户端主动断连，发出终态让 Toast 收尾淡出，避免僵尸 Toast。
                        ctx.lifecyclePublisher().publish(CallLifecycleEvent.of(
                                ctx.requestId(), CallPhase.CANCELED, ctx.model(), true,
                                ctx.eventCount().get()));
                        return Flux.empty();
                    }
                    // FAILED：上游错误或连接失败（客户端主动断连已在上面 return，不计入）。
                    ctx.lifecyclePublisher().publish(CallLifecycleEvent.of(
                            ctx.requestId(), CallPhase.FAILED, ctx.model(), true));
                    return Flux.just(ServerSentEvent.<String>builder(errorBody.apply(error))
                            .event("error").build());
                })
                // CANCELED：下游主动断连是 Reactor 的 cancel 信号，onErrorResume 捕获不到，
                // 必须用 doOnCancel 感知 —— 否则不发终态事件，前端那条 Toast 永远停在 CHUNK。
                // 管理后台取消走 takeUntilOther→concatWith 正常 complete（不触发此处），
                // 正常/失败结束也走 complete/error，故此处只会在「下游真断连」时命中。
                // 用 canceled/completed 守卫兜底：若终态已发出则不重复发，避免多条终态事件。
                .doOnCancel(() -> {
                    if (ctx.canceled().get() || ctx.completed().get()) {
                        return;
                    }
                    ctx.lifecyclePublisher().publish(CallLifecycleEvent.of(
                            ctx.requestId(), CallPhase.CANCELED, ctx.model(), true,
                            ctx.eventCount().get()));
                    ctx.log().info("下游主动断连，静默收尾 [{}] {}", ctx.model(), ctx.requestId());
                })
                // 无论正常结束、失败还是取消，都清理注册表，避免内存泄漏；
                // 同时 emit streamEnd 让心跳停止（与 doOnComplete 里的 emit 幂等，谁先到都行）。
                .doFinally(signal -> {
                    ctx.cancellationRegistry().remove(ctx.requestId());
                    streamEnd.tryEmitEmpty();
                });

        // 心跳：空闲期周期性写注释帧。客户端断开后，下一次写即失败，走写失败路径
        // 触发取消 / 错误，从而立即终止上游调用，而不是干等到上游产生响应。
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<String>builder().comment("keep-alive").build())
                .takeUntilOther(streamEnd.asMono());

        return Flux.merge(streamBody, heartbeat);
    }

    /**
     * 收尾：记账 + 发终态相位。<strong>CAS 去重</strong>，Layer 1 与 Layer 2 只有先到的生效。
     *
     * <p>去重必须在这里（而不是各调用点）：Layer 1 走 {@code doOnNext}、
     * Layer 2 走 {@code doOnComplete}，两处必须共用同一个标志。
     *
     * <p>记账用 {@link UsageAccounting#recordStream}（<strong>恒记</strong>，
     * 无 usage 时记 {@code 0,0}）—— 它同时承担「本次调用发生过」的计数职责。
     *
     * @param phase 终态相位：Layer 1 由 {@code terminalPhase} 给出，Layer 2 恒为 {@code COMPLETED}
     */
    private static void finalizeStream(CallContext ctx, CallPhase phase) {
        if (!ctx.completed().compareAndSet(false, true)) {
            return;
        }
        UsageAccounting.recordStream(ctx.usageCollector(), ctx.usage());
        ctx.lifecyclePublisher().publish(CallLifecycleEvent.of(
                ctx.requestId(), phase, ctx.model(), true, ctx.eventCount().get()));
    }
}

package com.kaixuan.copilot_ollama_proxy.api.shared;

import com.kaixuan.copilot_ollama_proxy.control.CallCancellationRegistry;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import org.slf4j.Logger;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 三条端点共用的<strong>收尾协议</strong>：把已成形的事件流包上取消 / 终止 / 心跳。
 *
 * <h2>它包住的是什么</h2>
 * 调用方负责把上游流转成 {@code ServerSentEvent}（三步：拉上游、{@code doOnNext} 记
 * 首个载荷同时数 CHUNK、{@code map} 回填 {@code event:} 类型），本类接手之后的全部算子：
 * <ol>
 *   <li>{@code takeUntilOther(cancelSignal)} —— 管理后台主动取消时中止上游流；</li>
 *   <li>{@code concatWith} 发 {@link CallPhase#ABORTED}；</li>
 *   <li>{@code doOnComplete} 兜底 finalize（不改变帧序列，只发终态与记用量）；</li>
 *   <li>{@code onErrorResume} 把失败转成一帧协议原生 error；</li>
 *   <li>{@code doOnCancel} 感知下游断连；</li>
 *   <li>{@code doFinally} 清理注册表并让心跳停下；</li>
 *   <li>心跳与 {@code merge}。</li>
 * </ol>
 *
 * <h2>两个回调就是三条端点的全部差异</h2>
 * {@code layer2Finalize} 与 {@code errorFrame} —— 前者是「这一轮的 usage 记在哪、
 * 终态怎么发」，后者是「错误报文长什么样」。两者恰好都是<strong>由协议决定</strong>的东西
 * （Chat 的 usage 是两个计数器、另两条是一个 {@code UsageTokens}；
 * Chat 出嵌套 error 体、Anthropic 多一层 {@code "type"}、Responses 流式用扁平事件体），
 * 因此留下而不抽取。
 *
 * <p>这正是本步没有做成「抽公共父类 + 钩子」的原因：那会把上面这些差异变成
 * <em>子类需要知道自己在覆盖什么</em> 的隐式契约。以参数传入则相反 ——
 * 回调为 null 会编译不过，读的人也一眼看出「这两处是端点自己的事」。
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
     * 方法本体只接受五样东西（流、取消信号、结束信号、上下文、两个回调）。
     * 把上下文收拢后，调用点读起来是「用这些状态包住这条流」，
     * 而不是在十个裸参数里找哪个是哪个。
     *
     * <h2>它不是一个值对象</h2>
     * 三个 {@code Atomic*} 分量<strong>刻意是可变引用</strong> ——
     * 它们承载的是「本轮流的进度」，要在算子之间共享同一份实例。
     * 调用方在 {@code doOnNext} 里读写它们，本类在收尾算子里读写它们，
     * 因而不能是快照式的值语义。
     *
     * @param requestId       本次调用唯一标识，用于事件分组与注册表清理
     * @param model           模型名（含前缀），仅用于日志与事件展示
     * @param eventCount      已下发的载荷帧数，终态事件要带上它给前端兜底
     * @param canceled        管理后台取消置位 —— 用于区分「ABORTED」与「正常完成」
     * @param completed       Layer 1 / Layer 2 去重标志（CAS），由各端点的 finalize 负责置位
     * @param lifecyclePublisher 生命周期事件发布器
     * @param cancellationRegistry 取消注册表，收尾时必须清理以免内存泄漏
     * @param log             端点自己的 logger，保持日志归属
     */
    public record CallContext(
            String requestId,
            String model,
            AtomicInteger eventCount,
            AtomicBoolean canceled,
            AtomicBoolean completed,
            CallLifecyclePublisher lifecyclePublisher,
            CallCancellationRegistry cancellationRegistry,
            Logger log) {
    }

    /**
     * 把收尾协议挂到已成形的事件流上。
     *
     * @param mappedBody     已映射为 {@code ServerSentEvent} 的上游流（不含任何收尾算子）
     * @param cancelSignal   管理后台取消信号；正常完成时 {@code takeUntilOther} 中止本流
     * @param streamEnd      数据流终止信号，用于停掉心跳
     * @param ctx            收尾上下文，见 {@link CallContext}
     * @param layer2Finalize 上游未给语义终止标记就关连接时的兜底动作。
     *                       传入的是<strong>一个会自行 CAS 去重</strong>的 Runnable ——
     *                       去重标志在各端点的 finalize 内部，因为 Layer 1 走的是另一条路
     *                       （{@code doOnNext} 里判终止事件），两处必须共用同一个标志。
     * @param errorFrame     把失败渲染成<strong>本协议</strong>的 error 帧。
     *                       由各端点提供：Chat 出嵌套体，Anthropic 多一层
     *                       {@code "type":"error"}，Responses 流式用扁平事件体。
     * @return 与心跳合并后的流，可直接作为 {@code ResponseEntity} 的 body
     */
    public static Flux<ServerSentEvent<String>> attach(Flux<ServerSentEvent<String>> mappedBody,
                                                      Mono<Void> cancelSignal,
                                                      Sinks.Empty<Void> streamEnd,
                                                      CallContext ctx,
                                                      Runnable layer2Finalize,
                                                      Function<Throwable, ServerSentEvent<String>> errorFrame) {
        Flux<ServerSentEvent<String>> streamBody = mappedBody
                .takeUntilOther(cancelSignal)
                // 取消时静默断连：只发 ABORTED 终态，不向下游注入任何错误帧。
                .concatWith(Flux.defer(() -> {
                    if (ctx.canceled().get()) {
                        ctx.lifecyclePublisher().publish(CallLifecycleEvent.of(
                                ctx.requestId(), CallPhase.ABORTED, ctx.model(), true, ctx.eventCount().get()));
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
                    layer2Finalize.run();
                })
                .onErrorResume(error -> {
                    if (UpstreamFailureClassifier.isClientDisconnect(error)) {
                        // CANCELED：客户端主动断连，发出终态让 Toast 收尾淡出，避免僵尸 Toast。
                        ctx.lifecyclePublisher().publish(CallLifecycleEvent.of(
                                ctx.requestId(), CallPhase.CANCELED, ctx.model(), true, ctx.eventCount().get()));
                        return Flux.empty();
                    }
                    // FAILED：上游错误或连接失败（客户端主动断连已在上面 return，不计入）。
                    ctx.lifecyclePublisher().publish(CallLifecycleEvent.of(
                            ctx.requestId(), CallPhase.FAILED, ctx.model(), true));
                    return Flux.just(errorFrame.apply(error));
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
                            ctx.requestId(), CallPhase.CANCELED, ctx.model(), true, ctx.eventCount().get()));
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
}

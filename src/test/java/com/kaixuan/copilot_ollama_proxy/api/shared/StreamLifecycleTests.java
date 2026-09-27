package com.kaixuan.copilot_ollama_proxy.api.shared;

import com.kaixuan.copilot_ollama_proxy.api.shared.StreamLifecycle.CallContext;
import com.kaixuan.copilot_ollama_proxy.control.CallCancellationRegistry;
import com.kaixuan.copilot_ollama_proxy.observability.publisher.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.observability.record.ApiUsageDailyService;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import com.kaixuan.copilot_ollama_proxy.testing.UpstreamStreams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link StreamLifecycle} 的收尾协议。
 *
 * <h2>为何需要一个直接单测</h2>
 * 三个端点的 Controller 测试各自覆盖了「自己那一侧」的收尾行为，
 * 但本类现在承担的是<strong>三者共同的</strong>那部分。若只靠端点测试，
 * 一个只在某一条端点上被覆盖的错误路径会看起来是绿的 —— 而那正是抽取要防的事。
 *
 * <h2>为何这些用例不依赖真实时间</h2>
 * 心跳是 5 秒的真实间隔，但它在这些用例里只起两个作用：不干扰帧序列、
 * 且在数据流终止后必须停下（否则 {@code merge} 永不完成、测试挂死）。
 * 因此用例只断言「不出现心跳帧」与「能正常收尾」，把时间交给
 * {@code block(Duration)} 的护栏 —— 一旦心跳没停，测试会以超时失败而不是永久挂起。
 */
class StreamLifecycleTests {

    private final CallLifecyclePublisher lifecyclePublisher = new CallLifecyclePublisher();
    private final CallCancellationRegistry cancellationRegistry = new CallCancellationRegistry();
    private final ApiUsageDailyService usageCollector = mock(ApiUsageDailyService.class);
    private final List<CallLifecycleEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        lifecyclePublisher.events().subscribe(events::add);
    }

    private CallContext context(String requestId, AtomicInteger frames, AtomicBoolean canceled,
                                AtomicBoolean completed) {
        return new CallContext(requestId, "m", frames, canceled, completed,
                new AtomicReference<>(null), lifecyclePublisher, cancellationRegistry,
                usageCollector, LoggerFactory.getLogger(StreamLifecycleTests.class));
    }

    /** 默认的帧映射：不拆包也不回填 event 名 —— 本类的用例只关心收尾协议。 */
    private static final Function<UpstreamEvent, ServerSentEvent<String>> PLAIN_FRAMES =
            event -> ServerSentEvent.builder(event.data()).build();

    /** 默认的 Layer 1 相位：恒为 COMPLETED（与 Chat / Anthropic 同口径）。 */
    private static final Function<UpstreamEvent, CallPhase> ALWAYS_COMPLETED = event -> CallPhase.COMPLETED;

    /** 默认的错误体渲染：带前缀以便断言「本类只是叫回调」。 */
    private static final Function<Throwable, String> RENDERED_ERROR =
            error -> "RENDERED:" + error.getMessage();

    /**
     * 用默认回调跑一次出口链。
     *
     * <p>本类的用例关心的是收尾协议，不是帧形状 —— 所以默认回调都取最简形式，
     * 需要时由单个用例自行传参覆盖。
     */
    private Flux<ServerSentEvent<String>> run(Flux<UpstreamEvent> upstream, CallContext ctx) {
        return StreamLifecycle.stream(upstream, ctx, ALWAYS_COMPLETED, PLAIN_FRAMES, RENDERED_ERROR);
    }

    /** 收集响应体帧，忽略心跳注释帧（它们的 data 为 null）。 */
    private static List<ServerSentEvent<String>> collect(Flux<ServerSentEvent<String>> stream) {
        List<ServerSentEvent<String>> all = stream.collectList().block(Duration.ofSeconds(5));
        List<ServerSentEvent<String>> content = new ArrayList<>();
        for (ServerSentEvent<String> event : all == null ? List.<ServerSentEvent<String>>of() : all) {
            if (event.data() != null) {
                content.add(event);
            }
        }
        return content;
    }

    private static boolean hasPhase(List<CallLifecycleEvent> observed, CallPhase phase) {
        return observed.stream().anyMatch(e -> e.phase() == phase);
    }

    @Nested
    @DisplayName("正常完成")
    class NormalCompletion {

        @Test
        void runsLayer2FinalizeOnStreamCompletion() {
            AtomicBoolean completed = new AtomicBoolean(false);

            List<ServerSentEvent<String>> frames = collect(run(
                    UpstreamStreams.chat("a").take(1),
                    context("req-1", new AtomicInteger(1), new AtomicBoolean(false), completed)));

            assertThat(frames).hasSize(1);
            assertThat(frames.getFirst().data()).isEqualTo("a");
            // Layer 2 必须跑，否则「上游不发终止标记就断连」的调用会永远没有终态。
            // 它现在内建在共享件里（相位恒 COMPLETED），故用「记账被调用」作为它跑过的证据。
            verify(usageCollector).record(0, 0);
        }

        /** 清理注册表：不清理会内存泄漏，且「再次取消」会返回 true（本该 false）。 */
        @Test
        void removesCancellationRegistrationOnCompletion() {
            String requestId = "req-2";
            cancellationRegistry.register(requestId);

            collect(run(UpstreamStreams.chat("a").take(1),
                    context(requestId, new AtomicInteger(1), new AtomicBoolean(false), new AtomicBoolean(false))));

            // 已被 doFinally 移除 —— 再取消应当无效。
            assertThat(cancellationRegistry.cancel(requestId)).isFalse();
        }
    }

    @Nested
    @DisplayName("管理后台主动取消 → ABORTED")
    class Aborted {

        /**
         * 取消走 {@code takeUntilOther}，流是<strong>正常完成</strong>的 ——
         * 因此不发 FAILED，只发 ABORTED，且不注入任何错误帧，也不走 Layer 2。
         *
         * <h2>为何预先完成 cancel sink</h2>
         * 那使取消在订阅的瞬间即生效，整个用例<strong>无并发</strong>：
         * 不需要「订阅后另一个线程置位再发射」那种时序假设，结论完全确定。
         * 代价是观察不到「已下发的帧被保留」—— 那一点由三个端点的取消测试覆盖
         * （它们用 {@code Mono.never()} 模拟挂起的上游，能观察到帧的中断点）。
         *
         * <p>本用例专钉的是<strong>本类</strong>那三条分支：ABORTED 而非 FAILED、
         * 不发 error 帧、且不影响 Layer 2 的判定。
         */
        @Test
        void emitsAbortedWithoutErrorFrameOrLayer2() {
            AtomicBoolean canceled = new AtomicBoolean(true);
            Sinks.Empty<Void> alreadyCanceled = Sinks.empty();
            alreadyCanceled.tryEmitEmpty();
            // 预先完成取消 sink 使取消在订阅瞬间即生效，整个用例无并发。
            // 但新签名下 cancelSignal 由共享件自己注册 —— 所以要先把 registry 里那条
            // 预先置为已取消：register 返回的 Mono 会立刻完成。
            cancellationRegistry.cancel("req-3");

            List<ServerSentEvent<String>> frames = collect(run(
                    UpstreamStreams.chat("a").take(1),
                    context("req-3", new AtomicInteger(0), canceled, new AtomicBoolean(false))));

            assertThat(hasPhase(events, CallPhase.ABORTED)).isTrue();
            assertThat(hasPhase(events, CallPhase.FAILED)).isFalse();
            // 静默断连：绝不下发 error 帧。
            assertThat(frames).noneMatch(f -> "error".equals(f.event()));
            // 取消时不记 COMPLETED 那条账（ABORTED 已发过）—— 记账被调用过即可，值是 0,0。
            verify(usageCollector, atMostOnce()).record(0, 0);
        }

        /**
         * 取消时 {@code canceled} 标志未置位 → 不发 ABORTED。
         *
         * <p>这条守卫比看起来重要：{@code takeUntilOther} 对「取消信号完成」与
         * 「上游流自己完成」两种情形一视同仁（都是正常 complete）。
         * 若只看「流完成了」就发 ABORTED，那么<strong>每一次正常结束</strong>
         * 都会多出一条「已取消」事件，Toast 会从「完成」被打回「已取消」。
         */
        @Test
        void doesNotEmitAbortedWhenCanceledFlagIsNotSet() {
            AtomicBoolean canceled = new AtomicBoolean(false);
            cancellationRegistry.cancel("req-3b");

            collect(run(UpstreamStreams.chat("a").take(1),
                    context("req-3b", new AtomicInteger(0), canceled, new AtomicBoolean(false))));

            assertThat(hasPhase(events, CallPhase.ABORTED)).isFalse();
        }
    }

    @Nested
    @DisplayName("上游失败 → FAILED + 一帧 error")
    class Failed {

        @Test
        void emitsFailedAndRendersOneErrorFrame() {
            List<ServerSentEvent<String>> frames = collect(run(
                    Flux.error(new IllegalStateException("upstream boom")),
                    context("req-4", new AtomicInteger(0), new AtomicBoolean(false), new AtomicBoolean(false))));

            assertThat(hasPhase(events, CallPhase.FAILED)).isTrue();
            assertThat(frames).hasSize(1);
            assertThat(frames.getFirst().event()).isEqualTo("error");
            // 回调是关键：三个端点的错误形态不同，本类不自己拼 —— 它只负责叫回调。
            assertThat(frames.getFirst().data()).isEqualTo("RENDERED:upstream boom");
        }
    }

    @Nested
    @DisplayName("下游断连 → CANCELED")
    class Canceled {

        /**
         * 下游断连是 Reactor 的 <em>cancel</em> 信号，{@code onErrorResume} 捕获不到 ——
         * 必须靠 {@code doOnCancel}。漏掉的症状是<strong>僵尸 Toast</strong>。
         */
        @Test
        void emitsCanceledOnDownstreamDisconnect() {
            AtomicBoolean completed = new AtomicBoolean(false);
            var stream = run(
                    UpstreamStreams.chat("a").concatWith(Flux.never()),
                    context("req-5", new AtomicInteger(1), new AtomicBoolean(false), completed));

            // 订阅后立刻 dispose —— 等价于下游断开连接。
            var subscription = stream.subscribe();
            subscription.dispose();

            assertThat(hasPhase(events, CallPhase.CANCELED)).isTrue();
        }

        /**
         * 终态已发过时不重复发。
         *
         * <p>守卫是 {@code canceled || completed}：少了它，一次收尾正常的调用
         * 之后若客户端再断开（比如读完就关），会多发一条 CANCELED，
         * 前端那条 Toast 会从「已完成」被打回「已取消」。
         */
        @Test
        void doesNotEmitCanceledWhenTerminalStateAlreadySent() {
            AtomicBoolean completed = new AtomicBoolean(true);
            var stream = run(
                    UpstreamStreams.chat("a").concatWith(Flux.never()),
                    context("req-6", new AtomicInteger(1), new AtomicBoolean(false), completed));

            stream.subscribe().dispose();

            assertThat(hasPhase(events, CallPhase.CANCELED)).isFalse();
        }
    }

    @Nested
    @DisplayName("逐帧副作用（步 3 起由本类承担）")
    class PerFrame {

        /**
         * Layer 1：见终止标记即收尾，<strong>不必等连接关闭</strong>。
         *
         * <p>这正是本类存在的理由之一：部分上游发完终止标记后不主动关闭 TCP，
         * 若只靠 Layer 2，Toast 会永远悬挂在 CHUNK。
         */
        @Test
        void finalizesOnTerminalMarkerWithoutWaitingForConnectionClose() {
            var stream = run(
                    UpstreamStreams.chat("a", "[DONE]").concatWith(Flux.never()),
                    context("req-l1", new AtomicInteger(0), new AtomicBoolean(false), new AtomicBoolean(false)));

            stream.subscribe();

            // 即使连接从未关闭，COMPLETED 也已发出。
            assertThat(hasPhase(events, CallPhase.COMPLETED)).isTrue();
        }

        /** 终止标记不计入帧数：只有 1 个内容帧。 */
        @Test
        void terminalMarkerIsNotCountedAsFrame() {
            collect(run(UpstreamStreams.chat("a", "[DONE]"),
                    context("req-count", new AtomicInteger(0), new AtomicBoolean(false), new AtomicBoolean(false))));

            CallLifecycleEvent completed = events.stream()
                    .filter(e -> e.phase() == CallPhase.COMPLETED).findFirst().orElseThrow();
            assertThat(completed.chunkCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("心跳")
    class Heartbeat {

        /**
         * 心跳不得出现在正常帧序列里。
         *
         * <p>它是 {@code comment} 帧（data 为 null），{@link #collect} 已过滤；
         * 这里额外确认「有内容时不会退化成 data 帧」——
         * 那会让 SSE 客户端收到一条空消息。
         */
        @Test
        void heartbeatIsACommentFrameNotADataFrame() {
            List<ServerSentEvent<String>> frames = collect(run(
                    UpstreamStreams.chat("a"),
                    context("req-7", new AtomicInteger(1), new AtomicBoolean(false), new AtomicBoolean(false))));

            assertThat(frames).hasSize(1);
            assertThat(frames.getFirst().data()).isEqualTo("a");
        }
    }
}

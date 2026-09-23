package com.kaixuan.copilot_ollama_proxy.api.shared;

import com.kaixuan.copilot_ollama_proxy.api.shared.StreamLifecycle.CallContext;
import com.kaixuan.copilot_ollama_proxy.control.CallCancellationRegistry;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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
    private final List<CallLifecycleEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        lifecyclePublisher.events().subscribe(events::add);
    }

    private CallContext context(String requestId, AtomicInteger frames, AtomicBoolean canceled,
                                AtomicBoolean completed) {
        return new CallContext(requestId, "m", frames, canceled, completed,
                lifecyclePublisher, cancellationRegistry, LoggerFactory.getLogger(StreamLifecycleTests.class));
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
            AtomicBoolean layer2Ran = new AtomicBoolean(false);

            List<ServerSentEvent<String>> frames = collect(StreamLifecycle.attach(
                    Flux.just(ServerSentEvent.builder("a").build()),
                    Mono.never(), Sinks.empty(),
                    context("req-1", new AtomicInteger(1), new AtomicBoolean(false), completed),
                    () -> layer2Ran.set(true),
                    error -> ServerSentEvent.<String>builder("err").event("error").build()));

            assertThat(frames).hasSize(1);
            assertThat(frames.getFirst().data()).isEqualTo("a");
            // Layer 2 必须跑，否则「上游不发终止标记就断连」的调用会永远没有终态。
            assertThat(layer2Ran).isTrue();
        }

        /** 清理注册表：不清理会内存泄漏，且「再次取消」会返回 true（本该 false）。 */
        @Test
        void removesCancellationRegistrationOnCompletion() {
            String requestId = "req-2";
            cancellationRegistry.register(requestId);

            collect(StreamLifecycle.attach(
                    Flux.just(ServerSentEvent.builder("a").build()),
                    Mono.never(), Sinks.empty(),
                    context(requestId, new AtomicInteger(1), new AtomicBoolean(false), new AtomicBoolean(false)),
                    () -> { },
                    error -> ServerSentEvent.<String>builder("err").event("error").build()));

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
            AtomicBoolean layer2Ran = new AtomicBoolean(false);
            Sinks.Empty<Void> alreadyCanceled = Sinks.empty();
            alreadyCanceled.tryEmitEmpty();

            List<ServerSentEvent<String>> frames = collect(StreamLifecycle.attach(
                    Flux.just(ServerSentEvent.builder("a").build()),
                    alreadyCanceled.asMono(), Sinks.empty(),
                    context("req-3", new AtomicInteger(0), canceled, new AtomicBoolean(false)),
                    () -> layer2Ran.set(true),
                    error -> ServerSentEvent.<String>builder("err").event("error").build()));

            assertThat(hasPhase(events, CallPhase.ABORTED)).isTrue();
            assertThat(hasPhase(events, CallPhase.FAILED)).isFalse();
            // 静默断连：绝不下发 error 帧。
            assertThat(frames).noneMatch(f -> "error".equals(f.event()));
            // 取消时不走 Layer 2（已由 concatWith 发过 ABORTED）。
            assertThat(layer2Ran).isFalse();
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
            Sinks.Empty<Void> alreadyCanceled = Sinks.empty();
            alreadyCanceled.tryEmitEmpty();

            collect(StreamLifecycle.attach(
                    Flux.just(ServerSentEvent.builder("a").build()),
                    alreadyCanceled.asMono(), Sinks.empty(),
                    context("req-3b", new AtomicInteger(0), canceled, new AtomicBoolean(false)),
                    () -> { },
                    error -> ServerSentEvent.<String>builder("err").event("error").build()));

            assertThat(hasPhase(events, CallPhase.ABORTED)).isFalse();
        }
    }

    @Nested
    @DisplayName("上游失败 → FAILED + 一帧 error")
    class Failed {

        @Test
        void emitsFailedAndRendersOneErrorFrame() {
            List<ServerSentEvent<String>> frames = collect(StreamLifecycle.attach(
                    Flux.error(new IllegalStateException("upstream boom")),
                    Mono.never(), Sinks.empty(),
                    context("req-4", new AtomicInteger(0), new AtomicBoolean(false), new AtomicBoolean(false)),
                    () -> { },
                    error -> ServerSentEvent.<String>builder("RENDERED:" + error.getMessage())
                            .event("error").build()));

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
            var stream = StreamLifecycle.attach(
                    Flux.concat(Flux.just(ServerSentEvent.builder("a").build()), Mono.never()),
                    Mono.never(), Sinks.empty(),
                    context("req-5", new AtomicInteger(1), new AtomicBoolean(false), completed),
                    () -> { },
                    error -> ServerSentEvent.<String>builder("err").event("error").build());

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
            var stream = StreamLifecycle.attach(
                    Flux.concat(Flux.just(ServerSentEvent.builder("a").build()), Mono.never()),
                    Mono.never(), Sinks.empty(),
                    context("req-6", new AtomicInteger(1), new AtomicBoolean(false), completed),
                    () -> { },
                    error -> ServerSentEvent.<String>builder("err").event("error").build());

            stream.subscribe().dispose();

            assertThat(hasPhase(events, CallPhase.CANCELED)).isFalse();
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
            List<ServerSentEvent<String>> frames = collect(StreamLifecycle.attach(
                    Flux.just(ServerSentEvent.builder("a").build()),
                    Mono.never(), Sinks.empty(),
                    context("req-7", new AtomicInteger(1), new AtomicBoolean(false), new AtomicBoolean(false)),
                    () -> { },
                    error -> ServerSentEvent.<String>builder("err").event("error").build()));

            assertThat(frames).hasSize(1);
            assertThat(frames.getFirst().data()).isEqualTo("a");
        }
    }
}

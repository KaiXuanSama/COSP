package com.kaixuan.copilot_ollama_proxy.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CallResendLoop} 的中断-重发语义。
 *
 * <h2>为何这些用例值得存在</h2>
 * 本类取代的是三个执行器里三份同构的递归循环。那三份此前只被
 * 「触发信号 → 上游被重新请求」那条集成用例间接覆盖，且<strong>只在 Chat / Anthropic 两侧</strong>
 * （Responses 没有任何直测）。
 *
 * <p>更关键的是它有两处<strong>断了不会响</strong>的性质，集成用例验不到：
 * <ul>
 *   <li><strong>信号挂在整轮而非单次往返上</strong> —— 挂错位置时「请求进行中」仍可被中断，
 *       只有「退避等待中」失效。而那是最需要该功能的时刻，集成用例通常只测前者；</li>
 *   <li><strong>未注入注册表时退化为单轮</strong> —— 三个执行器的测试夹具都直接 new、
 *       注册表为 null。若退化不成立，那些测试会全部挂起在 {@code Mono.never()} 上，
 *       而「挂起」表现为超时而非断言失败，极难定位。</li>
 * </ul>
 *
 * <p>断言用手动订阅 + {@code CountDownLatch} 而非 {@code StepVerifier}：
 * 本项目<strong>没有引 {@code reactor-test} 依赖</strong>（这是既有决定，
 * 见 {@code AnthropicSilentRetryBehaviorTests} 的同类说明），
 * 而这里要断言的只是「第几轮到达」与「终止信号」，用不上虚拟时间与背压控制。
 */
class CallResendLoopTests {

    private static final Logger LOG = LoggerFactory.getLogger(CallResendLoopTests.class);

    /** 订阅到结束，返回收到的元素；超时则失败。 */
    private static List<String> collect(Flux<String> flux) {
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        flux.doOnNext(received::add)
                .doOnError(error::set)
                .doFinally(signal -> completed.countDown())
                .subscribe();

        try {
            assertThat(completed.await(10, TimeUnit.SECONDS))
                    .as("流应当在 10 秒内终结（挂起说明信号没接通）")
                    .isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待流终结时被中断", interrupted);
        }
        assertThat(error.get()).as("本类不吞异常").isNull();
        return received;
    }

    @Nested
    @DisplayName("未注入注册表：退化为单轮")
    class WithoutRegistry {

        @Test
        @DisplayName("注册表为 null 时信号恒为 never，正常完成的一轮照常下发")
        void nullRegistryKeepsSingleRound() {
            AtomicInteger rounds = new AtomicInteger(0);

            List<String> received = collect(CallResendLoop.loop(
                    Flux.defer(() -> Flux.just("round-" + rounds.incrementAndGet())),
                    null, "req-1", "OpenAI", LOG, "model-a"));

            assertThat(received).containsExactly("round-1");
            assertThat(rounds.get()).as("不应有多余轮次").isEqualTo(1);
        }

        @Test
        @DisplayName("requestId 为 null 时同上（注册表有值也不注册）")
        void nullRequestIdKeepsSingleRound() {
            CallRetryRegistry registry = new CallRetryRegistry();
            AtomicInteger rounds = new AtomicInteger(0);

            List<String> received = collect(CallResendLoop.loop(
                    Flux.defer(() -> Flux.just("round-" + rounds.incrementAndGet())),
                    registry, null, "OpenAI", LOG, "model-a"));

            assertThat(received).containsExactly("round-1");
            assertThat(rounds.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("信号触发后的递归重发")
    class Recursion {

        /**
         * 触发信号 → 当前轮被中断（无值完成）→ 自动再来一轮 → 新内容续在同一条流上。
         *
         * <p>这正是「静默」二字的含义：下游只看到一个连续流，
         * 不会察觉中间换过一次上游往返。
         */
        @Test
        @DisplayName("中断后重新发起，两轮内容先后下发到同一条流")
        void reissuesAfterSignal() throws Exception {
            CallRetryRegistry registry = new CallRetryRegistry();
            AtomicInteger rounds = new AtomicInteger(0);

            // 第一轮发一帧后挂起 —— 必须有帧到达才能确定「已进入第一轮」，
            // 否则信号可能早于注册送达（注册发生在 defer 内）。
            Flux<String> attempt = Flux.defer(() -> rounds.incrementAndGet() == 1
                    ? Flux.concat(Mono.just("first-round"), Flux.never())
                    : Flux.just("second-round"));

            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch firstArrived = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(1);

            CallResendLoop.loop(attempt, registry, "req-2", "OpenAI", LOG, "model-a")
                    .doOnNext(data -> {
                        received.add(data);
                        if ("first-round".equals(data)) {
                            firstArrived.countDown();
                        }
                    })
                    .doFinally(signal -> completed.countDown())
                    .subscribe();

            assertThat(firstArrived.await(10, TimeUnit.SECONDS))
                    .as("首轮内容应当先到达").isTrue();
            assertThat(registry.retry("req-2"))
                    .as("调用存活期间信号必须可触发").isTrue();
            assertThat(completed.await(10, TimeUnit.SECONDS))
                    .as("重发的那一轮应当正常结束整条流").isTrue();

            assertThat(rounds.get()).as("信号触发后必须真的再打一次").isEqualTo(2);
            assertThat(received).containsExactly("first-round", "second-round");
        }

        /**
         * 每轮重新注册<strong>新鲜</strong>的信号，因此可以连续点击。
         *
         * <p>若复用同一个 sink，第二次点击会落在一个已完成的信号上 ——
         * 症状是「点第一次有用，之后没反应」，而前端看不出原因。
         */
        @Test
        @DisplayName("连续触发两次，每次都能再发起一轮")
        void canBeTriggeredRepeatedly() throws Exception {
            CallRetryRegistry registry = new CallRetryRegistry();
            AtomicInteger rounds = new AtomicInteger(0);

            Flux<String> attempt = Flux.defer(() -> {
                int round = rounds.incrementAndGet();
                return Flux.concat(Mono.just("round-" + round),
                        round <= 2 ? Flux.never() : Flux.empty());
            });

            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch firstArrived = new CountDownLatch(1);
            CountDownLatch secondArrived = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(1);

            CallResendLoop.loop(attempt, registry, "req-3", "OpenAI", LOG, "model-a")
                    .doOnNext(data -> {
                        received.add(data);
                        if ("round-1".equals(data)) {
                            firstArrived.countDown();
                        }
                        if ("round-2".equals(data)) {
                            secondArrived.countDown();
                        }
                    })
                    .doFinally(signal -> completed.countDown())
                    .subscribe();

            assertThat(firstArrived.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.retry("req-3")).as("第一次点击").isTrue();
            assertThat(secondArrived.await(10, TimeUnit.SECONDS))
                    .as("第一次点击后应进入第二轮").isTrue();
            assertThat(registry.retry("req-3")).as("第二次点击必须仍能触发（信号是新鲜的）").isTrue();
            assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(rounds.get()).as("两次点击 = 三轮尝试").isEqualTo(3);
            assertThat(received).containsExactly("round-1", "round-2", "round-3");
        }

        /** 未触发时不重发 —— 否则每条正常结束的流都会无限重发。 */
        @Test
        @DisplayName("未触发则只有一轮，不凭空重发")
        void noSignalMeansNoExtraRound() {
            CallRetryRegistry registry = new CallRetryRegistry();
            AtomicInteger rounds = new AtomicInteger(0);

            List<String> received = collect(CallResendLoop.loop(
                    Flux.defer(() -> Flux.just("round-" + rounds.incrementAndGet())),
                    registry, "req-4", "OpenAI", LOG, "model-a"));

            assertThat(received).containsExactly("round-1");
            assertThat(rounds.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("信号挂在整轮上，而非单次往返")
    class SignalScope {

        /**
         * <strong>本类最重要的一条</strong>：退避等待期间也必须能被中断。
         *
         * <h2>构造方式与它为何能区分两种挂载位置</h2>
         * 单轮尝试做成「先出一帧、然后静默等待很久」的形状 —— 那正是
         * 一轮里首个往返已结束、正处在 {@code retryWhen} 退避期的样子：
         * 链上没有任何元素在动，也没有完成的迹象。
         *
         * <p>信号若挂在<strong>这一整轮</strong>上（本类的实现），它此时仍活着，
         * 能立刻切断那段长等待并进入下一轮；
         * 若挂在<strong>单次往返</strong>上，它会随首个往返一起终结，
         * 等待将走满 30 秒 —— 而本用例给整条流的上限是 10 秒，因此会失败。
         *
         * <p>现实意义：上游反复失败、用户正等着退避，是<strong>最想点重试的时刻</strong>。
         */
        @Test
        @DisplayName("退避等待窗口内仍可被中断（这正是最需要它的时刻）")
        void interruptsDuringBackoffWindow() throws Exception {
            CallRetryRegistry registry = new CallRetryRegistry();
            AtomicInteger rounds = new AtomicInteger(0);

            Flux<String> attempt = Flux.defer(() -> {
                int round = rounds.incrementAndGet();
                // 第 1 轮：出一帧后进入「退避」——30 秒内无元素、也不完成。
                // 第 2 轮：立刻完成，证明前一截确实被信号切断而非走满。
                return round == 1
                        ? Flux.concat(Mono.just("first-attempt"),
                                Mono.delay(Duration.ofSeconds(30)).thenReturn("too-late"))
                        : Flux.just("second-round");
            });

            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch firstArrived = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Duration> elapsed = new AtomicReference<>();

            long start = System.nanoTime();
            CallResendLoop.loop(attempt, registry, "req-5", "OpenAI", LOG, "model-a")
                    .doOnNext(data -> {
                        received.add(data);
                        if ("first-attempt".equals(data)) {
                            firstArrived.countDown();
                        }
                    })
                    .doFinally(signal -> {
                        elapsed.set(Duration.ofNanos(System.nanoTime() - start));
                        completed.countDown();
                    })
                    .subscribe();

            assertThat(firstArrived.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.retry("req-5")).isTrue();
            assertThat(completed.await(10, TimeUnit.SECONDS))
                    .as("退避窗口必须被信号切断；等到 30 秒说明信号挂错了层级")
                    .isTrue();

            assertThat(rounds.get()).as("切断了退避就必须真的再发起一轮").isEqualTo(2);
            assertThat(received).containsExactly("first-attempt", "second-round");
            assertThat(elapsed.get())
                    .as("远早于那段 30 秒的等待，说明确实是被切断的")
                    .isLessThan(Duration.ofSeconds(20));
        }
    }

    @Nested
    @DisplayName("协议名只影响日志")
    class ProtocolLabel {

        /** label 是唯一的协议相关输入 —— 它进日志，不进行为。 */
        @Test
        @DisplayName("三条线路的 label 走同一条码路，行为一致")
        void labelDoesNotAffectBehavior() throws Exception {
            for (String label : List.of("OpenAI", "Anthropic", "Responses")) {
                CallRetryRegistry registry = new CallRetryRegistry();
                AtomicInteger rounds = new AtomicInteger(0);

                // 与 Recursion.reissuesAfterSignal 同一场景，只换 label ——
                // 若 label 意外参与了判定，三者中至少一个会不同。
                Flux<String> attempt = Flux.defer(() -> rounds.incrementAndGet() == 1
                        ? Flux.concat(Mono.just("first-round"), Flux.never())
                        : Flux.just("second-round"));

                List<String> received = new CopyOnWriteArrayList<>();
                CountDownLatch firstArrived = new CountDownLatch(1);
                CountDownLatch completed = new CountDownLatch(1);

                CallResendLoop.loop(attempt, registry, "req-6", label, LOG, "model-a")
                        .doOnNext(data -> {
                            received.add(data);
                            if ("first-round".equals(data)) {
                                firstArrived.countDown();
                            }
                        })
                        .doFinally(signal -> completed.countDown())
                        .subscribe();

                assertThat(firstArrived.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(registry.retry("req-6")).isTrue();
                assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();

                assertThat(rounds.get())
                        .as("label=%s 时信号重发必须照常", label)
                        .isEqualTo(2);
                assertThat(received).containsExactly("first-round", "second-round");
            }
        }
    }
}

package com.kaixuan.copilot_ollama_proxy.upstream;

import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UpstreamAutoRetry} 构造出的重试规格 —— <strong>真跑一遍</strong>。
 *
 * <h2>为何这些用例值得存在</h2>
 * 本类取代的是三个执行器里三份 {@code buildRetrySpec}（阶段 3.6c-2）。那三份此前
 * <strong>没有直接单测</strong> —— 只被「重试到耗尽」那类集成用例间接覆盖，
 * 而那类用例要起真实上游 stub，成本高且只说「重试了」不说「按什么规格重试」。
 *
 * <p>本用例不读 {@code Retry} 的字段（那些是 Reactor 的内部形状，随版本变），
 * 而是把规格<strong>挂到一条会失败的流上</strong>，数「上游被调了几次」、
 * 「事件发了几个」。这是行为而不是实现细节。
 *
 * <h2>不测日志文案（刻意的）</h2>
 * 429 的日志特化是本步的行为变更之一，但本项目<strong>没有日志捕获设施，
 * 且既有约定不把日志文案当断言目标</strong>（见 {@code UpstreamCallReporterTests}：
 * 「mock 一个 Logger 会让『日志文案有没有变』变成断言目标，而那属于实现细节」）。
 * 故这里只钉「429 走同一条可重试判定」，文案由单个实现保证一致性。
 */
class UpstreamAutoRetryTests {

    private static final Logger LOG = LoggerFactory.getLogger(UpstreamAutoRetryTests.class);

    /** 毫秒级退避：本类验的是「重试几次、发几个事件」，真实退避时长不是被测行为。 */
    private static final Duration FIRST = Duration.ofMillis(1);
    private static final Duration MAX = Duration.ofMillis(2);

    private static UpstreamAutoRetry.CallContext ctx() {
        return new UpstreamAutoRetry.CallContext("someMethod", "OpenAI",
                "relay-x", "req-1", "model-a", true);
    }

    private static WebClientResponseException upstreamError(HttpStatus status, String body) {
        return WebClientResponseException.create(status.value(), status.getReasonPhrase(),
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), null);
    }

    /** 固定次数的策略服务替身 —— 直接给值，不碰 JDBC。 */
    private static RetryPolicyService fixedPolicy(int maxAttempts) {
        return new RetryPolicyService(null) {
            @Override
            public int getMaxAttempts() {
                return maxAttempts;
            }
        };
    }

    /** 一次运行的观测量。 */
    private record Outcome(int attempts, boolean succeeded, List<CallLifecycleEvent> events) {
    }

    /**
     * 跑一条「前 {@code successOnAttempt - 1} 次失败、之后成功」的流，数实际尝试次数。
     *
     * <p>用 {@code onErrorResume} 接住耗尽后的异常而不是让 {@code block()} 抛 ——
     * 那样「耗尽」与「成功」可以用同一个返回值区分，断言更好读。
     */
    private static Outcome run(RetryPolicyService policy, Throwable failure, int successOnAttempt) {
        List<CallLifecycleEvent> events = new ArrayList<>();
        CallLifecycleNotifier notifier = events::add;
        Retry retry = UpstreamAutoRetry.build(ctx(), policy, notifier, FIRST, MAX, LOG);

        AtomicInteger attempts = new AtomicInteger();
        List<String> received = Flux.defer(() -> {
                    int n = attempts.incrementAndGet();
                    return n < successOnAttempt
                            ? Flux.<String>error(failure)
                            : Flux.just("ok");
                })
                .retryWhen(retry)
                .onErrorResume(e -> Flux.empty())
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(received).as("block 不应超时").isNotNull();
        return new Outcome(attempts.get(), !received.isEmpty(),
                events.stream().filter(e -> e.phase() == CallPhase.RETRYING).toList());
    }

    private static final Throwable RETRYABLE_5XX =
            upstreamError(HttpStatus.SERVICE_UNAVAILABLE, "boom");

    @Nested
    @DisplayName("次数配置的四档")
    class AttemptBudget {

        /** 正数 N = 首次之外再试 N 次 —— 总尝试次数为 N+1（Reactor 的 maxAttempts 口径）。 */
        @Test
        @DisplayName("正数 3 → 总尝试 4 次")
        void positiveRetriesThatManyTimes() {
            Outcome outcome = run(fixedPolicy(3), RETRYABLE_5XX, 4);

            assertThat(outcome.attempts()).isEqualTo(4);
            assertThat(outcome.succeeded()).isTrue();
            assertThat(outcome.events()).hasSize(3);
        }

        /**
         * {@code 0} 与「不加 retryWhen」并不完全等价：{@code filter} 与 {@code doBeforeRetry}
         * 依旧挂着，只是永不重订阅。保留这条链而不做分支，是为了让重试次数只有一个来源。
         */
        @Test
        @DisplayName("0 → 只调一次，不重试")
        void zeroMeansNoRetry() {
            Outcome outcome = run(fixedPolicy(0), RETRYABLE_5XX, 2);

            assertThat(outcome.attempts()).as("首次失败即透传，不该有第二次").isEqualTo(1);
            assertThat(outcome.succeeded()).isFalse();
            assertThat(outcome.events()).isEmpty();
        }

        /** 未注入策略服务是单元测试直接 new 执行器的常态 —— 必须回退默认值而非 NPE。 */
        @Test
        @DisplayName("未注入策略服务 → 回退默认 5 次")
        void nullPolicyFallsBackToDefault() {
            Outcome outcome = run(null, RETRYABLE_5XX, 6);

            assertThat(outcome.attempts())
                    .as("默认 %d 次重试 → 总尝试 %d 次",
                            RetryPolicyService.DEFAULT_MAX_ATTEMPTS,
                            RetryPolicyService.DEFAULT_MAX_ATTEMPTS + 1)
                    .isEqualTo(RetryPolicyService.DEFAULT_MAX_ATTEMPTS + 1);
            assertThat(outcome.succeeded()).isTrue();
        }

        /**
         * <strong>{@code -1} 真的无限</strong> —— 本类用「撑过默认预算」来证明，
         * 而不是去读 {@code Retry.maxAttempts} 那个内部字段。
         *
         * <p>构造：第 8 次才成功（即需要 7 次重试）。
         * 有限 5 次预算只能到第 6 次尝试就耗尽，无限则能撑到第 8 次。
         * 若某天 {@code -1} 被误当成「按默认值处理」，第一段断言会红。
         */
        @Test
        @DisplayName("-1 → 撑过默认预算（第 8 次才成功时仍能成功）")
        void minusOneMeansUnlimited() {
            Outcome unlimited = run(fixedPolicy(RetryPolicyService.UNLIMITED_MAX_ATTEMPTS),
                    RETRYABLE_5XX, 8);
            Outcome finite = run(fixedPolicy(RetryPolicyService.DEFAULT_MAX_ATTEMPTS),
                    RETRYABLE_5XX, 8);

            assertThat(unlimited.attempts())
                    .as("无限模式必须撑到第 8 次（7 次重试）").isEqualTo(8);
            assertThat(unlimited.succeeded()).isTrue();
            assertThat(finite.succeeded())
                    .as("有限 5 次预算撑不到第 8 次 —— 两条路径必须真的不同").isFalse();
            assertThat(finite.attempts()).isEqualTo(RetryPolicyService.DEFAULT_MAX_ATTEMPTS + 1);
        }
    }

    @Nested
    @DisplayName("可重试判定已接上")
    class RetryableFilter {

        /**
         * 429 是 <strong>HTTP 层</strong>事实 —— 判定（{@link UpstreamRetryPolicy}）三线共用，
         * 收归前只有 Chat 的<em>日志</em>带特化，那是抄漏。本用例钉住 429 确实会重试。
         */
        @Test
        @DisplayName("429 会重试")
        void rateLimitIsRetried() {
            Outcome outcome = run(fixedPolicy(2),
                    upstreamError(HttpStatus.TOO_MANY_REQUESTS, "slow down"), 3);

            assertThat(outcome.attempts()).isEqualTo(3);
            assertThat(outcome.succeeded()).isTrue();
            assertThat(outcome.events()).hasSize(2);
        }

        /**
         * 反方向同样重要：401 这类确定性 4xx <strong>不得</strong>重试 ——
         * 请求内容未变，重试结果必然相同，只是白等 62 秒。
         *
         * <p>这条同时守住「{@code .filter} 确实挂上了」。若收归时漏掉 filter，
         * 401 会被重试满预算，而症状是「用户以为服务卡死」。
         */
        @Test
        @DisplayName("401 不重试（确定性 4xx，重试只会白等）")
        void deterministicClientErrorIsNotRetried() {
            Outcome outcome = run(fixedPolicy(5),
                    upstreamError(HttpStatus.UNAUTHORIZED, "bad key"), 6);

            assertThat(outcome.attempts()).as("首轮即透传").isEqualTo(1);
            assertThat(outcome.events()).isEmpty();
        }
    }

    @Nested
    @DisplayName("RETRYING 生命周期事件")
    class RetryingEvents {

        /**
         * 事件是前端的「正在重试（第 N 次）」Toast 的来源；
         * 不发或发错次数会让重试期间静默卡顿，用户以为服务卡死。
         */
        @Test
        @DisplayName("每次重试发一个，attempt 从 1 递增")
        void oneEventPerRetryWithIncrementingAttempt() {
            Outcome outcome = run(fixedPolicy(3), RETRYABLE_5XX, 4);

            assertThat(outcome.events()).hasSize(3);
            assertThat(outcome.events()).extracting(CallLifecycleEvent::attempt)
                    .containsExactly(1, 2, 3);
        }

        /**
         * <strong>本类最重要的一条</strong>：事件里的 requestId 与 model 必须各归各位。
         *
         * <p>这两个字段在 {@link UpstreamAutoRetry.CallContext} 里<strong>相邻且同为
         * {@code String}</strong> —— 构造时对调<strong>不会编译失败</strong>，
         * 症状只是 Toast 与日志里模型名变成 requestId。把它们收进 record 正是为了
         * 让构造点只有一处、一眼可核；本用例从行为侧再钉一次。
         */
        @Test
        @DisplayName("requestId 与 model 不串位（record 的相邻同型字段）")
        void requestIdAndModelDoNotSwap() {
            Outcome outcome = run(fixedPolicy(1), RETRYABLE_5XX, 2);

            assertThat(outcome.events()).hasSize(1);
            CallLifecycleEvent event = outcome.events().getFirst();
            assertThat(event.requestId()).as("不能是 model-a").isEqualTo("req-1");
            assertThat(event.model()).as("不能是 req-1").isEqualTo("model-a");
            assertThat(event.stream()).isTrue();
        }

        /** 未注入 notifier（测试常态）时不得抛错 —— 观测失败不能伤到主链路。 */
        @Test
        @DisplayName("notifier 未注入时照常重试，不抛错")
        void nullNotifierDoesNotBreakRetry() {
            Retry retry = UpstreamAutoRetry.build(ctx(), fixedPolicy(1), null, FIRST, MAX, LOG);
            AtomicInteger attempts = new AtomicInteger();

            List<String> received = Flux.defer(() -> {
                        attempts.incrementAndGet();
                        return Flux.<String>error(RETRYABLE_5XX);
                    })
                    .retryWhen(retry)
                    .onErrorResume(e -> Flux.empty())
                    .collectList()
                    .block(Duration.ofSeconds(10));

            assertThat(received).isEmpty();
            assertThat(attempts.get()).as("重试本身不该因缺 notifier 而失效").isEqualTo(2);
        }
    }
}

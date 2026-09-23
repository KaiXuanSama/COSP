package com.kaixuan.copilot_ollama_proxy.upstream;

import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * {@link UpstreamCallReporter} 的容错契约。
 *
 * <h2>这里真正在测什么</h2>
 * 不是「事件有没有发出去」—— 那只证明转发了一行调用。核心被测行为是
 * <strong>「观测失败不得伤到主链路」</strong>：三个执行器里这些调用点都长在
 * {@code doOnNext} / {@code doFinally} / {@code doBeforeRetry} 里，
 * 一个向上冒出的异常会让整轮对话失败。
 *
 * <p>因此每个「失败」用例都断言两件事：<strong>不抛异常</strong>，
 * 且<strong>确实尝试过调用</strong>。只断言前者的话，
 * 把方法体换成空实现也能通过 —— 而那会让所有观测静默消失。
 *
 * <h2>为何用真实的 logger 而不是 mock</h2>
 * logger 是参数（为了保留日志归属），但它的行为不是被测对象。
 * mock 一个 {@code Logger} 会让「日志文案有没有变」变成断言目标，
 * 而那属于实现细节；本类的契约是「不抛」。
 */
class UpstreamCallReporterTests {

    /** 测试用 logger：交由 slf4j 走标准输出，不参与断言。 */
    private Logger log;

    @BeforeEach
    void setUp() {
        log = org.slf4j.LoggerFactory.getLogger(UpstreamCallReporterTests.class);
    }

    private static CallLifecycleEvent sampleEvent() {
        return CallLifecycleEvent.of("req-1", CallPhase.CONNECTED, "m", true);
    }

    @Nested
    @DisplayName("生命周期事件")
    class Lifecycle {

        @Test
        void publishesToNotifierWhenPresent() {
            List<CallLifecycleEvent> received = new ArrayList<>();

            UpstreamCallReporter.publishLifecycle(log, received::add, sampleEvent());

            assertThat(received).hasSize(1);
            assertThat(received.getFirst().requestId()).isEqualTo("req-1");
        }

        /** 未注入（如单元测试直接 new 执行器）时静默跳过 —— 缺省即不发。 */
        @Test
        void silentlySkipsWhenNotifierNotInjected() {
            assertThatCode(() -> UpstreamCallReporter.publishLifecycle(log, null, sampleEvent()))
                    .doesNotThrowAnyException();
        }

        /**
         * 推送层抛异常不得外溢。
         *
         * <p>这些调用点长在 Reactor 算子里，异常会变成流上的 error 信号 ——
         * 一个「Toast 没更新」的观测问题会升级成「这次对话失败」。
         */
        @Test
        void swallowsRuntimeExceptionFromNotifier() {
            CallLifecycleNotifier throwing = mock(CallLifecycleNotifier.class);
            org.mockito.BDDMockito.willThrow(new IllegalStateException("sink closed"))
                    .given(throwing).publish(org.mockito.ArgumentMatchers.any());

            assertThatCode(() -> UpstreamCallReporter.publishLifecycle(log, throwing, sampleEvent()))
                    .doesNotThrowAnyException();
            org.mockito.Mockito.verify(throwing).publish(org.mockito.ArgumentMatchers.any());
        }

        /**
         * 包装成 RuntimeException 的受检异常同样吞掉。
         *
         * <p>这是受检异常在生产里唯一可能的形态：
         * {@link CallLifecycleNotifier#publish} 未声明受检异常，实现只能包一层再抛，
         * 常见的是 {@code UncheckedIOException}。
         */
        @Test
        void swallowsWrappedCheckedExceptionFromNotifier() {
            CallLifecycleNotifier throwing = mock(CallLifecycleNotifier.class);
            org.mockito.BDDMockito.willThrow(new java.io.UncheckedIOException("wrapped", new java.io.IOException("io")))
                    .given(throwing).publish(org.mockito.ArgumentMatchers.any());

            assertThatCode(() -> UpstreamCallReporter.publishLifecycle(log, throwing, sampleEvent()))
                    .doesNotThrowAnyException();
        }

        /**
         * 一次失败不得污染后续调用（本类无状态）。
         *
         * <p>少了这条，把失败标志存在静态字段里这类写法也能让其余用例通过，
         * 而那种缺陷的症状是「一个推送失败以后，该执行器再也不发事件」。
         */
        @Test
        void aFailureDoesNotAffectSubsequentCalls() {
            CallLifecycleNotifier flaky = mock(CallLifecycleNotifier.class);
            org.mockito.BDDMockito.willThrow(new IllegalStateException("first"))
                    .willDoNothing()
                    .given(flaky).publish(org.mockito.ArgumentMatchers.any());

            assertThatCode(() -> UpstreamCallReporter.publishLifecycle(log, flaky, sampleEvent()))
                    .doesNotThrowAnyException();
            assertThatCode(() -> UpstreamCallReporter.publishLifecycle(log, flaky, sampleEvent()))
                    .doesNotThrowAnyException();

            org.mockito.Mockito.verify(flaky, org.mockito.Mockito.times(2))
                    .publish(org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested
    @DisplayName("调用记录变更信号")
    class CallRecorded {

        @Test
        void publishesToLogServiceWhenPresent() {
            ApiCallLogService logService = mock(ApiCallLogService.class);

            UpstreamCallReporter.publishCallRecorded(log, logService);

            org.mockito.Mockito.verify(logService).publishCallRecorded();
        }

        @Test
        void silentlySkipsWhenLogServiceNotInjected() {
            assertThatCode(() -> UpstreamCallReporter.publishCallRecorded(log, null))
                    .doesNotThrowAnyException();
        }

        /**
         * 与 save* 的容错策略一致：推送失败不影响主调用链。
         *
         * <p>尤其不能在这里抛 —— 调用点之一是 {@code doFinally}，
         * 那里的异常会覆盖掉原本的终止信号，症状极难定位。
         */
        @Test
        void swallowsExceptionFromLogService() {
            ApiCallLogService throwing = mock(ApiCallLogService.class);
            org.mockito.BDDMockito.willThrow(new IllegalStateException("sink closed"))
                    .given(throwing).publishCallRecorded();

            assertThatCode(() -> UpstreamCallReporter.publishCallRecorded(log, throwing))
                    .doesNotThrowAnyException();
            org.mockito.Mockito.verify(throwing).publishCallRecorded();
        }

        /**
         * 包装成 RuntimeException 的受检异常同样吞掉 ——
         * {@link ApiCallLogService#publishCallRecorded()} 未声明受检异常，
         * 实现只能包一层再抛。
         */
        @Test
        void swallowsWrappedCheckedExceptionFromLogService() {
            ApiCallLogService throwing = mock(ApiCallLogService.class);
            org.mockito.BDDMockito.willThrow(new java.io.UncheckedIOException("wrapped", new java.io.IOException("io")))
                    .given(throwing).publishCallRecorded();

            assertThatCode(() -> UpstreamCallReporter.publishCallRecorded(log, throwing))
                    .doesNotThrowAnyException();
        }
    }
}

package com.kaixuan.copilot_ollama_proxy.pipeline.before.notify;

import com.kaixuan.copilot_ollama_proxy.observability.port.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.dispatch.ProtocolDispatchDecision;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThatCode;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ProtocolNotifier} 的容错契约与参数传递。
 *
 * <h2>这里真正在测什么</h2>
 * 不是「能不能补写」—— 那只证明转发了一行调用。核心是
 * <strong>「观测失败不得伤到主链路」</strong>：调用点在
 * {@code dispatch(...)} 之后、真正调上游之前，一个冒出的异常会让整次对话失败。
 *
 * <p>因此「失败」用例都断言两件事：<strong>不抛</strong>，且<strong>确实尝试过</strong>
 * —— 只断言前者的话，把方法体换成空实现也能通过，而那会让路径标记静默消失。
 */
class ProtocolNotifierTests {

    private static final Logger log = LoggerFactory.getLogger(ProtocolNotifierTests.class);

    private ProtocolDispatchDecision decision;

    @BeforeEach
    void setUp() {
        decision = mock(ProtocolDispatchDecision.class);
        org.mockito.BDDMockito.given(decision.upstreamProtocol()).willReturn(WireProtocol.MESSAGES);
    }

    @Test
    @DisplayName("补写时用调用方的下游协议，而不是本类的猜测")
    void passesDownstreamProtocolFromCaller() {
        CallLifecycleNotifier notifier = mock(CallLifecycleNotifier.class);

        ProtocolNotifier.notifyProtocols(log, notifier, "req-1", WireProtocol.CHAT, decision);

        // 下游协议必须来自调用方：三个 Service 各自服务一个端点，
        // 那是它们的身份，不是本类的知识。写死成任一值都会让另两条线路的标记出错。
        verify(notifier).recordProtocols("req-1", "CHAT", "MESSAGES");
    }

    @Test
    void passesEachProtocolThroughVerbatim() {
        CallLifecycleNotifier notifier = mock(CallLifecycleNotifier.class);

        ProtocolNotifier.notifyProtocols(log, notifier, "req-r", WireProtocol.RESPONSES, decision);

        verify(notifier).recordProtocols("req-r", "RESPONSES", "MESSAGES");
    }

    /** 未注入（单元测试直接 new Service）时静默跳过 —— 缺省即不发。 */
    @Test
    void silentlySkipsWhenNotifierNotInjected() {
        assertThatCode(() -> ProtocolNotifier.notifyProtocols(log, null, "req-2", WireProtocol.CHAT, decision))
                .doesNotThrowAnyException();
    }

    /**
     * requestId 为 null 时直接返回。
     *
     * <p>它是补写的<strong>唯一匹配键</strong> —— 事件按 requestId 分组，
     * 拿不到 id 时补写会打到一条不存在的记录上，而调用方无法察觉。
     */
    @Test
    void silentlySkipsWhenRequestIdIsNull() {
        CallLifecycleNotifier notifier = mock(CallLifecycleNotifier.class);

        assertThatCode(() -> ProtocolNotifier.notifyProtocols(log, notifier, null, WireProtocol.CHAT, decision))
                .doesNotThrowAnyException();
        verify(notifier, never()).recordProtocols(anyString(), anyString(), anyString());
    }

    /**
     * 推送层抛异常不得外溢。
     *
     * <p>调用点在 {@code dispatch} 之后、真正调上游之前 ——
     * 这里冒出的异常会让整次对话失败，而它本来只该影响「Toast 上有没有路径标记」。
     */
    @Test
    void swallowsExceptionAndStillAttempted() {
        CallLifecycleNotifier throwing = mock(CallLifecycleNotifier.class);
        willThrow(new IllegalStateException("no such requestId"))
                .given(throwing).recordProtocols(anyString(), anyString(), anyString());

        assertThatCode(() -> ProtocolNotifier.notifyProtocols(log, throwing, "req-3", WireProtocol.CHAT, decision))
                .doesNotThrowAnyException();
        verify(throwing).recordProtocols(anyString(), anyString(), anyString());
    }

    /** 受检异常包一层后的形态同样吞掉（接口未声明受检异常，实现只能包一层再抛）。 */
    @Test
    void swallowsWrappedCheckedException() {
        CallLifecycleNotifier throwing = mock(CallLifecycleNotifier.class);
        willThrow(new java.io.UncheckedIOException("wrapped", new java.io.IOException("io")))
                .given(throwing).recordProtocols(anyString(), anyString(), anyString());

        assertThatCode(() -> ProtocolNotifier.notifyProtocols(log, throwing, "req-4", WireProtocol.CHAT, decision))
                .doesNotThrowAnyException();
    }

    /** 一次失败不得污染后续调用（本类无状态）。 */
    @Test
    void aFailureDoesNotAffectSubsequentCalls() {
        CallLifecycleNotifier flaky = mock(CallLifecycleNotifier.class);
        willThrow(new IllegalStateException("first"))
                .willDoNothing()
                .given(flaky).recordProtocols(anyString(), anyString(), anyString());

        assertThatCode(() -> ProtocolNotifier.notifyProtocols(log, flaky, "req-5", WireProtocol.CHAT, decision))
                .doesNotThrowAnyException();
        assertThatCode(() -> ProtocolNotifier.notifyProtocols(log, flaky, "req-6", WireProtocol.CHAT, decision))
                .doesNotThrowAnyException();

        verify(flaky, org.mockito.Mockito.times(2)).recordProtocols(anyString(), anyString(), anyString());
    }
}

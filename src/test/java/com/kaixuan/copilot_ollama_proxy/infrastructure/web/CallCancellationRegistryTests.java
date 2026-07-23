package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CallCancellationRegistry} 单元测试。
 *
 * <p>取消协调是 A3 手动取消的核心：验证 register→cancel 触发信号、
 * 未注册/已移除的 requestId 取消返回 false，确保 chat 链能被干净中止且不残留。
 */
class CallCancellationRegistryTests {

    @Test
    void cancelTriggersRegisteredSignalAndReturnsTrue() {
        CallCancellationRegistry registry = new CallCancellationRegistry();

        AtomicBoolean completed = new AtomicBoolean(false);
        Mono<Void> signal = registry.register("req-1");
        // Sinks.empty().tryEmitEmpty() 同步触发，订阅者立即收到 complete。
        signal.subscribe(null, null, () -> completed.set(true));

        boolean result = registry.cancel("req-1");

        assertThat(result).isTrue();
        assertThat(completed).as("取消信号应使已注册的 Mono 正常完成").isTrue();
    }

    @Test
    void cancelUnknownRequestIdReturnsFalse() {
        CallCancellationRegistry registry = new CallCancellationRegistry();

        assertThat(registry.cancel("never-registered")).isFalse();
    }

    @Test
    void cancelAfterRemoveReturnsFalse() {
        CallCancellationRegistry registry = new CallCancellationRegistry();
        registry.register("req-2");

        registry.remove("req-2");

        assertThat(registry.cancel("req-2")).as("已移除的调用不应再能被取消").isFalse();
    }

    @Test
    void secondCancelReturnsFalseAfterSignalAlreadyEmitted() {
        CallCancellationRegistry registry = new CallCancellationRegistry();
        registry.register("req-3");
        registry.register("req-3");

        assertThat(registry.cancel("req-3")).isTrue();
        // 第一次取消触发后，sink 仍在 map 中（remove 由 chat 链的 doFinally 负责），
        // 但已完成的 sink 再次 tryEmitEmpty 不会再成功放出 complete 信号。
        registry.remove("req-3");
        assertThat(registry.cancel("req-3")).isFalse();
    }
}

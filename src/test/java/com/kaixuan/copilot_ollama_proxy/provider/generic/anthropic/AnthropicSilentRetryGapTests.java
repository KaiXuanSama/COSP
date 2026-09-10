package com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic;

import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallRetryRegistry;
import com.kaixuan.copilot_ollama_proxy.provider.AbstractUpstreamChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手动（静默）重试在两条上游线路上都可用。
 *
 * <h2>修复的问题</h2>
 * 曾经 {@link CallRetryRegistry} + {@code takeUntilOther} 只接在
 * {@link AbstractUpstreamChatService} 的 OpenAI 流式管道上。O2A 路线（下游 OpenAI、
 * 上游 Anthropic）的上游请求由 {@link GenericAnthropicChatService} 发出，它不注册重试信号，
 * 于是 {@code retry(requestId)} 找不到 sink 返回 false，控制器把它包成
 * {@code 200 + {"retried": false}}，前端没有对应分支 —— 表现为「点了没反应、无任何报错」。
 *
 * <p>前端菜单项的条件是 {@code v-if="menuTarget.stream"}，<strong>只看是否流式，
 * 看不到上游协议</strong>，因此 O2A 的菜单项照常显示却点不动。修法是给 Anthropic 管道
 * 补齐机制，而非让前端多一个判据 —— 后者只是把功能缺失包装成「不提供」。
 */
class AnthropicSilentRetryGapTests {

    @Test
    @DisplayName("未注册的调用触发静默重试返回 false，且不抛异常")
    void retryOnUnregisteredCallSilentlyFails() {
        CallRetryRegistry registry = new CallRetryRegistry();

        assertThat(registry.retry("never-registered")).isFalse();
    }

    @Test
    @DisplayName("注册过的调用可以成功触发")
    void retryOnRegisteredCallSucceeds() {
        CallRetryRegistry registry = new CallRetryRegistry();
        registry.register("some-request-id");

        assertThat(registry.retry("some-request-id")).isTrue();
    }

    /**
     * 两条流式管道都必须具备注入点。
     *
     * <p>用反射查 setter 而非读源码文本：任一侧的注入点被移除时本用例会失败，
     * 而那正是「静默重试在该线路上静默失效」的前兆 —— 那种缺陷在运行时没有任何报错。
     */
    @Test
    @DisplayName("OpenAI 与 Anthropic 管道都接入了 CallRetryRegistry")
    void bothPipelinesAcceptTheRegistry() {
        assertThat(hasRetryRegistrySetter(AbstractUpstreamChatService.class))
                .as("OpenAI 管道应当接入静默重试")
                .isTrue();

        assertThat(hasRetryRegistrySetter(GenericAnthropicChatService.class))
                .as("Anthropic 管道应当接入静默重试；缺了它 O2A 路线点击重试会静默无反应")
                .isTrue();
    }

    private boolean hasRetryRegistrySetter(Class<?> type) {
        return Arrays.stream(type.getMethods())
                .filter(method -> method.getName().equals("setCallRetryRegistry"))
                .map(Method::getParameterTypes)
                .anyMatch(parameters -> parameters.length == 1
                        && parameters[0] == CallRetryRegistry.class);
    }
}

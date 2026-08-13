package com.kaixuan.copilot_ollama_proxy.application.protocol;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 协议调度管理器的决策规则。
 *
 * <p>本类刻意只测<strong>决策</strong>而不触达任何上游服务：调度器是无 I/O 的纯组件，
 * 四种协议组合可以被穷举，不需要 Spring 上下文也不需要 stub WebClient。
 *
 * <p>当前阶段供应商协议支持是乐观假设（一律两种都支持），因此「需要翻译」那两条分支
 * 在生产路径上不可达。这里仍然断言直连结论，作用是把「乐观假设下恒直连」这一事实钉住 ——
 * 将来把假设换成读数据库时，若不小心改坏了同名优先规则，这几个用例会失败。
 */
class ProtocolDispatchManagerTests {

    private final ProtocolDispatchManager manager = new ProtocolDispatchManager();

    @Test
    void openAiDownstreamGoesDirectWhenProviderSupportsOpenAi() {
        ProtocolDispatchDecision decision = manager.dispatch(WireProtocol.OPENAI, provider());

        assertThat(decision.downstreamProtocol()).isEqualTo(WireProtocol.OPENAI);
        assertThat(decision.upstreamProtocol()).isEqualTo(WireProtocol.OPENAI);
        assertThat(decision.translationNeeded()).isFalse();
    }

    @Test
    void anthropicDownstreamGoesDirectWhenProviderSupportsAnthropic() {
        ProtocolDispatchDecision decision = manager.dispatch(WireProtocol.ANTHROPIC, provider());

        assertThat(decision.downstreamProtocol()).isEqualTo(WireProtocol.ANTHROPIC);
        assertThat(decision.upstreamProtocol()).isEqualTo(WireProtocol.ANTHROPIC);
        assertThat(decision.translationNeeded()).isFalse();
    }

    /**
     * 同名优先：两种都支持时不该绕翻译。
     *
     * <p>这是本阶段唯一真正生效的规则，也是「加了调度器但行为不变」的依据 ——
     * 若它被写成「按枚举顺序取第一个支持的协议」，Anthropic 下游就会被错误地判成需要翻译。
     */
    @Test
    void bothProtocolsSupportedStillPrefersSameNameOverTranslation() {
        assertThat(ProviderProtocolSupport.of(provider()))
                .containsExactlyInAnyOrder(WireProtocol.OPENAI, WireProtocol.ANTHROPIC);

        for (WireProtocol downstream : WireProtocol.values()) {
            ProtocolDispatchDecision decision = manager.dispatch(downstream, provider());
            assertThat(decision.translationNeeded())
                    .as("下游 %s 在两种协议都支持时应直连", downstream)
                    .isFalse();
            assertThat(decision.upstreamProtocol()).isEqualTo(downstream);
        }
    }

    /** 乐观假设覆盖所有供应商，不因 provider_key / baseUrl 不同而变化。 */
    @Test
    void optimisticSupportAppliesToEveryProviderRegardlessOfConfiguration() {
        ProviderRuntimeConfiguration other =
                new ProviderRuntimeConfiguration("another", "https://example.org", "key", List.of());

        assertThat(ProviderProtocolSupport.supports(other, WireProtocol.OPENAI)).isTrue();
        assertThat(ProviderProtocolSupport.supports(other, WireProtocol.ANTHROPIC)).isTrue();
    }

    private ProviderRuntimeConfiguration provider() {
        return new ProviderRuntimeConfiguration("stub", "", "", List.of());
    }
}

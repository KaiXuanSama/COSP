package com.kaixuan.copilot_ollama_proxy.application.protocol;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 协议调度管理器的决策规则。
 *
 * <p>本类刻意只测<strong>决策</strong>而不触达任何上游服务：调度器是无 I/O 的纯组件，
 * 四种协议组合可以被穷举，不需要 Spring 上下文也不需要 stub WebClient。
 *
 * <p>V8.8 协议支持落库后，「需要翻译」与「一种都不支持」两条分支都可达了 ——
 * 前者由「只勾了 OpenAI 却打 /v1/messages」触发，后者由空集合触发。
 * 在此之前它们在乐观假设下永远走不到，只能靠断言「恒直连」间接保护。
 */
class ProtocolDispatchManagerTests {

    private final ProtocolDispatchManager manager = new ProtocolDispatchManager();

    @Test
    void openAiDownstreamGoesDirectWhenProviderSupportsOpenAi() {
        ProtocolDispatchDecision decision = manager.dispatch(WireProtocol.OPENAI, provider("[\"OPENAI\"]"));

        assertThat(decision.downstreamProtocol()).isEqualTo(WireProtocol.OPENAI);
        assertThat(decision.upstreamProtocol()).isEqualTo(WireProtocol.OPENAI);
        assertThat(decision.translationNeeded()).isFalse();
    }

    @Test
    void anthropicDownstreamGoesDirectWhenProviderSupportsAnthropic() {
        ProtocolDispatchDecision decision = manager.dispatch(WireProtocol.ANTHROPIC, provider("[\"ANTHROPIC\"]"));

        assertThat(decision.downstreamProtocol()).isEqualTo(WireProtocol.ANTHROPIC);
        assertThat(decision.upstreamProtocol()).isEqualTo(WireProtocol.ANTHROPIC);
        assertThat(decision.translationNeeded()).isFalse();
    }

    /**
     * 同名优先：两种都支持时不该绕翻译。
     *
     * <p>若它被写成「按枚举顺序取第一个支持的协议」，Anthropic 下游就会被错误地判成需要翻译。
     */
    @Test
    void bothProtocolsSupportedStillPrefersSameNameOverTranslation() {
        ProviderRuntimeConfiguration provider = provider("[\"OPENAI\",\"ANTHROPIC\"]");
        assertThat(ProviderProtocolSupport.of(provider))
                .containsExactlyInAnyOrder(WireProtocol.OPENAI, WireProtocol.ANTHROPIC);

        for (WireProtocol downstream : WireProtocol.values()) {
            ProtocolDispatchDecision decision = manager.dispatch(downstream, provider);
            assertThat(decision.translationNeeded())
                    .as("下游 %s 在两种协议都支持时应直连", downstream)
                    .isFalse();
            assertThat(decision.upstreamProtocol()).isEqualTo(downstream);
        }
    }

    /**
     * 只声明 OpenAI 的供应商收到 Anthropic 下游请求时标记需要翻译。
     *
     * <p>这是落库带来的主要行为变化：此前该请求会被直连发往上游的 Anthropic 端点，
     * 得到一个上游 404 —— 日志里看起来像上游故障。现在在本地就有明确结论。
     */
    @Test
    void anthropicDownstreamNeedsTranslationWhenProviderOnlySupportsOpenAi() {
        ProtocolDispatchDecision decision = manager.dispatch(WireProtocol.ANTHROPIC, provider("[\"OPENAI\"]"));

        assertThat(decision.downstreamProtocol()).isEqualTo(WireProtocol.ANTHROPIC);
        assertThat(decision.upstreamProtocol()).isEqualTo(WireProtocol.OPENAI);
        assertThat(decision.translationNeeded()).isTrue();
    }

    @Test
    void openAiDownstreamNeedsTranslationWhenProviderOnlySupportsAnthropic() {
        ProtocolDispatchDecision decision = manager.dispatch(WireProtocol.OPENAI, provider("[\"ANTHROPIC\"]"));

        assertThat(decision.upstreamProtocol()).isEqualTo(WireProtocol.ANTHROPIC);
        assertThat(decision.translationNeeded()).isTrue();
    }

    /**
     * 显式的空集合是非法配置，必须明确报错。
     *
     * <p>不能悄悄回退成全集：那会让「用户把协议全部取消勾选」这一状态永远无法被发现，
     * 而它与「配置读不懂」是两回事 —— 后者才该保守放行。
     */
    @Test
    void emptyProtocolSetIsRejectedRatherThanSilentlyFallingBack() {
        ProviderRuntimeConfiguration provider = provider("[]");

        assertThat(ProviderProtocolSupport.of(provider)).isEmpty();
        assertThatThrownBy(() -> manager.dispatch(WireProtocol.OPENAI, provider))
                // 独立类型让控制器能精确识别并给 400；仍是 IllegalStateException 的子类，
                // 因此任何只认父类型的兜底逻辑行为不变。
                .isInstanceOf(NoSupportedProtocolException.class)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未声明支持任何线路协议")
                // 消息要指向可操作的动作，而不是只陈述状态。
                .hasMessageContaining("至少勾选一种协议");
    }

    /** 字段缺失回退为全集，等同 V8.8 之前的行为；不与显式空集合混淆。 */
    @Test
    void missingProtocolConfigurationFallsBackToBothProtocols() {
        ProviderRuntimeConfiguration provider =
                new ProviderRuntimeConfiguration("legacy", "https://example.org", "key", List.of());

        assertThat(ProviderProtocolSupport.supports(provider, WireProtocol.OPENAI)).isTrue();
        assertThat(ProviderProtocolSupport.supports(provider, WireProtocol.ANTHROPIC)).isTrue();
    }

    /** 未知协议名被忽略而非让整个供应商不可用；剩下的可识别协议照常生效。 */
    @Test
    void unknownProtocolNamesAreIgnoredWhileKnownOnesStillApply() {
        assertThat(ProviderProtocolSupport.of(provider("[\"OPENAI\",\"GRPC\"]")))
                .containsExactly(WireProtocol.OPENAI);
    }

    /** 不是数组的脏配置保守放行为全集：宁可在上游失败，也不要本地全面拒绍。 */
    @Test
    void malformedProtocolConfigurationFallsBackToBothProtocols() {
        assertThat(ProviderProtocolSupport.of(provider("\"OPENAI\"")))
                .containsExactlyInAnyOrder(WireProtocol.OPENAI, WireProtocol.ANTHROPIC);
    }

    private ProviderRuntimeConfiguration provider(String supportedProtocolsJson) {
        return new ProviderRuntimeConfiguration("stub", "", "", List.of(), "[]",
                "{\"version\":2,\"groups\":[]}", supportedProtocolsJson, "");
    }
}

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
        ProtocolDispatchDecision decision = manager.dispatch(WireProtocol.CHAT, provider("[\"CHAT\"]"));

        assertThat(decision.downstreamProtocol()).isEqualTo(WireProtocol.CHAT);
        assertThat(decision.upstreamProtocol()).isEqualTo(WireProtocol.CHAT);
        assertThat(decision.translationNeeded()).isFalse();
    }

    @Test
    void anthropicDownstreamGoesDirectWhenProviderSupportsAnthropic() {
        ProtocolDispatchDecision decision = manager.dispatch(WireProtocol.MESSAGES, provider("[\"MESSAGES\"]"));

        assertThat(decision.downstreamProtocol()).isEqualTo(WireProtocol.MESSAGES);
        assertThat(decision.upstreamProtocol()).isEqualTo(WireProtocol.MESSAGES);
        assertThat(decision.translationNeeded()).isFalse();
    }

    /**
     * 同名优先：勾了的协议都该直连，不绕翻译。
     *
     * <p>若它被写成「按某个固定顺序取第一个支持的协议」，Anthropic 下游就会被错误地判成需要翻译。
     *
     * <p>遍历范围是<strong>勾选集</strong>而非 {@code values()}：本用例的命题是「同名优先」，
     * 只对该供应商声明支持的协议成立。没勾的那些本就该走翻译分支，那是
     * {@link #anthropicDownstreamNeedsTranslationWhenProviderOnlySupportsOpenAi()} 一类用例的事。
     * 曾经遍历 {@code values()} 而恰好通过，那是因为当时勾选集等于全集 ——
     * 第三个协议加入后这个巧合就消失了。
     */
    @Test
    void everySupportedProtocolPrefersSameNameOverTranslation() {
        ProviderRuntimeConfiguration provider = provider("[\"CHAT\",\"MESSAGES\"]");
        assertThat(ProviderProtocolSupport.of(provider))
                .containsExactlyInAnyOrder(WireProtocol.CHAT, WireProtocol.MESSAGES);

        for (WireProtocol downstream : ProviderProtocolSupport.of(provider)) {
            ProtocolDispatchDecision decision = manager.dispatch(downstream, provider);
            assertThat(decision.translationNeeded())
                    .as("下游 %s 已被该供应商声明支持，应直连", downstream)
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
        ProtocolDispatchDecision decision = manager.dispatch(WireProtocol.MESSAGES, provider("[\"CHAT\"]"));

        assertThat(decision.downstreamProtocol()).isEqualTo(WireProtocol.MESSAGES);
        assertThat(decision.upstreamProtocol()).isEqualTo(WireProtocol.CHAT);
        assertThat(decision.translationNeeded()).isTrue();
    }

    @Test
    void openAiDownstreamNeedsTranslationWhenProviderOnlySupportsAnthropic() {
        ProtocolDispatchDecision decision = manager.dispatch(WireProtocol.CHAT, provider("[\"MESSAGES\"]"));

        assertThat(decision.upstreamProtocol()).isEqualTo(WireProtocol.MESSAGES);
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
        assertThatThrownBy(() -> manager.dispatch(WireProtocol.CHAT, provider))
                // 独立类型让控制器能精确识别并给 400；仍是 IllegalStateException 的子类，
                // 因此任何只认父类型的兜底逻辑行为不变。
                .isInstanceOf(NoSupportedProtocolException.class)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未声明支持任何线路协议")
                // 消息要指向可操作的动作，而不是只陈述状态。
                .hasMessageContaining("至少勾选一种协议");
    }

    /**
     * 字段缺失回退为<strong>全集</strong>，等同 V8.8 之前的行为；不与显式空集合混淆。
     *
     * <p>断言写成「等于 {@code values()} 全集」而非逐个列举：这个回退的语义就是「全部」，
     * 与 {@code schema.sql} 的 DEFAULT、V13 迁移的回填是同一个集合。
     * 逐个列举会让加协议时忘改这里的行为悄悄退化成子集。
     */
    @Test
    void missingProtocolConfigurationFallsBackToAllProtocols() {
        ProviderRuntimeConfiguration provider =
                new ProviderRuntimeConfiguration("legacy", "https://example.org", "key", List.of());

        assertThat(ProviderProtocolSupport.of(provider))
                .containsExactlyInAnyOrder(WireProtocol.values());
    }

    /** 未知协议名被忽略而非让整个供应商不可用；剩下的可识别协议照常生效。 */
    @Test
    void unknownProtocolNamesAreIgnoredWhileKnownOnesStillApply() {
        assertThat(ProviderProtocolSupport.of(provider("[\"CHAT\",\"GRPC\"]")))
                .containsExactly(WireProtocol.CHAT);
    }

    /** 不是数组的脏配置保守放行为全集：宁可在上游失败，也不要本地全面拒绍。 */
    @Test
    void malformedProtocolConfigurationFallsBackToAllProtocols() {
        assertThat(ProviderProtocolSupport.of(provider("\"CHAT\"")))
                .containsExactlyInAnyOrder(WireProtocol.values());
    }

    // ==================== Responses 协议加入后的三条约束 ====================

    /** Responses 下游在供应商支持它时直连，与另两条线路同规则。 */
    @Test
    void responsesDownstreamGoesDirectWhenProviderSupportsResponses() {
        ProtocolDispatchDecision decision =
                manager.dispatch(WireProtocol.RESPONSES, provider("[\"RESPONSES\"]"));

        assertThat(decision.downstreamProtocol()).isEqualTo(WireProtocol.RESPONSES);
        assertThat(decision.upstreamProtocol()).isEqualTo(WireProtocol.RESPONSES);
        assertThat(decision.translationNeeded()).isFalse();
    }

    /**
     * V13 迁移后的常态配置（三条全勾）下，每条下游线路都直连、都不翻译。
     *
     * <p>这是接受「迁移为存量供应商全量追加 RESPONSES」的关键支撑：追加第三个元素
     * <strong>不得</strong>影响另两条已在使用的线路。规则 1（同名优先）保证了这一点，
     * 但那依赖「集合里多出来的元素不参与判断」，所以要显式钉住。
     */
    @Test
    void allThreeProtocolsSupportedKeepsEveryDownstreamDirect() {
        ProviderRuntimeConfiguration provider = provider("[\"CHAT\",\"MESSAGES\",\"RESPONSES\"]");
        assertThat(ProviderProtocolSupport.of(provider))
                .containsExactlyInAnyOrder(WireProtocol.values());

        for (WireProtocol downstream : WireProtocol.values()) {
            ProtocolDispatchDecision decision = manager.dispatch(downstream, provider);
            assertThat(decision.translationNeeded())
                    .as("下游 %s 在三条协议都支持时应直连", downstream)
                    .isFalse();
            assertThat(decision.upstreamProtocol()).isEqualTo(downstream);
        }
    }

    /**
     * 候选有多个时按 {@code TRANSLATION_FALLBACK_ORDER} 挑，而非枚举声明序。
     *
     * <p>这是三个协议才出现的分支，也是回退序常量存在的唯一理由。两处断言各自独立：
     * <ul>
     *   <li>下游 CHAT、候选 {MESSAGES, RESPONSES} → 挑 MESSAGES。
     *       <strong>枚举声明序是 CHAT, RESPONSES, MESSAGES，会挑 RESPONSES</strong> ——
     *       而 C2M 已实现、C2R 未实现，挑错的代价是一个本能跑的调用抛「未实现」。
     *       这一条同时证明「用了回退序」与「回退序的排法是对的」。</li>
     *   <li>下游 MESSAGES、候选 {CHAT, RESPONSES} → 挑 CHAT，兼容面最广。</li>
     * </ul>
     */
    @Test
    void translationTargetFollowsFallbackOrderNotEnumDeclarationOrder() {
        ProtocolDispatchDecision fromChat =
                manager.dispatch(WireProtocol.CHAT, provider("[\"MESSAGES\",\"RESPONSES\"]"));
        assertThat(fromChat.translationNeeded()).isTrue();
        assertThat(fromChat.upstreamProtocol()).isEqualTo(WireProtocol.MESSAGES);

        ProtocolDispatchDecision fromMessages =
                manager.dispatch(WireProtocol.MESSAGES, provider("[\"CHAT\",\"RESPONSES\"]"));
        assertThat(fromMessages.translationNeeded()).isTrue();
        assertThat(fromMessages.upstreamProtocol()).isEqualTo(WireProtocol.CHAT);
    }

    /**
     * 回退序必须覆盖<strong>全部</strong>协议，否则某个候选永远选不上。
     *
     * <p>加第四个协议时若忘了把它加进回退序，症状是「只勾了那一个协议的供应商」在
     * 跨协议请求下抛 {@code NoSupportedProtocolException} —— 而它明明声明了支持，
     * 错误消息会把人引向「去勾选协议」这个已经做过的动作。
     */
    @Test
    void fallbackOrderCoversEveryProtocol() {
        assertThat(ProtocolDispatchManager.TRANSLATION_FALLBACK_ORDER)
                .containsExactlyInAnyOrder(WireProtocol.values());
    }

    private ProviderRuntimeConfiguration provider(String supportedProtocolsJson) {
        return new ProviderRuntimeConfiguration("stub", "", "", List.of(), "[]",
                "{\"version\":2,\"groups\":[]}", supportedProtocolsJson, "");
    }
}

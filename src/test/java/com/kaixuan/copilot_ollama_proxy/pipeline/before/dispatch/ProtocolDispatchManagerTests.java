package com.kaixuan.copilot_ollama_proxy.pipeline.before.dispatch;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.NoSupportedProtocolException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.TranslatedRequest;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.RequestProtocolTranslator;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.TranslationRoute;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

    /**
     * 默认调度器<strong>假设全部跨协议去程翻译都已实现</strong>。
     *
     * <p>下面绝大多数用例验的是<strong>选择逻辑</strong>（直连、优先级顺序），与「谁实现了」
     * 无关；用全实现调度器把实现状态这个变量置真、隔离出「选哪个」本身。
     * 「去程翻译是否已实现」这个新维度由文件末尾一组专门用例覆盖，它们各自构造实现集合。
     */
    private final ProtocolDispatchManager manager = managerWithAllRoutesImplemented();

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
     * <p>本用例用<strong>全部方向都已实现</strong>的调度器，把实现状态置真、
     * 隔离出顺序这一个变量 —— 挑哪个纯由回退序决定（「跳过未实现」另见
     * {@link #skipsUnimplementedCandidateAndFallsToNextImplemented}）。
     * 这是三个协议才出现的分支，也是回退序常量存在的唯一理由。两处断言各自独立：
     * <ul>
     *   <li>下游 CHAT、候选 {MESSAGES, RESPONSES} → 挑 MESSAGES。
     *       <strong>枚举声明序是 CHAT, RESPONSES, MESSAGES，会挑 RESPONSES</strong> ——
     *       回退序把 MESSAGES 排在 RESPONSES 前，因此即便两者都实现也优先 MESSAGES。
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

    // ==================== 「去程翻译是否已实现」判据（本次新增维度）====================

    /**
     * 直连不看实现状态：即使一个翻译器都没有，同名协议仍直连。
     *
     * <p>钉住「规则 1 先于实现判据」—— 直连是 {@code supported.contains(downstream)} 的直接结论，
     * 不读已实现方向集。若有人把实现判据误加到直连分支，这条会红。
     */
    @Test
    void directRouteIgnoresImplementationStatus() {
        ProtocolDispatchManager manager = managerWith();   // 没有任何翻译器
        ProtocolDispatchDecision decision = manager.dispatch(WireProtocol.CHAT, provider("[\"CHAT\"]"));
        assertThat(decision.translationNeeded()).isFalse();
        assertThat(decision.upstreamProtocol()).isEqualTo(WireProtocol.CHAT);
    }

    /** 当前生产的真实状态：只有 C2M 去程实现，下游 CHAT + 供应商只有 MESSAGES → 命中翻译。 */
    @Test
    void chatToMessagesTranslatesWhenOnlyC2mImplemented() {
        ProtocolDispatchManager manager = managerWith(
                new TranslationRoute(WireProtocol.CHAT, WireProtocol.MESSAGES));

        ProtocolDispatchDecision decision = manager.dispatch(WireProtocol.CHAT, provider("[\"MESSAGES\"]"));

        assertThat(decision.translationNeeded()).isTrue();
        assertThat(decision.upstreamProtocol()).isEqualTo(WireProtocol.MESSAGES);
    }

    /**
     * 候选方向的去程翻译未实现时，跳过它、降级到下一个已实现的候选。
     *
     * <p>这是「优先级降级」的核心断言：只实现 M2R，下游 MESSAGES、供应商 {CHAT, RESPONSES}。
     * 回退序里 CHAT 在 RESPONSES 前，但 M2C 未实现 → 跳过 CHAT；降级到 RESPONSES（M2R 已实现）。
     * 若实现判据缺失（只看 supported），会错误地挑中 CHAT。
     */
    @Test
    void skipsUnimplementedCandidateAndFallsToNextImplemented() {
        ProtocolDispatchManager manager = managerWith(
                new TranslationRoute(WireProtocol.MESSAGES, WireProtocol.RESPONSES));

        ProtocolDispatchDecision decision =
                manager.dispatch(WireProtocol.MESSAGES, provider("[\"CHAT\",\"RESPONSES\"]"));

        assertThat(decision.translationNeeded()).isTrue();
        assertThat(decision.upstreamProtocol())
                .as("CHAT 优先级更高但 M2C 未实现，应跳过并降级到已实现的 RESPONSES")
                .isEqualTo(WireProtocol.RESPONSES);
    }

    /**
     * 供应商支持跨协议、但候选方向的去程翻译一个都没实现 → 抛
     * {@link ProtocolTranslationNotSupportedException}。
     *
     * <p>这正是当前生产里「下游 MESSAGES、供应商只有 CHAT」的真实结局（M2C 去程未实现）。
     * 报错点在调度器而非 translateStep：跳过决定发生在这里，由本类报出更早、消息也更准。
     * 异常须带下游协议与代表候选，那是用户弄清「该换供应商还是等实现」的依据。
     */
    @Test
    void throwsProtocolTranslationNotSupportedWhenNoCandidateImplemented() {
        ProtocolDispatchManager manager = managerWith();   // 无任何去程翻译器

        assertThatThrownBy(() -> manager.dispatch(WireProtocol.MESSAGES, provider("[\"CHAT\"]")))
                .isInstanceOf(ProtocolTranslationNotSupportedException.class)
                .hasMessageContaining("MESSAGES")
                .hasMessageContaining("CHAT");
    }

    /**
     * 「一个协议都没勾」与「勾了但去程翻译没实现」是两种失败，异常类型不同。
     *
     * <p>两者对用户的可操作动作不同（前者「至少勾一个」、后者「换供应商或等实现」），
     * 因此不能共用异常。用同一个空实现调度器同时触发两者，钉住它们不被混为一类。
     */
    @Test
    void emptyProtocolSetAndUnimplementedTranslationAreDistinctFailures() {
        ProtocolDispatchManager manager = managerWith();   // 无任何去程翻译器

        assertThatThrownBy(() -> manager.dispatch(WireProtocol.CHAT, provider("[]")))
                .as("一个都没勾 → NoSupportedProtocolException")
                .isInstanceOf(NoSupportedProtocolException.class);

        assertThatThrownBy(() -> manager.dispatch(WireProtocol.CHAT, provider("[\"MESSAGES\"]")))
                .as("勾了 MESSAGES 但 C2M 未实现 → ProtocolTranslationNotSupportedException")
                .isInstanceOf(ProtocolTranslationNotSupportedException.class);
    }

    // ==================== 构造 helper ====================

    /**
     * 「全部跨协议去程都已实现」的调度器 —— 供上面那些验证<strong>选择逻辑</strong>
     * （直连、优先级顺序）的用例使用：把实现状态这个变量全部置真，隔离出「选哪个」本身。
     */
    private static ProtocolDispatchManager managerWithAllRoutesImplemented() {
        List<RequestProtocolTranslator> all = new ArrayList<>();
        for (WireProtocol downstream : WireProtocol.values()) {
            for (WireProtocol upstream : WireProtocol.values()) {
                if (downstream != upstream) {
                    all.add(stubTranslator(downstream, upstream));
                }
            }
        }
        return new ProtocolDispatchManager(all);
    }

    /** 指定「已实现哪些去程方向」的调度器 —— 供验证实现判据的用例精确控制。 */
    private static ProtocolDispatchManager managerWith(TranslationRoute... implementedRoutes) {
        List<RequestProtocolTranslator> translators = Arrays.stream(implementedRoutes)
                .map(route -> stubTranslator(route.downstream(), route.upstream()))
                .toList();
        return new ProtocolDispatchManager(translators);
    }

    /**
     * 只声明方向、不实际翻译的去程翻译器替身。
     *
     * <p>调度器只读方向（{@code downstreamProtocol()} / {@code upstreamProtocol()}）建集合，
     * 从不调 {@code translateRequest}，故后者抛异常即可 —— 一旦被调到说明测试走错了路径。
     */
    private static RequestProtocolTranslator stubTranslator(WireProtocol downstream, WireProtocol upstream) {
        return new RequestProtocolTranslator() {
            @Override
            public WireProtocol downstreamProtocol() {
                return downstream;
            }

            @Override
            public WireProtocol upstreamProtocol() {
                return upstream;
            }

            @Override
            public TranslatedRequest translateRequest(Map<String, Object> downstreamBody) {
                throw new UnsupportedOperationException("方向声明替身，不实际翻译");
            }
        };
    }

    private ProviderRuntimeConfiguration provider(String supportedProtocolsJson) {
        return new ProviderRuntimeConfiguration("stub", "", "", List.of(), "[]",
                "{\"version\":2,\"groups\":[]}", supportedProtocolsJson, "");
    }
}

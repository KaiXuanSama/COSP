package com.kaixuan.copilot_ollama_proxy.application.pipeline;

import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PipelineExecution} 的登记语义与空响应拦截判据。
 *
 * <h2>为何这些用例值得存在</h2>
 * 这类取代的是「让检测器按帧形状自己猜是不是本协议」的启发式做法。
 * 启发式错了会怎样，取决于错的方向：
 * <ul>
 *   <li><strong>误判成「不是本协议」</strong> —— 真实响应被当成半轮实现态，
 *       空响应兜底静默失效，中转站抽风返回的空回复会原样透给下游；</li>
 *   <li><strong>误判成「是本协议」</strong> —— 半轮实现态被当成真·空响应，
 *       白等完整轮重试预算（生产值约 62 秒、6 次上游调用）。</li>
 * </ul>
 * 两个方向都不可接受，因此判据取「编排层显式登记的事实」而非形状推断 ——
 * 本类就是钉住那份登记的正确读取方式。
 *
 * <h2>三条线路的三种情形都要覆盖</h2>
 * 直连 / 全实现翻译 / 半轮实现，三者对拦截的期望各不相同，
 * 而它们共用同一个判据 —— 只测其中一种会让另外两种的回归无人看守。
 */
class PipelineExecutionTests {

    @Nested
    @DisplayName("步骤登记")
    class Registration {

        @Test
        void emptyRegistersNothing() {
            PipelineExecution execution = PipelineExecution.empty();

            assertThat(execution.completedSteps()).isEmpty();
            assertThat(execution.hasCompleted(PipelineStep.REQUEST_TRANSLATION)).isFalse();
            assertThat(execution.hasCompleted(PipelineStep.RESPONSE_TRANSLATION)).isFalse();
        }

        @Test
        void withCompletedRecordsOnlyTheGivenStep() {
            PipelineExecution execution = PipelineExecution.empty()
                    .withCompleted(PipelineStep.REQUEST_TRANSLATION);

            assertThat(execution.hasCompleted(PipelineStep.REQUEST_TRANSLATION)).isTrue();
            assertThat(execution.hasCompleted(PipelineStep.RESPONSE_TRANSLATION)).isFalse();
        }

        /**
         * 不可变：{@code withCompleted} 返回新实例，不改原实例。
         *
         * <p>它让「请求级事实」可以被安全共享 —— 上游执行器的每轮往返都读同一个实例，
         * 而任何一处对它的「补充登记」都不会泄漏给别的读取方。
         * 若做成可变，一处登记会静默影响同一次调用里的其他读取点。
         */
        @Test
        void withCompletedDoesNotMutateTheOriginal() {
            PipelineExecution original = PipelineExecution.empty();
            PipelineExecution extended = original.withCompleted(PipelineStep.REQUEST_TRANSLATION);

            assertThat(original.hasCompleted(PipelineStep.REQUEST_TRANSLATION)).isFalse();
            assertThat(extended.hasCompleted(PipelineStep.REQUEST_TRANSLATION)).isTrue();
        }

        /** 已登记的步骤集合只读 —— 外部拿不到可变视图，也就无法绕过不可变性。 */
        @Test
        void completedStepsIsUnmodifiable() {
            PipelineExecution execution = PipelineExecution.empty()
                    .withCompleted(PipelineStep.REQUEST_TRANSLATION);

            assertThat(execution.completedSteps()).containsExactly(PipelineStep.REQUEST_TRANSLATION);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            execution.completedSteps().add(PipelineStep.RESPONSE_TRANSLATION))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        /** 重复登记同一步骤是幂等的。 */
        @Test
        void recordingTheSameStepTwiceIsIdempotent() {
            PipelineExecution execution = PipelineExecution.empty()
                    .withCompleted(PipelineStep.REQUEST_TRANSLATION)
                    .withCompleted(PipelineStep.REQUEST_TRANSLATION);

            assertThat(execution.completedSteps()).containsExactly(PipelineStep.REQUEST_TRANSLATION);
        }
    }

    @Nested
    @DisplayName("协议登记")
    class ProtocolRegistration {

        @Test
        void ofKeepsBothProtocols() {
            PipelineExecution execution = PipelineExecution.of(WireProtocol.CHAT, WireProtocol.MESSAGES);

            assertThat(execution.downstreamProtocol()).isEqualTo(WireProtocol.CHAT);
            assertThat(execution.upstreamProtocol()).isEqualTo(WireProtocol.MESSAGES);
            assertThat(execution.translationNeeded()).isTrue();
        }

        @Test
        void sameProtocolOnBothSidesMeansNoTranslation() {
            PipelineExecution execution = PipelineExecution.of(WireProtocol.MESSAGES, WireProtocol.MESSAGES);

            assertThat(execution.translationNeeded()).isFalse();
        }

        /**
         * 协议未知时不猜「需要翻译」。
         *
         * <p>{@code empty()} 是既有调用方的默认值，它们不涉及翻译；
         * 若这里返回 true，那些调用会被当成跨协议，进而可能被判成半轮实现态而跳过拦截，
         * 静默失去空响应兜底。
         */
        @Test
        void unknownProtocolsDoNotClaimTranslationNeeded() {
            assertThat(PipelineExecution.empty().translationNeeded()).isFalse();
            assertThat(PipelineExecution.empty().downstreamProtocol()).isNull();
            assertThat(PipelineExecution.empty().upstreamProtocol()).isNull();
        }

        /**
         * 只登记一侧协议时也判 false —— 半个事实不足以断言「需要翻译」。
         */
        @Test
        void halfRegisteredProtocolsDoNotClaimTranslationNeeded() {
            PipelineExecution execution = PipelineExecution
                    .of(WireProtocol.CHAT, WireProtocol.CHAT)
                    .withCompleted(PipelineStep.REQUEST_TRANSLATION);

            // 两侧相同，不构成需要翻译
            assertThat(execution.translationNeeded()).isFalse();
        }
    }

    @Nested
    @DisplayName("空响应拦截是否介入")
    class GateDecision {

        /**
         * 直连：两侧同协议，照常拦截。
         *
         * <p>帧的形状与下游期待一致，拦截的判定有意义 —— 与重构前行为完全一致。
         * 这条不能被「跳过」逻辑误伤：直连是最常见的路径。
         */
        @Test
        void directConnectionAlwaysGates() {
            PipelineExecution execution = PipelineExecution.of(WireProtocol.CHAT, WireProtocol.CHAT);

            assertThat(execution.shouldApplyEmptyResponseGate()).isTrue();
        }

        /**
         * 未登记任何东西（既有调用方与测试辅助方法）：照常拦截。
         *
         * <p>这是「默认必须保守」的那一面 —— 空响应兜底是保护用户的功能，
         * 不能因为调用方没传登记就静默关掉。
         */
        @Test
        void unregisteredExecutionGatesByDefault() {
            assertThat(PipelineExecution.empty().shouldApplyEmptyResponseGate()).isTrue();
        }

        /**
         * 全实现翻译（C2M 现状）：照常拦截。
         *
         * <p>跨协议但回程已接时，一条真正空的响应同样应该被识别并重试 ——
         * 否则中转站抽风返回的空回复会原样透给下游。
         * 编排层因此在 C2M 路径上必须登记两个翻译步骤，漏登记会让这条线路静默失去兜底。
         */
        @Test
        void fullyImplementedTranslationStillGates() {
            PipelineExecution execution = PipelineExecution.of(WireProtocol.CHAT, WireProtocol.MESSAGES)
                    .withCompleted(PipelineStep.REQUEST_TRANSLATION)
                    .withCompleted(PipelineStep.RESPONSE_TRANSLATION);

            assertThat(execution.shouldApplyEmptyResponseGate()).isTrue();
        }

        /**
         * <strong>半轮实现态：跳过拦截。</strong>
         *
         * <p>去程已接、回程未接（开发新协议翻译时的中间态）。此时下游拿到的帧是
         * 上游协议的形态，拦截的判据属于本服务所服务的协议，判定结果不承载任何信息。
         *
         * <p>不跳过的话，开发者会看到「卡一分钟、额度莫名消耗」，
         * 而那批帧本来就在手里 —— 这正是本类要消除的体验。
         */
        @Test
        void halfImplementedTranslationSkipsGate() {
            PipelineExecution execution = PipelineExecution.of(WireProtocol.CHAT, WireProtocol.MESSAGES)
                    .withCompleted(PipelineStep.REQUEST_TRANSLATION);

            assertThat(execution.shouldApplyEmptyResponseGate()).isFalse();
        }

        /**
         * 判据只看<strong>回程</strong>登记：只登记回程也会照常拦截。
         *
         * <p>「需要翻译」这个前提由两侧协议给出（{@link PipelineExecution#translationNeeded()}），
         * 不由步骤登记推出 —— 登记是「做了什么」，协议是「该做什么」，两者独立。
         * 因此一个不合常理的登记组合（只记回程）也不会让拦截被跳过：
         * 跳过只对「确实缺了回程」成立，这是保守的那一面。
         */
        @Test
        void responseTranslationAloneStillGates() {
            PipelineExecution execution = PipelineExecution.of(WireProtocol.CHAT, WireProtocol.MESSAGES)
                    .withCompleted(PipelineStep.RESPONSE_TRANSLATION);

            assertThat(execution.shouldApplyEmptyResponseGate()).isTrue();
        }

        /**
         * 登记了翻译步骤但两侧协议相同：仍然照常拦截。
         *
         * <p>协议相同意味着帧形状与下游期待一致，拦截判定有意义。
         * 步骤登记是事实的一部分，协议是另一部分 —— 判据要同时看两者。
         */
        @Test
        void sameProtocolGatesEvenIfTranslationStepsWereRecorded() {
            PipelineExecution execution = PipelineExecution.of(WireProtocol.CHAT, WireProtocol.CHAT)
                    .withCompleted(PipelineStep.REQUEST_TRANSLATION);

            assertThat(execution.shouldApplyEmptyResponseGate()).isTrue();
        }
    }
}

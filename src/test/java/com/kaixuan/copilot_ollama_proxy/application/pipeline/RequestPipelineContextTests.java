package com.kaixuan.copilot_ollama_proxy.application.pipeline;

import com.kaixuan.copilot_ollama_proxy.application.protocol.TranslationContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RequestPipelineContext} 的构造、可变性与「两级状态分界」契约。
 *
 * <h2>为何这些用例值得存在</h2>
 * 本类是主干上的<strong>穿线载体</strong> —— 它本身不做决定，但它的形状决定了
 * 后续每一步能不能取到自己要的事实。它的失效是**安静**的：
 * 字段取错、{@code bodyProtocol} 没跟着 body 一起改、流级状态被误塞进来 ——
 * 三种都不会抛异常，只会让某个阶段的判断悄悄偏掉。
 *
 * <p>尤其 {@link RequestPipelineContext#replaceBody} —— 它必须**同时**换掉 body 与
 * {@code bodyProtocol}。只换前者会让查表键撒谎，而「挑错实现」通常不报错，
 * 只是那个实现往 body 里写的字段名不对。
 */
class RequestPipelineContextTests {

    private static ProviderRuntimeConfiguration provider() {
        return new ProviderRuntimeConfiguration("relay-x", "https://up.example", "key-1", List.of());
    }

    private static RequestPipelineContext chatToMessages() {
        return RequestPipelineContext.of(new LinkedHashMap<>(Map.of("model", "m")),
                WireProtocol.CHAT, WireProtocol.MESSAGES, provider(),
                HttpHeaders.EMPTY, "req-1", null);
    }

    @Nested
    @DisplayName("组装期创建")
    class Creation {

        @Test
        void carriesAllRequestLevelFacts() {
            RequestPipelineContext ctx = chatToMessages();

            assertThat(ctx.downstreamProtocol()).isEqualTo(WireProtocol.CHAT);
            assertThat(ctx.upstreamProtocol()).isEqualTo(WireProtocol.MESSAGES);
            assertThat(ctx.provider().providerKey()).isEqualTo("relay-x");
            assertThat(ctx.requestId()).isEqualTo("req-1");
            assertThat(ctx.body()).containsEntry("model", "m");
        }

        /**
         * {@code bodyProtocol} 初值取 <strong>upstream</strong> 而非 downstream。
         *
         * <p>因为翻译发生在应用服务层：编排器把 body 换成上游形态之后才交给主干，
         * 所以主干拿到的 body 从一开始就是上游协议的形状。
         *
         * <p>这条钉住的是「当前事实」而非「设计选择」—— 等 3.4 把 translate 移进主干，
         * 初值语义会变成「= downstream、被 translate 改写」，届时本用例需一并更新，
         * 而那正是它该提醒的事。
         */
        @Test
        void bodyProtocolStartsAsUpstreamProtocolBecauseTranslationHappensAbove() {
            RequestPipelineContext ctx = chatToMessages();

            assertThat(ctx.bodyProtocol()).isEqualTo(WireProtocol.MESSAGES);
            assertThat(ctx.bodyProtocol()).isEqualTo(ctx.upstreamProtocol());
        }

        /** 直连：两侧同协议，{@code bodyProtocol} 自然也相同。 */
        @Test
        void directConnectionHasIdenticalProtocols() {
            RequestPipelineContext ctx = RequestPipelineContext.of(new LinkedHashMap<>(),
                    WireProtocol.MESSAGES, WireProtocol.MESSAGES, provider(),
                    HttpHeaders.EMPTY, "req-2", null);

            assertThat(ctx.bodyProtocol()).isEqualTo(WireProtocol.MESSAGES);
            assertThat(ctx.downstreamProtocol()).isEqualTo(ctx.upstreamProtocol());
        }

        /**
         * {@code translationContext} 在直连时为 null —— 它由去程翻译产出，没有翻译就没有它。
         *
         * <p>本类刻意允许这个字段为空（且当前主干上无人读它）：它属响应侧，
         * 等响应侧接进主干才有读取方。留空位是为了那时不必再动传递链。
         */
        @Test
        void translationContextIsNullOnDirectConnection() {
            assertThat(chatToMessages().translationContext()).isNull();
        }

        /** 翻译路线下携带去程产出的事实。 */
        @Test
        void translationContextIsCarriedOnTranslationRoute() {
            RequestPipelineContext ctx = RequestPipelineContext.of(new LinkedHashMap<>(),
                    WireProtocol.CHAT, WireProtocol.MESSAGES, provider(),
                    HttpHeaders.EMPTY, "req-3", new TranslationContext(false, true));

            assertThat(ctx.translationContext()).isNotNull();
        }

        /** 新建的上下文：协议已带上，但没有任何步骤被执行。 */
        @Test
        void startsWithProtocolsKnownButNoCompletedSteps() {
            RequestPipelineContext ctx = chatToMessages();

            assertThat(ctx.completedSteps()).isEmpty();
            // 协议已知 → 跨协议被正确识别。这是 3.3b-1 合并的关键收益：
            // 协议与判据现在同处一个对象，不存在「协议未登记」这个中间态。
            assertThat(ctx.translationNeeded()).isTrue();
        }
    }

    @Nested
    @DisplayName("body 的可变与替换")
    class BodyMutation {

        /**
         * 阶段可以原地改 body —— 这是主干的正常动作。
         *
         * <p>{@code body()} 返回的就是持有的那个 {@code Map} 实例（不是副本），
         * 因此各注入步骤可以直接往里写字段，不必每步都换一个新 Map。
         */
        @Test
        void stagesMayMutateTheBodyInPlace() {
            RequestPipelineContext ctx = chatToMessages();

            ctx.body().put("temperature", 0.7);

            assertThat(ctx.body()).containsEntry("temperature", 0.7);
        }

        /**
         * <strong>{@link RequestPipelineContext#replaceBody} 必须同时更新查表键。</strong>
         *
         * <p>这是本类最容易写错的一处：只换 body 会让 {@code bodyProtocol} 撒谎，
         * 后续阶段据此查表就会挑到按旧协议写的实现。而「挑错实现」通常不报错 ——
         * 那个实现只是往 body 里写了另一个协议的字段名，上游收到一个形状混乱的请求体。
         */
        @Test
        void replaceBodyAlsoUpdatesTheLookupKey() {
            RequestPipelineContext ctx = chatToMessages();

            ctx.replaceBody(new LinkedHashMap<>(Map.of("input", "hi")), WireProtocol.RESPONSES);

            assertThat(ctx.body()).containsEntry("input", "hi");
            assertThat(ctx.bodyProtocol()).isEqualTo(WireProtocol.RESPONSES);
        }

        /**
         * 替换 body 不改变两侧协议 —— 那是路由事实，不由 body 决定。
         *
         * <p>三者各司其职：{@code downstream}/{@code upstream} 是「跟谁说话」，
         * {@code bodyProtocol} 是「手里这份报文长什么样」。翻译只动最后一个。
         */
        @Test
        void replaceBodyLeavesRoutingFactsUntouched() {
            RequestPipelineContext ctx = chatToMessages();

            ctx.replaceBody(new LinkedHashMap<>(), WireProtocol.RESPONSES);

            assertThat(ctx.downstreamProtocol()).isEqualTo(WireProtocol.CHAT);
            assertThat(ctx.upstreamProtocol()).isEqualTo(WireProtocol.MESSAGES);
        }
    }

    @Nested
    @DisplayName("步骤登记")
    class StepRegistration {

        @Test
        void emptyContextRegistersNoSteps() {
            RequestPipelineContext ctx = chatToMessages();

            assertThat(ctx.completedSteps()).isEmpty();
            assertThat(ctx.hasCompleted(PipelineStep.REQUEST_TRANSLATION)).isFalse();
            assertThat(ctx.hasCompleted(PipelineStep.RESPONSE_TRANSLATION)).isFalse();
        }

        @Test
        void markCompletedRecordsOnlyTheGivenStep() {
            RequestPipelineContext ctx = chatToMessages();

            ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);

            assertThat(ctx.hasCompleted(PipelineStep.REQUEST_TRANSLATION)).isTrue();
            assertThat(ctx.hasCompleted(PipelineStep.RESPONSE_TRANSLATION)).isFalse();
        }

        /** 重复登记同一步骤是幂等的（集合语义）。 */
        @Test
        void recordingTheSameStepTwiceIsIdempotent() {
            RequestPipelineContext ctx = chatToMessages();

            ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);
            ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);

            assertThat(ctx.completedSteps()).containsExactly(PipelineStep.REQUEST_TRANSLATION);
        }

        /**
         * 已登记的步骤集合<strong>只读</strong>。
         *
         * <p>登记入口只有 {@link RequestPipelineContext#markCompleted} 一个 ——
         * 拿到集合的人改不动它，因此「谁登记了什么」始终可追溯。
         * 这是 3.3b-1 从 {@code PipelineExecution} 继承下来的约束。
         */
        @Test
        void completedStepsIsUnmodifiable() {
            RequestPipelineContext ctx = chatToMessages();
            ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);

            assertThat(ctx.completedSteps()).containsExactly(PipelineStep.REQUEST_TRANSLATION);
            assertThatThrownBy(() -> ctx.completedSteps().add(PipelineStep.RESPONSE_TRANSLATION))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        /**
         * 一个上下文的登记<strong>不会泄漏给另一个</strong>。
         *
         * <p>这是合并时换掉的那条不变式：原 {@code PipelineExecution} 不可变，
         * 「不泄漏」靠每次返回新实例自动成立；本类的登记集合是<strong>可变字段</strong>，
         * 因此同一条意图必须改由「每个实例各持一个集合」来保证。
         *
         * <p>若那个集合被写成 {@code static} 或共享实例，症状是
         * <strong>跨请求串登记</strong>：上一条半轮态线路的登记会让下一条正常线路
         * 也被当成半轮态而跳过空响应兜底 —— 而两条用例单独跑都过。
         */
        @Test
        void markCompletedOnOneContextDoesNotAffectAnother() {
            RequestPipelineContext first = chatToMessages();
            RequestPipelineContext second = chatToMessages();

            first.markCompleted(PipelineStep.REQUEST_TRANSLATION);

            assertThat(second.completedSteps()).isEmpty();
            assertThat(second.hasCompleted(PipelineStep.REQUEST_TRANSLATION)).isFalse();
        }

        /**
         * {@code completedSteps()} 返回的是<strong>活视图</strong>，不是快照。
         *
         * <p>它包的是内部那个可变集合，因此**先前取到的视图会跟着后续登记变化**。
         * 这不是缺陷（读它的都是终态使用者），但它是可观察的事实 ——
         * 若有调用方把它当作「当时的登记快照」缓存起来，就会看到预期外的内容。
         * 钉住它，是为了那个调用方出现时能被这条用例提醒。
         */
        @Test
        void completedStepsViewReflectsLaterRegistrations() {
            RequestPipelineContext ctx = chatToMessages();
            Set<PipelineStep> earlierView = ctx.completedSteps();

            assertThat(earlierView).isEmpty();

            ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);

            assertThat(earlierView).containsExactly(PipelineStep.REQUEST_TRANSLATION);
        }
    }

    @Nested
    @DisplayName("从协议派生的判据")
    class ProtocolFacts {

        /** 两侧同协议 → 不需要翻译（直连）。 */
        @Test
        void sameProtocolOnBothSidesMeansNoTranslation() {
            RequestPipelineContext ctx = RequestPipelineContext.of(new LinkedHashMap<>(),
                    WireProtocol.MESSAGES, WireProtocol.MESSAGES, provider(),
                    HttpHeaders.EMPTY, "req-1", null);

            assertThat(ctx.translationNeeded()).isFalse();
        }

        /**
         * 两侧协议不同 → 恒需要翻译。
         *
         * <p>取代原 {@code unknownProtocolsDoNotClaimTranslationNeeded}：
         * 它验的是「协议未知时不要猜需要翻译」，而<strong>未知态已在合并中消失</strong>
         * （协议是创建时的必填参数）。
         *
         * <p>但那条用例守护的意图仍在：{@code translationNeeded()} 必须是
         * **协议的纯函数**，不得因为信息缺失或登记内容而偏移。
         * 前半（缺失）由「必填」这个形状本身保证，后半由本用例与下两条一起钉住。
         */
        @Test
        void differentProtocolsAlwaysClaimTranslationNeeded() {
            assertThat(chatToMessages().translationNeeded()).isTrue();
        }

        /**
         * 两侧同协议时，即便登记了翻译步骤也不声称「需要翻译」。
         *
         * <p>「登记」是<strong>做了什么</strong>，「协议」是<strong>该做什么</strong>，两者独立。
         * 因此一个不合常理的登记组合（同协议却记了去程）不得让判据翻转 ——
         * 翻成 true 会让这条线路被当成跨协议，进而可能被判成半轮态而跳过拦截。
         */
        @Test
        void sameProtocolWithTranslationStepsRecordedStillClaimsNoTranslation() {
            RequestPipelineContext ctx = RequestPipelineContext.of(new LinkedHashMap<>(),
                    WireProtocol.CHAT, WireProtocol.CHAT, provider(),
                    HttpHeaders.EMPTY, "req-1", null);

            ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);

            assertThat(ctx.translationNeeded()).isFalse();
        }

        /**
         * 两侧协议不同时，把两个步骤都登记上也不会把判据从 true 改成 false。
         *
         * <p>与上一条配对，封住「从登记反推是否需要翻译」这个错误实现 ——
         * 那种实现会让一个登记齐全的 C2M 请求失去跨协议识别，
         * 而它当前是线上唯一的跨协议线路。
         */
        @Test
        void differentProtocolsClaimTranslationEvenWithBothStepsRecorded() {
            RequestPipelineContext ctx = chatToMessages();
            ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);
            ctx.markCompleted(PipelineStep.RESPONSE_TRANSLATION);

            assertThat(ctx.translationNeeded()).isTrue();
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
            RequestPipelineContext ctx = RequestPipelineContext.of(new LinkedHashMap<>(),
                    WireProtocol.CHAT, WireProtocol.CHAT, provider(), HttpHeaders.EMPTY, "req-1", null);

            assertThat(ctx.shouldApplyEmptyResponseGate()).isTrue();
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
            RequestPipelineContext ctx = chatToMessages();
            ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);
            ctx.markCompleted(PipelineStep.RESPONSE_TRANSLATION);

            assertThat(ctx.shouldApplyEmptyResponseGate()).isTrue();
        }

        /**
         * <strong>半轮实现态：跳过拦截。</strong>
         *
         * <p>去程已接、回程未接（开发新协议翻译时的中间态）。此时下游拿到的帧是
         * 上游协议的形态，拦截的判据属于本服务所服务的协议，判定结果不承载任何信息。
         *
         * <p>不跳过的话，开发者会看到「卡一分钟、额度莫名消耗」，
         * 而那批帧本来就在手里 —— 这正是本节要消除的体验。
         */
        @Test
        void halfImplementedTranslationSkipsGate() {
            RequestPipelineContext ctx = chatToMessages();
            ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);

            assertThat(ctx.shouldApplyEmptyResponseGate()).isFalse();
        }

        /**
         * 只登记回程也会照常拦截。
         *
         * <p>判据只看<strong>回程</strong>是否登记：直接连必然拦截、半轮态必然跳过，
         * 其余组合一律保守地拦截。这条钉住「跳过只对确实缺了回程成立」。
         */
        @Test
        void responseTranslationAloneStillGates() {
            RequestPipelineContext ctx = chatToMessages();
            ctx.markCompleted(PipelineStep.RESPONSE_TRANSLATION);

            assertThat(ctx.shouldApplyEmptyResponseGate()).isTrue();
        }

        /**
         * 新建的跨协议上下文（什么都没登记）：跳过拦截。
         *
         * <p>取代原 {@code unregisteredExecutionGatesByDefault}：它那时成立是因为
         * 登记为空意味着「协议未知 → 不需要翻译 → 直连 → 照常拦截」。
         * 合并后协议必知，于是同样的「什么都没登记」在<strong>跨协议</strong>下
         * 指向相反的结论 —— 它正是<strong>半轮实现态的形状</strong>（去程还没接上，
         * 但两侧协议已注定不同），因此跳过。
         *
         * <p>这条同时钉住「判据不要求先登记去程」：跨协议这一个事实就足以判定回程缺席。
         */
        @Test
        void crossProtocolWithoutAnyRegistrationSkipsGate() {
            assertThat(chatToMessages().shouldApplyEmptyResponseGate()).isFalse();
        }

        /**
         * 两侧同协议时，即便登记了翻译步骤也照常拦截。
         *
         * <p>协议相同意味着帧形状与下游期待一致，拦截判定有意义。
         * 步骤登记是事实的一部分，协议是另一部分 —— 判据要同时看两者。
         */
        @Test
        void sameProtocolGatesEvenIfTranslationStepsWereRecorded() {
            RequestPipelineContext ctx = RequestPipelineContext.of(new LinkedHashMap<>(),
                    WireProtocol.CHAT, WireProtocol.CHAT, provider(),
                    HttpHeaders.EMPTY, "req-1", null);
            ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);

            assertThat(ctx.shouldApplyEmptyResponseGate()).isTrue();
        }

        /**
         * 直连把两个翻译步骤都登记上，仍然照常拦截。
         *
         * <p>「同协议 → 短踷返回 true」不能被任何登记组合绕过。
         * 直连是最常见的路径，它一旦被误跳过，空响应兜底就在**绝大多数线上流量**上
         * 静默失效 —— 而这个方向的失效不会报错，只会让空回复透给下游。
         */
        @Test
        void directConnectionGatesAfterBothTranslationStepsRecorded() {
            RequestPipelineContext ctx = RequestPipelineContext.of(new LinkedHashMap<>(),
                    WireProtocol.CHAT, WireProtocol.CHAT, provider(),
                    HttpHeaders.EMPTY, "req-1", null);
            ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);
            ctx.markCompleted(PipelineStep.RESPONSE_TRANSLATION);

            assertThat(ctx.shouldApplyEmptyResponseGate()).isTrue();
        }

        /**
         * 跨协议下，登记去程对拦截<strong>没有任何影响</strong>。
         *
         * <p>判据只看回程：登记了去程仍然跳过，与上面那条「什么都不登记」的结论一致。
         * 这意味着实现层真正读的只有 {@code RESPONSE_TRANSLATION}。
         *
         * <p>若有人把判据改成「去程已接就拦截」，他会在开发半轮实现时
         * 重新撞上 2.1a 要消除的体验：帧明明在手里，却被压着等完整轮重试预算
         * （生产值约 62 秒、6 次上游调用）。这条用例让那个回归在校时就失败。
         */
        @Test
        void requestTranslationRegistrationDoesNotOpenTheGate() {
            RequestPipelineContext ctx = chatToMessages();
            ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);

            assertThat(ctx.shouldApplyEmptyResponseGate()).isFalse();
        }
    }
}

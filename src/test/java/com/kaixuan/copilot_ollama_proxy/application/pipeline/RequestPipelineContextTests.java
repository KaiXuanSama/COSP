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

import static org.assertj.core.api.Assertions.assertThat;

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

        /** 新建的上下文：执行登记已带上两侧协议，但没有任何步骤被执行。 */
        @Test
        void startsWithProtocolsRegisteredButNoCompletedSteps() {
            RequestPipelineContext ctx = chatToMessages();

            assertThat(ctx.execution().completedSteps()).isEmpty();
            // 协议已登记 → 跨协议被正确识别。若这里退化成 empty()（协议未知），
            // 半轮实现态会被误判成直连，空响应拦截照常介入（见 ExecutionUpdates 组）。
            assertThat(ctx.execution().translationNeeded()).isTrue();
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
    @DisplayName("执行登记可更新")
    class ExecutionUpdates {

        /**
         * 步骤可以登记自己执行完了，登记结果被读回来。
         *
         * <p>这是「查表驱动状态位」的落点：回程翻译查表命中时才登记
         * {@code RESPONSE_TRANSLATION}，未命中就不登记 ——
         * 空响应拦截据此判断是否该跳过（见 {@link PipelineExecution}）。
         */
        @Test
        void registrationCanBeUpdatedAndReadBack() {
            RequestPipelineContext ctx = chatToMessages();

            ctx.updateExecution(ctx.execution().withCompleted(PipelineStep.REQUEST_TRANSLATION));

            assertThat(ctx.execution().hasCompleted(PipelineStep.REQUEST_TRANSLATION)).isTrue();
        }

        /**
         * 只登记去程（半轮实现态）时，空响应拦截应被跳过。
         *
         * <p>本用例把两类的契约连起来验一次：{@code RequestPipelineContext.execution()}
         * 取到的登记确实被 {@link PipelineExecution} 的判据正确消费。
         */
        @Test
        void halfRoundStateReachesTheGateDecision() {
            RequestPipelineContext ctx = chatToMessages();

            ctx.updateExecution(ctx.execution().withCompleted(PipelineStep.REQUEST_TRANSLATION));

            assertThat(ctx.execution().shouldApplyEmptyResponseGate()).isFalse();
        }

        /** 两半都登记时拦截照常 —— 与上一条配对，防止「登记了就跳过」的误实现。 */
        @Test
        void fullyImplementedTranslationKeepsTheGate() {
            RequestPipelineContext ctx = chatToMessages();

            ctx.updateExecution(ctx.execution()
                    .withCompleted(PipelineStep.REQUEST_TRANSLATION)
                    .withCompleted(PipelineStep.RESPONSE_TRANSLATION));

            assertThat(ctx.execution().shouldApplyEmptyResponseGate()).isTrue();
        }
    }
}

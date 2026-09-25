package com.kaixuan.copilot_ollama_proxy.pipeline;

import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.protocol.NoSupportedProtocolException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchManager;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.TranslatorRegistry;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.application.runtime.UnresolvedModelRouteException;
import com.kaixuan.copilot_ollama_proxy.upstream.requestbody.RequestBodyAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BeforeSend} 的<strong>路由步骤</strong>（{@code routeStep}）直接单测。
 *
 * <h2>这里在测什么（阶段 4 刀 3 块化后）</h2>
 * 块化前，路由 + 调度 + 通知这段是 {@code RequestPipeline.run}，本类测的是它。
 * 块化把它搬进 {@link BeforeSend#routeStep}（发送前块的第一步），故本类改测那个方法。
 * 逻辑逐字未搬动，只换了家 —— 因此「行为正确」由既有集成用例覆盖，本类钉的是
 * <strong>「路由步骤作为独立单元自己的契约」</strong>：
 * <ul>
 *   <li>路由未解析 → 抛类型化异常（不是返回 null）；</li>
 *   <li>供应商一个协议都不支持 → 把 {@link NoSupportedProtocolException} 透出；</li>
 *   <li>生命周期通知器缺省（未注入）时<strong>不崩</strong>；</li>
 *   <li>结论<strong>回填进 ctx</strong>（上游协议 / 供应商 / 目标模型名）——
 *       这是块化后的形态：不再返回 {@code PipelinePreamble}，而是就地改 ctx。</li>
 * </ul>
 *
 * <p>{@code routeStep} 同步执行、同步抛异常 —— 这正是「等在 {@code defer} 内调用」的前提另一半。
 * 若将来有人把它改成返回 {@code Mono.error}，端点服务的 {@code defer} 就不再需要，
 * 而本类的「同步抛出」断言会立刻变红，提示契约变了。
 */
class RequestPipelineTests {

    private ProviderRouteResolver routeResolver;
    private ProtocolDispatchManager dispatchManager;
    private BeforeSend beforeSend;

    @BeforeEach
    void setUp() {
        routeResolver = mock(ProviderRouteResolver.class);
        dispatchManager = new ProtocolDispatchManager();
        // 本类只测路由步骤，不触及翻译 / 装配 / 出站 —— 故后三个协作者给占位即可。
        beforeSend = new BeforeSend(routeResolver, dispatchManager,
                new TranslatorRegistry(List.of(), List.of()),
                new RequestBodyAssembler(
                        new com.kaixuan.copilot_ollama_proxy.upstream.requestbody.RequestBodyStageRegistry(
                                List.of(), List.of(), List.of()),
                        new com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine(
                                new com.fasterxml.jackson.databind.ObjectMapper())),
                new com.kaixuan.copilot_ollama_proxy.upstream.outbound.OutboundRequestAssembler(
                        new com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService(
                                new com.fasterxml.jackson.databind.ObjectMapper()),
                        new com.kaixuan.copilot_ollama_proxy.upstream.outbound.OutboundRequestStageRegistry(List.of()),
                        new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    /** 造一个端点刚建好的 ctx（只含下游侧事实），交给 routeStep 回填。 */
    private RequestPipelineContext ctxFor(String model, WireProtocol downstream, String requestId) {
        return RequestPipelineContext.forEndpoint(new java.util.LinkedHashMap<>(Map.of("model", model)),
                model, downstream, HttpHeaders.EMPTY, requestId, false);
    }

    @Test
    @DisplayName("直连：路由与调度结论回填进 ctx")
    void backfillsBothConclusionsForDirectRoute() {
        givenRouteWithProtocols("[\"CHAT\"]");
        RequestPipelineContext ctx = ctxFor("m", WireProtocol.CHAT, "req-direct");

        beforeSend.routeStep(ctx);

        assertThat(ctx.model()).as("目标模型名回填").isEqualTo("m");
        assertThat(ctx.provider()).as("供应商回填").isNotNull();
        assertThat(ctx.upstreamProtocol()).isEqualTo(WireProtocol.CHAT);
        assertThat(ctx.translationNeeded()).as("两侧同协议 → 直连").isFalse();
    }

    @Test
    @DisplayName("跨协议：上游协议回填为供应商支持的那个，translationNeeded 为真")
    void backfillsTranslationDecisionForCrossProtocolRoute() {
        givenRouteWithProtocols("[\"MESSAGES\"]");
        RequestPipelineContext ctx = ctxFor("m", WireProtocol.CHAT, "req-cross");

        beforeSend.routeStep(ctx);

        assertThat(ctx.upstreamProtocol()).isEqualTo(WireProtocol.MESSAGES);
        assertThat(ctx.translationNeeded()).isTrue();
    }

    @Test
    @DisplayName("路由未解析 → 抛类型化异常（同步抛出，不是返回 null）")
    void throwsTypedExceptionWhenRouteUnresolved() {
        given(routeResolver.resolve(any())).willReturn(null);

        assertThatThrownBy(() -> beforeSend.routeStep(ctxFor("ghost", WireProtocol.CHAT, "req-null")))
                .as("类型化异常让控制器能回 400 而不是「无法连接到上游服务」502")
                .isInstanceOf(UnresolvedModelRouteException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("供应商一个协议都不支持 → NoSupportedProtocolException 透出")
    void propagatesNoSupportedProtocol() {
        givenRouteWithProtocols("[]");

        assertThatThrownBy(() -> beforeSend.routeStep(ctxFor("m", WireProtocol.CHAT, "req-empty")))
                .isInstanceOf(NoSupportedProtocolException.class);
    }

    @Test
    @DisplayName("未注入生命周期通知器时不崩，且不影响回填")
    void worksWithoutLifecycleNotifier() {
        givenRouteWithProtocols("[\"CHAT\"]");
        // 刻意不调 setLifecycleNotifier —— 模拟单元测试直接 new 的场景。
        RequestPipelineContext ctx = ctxFor("m", WireProtocol.CHAT, "req-no-notifier");

        beforeSend.routeStep(ctx);

        assertThat(ctx.upstreamProtocol()).isEqualTo(WireProtocol.CHAT);
    }

    @Test
    @DisplayName("调度结论出来即补生命周期事件，用调用方给的下游协议")
    void notifiesProtocolsWithCallerDownstream() {
        CallLifecycleNotifier notifier = mock(CallLifecycleNotifier.class);
        beforeSend.setLifecycleNotifier(notifier);
        givenRouteWithProtocols("[\"MESSAGES\"]");

        beforeSend.routeStep(ctxFor("m", WireProtocol.RESPONSES, "req-notify"));

        // 下游协议必须来自调用方（各端点服务的身份），上游协议来自调度结论。
        verify(notifier).recordProtocols("req-notify", "RESPONSES", "MESSAGES");
    }

    @Test
    @DisplayName("路由未解析时不补生命周期事件 —— 那时还没有调度结论")
    void doesNotNotifyWhenRouteUnresolved() {
        CallLifecycleNotifier notifier = mock(CallLifecycleNotifier.class);
        beforeSend.setLifecycleNotifier(notifier);
        given(routeResolver.resolve(any())).willReturn(null);

        assertThatThrownBy(() -> beforeSend.routeStep(ctxFor("ghost", WireProtocol.CHAT, "req-x")))
                .isInstanceOf(UnresolvedModelRouteException.class);
        verify(notifier, never()).recordProtocols(any(), any(), any());
    }

    /** 让路由解析返回一个协议集合为指定 JSON 的供应商。 */
    private void givenRouteWithProtocols(String supportedProtocolsJson) {
        ProviderRuntimeConfiguration provider = new ProviderRuntimeConfiguration(
                "relay-x", "https://example.invalid/v1", "key", List.of(),
                "[]", "{\"version\":2,\"groups\":[]}", supportedProtocolsJson, "");
        given(routeResolver.resolve(any())).willReturn(new ResolvedProviderRoute(provider, "m", "m"));
    }
}

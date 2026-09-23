package com.kaixuan.copilot_ollama_proxy.application.pipeline;

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
import com.kaixuan.copilot_ollama_proxy.upstream.send.UpstreamExecutorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RequestPipeline} 前奏段的直接单测。
 *
 * <h2>这里在测什么</h2>
 * 前奏段从三个 Service 搬来，逐字未改逻辑，因此「行为正确」由既有集成用例覆盖。
 * 本类要钉的是<strong>「前奏作为独立单元自己的契约」</strong>——
 * 那些在搬迁前散在三个 Service 里、没有单独用例的东西：
 * <ul>
 *   <li>路由未解析 → 抛类型化异常（不是返回 null）；</li>
 *   <li>供应商一个协议都不支持 → 把 {@link NoSupportedProtocolException} 透出；</li>
 *   <li>生命周期通知器缺省（未注入）时<strong>不崩</strong> —— 单元测试直接 new 的场景；</li>
 *   <li>两条结论都被如实装进 {@link PipelinePreamble}。</li>
 * </ul>
 *
 * <p>它是同步方法、同步抛异常 —— 这正是「等在 {@code defer} 内调用」这个前提的另一半。
 * 若将来有人把它改成返回 {@code Mono.error}，调用方的 {@code defer} 就不再需要，
 * 而本类的「同步抛出」断言会立刻变红，提示契约变了。
 */
class RequestPipelineTests {

    private ProviderRouteResolver routeResolver;
    private ProtocolDispatchManager dispatchManager;
    private RequestPipeline pipeline;

    @BeforeEach
    void setUp() {
        routeResolver = mock(ProviderRouteResolver.class);
        dispatchManager = new ProtocolDispatchManager();
        // 本类只测前奏（run），不触及翻译、执行器查表与请求体装配 —— 故这些依赖给空/占位即可。
        pipeline = new RequestPipeline(routeResolver, dispatchManager,
                new TranslatorRegistry(List.of(), List.of()),
                new UpstreamExecutorRegistry(List.of()),
                new RequestBodyAssembler(
                        new com.kaixuan.copilot_ollama_proxy.upstream.requestbody.RequestBodyStageRegistry(
                                List.of(), List.of(), List.of()),
                        new com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine(
                                new com.fasterxml.jackson.databind.ObjectMapper())));
    }

    @Test
    @DisplayName("直连：路由与调度两个结论都装进 preamble")
    void returnsBothConclusionsForDirectRoute() {
        givenRouteWithProtocols("[\"CHAT\"]");

        PipelinePreamble preamble = pipeline.run("m", WireProtocol.CHAT, "req-direct");

        assertThat(preamble.route().model()).as("路由结论要带出来").isEqualTo("m");
        assertThat(preamble.decision().upstreamProtocol()).isEqualTo(WireProtocol.CHAT);
        assertThat(preamble.decision().translationNeeded()).as("两侧同协议 → 直连").isFalse();
    }

    @Test
    @DisplayName("跨协议：调度结论标记需要翻译，上游协议是供应商支持的那个")
    void returnsTranslationDecisionForCrossProtocolRoute() {
        givenRouteWithProtocols("[\"MESSAGES\"]");

        PipelinePreamble preamble = pipeline.run("m", WireProtocol.CHAT, "req-cross");

        assertThat(preamble.decision().upstreamProtocol()).isEqualTo(WireProtocol.MESSAGES);
        assertThat(preamble.decision().translationNeeded()).isTrue();
    }

    @Test
    @DisplayName("路由未解析 → 抛类型化异常（同步抛出，不是返回 null）")
    void throwsTypedExceptionWhenRouteUnresolved() {
        given(routeResolver.resolve(any())).willReturn(null);

        assertThatThrownBy(() -> pipeline.run("ghost", WireProtocol.CHAT, "req-null"))
                .as("类型化异常让控制器能回 400 而不是「无法连接到上游服务」502")
                .isInstanceOf(UnresolvedModelRouteException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("供应商一个协议都不支持 → NoSupportedProtocolException 透出")
    void propagatesNoSupportedProtocol() {
        givenRouteWithProtocols("[]");

        assertThatThrownBy(() -> pipeline.run("m", WireProtocol.CHAT, "req-empty"))
                .isInstanceOf(NoSupportedProtocolException.class);
    }

    @Test
    @DisplayName("未注入生命周期通知器时不崩，且不影响结论")
    void worksWithoutLifecycleNotifier() {
        givenRouteWithProtocols("[\"CHAT\"]");
        // 刻意不调 setLifecycleNotifier —— 模拟单元测试直接 new 的场景。
        PipelinePreamble preamble = pipeline.run("m", WireProtocol.CHAT, "req-no-notifier");

        assertThat(preamble.decision().upstreamProtocol()).isEqualTo(WireProtocol.CHAT);
    }

    @Test
    @DisplayName("调度结论出来即补生命周期事件，用调用方给的下游协议")
    void notifiesProtocolsWithCallerDownstream() {
        CallLifecycleNotifier notifier = mock(CallLifecycleNotifier.class);
        pipeline.setLifecycleNotifier(notifier);
        givenRouteWithProtocols("[\"MESSAGES\"]");

        pipeline.run("m", WireProtocol.RESPONSES, "req-notify");

        // 下游协议必须来自调用方（各端点服务的身份），上游协议来自调度结论。
        verify(notifier).recordProtocols("req-notify", "RESPONSES", "MESSAGES");
    }

    @Test
    @DisplayName("路由未解析时不补生命周期事件 —— 那时还没有调度结论")
    void doesNotNotifyWhenRouteUnresolved() {
        CallLifecycleNotifier notifier = mock(CallLifecycleNotifier.class);
        pipeline.setLifecycleNotifier(notifier);
        given(routeResolver.resolve(any())).willReturn(null);

        assertThatThrownBy(() -> pipeline.run("ghost", WireProtocol.CHAT, "req-x"))
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

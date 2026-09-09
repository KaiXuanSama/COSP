package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.kaixuan.copilot_ollama_proxy.infrastructure.config.OutboundProxyDecider;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link OutboundProxyTargetProjector} 的投影行为。
 *
 * <p>用真实的 {@link OutboundProxyDecider}（无外部依赖，可直接 new）而非 mock：投影的价值
 * 恰在于「灌进去的目标能被 {@code isDirect} 命中」，mock 掉决策中心就只能断言调用参数，
 * 验不到端到端效果。地址给一个非空值，让「有没有代理」这条判据成立，从而能观察目标命中。
 */
class OutboundProxyTargetProjectorTests {

    private static InetSocketAddress target(String host, int port) {
        return InetSocketAddress.createUnresolved(host, port);
    }

    private static ProviderConfigRow provider(String key, String baseUrl, String anthropicBaseUrl, boolean useProxy) {
        return new ProviderConfigRow(1, key, key, true, baseUrl,
                "[\"OPENAI\",\"ANTHROPIC\"]", anthropicBaseUrl, useProxy, "2026-09-09T00:00:00", List.of());
    }

    private OutboundProxyDecider project(List<ProviderConfigRow> providers) {
        ProviderConfigRepository repository = mock(ProviderConfigRepository.class);
        when(repository.findAllWithModels()).thenReturn(providers);
        OutboundProxyDecider decider = new OutboundProxyDecider();
        decider.updateProxyAddress("127.0.0.1:7890");
        new OutboundProxyTargetProjector(repository, decider).reprojectProxiedTargets();
        return decider;
    }

    @Test
    void onlyProvidersWithProxyEnabledAreProjected() {
        OutboundProxyDecider decider = project(List.of(
                provider("proxied", "https://proxied.example.com/v1", "", true),
                provider("direct", "https://direct.example.com/v1", "", false)));

        // 开了开关的走代理，没开的直连。
        assertThat(decider.isDirect(target("proxied.example.com", 443))).isFalse();
        assertThat(decider.isDirect(target("direct.example.com", 443))).isTrue();
    }

    @Test
    void bothBaseUrlAndAnthropicEndpointAreProjected() {
        OutboundProxyDecider decider = project(List.of(
                provider("dual", "https://openai.example.com/v1", "https://anthropic.example.com/v1", true)));

        // 两条线路的端点都要走代理，只投 base_url 会让 Anthropic 直连线路漏掉。
        assertThat(decider.isDirect(target("openai.example.com", 443))).isFalse();
        assertThat(decider.isDirect(target("anthropic.example.com", 443))).isFalse();
    }

    @Test
    void anthropicEndpointFallsBackToBaseUrlWhenBlank() {
        OutboundProxyDecider decider = project(List.of(
                provider("single", "https://shared.example.com/v1", "", true)));

        // anthropic_base_url 为空 → 回退 base_url，投影出的仍是同一个 host。
        assertThat(decider.isDirect(target("shared.example.com", 443))).isFalse();
    }

    @Test
    void portIsInferredFromScheme() {
        OutboundProxyDecider decider = project(List.of(
                provider("http", "http://plain.example.com/v1", "", true)));

        // http 无显式端口 → 推断 80；决策中心按 host:port 精确匹配，端口对不上就等于没配。
        assertThat(decider.isDirect(target("plain.example.com", 80))).isFalse();
        assertThat(decider.isDirect(target("plain.example.com", 443))).isTrue();
    }

    @Test
    void explicitPortIsHonored() {
        OutboundProxyDecider decider = project(List.of(
                provider("ported", "https://relay.example.com:8443/v1", "", true)));

        assertThat(decider.isDirect(target("relay.example.com", 8443))).isFalse();
    }

    @Test
    void malformedEndpointIsSkippedWithoutBlockingOthers() {
        OutboundProxyDecider decider = project(List.of(
                provider("broken", "not a url", "", true),
                provider("ok", "https://ok.example.com/v1", "", true)));

        // 坏地址被跳过，但不影响后一个正常供应商的投影。
        assertThat(decider.isDirect(target("ok.example.com", 443))).isFalse();
    }

    @Test
    void noProxyProvidersClearsTargets() {
        OutboundProxyDecider decider = project(List.of(
                provider("direct", "https://direct.example.com/v1", "", false)));

        // 没有任何供应商开代理 → 集合空 → 一切直连。
        assertThat(decider.isDirect(target("direct.example.com", 443))).isTrue();
    }
}

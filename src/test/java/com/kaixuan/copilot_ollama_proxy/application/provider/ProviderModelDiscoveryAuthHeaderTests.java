package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRow;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型拉取路径的出站鉴权头装配。
 *
 * <h2>为何这条路径值得独立覆盖</h2>
 * 它是四个调用点里唯一<strong>没有下游请求</strong>的那个，因此也是唯一能验证
 * 「取下游模式下配置值仍然生效」的生产路径 —— 两项探测皆为假，
 * {@code AuthHeaderSetting.resolveHeader} 于是在两种模式下都落到配置的头名。
 *
 * <p>另一个理由是漏接线的症状很窄：聊天正常、只有拉模型 401。
 * 那种缺陷不会被任何聊天线路的用例发现，而排查会先怀疑 Key 或地址。
 *
 * <p>不需要 JDBC 夹具：三个仓储用 Mockito 打桩，
 * {@code ProviderRequestHeaderService} 用真实实例（被测行为正在它里面），
 * 上游用 {@code exchangeFunction} 捕获出站头。
 */
class ProviderModelDiscoveryAuthHeaderTests {

    /**
     * 配置的头名生效，即使模式是「取下游」。
     *
     * <p>这条同时钉住两件事：接线没漏（读的是库里那一列），以及无下游时
     * {@code DOWNSTREAM} 等价于 {@code CONFIGURED}（兜底语义）。
     *
     * <p>刻意选 {@code x-api-key} —— {@code AUTHORIZATION} 与缺省值相同，
     * 漏接线（传 {@code defaults()}）也照样绿。
     */
    @Test
    void configuredApiKeyHeaderIsUsedWhenThereIsNoDownstream() {
        Fixture fixture = new Fixture("{\"mode\":\"DOWNSTREAM\",\"header\":\"X_API_KEY\"}");

        fixture.pull(WireProtocol.CHAT);

        assertThat(fixture.sentHeaders()).containsEntry("x-api-key", "plaintext-key");
        assertThat(fixture.sentHeaders()).doesNotContainKey(HttpHeaders.AUTHORIZATION);
    }

    /** 取设置 + x-api-key：与上一条结果相同，但走的是另一条决策分支。 */
    @Test
    void configuredModeAlsoUsesTheConfiguredHeader() {
        Fixture fixture = new Fixture("{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}");

        fixture.pull(WireProtocol.CHAT);

        assertThat(fixture.sentHeaders()).containsEntry("x-api-key", "plaintext-key");
        assertThat(fixture.sentHeaders()).doesNotContainKey(HttpHeaders.AUTHORIZATION);
    }

    /**
     * 列为空（未迁移的库、或该供应商还没保存过）时兜到 Bearer。
     *
     * <p>这是改造前这条路径的<strong>唯一</strong>行为，保留它是为了让「升级后拉取
     * 突然换了头名」这种回归能被发现。
     */
    @Test
    void blankConfigurationFallsBackToBearer() {
        Fixture fixture = new Fixture("");

        fixture.pull(WireProtocol.CHAT);

        assertThat(fixture.sentHeaders()).containsEntry(HttpHeaders.AUTHORIZATION, "Bearer plaintext-key");
        assertThat(fixture.sentHeaders()).doesNotContainKey("x-api-key");
    }

    /**
     * 供应商在库里查不到时同样兜到 Bearer，而不是抛异常。
     *
     * <p>这条路径真实可达：表单里直接填了 apiKey 明文、而供应商还没落库
     * （新建流程中先试拉一次）。{@code prepareModelPullRequest} 那里传的是 {@code null}，
     * 缺省语义由 {@code AuthHeaderSetting.parse} 统一给出。
     */
    @Test
    void missingProviderRowFallsBackToBearer() {
        Fixture fixture = new Fixture(null, false);

        fixture.pull(WireProtocol.CHAT);

        assertThat(fixture.sentHeaders()).containsEntry(HttpHeaders.AUTHORIZATION, "Bearer plaintext-key");
        assertThat(fixture.sentHeaders()).doesNotContainKey("x-api-key");
    }

    /**
     * 协议仍然决定版本头，只是不再决定鉴权头。
     *
     * <p>两件事的判据不同，容易在重构时被合并：{@code anthropic-version} 是那条线路的
     * 协议要求（缺失即 400），头名则取决于用户配了什么。这条用例让它们同时出现 ——
     * Anthropic 线路 + 配置为 Authorization，即「版本头照发、鉴权头听配置」。
     */
    @Test
    void protocolStillDecidesVersionHeaderButNotTheAuthenticationHeader() {
        Fixture fixture = new Fixture("{\"mode\":\"CONFIGURED\",\"header\":\"AUTHORIZATION\"}");

        fixture.pull(WireProtocol.MESSAGES);

        assertThat(fixture.sentHeaders()).containsEntry("anthropic-version", "2023-06-01");
        assertThat(fixture.sentHeaders()).containsEntry(HttpHeaders.AUTHORIZATION, "Bearer plaintext-key");
        assertThat(fixture.sentHeaders()).doesNotContainKey("x-api-key");
    }

    /**
     * 请求头规则仍在装配之后执行，保有最终决定权。
     *
     * <p>拉取与聊天共用同一套三层装配，这条确认那个分层在这条路径上也成立 ——
     * 曾经这里有一份「双认证头」副本，与聊天链路各写一遍。
     */
    @Test
    void headerRulesStillRunAfterAssembly() {
        Fixture fixture = new Fixture("{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}",
                "[{\"key\":\"Authorization\",\"value\":\"Bearer {apiKey}\"}]");

        fixture.pull(WireProtocol.CHAT);

        assertThat(fixture.sentHeaders()).containsEntry("x-api-key", "plaintext-key");
        assertThat(fixture.sentHeaders()).containsEntry(HttpHeaders.AUTHORIZATION, "Bearer plaintext-key");
    }

    /** 各用例共用的装配：三个仓储打桩 + 捕获出站头的上游。 */
    private static final class Fixture {

        private static final int PROVIDER_ID = 7;
        private static final String PROVIDER_KEY = "relay";

        private final ProviderModelDiscoveryService service;
        private final AtomicReference<Map<String, String>> captured = new AtomicReference<>();

        private Fixture(String authHeaderJson) {
            this(authHeaderJson, "[]", true);
        }

        private Fixture(String authHeaderJson, String headerRulesJson) {
            this(authHeaderJson, headerRulesJson, true);
        }

        private Fixture(String authHeaderJson, boolean providerExists) {
            this(authHeaderJson, "[]", providerExists);
        }

        private Fixture(String authHeaderJson, String headerRulesJson, boolean providerExists) {
            ProviderConfigRepository configRepository = mock(ProviderConfigRepository.class);
            ProviderRequestTransformRepository transformRepository =
                    mock(ProviderRequestTransformRepository.class);

            if (providerExists) {
                when(configRepository.findByKey(PROVIDER_KEY)).thenReturn(new ProviderConfigRow(
                        PROVIDER_ID, PROVIDER_KEY, "Relay", true, "https://relay.example.com/v1",
                        "[\"CHAT\",\"MESSAGES\"]", "", "", false, authHeaderJson, "", List.of()));
                when(transformRepository.findByProviderId(PROVIDER_ID)).thenReturn(
                        new ProviderRequestTransformRow(PROVIDER_ID, 1, headerRulesJson,
                                "[]", "{}", 1, "{\"version\":2,\"groups\":[]}", 2, "", ""));
            }

            service = new ProviderModelDiscoveryService(configRepository,
                    mock(ProviderApiKeyRepository.class), transformRepository,
                    new ProviderRequestHeaderService(new ObjectMapper()),
                    capturingWebClientBuilder(), new ObjectMapper());
        }

        /**
         * 发一次拉取。
         *
         * <p>apiKey 走<strong>表单明文</strong>那条分支，于是不必给凭据仓储打桩解密 ——
         * 本类验的是头装配，凭据来源是另一回事。
         */
        private void pull(WireProtocol protocol) {
            service.pullModels(PROVIDER_KEY, new ProviderModelDiscoveryService.ModelPullCommand(
                            "https://relay.example.com/v1", "plaintext-key", "", "", protocol))
                    .block(Duration.ofSeconds(10));
        }

        private Map<String, String> sentHeaders() {
            assertThat(captured.get()).as("上游未被调用，出站头无从捕获").isNotNull();
            return captured.get();
        }

        private WebClient.Builder capturingWebClientBuilder() {
            return WebClient.builder().exchangeFunction(request -> {
                Map<String, String> headers = new LinkedHashMap<>();
                request.headers().forEach((name, values) -> headers.put(name, String.join(", ", values)));
                captured.set(headers);
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"data\":[]}").build());
            });
        }
    }
}

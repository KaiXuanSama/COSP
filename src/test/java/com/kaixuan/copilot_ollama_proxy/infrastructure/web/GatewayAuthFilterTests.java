package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.application.openai.ChatCompletionService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.AppConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.security.ApiKeyCryptoService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.security.ApiKeyCryptoService.EncryptedValue;

import reactor.core.publisher.Mono;

/**
 * 下游鉴权过滤器集成测试。
 *
 * <p>用真实的 {@link ApiKeyCryptoService} 加密一把测试 Key 写入被 mock 的
 * {@link AppConfigRepository}，从而在真实 HTTP 链路下验证 {@link GatewayAuthFilter}
 * 对 {@code POST /v1/chat/completions} 的拦截行为，以及对发现类接口的放行。
 */
@SpringBootTest(classes = CopilotOllamaProxyApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayAuthFilterTests {

    private static final String ENABLED_KEY = "gateway_auth_enabled";
    private static final String API_KEY_KEY = "gateway_api_key";
    private static final String VALID_KEY = "cosp-test-valid-key-123456";

    @LocalServerPort
    private int port;

    @SuppressWarnings("removal")
    @MockBean
    private ChatCompletionService chatCompletionService;

    @SuppressWarnings("removal")
    @MockBean
    private AppConfigRepository appConfigRepository;

    @Autowired
    private ApiKeyCryptoService cryptoService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(2)).build();
        // 默认：聊天服务返回一个简单响应，便于验证「放行」时确实打到了控制器。
        given(chatCompletionService.chatCompletion(anyMap(), anyString(),
                any(HttpHeaders.class), anyString()))
                .willReturn(Mono.just("{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\"}"));
    }

    /** 在 mock 仓库中写入「已开启 + 指定明文 Key」的配置。 */
    private void enableWithKey(String plaintextKey) {
        given(appConfigRepository.findConfigValue(ENABLED_KEY)).willReturn("true");
        EncryptedValue encrypted = cryptoService.encrypt(plaintextKey);
        given(appConfigRepository.findConfigValue(API_KEY_KEY))
                .willReturn(encrypted.nonceBase64() + ":" + encrypted.ciphertextBase64());
    }

    private WebTestClient.ResponseSpec postChat(String bearer) {
        WebTestClient.RequestBodySpec spec = webTestClient.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        return spec.bodyValue("{\"model\":\"test-model\",\"messages\":[]}").exchange();
    }

    @Test
    void enabledWithCorrectKeyPassesThrough() {
        enableWithKey(VALID_KEY);
        postChat(VALID_KEY).expectStatus().isOk();
    }

    @Test
    void enabledWithWrongKeyReturns401() {
        enableWithKey(VALID_KEY);
        postChat("cosp-wrong-key").expectStatus().isUnauthorized();
    }

    @Test
    void enabledWithoutAuthorizationHeaderReturns401() {
        enableWithKey(VALID_KEY);
        postChat(null).expectStatus().isUnauthorized();
    }

    @Test
    void disabledPassesThroughRegardlessOfKey() {
        given(appConfigRepository.findConfigValue(ENABLED_KEY)).willReturn("false");
        postChat(null).expectStatus().isOk();
        postChat("any-key").expectStatus().isOk();
    }

    @Test
    void enabledButNoKeyConfiguredPassesThrough() {
        // 1A：开启但未生成 Key → 放行，避免自锁死。
        given(appConfigRepository.findConfigValue(ENABLED_KEY)).willReturn("true");
        given(appConfigRepository.findConfigValue(API_KEY_KEY)).willReturn(null);
        postChat(null).expectStatus().isOk();
    }

    @Test
    void discoveryEndpointIsNeverBlocked() {
        // 即便开启鉴权，Ollama 版本发现接口也不应被拦截（不带 Authorization 也放行）。
        // fake_version 未 stub 时返回 null，控制器回退到 application.yml 默认版本，仍为 200。
        enableWithKey(VALID_KEY);
        webTestClient.get().uri("/api/version").exchange().expectStatus().isOk();
    }
}

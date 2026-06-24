package com.kaixuan.copilot_ollama_proxy.provider.agnes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.agnes.openai.AgnesOpenAiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgnesProviderTest {

    @Test
    void agnesOpenAiChatServiceHasCorrectProviderKey() {
        RuntimeProviderCatalog catalog = () -> List.of(new ProviderRuntimeConfiguration("agnes", "https://apihub.agnes-ai.com", "test-key", "openai", List.of(new ProviderRuntimeModel("agnes-2.0-flash", 0, false, false, "Medium"))));
        TestAgnesOpenAiService service = new TestAgnesOpenAiService(catalog);

        assertThat(service.getProviderKey()).isEqualTo("agnes");
    }

    @Test
    void agnesOpenAiChatServiceHasCorrectDefaultBaseUrl() {
        RuntimeProviderCatalog catalog = () -> List.of(new ProviderRuntimeConfiguration("agnes", "https://apihub.agnes-ai.com", "test-key", "openai", List.of(new ProviderRuntimeModel("agnes-2.0-flash", 0, false, false, "Medium"))));
        TestAgnesOpenAiService service = new TestAgnesOpenAiService(catalog);

        assertThat(service.exposeDefaultBaseUrl()).isEqualTo("https://apihub.agnes-ai.com/v1");
    }

    @Test
    void agnesOpenAiChatServiceNormalizesBaseUrlCorrectly() {
        RuntimeProviderCatalog catalog = () -> List.of(new ProviderRuntimeConfiguration("agnes", "https://apihub.agnes-ai.com", "test-key", "openai", List.of(new ProviderRuntimeModel("agnes-2.0-flash", 0, false, false, "Medium"))));
        TestAgnesOpenAiService service = new TestAgnesOpenAiService(catalog);

        // 测试去除尾部斜杠
        assertThat(service.exposeNormalizeBaseUrl("https://apihub.agnes-ai.com/v1/")).isEqualTo("https://apihub.agnes-ai.com/v1");
        // 测试保留 /v1 后缀
        assertThat(service.exposeNormalizeBaseUrl("https://apihub.agnes-ai.com/v1")).isEqualTo("https://apihub.agnes-ai.com/v1");
    }

    @Test
    void agnesOpenAiChatServiceAppliesBearerTokenAuthentication() {
        RuntimeProviderCatalog catalog = () -> List.of(new ProviderRuntimeConfiguration("agnes", "https://apihub.agnes-ai.com", "test-key", "openai", List.of(new ProviderRuntimeModel("agnes-2.0-flash", 0, false, false, "Medium"))));
        TestAgnesOpenAiService service = new TestAgnesOpenAiService(catalog);

        HttpHeaders headers = new HttpHeaders();
        service.exposeApplyAuthenticationHeaders(headers, "test-api-key");

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-api-key");
    }

    @Test
    void agnesOpenAiChatServiceHasCorrectChatCompletionsUri() {
        RuntimeProviderCatalog catalog = () -> List.of(new ProviderRuntimeConfiguration("agnes", "https://apihub.agnes-ai.com", "test-key", "openai", List.of(new ProviderRuntimeModel("agnes-2.0-flash", 0, false, false, "Medium"))));
        TestAgnesOpenAiService service = new TestAgnesOpenAiService(catalog);

        assertThat(service.exposeChatCompletionsUri()).isEqualTo("/chat/completions");
    }

    private static final class TestAgnesOpenAiService extends AgnesOpenAiChatService {

        private TestAgnesOpenAiService(RuntimeProviderCatalog runtimeProviderCatalog) {
            super(runtimeProviderCatalog, "agnes-2.0-flash", new ObjectMapper());
        }

        private String exposeDefaultBaseUrl() {
            return defaultBaseUrl();
        }

        private String exposeNormalizeBaseUrl(String rawBaseUrl) {
            return normalizeBaseUrl(rawBaseUrl);
        }

        private void exposeApplyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
            applyAuthenticationHeaders(headers, apiKey);
        }

        private String exposeChatCompletionsUri() {
            return chatCompletionsUri();
        }
    }
}
package com.kaixuan.copilot_ollama_proxy.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractOpenAiCompatibleUpstreamChatServiceTests {

    @Test
    void supportsModelUsesRuntimeCatalogSnapshot() {
        RuntimeProviderCatalog catalog = () -> List.of(new ProviderRuntimeConfiguration("stub", "", "", "openai", List.of(new ProviderRuntimeModel("model-a", 0, false, false))));

        TestOpenAiService service = new TestOpenAiService(catalog);

        assertThat(service.supportsModel("model-a")).isTrue();
        assertThat(service.supportsModel("model-b")).isFalse();
    }

    @Test
    void prepareRequestBodyResolvesFallbackModelAndRunsCustomizationHook() {
        RuntimeProviderCatalog catalog = List::of;
        TestOpenAiService service = new TestOpenAiService(catalog);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", null);
        request.put("temperature", 0.7);
        request.put("tool_choice", null);

        Map<String, Object> prepared = service.exposePrepareRequestBody(request, true, "fallback-model");

        assertThat(prepared).containsEntry("model", "fallback-model");
        assertThat(prepared).containsEntry("stream", true);
        assertThat(prepared).containsEntry("customized", true);
        assertThat(prepared).doesNotContainKey("tool_choice");
    }

    private static final class TestOpenAiService extends AbstractOpenAiCompatibleUpstreamChatService {

        private TestOpenAiService(RuntimeProviderCatalog runtimeProviderCatalog) {
            super(runtimeProviderCatalog, new ObjectMapper(), "default-model");
        }

        private Map<String, Object> exposePrepareRequestBody(Map<String, Object> request, boolean stream, String model) {
            return prepareRequestBody(request, stream, model);
        }

        @Override
        public String getProviderKey() {
            return "stub";
        }

        @Override
        protected String defaultBaseUrl() {
            return "https://example.com";
        }

        @Override
        protected String normalizeBaseUrl(String rawBaseUrl) {
            return rawBaseUrl;
        }

        @Override
        protected void applyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
            headers.set("x-api-key", apiKey);
        }

        @Override
        protected String chatCompletionsUri() {
            return "/v1/chat/completions";
        }

        @Override
        protected void customizeRequestBody(Map<String, Object> body, String resolvedModel) {
            body.put("customized", true);
        }
    }
}
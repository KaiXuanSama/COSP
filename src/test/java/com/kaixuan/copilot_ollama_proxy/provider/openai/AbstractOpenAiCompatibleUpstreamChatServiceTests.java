package com.kaixuan.copilot_ollama_proxy.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractOpenAiCompatibleUpstreamChatServiceTests {

    @Test
    void supportsModelUsesRuntimeCatalogSnapshot() {
        RuntimeProviderCatalog catalog = () -> List.of(new ProviderRuntimeConfiguration("stub", "", "", "openai", List.of(new ProviderRuntimeModel("model-a", 0, false, false, "Medium"))));

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

    @Test
    void normalizeChunkRemovesEmptyToolCallsAndNormalizesFinishReason() throws Exception {
        RuntimeProviderCatalog catalog = List::of;
        TestOpenAiService service = new TestOpenAiService(catalog);

        String raw = """
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"content":"","reasoning_content":"The","tool_calls":[]},"finish_reason":""}]}
                """;

        String normalized = service.exposeTranslateChunk(raw);

        assertThat(normalized).contains("\"reasoning_content\":\"The\"");
        assertThat(normalized).contains("\"finish_reason\":null");
        assertThat(normalized).doesNotContain("\"tool_calls\"");
        assertThat(normalized).doesNotContain("\"content\":\"\"");
    }

    @Test
    void normalizeChunkUnifiesThinkingAliasesToReasoningContent() throws Exception {
        RuntimeProviderCatalog catalog = List::of;
        TestOpenAiService service = new TestOpenAiService(catalog);

        String raw = """
                {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"thinking":"Hello thinking"},"finish_reason":null}]}
                """;

        String normalized = service.exposeTranslateChunk(raw);

        assertThat(normalized).contains("\"reasoning_content\":\"Hello thinking\"");
        assertThat(normalized).doesNotContain("\"thinking\"");
    }

    @Test
    void normalizeFinishChunkKeepsEmptyDeltaObjectInsteadOfNullFields() throws Exception {
        RuntimeProviderCatalog catalog = List::of;
        TestOpenAiService service = new TestOpenAiService(catalog);

        String raw = """
                {"id":"chatcmpl-3","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"role":null,"content":null},"finish_reason":"stop"}]}
                """;

        String normalized = service.exposeTranslateChunk(raw);

        assertThat(normalized).contains("\"delta\":{}");
        assertThat(normalized).contains("\"finish_reason\":\"stop\"");
        assertThat(normalized).doesNotContain("\"role\":null");
        assertThat(normalized).doesNotContain("\"content\":null");
    }

    private static final class TestOpenAiService extends AbstractOpenAiCompatibleUpstreamChatService {

        private TestOpenAiService(RuntimeProviderCatalog runtimeProviderCatalog) {
            super(runtimeProviderCatalog, new ObjectMapper(), "default-model");
        }

        private Map<String, Object> exposePrepareRequestBody(Map<String, Object> request, boolean stream, String model) {
            return prepareRequestBody(request, stream, model);
        }

        private String exposeTranslateChunk(String chunk) throws Exception {
            Method method = AbstractOpenAiCompatibleUpstreamChatService.class.getDeclaredMethod(
                    "translateChunk", String.class, AtomicBoolean.class, StringBuilder.class, AtomicReference.class);
            method.setAccessible(true);
            return (String) method.invoke(this, chunk, new AtomicBoolean(false), new StringBuilder(), new AtomicReference<String>("chatcmpl-unknown"));
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
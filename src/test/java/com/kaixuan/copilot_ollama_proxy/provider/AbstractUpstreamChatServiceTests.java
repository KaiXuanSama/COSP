package com.kaixuan.copilot_ollama_proxy.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import reactor.core.publisher.Mono;

class AbstractUpstreamChatServiceTests {

    @Test
    void prepareRequestBodyResolvesFallbackModelAndRunsCustomizationHook() {
        TestOpenAiService service = new TestOpenAiService();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", null);
        request.put("temperature", 0.7);
        request.put("tool_choice", null);

        Map<String, Object> prepared = service.exposePrepareRequestBody(request, true, "fallback-model", provider());

        assertThat(prepared).containsEntry("model", "fallback-model");
        assertThat(prepared).containsEntry("stream", true);
        assertThat(prepared).containsEntry("customized", true);
        assertThat(prepared).doesNotContainKey("tool_choice");
    }

    @Test
    void normalizeChunkRemovesEmptyToolCallsAndNormalizesFinishReason() throws Exception {
        TestOpenAiService service = new TestOpenAiService();

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
        TestOpenAiService service = new TestOpenAiService();

        String raw = """
                {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"thinking":"Hello thinking"},"finish_reason":null}]}
                """;

        String normalized = service.exposeTranslateChunk(raw);

        assertThat(normalized).contains("\"reasoning_content\":\"Hello thinking\"");
        assertThat(normalized).doesNotContain("\"thinking\"");
    }

    @Test
    void normalizeFinishChunkKeepsEmptyDeltaObjectInsteadOfNullFields() throws Exception {
        TestOpenAiService service = new TestOpenAiService();

        String raw = """
                {"id":"chatcmpl-3","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"role":null,"content":null},"finish_reason":"stop"}]}
                """;

        String normalized = service.exposeTranslateChunk(raw);

        assertThat(normalized).contains("\"delta\":{}");
        assertThat(normalized).contains("\"finish_reason\":\"stop\"");
        assertThat(normalized).doesNotContain("\"role\":null");
        assertThat(normalized).doesNotContain("\"content\":null");
    }

    @Test
    void normalizeChunkPreservesWhitespaceContentLikeNewlinesAndSpaces() throws Exception {
        TestOpenAiService service = new TestOpenAiService();

        // 换行符 content 不应被清理
        String newlineChunk = """
                {"id":"chatcmpl-4","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"content":"\\n"},"finish_reason":null}]}
                """;
        String normalizedNewline = service.exposeTranslateChunk(newlineChunk);
        assertThat(normalizedNewline).contains("\"content\":\"\\n\"");

        // 空格 content 不应被清理
        String spaceChunk = """
                {"id":"chatcmpl-5","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"content":" "},"finish_reason":null}]}
                """;
        String normalizedSpace = service.exposeTranslateChunk(spaceChunk);
        assertThat(normalizedSpace).contains("\"content\":\" \"");

        // 缩进+换行 content 不应被清理
        String indentChunk = """
                {"id":"chatcmpl-6","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"content":"  \\n"},"finish_reason":null}]}
                """;
        String normalizedIndent = service.exposeTranslateChunk(indentChunk);
        assertThat(normalizedIndent).contains("\"content\":\"  \\n\"");
    }

    @Test
    void webClientFilterCapturesFinalRequestHeadersInsteadOfOnlyDefaultHeaders() {
        TestOpenAiService service = new TestOpenAiService();
        AtomicReference<Map<String, String>> sentHeaders = new AtomicReference<>();
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            Map<String, String> headers = new LinkedHashMap<>();
            request.headers().forEach((name, values) -> headers.put(name, String.join(", ", values)));
            sentHeaders.set(headers);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{}").build());
        }));

        Map<String, String> capturedHeaders = new LinkedHashMap<>();
        ProviderRuntimeConfiguration provider = new ProviderRuntimeConfiguration(
                "stub", "https://example.com", "actual-api-key", List.of(),
                "[{\"key\":\"X-Api-Key\",\"value\":\"{apiKey}\"},{\"key\":\"X-Provider\",\"value\":\"generic\"}]",
                "{\"version\":1,\"rules\":[]}");

        service.exposeBuildWebClient(capturedHeaders, provider).post().uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("model", "model-a")).retrieve().bodyToMono(String.class).block();

        assertThat(sentHeaders.get()).containsEntry(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        assertThat(sentHeaders.get()).containsEntry(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(sentHeaders.get()).containsEntry("X-Provider", "generic");
        assertThat(sentHeaders.get()).containsEntry("X-Api-Key", "actual-api-key");
        assertThat(capturedHeaders).containsEntry(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        assertThat(capturedHeaders).containsEntry(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(capturedHeaders).containsEntry("X-Provider", "generic");
        assertThat(capturedHeaders).containsEntry("X-Api-Key", "****");
        assertThat(capturedHeaders.values()).doesNotContain("actual-api-key");
    }

    private static final class TestOpenAiService extends AbstractUpstreamChatService {

        private TestOpenAiService() {
            super(new ObjectMapper(), "default-model", new ProviderRequestHeaderService(new ObjectMapper()));
        }

        private Map<String, Object> exposePrepareRequestBody(Map<String, Object> request, boolean stream,
                                                              String model, ProviderRuntimeConfiguration provider) {
            return prepareRequestBody(request, stream, model, provider);
        }

        private WebClient exposeBuildWebClient(Map<String, String> capturedHeaders,
                                               ProviderRuntimeConfiguration provider) {
            return buildWebClientWithHeaders(capturedHeaders, provider, HttpHeaders.EMPTY, false);
        }

        private String exposeTranslateChunk(String chunk) throws Exception {
            Method method = AbstractUpstreamChatService.class.getDeclaredMethod(
                    "normalizeUpstreamChunk", String.class, AtomicBoolean.class, StringBuilder.class, AtomicReference.class);
            method.setAccessible(true);
            return (String) method.invoke(this, chunk, new AtomicBoolean(false), new StringBuilder(), new AtomicReference<String>("chatcmpl-unknown"));
        }

        @Override
        protected String defaultBaseUrl() {
            return "https://example.com";
        }

        @Override
        protected String chatCompletionsUri() {
            return "/v1/chat/completions";
        }

        @Override
        protected void customizeRequestBody(Map<String, Object> body, String resolvedModel,
                                            ProviderRuntimeConfiguration provider) {
            body.put("customized", true);
        }
    }

    private ProviderRuntimeConfiguration provider() {
        return new ProviderRuntimeConfiguration("stub", "", "", List.of());
    }
}
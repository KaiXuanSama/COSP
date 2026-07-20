package com.kaixuan.copilot_ollama_proxy.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import reactor.core.publisher.Flux;
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

    /**
     * C1 实锤测试：流式响应吐出若干 chunk 后中途以原始 IOException 断开，观察真实行为。
     *
     * <p>关注两个事实（如实断言，不预设结论）：
     * <ol>
     *   <li>上游被调用几次 —— 揭示这种"流中途 IOException"是否命中 {@code buildRetrySpec} 的重试 filter；</li>
     *   <li>下游实际收到的 chunk 序列 —— 揭示若重试是否会把两次的 chunk 叠加（计数虚高）。</li>
     * </ol>
     *
     * <p>不改任何 provider 生产代码：用 {@code exchangeFunction} mock 上游，它在 build 时
     * 优先于 {@code clientConnector}，因此流式路径的 mock 有效。
     */
    @Test
    void midStreamIoExceptionRevealsWhetherRetryDuplicatesChunks() {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        TestOpenAiService service = new TestOpenAiService();

        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            int attempt = upstreamCallCount.incrementAndGet();
            DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
            // 每次尝试都吐 3 个 chunk，随后本次流以原始 IOException 中途断开。
            Flux<DataBuffer> body = Flux.<DataBuffer>concat(
                    Mono.just(sseData(factory, "{\"id\":\"c-" + attempt + "-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"},\"finish_reason\":null}]}")),
                    Mono.just(sseData(factory, "{\"id\":\"c-" + attempt + "-2\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"b\"},\"finish_reason\":null}]}")),
                    Mono.just(sseData(factory, "{\"id\":\"c-" + attempt + "-3\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"c\"},\"finish_reason\":null}]}")))
                    .concatWith(Flux.error(new java.io.IOException("connection reset by peer")));

            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body)
                    .build());
        }));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");

        List<String> received = new java.util.ArrayList<>();
        Throwable error = catchThrowable(() -> service.exposeChatCompletionStream(request, "model-a", provider())
                .doOnNext(received::add)
                .blockLast(Duration.ofSeconds(20)));

        // 如实记录真实行为，便于诊断：上游调用次数 + 下游收到的 chunk。
        System.out.println("[C1] 上游调用次数 = " + upstreamCallCount.get());
        System.out.println("[C1] 下游收到 chunk 数 = " + received.size() + "，内容 = " + received);
        System.out.println("[C1] 终态异常 = " + (error == null ? "无（正常完成）" : error.getClass().getSimpleName() + ": " + error.getMessage()));

        // 断言揭示两种可能之一：
        // - 若 IOException 不命中重试 filter：upstreamCallCount == 1，received 只含首次的 3 个 chunk，随后异常终止；
        // - 若命中重试：upstreamCallCount > 1，received 会累加多次尝试的 chunk（计数虚高）。
        if (upstreamCallCount.get() == 1) {
            assertThat(received).hasSize(3);
            assertThat(received).allMatch(chunk -> chunk.contains("c-1-"));
            assertThat(error).isNotNull();
        } else {
            // 命中重试的情况下，received 至少包含多于一次尝试的 chunk，实锤计数虚高。
            assertThat(received.size()).isGreaterThan(3);
        }
    }

    /**
     * RETRYING 实锤测试：上游首次返回可重试的 500，第二次成功，
     * 验证 {@code buildRetrySpec} 的 doBeforeRetry 真的通过注入的 notifier 发出了 RETRYING 事件。
     *
     * <p>断言两点：
     * <ol>
     *   <li>至少发出一个 RETRYING 阶段事件（重试对前端可见）；</li>
     *   <li>RETRYING 事件携带正确的 requestId 与递增的 attempt（首次重试 attempt=1）。</li>
     * </ol>
     */
    @Test
    void upstreamRetryEmitsRetryingLifecycleEvent() {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        List<CallLifecycleEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        TestOpenAiService service = new TestOpenAiService();
        service.setLifecycleNotifier(events::add);

        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            int attempt = upstreamCallCount.incrementAndGet();
            if (attempt == 1) {
                // 首次返回可重试的 500，触发一次重试。
                return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":\"upstream boom\"}").build());
            }
            // 第二次成功，吐一个 chunk 后正常结束。
            DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
            Flux<DataBuffer> body = Mono.just(sseData(factory,
                    "{\"id\":\"ok-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}")).flux();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");

        List<String> received = new java.util.ArrayList<>();
        catchThrowable(() -> service.exposeChatCompletionStream(request, "model-a", provider(), "req-retry-1")
                .doOnNext(received::add)
                .blockLast(Duration.ofSeconds(20)));

        List<CallLifecycleEvent> retryingEvents = events.stream()
                .filter(e -> e.phase() == CallPhase.RETRYING)
                .toList();

        System.out.println("[RETRYING] 上游调用次数 = " + upstreamCallCount.get());
        System.out.println("[RETRYING] RETRYING 事件数 = " + retryingEvents.size());

        assertThat(upstreamCallCount.get()).isGreaterThanOrEqualTo(2);
        assertThat(retryingEvents).isNotEmpty();
        assertThat(retryingEvents).allMatch(e -> "req-retry-1".equals(e.requestId()));
        assertThat(retryingEvents.get(0).attempt()).isEqualTo(1);
        assertThat(retryingEvents.get(0).stream()).isTrue();
    }

    /** 把一段 JSON 包装成 SSE data 帧的 DataBuffer（{@code data: {...}\n\n}）。 */
    private static DataBuffer sseData(DefaultDataBufferFactory factory, String json) {
        byte[] bytes = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
        return factory.wrap(bytes);
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

        private Flux<String> exposeChatCompletionStream(Map<String, Object> request, String model,
                                                        ProviderRuntimeConfiguration provider) {
            return chatCompletionStream(request, model, provider, HttpHeaders.EMPTY, null);
        }

        private Flux<String> exposeChatCompletionStream(Map<String, Object> request, String model,
                                                        ProviderRuntimeConfiguration provider, String requestId) {
            return chatCompletionStream(request, model, provider, HttpHeaders.EMPTY, requestId);
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
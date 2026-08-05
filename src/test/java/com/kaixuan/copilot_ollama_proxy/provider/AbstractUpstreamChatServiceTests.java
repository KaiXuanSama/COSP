package com.kaixuan.copilot_ollama_proxy.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallRetryRegistry;
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

    /**
     * 取消穿透 backoff 实锤测试：下游在重试 backoff 等待期间主动断连，重试链应同步停止。
     *
     * <p>场景：上游持续返回可重试的 500，若不取消会一路重试（backoff 2s/4s/8s...最多 5 次）。
     * 订阅后在第一个 backoff 等待窗口内（backoff 最小 ≥1s）dispose 订阅，模拟下游 Copilot 断连。
     * Reactor 的 cancel 信号应向上穿透到 {@code retryWhen} 的 backoff 定时器，中止后续重试。
     *
     * <p>断言：取消前上游只被调用 1 次；dispose 后等待远超第一个 backoff 窗口（jitter 后最大约 3s），
     * 上游调用次数不再增长——证明重试链随下游断连同步取消，不会空打上游。
     *
     * <p>此行为当前由 Reactor cancel 级联天然实现（无显式取消代码）。本测试锁定它，
     * 防止后续对重试链的重构无意破坏这一特性。
     */
    @Test
    void downstreamCancelDuringRetryBackoffStopsUpstreamRetries() throws InterruptedException {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        TestOpenAiService service = new TestOpenAiService();

        // 上游持续返回可重试的 500：若重试不被取消，会一路重试下去。
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            upstreamCallCount.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"error\":\"upstream boom\"}").build());
        }));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");

        // 订阅拿到 Disposable 模拟下游连接；首次上游调用后进入第一个 backoff 等待（≥1s）。
        // 吞掉可能的终态信号，避免污染测试输出。
        reactor.core.Disposable subscription = service
                .exposeChatCompletionStream(request, "model-a", provider(), "req-cancel-retry-1")
                .subscribe(chunk -> { }, error -> { });

        // 等到首次上游调用已发生，但仍处于第一个 backoff 等待窗口内（backoff 最小 ≥1s，此处 500ms 安全）。
        Thread.sleep(500);
        int callsBeforeCancel = upstreamCallCount.get();

        // 模拟下游主动断开：dispose 订阅，取消信号应穿透到 retryWhen 的 backoff。
        subscription.dispose();

        // 等待远超第一个 backoff 窗口（jitter 后最大约 3s）：若取消未穿透，第 2 次上游调用会在此期间发生。
        Thread.sleep(4500);
        int callsAfterCancel = upstreamCallCount.get();

        System.out.println("[CANCEL-RETRY] 取消前上游调用次数 = " + callsBeforeCancel);
        System.out.println("[CANCEL-RETRY] 取消后上游调用次数 = " + callsAfterCancel);

        // 锁定行为：取消发生在 backoff 等待期间，重试链应停止，上游调用次数不再增长。
        assertThat(callsBeforeCancel).isEqualTo(1);
        assertThat(callsAfterCancel).isEqualTo(callsBeforeCancel);
    }

    /**
     * 静默重试实锤测试：触发重试信号后，当前上游请求被中断，重新发起一次新请求，
     * 且<strong>下游流不终止</strong> —— 新的 chunk 继续沿同一条流下发。
     *
     * <p>场景：第一次上游调用<strong>挂起</strong>（返回永不结束的 SSE 流，不吐任何 chunk），
     * 此时触发 {@link CallRetryRegistry#retry}。预期：
     * <ol>
     *   <li>第一次上游请求被取消（takeUntilOther 中断）；</li>
     *   <li>重新发起第二次上游请求，且返回的 chunk 正常送达下游；</li>
     *   <li>全程没有 error、没有提前 complete —— 下游看到的是一条连贯的流。</li>
     * </ol>
     *
     * <p>这是「静默重试」的核心语义：只中断上游往返，不触碰下游连接。
     */
    @Test
    void silentRetryReissuesUpstreamRequestWhileDownstreamStaysOpen() throws InterruptedException {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        CallRetryRegistry retryRegistry = new CallRetryRegistry();
        TestOpenAiService service = new TestOpenAiService();
        service.setCallRetryRegistry(retryRegistry);

        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            int attempt = upstreamCallCount.incrementAndGet();
            if (attempt == 1) {
                // 第一次：挂起 —— 永不结束、不吐任何数据。等待被静默重试信号中断。
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .body(Flux.<DataBuffer>never())
                        .build());
            }
            // 第二次：正常吐一个 chunk 后结束。
            Flux<DataBuffer> body = Mono.just(sseData(factory,
                    "{\"id\":\"ok-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"retried\"},\"finish_reason\":null}]}"))
                    .concatWith(Mono.just(sseData(factory, "[DONE]")));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");

        List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        reactor.core.Disposable subscription = service
                .exposeChatCompletionStream(request, "model-a", provider(), "req-silent-retry-1")
                .subscribe(received::add, error -> { });

        // 等到第一次上游调用已发生且仍挂起。
        Thread.sleep(500);
        assertThat(upstreamCallCount.get()).isEqualTo(1);

        // 触发静默重试。
        assertThat(retryRegistry.retry("req-silent-retry-1")).isTrue();

        // 等到第二次调用完成、chunk 送达下游。
        Thread.sleep(2000);

        System.out.println("[SILENT-RETRY] 上游调用次数 = " + upstreamCallCount.get());
        System.out.println("[SILENT-RETRY] 下游收到 = " + received);

        // 第二次上游调用已发生，且它的 chunk 正常到了下游。
        assertThat(upstreamCallCount.get()).isEqualTo(2);
        assertThat(received).anyMatch(chunk -> chunk.contains("retried"));
        assertThat(received).contains("[DONE]");
        // 流未提前终止：订阅仍活着（第二次调用完成后才自然结束）。
        subscription.dispose();
    }

    /**
     * 静默重试不消耗异常重试预算：即使信号在「异常重试 backoff 等待期间」触发，
     * 也直接进入新的一轮请求，而不是被 retryWhen 计入 5 次。
     *
     * <p>场景：第一次上游返回可重试的 500，进入 backoff 等待；此时触发静默重试。
     * 预期：backoff 定时器被取消，新请求立即发起并成功 —— 总上游调用次数为 2
     * （1 次 500 + 1 次成功），而非「1 + 1（异常重试）…」的叠加。
     */
    @Test
    void silentRetryDuringBackoffDoesNotConsumeExceptionRetryBudget() throws InterruptedException {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        CallRetryRegistry retryRegistry = new CallRetryRegistry();
        TestOpenAiService service = new TestOpenAiService();
        service.setCallRetryRegistry(retryRegistry);

        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            int attempt = upstreamCallCount.incrementAndGet();
            if (attempt == 1) {
                // 第一次：可重试的 500，会触发异常重试 backoff。
                return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":\"upstream boom\"}").build());
            }
            // 第二次：成功。
            Flux<DataBuffer> body = Mono.just(sseData(factory,
                    "{\"id\":\"ok-2\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}"))
                    .concatWith(Mono.just(sseData(factory, "[DONE]")));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");

        List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        service.exposeChatCompletionStream(request, "model-a", provider(), "req-silent-budget-1")
                .subscribe(received::add, error -> { });

        // 等到第一次 500 已发生、进入 backoff 等待。
        Thread.sleep(500);
        assertThat(upstreamCallCount.get()).isEqualTo(1);

        // 在 backoff 等待期间触发静默重试：应取消定时器并立即发起新请求。
        assertThat(retryRegistry.retry("req-silent-budget-1")).isTrue();

        // 等待第二次调用完成。
        Thread.sleep(2000);

        System.out.println("[SILENT-BUDGET] 上游调用次数 = " + upstreamCallCount.get());
        System.out.println("[SILENT-BUDGET] 下游收到 = " + received);

        // 只有 1 次 500 + 1 次成功，没有被异常重试预算叠加成 6 次。
        assertThat(upstreamCallCount.get()).isEqualTo(2);
        assertThat(received).anyMatch(chunk -> chunk.contains("ok"));
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
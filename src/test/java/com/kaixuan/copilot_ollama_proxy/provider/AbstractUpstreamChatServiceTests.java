package com.kaixuan.copilot_ollama_proxy.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
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

    // ── 空响应兜底 ──────────────────────────────────────────────────────────

    /**
     * 空流（仅 role / finish / [DONE]，无任何实质载荷）触发自动重试，第二轮有内容即放行。
     *
     * <p>对应 mock 的 {@code empty-stream}。断言两件事：
     * 上游被重发（次数 2），且下游<strong>只看到第二轮</strong>的内容 ——
     * 第一轮的空帧被 gate 拦下，不该泄漏给下游。
     */
    @Test
    void emptyStreamTriggersRetryAndSecondRoundContentPassesThrough() {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        TestOpenAiService service = new TestOpenAiService();

        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            int attempt = upstreamCallCount.incrementAndGet();
            Flux<DataBuffer> body = attempt == 1
                    // 第一轮：role + finish + [DONE]，零实质载荷。
                    ? Flux.concat(
                            Mono.just(sseData(factory, "{\"id\":\"e-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}")),
                            Mono.just(sseData(factory, "{\"id\":\"e-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}")),
                            Mono.just(sseData(factory, "[DONE]")))
                    // 第二轮：正常回复。
                    : Flux.concat(
                            Mono.just(sseData(factory, "{\"id\":\"ok\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"},\"finish_reason\":null}]}")),
                            Mono.just(sseData(factory, "[DONE]")));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        List<String> received = service
                .exposeChatCompletionStream(newRequest(), "model-a", provider(), "req-empty-1")
                .collectList().block(Duration.ofSeconds(20));

        assertThat(upstreamCallCount.get()).isEqualTo(2);
        assertThat(received).isNotNull();
        assertThat(received).anyMatch(chunk -> chunk.contains("hello"));
        // 第一轮的空帧被拦住，没有泄漏到下游。
        assertThat(received).noneMatch(chunk -> chunk.contains("\"id\":\"e-1\""));
    }

    /** 200 但 0 帧（空 body）同样判空并重试 —— gate 一帧都没见到，自然没开闸。 */
    @Test
    void emptyBodyWithZeroFramesTriggersRetry() {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        TestOpenAiService service = new TestOpenAiService();

        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            int attempt = upstreamCallCount.incrementAndGet();
            Flux<DataBuffer> body = attempt == 1
                    ? Flux.empty()
                    : Flux.concat(
                            Mono.just(sseData(factory, "{\"id\":\"ok\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"recovered\"},\"finish_reason\":null}]}")),
                            Mono.just(sseData(factory, "[DONE]")));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        List<String> received = service
                .exposeChatCompletionStream(newRequest(), "model-a", provider(), "req-empty-body-1")
                .collectList().block(Duration.ofSeconds(20));

        assertThat(upstreamCallCount.get()).isEqualTo(2);
        assertThat(received).isNotNull().anyMatch(chunk -> chunk.contains("recovered"));
    }

    /** 全 0 usage 不是独立判据：空流带全 0 usage 仍按内容口径判空并重试。 */
    @Test
    void emptyStreamWithAllZeroUsageStillTriggersRetry() {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        TestOpenAiService service = new TestOpenAiService();

        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            int attempt = upstreamCallCount.incrementAndGet();
            Flux<DataBuffer> body = attempt == 1
                    ? Flux.concat(
                            Mono.just(sseData(factory, "{\"id\":\"z-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}")),
                            Mono.just(sseData(factory, "[DONE]")))
                    : Flux.concat(
                            Mono.just(sseData(factory, "{\"id\":\"ok\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"zzz\"},\"finish_reason\":null}]}")),
                            Mono.just(sseData(factory, "[DONE]")));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        List<String> received = service
                .exposeChatCompletionStream(newRequest(), "model-a", provider(), "req-empty-usage-1")
                .collectList().block(Duration.ofSeconds(20));

        assertThat(upstreamCallCount.get()).isEqualTo(2);
        assertThat(received).isNotNull().anyMatch(chunk -> chunk.contains("zzz"));
    }

    /**
     * 对照场景：纯工具调用<strong>不算</strong>空响应，不该触发任何重试。
     *
     * <p>对应 mock 的 {@code empty-tool-call}。这条测试是判定口径的护栏 ——
     * 若将来把「无正文」误当成「空」，此处会立刻失败。
     */
    @Test
    void toolCallOnlyStreamIsNotTreatedAsEmpty() {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        TestOpenAiService service = new TestOpenAiService();

        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            upstreamCallCount.incrementAndGet();
            Flux<DataBuffer> body = Flux.concat(
                    Mono.just(sseData(factory, "{\"id\":\"t-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}")),
                    Mono.just(sseData(factory, "{\"id\":\"t-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")),
                    Mono.just(sseData(factory, "[DONE]")));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        List<String> received = service
                .exposeChatCompletionStream(newRequest(), "model-a", provider(), "req-tool-1")
                .collectList().block(Duration.ofSeconds(20));

        // 只调用一次：没有被判空重试。
        assertThat(upstreamCallCount.get()).isEqualTo(1);
        assertThat(received).isNotNull().anyMatch(chunk -> chunk.contains("get_weather"));
    }

    /**
     * 纯思考链（仅 reasoning 兼容字段）不算空 —— gate 工作在清洗之前，
     * 因此必须认得 {@code thinking} 这类未统一的别名。
     */
    @Test
    void reasoningAliasOnlyStreamIsNotTreatedAsEmpty() {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        TestOpenAiService service = new TestOpenAiService();

        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            upstreamCallCount.incrementAndGet();
            Flux<DataBuffer> body = Flux.concat(
                    // 用别名 thinking 而非 reasoning_content：gate 在清洗前，别名也得认。
                    Mono.just(sseData(factory, "{\"id\":\"r-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"thinking\":\"pondering\"},\"finish_reason\":null}]}")),
                    Mono.just(sseData(factory, "{\"id\":\"r-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}")),
                    Mono.just(sseData(factory, "[DONE]")));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        List<String> received = service
                .exposeChatCompletionStream(newRequest(), "model-a", provider(), "req-reasoning-1")
                .collectList().block(Duration.ofSeconds(60));

        assertThat(upstreamCallCount.get()).isEqualTo(1);
        // 无正文时 reasoning fallback 会把思考内容转成正文下发。
        assertThat(received).isNotNull().anyMatch(chunk -> chunk.contains("pondering"));
    }

    /**
     * 空响应与异常失败<strong>共用同一份 5 次预算</strong>：混合失败序列不该叠加成两套额度。
     *
     * <p>序列：空响应 → 500 → 空响应 → 成功。共 4 次上游调用，其中前 3 次消耗预算。
     * 若两类失败各有独立计数，次数会与此不符。
     */
    @Test
    void emptyResponseSharesRetryBudgetWithExceptionFailures() {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        TestOpenAiService service = new TestOpenAiService();

        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            int attempt = upstreamCallCount.incrementAndGet();
            if (attempt == 2) {
                // 第 2 轮：可重试的 500，与空响应共用预算。
                return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":\"boom\"}").build());
            }
            Flux<DataBuffer> body = attempt <= 3
                    // 第 1、3 轮：空响应。
                    ? Mono.just(sseData(factory, "[DONE]")).flux()
                    // 第 4 轮：成功。
                    : Flux.concat(
                            Mono.just(sseData(factory, "{\"id\":\"ok\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"final\"},\"finish_reason\":null}]}")),
                            Mono.just(sseData(factory, "[DONE]")));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        List<String> received = service
                .exposeChatCompletionStream(newRequest(), "model-a", provider(), "req-shared-budget-1")
                .collectList().block(Duration.ofSeconds(60));

        assertThat(upstreamCallCount.get()).isEqualTo(4);
        assertThat(received).isNotNull().anyMatch(chunk -> chunk.contains("final"));
    }

    /**
     * 空响应重试耗尽后，把最后一轮被拦下的帧原样放行给下游，而不是抛错。
     *
     * <p>行为与其他失败的「耗尽后透传最后一次响应」一致：至少让下游看到上游真实返回了什么。
     * 上游共 6 次调用（首次 + 5 次重试预算）。
     */
    @Test
    void exhaustedEmptyResponseRetriesPassLastRoundFramesDownstream() {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        TestOpenAiService service = new TestOpenAiService();

        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            upstreamCallCount.incrementAndGet();
            // 每轮都是空响应：role + [DONE]，永不恢复。
            Flux<DataBuffer> body = Flux.concat(
                    Mono.just(sseData(factory, "{\"id\":\"x-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}")),
                    Mono.just(sseData(factory, "[DONE]")));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        List<String> received = service
                .exposeChatCompletionStream(newRequest(), "model-a", provider(), "req-exhausted-1")
                .collectList().block(Duration.ofSeconds(180));

        // 首次 + 5 次重试预算 = 6 次上游调用。
        assertThat(upstreamCallCount.get()).isEqualTo(6);
        // 没有抛错：最后一轮的帧被原样放行。
        assertThat(received).isNotNull().isNotEmpty();
        assertThat(received).anyMatch(chunk -> chunk.contains("\"role\":\"assistant\"") || chunk.contains("[DONE]"));
    }

    // ── 可配置重试次数 ──────────────────────────────────────────────────────

    /**
     * 配置为 0 时完全不重试：首次失败即透传，上游只被调用一次。
     *
     * <p>与「不挂 retryWhen」不完全等价 —— filter / doBeforeRetry 仍在链上，
     * 只是永不触发重订阅。这条测试锁的是对外可观测的行为。
     */
    @Test
    void zeroConfiguredAttemptsDisablesRetryEntirely() {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        TestOpenAiService service = new TestOpenAiService();
        service.setRetryPolicyService(fixedRetryPolicy(0));

        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            upstreamCallCount.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"error\":\"boom\"}").build());
        }));

        Throwable error = catchThrowable(() -> service
                .exposeChatCompletionStream(newRequest(), "model-a", provider(), "req-zero-retry")
                .collectList().block(Duration.ofSeconds(20)));

        assertThat(upstreamCallCount.get()).isEqualTo(1);
        assertThat(error).isNotNull();
    }

    /** 配置为 2 时预算随之收窄：首次 + 2 次重试 = 3 次上游调用后放弃。 */
    @Test
    void configuredAttemptsBoundTheRetryBudget() {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        TestOpenAiService service = new TestOpenAiService();
        service.setRetryPolicyService(fixedRetryPolicy(2));

        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            upstreamCallCount.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"error\":\"boom\"}").build());
        }));

        catchThrowable(() -> service
                .exposeChatCompletionStream(newRequest(), "model-a", provider(), "req-two-retry")
                .collectList().block(Duration.ofSeconds(30)));

        assertThat(upstreamCallCount.get()).isEqualTo(3);
    }

    /**
     * 空响应兜底同样受配置约束 —— 两类失败共用一份预算，配置改小对两者同时生效。
     *
     * <p>配置 1 次：首轮空响应 + 1 次重试仍空 = 2 次调用，随后耗尽放行。
     */
    @Test
    void configuredAttemptsAlsoBoundEmptyResponseFallback() {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        TestOpenAiService service = new TestOpenAiService();
        service.setRetryPolicyService(fixedRetryPolicy(1));

        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            upstreamCallCount.incrementAndGet();
            Flux<DataBuffer> body = Mono.just(sseData(factory, "[DONE]")).flux();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        List<String> received = service
                .exposeChatCompletionStream(newRequest(), "model-a", provider(), "req-empty-budget-1")
                .collectList().block(Duration.ofSeconds(30));

        // 首次 + 1 次重试 = 2 次，而非默认的 6 次。
        assertThat(upstreamCallCount.get()).isEqualTo(2);
        // 耗尽后放行，不抛错。
        assertThat(received).isNotNull();
    }

    /** 未注入策略服务时（纯单元测试场景）回退默认 5 次：首次 + 5 = 6 次调用。 */
    @Test
    void missingRetryPolicyServiceFallsBackToDefaultBudget() {
        AtomicInteger upstreamCallCount = new AtomicInteger(0);
        TestOpenAiService service = new TestOpenAiService();
        // 刻意不调用 setRetryPolicyService。

        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            upstreamCallCount.incrementAndGet();
            Flux<DataBuffer> body = Mono.just(sseData(factory, "[DONE]")).flux();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        service.exposeChatCompletionStream(newRequest(), "model-a", provider(), "req-default-budget")
                .collectList().block(Duration.ofSeconds(180));

        assertThat(upstreamCallCount.get()).isEqualTo(6);
    }

    /** 构造一个固定返回指定次数的策略服务，避免测试触库。 */
    private static RetryPolicyService fixedRetryPolicy(int maxAttempts) {
        return new RetryPolicyService(null) {
            @Override
            public int getMaxAttempts() {
                return maxAttempts;
            }
        };
    }

    /** 构造一个最小请求体。 */
    private static Map<String, Object> newRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");
        return request;
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
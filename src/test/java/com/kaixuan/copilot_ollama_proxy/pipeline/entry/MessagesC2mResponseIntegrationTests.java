package com.kaixuan.copilot_ollama_proxy.pipeline.entry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.api.shared.UsageAccounting;
import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.observability.record.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.observability.record.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.observability.record.ApiUsageDailyService;
import com.kaixuan.copilot_ollama_proxy.pipeline.AfterSend;
import com.kaixuan.copilot_ollama_proxy.pipeline.BeforeSend;
import com.kaixuan.copilot_ollama_proxy.pipeline.RequestPipeline;
import com.kaixuan.copilot_ollama_proxy.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.pipeline.after.attempt.UpstreamRetryPolicy;
import com.kaixuan.copilot_ollama_proxy.pipeline.after.send.chat.GenericOpenAiChatService;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.dispatch.ProtocolDispatchManager;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.OutboundRequestAssembler;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.OutboundRequestStageRegistry;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.chat.ChatOutboundStage;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.RequestBodyAssembler;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ResponseTranslationException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.ChatToMessagesResponseTranslator;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.MessagesToChatRequestTranslator;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.RequestProtocolTranslator;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.TranslatorRegistry;
import com.kaixuan.copilot_ollama_proxy.protocol.ChunkLogPayload;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.testing.PipelineContexts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 真实主干与 Chat 执行器，仅上游 HTTP 和落库端口打桩；不启动服务、不访问数据库。 */
class MessagesC2mResponseIntegrationTests {

    private static final String REQUESTED_MODEL = "[fixture] upstream-model";
    private static final String CONTENT = """
            {"id":"chatcmpl_native","model":"upstream-returned","choices":[{"delta":{"content":"答案"}}]}
            """;
    private static final String FINISH = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}";
    private static final String USAGE_OBJECT = """
            {"prompt_tokens":100,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":40,
             "cache_write_tokens":20},"completion_tokens_details":{"reasoning_tokens":3},"credit":0.25}
            """;
    private static final String USAGE_EVENT = "{\"choices\":[],\"usage\":" + USAGE_OBJECT + "}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatToMessagesResponseTranslator translator = new ChatToMessagesResponseTranslator(mapper);
    private final ProviderRuntimeConfiguration provider = new ProviderRuntimeConfiguration("fixture", "https://example.invalid",
            "", List.of(), "[]", "{\"version\":2,\"groups\":[]}", "[\"CHAT\"]", "");

    private TestChatService chat;
    private MessagesService messages;
    private ApiCallLogService logs;
    private ApiCallUsageService usages;
    private RetryPolicyService retries;
    private AtomicInteger attempts;

    @BeforeEach
    void setUp() {
        chat = new TestChatService(mapper);
        logs = mock(ApiCallLogService.class);
        usages = mock(ApiCallUsageService.class);
        retries = mock(RetryPolicyService.class);
        when(retries.getMaxAttempts()).thenReturn(0);
        chat.setRetryPolicyService(retries);
        chat.setApiCallLog(logs);
        chat.setApiCallUsage(usages);
        // Stub 协议感知重载；Mockito 不会自动执行接口 default 方法。
        when(logs.saveStream(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyInt(),
                any(ChunkLogPayload.class), anyLong())).thenReturn(41L);
        when(logs.saveNonStream(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyInt(),
                any(), anyLong())).thenReturn(42L);
        ProviderRouteResolver routes = mock(ProviderRouteResolver.class);
        when(routes.resolve(any())).thenReturn(new ResolvedProviderRoute(provider, "upstream-model", REQUESTED_MODEL));
        List<RequestProtocolTranslator> requests = List.of(new MessagesToChatRequestTranslator(mapper));
        ProviderRequestHeaderService headers = new ProviderRequestHeaderService(mapper);
        messages = new MessagesService(new RequestPipeline(new BeforeSend(routes, new ProtocolDispatchManager(requests),
                new TranslatorRegistry(requests, List.of(translator)),
                new RequestBodyAssembler(PipelineContexts.registryWithAllBodyStages(mapper), new RequestBodyRuleEngine(mapper)),
                new OutboundRequestAssembler(headers, new OutboundRequestStageRegistry(List.of(new ChatOutboundStage())), mapper)),
                new AfterSend(PipelineContexts.executorRegistry(chat))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void extensionRolesReachSerializedChatRequestAndReturnMessages(boolean stream) throws Exception {
        AtomicReference<String> sentBody = new AtomicReference<>();
        upstreamCapturingBody(index -> stream ? sse(List.of(CONTENT, FINISH, USAGE_EVENT, "[DONE]"))
                : json("{\"id\":\"chatcmpl_native\",\"model\":\"upstream-returned\",\"choices\":[{\"message\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":" + USAGE_OBJECT + "}"), sentBody);
        Map<String, Object> source = Map.of("model", REQUESTED_MODEL, "stream", stream,
                "system", "top-level instruction", "messages", List.of(
                        Map.of("role", "user", "content", "hi"),
                        Map.of("role", "system", "content", "embedded instruction"),
                        Map.of("role", "developer", "content", "operator instruction"),
                        Map.of("role", "narrator", "content", List.of(Map.of("type", "text", "text", "context")))));
        JsonNode original = mapper.valueToTree(source);

        Flux<UpstreamEvent> response = stream
                ? messages.messagesStream(source, REQUESTED_MODEL, HttpHeaders.EMPTY, "req-roles-stream")
                : messages.messages(source, REQUESTED_MODEL, HttpHeaders.EMPTY, "req-roles-single").flux();
        List<UpstreamEvent> output = response.collectList().block(Duration.ofSeconds(3));

        assertThat(attempts.get()).isEqualTo(1);
        assertThat(sentBody.get()).isNotNull();
        JsonNode sent = mapper.readTree(sentBody.get());
        assertThat(sent.path("messages")).isEqualTo(mapper.valueToTree(List.of(
                Map.of("role", "system", "content", "top-level instruction"),
                Map.of("role", "user", "content", "hi"),
                Map.of("role", "system", "content", "embedded instruction"),
                Map.of("role", "developer", "content", "operator instruction"),
                Map.of("role", "narrator", "content", "context"))));
        assertThat(sent.path("stream").asBoolean()).isEqualTo(stream);
        assertThat(sent.has("system")).isFalse();
        JsonNode currentSource = mapper.valueToTree(source);
        assertThat(currentSource).isEqualTo(original);
        assertThat(output).isNotEmpty();
        assertThat(mapper.readTree(output.getFirst().data()).path("type").asText())
                .isEqualTo(stream ? "message_start" : "message");
        assertThat(output.getLast().usage()).isEqualTo(new UsageTokens(100, 7, 40));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void upstreamRoleRejectionRemainsHttpFailureInsteadOfLocalTranslationFailure(boolean stream) throws Exception {
        String errorBody = "{\"error\":{\"message\":\"invalid role narrator\",\"type\":\"invalid_request_error\"}}";
        AtomicReference<String> sentBody = new AtomicReference<>();
        upstreamCapturingBody(index -> ClientResponse.create(HttpStatus.BAD_REQUEST)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(errorBody).build(), sentBody);
        Map<String, Object> source = Map.of("model", REQUESTED_MODEL, "stream", stream,
                "messages", List.of(Map.of("role", "narrator", "content", "hi")));

        Flux<UpstreamEvent> response = stream
                ? messages.messagesStream(source, REQUESTED_MODEL, HttpHeaders.EMPTY, "req-role-rejected-stream")
                : messages.messages(source, REQUESTED_MODEL, HttpHeaders.EMPTY, "req-role-rejected-single").flux();
        assertThatThrownBy(() -> response.collectList().block(Duration.ofSeconds(3)))
                .satisfies(error -> {
                    WebClientResponseException upstreamError = UpstreamRetryPolicy.findWebResponseException(error);
                    assertThat(upstreamError).isNotNull();
                    assertThat(upstreamError.getStatusCode().value()).isEqualTo(400);
                    assertThat(upstreamError.getResponseBodyAsString()).isEqualTo(errorBody);
                });

        // fixture 重试预算为 0；不改变生产既有的 HTTP 400 重试策略。
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(mapper.readTree(sentBody.get()).path("messages").get(0).path("role").asText()).isEqualTo("narrator");
        verify(usages, never()).save(any(), anyString(), anyString(), anyBoolean(), any(), any(), any());
    }

    @Test
    void streamWiringProducesMessagesLogViewsButPersistsNativeUsageAndRaw() throws Exception {
        List<String> source = List.of(CONTENT, FINISH, USAGE_EVENT, "[DONE]");
        upstream(index -> sse(source));

        List<UpstreamEvent> output = stream();

        assertThat(attempts.get()).isEqualTo(1);
        assertThat(chat.ctx.shouldApplyEmptyResponseGate()).isTrue();
        assertThat(chat.ctx.bodyProtocol()).isEqualTo(WireProtocol.CHAT);
        assertThat(chat.ctx.body()).containsEntry("model", "upstream-model");
        assertThat(chat.ctx.body().get("stream_options")).isEqualTo(Map.of("include_usage", true));
        assertThat(chat.ctx.responseTranslator()).isSameAs(translator);
        JsonNode start = mapper.readTree(output.getFirst().data()).path("message");
        assertThat(start.path("id").asText()).isEqualTo("chatcmpl_native");
        assertThat(start.path("model").asText()).isEqualTo("upstream-returned");
        assertThat(output.getLast().isTerminal()).isTrue();

        ArgumentCaptor<ChunkLogPayload> payload = ArgumentCaptor.forClass(ChunkLogPayload.class);
        verify(logs).saveStream(eq("fixture"), eq("upstream-model"), eq("MESSAGES"), eq("CHAT"), any(), any(), any(),
                eq(200), payload.capture(), anyLong());
        assertThat(payload.getValue().hasUpstreamView()).isTrue();
        assertThat(payload.getValue().upstream()).hasSize(source.size()).anyMatch(frame -> frame.contains("\"prompt_tokens\":100"));
        assertThat(payload.getValue().translated()).isEqualTo(output.stream().map(UpstreamEvent::data).toList());
        assertThat(payload.getValue().frameCounts()).containsExactly(3, 1, 2, 0);

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UsageTokens> nativeUsage = ArgumentCaptor.forClass(UsageTokens.class);
        verify(usages).save(eq(41L), eq("fixture"), eq("upstream-model"), eq(true), raw.capture(), nativeUsage.capture(), any());
        assertThat(mapper.readTree(raw.getValue())).isEqualTo(mapper.readTree(USAGE_OBJECT));
        assertThat(nativeUsage.getValue()).isEqualTo(new UsageTokens(100, 7, 40));
        assertThat(output.getLast().usage()).isSameAs(nativeUsage.getValue());
        JsonNode delta = mapper.readTree(output.get(output.size() - 2).data());
        assertThat(delta.path("usage").path("input_tokens").asInt()).isEqualTo(40);
        assertThat(delta.path("usage").path("cache_creation_input_tokens").asInt()).isEqualTo(20);

        ApiUsageDailyService daily = mock(ApiUsageDailyService.class);
        AtomicReference<UsageTokens> accounting = new AtomicReference<>();
        output.forEach(event -> UsageAccounting.accumulate(event, accounting));
        UsageAccounting.recordStream(daily, accounting);
        verify(daily).record(100, 7);
    }

    @Test
    void nonStreamRecordsNativeBodyAndUsageBeforeResponseConversion() throws Exception {
        String body = "{\"id\":\"chatcmpl_native\",\"model\":\"upstream-returned\",\"choices\":[{\"message\":{\"content\":\"答案\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":" + USAGE_OBJECT + "}";
        upstream(index -> json(body));

        UpstreamEvent output = messages.messages(request(false), REQUESTED_MODEL, HttpHeaders.EMPTY, "req-nonstream")
                .block(Duration.ofSeconds(3));

        assertThat(output).isNotNull();
        assertThat(mapper.readTree(output.data()).path("usage").path("input_tokens").asInt()).isEqualTo(40);
        assertThat(output.usage()).isEqualTo(new UsageTokens(100, 7, 40));
        verify(logs).saveNonStream(eq("fixture"), eq("upstream-model"), eq("MESSAGES"), eq("CHAT"), any(), any(), any(),
                eq(200), eq(body), anyLong());
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UsageTokens> tokens = ArgumentCaptor.forClass(UsageTokens.class);
        verify(usages).save(eq(42L), eq("fixture"), eq("upstream-model"), eq(false), raw.capture(), tokens.capture(), isNull());
        assertThat(mapper.readTree(raw.getValue())).isEqualTo(mapper.readTree(USAGE_OBJECT));
        assertThat(tokens.getValue()).isSameAs(output.usage());
    }

    @Test
        void nativeEmptyStreamRetriesBeforeTranslatorAndDoesNotLeakFirstAttemptFrames() throws InterruptedException {
                CountDownLatch recorded = new CountDownLatch(2);
                doAnswer(invocation -> {
                        recorded.countDown();
                        return null;
                }).when(logs).publishCallRecorded();
        when(retries.getMaxAttempts()).thenReturn(1);
        upstream(index -> index == 1 ? sse(List.of("{\"choices\":[],\"usage\":{\"prompt_tokens\":0}}", "[DONE]"))
                : sse(List.of(CONTENT, FINISH, USAGE_EVENT, "[DONE]")));

        List<UpstreamEvent> output = stream();

        // retry 的成功轮可能在 parallel 线程：onComplete 已解锁 block 时 doFinally 仍在收尾。
        assertThat(recorded.await(3, TimeUnit.SECONDS)).as("两轮日志收尾完成后再断言落库次数").isTrue();
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(output.stream().filter(event -> event.data().contains("\"type\":\"message_start\""))).hasSize(1);
        assertThat(output.getFirst().data()).contains("chatcmpl_native");
        verify(logs, times(2)).saveStream(anyString(), anyString(), eq("MESSAGES"), eq("CHAT"), any(), any(), any(),
                anyInt(), any(ChunkLogPayload.class), anyLong());
        verify(usages).save(eq(41L), eq("fixture"), eq("upstream-model"), eq(true), anyString(),
                eq(new UsageTokens(100, 7, 40)), any());
    }

    @Test
    void exhaustedEmptyNonStreamKeepsNativeRetryBudgetThenWrapsEmptyMessage() throws Exception {
        when(retries.getMaxAttempts()).thenReturn(1);
        upstream(index -> json("{\"choices\":[]}"));

        UpstreamEvent output = messages.messages(request(false), REQUESTED_MODEL, HttpHeaders.EMPTY, "req-empty")
                .block(Duration.ofSeconds(3));

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(output).isNotNull();
        JsonNode body = mapper.readTree(output.data());
        assertThat(body.path("stop_reason").asText()).isEqualTo("end_turn");
        assertThat(body.path("content").get(0).path("text").asText()).isEmpty();
        assertThat(output.usage()).isNull();
        verify(logs, times(2)).saveNonStream(anyString(), anyString(), eq("MESSAGES"), eq("CHAT"), any(), any(), any(),
                eq(200), eq("{\"choices\":[]}"), anyLong());
        verify(usages, never()).save(any(), anyString(), anyString(), anyBoolean(), any(), any(), any());
    }

    @Test
    void nonStreamReasoningAliasesReuseChatNormalizationBeforeMessagesMapping() throws Exception {
        upstream(index -> json("""
                {"id":"chatcmpl_native","model":"upstream-returned","choices":[{"message":{"reasoning":"想完了", "content":null}}]}
                """));

        UpstreamEvent output = messages.messages(request(false), REQUESTED_MODEL, HttpHeaders.EMPTY, "req-thinking")
                .block(Duration.ofSeconds(3));

        assertThat(output).isNotNull();
        JsonNode content = mapper.readTree(output.data()).path("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).path("thinking").asText()).isEqualTo("想完了");
        assertThat(content.get(1).path("text").asText()).isEqualTo("想完了");
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void nativeThinkingToolRoundDoesNotAcquireSyntheticTextBeforeTranslation() throws Exception {
        upstream(index -> json("""
                {"id":"chatcmpl_native","model":"upstream-returned","choices":[{"message":{
                 "reasoning":"调工具","content":null,"tool_calls":[{"id":"call_1", "type":"function",
                 "function":{"name":"lookup", "arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
                """));

        UpstreamEvent output = messages.messages(request(false), REQUESTED_MODEL, HttpHeaders.EMPTY, "req-thinking-tool")
                .block(Duration.ofSeconds(3));

        assertThat(output).isNotNull();
        JsonNode content = mapper.readTree(output.data()).path("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).path("type").asText()).isEqualTo("thinking");
        assertThat(content.get(1).path("type").asText()).isEqualTo("tool_use");
    }

    @Test
    void nativeContentArrayIsNotReplacedWithReasoningFallback() throws Exception {
        upstream(index -> json("""
                {"id":"chatcmpl_native","model":"upstream-returned","choices":[{"message":{
                 "reasoning":"推理","content":[{"type":"text", "text":"原始正文"}]},"finish_reason":"stop"}]}
                """));

        UpstreamEvent output = messages.messages(request(false), REQUESTED_MODEL, HttpHeaders.EMPTY, "req-array")
                .block(Duration.ofSeconds(3));

        assertThat(output).isNotNull();
        JsonNode content = mapper.readTree(output.data()).path("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).path("thinking").asText()).isEqualTo("推理");
        assertThat(content.get(1).path("text").asText()).isEqualTo("原始正文");
    }

    @Test
    void streamWithoutUsageDoesNotInventUsageRowDespiteMessagesPlaceholders() {
        upstream(index -> sse(List.of(CONTENT, FINISH, "[DONE]")));

        List<UpstreamEvent> output = stream();

        assertThat(output).allMatch(event -> event.usage() == null);
        assertThat(output).anyMatch(event -> event.data().contains("\"input_tokens\":0"));
        verify(usages, never()).save(any(), anyString(), anyString(), anyBoolean(), any(), any(), any());
    }

    @Test
    void errorHttpRoundStillUsesLogRewriterAndPreservesOriginalError() {
        upstream(index -> ClientResponse.create(HttpStatus.UNAUTHORIZED).header(HttpHeaders.CONTENT_TYPE,
                MediaType.APPLICATION_JSON_VALUE).body("{\"error\":\"unauthorized\"}").build());

        assertThatThrownBy(this::stream).isInstanceOf(WebClientResponseException.class);

        assertThat(attempts.get()).isEqualTo(1);
        ArgumentCaptor<ChunkLogPayload> payload = ArgumentCaptor.forClass(ChunkLogPayload.class);
        verify(logs).saveStreamWithError(eq("fixture"), eq("upstream-model"), eq("MESSAGES"), eq("CHAT"),
                any(), any(), any(), eq(401), payload.capture(), any(), eq(401), eq("{\"error\":\"unauthorized\"}"), anyLong());
        assertThat(payload.getValue().hasUpstreamView()).isTrue();
        assertThat(payload.getValue().upstream()).isEmpty();
        verify(usages, never()).save(any(), anyString(), anyString(), anyBoolean(), any(), any(), any());
    }

    @Test
    void exhaustedEmptyStreamWritesTranslatedPayloadButDoesNotWriteUsageRow() {
        upstream(index -> sse(List.of("{\"choices\":[]}", "[DONE]")));

        List<UpstreamEvent> output = stream();

        assertThat(attempts.get()).isEqualTo(1);
        assertThat(output.getLast().isTerminal()).isTrue();
        ArgumentCaptor<ChunkLogPayload> payload = ArgumentCaptor.forClass(ChunkLogPayload.class);
        verify(logs).saveStream(eq("fixture"), eq("upstream-model"), eq("MESSAGES"), eq("CHAT"), any(), any(), any(),
                eq(200), payload.capture(), anyLong());
        assertThat(payload.getValue().upstream()).containsExactly("{\"choices\":[]}", "[DONE]");
        assertThat(payload.getValue().translated()).hasSize(3);
        assertThat(payload.getValue().frameCounts()).containsExactly(1, 2);
        verify(usages, never()).save(any(), anyString(), anyString(), anyBoolean(), any(), any(), any());
    }

    @Test
    void networkFailureLogUsesRewriterButDoesNotEmitSuccessfulMessageStop() {
        attempts = new AtomicInteger();
        chat.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            attempts.incrementAndGet();
            return Mono.error(new IllegalStateException("connection unavailable"));
        }));

        assertThatThrownBy(this::stream).hasMessageContaining("connection unavailable");

        assertThat(attempts.get()).isEqualTo(1);
        ArgumentCaptor<ChunkLogPayload> payload = ArgumentCaptor.forClass(ChunkLogPayload.class);
        verify(logs).saveStream(eq("fixture"), eq("upstream-model"), eq("MESSAGES"), eq("CHAT"), any(), any(), any(),
                eq(-1), payload.capture(), anyLong());
        assertThat(payload.getValue().hasUpstreamView()).isTrue();
        assertThat(payload.getValue().upstream()).isEmpty();
        verify(usages, never()).save(any(), anyString(), anyString(), anyBoolean(), any(), any(), any());
    }

    @Test
    void malformedUpstreamResponseFailsAfterNativeAccountingWithoutAutomaticRetry() {
        upstream(index -> json("{\"choices\":[{\"unexpected\":true}]}"));

        assertThatThrownBy(() -> messages.messages(request(false), REQUESTED_MODEL, HttpHeaders.EMPTY, "req-malformed")
                .block(Duration.ofSeconds(3))).isInstanceOf(ResponseTranslationException.class);
        assertThat(attempts.get()).isEqualTo(1);
    }

    private List<UpstreamEvent> stream() {
        return messages.messagesStream(request(true), REQUESTED_MODEL, HttpHeaders.EMPTY, "req-stream")
                .collectList().block(Duration.ofSeconds(3));
    }

    private Map<String, Object> request(boolean stream) {
        return Map.of("model", REQUESTED_MODEL, "stream", stream, "messages", List.of(Map.of("role", "user", "content", "hi")));
    }

    private void upstream(Function<Integer, ClientResponse> response) {
        attempts = new AtomicInteger();
        chat.setWebClientBuilder(WebClient.builder().exchangeFunction(request ->
                Mono.just(response.apply(attempts.incrementAndGet()))));
    }

        /** 用实际 codec 写到内存 HTTP 请求，不启动网络服务即可检查 bodyValue 最终序列化。 */
        private void upstreamCapturingBody(Function<Integer, ClientResponse> response, AtomicReference<String> sentBody) {
                attempts = new AtomicInteger();
                chat.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
                        MockClientHttpRequest outbound = new MockClientHttpRequest(request.method(), request.url());
                        return request.writeTo(outbound, ExchangeStrategies.withDefaults())
                                        .then(Mono.defer(outbound::getBodyAsString))
                                        .doOnNext(sentBody::set)
                                        .map(body -> response.apply(attempts.incrementAndGet()));
                }));
        }

    private ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body).build();
    }

    private ClientResponse sse(List<String> frames) {
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        Flux<DataBuffer> body = Flux.fromIterable(frames)
                                .map(this::compactSseData)
                                .map(frame -> factory.wrap(("data: " + frame + "\n\n").getBytes(StandardCharsets.UTF_8)));
        return ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                .body(body).build();
    }

    private String compactSseData(String frame) {
        if ("[DONE]".equals(frame)) {
            return frame;
        }
        try {
            return mapper.readTree(frame).toString();
        } catch (Exception exception) {
            throw new AssertionError("SSE fixture 必须为合法单行 JSON", exception);
        }
    }

    private static final class TestChatService extends GenericOpenAiChatService {
        private RequestPipelineContext ctx;

        TestChatService(ObjectMapper mapper) {
            super(mapper, new ProviderRequestHeaderService(mapper), PipelineContexts.registryWithChatChunkStages(mapper),
                    PipelineContexts.contentDetectorRegistry(mapper));
        }

        @Override
        public Flux<UpstreamEvent> invokeStream(RequestPipelineContext ctx, Function<List<String>, ChunkLogPayload> rewriter) {
            this.ctx = ctx;
            return super.invokeStream(ctx, rewriter);
        }

        @Override
        protected Duration retryFirstBackoff() {
            return Duration.ofMillis(1);
        }

        @Override
        protected Duration retryMaxBackoff() {
            return Duration.ofMillis(5);
        }
    }
}
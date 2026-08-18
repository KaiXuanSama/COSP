package com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Anthropic 上游服务的请求构造、空响应兜底与重试。
 *
 * <p>请求构造那批用例走<strong>真实 {@code HttpServer}</strong>：
 * {@code ClientRequest.body()} 是个 {@code BodyInserter}，读不出已序列化的内容，
 * 而请求体形态正是要验的东西。顺带也真实验证了出站 URL 路径。
 *
 * <p>重试与空响应那批用 {@code ExchangeFunction} stub —— 那些只需控制响应，
 * 不关心请求体，stub 更轻也更好构造病态形状。
 *
 * <p>退避时长由测试子类覆盖成毫秒级，否则一个「重试到耗尽」的用例要真实等 62 秒。
 */
class GenericAnthropicChatServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer upstream;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name, String.join(", ", values)));
            capturedHeaders.set(headers);
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] response = okBody().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        upstream.start();
    }

    @AfterEach
    void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    // ==================== 出站路径 ====================

    /**
     * Base URL 自带的路径被<strong>完整保留</strong>，{@code /messages} 接在其后。
     *
     * <p>数据库里已有供应商的 Base URL 通常是 {@code .../v1}，因而实际请求为
     * {@code /v1/messages} —— 与 Anthropic 官方及 tokenrhythm 的实测端点一致。
     * 早先的乐观规则会先剥掉尾部 {@code /v1}，对 tokenrhythm 得到站点根路径的 405。
     *
     * <p>这条用例的价值在于：将来改成按供应商读端点配置时，它会明确地失败并提醒改断言。
     * 见服务里 {@code normalizeAnthropicBaseUrl} 的 TODO。
     */
    @Test
    void baseUrlPathIsPreservedAndMessagesPathIsAppended() {
        realService().exposeMessages(newRequest(), routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        assertThat(capturedPath.get()).isEqualTo("/v1/messages");
    }

    /** Base URL 本就是站点根路径时，端点落在 {@code /messages}。 */
    @Test
    void baseUrlWithoutVersionPrefixHitsRootMessagesPath() {
        realService().exposeMessages(newRequest(), routeTo(baseUrlRoot())).block(Duration.ofSeconds(10));

        assertThat(capturedPath.get()).isEqualTo("/messages");
    }

    // ==================== 请求头 ====================

    /** {@code anthropic-version} 是必需头，缺失时官方 API 返回 400。 */
    @Test
    void anthropicVersionHeaderIsAlwaysSent() {
        realService().exposeMessages(newRequest(), routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        assertThat(capturedHeaders.get()).containsEntry("Anthropic-version", "2023-06-01");
    }

    /**
     * 同时发送两种认证头形态。
     *
     * <p>官方用 {@code x-api-key}，多数 OpenAI 兼容中转站沿用
     * {@code Authorization: Bearer} —— 本阶段两个都给以兼容两类上游，见服务里的 TODO。
     */
    @Test
    void bothAuthenticationHeaderStylesAreSent() {
        realService().exposeMessages(newRequest(), routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        assertThat(capturedHeaders.get()).containsEntry("Authorization", "Bearer test-key");
        assertThat(capturedHeaders.get()).containsEntry("X-api-key", "test-key");
    }

    // ==================== 请求体构造 ====================

    /** {@code system} 必须提到顶层 —— Anthropic 不接受 OpenAI 那种形态。 */
    @Test
    void systemMessageIsLiftedToTopLevelField() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "claude-x");
        request.put("messages", List.of(
                Map.of("role", "system", "content", "你是助手"),
                Map.of("role", "user", "content", "你好")));

        realService().exposeMessages(request, routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        JsonNode body = objectMapper.readTree(capturedBody.get());
        assertThat(body.path("system").asText()).isEqualTo("你是助手");
        assertThat(body.path("messages")).hasSize(1);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
    }

    /** 多条 system 按顺序拼接，不能只留一条。 */
    @Test
    void multipleSystemMessagesAreConcatenated() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "system", "content", "第一条"),
                Map.of("role", "user", "content", "hi"),
                Map.of("role", "system", "content", "第二条")));

        realService().exposeMessages(request, routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        JsonNode body = objectMapper.readTree(capturedBody.get());
        assertThat(body.path("system").asText()).isEqualTo("第一条\n\n第二条");
        assertThat(body.path("messages")).hasSize(1);
    }

    /** OpenAI 多模态那种 content 数组，取其中 text 片段。 */
    @Test
    void systemContentArrayIsFlattenedToText() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                Map.of("role", "system", "content", List.of(
                        Map.of("type", "text", "text", "指令一"),
                        Map.of("type", "text", "text", "指令二"))),
                Map.of("role", "user", "content", "hi")));

        realService().exposeMessages(request, routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        assertThat(objectMapper.readTree(capturedBody.get()).path("system").asText())
                .isEqualTo("指令一\n指令二");
    }

    /** 顶层已有 system 时保留它，messages 里的追加在后 —— 丢任何一份都改变语义。 */
    @Test
    void existingTopLevelSystemIsKeptAndAppended() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("system", "原有指令");
        request.put("messages", List.of(
                Map.of("role", "system", "content", "追加指令"),
                Map.of("role", "user", "content", "hi")));

        realService().exposeMessages(request, routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        assertThat(objectMapper.readTree(capturedBody.get()).path("system").asText())
                .isEqualTo("原有指令\n\n追加指令");
    }

    /** {@code max_tokens} 必填 —— 下游没带时要补，否则上游 400。 */
    @Test
    void maxTokensIsAlwaysPresent() throws Exception {
        realService().exposeMessages(newRequest(), routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        assertThat(objectMapper.readTree(capturedBody.get()).path("max_tokens").asInt())
                .isGreaterThan(0);
    }

    /** 下游显式给了就用它，不被兜底值覆盖。 */
    @Test
    void explicitMaxTokensIsPreserved() throws Exception {
        Map<String, Object> request = newRequest();
        request.put("max_tokens", 1234);

        realService().exposeMessages(request, routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        assertThat(objectMapper.readTree(capturedBody.get()).path("max_tokens").asInt()).isEqualTo(1234);
    }

    /** OpenAI 的同义字段被识别并改名。 */
    @Test
    void openAiMaxCompletionTokensAliasIsAccepted() throws Exception {
        Map<String, Object> request = newRequest();
        request.put("max_completion_tokens", 555);

        realService().exposeMessages(request, routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        JsonNode body = objectMapper.readTree(capturedBody.get());
        assertThat(body.path("max_tokens").asInt()).isEqualTo(555);
        assertThat(body.has("max_completion_tokens")).isFalse();
    }

    /** {@code reasoning_effort} 是 OpenAI 概念，必须剥掉否则上游 400。 */
    @Test
    void openAiReasoningEffortIsStripped() throws Exception {
        Map<String, Object> request = newRequest();
        request.put("reasoning_effort", "high");

        realService().exposeMessages(request, routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        assertThat(objectMapper.readTree(capturedBody.get()).has("reasoning_effort")).isFalse();
    }

    /** 模型名的供应商前缀要剥掉，上游只认真实模型名。 */
    @Test
    void providerPrefixIsStrippedFromModelName() throws Exception {
        Map<String, Object> request = newRequest();
        request.put("model", "[anthro] claude-opus-4");

        realService().exposeMessages(request, routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        assertThat(objectMapper.readTree(capturedBody.get()).path("model").asText())
                .isEqualTo("claude-opus-4");
    }

    /** {@code stream} 标志按调用入口设置，不取决于下游传了什么。 */
    @Test
    void streamFlagIsSetByEntryPointNotByRequest() throws Exception {
        Map<String, Object> request = newRequest();
        request.put("stream", true);   // 下游误传，非流式入口应覆盖成 false

        realService().exposeMessages(request, routeTo(baseUrlWithV1())).block(Duration.ofSeconds(10));

        assertThat(objectMapper.readTree(capturedBody.get()).path("stream").asBoolean()).isFalse();
    }

    // ==================== 空响应兜底 ====================

    /** 非流式空响应触发重试，第二轮正常内容透传。 */
    @Test
    void nonStreamEmptyResponseTriggersRetry() {
        AtomicInteger calls = new AtomicInteger(0);
        TestService service = stubService(request -> {
            String body = calls.incrementAndGet() == 1 ? "{\"id\":\"msg_1\",\"content\":[]}" : okBody();
            return jsonResponse(HttpStatus.OK, body);
        });

        String received = service.exposeMessages(newRequest(), routeTo(baseUrlWithV1()))
                .block(Duration.ofSeconds(20));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(received).contains("hello");
    }

    /** 空 body 同样判空 —— 比「0 事件」更极端，连 JSON 骨架都没有。 */
    @Test
    void nonStreamEmptyBodyTriggersRetry() {
        AtomicInteger calls = new AtomicInteger(0);
        TestService service = stubService(request ->
                jsonResponse(HttpStatus.OK, calls.incrementAndGet() == 1 ? "" : okBody()));

        String received = service.exposeMessages(newRequest(), routeTo(baseUrlWithV1()))
                .block(Duration.ofSeconds(20));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(received).contains("hello");
    }

    /** 耗尽后放行最后一轮而非抛错 —— 与 OpenAI 侧「透传上游真实返回」一致。 */
    @Test
    void nonStreamExhaustedEmptyResponseIsPassedThrough() {
        AtomicInteger calls = new AtomicInteger(0);
        TestService service = stubService(request -> {
            calls.incrementAndGet();
            return jsonResponse(HttpStatus.OK, "{\"id\":\"msg_1\",\"content\":[]}");
        });
        service.setRetryPolicyService(fixedRetryPolicy(2));

        String received = service.exposeMessages(newRequest(), routeTo(baseUrlWithV1()))
                .block(Duration.ofSeconds(20));

        // 首次 + 2 次重试 = 3 次。
        assertThat(calls.get()).isEqualTo(3);
        assertThat(received).contains("\"content\":[]");
    }

    /** 对照组：纯工具调用不该被判空重发。 */
    @Test
    void nonStreamToolUseOnlyIsNotTreatedAsEmpty() {
        AtomicInteger calls = new AtomicInteger(0);
        TestService service = stubService(request -> {
            calls.incrementAndGet();
            return jsonResponse(HttpStatus.OK, """
                    {"id":"msg_1","content":[{"type":"tool_use","id":"t1",\
                    "name":"get_weather","input":{}}],"stop_reason":"tool_use"}""");
        });

        service.exposeMessages(newRequest(), routeTo(baseUrlWithV1())).block(Duration.ofSeconds(20));

        assertThat(calls.get()).isEqualTo(1);
    }

    /** 对照组：纯思考链不该被判空重发。 */
    @Test
    void nonStreamThinkingOnlyIsNotTreatedAsEmpty() {
        AtomicInteger calls = new AtomicInteger(0);
        TestService service = stubService(request -> {
            calls.incrementAndGet();
            return jsonResponse(HttpStatus.OK, """
                    {"id":"msg_1","content":[{"type":"thinking","thinking":"想了想"}],\
                    "stop_reason":"end_turn"}""");
        });

        service.exposeMessages(newRequest(), routeTo(baseUrlWithV1())).block(Duration.ofSeconds(20));

        assertThat(calls.get()).isEqualTo(1);
    }

    // ==================== 流式 ====================

    /** 完整事件序列原样透传，顺序不变。 */
    @Test
    void streamPassesEventsThroughInOrder() {
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        TestService service = stubService(request -> sseResponse(fullEventSequence(factory)));

        List<String> received = service.exposeMessagesStream(newRequest(), routeTo(baseUrlWithV1()))
                .collectList().block(Duration.ofSeconds(20));

        assertThat(received).isNotNull();
        assertThat(received.get(0)).contains("message_start");
        assertThat(received).anyMatch(e -> e.contains("text_delta"));
        assertThat(received.get(received.size() - 1)).contains("message_stop");
    }

    /**
     * 流式空响应（只有控制事件）触发重试。
     *
     * <p>不设 gate 是有意的：Anthropic 客户端是状态机，扣住 {@code message_start}
     * 会让它无法初始化，故这一轮的事件已经流到下游了。
     */
    @Test
    void streamWithoutAnyPayloadTriggersRetry() {
        AtomicInteger calls = new AtomicInteger(0);
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        TestService service = stubService(request -> {
            Flux<DataBuffer> body = calls.incrementAndGet() == 1
                    ? Flux.concat(
                            Mono.just(sse(factory, "{\"type\":\"message_start\",\"message\":{\"id\":\"m1\"}}")),
                            Mono.just(sse(factory, "{\"type\":\"message_stop\"}")))
                    : fullEventSequence(factory);
            return sseResponse(body);
        });

        List<String> received = service.exposeMessagesStream(newRequest(), routeTo(baseUrlWithV1()))
                .collectList().block(Duration.ofSeconds(20));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(received).anyMatch(e -> e.contains("text_delta"));
    }

    /** 零事件同样判空 —— 一个事件都没来，自然没见过载荷。 */
    @Test
    void streamWithZeroEventsTriggersRetry() {
        AtomicInteger calls = new AtomicInteger(0);
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        TestService service = stubService(request ->
                sseResponse(calls.incrementAndGet() == 1 ? Flux.empty() : fullEventSequence(factory)));

        service.exposeMessagesStream(newRequest(), routeTo(baseUrlWithV1()))
                .collectList().block(Duration.ofSeconds(20));

        assertThat(calls.get()).isEqualTo(2);
    }

    /** 对照组：只有思考链的流不该被判空。 */
    @Test
    void streamWithOnlyThinkingIsNotTreatedAsEmpty() {
        AtomicInteger calls = new AtomicInteger(0);
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        TestService service = stubService(request -> {
            calls.incrementAndGet();
            return sseResponse(Flux.concat(
                    Mono.just(sse(factory, "{\"type\":\"message_start\",\"message\":{\"id\":\"m1\"}}")),
                    Mono.just(sse(factory, """
                            {"type":"content_block_delta","index":0,\
                            "delta":{"type":"thinking_delta","thinking":"想"}}""")),
                    Mono.just(sse(factory, "{\"type\":\"message_stop\"}"))));
        });

        service.exposeMessagesStream(newRequest(), routeTo(baseUrlWithV1()))
                .collectList().block(Duration.ofSeconds(20));

        assertThat(calls.get()).isEqualTo(1);
    }

    /**
     * 重试重订阅时轮内状态必须重置。
     *
     * <p>状态在 {@code Flux.defer} 内重置而非声明处初始化：{@code retryWhen} 会重订阅，
     * 若不重置，第二轮会带着第一轮的 {@code sawPayload} 与事件记录 ——
     * 那会让「第一轮有内容但中断、第二轮空」这种组合被误判成正常。
     *
     * <p>第一轮用「有内容 + 流中途断开」而不是简单的空响应：这样第一轮的
     * {@code sawPayload} 为真，若不重置，第二轮即使真空也不会被判空。
     * 中断异常必须包成 {@code WebClientRequestException} —— 裸 {@code IOException}
     * 不在可重试判定范围内（{@code isRetryableFailure} 只认前者，或以 IOException
     * 为 cause 的 {@code WebClientResponseException}），真实的流中断也是这个形状。
     */
    @Test
    void perAttemptStateIsResetOnRetry() {
        AtomicInteger calls = new AtomicInteger(0);
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        TestService service = stubService(request -> {
            // 第一轮有内容但随后网络中断，第二轮正常收尾。
            if (calls.incrementAndGet() == 1) {
                return sseResponse(Flux.concat(
                        Mono.just(sse(factory, """
                                {"type":"content_block_delta","index":0,\
                                "delta":{"type":"text_delta","text":"partial"}}""")),
                        Flux.error(new WebClientRequestException(
                                new IOException("connection reset"), HttpMethod.POST,
                                URI.create("http://localhost/messages"), HttpHeaders.EMPTY))));
            }
            return sseResponse(fullEventSequence(factory));
        });

        List<String> received = service.exposeMessagesStream(newRequest(), routeTo(baseUrlWithV1()))
                .collectList().block(Duration.ofSeconds(20));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(received).isNotNull();
        assertThat(received).anyMatch(e -> e.contains("message_stop"));
    }

    // ==================== 重试口径 ====================

    /** 5xx 可重试 —— 与 OpenAI 侧同一判定。 */
    @Test
    void serverErrorIsRetried() {
        AtomicInteger calls = new AtomicInteger(0);
        TestService service = stubService(request -> {
            calls.incrementAndGet();
            return jsonResponse(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"boom\"}");
        });
        service.setRetryPolicyService(fixedRetryPolicy(2));

        catchThrowable(() -> service.exposeMessages(newRequest(), routeTo(baseUrlWithV1()))
                .block(Duration.ofSeconds(20)));

        assertThat(calls.get()).isEqualTo(3);
    }

    /** 401 不重试 —— 请求内容未变，确定性错误重试结果必然相同。 */
    @Test
    void unauthorizedIsNotRetried() {
        AtomicInteger calls = new AtomicInteger(0);
        TestService service = stubService(request -> {
            calls.incrementAndGet();
            return jsonResponse(HttpStatus.UNAUTHORIZED, "{\"error\":\"unauthorized\"}");
        });

        catchThrowable(() -> service.exposeMessages(newRequest(), routeTo(baseUrlWithV1()))
                .block(Duration.ofSeconds(20)));

        assertThat(calls.get()).isEqualTo(1);
    }

    /**
     * 重试次数取自与 OpenAI 侧共享的同一个配置项。
     *
     * <p>这条断言守的是「重试次数只有一个来源」在跨协议后仍然成立：
     * 实现各写一份，但配置不分叉。若 Anthropic 侧另起了一套配置，此处会失败。
     */
    @Test
    void retryBudgetComesFromSharedConfiguration() {
        AtomicInteger calls = new AtomicInteger(0);
        TestService service = stubService(request -> {
            calls.incrementAndGet();
            return jsonResponse(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"boom\"}");
        });
        service.setRetryPolicyService(fixedRetryPolicy(0));

        catchThrowable(() -> service.exposeMessages(newRequest(), routeTo(baseUrlWithV1()))
                .block(Duration.ofSeconds(20)));

        // 配置 0 表示不重试。
        assertThat(calls.get()).isEqualTo(1);
    }

    // ==================== 辅助 ====================

    /** 打真实 HttpServer 的服务实例，用于验证请求构造与出站路径。 */
    private TestService realService() {
        return new TestService();
    }

    /** 用 ExchangeFunction stub 的服务实例，用于精确控制响应形态。 */
    private TestService stubService(Function<ClientRequest, Mono<ClientResponse>> handler) {
        TestService service = new TestService();
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(handler::apply));
        return service;
    }

    private static Mono<ClientResponse> jsonResponse(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body).build());
    }

    private static Mono<ClientResponse> sseResponse(Flux<DataBuffer> body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                .body(body).build());
    }

    private static String okBody() {
        return """
                {"id":"msg_1","type":"message","role":"assistant",\
                "content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn",\
                "usage":{"input_tokens":10,"output_tokens":2}}""";
    }

    private static Map<String, Object> newRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "claude-x");
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        return request;
    }

    private String baseUrlWithV1() {
        return "http://localhost:" + upstream.getAddress().getPort() + "/v1";
    }

    private String baseUrlRoot() {
        return "http://localhost:" + upstream.getAddress().getPort();
    }

    private static ResolvedProviderRoute routeTo(String baseUrl) {
        return new ResolvedProviderRoute(
                new ProviderRuntimeConfiguration("anthro", baseUrl, "test-key", List.of()),
                "claude-x", "[anthro] claude-x");
    }

    private static RetryPolicyService fixedRetryPolicy(int maxAttempts) {
        return new RetryPolicyService(null) {
            @Override
            public int getMaxAttempts() {
                return maxAttempts;
            }
        };
    }

    /** 一个完整的 Anthropic 流式事件序列。 */
    private static Flux<DataBuffer> fullEventSequence(DefaultDataBufferFactory factory) {
        return Flux.concat(
                Mono.just(sse(factory, """
                        {"type":"message_start","message":{"id":"m1","role":"assistant",\
                        "usage":{"input_tokens":10,"output_tokens":0}}}""")),
                Mono.just(sse(factory, """
                        {"type":"content_block_start","index":0,\
                        "content_block":{"type":"text","text":""}}""")),
                Mono.just(sse(factory, """
                        {"type":"content_block_delta","index":0,\
                        "delta":{"type":"text_delta","text":"hello"}}""")),
                Mono.just(sse(factory, "{\"type\":\"content_block_stop\",\"index\":0}")),
                Mono.just(sse(factory, """
                        {"type":"message_delta","delta":{"stop_reason":"end_turn"},\
                        "usage":{"output_tokens":5}}""")),
                Mono.just(sse(factory, "{\"type\":\"message_stop\"}")));
    }

    private static DataBuffer sse(DefaultDataBufferFactory factory, String json) {
        return factory.wrap(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 测试子类：暴露受保护入口并把退避压到毫秒级。
     *
     * <p>不压缩退避的话，「重试到耗尽」的用例要真实等待 62 秒 —— 而它验证的是
     * 重试次数与判定条件，退避时长本身不是被测行为。
     */
    private static final class TestService extends GenericAnthropicChatService {

        private TestService() {
            super(new ObjectMapper(), new ProviderRequestHeaderService(new ObjectMapper()));
        }

        private Mono<String> exposeMessages(Map<String, Object> request, ResolvedProviderRoute route) {
            return messages(request, route, HttpHeaders.EMPTY, "req-test");
        }

        private Flux<String> exposeMessagesStream(Map<String, Object> request, ResolvedProviderRoute route) {
            return messagesStream(request, route, HttpHeaders.EMPTY, "req-test");
        }

        @Override
        protected Duration retryFirstBackoff() {
            return Duration.ofMillis(5);
        }

        @Override
        protected Duration retryMaxBackoff() {
            return Duration.ofMillis(20);
        }
    }
}

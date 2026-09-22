package com.kaixuan.copilot_ollama_proxy.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic.GenericAnthropicChatService;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.GenericOpenAiChatService;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.GenericResponsesChatService;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ChunkStageRegistry;
import com.kaixuan.copilot_ollama_proxy.provider.stage.chat.ChatChunkNormalizeStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.chat.ChatReasoningFallbackStage;
import com.kaixuan.copilot_ollama_proxy.testing.PipelineContexts;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UpstreamExecutor} 的<strong>协议键</strong>与<strong>委派等价性</strong>。
 *
 * <h2>为何这两件事必须单独测</h2>
 * 3.4d-1 让三个执行器 {@code implements UpstreamExecutor}，新方法只是<strong>委派</strong>旧方法。
 * 委派本身不会出错，但两处会静默错：
 * <ul>
 *   <li><strong>协议键写错</strong> —— {@code protocol()} 返回了别的值。它不报错，
 *       只会在 3.4d-2 查表时「按 Chat 查到 Anthropic 实现」，症状是出站字段名不对；</li>
 *   <li><strong>委派时参数取错</strong> —— 例如 {@code ctx.model()} 取成了别的字段。
 *       编译通过，症状是「模型名变成了别的东西」这类怪事。</li>
 * </ul>
 * 因此这里对每个执行器断言：键正确，且新入口与旧入口<strong>产生同样的出站请求体</strong>。
 *
 * <h2>为何用真实 HttpServer 抓请求体</h2>
 * 与三个执行器各自的测试同一手法（{@code GenericAnthropicChatServiceTests} 等）：
 * 起一个本地 HttpServer 记录收到的请求体，比对「走 invoke」与「走旧方法」两条路
 * 请求体 JSON 逐字相同。这是委派等价性最直接的证据 —— 比比对响应更强
 * （响应可能被 fallback / 清洗抹平差异）。
 *
 * <h2>桩的响应体必须带实质载荷</h2>
 * 否则会被空响应兜底卷入重试，表现为超时而非断言失败（项目已踩过的坑）。
 * 故按协议回一个形态正常、内容非空的响应。
 */
class UpstreamExecutorTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer upstream;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedBody.set(requestBody);
            // 流式请求要回 SSE，否则解码器不启动 → 空响应 → 触发兜底重试（慢且不稳）。
            // 判据取请求体里的 "stream":true —— 那正是各执行器写进去的协议字段。
            boolean streaming = requestBody.replace(" ", "").contains("\"stream\":true");
            String path = exchange.getRequestURI().getPath();
            String body;
            String contentType;
            if (streaming) {
                body = sseBodyFor(path);
                contentType = "text/event-stream";
            } else {
                body = jsonBodyFor(path);
                contentType = "application/json";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();
    }

    /** 按路径挑非流式响应体（带实质载荷，避免被空响应兜底卷入重试）。 */
    private static String jsonBodyFor(String path) {
        if (path.endsWith("/messages")) {
            return anthropicOkBody();
        }
        if (path.endsWith("/responses")) {
            return responsesOkBody();
        }
        return chatOkBody();
    }

    /** 按路径挑流式响应体 —— 每协议一帧实质载荷 + 一个终止标记。 */
    private static String sseBodyFor(String path) {
        if (path.endsWith("/messages")) {
            return "event: content_block_delta\n"
                    + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\n\n"
                    + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        }
        if (path.endsWith("/responses")) {
            return "event: response.output_text.delta\n"
                    + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n\n"
                    + "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\"}}\n\n";
        }
        return "data: {\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n"
                + "data: [DONE]\n\n";
    }

    @AfterEach
    void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    // ==================== 协议键 ====================

    @Test
    @DisplayName("三个执行器各自声明正确的上游协议键")
    void eachExecutorDeclaresItsProtocol() {
        assertThat(openAiService().protocol())
                .as("Chat 执行器必须声明 CHAT —— 键错了会在 3.4d-2 查到别的实现")
                .isEqualTo(WireProtocol.CHAT);
        assertThat(anthropicService().protocol())
                .as("Anthropic 执行器必须声明 MESSAGES")
                .isEqualTo(WireProtocol.MESSAGES);
        assertThat(responsesService().protocol())
                .as("Responses 执行器必须声明 RESPONSES")
                .isEqualTo(WireProtocol.RESPONSES);
    }

    // ==================== 委派等价性：非流式 ====================

    @Test
    @DisplayName("Chat：invoke 与旧 chatCompletion 出站请求体逐字相同")
    void openAiInvokeDelegatesToLegacyEntry() {
        assertThat(captureOpenAiBody(true))
                .as("invoke 只是委派，出站请求体必须与旧方法逐字相同")
                .isEqualTo(captureOpenAiBody(false));
    }

    @Test
    @DisplayName("Anthropic：invoke 与旧 messages 出站请求体逐字相同")
    void anthropicInvokeDelegatesToLegacyEntry() {
        assertThat(captureAnthropicBody(true)).isEqualTo(captureAnthropicBody(false));
    }

    @Test
    @DisplayName("Responses：invoke 与旧 responses 出站请求体逐字相同")
    void responsesInvokeDelegatesToLegacyEntry() {
        assertThat(captureResponsesBody(true)).isEqualTo(captureResponsesBody(false));
    }

    // ==================== 委派等价性：流式 ====================

    @Test
    @DisplayName("Chat：invokeStream 与旧 chatCompletionStream 出站请求体逐字相同")
    void openAiInvokeStreamDelegatesToLegacyEntry() {
        assertThat(captureOpenAiStreamBody(true)).isEqualTo(captureOpenAiStreamBody(false));
    }

    @Test
    @DisplayName("Anthropic：invokeStream 与旧 messagesStream 出站请求体逐字相同")
    void anthropicInvokeStreamDelegatesToLegacyEntry() {
        assertThat(captureAnthropicStreamBody(true)).isEqualTo(captureAnthropicStreamBody(false));
    }

    @Test
    @DisplayName("Responses：invokeStream 与旧 responsesStream 出站请求体逐字相同")
    void responsesInvokeStreamDelegatesToLegacyEntry() {
        assertThat(captureResponsesStreamBody(true)).isEqualTo(captureResponsesStreamBody(false));
    }

    // ==================== 辅助：抓请求体 ====================

    private String captureOpenAiBody(boolean viaInterface) {
        GenericOpenAiChatService service = openAiService();
        Map<String, Object> request = chatRequest("gpt-x");
        ProviderRuntimeConfiguration provider = providerOf();
        ResolvedProviderRoute route = new ResolvedProviderRoute(provider, "gpt-x", "gpt-x");
        RequestPipelineContext ctx = PipelineContexts.direct(request, provider, WireProtocol.CHAT);

        if (viaInterface) {
            service.invoke(ctx, null).map(UpstreamEvent::data).block(Duration.ofSeconds(10));
        } else {
            service.chatCompletion(request, route, HttpHeaders.EMPTY, "req", ctx)
                    .map(UpstreamEvent::data).block(Duration.ofSeconds(10));
        }
        return capturedBody.get();
    }

    private String captureOpenAiStreamBody(boolean viaInterface) {
        GenericOpenAiChatService service = openAiService();
        Map<String, Object> request = chatRequest("gpt-x");
        ProviderRuntimeConfiguration provider = providerOf();
        ResolvedProviderRoute route = new ResolvedProviderRoute(provider, "gpt-x", "gpt-x");
        RequestPipelineContext ctx = PipelineContexts.direct(request, provider, WireProtocol.CHAT);

        if (viaInterface) {
            service.invokeStream(ctx, null).collectList().block(Duration.ofSeconds(10));
        } else {
            service.chatCompletionStream(request, route, HttpHeaders.EMPTY, "req", ctx)
                    .collectList().block(Duration.ofSeconds(10));
        }
        return capturedBody.get();
    }

    private String captureAnthropicBody(boolean viaInterface) {
        GenericAnthropicChatService service = anthropicService();
        Map<String, Object> request = anthropicRequest();
        ProviderRuntimeConfiguration provider = providerOf();
        ResolvedProviderRoute route = new ResolvedProviderRoute(provider, "claude-x", "claude-x");
        RequestPipelineContext ctx = PipelineContexts.direct(request, provider, WireProtocol.MESSAGES);

        if (viaInterface) {
            service.invoke(ctx, null).map(UpstreamEvent::data).block(Duration.ofSeconds(10));
        } else {
            service.messages(request, route, HttpHeaders.EMPTY, "req", null, ctx)
                    .map(UpstreamEvent::data).block(Duration.ofSeconds(10));
        }
        return capturedBody.get();
    }

    private String captureAnthropicStreamBody(boolean viaInterface) {
        GenericAnthropicChatService service = anthropicService();
        Map<String, Object> request = anthropicRequest();
        ProviderRuntimeConfiguration provider = providerOf();
        ResolvedProviderRoute route = new ResolvedProviderRoute(provider, "claude-x", "claude-x");
        RequestPipelineContext ctx = PipelineContexts.direct(request, provider, WireProtocol.MESSAGES);

        if (viaInterface) {
            service.invokeStream(ctx, null).collectList().block(Duration.ofSeconds(10));
        } else {
            service.messagesStream(request, route, HttpHeaders.EMPTY, "req", null, ctx)
                    .collectList().block(Duration.ofSeconds(10));
        }
        return capturedBody.get();
    }

    private String captureResponsesBody(boolean viaInterface) {
        GenericResponsesChatService service = responsesService();
        Map<String, Object> request = responsesRequest();
        ProviderRuntimeConfiguration provider = providerOf();
        ResolvedProviderRoute route = new ResolvedProviderRoute(provider, "resp-x", "resp-x");
        RequestPipelineContext ctx = PipelineContexts.direct(request, provider, WireProtocol.RESPONSES);

        if (viaInterface) {
            service.invoke(ctx, null).map(UpstreamEvent::data).block(Duration.ofSeconds(10));
        } else {
            service.responses(request, route, HttpHeaders.EMPTY, "req", ctx)
                    .map(UpstreamEvent::data).block(Duration.ofSeconds(10));
        }
        return capturedBody.get();
    }

    private String captureResponsesStreamBody(boolean viaInterface) {
        GenericResponsesChatService service = responsesService();
        Map<String, Object> request = responsesRequest();
        ProviderRuntimeConfiguration provider = providerOf();
        ResolvedProviderRoute route = new ResolvedProviderRoute(provider, "resp-x", "resp-x");
        RequestPipelineContext ctx = PipelineContexts.direct(request, provider, WireProtocol.RESPONSES);

        if (viaInterface) {
            service.invokeStream(ctx, null).collectList().block(Duration.ofSeconds(10));
        } else {
            service.responsesStream(request, route, HttpHeaders.EMPTY, "req", ctx)
                    .collectList().block(Duration.ofSeconds(10));
        }
        return capturedBody.get();
    }

    // ==================== 辅助：组装 ====================

    /**
     * 重试预算固定为 0 —— 本类测的是<strong>委派等价性</strong>，与重试无关。
     *
     * <p>不注入的话执行器会走生产默认（5 次 + 2s 起的指数退避）。一旦某条路的桩
     * 形态写错被判空，症状就是<strong>静默挂起 62 秒然后超时</strong> ——
     * 看起来像「测试没跑起来」，排查时极易误判。
     * 预算归零后同样的错桩<strong>立即</strong>抛
     * {@code EmptyUpstreamResponseException}：红得快、堆栈直指用例。
     */
    private static RetryPolicyService fixedRetryPolicy(int maxAttempts) {
        return new RetryPolicyService(null) {
            @Override
            public int getMaxAttempts() {
                return maxAttempts;
            }
        };
    }

    private GenericOpenAiChatService openAiService() {
        GenericOpenAiChatService service = new GenericOpenAiChatService(objectMapper,
                new ProviderRequestHeaderService(objectMapper),
                new RequestBodyRuleEngine(objectMapper),
                new ChunkStageRegistry(
                        List.of(new ChatChunkNormalizeStage(objectMapper)),
                        List.of(new ChatReasoningFallbackStage(objectMapper))));
        service.setRetryPolicyService(fixedRetryPolicy(0));
        return service;
    }

    private GenericAnthropicChatService anthropicService() {
        GenericAnthropicChatService service = new GenericAnthropicChatService(objectMapper,
                new ProviderRequestHeaderService(objectMapper),
                new RequestBodyRuleEngine(objectMapper),
                PipelineContexts.registryWithMessagesStages(objectMapper));
        service.setRetryPolicyService(fixedRetryPolicy(0));
        return service;
    }

    private GenericResponsesChatService responsesService() {
        GenericResponsesChatService service = new GenericResponsesChatService(objectMapper,
                new ProviderRequestHeaderService(objectMapper),
                new RequestBodyRuleEngine(objectMapper));
        service.setRetryPolicyService(fixedRetryPolicy(0));
        return service;
    }

    /** 供应商 Base URL 指向本地 HttpServer —— 端口动态，故取 upstream 的地址。 */
    private ProviderRuntimeConfiguration providerOf() {
        return new ProviderRuntimeConfiguration("relay-x",
                "http://localhost:" + upstream.getAddress().getPort(),
                "key", List.of());
    }

    private static Map<String, Object> chatRequest(String model) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", List.of());
        return request;
    }

    private static Map<String, Object> anthropicRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "claude-x");
        request.put("messages", List.of());
        request.put("max_tokens", 16);
        return request;
    }

    private static Map<String, Object> responsesRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "resp-x");
        request.put("input", List.of());
        return request;
    }

    private static String chatOkBody() {
        return """
                {"id":"chatcmpl-1","object":"chat.completion","choices":[
                  {"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}
                """;
    }

    private static String anthropicOkBody() {
        return """
                {"id":"msg_1","type":"message","role":"assistant",
                 "content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn"}
                """;
    }

    /** Responses 形态的非流式响应体 —— 形态必须对，否则 {@code ResponsesContentDetector} 判空并卷入重试。 */
    private static String responsesOkBody() {
        return """
                {"id":"resp_1","object":"response","status":"completed",
                 "output":[{"type":"message","role":"assistant",
                 "content":[{"type":"output_text","text":"hello"}]}],
                 "usage":{"input_tokens":10,"output_tokens":2}}
                """;
    }
}

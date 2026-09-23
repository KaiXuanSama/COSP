package com.kaixuan.copilot_ollama_proxy.upstream.send;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.upstream.requestbody.RequestBodyAssembler;
import com.kaixuan.copilot_ollama_proxy.upstream.send.messages.GenericAnthropicChatService;
import com.kaixuan.copilot_ollama_proxy.upstream.send.chat.GenericOpenAiChatService;
import com.kaixuan.copilot_ollama_proxy.upstream.send.responses.GenericResponsesChatService;
import com.kaixuan.copilot_ollama_proxy.upstream.chunk.ChunkStageRegistry;
import com.kaixuan.copilot_ollama_proxy.upstream.chunk.normalize.ChatChunkNormalizeStage;
import com.kaixuan.copilot_ollama_proxy.upstream.chunk.fallback.ChatReasoningFallbackStage;
import com.kaixuan.copilot_ollama_proxy.testing.PipelineContexts;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamEvent;

/**
 * 三个上游执行器经 {@link UpstreamExecutor} 接口调用时<strong>确实发出了正确的请求</strong>。
 *
 * <h2>这里在测什么（3.4e 起）</h2>
 * 3.4d-1 时本类测的是「新接口方法委托旧方法，两者出站请求逐字相同」。
 * 3.4e 删掉旧方法之后，对比对象没了 —— 于是改为直接断言**接口调用本身的行为**：
 * 请求打到了正确的路径、请求体带上了 ctx 里的模型名与 body。
 *
 * <p>这样更强：原先的等价性只证明「新方法没抄错旧方法」，而旧方法对不对它管不着；
 * 现在直接钉住「经主干那条路（查表 → invoke）真的把请求发出去了」。
 *
 * <h2>桩的响应体必须带实质载荷、且形态要对</h2>
 * 三协议的检测器各看各的取值路径：Chat 看 {@code choices[].delta}、
 * Anthropic 看 {@code content[]}、Responses 看 {@code output[].content[].output_text}。
 * 形态不对会被判空并卷入重试 —— 表现为超时而非断言失败，排查时极易误判成
 * 「测试没跑起来」。故这里按协议各回一个形态正确的非空响应。
 *
 * <p>另注入<b>0 重试预算</b>：本类不测重试，预算归零后错桩会<strong>立即</strong>抛错，
 * 而不是静默挂起约 62 秒。
 */
class UpstreamExecutorTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer upstream;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedBody.set(requestBody);
            capturedPath.set(exchange.getRequestURI().getPath());
            // 流式请求要回 SSE，否则解码器不启动 → 空响应 → 触发兜底重试。
            boolean streaming = requestBody.replace(" ", "").contains("\"stream\":true");
            String path = exchange.getRequestURI().getPath();
            String body = streaming ? sseBodyFor(path) : jsonBodyFor(path);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",
                    streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
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

    // ==================== 协议键 ====================

    @Test
    @DisplayName("三个执行器各自声明正确的上游协议键")
    void eachExecutorDeclaresItsProtocol() {
        assertThat(openAiService().protocol())
                .as("Chat 执行器必须声明 CHAT —— 键错了主干会查到别的实现")
                .isEqualTo(WireProtocol.CHAT);
        assertThat(anthropicService().protocol())
                .as("Anthropic 执行器必须声明 MESSAGES")
                .isEqualTo(WireProtocol.MESSAGES);
        assertThat(responsesService().protocol())
                .as("Responses 执行器必须声明 RESPONSES")
                .isEqualTo(WireProtocol.RESPONSES);
    }

    // ==================== 经接口调用确实发出请求 ====================

    @Test
    @DisplayName("Chat：invoke 打到 /chat/completions，请求体带上 ctx 的模型名与 body")
    void openAiInvokeSendsRequest() {
        GenericOpenAiChatService service = openAiService();
        Map<String, Object> request = chatRequest("gpt-x");
        RequestPipelineContext ctx = ctxFor(request, "gpt-x", WireProtocol.CHAT, false);

        service.invoke(ctx, null).map(UpstreamEvent::data).block(Duration.ofSeconds(10));

        assertThat(capturedPath.get()).as("Chat 执行器的上游路径").endsWith("/chat/completions");
        assertThat(capturedBody.get())
                .as("请求体必须来自 ctx.body()，且模型名取自 ctx.model()")
                .contains("\"model\":\"gpt-x\"")
                .contains("\"messages\"");
    }

    @Test
    @DisplayName("Chat：invokeStream 同样打到 /chat/completions")
    void openAiInvokeStreamSendsRequest() {
        GenericOpenAiChatService service = openAiService();
        Map<String, Object> request = chatRequest("gpt-x");
        RequestPipelineContext ctx = ctxFor(request, "gpt-x", WireProtocol.CHAT, true);

        service.invokeStream(ctx, null).collectList().block(Duration.ofSeconds(10));

        assertThat(capturedPath.get()).endsWith("/chat/completions");
        assertThat(capturedBody.get()).contains("\"stream\":true");
    }

    @Test
    @DisplayName("Anthropic：invoke 打到 /messages，请求体带上 ctx 的模型名")
    void anthropicInvokeSendsRequest() {
        GenericAnthropicChatService service = anthropicService();
        Map<String, Object> request = anthropicRequest();
        RequestPipelineContext ctx = ctxFor(request, "claude-x", WireProtocol.MESSAGES, false);

        service.invoke(ctx, null).map(UpstreamEvent::data).block(Duration.ofSeconds(10));

        assertThat(capturedPath.get()).as("Anthropic 执行器的上游路径").endsWith("/messages");
        assertThat(capturedBody.get()).contains("\"model\":\"claude-x\"");
    }

    @Test
    @DisplayName("Anthropic：invokeStream 打到 /messages")
    void anthropicInvokeStreamSendsRequest() {
        GenericAnthropicChatService service = anthropicService();
        Map<String, Object> request = anthropicRequest();
        RequestPipelineContext ctx = ctxFor(request, "claude-x", WireProtocol.MESSAGES, true);

        service.invokeStream(ctx, null).collectList().block(Duration.ofSeconds(10));

        assertThat(capturedPath.get()).endsWith("/messages");
        assertThat(capturedBody.get()).contains("\"stream\":true");
    }

    @Test
    @DisplayName("Responses：invoke 打到 /responses，请求体带上 ctx 的模型名")
    void responsesInvokeSendsRequest() {
        GenericResponsesChatService service = responsesService();
        Map<String, Object> request = responsesRequest();
        RequestPipelineContext ctx = ctxFor(request, "resp-x", WireProtocol.RESPONSES, false);

        service.invoke(ctx, null).map(UpstreamEvent::data).block(Duration.ofSeconds(10));

        assertThat(capturedPath.get()).as("Responses 执行器的上游路径").endsWith("/responses");
        assertThat(capturedBody.get()).contains("\"model\":\"resp-x\"");
    }

    @Test
    @DisplayName("Responses：invokeStream 打到 /responses")
    void responsesInvokeStreamSendsRequest() {
        GenericResponsesChatService service = responsesService();
        Map<String, Object> request = responsesRequest();
        RequestPipelineContext ctx = ctxFor(request, "resp-x", WireProtocol.RESPONSES, true);

        service.invokeStream(ctx, null).collectList().block(Duration.ofSeconds(10));

        assertThat(capturedPath.get()).endsWith("/responses");
        assertThat(capturedBody.get()).contains("\"stream\":true");
    }

    // ==================== 辅助 ====================

    /**
     * 造一个「已就绪」的 ctx：body 已经过主干装配（写好 model / stream / 思考深度等）。
     *
     * <p>阶段 4 刀 1 起请求体装配在主干的 {@code RequestBodyAssembler}，执行器只读 {@code ctx.body()}。
     * 本类直接调执行器（不走完整主干），因此在这里补跑一遍装配 —— 与生产路径一致，
     * 否则 {@code stream} 等字段不会出现在出站 body 里。
     */
    private RequestPipelineContext ctxFor(Map<String, Object> body, String model, WireProtocol protocol,
                                          boolean stream) {
        RequestPipelineContext ctx = PipelineContexts.direct(body, providerOf(), protocol, stream);
        new RequestBodyAssembler(PipelineContexts.registryWithAllBodyStages(objectMapper),
                new RequestBodyRuleEngine(objectMapper)).assemble(ctx);
        return ctx;
    }

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
                new ChunkStageRegistry(
                        List.of(new ChatChunkNormalizeStage(objectMapper)),
                        List.of(new ChatReasoningFallbackStage(objectMapper))),
                PipelineContexts.contentDetectorRegistry(objectMapper));
        service.setRetryPolicyService(fixedRetryPolicy(0));
        return service;
    }

    private GenericAnthropicChatService anthropicService() {
        GenericAnthropicChatService service = new GenericAnthropicChatService(objectMapper,
                new ProviderRequestHeaderService(objectMapper),
                PipelineContexts.contentDetectorRegistry(objectMapper));
        service.setRetryPolicyService(fixedRetryPolicy(0));
        return service;
    }

    private GenericResponsesChatService responsesService() {
        GenericResponsesChatService service = new GenericResponsesChatService(objectMapper,
                new ProviderRequestHeaderService(objectMapper),
                PipelineContexts.contentDetectorRegistry(objectMapper));
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

    /** Responses 形态的非流式响应体 —— 形态必须对，否则检测器判空。 */
    private static String responsesOkBody() {
        return """
                {"id":"resp_1","object":"response","status":"completed",
                 "output":[{"type":"message","role":"assistant",
                 "content":[{"type":"output_text","text":"hello"}]}],
                 "usage":{"input_tokens":10,"output_tokens":2}}
                """;
    }
}

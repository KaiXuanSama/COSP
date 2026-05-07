package com.kaixuan.copilot_ollama_proxy.api.ollama;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.application.ollama.CompositeOllamaService;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Ollama 控制器流式回归测试。
 * <p>
 * 这里不验证 provider 内部的转换细节，而是锁住控制器的两个关键行为：
 * 1. tool_calls 结束包能否按 NDJSON 原样向前透传。
 * 2. reasoning fallback 场景下，前置 chunk 能否在最终 done 包之前及时输出。
 */
@SpringBootTest(classes = CopilotOllamaProxyApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "admin.server.port:0")
class OllamaApiControllerStreamingTests {

    @LocalServerPort
    private int port;

    @SuppressWarnings("removal")
    @MockBean
    private CompositeOllamaService ollamaService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        // 随机端口 + WebFlux + NDJSON 在 IDE 测试运行器下会比 Maven 批量运行更容易接近默认超时，
        // 这里放宽客户端响应窗口，避免把环境抖动误判成流式行为回归。
        webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * tool_calls 场景下，控制器必须保留最终完成包里的函数名和参数。
     */
    @Test
    void forwardsToolCallCompletionChunksForStreamingChat() {
        OllamaChatResponse toolCallResponse = new OllamaChatResponse();
        toolCallResponse.setModel("mimo-v2.5-pro");
        toolCallResponse.setCreatedAt("2026-05-07T12:00:00Z");
        toolCallResponse.setDone(true);
        toolCallResponse.setDoneReason("tool_calls");

        OllamaChatResponse.ResponseMessage message = new OllamaChatResponse.ResponseMessage();
        message.setRole("assistant");
        message.setContent("");
        OllamaChatResponse.ToolCallResult toolCall = new OllamaChatResponse.ToolCallResult();
        OllamaChatResponse.ToolCallFunction function = new OllamaChatResponse.ToolCallFunction();
        function.setName("read_file");
        function.setArguments(Map.of("path", "README.md"));
        toolCall.setFunction(function);
        message.setToolCalls(List.of(toolCall));
        toolCallResponse.setMessage(message);

        given(ollamaService.chatStream(any())).willReturn(Flux.just(toolCallResponse));

        FluxExchangeResult<OllamaChatResponse> result = webTestClient.post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_NDJSON).bodyValue("""
                        {
                          "model": "mimo-v2.5-pro",
                          "stream": true,
                          "messages": [
                            {
                              "role": "user",
                              "content": "hello"
                            }
                          ]
                        }
                        """).exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(OllamaChatResponse.class);

        OllamaChatResponse firstChunk = result.getResponseBody().blockFirst(Duration.ofMillis(200));

        assertThat(firstChunk).isNotNull();
        assertThat(firstChunk.isDone()).isTrue();
        assertThat(firstChunk.getDoneReason()).isEqualTo("tool_calls");
        assertThat(firstChunk.getMessage().getToolCalls()).hasSize(1);
        assertThat(firstChunk.getMessage().getToolCalls().get(0).getFunction().getName()).isEqualTo("read_file");
        assertThat(firstChunk.getMessage().getToolCalls().get(0).getFunction().getArguments())
                .containsEntry("path", "README.md");
    }

    /**
     * reasoning fallback 场景下，控制器不能等到 done 包才一次性返回，而应该先把 fallback 文本 chunk 发出去。
     */
    @Test
    void forwardsReasoningFallbackChunksBeforeTheStreamCompletes() {
        OllamaChatResponse fallbackChunk = createChunk("longcat", false, null, "thinking", List.of());
        OllamaChatResponse doneChunk = createChunk("longcat", true, "stop", "thinking", List.of());

        given(ollamaService.chatStream(any())).willReturn(
                Flux.concat(Mono.just(fallbackChunk), Mono.delay(Duration.ofMillis(350)).thenReturn(doneChunk)));

        FluxExchangeResult<OllamaChatResponse> result = webTestClient.post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_NDJSON).bodyValue("""
                        {
                          "model": "longcat",
                          "stream": true,
                          "messages": [
                            {
                              "role": "user",
                              "content": "hello"
                            }
                          ]
                        }
                        """).exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(OllamaChatResponse.class);

        OllamaChatResponse firstChunk = result.getResponseBody().blockFirst(Duration.ofMillis(200));

        assertThat(firstChunk).isNotNull();
        assertThat(firstChunk.isDone()).isFalse();
        assertThat(firstChunk.getMessage().getContent()).isEqualTo("thinking");
    }

    private OllamaChatResponse createChunk(String modelName, boolean done, String doneReason, String content,
            List<OllamaChatResponse.ToolCallResult> toolCalls) {
        OllamaChatResponse response = new OllamaChatResponse();
        response.setModel(modelName);
        response.setCreatedAt("2026-05-07T12:00:00Z");
        response.setDone(done);
        response.setDoneReason(doneReason);

        OllamaChatResponse.ResponseMessage message = new OllamaChatResponse.ResponseMessage();
        message.setRole("assistant");
        message.setContent(content);
        if (!toolCalls.isEmpty()) {
            message.setToolCalls(toolCalls);
        }
        response.setMessage(message);
        return response;
    }
}
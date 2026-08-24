package com.kaixuan.copilot_ollama_proxy.api.anthropic;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.application.anthropic.MessagesService;
import com.kaixuan.copilot_ollama_proxy.application.openai.ChatCompletionService;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Anthropic Messages 端点的 HTTP 契约。
 *
 * <p>应用层被 mock，本类只验控制器职责：请求体还原、流式/非流式分流、
 * SSE 事件类型回填、错误形态。上游行为已由
 * {@code GenericAnthropicChatServiceTests} 覆盖。
 */
@SpringBootTest(classes = CopilotOllamaProxyApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnthropicControllerTests {

    @LocalServerPort
    private int port;

    @SuppressWarnings("removal")
    @MockBean
    private MessagesService messagesService;

    /** 一并 mock 掉 OpenAI 侧，避免它去连真实上游。 */
    @SuppressWarnings("removal")
    @MockBean
    private ChatCompletionService chatCompletionService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(5)).build();
    }

    // ==================== 非流式 ====================

    /** 非流式返回 {@code application/json} 与上游原始 body。 */
    @Test
    void nonStreamReturnsJsonBody() {
        given(messagesService.messages(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                .willReturn(Mono.just("""
                        {"id":"msg_1","type":"message","role":"assistant",\
                        "content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn",\
                        "usage":{"input_tokens":10,"output_tokens":2}}"""));

        webTestClient.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"claude-x","max_tokens":100,\
                        "messages":[{"role":"user","content":"hi"}]}""")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.id").isEqualTo("msg_1")
                .jsonPath("$.content[0].text").isEqualTo("hello")
                .jsonPath("$.usage.input_tokens").isEqualTo(10);
    }

    /**
     * 未建模字段原样透传给应用层。
     *
     * <p>这是 DTO 用 {@code @JsonAnySetter} 而非逐个建模的价值所在 ——
     * {@code temperature} / {@code tools} / {@code thinking} 等无需控制器知晓即可转发。
     * 若这条失败，说明有字段被 DTO 悄悄吞掉了。
     */
    @SuppressWarnings("unchecked")
    @Test
    void unmodeledFieldsArePassedThrough() {
        given(messagesService.messages(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                .willReturn(Mono.just("{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}"));

        webTestClient.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"claude-x","max_tokens":100,"temperature":0.7,\
                        "top_k":40,"thinking":{"type":"enabled","budget_tokens":1024},\
                        "messages":[{"role":"user","content":"hi"}]}""")
                .exchange()
                .expectStatus().isOk();

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(messagesService).messages(captor.capture(), eq("claude-x"),
                any(HttpHeaders.class), anyString());

        Map<String, Object> forwarded = captor.getValue();
        assertThat(forwarded).containsEntry("temperature", 0.7);
        assertThat(forwarded).containsEntry("top_k", 40);
        assertThat(forwarded).containsKey("thinking");
        assertThat(forwarded).containsEntry("max_tokens", 100);
    }

    /** 顶层 system 为字符串形态时透传。 */
    @SuppressWarnings("unchecked")
    @Test
    void topLevelSystemStringIsPassedThrough() {
        given(messagesService.messages(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                .willReturn(Mono.just("{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}"));

        webTestClient.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"claude-x","max_tokens":100,"system":"你是助手",\
                        "messages":[{"role":"user","content":"hi"}]}""")
                .exchange()
                .expectStatus().isOk();

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(messagesService).messages(captor.capture(), anyString(),
                any(HttpHeaders.class), anyString());

        assertThat(captor.getValue()).containsEntry("system", "你是助手");
    }

    /**
     * system 为 content block 数组时不会反序列化失败。
     *
     * <p>Anthropic 允许两种形态，DTO 用 {@code Object} 承载正是为此 ——
     * 建模成 String 会让数组形态直接 400。
     */
    @Test
    void topLevelSystemArrayDoesNotFailDeserialization() {
        given(messagesService.messages(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                .willReturn(Mono.just("{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}"));

        webTestClient.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"claude-x","max_tokens":100,\
                        "system":[{"type":"text","text":"你是助手"}],\
                        "messages":[{"role":"user","content":"hi"}]}""")
                .exchange()
                .expectStatus().isOk();
    }

    /** 缺 {@code max_tokens} 不被本代理拒绝 —— 由上游服务补默认值。 */
    @Test
    void missingMaxTokensIsNotRejectedByProxy() {
        given(messagesService.messages(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                .willReturn(Mono.just("{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}"));

        webTestClient.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"claude-x\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isOk();
    }

    /** 上游错误状态码与错误体透传，不改写成 500。 */
    @Test
    void upstreamErrorStatusAndBodyArePassedThrough() {
        given(messagesService.messages(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                .willReturn(Mono.error(new org.springframework.web.reactive.function.client.WebClientResponseException(
                        429, "Too Many Requests", null,
                        "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}".getBytes(), null)));

        webTestClient.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"claude-x\",\"max_tokens\":100,\"messages\":[]}")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("rate_limit_error");
    }

    /** 无法解包上游异常时给 502 与 Anthropic 风格错误体。 */
    @Test
    void connectionFailureReturnsAnthropicStyleError() {
        given(messagesService.messages(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                .willReturn(Mono.error(new RuntimeException("connect timeout")));

        webTestClient.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"claude-x\",\"max_tokens\":100,\"messages\":[]}")
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                // 外层 type=error 是 Anthropic 的错误帧标识，客户端据此区分错误与内容。
                .jsonPath("$.type").isEqualTo("error")
                .jsonPath("$.error.type").isEqualTo("api_error");
    }

    /**
     * 供应商未声明支持 Anthropic 时给 400 并点名成因，而不是伪装成上游连接失败。
     *
     * <p>{@code ProtocolTranslationNotSupportedException} 不是
     * {@code WebClientResponseException}，若不单独判定就会落进 502 兜底分支、
     * 被译成「无法连接到上游服务」—— 而上游根本没被尝试连接。那条消息会把排查方向
     * 指向网络与上游可用性，而真正要改的是供应商的协议勾选。
     *
     * <p>状态码用 400：失败源于本地配置与请求的组合，重试多少次结果都一样，
     * 5xx 会诱导客户端重试。
     */
    @Test
    void unsupportedProtocolReturnsBadRequestNamingTheRealCause() {
        given(messagesService.messages(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                .willReturn(Mono.error(new ProtocolTranslationNotSupportedException(
                        "relay-x", WireProtocol.ANTHROPIC, WireProtocol.OPENAI)));

        webTestClient.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"claude-x\",\"max_tokens\":100,\"messages\":[]}")
                .exchange()
                .expectStatus().isEqualTo(400)
                .expectBody()
                .jsonPath("$.type").isEqualTo("error")
                // 消息里要能看到供应商标识与协议名，否则用户不知道该去改哪个配置。
                .jsonPath("$.error.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("relay-x"),
                        org.hamcrest.Matchers.containsString("ANTHROPIC")))
                .jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("无法连接")));
    }

    // ==================== 流式 ====================

    /**
     * 流式返回 {@code text/event-stream}，且每帧带 {@code event:} 类型。
     *
     * <p>事件类型回填是 Anthropic 与 OpenAI 最关键的差异 —— 客户端是状态机，
     * 只发 data 无法解析。若这条失败，Claude Desktop 之类的客户端会完全无响应。
     */
    @Test
    void streamReturnsSseWithEventTypes() {
        given(messagesService.messagesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                .willReturn(Flux.just(
                        "{\"type\":\"message_start\",\"message\":{\"id\":\"m1\"}}",
                        "{\"type\":\"content_block_delta\",\"index\":0,"
                                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}",
                        "{\"type\":\"message_stop\"}"));

        FluxExchangeResult<ServerSentEvent<String>> result = webTestClient.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"claude-x","max_tokens":100,"stream":true,\
                        "messages":[{"role":"user","content":"hi"}]}""")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                });

        List<ServerSentEvent<String>> events = result.getResponseBody()
                // 只取内容帧，跳过心跳注释帧（心跳的 data 为 null）。
                .filter(event -> event.data() != null)
                .take(3)
                .collectList().block(Duration.ofSeconds(10));

        assertThat(events).isNotNull().hasSize(3);
        assertThat(events.get(0).event()).isEqualTo("message_start");
        assertThat(events.get(1).event()).isEqualTo("content_block_delta");
        assertThat(events.get(2).event()).isEqualTo("message_stop");
        assertThat(events.get(1).data()).contains("text_delta");
    }

    /** 流式请求走流式入口，非流式入口不该被调用。 */
    @Test
    void streamRequestUsesStreamingEntryPoint() {
        given(messagesService.messagesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                .willReturn(Flux.just("{\"type\":\"message_stop\"}"));

        webTestClient.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"claude-x\",\"max_tokens\":100,\"stream\":true,\"messages\":[]}")
                .exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .getResponseBody()
                .filter(event -> event.data() != null)
                .take(1).collectList().block(Duration.ofSeconds(10));

        verify(messagesService).messagesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString());
    }

    /** 缺省 {@code stream} 视为非流式，与 OpenAI 侧同一约定。 */
    @Test
    void missingStreamFlagIsTreatedAsNonStream() {
        given(messagesService.messages(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                .willReturn(Mono.just("{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}"));

        webTestClient.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"claude-x\",\"max_tokens\":100,\"messages\":[]}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON);

        verify(messagesService).messages(anyMap(), anyString(), any(HttpHeaders.class), anyString());
    }
}

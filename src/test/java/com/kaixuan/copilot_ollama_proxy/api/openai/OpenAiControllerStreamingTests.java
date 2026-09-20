package com.kaixuan.copilot_ollama_proxy.api.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.util.List;

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

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.application.openai.ChatCompletionService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.UnresolvedModelRouteException;
import com.kaixuan.copilot_ollama_proxy.testing.UpstreamStreams;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootTest(classes = CopilotOllamaProxyApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenAiControllerStreamingTests {

  @LocalServerPort
  private int port;

  @SuppressWarnings("removal") @MockBean
  private ChatCompletionService chatCompletionService;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
        .responseTimeout(Duration.ofSeconds(2)).build();
  }

  @Test
  void forwardsTheFirstStreamingChunkBeforeTheUpstreamStreamFinishes() {
    given(chatCompletionService.chatCompletionStream(anyMap(), anyString(), org.mockito.ArgumentMatchers.any(HttpHeaders.class), anyString())).willReturn(Flux.concat(
        Mono.just(UpstreamStreams.body(
            """
                {"id":"chatcmpl-msg_123","object":"chat.completion.chunk","created":1735689600,"model":"mimo-v2.5-pro","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}
                """)),
        Mono.delay(Duration.ofMillis(350)).thenReturn(UpstreamStreams.terminal("[DONE]"))));

    FluxExchangeResult<ServerSentEvent<String>> result = webTestClient.post().uri("/v1/chat/completions")
        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).bodyValue("""
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
            """).exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
        .returnResult(new ParameterizedTypeReference<>() {
        });

    ServerSentEvent<String> firstEvent = result.getResponseBody().blockFirst(Duration.ofMillis(200));

    assertThat(firstEvent).isNotNull();
    assertThat(firstEvent.data()).contains("\"chat.completion.chunk\"");
    assertThat(firstEvent.data()).contains("\"role\":\"assistant\"");
  }

  /**
   * 流式下模型名没解析到供应商：发 {@code event: error} 帧，而不是报成上游连接失败。
   *
   * <p>这条分支的缺失正是本轮的缺陷形态 —— 非流式有分类、流式没有，
   * 于是同一件事在两条路径上得到不同的结论。流式不能只靠非流式用例间接守护：
   * 响应码在第一帧就提交了，分类结果只能体现在 SSE 事件上。
   *
   * <p>错误体用 Chat 形态（{@code {"error":{"message":…}}}），
   * 与非流式 400 同形 —— Chat 客户端两条路径的错误解析代码通常共用。
   */
  @Test
  void unresolvedModelRouteIsSentAsErrorEventNotConnectionFailure() {
    given(chatCompletionService.chatCompletionStream(anyMap(), anyString(),
        org.mockito.ArgumentMatchers.any(HttpHeaders.class), anyString()))
            .willReturn(Flux.error(new UnresolvedModelRouteException("ghost-model")));

    FluxExchangeResult<ServerSentEvent<String>> result = webTestClient.post().uri("/v1/chat/completions")
        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).bodyValue("""
            {
              "model": "ghost-model",
              "stream": true,
              "messages": [
                {
                  "role": "user",
                  "content": "hello"
                }
              ]
            }
            """).exchange().expectStatus().isOk().expectHeader()
        .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
        .returnResult(new ParameterizedTypeReference<>() {
        });

    // 心跳是注释帧（data 为 null），不属于协议事件，过滤掉。
    List<ServerSentEvent<String>> events = result.getResponseBody()
        .filter(event -> event.data() != null)
        .collectList().block(Duration.ofSeconds(5));

    assertThat(events).hasSize(1);
    assertThat(events.get(0).event()).isEqualTo("error");
    // 模型名要保留，且不得是那句会把排查方向引向网络的兜底文案。
    assertThat(events.get(0).data()).contains("ghost-model");
    assertThat(events.get(0).data()).doesNotContain("无法连接");
  }
}

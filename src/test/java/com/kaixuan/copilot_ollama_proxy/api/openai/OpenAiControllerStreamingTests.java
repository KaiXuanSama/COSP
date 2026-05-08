package com.kaixuan.copilot_ollama_proxy.api.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.application.openai.CompositeUpstreamChatService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootTest(classes = CopilotOllamaProxyApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "admin.server.port:0")
class OpenAiControllerStreamingTests {

  @LocalServerPort
  private int port;

  @SuppressWarnings("removal") @MockBean
  private CompositeUpstreamChatService upstreamChatService;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
        .responseTimeout(Duration.ofSeconds(2)).build();
  }

  @Test
  void forwardsTheFirstStreamingChunkBeforeTheUpstreamStreamFinishes() {
    given(upstreamChatService.chatCompletionStream(anyMap(), anyString())).willReturn(Flux.concat(
        Mono.just(
            """
                {"id":"chatcmpl-msg_123","object":"chat.completion.chunk","created":1735689600,"model":"mimo-v2.5-pro","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}
                """),
        Mono.delay(Duration.ofMillis(350)).thenReturn("[DONE]")));

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
}

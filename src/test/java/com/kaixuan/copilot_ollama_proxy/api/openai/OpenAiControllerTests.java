package com.kaixuan.copilot_ollama_proxy.api.openai;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.application.openai.UpstreamChatService;

import reactor.core.publisher.Mono;

@SpringBootTest(classes = CopilotOllamaProxyApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenAiControllerTests {

    @LocalServerPort
    private int port;

    @SuppressWarnings("removal") @MockBean
    private UpstreamChatService upstreamChatService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(2)).build();
    }

    @Test
    void returnsNonStreamingOpenAiChatCompletionsWithoutBlockingTheControllerPath() {
        given(upstreamChatService.chatCompletion(anyMap(), anyString())).willReturn(Mono.just("""
                {
                  "id": "chatcmpl-msg_123",
                  "object": "chat.completion",
                  "created": 1735689600,
                  "model": "mimo-v2.5-pro",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "hello from mimo"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 11,
                    "completion_tokens": 7,
                    "total_tokens": 18
                  }
                }
                """));

        webTestClient.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).bodyValue("""
                        {
                          "model": "mimo-v2.5-pro",
                          "stream": false,
                          "messages": [
                            {
                              "role": "user",
                              "content": "hello"
                            }
                          ]
                        }
                        """).exchange().expectStatus().isOk().expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON).expectBody().jsonPath("$.object")
                .isEqualTo("chat.completion").jsonPath("$.model").isEqualTo("mimo-v2.5-pro")
                .jsonPath("$.choices[0].message.role").isEqualTo("assistant")
                .jsonPath("$.choices[0].message.content").isEqualTo("hello from mimo")
                .jsonPath("$.choices[0].finish_reason").isEqualTo("stop").jsonPath("$.usage.prompt_tokens")
                .isEqualTo(11).jsonPath("$.usage.completion_tokens").isEqualTo(7)
                .jsonPath("$.usage.total_tokens").isEqualTo(18);
    }
}
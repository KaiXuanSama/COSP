package com.kaixuan.copilot_ollama_proxy.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;

import reactor.core.publisher.Mono;

@ExtendWith(OutputCaptureExtension.class) @SpringBootTest(classes = CopilotOllamaProxyApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "logging.level.com.kaixuan.copilot_ollama_proxy.proxy=DEBUG" })
class ResponseLoggingFilterTests {

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
    void logsTheResponseBodyForAsyncJsonEndpoints(CapturedOutput output) {
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
                  ]
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
                        """).exchange().expectStatus().isOk();

        assertThat(output).contains("API 响应").contains("Status : 200").contains("Type   : application/json")
                .contains("hello from mimo").contains("chat.completion");
    }
}
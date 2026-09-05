package com.kaixuan.copilot_ollama_proxy.api.openai;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.application.openai.ChatCompletionService;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderModelRow;

import reactor.core.publisher.Mono;

@SpringBootTest(classes = CopilotOllamaProxyApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenAiControllerTests {

  @LocalServerPort
  private int port;

  @SuppressWarnings("removal") @MockBean
  private ChatCompletionService chatCompletionService;

  @SuppressWarnings("removal") @MockBean
  private ProviderConfigRepository providerConfigRepository;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
        .responseTimeout(Duration.ofSeconds(2)).build();
  }

  @Test
  void returnsNonStreamingOpenAiChatCompletionsWithoutBlockingTheControllerPath() {
    given(chatCompletionService.chatCompletion(anyMap(), anyString(), org.mockito.ArgumentMatchers.any(HttpHeaders.class), anyString())).willReturn(Mono.just("""
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
            """).exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        .expectBody().jsonPath("$.object").isEqualTo("chat.completion").jsonPath("$.model").isEqualTo("mimo-v2.5-pro")
        .jsonPath("$.choices[0].message.role").isEqualTo("assistant").jsonPath("$.choices[0].message.content")
        .isEqualTo("hello from mimo").jsonPath("$.choices[0].finish_reason").isEqualTo("stop")
        .jsonPath("$.usage.prompt_tokens").isEqualTo(11).jsonPath("$.usage.completion_tokens").isEqualTo(7)
        .jsonPath("$.usage.total_tokens").isEqualTo(18);
  }

  /**
   * 供应商未声明支持 OpenAI 时给 400 并点名成因，而不是伪装成上游连接失败。
   *
   * <p>{@code ProtocolTranslationNotSupportedException} 不是
   * {@code WebClientResponseException}，若不单独判定就落进 502 兜底分支、
   * 被译成「无法连接到上游服务」—— 而上游根本没被尝试连接，那条消息会把排查方向
   * 指向网络与上游可用性，而真正要改的是供应商的协议勾选。
   */
  @Test
  void unsupportedProtocolReturnsBadRequestNamingTheRealCause() {
    given(chatCompletionService.chatCompletion(anyMap(), anyString(),
        org.mockito.ArgumentMatchers.any(HttpHeaders.class), anyString()))
        .willReturn(Mono.error(new ProtocolTranslationNotSupportedException(
            "relay-x", WireProtocol.OPENAI, WireProtocol.ANTHROPIC)));

    webTestClient.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .bodyValue("{\"model\":\"m\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
        .exchange().expectStatus().isEqualTo(400).expectBody()
        .jsonPath("$.error.type").isEqualTo("invalid_request_error")
        .jsonPath("$.error.message").value(org.hamcrest.Matchers.allOf(
            org.hamcrest.Matchers.containsString("relay-x"),
            org.hamcrest.Matchers.containsString("OPENAI"),
            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("无法连接"))));
  }

  @Test
  void returnsOpenAiModelsList() {
    // 模拟数据库返回的已启用服务商和模型列表
    List<ProviderConfigRow> activeProviders = new ArrayList<>();

    activeProviders.add(new ProviderConfigRow(
          1, "mimo", "MiMo", true, "", "[\"OPENAI\",\"ANTHROPIC\"]", "", null,
            List.of(new ProviderModelRow(1, 1, "mimo-v2.5-pro", true, 0,
                    "{\"max_output_tokens\":128000,\"overwrite_mode\":\"fallback\"}",
                    false, false, "Medium",
                    "{\"thinking_type\":\"adaptive\",\"overwrite_mode\":\"fallback\"}", -1, 0))
    ));
    activeProviders.add(new ProviderConfigRow(
          2, "deepseek", "DeepSeek", true, "", "[\"OPENAI\",\"ANTHROPIC\"]", "", null,
            List.of(new ProviderModelRow(2, 2, "deepseek-v4-flash", true, 0,
                    "{\"max_output_tokens\":128000,\"overwrite_mode\":\"fallback\"}",
                    false, false, "Medium",
                    "{\"thinking_type\":\"adaptive\",\"overwrite_mode\":\"fallback\"}", -1, 0))
    ));

    given(providerConfigRepository.findAllActiveProvidersWithEnabledModels()).willReturn(activeProviders);

    webTestClient.get().uri("/v1/models")
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk()
        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        .expectBody()
        .jsonPath("$.object").isEqualTo("list")
        .jsonPath("$.data.length()").isEqualTo(2)
        .jsonPath("$.data[0].id").isEqualTo("[mimo] mimo-v2.5-pro")
        .jsonPath("$.data[0].object").isEqualTo("model")
        .jsonPath("$.data[0].owned_by").isEqualTo("mimo")
        .jsonPath("$.data[1].id").isEqualTo("[deepseek] deepseek-v4-flash")
        .jsonPath("$.data[1].object").isEqualTo("model")
        .jsonPath("$.data[1].owned_by").isEqualTo("deepseek");
  }
}

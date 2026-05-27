package com.kaixuan.copilot_ollama_proxy.api.openai;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.application.openai.CompositeUpstreamChatService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;

import reactor.core.publisher.Mono;

@SpringBootTest(classes = CopilotOllamaProxyApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "admin.server.port:0")
class OpenAiControllerTests {

  @LocalServerPort
  private int port;

  @SuppressWarnings("removal") @MockBean
  private CompositeUpstreamChatService upstreamChatService;

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
            """).exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        .expectBody().jsonPath("$.object").isEqualTo("chat.completion").jsonPath("$.model").isEqualTo("mimo-v2.5-pro")
        .jsonPath("$.choices[0].message.role").isEqualTo("assistant").jsonPath("$.choices[0].message.content")
        .isEqualTo("hello from mimo").jsonPath("$.choices[0].finish_reason").isEqualTo("stop")
        .jsonPath("$.usage.prompt_tokens").isEqualTo(11).jsonPath("$.usage.completion_tokens").isEqualTo(7)
        .jsonPath("$.usage.total_tokens").isEqualTo(18);
  }

  @Test
  void returnsOpenAiModelsList() {
    // 模拟数据库返回的已启用服务商和模型列表
    List<Map<String, Object>> activeProviders = new ArrayList<>();

    Map<String, Object> provider1 = new HashMap<>();
    provider1.put("providerKey", "mimo");
    List<Map<String, Object>> models1 = new ArrayList<>();
    Map<String, Object> model1 = new HashMap<>();
    model1.put("modelName", "mimo-v2.5-pro");
    models1.add(model1);
    provider1.put("models", models1);

    Map<String, Object> provider2 = new HashMap<>();
    provider2.put("providerKey", "deepseek");
    List<Map<String, Object>> models2 = new ArrayList<>();
    Map<String, Object> model2 = new HashMap<>();
    model2.put("modelName", "deepseek-v4-flash");
    models2.add(model2);
    provider2.put("models", models2);

    activeProviders.add(provider1);
    activeProviders.add(provider2);

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

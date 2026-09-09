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
import com.kaixuan.copilot_ollama_proxy.application.protocol.NoSupportedProtocolException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.RequestTranslationException;
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

  /**
   * 供应商一个协议都没勾时给 400 并点名该去做什么。
   *
   * <p>此前是 WebFlux 默认 500：{@code dispatch(...)} 在应用服务方法体里同步执行，
   * 异常在 Mono 组装期就逃出了控制器，{@code onErrorResume} 不在链上。
   * 服务层加 defer 之后才有本用例断言的形态。
   */
  @Test
  void providerWithoutAnyProtocolReturnsBadRequestInsteadOfServerError() {
    given(chatCompletionService.chatCompletion(anyMap(), anyString(),
        org.mockito.ArgumentMatchers.any(HttpHeaders.class), anyString()))
        .willReturn(Mono.error(new NoSupportedProtocolException("relay-x")));

    webTestClient.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .bodyValue("{\"model\":\"m\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
        .exchange().expectStatus().isEqualTo(400).expectBody()
        .jsonPath("$.error.type").isEqualTo("invalid_request_error")
        .jsonPath("$.error.message").value(org.hamcrest.Matchers.allOf(
            org.hamcrest.Matchers.containsString("relay-x"),
            org.hamcrest.Matchers.containsString("至少勾选一种协议"),
            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("无法连接"))));
  }

  /**
   * 请求翻译失败给 400，且<strong>字段路径必须出现在错误体里</strong>。
   *
   * <p>这是这批修复里最容易被忽略的一条：前两条的异常来自调度器（配置问题），
   * 本条来自翻译器（请求内容问题）—— 即使供应商配置完全正常，一个带未知
   * {@code role} 的普通下游请求就能触发。控制器此前没有这个分支，
   * 而 {@code RequestTranslationException} 精心构造的
   * {@code messages[2].role} 一个字都到不了对端。
   *
   * <p>不能归到 502：下游没做错网络的事，是它的请求体本身无法表达成上游协议，
   * 502 会让调用方去查上游可用性。
   */
  @Test
  void requestTranslationFailureReturnsBadRequestCarryingTheFieldPath() {
    given(chatCompletionService.chatCompletion(anyMap(), anyString(),
        org.mockito.ArgumentMatchers.any(HttpHeaders.class), anyString()))
        .willReturn(Mono.error(new RequestTranslationException(
            "messages[2].role", "是未知角色 narrator，无法翻译到 Anthropic 协议")));

    webTestClient.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .bodyValue("{\"model\":\"m\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
        .exchange().expectStatus().isEqualTo(400).expectBody()
        .jsonPath("$.error.type").isEqualTo("invalid_request_error")
        .jsonPath("$.error.message").value(org.hamcrest.Matchers.allOf(
            // 字段路径是这个异常存在的意义，丢了它下游只能去猜改哪里。
            org.hamcrest.Matchers.containsString("messages[2].role"),
            org.hamcrest.Matchers.containsString("narrator"),
            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("无法连接"))));
  }

  @Test
  void returnsOpenAiModelsList() {
    // 模拟数据库返回的已启用服务商和模型列表
    List<ProviderConfigRow> activeProviders = new ArrayList<>();

    activeProviders.add(new ProviderConfigRow(
          1, "mimo", "MiMo", true, "", "[\"OPENAI\",\"ANTHROPIC\"]", "", false, null,
            List.of(new ProviderModelRow(1, 1, "mimo-v2.5-pro", true, 0,
                    "{\"max_output_tokens\":128000,\"overwrite_mode\":\"fallback\"}",
                    false, false, "Medium",
                    "{\"thinking_type\":\"adaptive\",\"overwrite_mode\":\"fallback\"}", -1, 0))
    ));
    activeProviders.add(new ProviderConfigRow(
          2, "deepseek", "DeepSeek", true, "", "[\"OPENAI\",\"ANTHROPIC\"]", "", false, null,
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

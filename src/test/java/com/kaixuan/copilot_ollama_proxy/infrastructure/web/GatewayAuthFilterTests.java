package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.application.anthropic.MessagesService;
import com.kaixuan.copilot_ollama_proxy.application.openai.ChatCompletionService;
import com.kaixuan.copilot_ollama_proxy.application.openai.ResponsesService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.AppConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.security.ApiKeyCryptoService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.security.ApiKeyCryptoService.EncryptedValue;

import reactor.core.publisher.Mono;

/**
 * 下游鉴权过滤器集成测试。
 *
 * <p>用真实的 {@link ApiKeyCryptoService} 加密一把测试 Key 写入被 mock 的
 * {@link AppConfigRepository}，从而在真实 HTTP 链路下验证 {@link GatewayAuthFilter}
 * 对 {@code POST /v1/chat/completions} 的拦截行为，以及对发现类接口的放行。
 */
@SpringBootTest(classes = CopilotOllamaProxyApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayAuthFilterTests {

    private static final String ENABLED_KEY = "gateway_auth_enabled";
    private static final String API_KEY_KEY = "gateway_api_key";
    private static final String VALID_KEY = "cosp-test-valid-key-123456";

    /**
     * 第二种凭据载体的头名。
     *
     * <p>与出站侧的 {@code ProviderRequestHeaderService.API_KEY_HEADER} 同名同值，但刻意
     * <strong>不引用</strong>那个常量：两者是独立的契约（一个管「COSP 认什么」、一个管
     * 「COSP 发什么」），耦合起来会让将来改动其中一个时误以为另一个也得跟着变。
     */
    private static final String API_KEY_HEADER = "x-api-key";

    @LocalServerPort
    private int port;

    @SuppressWarnings("removal")
    @MockBean
    private ChatCompletionService chatCompletionService;

    @SuppressWarnings("removal")
    @MockBean
    private MessagesService messagesService;

    @SuppressWarnings("removal")
    @MockBean
    private ResponsesService responsesService;

    @SuppressWarnings("removal")
    @MockBean
    private AppConfigRepository appConfigRepository;

    @Autowired
    private ApiKeyCryptoService cryptoService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(2)).build();
        // 默认：聊天服务返回一个简单响应，便于验证「放行」时确实打到了控制器。
        given(chatCompletionService.chatCompletion(anyMap(), anyString(),
                any(HttpHeaders.class), anyString()))
                .willReturn(Mono.just("{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\"}"));
        given(messagesService.messages(anyMap(), anyString(),
                any(HttpHeaders.class), anyString()))
                .willReturn(Mono.just("{\"id\":\"msg_test\",\"type\":\"message\"}"));
        given(responsesService.responses(anyMap(), anyString(),
                any(HttpHeaders.class), anyString()))
                .willReturn(Mono.just("{\"id\":\"resp_test\",\"object\":\"response\"}"));
    }

    /** 在 mock 仓库中写入「已开启 + 指定明文 Key」的配置。 */
    private void enableWithKey(String plaintextKey) {
        given(appConfigRepository.findConfigValue(ENABLED_KEY)).willReturn("true");
        EncryptedValue encrypted = cryptoService.encrypt(plaintextKey);
        given(appConfigRepository.findConfigValue(API_KEY_KEY))
                .willReturn(encrypted.nonceBase64() + ":" + encrypted.ciphertextBase64());
    }

    private WebTestClient.ResponseSpec postChat(String bearer) {
        WebTestClient.RequestBodySpec spec = webTestClient.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        return spec.bodyValue("{\"model\":\"test-model\",\"messages\":[]}").exchange();
    }

    /**
     * 打聊天端点，两个鉴权头各自可选。
     *
     * <p>与 {@link #postChat} 并存而不是取代它：那个签名被十余条用例使用，
     * 且「只带 Bearer」是最常见的形态，多一个 {@code null} 参数会让那些用例变难读。
     *
     * @param bearer {@code Authorization: Bearer <值>}；{@code null} 表示不带该头
     * @param apiKey {@code x-api-key: <值>}（裸值）；{@code null} 表示不带该头
     */
    private WebTestClient.ResponseSpec postChatWithBoth(String bearer, String apiKey) {
        WebTestClient.RequestBodySpec spec = webTestClient.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        if (apiKey != null) {
            spec = spec.header(API_KEY_HEADER, apiKey);
        }
        return spec.bodyValue("{\"model\":\"test-model\",\"messages\":[]}").exchange();
    }

    /** 只带 {@code x-api-key} 打聊天端点。 */
    private WebTestClient.ResponseSpec postChatWithApiKey(String apiKey) {
        return postChatWithBoth(null, apiKey);
    }

    /** 只带 {@code x-api-key} 打 Anthropic 端点。 */
    private WebTestClient.ResponseSpec postMessagesWithApiKey(String apiKey) {
        return webTestClient.post().uri("/v1/messages").contentType(MediaType.APPLICATION_JSON)
                .header(API_KEY_HEADER, apiKey)
                .bodyValue("{\"model\":\"test-model\",\"max_tokens\":100,\"messages\":[]}").exchange();
    }

    /** 只带 {@code x-api-key} 打 Responses 端点。 */
    private WebTestClient.ResponseSpec postResponsesWithApiKey(String apiKey) {
        return webTestClient.post().uri("/v1/responses").contentType(MediaType.APPLICATION_JSON)
                .header(API_KEY_HEADER, apiKey)
                .bodyValue("{\"model\":\"test-model\",\"input\":\"hi\"}").exchange();
    }

    /** 打 Anthropic 端点，与 {@link #postChat} 对称。 */
    private WebTestClient.ResponseSpec postMessages(String bearer) {
        WebTestClient.RequestBodySpec spec = webTestClient.post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        return spec.bodyValue("{\"model\":\"test-model\",\"max_tokens\":100,\"messages\":[]}").exchange();
    }

    /** 打 Responses 端点，与上两个对称。 */
    private WebTestClient.ResponseSpec postResponses(String bearer) {
        WebTestClient.RequestBodySpec spec = webTestClient.post().uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        return spec.bodyValue("{\"model\":\"test-model\",\"input\":\"hi\"}").exchange();
    }

    @Test
    void enabledWithCorrectKeyPassesThrough() {
        enableWithKey(VALID_KEY);
        postChat(VALID_KEY).expectStatus().isOk();
    }

    @Test
    void enabledWithWrongKeyReturns401() {
        enableWithKey(VALID_KEY);
        postChat("cosp-wrong-key").expectStatus().isUnauthorized();
    }

    @Test
    void enabledWithoutAuthorizationHeaderReturns401() {
        enableWithKey(VALID_KEY);
        postChat(null).expectStatus().isUnauthorized();
    }

    @Test
    void disabledPassesThroughRegardlessOfKey() {
        given(appConfigRepository.findConfigValue(ENABLED_KEY)).willReturn("false");
        postChat(null).expectStatus().isOk();
        postChat("any-key").expectStatus().isOk();
    }

    @Test
    void enabledButNoKeyConfiguredPassesThrough() {
        // 1A：开启但未生成 Key → 放行，避免自锁死。
        given(appConfigRepository.findConfigValue(ENABLED_KEY)).willReturn("true");
        given(appConfigRepository.findConfigValue(API_KEY_KEY)).willReturn(null);
        postChat(null).expectStatus().isOk();
    }

    @Test
    void discoveryEndpointIsNeverBlocked() {
        // 即便开启鉴权，Ollama 版本发现接口也不应被拦截（不带 Authorization 也放行）。
        // fake_version 未 stub 时返回 null，控制器回退到 application.yml 默认版本，仍为 200。
        enableWithKey(VALID_KEY);
        webTestClient.get().uri("/api/version").exchange().expectStatus().isOk();
    }

    // ---------- 两种凭据载体 ----------

    /**
     * {@code x-api-key} 里的 Key 同样被接受。
     *
     * <p>客户端把 Key 放哪个头取决于它用的凭据变量：Claude 系客户端配
     * {@code ANTHROPIC_API_KEY} 就只发 {@code x-api-key}。只读 {@code Authorization}
     * 会让那一半客户端无论配得多对都拿 401，而错误消息指向「Key 无效」——
     * 排查会去查 Key 本身，而 Key 是对的。
     */
    @Test
    void enabledAcceptsKeyFromApiKeyHeader() {
        enableWithKey(VALID_KEY);
        postChatWithApiKey(VALID_KEY).expectStatus().isOk();
    }

    /** {@code x-api-key} 里的值不对照样 401 —— 多认一个头不等于放松校验。 */
    @Test
    void wrongKeyInApiKeyHeaderStillReturns401() {
        enableWithKey(VALID_KEY);
        postChatWithApiKey("cosp-wrong-key").expectStatus().isUnauthorized();
    }

    /**
     * 两个头都带、只有 {@code x-api-key} 是对的 → 放行。
     *
     * <p>这条钉住「OR 而非优先级」：实现若写成「先看 Authorization，不匹配就拒」，
     * 本用例即红。该组合真实可达 —— 同时设了两个环境变量，或请求经过一层网关补了头。
     *
     * <p>「向 COSP 证明身份」的本质是证明知道那把 Key，载体是哪个头无关；
     * 提前返回会把一次合法请求判成 401。
     */
    @Test
    void eitherHeaderCarryingTheKeyIsEnough() {
        enableWithKey(VALID_KEY);
        postChatWithBoth("cosp-wrong-key", VALID_KEY).expectStatus().isOk();
    }

    /** 反方向同样成立：{@code Authorization} 对、{@code x-api-key} 错 → 放行。 */
    @Test
    void correctAuthorizationSurvivesAWrongApiKeyHeader() {
        enableWithKey(VALID_KEY);
        postChatWithBoth(VALID_KEY, "cosp-wrong-key").expectStatus().isOk();
    }

    /**
     * {@code x-api-key} 按<strong>裸值</strong>解析，带 {@code Bearer } 前缀反而不匹配。
     *
     * <p>两个头各自只接受自己那一种既有形态。悄悄剥掉前缀会让「哪种写法有效」
     * 变得无法从代码读出，而混着用的请求本身就说明配置有误。
     */
    @Test
    void bearerPrefixInApiKeyHeaderIsNotStripped() {
        enableWithKey(VALID_KEY);
        postChatWithApiKey("Bearer " + VALID_KEY).expectStatus().isUnauthorized();
    }

    /**
     * 裸密钥放 {@code Authorization} 不被接受，必须带 {@code Bearer } 前缀。
     *
     * <p>刻意不认：那不是任何客户端的既有写法，认它只会扩大接受面 ——
     * 需要裸值形态的客户端用 {@code x-api-key} 即可。
     */
    @Test
    void bareKeyInAuthorizationHeaderIsRejected() {
        enableWithKey(VALID_KEY);
        webTestClient.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, VALID_KEY)
                .bodyValue("{\"model\":\"test-model\",\"messages\":[]}").exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * 空值的 {@code x-api-key} 视为没带，不会被当成一次失败的比对。
     *
     * <p>用<strong>空串</strong>而不是几个空格：纯空白的头值在 HTTP 传输层就发不出去 ——
     * Netty 直接抛 {@code prohibited character 0x20 at index 0}（前导空白在 HTTP 语法里
     * 属于 obs-fold 折叠行，不是头值的一部分）。因此「值全是空格」这个情形在真实请求中
     * 根本不可达，写成那样只会得到一个 500 而验不到任何东西。
     *
     * <p>服务层仍然用 {@code isBlank()} 而非 {@code isEmpty()}：尾部空白是可达的
     * （{@code x-api-key: key  }），而 RFC 7230 本就要求接收方忽略 field-value 前后的 OWS。
     */
    @Test
    void emptyApiKeyHeaderIsTreatedAsAbsent() {
        enableWithKey(VALID_KEY);
        postChatWithApiKey("").expectStatus().isUnauthorized();
    }

    /**
     * Anthropic 端点也认 {@code x-api-key}。
     *
     * <p>这条线路的客户端<strong>最可能</strong>用那个头（官方约定如此），
     * 若三个端点里只有它漏了，症状是「Claude Code 连不上、Copilot 正常」。
     */
    @Test
    void anthropicEndpointAlsoAcceptsApiKeyHeader() {
        enableWithKey(VALID_KEY);
        postMessagesWithApiKey(VALID_KEY).expectStatus().isOk();
    }

    /** Responses 端点同样认，三个端点共用一套判据。 */
    @Test
    void responsesEndpointAlsoAcceptsApiKeyHeader() {
        enableWithKey(VALID_KEY);
        postResponsesWithApiKey(VALID_KEY).expectStatus().isOk();
    }

    // ---------- Anthropic 端点 ----------

    /**
     * Anthropic 端点同样受保护。
     *
     * <p>它与 OpenAI 端点一样真实消耗上游额度，若漏掉就等于给鉴权开了个后门 ——
     * 攻击者换个端点即可绕过。
     */
    @Test
    void anthropicEndpointWithoutAuthorizationReturns401() {
        enableWithKey(VALID_KEY);
        postMessages(null).expectStatus().isUnauthorized();
    }

    @Test
    void anthropicEndpointWithWrongKeyReturns401() {
        enableWithKey(VALID_KEY);
        postMessages("cosp-wrong-key").expectStatus().isUnauthorized();
    }

    /** 两个端点共用同一把网关 Key —— 它保护的是「谁能用这个代理」，与协议无关。 */
    @Test
    void anthropicEndpointAcceptsTheSameGatewayKey() {
        enableWithKey(VALID_KEY);
        postMessages(VALID_KEY).expectStatus().isOk();
    }

    @Test
    void anthropicEndpointPassesThroughWhenAuthDisabled() {
        given(appConfigRepository.findConfigValue(ENABLED_KEY)).willReturn("false");
        postMessages(null).expectStatus().isOk();
    }

    // ---------- Responses 端点 ----------

    /**
     * Responses 端点同样受保护。
     *
     * <p>这组用例是「新增聊天端点时必須同步改白名单」的唯一自动化保障。
     * 漏加的症状在未开启鉴权时<strong>完全无症状</strong>（本来就该放行），
     * 开启后才会表现为「换个端点即可绕过鉴权」—— 而那时已经是安全问题了。
     */
    @Test
    void responsesEndpointWithoutAuthorizationReturns401() {
        enableWithKey(VALID_KEY);
        postResponses(null).expectStatus().isUnauthorized();
    }

    @Test
    void responsesEndpointWithWrongKeyReturns401() {
        enableWithKey(VALID_KEY);
        postResponses("cosp-wrong-key").expectStatus().isUnauthorized();
    }

    /** 三个端点共用同一把网关 Key —— 它保护的是「谁能用这个代理」，与协议无关。 */
    @Test
    void responsesEndpointAcceptsTheSameGatewayKey() {
        enableWithKey(VALID_KEY);
        postResponses(VALID_KEY).expectStatus().isOk();
    }

    @Test
    void responsesEndpointPassesThroughWhenAuthDisabled() {
        given(appConfigRepository.findConfigValue(ENABLED_KEY)).willReturn("false");
        postResponses(null).expectStatus().isOk();
    }
}

package com.kaixuan.copilot_ollama_proxy.application.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.anthropic.MessagesService;
import com.kaixuan.copilot_ollama_proxy.application.openai.ChatCompletionService;
import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.AnthropicToOpenAiResponseTranslator;
import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.OpenAiToAnthropicRequestTranslator;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic.GenericAnthropicChatService;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.GenericOpenAiChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 两条聊天线路的<strong>组装期</strong>不得抛异常。
 *
 * <h2>这个不变式在保护什么</h2>
 * 路由解析、协议调度与请求翻译都是<strong>同步</strong>调用。控制器那侧是
 * {@code Mono.firstWithSignal(service.xxx(...), cancelSignal)}，参数为 eager 求值：
 * 若应用服务在方法体里直接跑这些同步步骤，异常会在 Mono 组装期就逃出控制器方法，
 * {@code onErrorResume} 根本不在链上 —— 下游拿到 WebFlux 默认 500 与通用错误体，
 * 而控制器精心构造的分类错误（400 + 供应商标识 + 字段路径）一个字都到不了对端。
 * 流式更隐蔽：状态码尚未提交，因此发出去的不是 SSE error 帧而是 500 JSON。
 *
 * <h2>为何每个用例都断言两件事</h2>
 * 「取 Publisher 不抛」与「异常以 onError 抵达」是两个独立的缺陷面：
 * 前者失败说明 defer 掉了，后者失败说明异常类型分错了。合成一句断言时，
 * 一个组装期就抛出的调用会让失败信息指向测试代码而非被测行为。
 *
 * <p>断言用 AssertJ + {@code block()} 而非 {@code StepVerifier}：本项目没有
 * {@code reactor-test} 依赖，而这里要断言的只是终止信号，用不上虚拟时间与背压控制。
 * 断言入口统一收在 {@code Publisher} 上，流式与非流式因此共用一个辅助方法。
 */
class ChatDispatchErrorSignalTests {

    private ProviderRouteResolver routeResolver;
    private GenericOpenAiChatService openAiChatService;
    private GenericAnthropicChatService anthropicChatService;
    private ChatCompletionService chatCompletionService;
    private MessagesService messagesService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        routeResolver = mock(ProviderRouteResolver.class);
        openAiChatService = mock(GenericOpenAiChatService.class);
        anthropicChatService = mock(GenericAnthropicChatService.class);
        ProtocolDispatchManager dispatchManager = new ProtocolDispatchManager();

        // 翻译器用真实实例而非 mock：本测试要验证的正是「真实翻译器抛出的异常
        // 如何抵达下游」，mock 掉它就把被测行为一起 mock 掉了。
        chatCompletionService = new ChatCompletionService(routeResolver, dispatchManager,
                openAiChatService, anthropicChatService,
                new OpenAiToAnthropicRequestTranslator(objectMapper),
                new AnthropicToOpenAiResponseTranslator(objectMapper));
        messagesService = new MessagesService(routeResolver, dispatchManager, anthropicChatService);
    }

    @Nested
    @DisplayName("供应商一个协议都没勾")
    class NoSupportedProtocol {

        @Test
        @DisplayName("非流式 OpenAI 以 onError 抵达而非组装期抛出")
        void openAiNonStream() {
            givenRoute("[]");

            assertErrorSignal(() -> chatCompletionService.chatCompletion(
                            Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-1"),
                    NoSupportedProtocolException.class, "relay-x");
        }

        @Test
        @DisplayName("流式 OpenAI 以 onError 抵达而非组装期抛出")
        void openAiStream() {
            givenRoute("[]");

            assertErrorSignal(() -> chatCompletionService.chatCompletionStream(
                            Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-2"),
                    NoSupportedProtocolException.class, "relay-x");
        }

        @Test
        @DisplayName("非流式 Anthropic 以 onError 抵达而非组装期抛出")
        void anthropicNonStream() {
            givenRoute("[]");

            assertErrorSignal(() -> messagesService.messages(
                            Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-3"),
                    NoSupportedProtocolException.class, "至少勾选一种协议");
        }

        @Test
        @DisplayName("流式 Anthropic 以 onError 抵达而非组装期抛出")
        void anthropicStream() {
            givenRoute("[]");

            assertErrorSignal(() -> messagesService.messagesStream(
                            Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-4"),
                    NoSupportedProtocolException.class, "至少勾选一种协议");
        }
    }

    /**
     * A2O 请求翻译未实现时的那条错误同样必须走信号。
     *
     * <p>这条路径在加 defer 之前也是坏的：{@code Mono.error(...)} 本身安全，
     * 但它前面的 {@code dispatch(...)} 不是，所以整个方法体必须一起进 defer。
     */
    @Nested
    @DisplayName("下游 Anthropic 而供应商只有 OpenAI（A2O 请求翻译未实现）")
    class TranslationNotSupported {

        @Test
        @DisplayName("非流式以 onError 抵达并点名供应商与协议")
        void nonStream() {
            givenRoute("[\"OPENAI\"]");

            Throwable error = assertErrorSignal(() -> messagesService.messages(
                            Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-5"),
                    ProtocolTranslationNotSupportedException.class, "relay-x");
            assertThat(error).hasMessageContaining("ANTHROPIC");
        }

        @Test
        @DisplayName("流式以 onError 抵达")
        void stream() {
            givenRoute("[\"OPENAI\"]");

            assertErrorSignal(() -> messagesService.messagesStream(
                            Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-6"),
                    ProtocolTranslationNotSupportedException.class, "relay-x");
        }
    }

    /**
     * O2A 请求翻译失败（下游请求本身无法表达成 Anthropic 协议）。
     *
     * <p>这是最容易被忽略的一条：前两组的异常来自调度器，而本组来自<strong>翻译器</strong>，
     * 位置在 {@code translateRequest} 那一行 —— 即使调度成功、供应商配置完全正常，
     * 一个带未知 {@code role} 的普通下游请求就能触发。异常消息里带着
     * {@code messages[0].role} 这样的字段路径，正是它必须抵达下游的理由。
     */
    @Nested
    @DisplayName("O2A 请求翻译失败")
    class RequestTranslationFailure {

        /** 未知 role 是契约第 6.1 节列出的四类硬失败之一。 */
        private static final Map<String, Object> BAD_ROLE_REQUEST = Map.of(
                "model", "m",
                "messages", List.of(Map.of("role", "narrator", "content", "hi")));

        @Test
        @DisplayName("非流式以 onError 抵达且消息带字段路径")
        void nonStream() {
            givenRoute("[\"ANTHROPIC\"]");

            Throwable error = assertErrorSignal(() -> chatCompletionService.chatCompletion(
                            BAD_ROLE_REQUEST, "m", HttpHeaders.EMPTY, "req-7"),
                    // 字段路径是这个异常存在的意义：只说「翻译失败」等于让调用方去猜。
                    RequestTranslationException.class, "messages[0].role");
            assertThat(error).hasMessageContaining("narrator");
        }

        @Test
        @DisplayName("流式以 onError 抵达且消息带字段路径")
        void stream() {
            givenRoute("[\"ANTHROPIC\"]");

            assertErrorSignal(() -> chatCompletionService.chatCompletionStream(
                            BAD_ROLE_REQUEST, "m", HttpHeaders.EMPTY, "req-8"),
                    RequestTranslationException.class, "messages[0].role");
        }
    }

    /**
     * 直连路径行为不变。
     *
     * <p>包 defer 是为了推迟同步步骤，不是为了改变行为。少了这组，
     * 上面那些用例即使把整个方法体换成 {@code Mono.error(...)} 也会全部通过。
     */
    @Nested
    @DisplayName("直连路径行为不变")
    class DirectPathUnchanged {

        @Test
        void openAiNonStreamStillDelegatesToUpstream() {
            givenRoute("[\"OPENAI\"]");
            given(openAiChatService.chatCompletion(any(), any(), any(), any()))
                    .willReturn(Mono.just("{\"ok\":true}"));

            assertThat(chatCompletionService.chatCompletion(
                    Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-9").block())
                    .isEqualTo("{\"ok\":true}");
        }

        @Test
        void anthropicStreamStillDelegatesToUpstream() {
            givenRoute("[\"ANTHROPIC\"]");
            given(anthropicChatService.messagesStream(any(), any(), any(), any()))
                    .willReturn(Flux.just("event-1"));

            assertThat(messagesService.messagesStream(
                    Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-10")
                    .collectList().block())
                    .containsExactly("event-1");
        }
    }

    /**
     * 断言取 Publisher 不抛、且订阅后以指定异常终止。
     *
     * <p>形参收在 {@code Publisher} 上，{@code Mono} 与 {@code Flux} 共用一个方法 ——
     * 两个泛型重载在 lambda 实参位置上无法消除歧义，而 {@code Flux.from} 对
     * {@code Mono} 是零成本适配。
     *
     * @return 捕获到的异常，供调用方追加断言
     */
    private Throwable assertErrorSignal(Supplier<? extends Publisher<String>> call,
                                        Class<? extends Throwable> expected, String messagePart) {
        assertThatCode(call::get)
                .as("组装期不得抛异常，否则控制器的 onErrorResume 不在链上")
                .doesNotThrowAnyException();
        return assertThatThrownBy(() -> Flux.from(call.get()).collectList().block())
                .isInstanceOf(expected)
                .hasMessageContaining(messagePart)
                .actual();
    }

    /** 让路由解析器返回一个协议集合为指定 JSON 的供应商。 */
    private void givenRoute(String supportedProtocolsJson) {
        ProviderRuntimeConfiguration provider = new ProviderRuntimeConfiguration(
                "relay-x", "https://example.invalid/v1", "key", List.of(),
                "[]", "{\"version\":2,\"groups\":[]}", supportedProtocolsJson, "");
        given(routeResolver.resolve(any())).willReturn(new ResolvedProviderRoute(provider, "m", "m"));
    }
}

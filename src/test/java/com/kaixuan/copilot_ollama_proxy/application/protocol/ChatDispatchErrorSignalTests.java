package com.kaixuan.copilot_ollama_proxy.application.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.anthropic.MessagesService;
import com.kaixuan.copilot_ollama_proxy.application.openai.ChatCompletionService;
import com.kaixuan.copilot_ollama_proxy.application.openai.ResponsesService;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.PipelineExecution;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.PipelineStep;
import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.MessagesToChatResponseTranslator;
import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.ChatToMessagesRequestTranslator;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.application.runtime.UnresolvedModelRouteException;
import com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic.GenericAnthropicChatService;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.GenericOpenAiChatService;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.GenericResponsesChatService;
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
import static org.mockito.Mockito.verifyNoInteractions;

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
    private GenericResponsesChatService responsesChatService;
    private ChatCompletionService chatCompletionService;
    private MessagesService messagesService;
    private ResponsesService responsesService;

    /**
     * 最近一次传给上游执行器的登记。
     *
     * <p>用 Mockito 的 {@code Answer} 捕获而不是在用例里重新 {@code given(...)}：
     * 断言的是「编排层实际传了什么」，重新打桩会把被测行为一起替换掉。
     */
    private final java.util.concurrent.atomic.AtomicReference<PipelineExecution> capturedExecution =
            new java.util.concurrent.atomic.AtomicReference<>();

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        routeResolver = mock(ProviderRouteResolver.class);
        openAiChatService = mock(GenericOpenAiChatService.class);
        anthropicChatService = mock(GenericAnthropicChatService.class);
        responsesChatService = mock(GenericResponsesChatService.class);
        ProtocolDispatchManager dispatchManager = new ProtocolDispatchManager();

        // 翻译器用真实实例而非 mock：本测试要验证的正是「真实翻译器抛出的异常
        // 如何抵达下游」，mock 掉它就把被测行为一起 mock 掉了。
        chatCompletionService = new ChatCompletionService(routeResolver, dispatchManager,
                openAiChatService, anthropicChatService,
                new ChatToMessagesRequestTranslator(objectMapper),
                new MessagesToChatResponseTranslator(objectMapper));
        messagesService = new MessagesService(routeResolver, dispatchManager, anthropicChatService);
        responsesService = new ResponsesService(routeResolver, dispatchManager, responsesChatService);
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

        @Test
        @DisplayName("非流式 Responses 以 onError 抵达而非组装期抛出")
        void responsesNonStream() {
            givenRoute("[]");

            assertErrorSignal(() -> responsesService.responses(
                            Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-4a"),
                    NoSupportedProtocolException.class, "relay-x");
        }

        @Test
        @DisplayName("流式 Responses 以 onError 抵达而非组装期抛出")
        void responsesStream() {
            givenRoute("[]");

            assertErrorSignal(() -> responsesService.responsesStream(
                            Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-4b"),
                    NoSupportedProtocolException.class, "relay-x");
        }
    }

    /**
     * M2C 请求翻译未实现时的那条错误同样必须走信号。
     *
     * <p>这条路径在加 defer 之前也是坏的：{@code Mono.error(...)} 本身安全，
     * 但它前面的 {@code dispatch(...)} 不是，所以整个方法体必须一起进 defer。
     */
    @Nested
    @DisplayName("下游 Anthropic 而供应商只有 OpenAI（M2C 请求翻译未实现）")
    class TranslationNotSupported {

        @Test
        @DisplayName("非流式以 onError 抵达并点名供应商与协议")
        void nonStream() {
            givenRoute("[\"CHAT\"]");

            Throwable error = assertErrorSignal(() -> messagesService.messages(
                            Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-5"),
                    ProtocolTranslationNotSupportedException.class, "relay-x");
            assertThat(error).hasMessageContaining("MESSAGES");
        }

        @Test
        @DisplayName("流式以 onError 抵达")
        void stream() {
            givenRoute("[\"CHAT\"]");

            assertErrorSignal(() -> messagesService.messagesStream(
                            Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-6"),
                    ProtocolTranslationNotSupportedException.class, "relay-x");
        }
    }

    /**
     * 下游 Responses 而供应商没勾 Responses。
     *
     * <p>R2C / R2M 翻译尚未实现，因此这条路径当前总是抛未实现。它仍需要走信号：
     * 异常里带着供应商标识与两侧协议名，那句话是用户弄清「该去勾哪个选项」的唯一依据。
     * 逆向方向（供应商只有 Responses、下游说 Chat）同样未实现，由 {@code CHAT} 那组覆盖。
     */
    @Nested
    @DisplayName("下游 Responses 而供应商没勾（R2C / R2M 未实现）")
    class ResponsesTranslationNotSupported {

        @Test
        @DisplayName("非流式以 onError 抵达并点名供应商与协议")
        void nonStream() {
            givenRoute("[\"CHAT\"]");

            Throwable error = assertErrorSignal(() -> responsesService.responses(
                            Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-6a"),
                    ProtocolTranslationNotSupportedException.class, "relay-x");
            assertThat(error).hasMessageContaining("RESPONSES");
        }

        @Test
        @DisplayName("流式以 onError 抵达")
        void stream() {
            givenRoute("[\"MESSAGES\"]");

            assertErrorSignal(() -> responsesService.responsesStream(
                            Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-6b"),
                    ProtocolTranslationNotSupportedException.class, "relay-x");
        }
    }

    /**
     * C2M 请求翻译失败（下游请求本身无法表达成 Anthropic 协议）。
     *
     * <p>这是最容易被忽略的一条：前两组的异常来自调度器，而本组来自<strong>翻译器</strong>，
     * 位置在 {@code translateRequest} 那一行 —— 即使调度成功、供应商配置完全正常，
     * 一个带未知 {@code role} 的普通下游请求就能触发。异常消息里带着
     * {@code messages[0].role} 这样的字段路径，正是它必须抵达下游的理由。
     */
    @Nested
    @DisplayName("C2M 请求翻译失败")
    class RequestTranslationFailure {

        /** 未知 role 是契约第 6.1 节列出的四类硬失败之一。 */
        private static final Map<String, Object> BAD_ROLE_REQUEST = Map.of(
                "model", "m",
                "messages", List.of(Map.of("role", "narrator", "content", "hi")));

        @Test
        @DisplayName("非流式以 onError 抵达且消息带字段路径")
        void nonStream() {
            givenRoute("[\"MESSAGES\"]");

            Throwable error = assertErrorSignal(() -> chatCompletionService.chatCompletion(
                            BAD_ROLE_REQUEST, "m", HttpHeaders.EMPTY, "req-7"),
                    // 字段路径是这个异常存在的意义：只说「翻译失败」等于让调用方去猜。
                    RequestTranslationException.class, "messages[0].role");
            assertThat(error).hasMessageContaining("narrator");
        }

        @Test
        @DisplayName("流式以 onError 抵达且消息带字段路径")
        void stream() {
            givenRoute("[\"MESSAGES\"]");

            assertErrorSignal(() -> chatCompletionService.chatCompletionStream(
                            BAD_ROLE_REQUEST, "m", HttpHeaders.EMPTY, "req-8"),
                    RequestTranslationException.class, "messages[0].role");
        }
    }

    /**
     * 模型名没解析到唯一供应商。
     *
     * <h2>为何必须是一个具名类型</h2>
     * 它此前是裸 {@code RuntimeException("没有可用的上游服务来处理模型: …")}，
     * 控制器认不出，只能落进 502 兜底被译成「无法连接到上游服务」。
     * 而这条路径上<strong>上游一次都没被连接过</strong> —— 路由在本地供应商目录里就返回了 null。
     * 换上具名类型后控制器才能回 400 并保留原消息。
     *
     * <p>三种成因（模型名空白 / 前缀不存在或未声明该模型 / 无前缀但命中多个）
     * 都归到这一组：它们对下游的可操作性相同（改模型名），只是改法不同。
     */
    @Nested
    @DisplayName("模型名未解析到唯一供应商")
    class UnresolvedRoute {

        @BeforeEach
        void noRoute() {
            given(routeResolver.resolve(any())).willReturn(null);
        }

        @Test
        @DisplayName("非流式 OpenAI 以 onError 抵达并保留模型名")
        void openAiNonStream() {
            assertErrorSignal(() -> chatCompletionService.chatCompletion(
                            Map.of("model", "ghost-model"), "ghost-model", HttpHeaders.EMPTY, "req-r1"),
                    UnresolvedModelRouteException.class, "ghost-model");
        }

        @Test
        @DisplayName("流式 OpenAI 以 onError 抵达")
        void openAiStream() {
            assertErrorSignal(() -> chatCompletionService.chatCompletionStream(
                            Map.of("model", "ghost-model"), "ghost-model", HttpHeaders.EMPTY, "req-r2"),
                    UnresolvedModelRouteException.class, "ghost-model");
        }

        @Test
        @DisplayName("非流式 Anthropic 以 onError 抵达")
        void anthropicNonStream() {
            assertErrorSignal(() -> messagesService.messages(
                            Map.of("model", "ghost-model"), "ghost-model", HttpHeaders.EMPTY, "req-r3"),
                    UnresolvedModelRouteException.class, "ghost-model");
        }

        @Test
        @DisplayName("流式 Anthropic 以 onError 抵达")
        void anthropicStream() {
            assertErrorSignal(() -> messagesService.messagesStream(
                            Map.of("model", "ghost-model"), "ghost-model", HttpHeaders.EMPTY, "req-r4"),
                    UnresolvedModelRouteException.class, "ghost-model");
        }

        @Test
        @DisplayName("非流式 Responses 以 onError 抵达")
        void responsesNonStream() {
            assertErrorSignal(() -> responsesService.responses(
                            Map.of("model", "ghost-model"), "ghost-model", HttpHeaders.EMPTY, "req-r5"),
                    UnresolvedModelRouteException.class, "ghost-model");
        }

        @Test
        @DisplayName("流式 Responses 以 onError 抵达")
        void responsesStream() {
            assertErrorSignal(() -> responsesService.responsesStream(
                            Map.of("model", "ghost-model"), "ghost-model", HttpHeaders.EMPTY, "req-r6"),
                    UnresolvedModelRouteException.class, "ghost-model");
        }

        /**
         * 上游一次都不该被调用。
         *
         * <p>少了这条，把路由失败改成「随便透传到上游」也能让上面六条全绿 ——
         * 而那正是本异常要防的误导：明明没连过上游，却报成上游连接失败。
         */
        @Test
        @DisplayName("任一上游执行器都不得被调用")
        void upstreamIsNeverTouched() {
            assertErrorSignal(() -> chatCompletionService.chatCompletion(
                            Map.of("model", "ghost-model"), "ghost-model", HttpHeaders.EMPTY, "req-r7"),
                    UnresolvedModelRouteException.class, "ghost-model");

            verifyNoInteractions(openAiChatService, anthropicChatService, responsesChatService);
        }
    }

    /**
     * C2M 路径必须把<strong>管道执行登记</strong>透传给上游执行器。
     *
     * <h2>漏登记的症状为什么值得一组用例</h2>
     * 登记决定空响应拦截是否介入（判据见 {@code PipelineExecution.shouldApplyEmptyResponseGate}）。
     * 编排层若不登记「回程翻译已执行」，上游执行器会把这条线路当成
     * <strong>半轮实现态</strong>而跳过拦截 —— 于是 C2M 这条已全实现的线路
     * <em>静默失去空响应兜底</em>：中转站抽风返回的空回复会原样透给下游，
     * 用户看到一次空回复，而无任何报错。
     *
     * <p>反向的漏登记（该跳过的没跳过）同样有害：开发者做半轮实现时会白等 62 秒。
     * 两个方向都只能靠「登记内容被如实传递」来保证，因此这里直接断言传过去的那份登记。
     *
     * <p>为何在<strong>编排层</strong>测而不是在 provider 层：provider 层的用例
     * 自己构造登记，验的是「判据用对了」；这里验的是「编排层填对了」。
     * 两者缺一，链条上就有一段无人看守。
     */
    @Nested
    @DisplayName("C2M 透传管道执行登记")
    class PipelineExecutionPropagation {

        private static final Map<String, Object> CHAT_REQUEST = Map.of(
                "model", "m",
                "messages", List.of(Map.of("role", "user", "content", "hi")));

        /**
         * 打桩 Anthropic 执行器：捕获它收到的登记，并回一份有内容的响应。
         *
         * <p>capture 放在用例内而不是 {@code setUp} 里 —— 全局打桩会改变
         * 本类其它用例的 fixture（它们要验证的是「异常抵达下游」，
         * 而一个会正常返回的桩会让那条路径不再被执行）。
         */
        private void stubAnthropicNonStreamCapturingExecution() {
            given(anthropicChatService.messages(any(), any(), any(), any(), any(), any()))
                    .willAnswer(invocation -> {
                        capturedExecution.set(invocation.getArgument(5));
                        return Mono.just("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");
                    });
        }

        /** 流式同理：捕获登记并回一个终止事件，避免下游 REQUEST 被翻译器继续处理。 */
        private void stubAnthropicStreamCapturingExecution() {
            given(anthropicChatService.messagesStream(any(), any(), any(), any(), any(), any()))
                    .willAnswer(invocation -> {
                        capturedExecution.set(invocation.getArgument(5));
                        return Flux.just("{\"type\":\"message_stop\"}");
                    });
        }

        @Test
        @DisplayName("非流式登记去程与回程都已执行")
        void nonStream() {
            givenRoute("[\"MESSAGES\"]");
            stubAnthropicNonStreamCapturingExecution();

            chatCompletionService.chatCompletion(CHAT_REQUEST, "m", HttpHeaders.EMPTY, "req-p1").block();

            PipelineExecution execution = capturedExecution.get();
            assertThat(execution).as("C2M 必须把登记传给上游执行器").isNotNull();
            assertThat(execution.hasCompleted(PipelineStep.REQUEST_TRANSLATION))
                    .as("去程已执行")
                    .isTrue();
            assertThat(execution.hasCompleted(PipelineStep.RESPONSE_TRANSLATION))
                    .as("回程已执行 —— 漏登记会让这条线路静默失去空响应兜底")
                    .isTrue();
            assertThat(execution.shouldApplyEmptyResponseGate())
                    .as("全实现的翻译线路必须照常拦截空响应")
                    .isTrue();
        }

        @Test
        @DisplayName("流式登记去程与回程都已执行")
        void stream() {
            givenRoute("[\"MESSAGES\"]");
            stubAnthropicStreamCapturingExecution();

            chatCompletionService.chatCompletionStream(CHAT_REQUEST, "m", HttpHeaders.EMPTY, "req-p2")
                    .blockLast();

            PipelineExecution execution = capturedExecution.get();
            assertThat(execution).as("C2M 必须把登记传给上游执行器").isNotNull();
            assertThat(execution.shouldApplyEmptyResponseGate())
                    .as("全实现的翻译线路必须照常拦截空响应")
                    .isTrue();
        }

        /**
         * 直连路径<strong>不传</strong>登记，走的是无登记的旧重载。
         *
         * <p>这不是遗漏而是刻意的：直连没有需要跳过的步骤，
         * {@code PipelineExecution.empty()} 的语义就是「照常执行一切」，
         * 而「不传」与「传一个空登记」在行为上完全等价 —— 因此不额外构造，
         * 少一处需要维护的接线。
         *
         * <p>本用例钉住这个等价性：直连走的仍是那一组不带登记的重载。
         * 若将来有人给直连也加上登记，这条会失败，提醒他确认那是否必要。
         */
        @Test
        @DisplayName("直连路径走不带登记的重载")
        void directUsesOverloadWithoutExecutionRegistration() {
            givenRoute("[\"CHAT\"]");
            given(openAiChatService.chatCompletion(any(), any(), any(), any()))
                    .willReturn(Mono.just("{\"ok\":true}"));

            assertThat(chatCompletionService.chatCompletion(
                    CHAT_REQUEST, "m", HttpHeaders.EMPTY, "req-p3").block())
                    .isEqualTo("{\"ok\":true}");
            // 未构造任何登记：捕获 Set 为空即直连没走 C2M 的登记分支。
            assertThat(capturedExecution.get()).isNull();
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
            givenRoute("[\"CHAT\"]");
            given(openAiChatService.chatCompletion(any(), any(), any(), any()))
                    .willReturn(Mono.just("{\"ok\":true}"));

            assertThat(chatCompletionService.chatCompletion(
                    Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-9").block())
                    .isEqualTo("{\"ok\":true}");
        }

        @Test
        void anthropicStreamStillDelegatesToUpstream() {
            givenRoute("[\"MESSAGES\"]");
            given(anthropicChatService.messagesStream(any(), any(), any(), any()))
                    .willReturn(Flux.just("event-1"));

            assertThat(messagesService.messagesStream(
                    Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-10")
                    .collectList().block())
                    .containsExactly("event-1");
        }

        @Test
        void responsesNonStreamStillDelegatesToUpstream() {
            givenRoute("[\"RESPONSES\"]");
            given(responsesChatService.responses(any(), any(), any(), any()))
                    .willReturn(Mono.just("{\"ok\":true}"));

            assertThat(responsesService.responses(
                    Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-11").block())
                    .isEqualTo("{\"ok\":true}");
        }

        @Test
        void responsesStreamStillDelegatesToUpstream() {
            givenRoute("[\"RESPONSES\"]");
            given(responsesChatService.responsesStream(any(), any(), any(), any()))
                    .willReturn(Flux.just("event-1"));

            assertThat(responsesService.responsesStream(
                    Map.of("model", "m"), "m", HttpHeaders.EMPTY, "req-12")
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

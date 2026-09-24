package com.kaixuan.copilot_ollama_proxy.application.openai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.PipelineStep;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.AfterSend;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.BeforeSend;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipeline;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchManager;
import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.ChatToMessagesRequestTranslator;
import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.MessagesToChatResponseTranslator;
import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.TranslatorRegistry;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.upstream.requestbody.RequestBodyAssembler;
import com.kaixuan.copilot_ollama_proxy.upstream.send.UpstreamExecutorRegistry;
import com.kaixuan.copilot_ollama_proxy.upstream.send.messages.GenericAnthropicChatService;
import com.kaixuan.copilot_ollama_proxy.upstream.send.chat.GenericOpenAiChatService;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.testing.PipelineContexts;
import com.kaixuan.copilot_ollama_proxy.testing.UpstreamStreams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * <strong>半轮实现态</strong>：去程翻译已接、回程翻译未接时，编排层原样透传上游响应 ——
 * 但<strong>不静默</strong>（方向文档 §2.3.2）。
 *
 * <h2>这组用例守护的是「去程/回程独立缺省」在 {@code ChatCompletionService} 里的落地</h2>
 * {@link TranslatorRegistry} 那组用例验的是查表本身（命中/未命中/冲突）；本类验的是编排层
 * <strong>拿未命中怎么办</strong>：
 * <ul>
 *   <li><strong>透传</strong>：回程未命中时把上游原生响应原样交给下游，不翻译；</li>
 *   <li><strong>登记只有去程</strong>：{@code RequestPipelineContext} 只记 {@code REQUEST_TRANSLATION}，
 *       于是空响应拦截跳过 —— 上游原生帧属于另一个协议，判空没有意义
 *       （判据见 {@link RequestPipelineContext#shouldApplyEmptyResponseGate()}）；</li>
 *   <li><strong>留痕</strong>：透传前打一条 {@code WARN}。透传本身可接受，静默不可接受 ——
 *       同一个坑（流挂住、界面转圈而无报错）已踩过一次。</li>
 * </ul>
 *
 * <h2>为何用「只装去程」的注册表</h2>
 * 生产环境里 C2M 两半都带 {@code @Component}，回程恒命中，透传路径是死代码。要触发它只能
 * 构造一个<strong>半装配</strong>的注册表（去程有、回程空）—— 这与 Stage 3.1 用 MESSAGES
 * 替身测「未来的多实现」同一手法：测的是结构允许的中间态，而非当前恰好存在的状态。
 *
 * <h2>为何用 {@link ListAppender} 而非 {@code OutputCaptureExtension}</h2>
 * 本类是纯单元测试（无 Spring）。{@code OutputCaptureExtension} 抓的是 {@code System.out}，
 * 而 logback 的 {@code ConsoleAppender} 会在初始化时缓存 {@code System.out} 引用 ——
 * 若 logback 早于扩展替换流就初始化了，warn 会写到旧引用而抓不到。{@code ListAppender}
 * 直接挂在 logger 上，与控制台无关，在无 Spring 的场景下更可靠。
 */
class ChatCompletionHalfRoundTranslationTests {

    private static final Map<String, Object> CHAT_REQUEST = Map.of(
            "model", "m",
            "messages", List.of(Map.of("role", "user", "content", "hi")));

    /** 上游原生 Anthropic 非流式响应体 —— 一旦被 M2C 翻译过就不再是这个形状。 */
    private static final String RAW_ANTHROPIC_BODY =
            "{\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":\"raw\"}]}";

    /** 上游原生 Anthropic 事件 —— 翻译后会变成 OpenAI chunk（含 [DONE]），因此可作判别。 */
    private static final String RAW_ANTHROPIC_EVENT =
            "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}";
    private static final String RAW_ANTHROPIC_STOP = "{\"type\":\"message_stop\"}";

    /** 可被 M2C 翻译成 OpenAI chat.completion 的完整 Anthropic 响应体（全实现对照用）。 */
    private static final String TRANSLATABLE_ANTHROPIC_BODY =
            "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"stop_reason\":\"end_turn\","
                    + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProviderRouteResolver routeResolver;
    private GenericOpenAiChatService openAiChatService;
    private GenericAnthropicChatService anthropicChatService;
    private ProtocolDispatchManager dispatchManager;

    /** 主体服务：注册表只装了去程（C2M），回程为空 —— 触发半轮实现态透传。 */
    private ChatCompletionService halfRoundService;

    /** 捕获传给上游执行器的登记，验「只登记了去程」。 */
    private final AtomicReference<RequestPipelineContext> capturedExecution = new AtomicReference<>();

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        routeResolver = mock(ProviderRouteResolver.class);
        openAiChatService = mock(GenericOpenAiChatService.class);
        anthropicChatService = mock(GenericAnthropicChatService.class);
        dispatchManager = new ProtocolDispatchManager();

        // 主干按协议查表选执行器，故 mock 必须声明自己的键 —— 否则注册表查到 null。
        given(anthropicChatService.protocol()).willReturn(WireProtocol.MESSAGES);
        given(openAiChatService.protocol()).willReturn(WireProtocol.CHAT);
        UpstreamExecutorRegistry executors =
                new UpstreamExecutorRegistry(List.of(openAiChatService, anthropicChatService));

        // 半装配注册表：去程 C2M 有、回程 M2C 空。查回程即未命中 → 透传路径。
        TranslatorRegistry halfRoundRegistry = new TranslatorRegistry(
                List.of(new ChatToMessagesRequestTranslator(objectMapper)),
                List.of());
        halfRoundService = new ChatCompletionService(pipelineOf(halfRoundRegistry, executors));

        // 供应商只勾 MESSAGES：下游 CHAT 打进来 → 调度判需要翻译、上游协议 MESSAGES。
        givenProviderSupporting("[\"MESSAGES\"]");

        // WARN 的归属在发送后块（AfterSend），故监听它的 logger。
        serviceLogger = (Logger) LoggerFactory.getLogger(AfterSend.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (serviceLogger != null && logAppender != null) {
            serviceLogger.detachAppender(logAppender);
        }
    }

    @Test
    @DisplayName("非流式：回程未命中则透传上游原生响应，且只登记去程、跳过空响应拦截")
    void nonStreamPassesThroughAndSkipsGateWhenResponseTranslatorMissing() {
        given(anthropicChatService.invoke(any(), any()))
                .willAnswer(invocation -> {
                    capturedExecution.set(invocation.getArgument(0));
                    return UpstreamStreams.single(RAW_ANTHROPIC_BODY);
                });

        String data = halfRoundService.chatCompletion(CHAT_REQUEST, "m", HttpHeaders.EMPTY, "req-half-1")
                .map(UpstreamEvent::data)
                .block();

        assertThat(data)
                .as("回程未接：下游拿到的是上游原生 Anthropic 响应，未经 M2C 翻译")
                .isEqualTo(RAW_ANTHROPIC_BODY);

        RequestPipelineContext execution = capturedExecution.get();
        assertThat(execution).as("去程仍要把登记传给上游执行器").isNotNull();
        assertThat(execution.hasCompleted(PipelineStep.REQUEST_TRANSLATION))
                .as("去程已执行")
                .isTrue();
        assertThat(execution.hasCompleted(PipelineStep.RESPONSE_TRANSLATION))
                .as("回程未接，不得登记 —— 这正是半轮态的结构表达")
                .isFalse();
        assertThat(execution.shouldApplyEmptyResponseGate())
                .as("半轮态：帧是上游协议形态，空响应拦截必须跳过")
                .isFalse();
    }

    @Test
    @DisplayName("流式：回程未命中则透传上游原生事件流，未翻译成 OpenAI chunk")
    void streamPassesThroughWhenResponseTranslatorMissing() {
        given(anthropicChatService.invokeStream(any(), any()))
                .willAnswer(invocation -> {
                    capturedExecution.set(invocation.getArgument(0));
                    return UpstreamStreams.messages(RAW_ANTHROPIC_EVENT, RAW_ANTHROPIC_STOP);
                });

        List<String> data = halfRoundService.chatCompletionStream(CHAT_REQUEST, "m", HttpHeaders.EMPTY, "req-half-2")
                .map(UpstreamEvent::data)
                .collectList()
                .block();

        assertThat(data)
                .as("回程未接：原样透传上游事件，不产生 OpenAI chunk 或 [DONE]")
                .containsExactly(RAW_ANTHROPIC_EVENT, RAW_ANTHROPIC_STOP);

        RequestPipelineContext execution = capturedExecution.get();
        assertThat(execution.hasCompleted(PipelineStep.RESPONSE_TRANSLATION))
                .as("流式同非流式：回程未接不登记")
                .isFalse();
    }

    @Test
    @DisplayName("透传不静默：非流式透传前打 WARN 且带 requestId")
    void nonStreamWarnsWhenPassingThrough() {
        given(anthropicChatService.invoke(any(), any()))
                .willReturn(UpstreamStreams.single(RAW_ANTHROPIC_BODY));

        halfRoundService.chatCompletion(CHAT_REQUEST, "m", HttpHeaders.EMPTY, "req-half-3").block();

        assertThat(logAppender.list)
                .as("透传本身可接受，静默不可接受 —— 必须留痕（§2.3.2）")
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("回程翻译未实现")
                        && event.getFormattedMessage().contains("req-half-3"));
    }

    @Test
    @DisplayName("透传不静默：流式透传前同样打 WARN")
    void streamWarnsWhenPassingThrough() {
        given(anthropicChatService.invokeStream(any(), any()))
                .willReturn(UpstreamStreams.messages(RAW_ANTHROPIC_EVENT, RAW_ANTHROPIC_STOP));

        halfRoundService.chatCompletionStream(CHAT_REQUEST, "m", HttpHeaders.EMPTY, "req-half-4")
                .blockLast();

        assertThat(logAppender.list)
                .as("流式路径也不得静默透传")
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("回程翻译未实现")
                        && event.getFormattedMessage().contains("req-half-4"));
    }

    /**
     * 全实现对照 —— 回程命中时<strong>翻译且不打透传 WARN</strong>。
     *
     * <p>这是「半轮态才透传+warn」的成对守护：只测半轮态会让「无论命中与否都 warn」
     * 或「无论命中与否都透传」的 bug 无人看守。回程命中时必须走翻译、且那条 WARN 不该响。
     */
    @Test
    @DisplayName("对照：回程命中则翻译成 OpenAI 形态，且不打透传 WARN、登记去程与回程")
    void fullyWiredTranslatesAndDoesNotWarn() {
        TranslatorRegistry fullRegistry = new TranslatorRegistry(
                List.of(new ChatToMessagesRequestTranslator(objectMapper)),
                List.of(new MessagesToChatResponseTranslator(objectMapper)));
        ChatCompletionService fullyWired = new ChatCompletionService(
                pipelineOf(fullRegistry,
                        new UpstreamExecutorRegistry(List.of(openAiChatService, anthropicChatService))));

        given(anthropicChatService.invoke(any(), any()))
                .willAnswer(invocation -> {
                    capturedExecution.set(invocation.getArgument(0));
                    return UpstreamStreams.single(TRANSLATABLE_ANTHROPIC_BODY);
                });

        String data = fullyWired.chatCompletion(CHAT_REQUEST, "m", HttpHeaders.EMPTY, "req-full-1")
                .map(UpstreamEvent::data)
                .block();

        assertThat(data)
                .as("回程命中：上游 Anthropic 响应被 M2C 翻译成 OpenAI chat.completion")
                .contains("choices")
                .isNotEqualTo(TRANSLATABLE_ANTHROPIC_BODY);

        RequestPipelineContext execution = capturedExecution.get();
        assertThat(execution.hasCompleted(PipelineStep.RESPONSE_TRANSLATION))
                .as("全实现：回程也登记，空响应拦截照常生效")
                .isTrue();
        assertThat(execution.shouldApplyEmptyResponseGate()).isTrue();

        assertThat(logAppender.list)
                .as("回程命中时那条透传 WARN 不该响")
                .noneMatch(event -> event.getFormattedMessage().contains("回程翻译未实现"));
    }

    /** 让路由解析器返回一个协议集合为指定 JSON 的供应商。 */
    private void givenProviderSupporting(String supportedProtocolsJson) {
        ProviderRuntimeConfiguration provider = new ProviderRuntimeConfiguration(
                "relay-x", "https://example.invalid/v1", "key", List.of(),
                "[]", "{\"version\":2,\"groups\":[]}", supportedProtocolsJson, "");
        given(routeResolver.resolve(any())).willReturn(new ResolvedProviderRoute(provider, "m", "m"));
    }

    /**
     * 主干需要的请求体装配器（阶段 4 刀 1）。
     *
     * <p>本类的执行器是 mock，装配后的 body 不会真正出站；但主干在调执行器前必调装配器，
     * 因此这里给一个真实实例，避免 NPE。装配序列对本类的断言（透传 / WARN / 登记）无影响。
     */
    private RequestBodyAssembler assembler() {
        return new RequestBodyAssembler(PipelineContexts.registryWithAllBodyStages(objectMapper),
                new RequestBodyRuleEngine(objectMapper));
    }

    /**
     * 用发送前块 + 发送后块拼出 {@link RequestPipeline}（阶段 4 刀 3 块化后的构造形态）。
     *
     * <p>路由/调度/翻译/装配/出站装配归 {@link BeforeSend}，执行器与回程翻译归 {@link AfterSend}；
     * 门面只按序转交。本类执行器是 mock，出站装配跑真实实例（避免 ctx.outboundHeaders 为 null），
     * 但装配结果不出站，故对「半轮态透传 + WARN」的断言透明。
     */
    private RequestPipeline pipelineOf(TranslatorRegistry registry, UpstreamExecutorRegistry executors) {
        return new RequestPipeline(
                new BeforeSend(routeResolver, dispatchManager, registry, assembler(), outboundAssembler()),
                new AfterSend(executors));
    }

    /** 出站装配器：三条出站支线齐备（本类上游走 MESSAGES，需 MessagesOutboundStage 解析地址）。 */
    private com.kaixuan.copilot_ollama_proxy.upstream.outbound.OutboundRequestAssembler outboundAssembler() {
        return new com.kaixuan.copilot_ollama_proxy.upstream.outbound.OutboundRequestAssembler(
                new com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService(objectMapper),
                new com.kaixuan.copilot_ollama_proxy.upstream.outbound.OutboundRequestStageRegistry(List.of(
                        new com.kaixuan.copilot_ollama_proxy.upstream.outbound.chat.ChatOutboundStage(),
                        new com.kaixuan.copilot_ollama_proxy.upstream.outbound.messages.MessagesOutboundStage(),
                        new com.kaixuan.copilot_ollama_proxy.upstream.outbound.responses.ResponsesOutboundStage())),
                objectMapper);
    }
}

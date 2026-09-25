package com.kaixuan.copilot_ollama_proxy.pipeline;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.pipeline.entry.MessagesService;
import com.kaixuan.copilot_ollama_proxy.pipeline.entry.ChatCompletionService;
import com.kaixuan.copilot_ollama_proxy.pipeline.entry.ResponsesService;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.upstream.send.UpstreamExecutorRegistry;
import com.kaixuan.copilot_ollama_proxy.upstream.send.messages.GenericAnthropicChatService;
import com.kaixuan.copilot_ollama_proxy.upstream.send.chat.GenericOpenAiChatService;
import com.kaixuan.copilot_ollama_proxy.upstream.send.responses.GenericResponsesChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 主干装配的结构断言 —— 三个应用服务真的把前奏托付给 {@link RequestPipeline} 了吗。
 *
 * <h2>为何这个替换必须单独验（本项目最反复的失效形态）</h2>
 * 3.4a/b 把三个应用服务<strong>逐字相同</strong>的前奏段
 * （{@code resolve → dispatch → notifyProtocols}）搬进主干。搬完之后，
 * 「服务仍自己跑前奏」与「服务委托主干」在<strong>行为上完全等价</strong> ——
 * 于是若某个服务漏改（仍持有旧的 routeResolver / dispatchManager 字段，
 * 或 Spring 装配到了别的东西），功能一切正常，而「6 份骨架 → 1 份」的成果
 * <strong>静默归零</strong>。
 *
 * <p>这正是 3.1/3.2/3.3 反复遇到的那条规律：<em>两条路等价时，只有结构断言能验出接线断了</em>。
 * 故这里断言的是「注入确实发生、字段已被清空」，而不是「行为正确」——
 * 后者由 {@code ChatDispatchErrorSignalTests}（异常与登记）与三条目的既有用例覆盖。
 *
 * <h2>为何用反射读私有字段</h2>
 * 委托之后，三个服务不再有 {@code providerRouteResolver} / {@code protocolDispatchManager}
 * 字段 —— 这正是「搬干净了」的证据。从公开 API 观察不到这件事，
 * 只有读私有字段能直接确认。用 {@link ReflectionTestUtils}：它按字段名取值、
 * 失败信息可读（Spring 专门为这类断言提供的工具）。
 *
 * <p>若将来有人把字段加回来（例如为某个新功能绕过主干直接解析路由），
 * 本测试会立刻变红 —— 那正是应有的提醒：前奏的唯一归属是主干。
 */
@SpringBootTest(classes = CopilotOllamaProxyApplication.class)
class RequestPipelineSpringWiringTests {

    @Autowired
    private RequestPipeline requestPipeline;

    @Autowired
    private BeforeSend beforeSend;

    @Autowired
    private AfterSend afterSend;

    @Autowired
    private ChatCompletionService chatCompletionService;

    @Autowired
    private MessagesService messagesService;

    @Autowired
    private ResponsesService responsesService;

    @Autowired
    private UpstreamExecutorRegistry executorRegistry;

    @Test
    @DisplayName("主干是 Spring Bean，且由两个功能块组成（发送前 / 发送后）")
    void requestPipelineIsWiredAsBean() {
        assertThat(requestPipeline).as("主干必须由容器提供").isNotNull();
        // 块化（阶段 4 刀 3）后主干只组合两个块 —— 门面持有的就是这两个块。
        assertThat(ReflectionTestUtils.getField(requestPipeline, "beforeSend"))
                .as("主干必须持有发送前块 —— 路由 / 调度 / 翻译 / 装配都归它")
                .isSameAs(beforeSend);
        assertThat(ReflectionTestUtils.getField(requestPipeline, "afterSend"))
                .as("主干必须持有发送后块 —— 选执行器 / send / 回程翻译都归它")
                .isSameAs(afterSend);
        // 前奏依赖随 route 逻辑一起进了发送前块（不再挂在门面上）。
        assertThat(ReflectionTestUtils.getField(beforeSend, "providerRouteResolver"))
                .as("发送前块必须持有路由解析器 —— 它是前奏的第一步")
                .isNotNull();
        assertThat(ReflectionTestUtils.getField(beforeSend, "protocolDispatchManager"))
                .as("发送前块必须持有协议调度器 —— 它是前奏的第二步")
                .isNotNull();
    }

    @Test
    @DisplayName("三个应用服务都持有同一个主干，且已不再自带前奏依赖")
    void allThreeServicesDelegateToTheSameTrunk() {
        assertThat(ReflectionTestUtils.getField(chatCompletionService, "requestPipeline"))
                .as("ChatCompletionService 必须委托主干")
                .isSameAs(requestPipeline);
        assertThat(ReflectionTestUtils.getField(messagesService, "requestPipeline"))
                .as("MessagesService 必须委托主干")
                .isSameAs(requestPipeline);
        assertThat(ReflectionTestUtils.getField(responsesService, "requestPipeline"))
                .as("ResponsesService 必须委托主干")
                .isSameAs(requestPipeline);
    }

    @Test
    @DisplayName("两张表各归其块：翻译器表在发送前块、执行器表在发送后块（刀 3 块化）")
    void trunkHoldsBothRegistries() {
        assertThat(ReflectionTestUtils.getField(beforeSend, "translatorRegistry"))
                .as("翻译器表归发送前块 —— 「选翻译策略」是发送前的事")
                .isNotNull();
        assertThat(ReflectionTestUtils.getField(afterSend, "executorRegistry"))
                .as("执行器表归发送后块 —— send 插槽靠它选执行器")
                .isNotNull();
    }

    @Test
    @DisplayName("执行器注册表收齐三个协议 —— 漏一个会让那条线路的请求报装配错误")
    void executorRegistryCoversAllThreeProtocols() {
        for (WireProtocol protocol : WireProtocol.values()) {
            // 未命中会抛 IllegalStateException（「装配坏了」而非「领域事实」），
            // 故这里逐个 require：任何一条线路少执行器都会当场红。
            assertThatCode(() -> executorRegistry.require(protocol))
                    .as("协议 %s 必须有执行器 —— 否则那条线路一请求就报装配错误", protocol)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("三个执行器注册的键与它们的类型对得上")
    void eachProtocolMapsToItsOwnExecutor() {
        assertThat(executorRegistry.require(WireProtocol.CHAT)).isInstanceOf(GenericOpenAiChatService.class);
        assertThat(executorRegistry.require(WireProtocol.MESSAGES)).isInstanceOf(GenericAnthropicChatService.class);
        assertThat(executorRegistry.require(WireProtocol.RESPONSES)).isInstanceOf(GenericResponsesChatService.class);
    }

    @Test
    @DisplayName("三个应用服务已无 routeResolver / dispatchManager / lifecycleNotifier 字段")
    void noServiceRetainsThePreambleDependencies() {
        for (Object service : new Object[]{chatCompletionService, messagesService, responsesService}) {
            String name = service.getClass().getSimpleName();
            // 断言字段「已不存在」—— 所以不能用 getField（字段缺失时它会抛）。
            assertThat(ReflectionUtils.findField(service.getClass(), "providerRouteResolver"))
                    .as("%s 不该再有路由解析器字段：前奏的唯一归属是主干", name)
                    .isNull();
            assertThat(ReflectionUtils.findField(service.getClass(), "protocolDispatchManager"))
                    .as("%s 不该再有协议调度器字段：前奏的唯一归属是主干", name)
                    .isNull();
            assertThat(ReflectionUtils.findField(service.getClass(), "lifecycleNotifier"))
                    .as("%s 不该再有生命周期通知器字段：它随前奏一起上移了", name)
                    .isNull();
            // 3.4c-2：执行器与翻译器也不再由 Service 持有 —— 它们只在主干上。
            assertThat(ReflectionUtils.findField(service.getClass(), "anthropicChatService"))
                    .as("%s 不该再持有执行器：选执行器是主干 send 插槽的事", name)
                    .isNull();
            assertThat(ReflectionUtils.findField(service.getClass(), "genericOpenAiChatService"))
                    .as("%s 不该再持有执行器", name)
                    .isNull();
            assertThat(ReflectionUtils.findField(service.getClass(), "responsesChatService"))
                    .as("%s 不该再持有执行器", name)
                    .isNull();
            assertThat(ReflectionUtils.findField(service.getClass(), "translatorRegistry"))
                    .as("%s 不该再持有翻译器表：翻译插槽的唯一归属是主干", name)
                    .isNull();
        }
    }
}

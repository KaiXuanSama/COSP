package com.kaixuan.copilot_ollama_proxy.provider.stage.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ChunkNormalizeStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ReasoningFallbackStage;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.GenericOpenAiChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 3.1 的<strong>装配验证</strong>：两个 Chat 支线真的被 Spring 收集并注入到 Chat 执行器了吗。
 *
 * <h2>为何这个「等价替换」必须单独验</h2>
 * 3.1 把调用点从「直接调静态工具」改成「优先调注入的支线、未注入则回退静态工具」。
 * 两条路<strong>按设计逐字等价</strong>（Chat 实现只转调那个静态工具），
 * 因此<strong>行为上完全观察不到差别</strong> —— 若 Spring 装配失败（漏了 {@code @Component}、
 * 扫描不到、{@code @Autowired} 写错、按单类型注入撞多候选），生产会静默退回静态工具：
 * 功能一切正常，而 3.1 的成果（具备被查表的资格）<strong>归零且无人察觉</strong>。
 *
 * <p>这正是本项目反复出现的形态：<em>两条路等价时，只有结构断言能验出装配断了</em>。
 * 故这里断言的是「注入确实发生」，而不是「行为正确」—— 后者由
 * {@code ChatStageImplementationTests} 的等价性用例与既有集成用例覆盖。
 *
 * <h2>为何断言要用反射读私有字段</h2>
 * 两条路等价 ⇒ 从公开 API 无法区分「注入了」与「回退到静态工具」。
 * 读私有字段是这里唯一能直接观察装配结果的手段。用 Spring 提供的
 * {@link ReflectionTestUtils} 而非手写反射：它按字段名取值、不依赖方法签名，
 * 且失败信息可读（该工具本就是为这类断言设计的）。
 * 代价是绑定了字段名 —— 若字段改名，本测试会失败，那正是应有的提醒（装配要重验）。
 */
@SpringBootTest(classes = CopilotOllamaProxyApplication.class)
class ChatStageSpringWiringTests {

    @Autowired
    private List<ChunkNormalizeStage> chunkNormalizeStages;

    @Autowired
    private List<ReasoningFallbackStage> reasoningFallbackStages;

    @Autowired
    private GenericOpenAiChatService genericOpenAiChatService;

    /** 构造新服务实例用的依赖（见 {@code StageSelection.freshService()}）。 */
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProviderRequestHeaderService providerRequestHeaderService;

    @Autowired
    private RequestBodyRuleEngine requestBodyRuleEngine;

    @Test
    @DisplayName("集合注入能收集到两个 Chat 支线，且各只有 CHAT 一个实现")
    void collectionInjectionCollectsBothChatStages() {
        assertThat(chunkNormalizeStages)
                .as("归一支线应被收集（@Component 在扫描范围内）")
                .hasSize(1);
        assertThat(reasoningFallbackStages)
                .as("fallback 支线应被收集")
                .hasSize(1);

        assertThat(chunkNormalizeStages.getFirst().protocol()).isEqualTo(WireProtocol.CHAT);
        assertThat(reasoningFallbackStages.getFirst().protocol()).isEqualTo(WireProtocol.CHAT);
    }

    /**
     * Chat 执行器确实拿到了注入的支线 —— 不是回退到了静态工具。
     *
     * <p>这条是本类存在的理由：装配失败时行为不变，只有这个断言会红。
     */
    @Test
    @DisplayName("Chat 执行器已注入两个支线（而非回退静态工具）")
    void chatExecutorReceivedInjectedStages() {
        assertThat(ReflectionTestUtils.getField(genericOpenAiChatService, "chunkNormalizeStage"))
                .as("未注入会静默回退静态工具，功能正常但 3.1 的成果归零")
                .isInstanceOf(ChatChunkNormalizeStage.class);
        assertThat(ReflectionTestUtils.getField(genericOpenAiChatService, "reasoningFallbackStage"))
                .isInstanceOf(ChatReasoningFallbackStage.class);
    }

    /**
     * 注入的支线可用 —— 且确实是 Chat 的那个实现。
     *
     * <p>与「字段非空」配对：防「注入了一个错误的实现」。这里只做冒烟级调用
     * （喂一帧、看它没抛且产出了字符串），逐字等价性由 {@code ChatStageImplementationTests} 覆盖。
     */
    @Test
    @DisplayName("注入的支线可直接调用")
    void injectedStagesAreUsable() {
        ChunkNormalizeStage normalize = (ChunkNormalizeStage) ReflectionTestUtils
                .getField(genericOpenAiChatService, "chunkNormalizeStage");
        ReasoningFallbackStage fallback = (ReasoningFallbackStage) ReflectionTestUtils
                .getField(genericOpenAiChatService, "reasoningFallbackStage");

        assertThat(normalize).isNotNull();
        assertThat(fallback).isNotNull();

        String frame = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";
        AtomicBoolean emitted = new AtomicBoolean(false);
        StringBuilder buffer = new StringBuilder();

        assertThat(normalize.normalize(frame, emitted, buffer, new AtomicReference<>("unknown")))
                .isNotBlank();
        // 无思考内容 → 不该触发回退。
        assertThat(fallback.shouldFallback(frame, emitted, buffer)).isFalse();
    }

    /**
     * 集合筛选的契约：只认 {@link WireProtocol#CHAT}，其余协议一律不选。
     *
     * <h2>为何现在就测（当前只有一个实现）</h2>
     * 现在生产中筛不筛都一样（只有 CHAT 一个实现），所以它测的是<strong>未来</strong>的契约：
     * 一旦 MESSAGES / RESPONSES 也补上实现，筛选若被删掉或写错，基类会挑中一个
     * 「读写 OpenAI 形态」的实现在别的协议上跑 —— 那正是支线机制要防的事。
     * 用测试里的 {@code MESSAGES} 替身提前把契约钉住，比等第二个实现出现时再补便宜得多。
     *
     * <h2>为何新建实例而不复用上下文里的那个</h2>
     * 直接改共享 Bean 的私有状态会污染 Spring 缓存上下文，影响同 JVM 里的其它测试类。
     * 构造器所需的两个依赖 Bean 从容器取，其余无关依赖（日志、用量、WebClient）
     * 本类不碰，故新建实例足够。
     */
    @Nested
    class StageSelection {

        /** 声明 MESSAGES 的替身 —— 只为验证「不会被选中」。 */
        private final class MessagesStub implements ChunkNormalizeStage {
            @Override
            public WireProtocol protocol() {
                return WireProtocol.MESSAGES;
            }

            @Override
            public String normalize(String chunkJson, AtomicBoolean contentEmitted,
                                    StringBuilder reasoningBuffer, AtomicReference<String> chunkId) {
                return "SHOULD_NOT_BE_USED";
            }
        }

        private GenericOpenAiChatService freshService() {
            return new GenericOpenAiChatService(objectMapper, providerRequestHeaderService, requestBodyRuleEngine);
        }

        /**
         * 容器里那个 CHAT 实现，当作「正确的那一个」参照。
         *
         * <p>从 {@code List} 取而不是单类型注入：本类的设计理由之一就是
         * 「用集合注入避免将来多实现时报多候选」，测试自己也该守这条 ——
         * 单类型注入会在第二个实现出现时以「测试装配失败」的形式先崩，
         * 而那与本测试要验的东西无关。
         */
        private ChunkNormalizeStage chatStageFromContext() {
            return chunkNormalizeStages.stream()
                    .filter(stage -> stage.protocol() == WireProtocol.CHAT)
                    .findFirst()
                    .orElseThrow();
        }

        @Test
        @DisplayName("混合列表下选中 CHAT 实现，而非先到的 MESSAGES")
        void picksChatFromMixedList() {
            GenericOpenAiChatService service = freshService();
            ChunkNormalizeStage chatStage = chatStageFromContext();

            // MESSAGES 替身排在前面：若筛选被删掉，findFirst 会拿到它。
            service.setChunkNormalizeStages(List.of(new MessagesStub(), chatStage));

            assertThat(ReflectionTestUtils.getField(service, "chunkNormalizeStage"))
                    .as("必须按协议筛选，不能取列表第一个")
                    .isSameAs(chatStage);
        }

        @Test
        @DisplayName("没有 CHAT 实现时保持 null —— 调用点回退静态工具")
        void leavesNullWhenNoChatImplementation() {
            GenericOpenAiChatService service = freshService();

            service.setChunkNormalizeStages(List.of(new MessagesStub()));

            assertThat(ReflectionTestUtils.getField(service, "chunkNormalizeStage"))
                    .as("筛不到即 null，调用点据此回退静态工具（另两条协议的预期行为）")
                    .isNull();
        }

        @Test
        @DisplayName("传入 null 列表不抛，同样保持 null")
        void toleratesNullList() {
            GenericOpenAiChatService service = freshService();

            service.setChunkNormalizeStages(null);

            assertThat(ReflectionTestUtils.getField(service, "chunkNormalizeStage")).isNull();
        }
    }
}

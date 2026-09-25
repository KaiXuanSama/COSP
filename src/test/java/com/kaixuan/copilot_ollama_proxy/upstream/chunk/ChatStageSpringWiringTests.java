package com.kaixuan.copilot_ollama_proxy.upstream.chunk;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.upstream.chunk.normalize.ChunkNormalizeStage;
import com.kaixuan.copilot_ollama_proxy.upstream.chunk.fallback.ReasoningFallbackStage;
import com.kaixuan.copilot_ollama_proxy.upstream.send.chat.GenericOpenAiChatService;
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
     * Chat 执行器确实拿到了查表入口 —— 不是绕开查表直接调静态工具。
     *
     * <p>这条是本类存在的理由（3.3d-2 后改写）：装配失败或接线被改回直调时行为不变，
     * 只有这个断言会红。
     */
    @Test
    @DisplayName("Chat 执行器已拿到查表入口（而非绕开查表直接调静态工具）")
    void chatExecutorHoldsTheRegistry() {
        assertThat(ReflectionTestUtils.getField(genericOpenAiChatService, "chunkStageRegistry"))
                .as("为 null 会让归一与 fallback 静默全跳过，而功能看起来仍然正常")
                .isNotNull();
    }

    /**
     * 注入的支线可用 —— 且确实是 Chat 的那个实现。
     *
     * <p>与「字段非空」配对：防「注入了一个错误的实现」。这里只做冒烟级调用
     * （喂一帧、看它没抛且产出了字符串），逐字等价性由 {@code ChatStageImplementationTests} 覆盖。
     */
    @Test
    @DisplayName("查到的支线可直接调用")
    void injectedStagesAreUsable() {
        ChunkStageRegistry registry = (ChunkStageRegistry) ReflectionTestUtils
                .getField(genericOpenAiChatService, "chunkStageRegistry");
        ChunkNormalizeStage normalize = registry.findNormalizer(WireProtocol.CHAT).orElseThrow();
        ReasoningFallbackStage fallback = registry.findFallback(WireProtocol.CHAT).orElseThrow();

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
     * 查表的契约：只认 {@link WireProtocol#CHAT}，其余协议一律查不到。
     *
     * <h2>为何现在就测（当前只有一个实现）</h2>
     * 现在生产上查不查都一样（只有 CHAT 一个实现），所以它测的是<strong>未来的契约</strong>：
     * 一旦 MESSAGES / RESPONSES 也补上实现，键若写错，主干会挑中一个
     * 「读写 OpenAI 形态」的实现在别的协议上跑 —— 那正是支线机制要防的事。
     * 用测试里的 {@code MESSAGES} 替身 + 空注册表提前把契约钉住，
     * 比等第二个实现出现时再补便宜得多。
     *
     * <h2>本组与 3.1 时期的差别</h2>
     * 3.1 时这里测的是「`setChunkNormalizeStages` 的硬编码 {@code filter(CHAT)} 是否写错」——
     * 那时筛在**注入时**做、键写死在基类里。3.3d-2 收掉了那个 setter 与硬编码筛选，
     * 改由 {@link ChunkStageRegistry} 按<strong>运行时键</strong>查表，
     * 于是这组用例改为直接验注册表本身。
     */
    @Nested
    class StageSelection {

        /** 声明 MESSAGES 的替身 —— 只为验证「查不到」。 */
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
        @DisplayName("按 CHAT 键查到 CHAT 实现，按 MESSAGES 键查到 MESSAGES 实现")
        void looksUpByProtocolKey() {
            ChunkNormalizeStage chatStage = chatStageFromContext();
            ChunkNormalizeStage messagesStub = new MessagesStub();
            // MESSAGES 替身排在前面：若按「列表第一个」实现，查 CHAT 会拿到它。
            ChunkStageRegistry registry = new ChunkStageRegistry(
                    List.of(messagesStub, chatStage), List.of());

            assertThat(registry.findNormalizer(WireProtocol.CHAT))
                    .as("必须按协议键查，不能取列表第一个")
                    .containsSame(chatStage);
            assertThat(registry.findNormalizer(WireProtocol.MESSAGES))
                    .as("两个键各自命中自己的实现 —— 这是「按键查」而非「按位置取」的证据")
                    .containsSame(messagesStub);
        }

        @Test
        @DisplayName("空注册表：任何协议都查不到 —— 调用点据此跳过本步骤")
        void emptyRegistryFindsNothing() {
            ChunkStageRegistry empty = new ChunkStageRegistry(List.of(), List.of());

            assertThat(empty.findNormalizer(WireProtocol.CHAT))
                    .as("跳过而非报错：这表达「该协议没有这一步」，是合法语义")
                    .isEmpty();
            assertThat(empty.findFallback(WireProtocol.CHAT)).isEmpty();
        }

        /**
         * 同一协议两个实现 → 建表时抛，而不是静默选一个。
         *
         * <p>与 {@code TranslatorRegistry} 同一取向：「哪个实现配哪个协议」是声明式事实，
         * 冲突意味着声明矛盾。若这条不成立，两个实现中「谁生效」会依赖
         * Spring 的收集顺序 —— 那是不可预测的。
         */
        @Test
        @DisplayName("同一协议两个实现抛 IllegalStateException")
        void duplicateProtocolThrows() {
            ChunkNormalizeStage duplicate = new ChunkNormalizeStage() {
                @Override
                public WireProtocol protocol() {
                    return WireProtocol.CHAT;
                }

                @Override
                public String normalize(String chunkJson, AtomicBoolean contentEmitted,
                                        StringBuilder reasoningBuffer, AtomicReference<String> chunkId) {
                    return chunkJson;
                }
            };

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ChunkStageRegistry(
                    List.of(chatStageFromContext(), duplicate), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CHAT")
                    .hasMessageContaining("两个实现");
        }
    }
}

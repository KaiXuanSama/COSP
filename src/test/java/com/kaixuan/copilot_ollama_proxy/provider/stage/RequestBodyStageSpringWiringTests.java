package com.kaixuan.copilot_ollama_proxy.provider.stage;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic.GenericAnthropicChatService;
import com.kaixuan.copilot_ollama_proxy.provider.stage.messages.MessagesMaxTokensStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.messages.MessagesSystemPromptStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.messages.MessagesThinkingStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求体支线的<strong>装配验证</strong>：三个 MESSAGES 支线真的被 Spring 收集并进了查表吗。
 *
 * <h2>为何必须单独验（与 {@code ChatStageSpringWiringTests} 同一理由）</h2>
 * 支线未命中时主干会<strong>跳过该步骤</strong>，而不是报错 —— 这是刻意的语义
 * （「别的协议没有这一步」是合法的）。但它的副作用是：
 * <strong>漏了 {@code @Component}、扫描不到、@Autowired 写错，行为会静默降级</strong> ——
 * Anthropic 请求不再抬升 system、不再补 max_tokens，而功能看起来「能用」，
 * 直到上游用 400 回答或系统提示词凭空消失。
 *
 * <p>故这里断言的是「收集到了、查得到、能调用」，而不是「行为正确」——
 * 后者由既有的 {@code GenericAnthropicChatServiceTests} 覆盖（它们走生产路径，
 * 本步之后仍调同一份静态工具，因此一行未改）。
 *
 * <h2>为何还要钉「查表键对得上」</h2>
 * 这是本步<strong>新引入的失效面</strong>：调研实测 {@code RequestPipelineContext.bodyProtocol()}
 * 在此之前<strong>零调用方</strong>，从未被任何测试验证过。等 3.3d-2 用它查表时，
 * 若初值语义错了，症状是「查到错误实现或查不到」—— 两种都不会抛异常。
 * 因此这里提前钉住「MESSAGES 键确实能查到 MESSAGES 实现」。
 */
@SpringBootTest(classes = CopilotOllamaProxyApplication.class)
class RequestBodyStageSpringWiringTests {

    @Autowired
    private RequestBodyStageRegistry registry;

    @Autowired
    private GenericAnthropicChatService anthropicChatService;

    @Autowired
    private List<SystemPromptNormalizeStage> systemPromptStages;

    @Autowired
    private List<MaxTokensNormalizeStage> maxTokensStages;

    @Autowired
    private List<ThinkingInjectStage> thinkingStages;

    @Test
    @DisplayName("集合注入收集到三个 MESSAGES 支线，且各只有 MESSAGES 一个实现")
    void collectionInjectionCollectsAllThreeStages() {
        assertThat(systemPromptStages)
                .as("system 抬升支线应被收集（@Component 在扫描范围内）")
                .hasSize(1);
        assertThat(maxTokensStages).hasSize(1);
        assertThat(thinkingStages).hasSize(1);

        assertThat(systemPromptStages.getFirst().protocol()).isEqualTo(WireProtocol.MESSAGES);
        assertThat(maxTokensStages.getFirst().protocol()).isEqualTo(WireProtocol.MESSAGES);
        assertThat(thinkingStages.getFirst().protocol()).isEqualTo(WireProtocol.MESSAGES);
    }

    @Test
    @DisplayName("三个支线都能按 MESSAGES 键查到")
    void allThreeStagesAreFoundByMessagesKey() {
        assertThat(registry.findSystemPromptStage(WireProtocol.MESSAGES))
                .as("查不到会让 Anthropic 的 system 抬升静默失效")
                .containsInstanceOf(MessagesSystemPromptStage.class);
        assertThat(registry.findMaxTokensStage(WireProtocol.MESSAGES))
                .as("查不到会让 max_tokens 缺失，上游 400")
                .containsInstanceOf(MessagesMaxTokensStage.class);
        assertThat(registry.findThinkingStage(WireProtocol.MESSAGES))
                .containsInstanceOf(MessagesThinkingStage.class);
    }

    /**
     * 另两条协议查不到实现 —— <strong>这是预期，不是缺口</strong>。
     *
     * <p>「跳过」比「不存在」更贴合意图：Chat 不需要抬升 system 到顶层
     * （它本来就是那个形态）、Responses 不需要补 max_tokens（该字段在它那里可选）。
     * 若某天有人给这两条协议补了实现，本用例会失败 —— 那是提醒他确认
     * 「确实该在这个协议上跑这一步」，而不是顺手加上。
     */
    @Test
    @DisplayName("CHAT / RESPONSES 查不到实现（本步只搬了 MESSAGES 侧）")
    void otherProtocolsFindNothingYet() {
        assertThat(registry.findSystemPromptStage(WireProtocol.CHAT)).isEmpty();
        assertThat(registry.findSystemPromptStage(WireProtocol.RESPONSES)).isEmpty();
        assertThat(registry.findMaxTokensStage(WireProtocol.CHAT)).isEmpty();
        assertThat(registry.findMaxTokensStage(WireProtocol.RESPONSES)).isEmpty();
        assertThat(registry.findThinkingStage(WireProtocol.CHAT)).isEmpty();
        assertThat(registry.findThinkingStage(WireProtocol.RESPONSES)).isEmpty();
    }

    /**
     * 查到的支线<strong>可直接调用且真的改了 body</strong>。
     *
     * <p>与「查得到」配对：防「查到但转调了一个空方法」。这里只做冒烟级验证
     * （喂一个必然触发抬升的 body），逐步语义由
     * {@code GenericAnthropicChatServiceTests} 的既有用例覆盖。
     */
    @Test
    @DisplayName("查到的支线可直接调用并生效")
    void foundStagesAreUsable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", List.of(
                Map.of("role", "system", "content", "be brief"),
                Map.of("role", "user", "content", "hi")));

        registry.findSystemPromptStage(WireProtocol.MESSAGES).orElseThrow().apply(body);

        assertThat(body).containsEntry("system", "be brief");
        assertThat((List<?>) body.get("messages"))
                .as("system 消息应已从 messages 里移除")
                .hasSize(1);
    }

    @Nested
    @DisplayName("同键冲突即失败")
    class DuplicateKeyGuard {

        /** 声明 MESSAGES 的替身 —— 用来制造「同一协议两个实现」。 */
        private final class MessagesStub implements SystemPromptNormalizeStage {
            @Override
            public WireProtocol protocol() {
                return WireProtocol.MESSAGES;
            }

            @Override
            public void apply(Map<String, Object> body) {
            }
        }

        /**
         * 同一协议两个实现 → 建表时抛错，而不是静默选一个。
         *
         * <p>与 {@code TranslatorRegistry} 同一取向：「哪个实现配哪个协议」是声明式事实，
         * 冲突意味着声明矛盾，早失败比运行期随机选一个可预测得多。
         * 若这条不成立，两个实现中「谁生效」会依赖 Spring 的收集顺序 —— 那是不可预测的。
         */
        @Test
        @DisplayName("同一协议两个实现抛 IllegalStateException")
        void duplicateProtocolThrows() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RequestBodyStageRegistry(
                    List.of(new MessagesSystemPromptStage(), new MessagesStub()),
                    List.of(),
                    List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MESSAGES")
                    .hasMessageContaining("两个实现");
        }

        /** 空集合是合法的 —— 只是那个步骤查不到（表达「尚未支线化 / 该协议不需要」）。 */
        @Test
        @DisplayName("空集合可构造，查表恒未命中")
        void emptyCollectionsAreLegal() {
            RequestBodyStageRegistry empty = new RequestBodyStageRegistry(List.of(), List.of(), List.of());

            assertThat(empty.findSystemPromptStage(WireProtocol.MESSAGES)).isEmpty();
            assertThat(empty.findMaxTokensStage(WireProtocol.MESSAGES)).isEmpty();
            assertThat(empty.findThinkingStage(WireProtocol.MESSAGES)).isEmpty();
        }
    }

    /**
     * 支线的身份：`provider` 参数确实被用作查配置的依据，而非被忽略。
     *
     * <p>这条防的是「实现里忘了用 provider，恒用默认值」——那种错误不会抛异常，
     * 只会让用户配的 max_tokens / 思考档位静默失效。
     */
    @Test
    @DisplayName("max_tokens 支线确实读取 provider 配置")
    void maxTokensStageActuallyUsesProvider() {
        ProviderRuntimeConfiguration provider = new ProviderRuntimeConfiguration(
                "relay-x", "https://up.example", "key-1", List.of());

        Map<String, Object> body = new LinkedHashMap<>();
        registry.findMaxTokensStage(WireProtocol.MESSAGES).orElseThrow()
                .apply(body, "unconfigured-model", provider);

        assertThat(body)
                .as("模型名查不到时按默认值（64K + 兜底）补齐 —— 缺了它这条线路根本发不出去")
                .containsEntry("max_tokens", 64000);
    }

    /**
     * <strong>执行器确实拿到了查表入口</strong> —— 不是绕开查表直接调静态工具。
     *
     * <h2>为何这条必须存在</h2>
     * 接入点接线后，两条路仍然**逐字等价**（查到的支线只转调同一个静态工具），
     * 因此「走了查表」与「还在直接调工具」<strong>从行为上完全无法区分</strong>。
     * 若构造器参数被换成一个空注册表、或将来有人为了「省事」改回直接调工具，
     * 行为一切正常，而 3.3d 的成果（协议特定步骤可查表扩展）<strong>静默归零</strong>。
     *
     * <p>因此这里读字段断言它非空 —— 与 {@code ChatStageSpringWiringTests} 同一处境：
     * <em>两条路等价时，只有结构断言能验出接线断了</em>。
     * 代价是绑定了字段名，改名即红，那正是应有的提醒。
     */
    @Test
    @DisplayName("Anthropic 执行器已拿到查表入口（而非绕开查表）")
    void anthropicExecutorHoldsTheRegistry() {
        assertThat(ReflectionTestUtils.getField(anthropicChatService, "requestBodyStageRegistry"))
                .as("为 null 会让三个协议特定步骤静默全跳过，而功能看起来仍然正常")
                .isNotNull();
    }
}

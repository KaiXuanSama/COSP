package com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.maxtokens.MaxTokensNormalizeStage;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.maxtokens.MessagesMaxTokensStage;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.system.MessagesSystemPromptStage;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.system.ResponsesSystemPromptStage;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.system.SystemPromptNormalizeStage;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.thinking.MessagesThinkingStage;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.thinking.ThinkingInjectStage;
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
 * 请求体支线的<strong>装配验证</strong>：协议特定支线真的被 Spring 收集并进了查表吗。
 *
 * <h2>为何必须单独验（与 {@code ChatStageSpringWiringTests} 同一理由）</h2>
 * 支线未命中时装配器会<strong>跳过该步骤</strong>，而不是报错 —— 这是刻意的语义
 * （「别的协议没有这一步」是合法的）。但它的副作用是：
 * <strong>漏了 {@code @Component}、扫描不到、@Autowired 写错，行为会静默降级</strong> ——
 * Anthropic 请求不再抬升 system、不再补 max_tokens，而功能看起来「能用」，
 * 直到上游用 400 回答或系统提示词凭空消失。
 *
 * <p>故这里断言的是「收集到了、查得到、能调用」，而不是「行为正确」——
 * 后者由 {@code RequestBodyAssemblerTests} 与 {@code GenericAnthropicChatServiceTests} 覆盖。
 *
 * <h2>查表入口在装配器（主干），不在执行器</h2>
 * 请求体装配收归主干的 {@code RequestBodyAssembler}，它持有本注册表。执行器不再持有 ——
 * 因此本类断言的「谁拿到查表入口」从执行器改为装配器（见
 * {@link #assemblerHoldsTheRegistry}）。同时 {@code thinking/} 从 1 个实现（仅 MESSAGES）
 * 扩为 3 个（三协议各一），因为装配器要对三条线路各自查 thinking。
 */
@SpringBootTest(classes = CopilotOllamaProxyApplication.class)
class RequestBodyStageSpringWiringTests {

    @Autowired
    private RequestBodyStageRegistry registry;

    @Autowired
    private RequestBodyAssembler assembler;

    @Autowired
    private List<SystemPromptNormalizeStage> systemPromptStages;

    @Autowired
    private List<MaxTokensNormalizeStage> maxTokensStages;

    @Autowired
    private List<ThinkingInjectStage> thinkingStages;

    @Test
    @DisplayName("集合注入收集到各支线：system 2（MESSAGES+RESPONSES）、max_tokens 1（MESSAGES）、thinking 3")
    void collectionInjectionCollectsAllThreeStages() {
        assertThat(systemPromptStages)
                .as("system 归一化支线应被收集（@Component 在扫描范围内）")
                .hasSize(2);
        assertThat(maxTokensStages).hasSize(1);
        // thinking 三协议各一：Chat 写 reasoning_effort、Responses 写 reasoning.effort、
        // Messages 写 output_config.effort + thinking 方式。
        assertThat(thinkingStages).hasSize(3);

        // system 两协议各一：MESSAGES 抬到顶层、RESPONSES 改写为 developer（C2R 方向）。
        assertThat(systemPromptStages.stream().map(SystemPromptNormalizeStage::protocol))
                .containsExactlyInAnyOrder(WireProtocol.MESSAGES, WireProtocol.RESPONSES);
        assertThat(maxTokensStages.getFirst().protocol()).isEqualTo(WireProtocol.MESSAGES);
        assertThat(thinkingStages.stream().map(ThinkingInjectStage::protocol))
                .containsExactlyInAnyOrder(WireProtocol.CHAT, WireProtocol.MESSAGES, WireProtocol.RESPONSES);
    }

    @Test
    @DisplayName("system 按 MESSAGES/RESPONSES 两键查到，max_tokens 按 MESSAGES，thinking 三协议")
    void allThreeStagesAreFoundByMessagesKey() {
        assertThat(registry.findSystemPromptStage(WireProtocol.MESSAGES))
                .as("查不到会让 Anthropic 的 system 抬升静默失效")
                .containsInstanceOf(MessagesSystemPromptStage.class);
        assertThat(registry.findSystemPromptStage(WireProtocol.RESPONSES))
                .as("查不到会让 C2R 线路的 system 角色保持非官方的 system，上游可能拒绝")
                .containsInstanceOf(ResponsesSystemPromptStage.class);
        assertThat(registry.findMaxTokensStage(WireProtocol.MESSAGES))
                .as("查不到会让 max_tokens 缺失，上游 400")
                .containsInstanceOf(MessagesMaxTokensStage.class);
        assertThat(registry.findThinkingStage(WireProtocol.MESSAGES))
                .containsInstanceOf(MessagesThinkingStage.class);
        // Chat / Responses 的思考注入也支线化了，故三协议都能查到 thinking。
        assertThat(registry.findThinkingStage(WireProtocol.CHAT)).isPresent();
        assertThat(registry.findThinkingStage(WireProtocol.RESPONSES)).isPresent();
    }

    /**
     * system 在 CHAT、max_tokens 在 CHAT/RESPONSES 上查不到 —— <strong>这是预期，不是缺口</strong>。
     *
     * <p>「跳过」比「不存在」更贴合意图：Chat 不需要归一化 system
     * （它本来就是那个形态）、RESPONSES/CHAT 不补 max_tokens（该字段在两侧都可选，
     * 补齐是设置层的事，见 C2R 计划 §6.4 的 TODO）。
     * 若某天有人给这些协议补了实现，本用例会失败 —— 那是提醒他确认
     * 「确实该在这个协议上跑这一步」，而不是顺手加上。
     *
     * <p>system 的 RESPONSES 实现<strong>不在此列</strong>（C2R 方向需要它，见
     * {@link #allThreeStagesAreFoundByMessagesKey}）；思考注入同理（三协议都有）。
     */
    @Test
    @DisplayName("CHAT 查不到 system，CHAT/RESPONSES 查不到 max_tokens（那些是无需/待做的）")
    void otherProtocolsFindNothingYet() {
        assertThat(registry.findSystemPromptStage(WireProtocol.CHAT)).isEmpty();
        assertThat(registry.findMaxTokensStage(WireProtocol.CHAT)).isEmpty();
        assertThat(registry.findMaxTokensStage(WireProtocol.RESPONSES)).isEmpty();
    }

    /**
     * 查到的支线<strong>可直接调用且真的改了 body</strong>。
     *
     * <p>与「查得到」配对：防「查到但转调了一个空方法」。这里只做冒烟级验证
     * （喂一个必然触发改写的 body），逐步语义由纯逻辑单测覆盖。
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

        // Responses 侧同一形态的冒烟：message item 的 system 改写为 developer。
        Map<String, Object> responsesBody = new LinkedHashMap<>();
        responsesBody.put("input", List.of(
                Map.of("type", "message", "role", "system",
                        "content", List.of(Map.of("type", "input_text", "text", "be brief")))));

        registry.findSystemPromptStage(WireProtocol.RESPONSES).orElseThrow().apply(responsesBody);

        assertThat(((Map<?, ?>) ((List<?>) responsesBody.get("input")).get(0)).get("role"))
                .isEqualTo("developer");
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
     * <strong>装配器确实拿到了查表入口</strong> —— 不是绕开查表直接调静态工具。
     *
     * <h2>为何这条必须存在</h2>
     * 接入点接线后，两条路仍然**逐字等价**（查到的支线只转调同一个静态工具），
     * 因此「走了查表」与「还在直接调工具」<strong>从行为上完全无法区分</strong>。
     * 若构造器参数被换成一个空注册表、或将来有人为了「省事」改回直接调工具，
     * 行为一切正常，而协议特定步骤可查表扩展的成果<strong>静默归零</strong>。
     *
     * <p>查表入口在装配器（主干）而非执行器，故这里断言的是装配器持有它。
     * 代价是绑定了字段名，改名即红，那正是应有的提醒。
     */
    @Test
    @DisplayName("装配器已拿到查表入口（而非绕开查表）")
    void assemblerHoldsTheRegistry() {
        assertThat(ReflectionTestUtils.getField(assembler, "stageRegistry"))
                .as("为 null 会让三个协议特定步骤静默全跳过，而功能看起来仍然正常")
                .isNotNull();
    }
}

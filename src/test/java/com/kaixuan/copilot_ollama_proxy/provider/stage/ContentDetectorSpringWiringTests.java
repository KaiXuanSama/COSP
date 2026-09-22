package com.kaixuan.copilot_ollama_proxy.provider.stage;

import com.kaixuan.copilot_ollama_proxy.CopilotOllamaProxyApplication;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic.GenericAnthropicChatService;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.GenericOpenAiChatService;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.GenericResponsesChatService;
import com.kaixuan.copilot_ollama_proxy.provider.stage.chat.ChatContentDetectorStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.messages.MessagesContentDetectorStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.responses.ResponsesContentDetectorStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内容检测器支线的<strong>装配验证</strong>（阶段 3.6a）与<strong>接线验证</strong>（阶段 3.6b）。
 *
 * <h2>为何必须单独验</h2>
 * 与 {@code RequestBodyStageSpringWiringTests} / {@code ChatStageSpringWiringTests} 同一处境：
 * 支线断了<strong>不会响</strong> —— 查不到检测器时「空响应判定失效」而功能看起来正常。
 *
 * <p>3.6a 只让支线成形（调用点仍在直调静态工具），那时能验的只有「新类型存在且被 Spring 收集」。
 * 3.6b 把判定机制收归 {@code EmptyResponseGate} 之后接上了线，于是本类又多了两件可验的事：
 * 「三个执行器都持有查表入口」与「主干确实用了它」（见类尾那两条）。
 *
 * <p>而它与另两个注册表有一处<strong>关键不同</strong>：本表的未命中是
 * <strong>报错</strong>而非跳过。因此「三个协议都注册齐」不是锦上添花，
 * 而是<strong>主干能跑的前提</strong> —— 少一个会让那条线路在运行期抛
 * {@code IllegalStateException}，而症状看起来像「程序接线错误」而非「配置问题」。
 *
 * <h2>这里还钉住一件最容易错的事：方法映射是「反的」</h2>
 * Chat 的静态工具里，{@code hasMeaningfulPayload} 其实读 {@code delta}（流式），
 * 而非流式那个叫 {@code hasMeaningfulNonStreamPayload}。
 * 三个实现类转调时必须<strong>按取值字段映射</strong>而不是按名字 ——
 * 接错不会编译失败（签名相同），症状是「某条线路的空响应判定失效」。
 *
 * <p>本测试用一组<strong>只有流式形态</strong>与<strong>只有非流式形态</strong>的报文
 * 分别喂两个入口，从而钉住映射方向：若把两者接反，这两条会立刻失败。
 */
@SpringBootTest(classes = CopilotOllamaProxyApplication.class)
class ContentDetectorSpringWiringTests {

    @Autowired
    private ContentDetectorRegistry registry;

    @Autowired
    private List<ContentDetectorStage> detectors;

    @Autowired
    private GenericOpenAiChatService genericOpenAiChatService;

    @Autowired
    private GenericAnthropicChatService anthropicChatService;

    @Autowired
    private GenericResponsesChatService responsesChatService;

    @Test
    @DisplayName("集合注入收集到三个协议的检测器，各只有一个实现")
    void collectionInjectionCollectsAllThree() {
        assertThat(detectors)
                .as("三个上游协议各需要一个检测器 —— 少一个会让那条线路在运行期抛装配错误")
                .hasSize(3);
        assertThat(detectors).extracting(ContentDetectorStage::protocol)
                .containsExactlyInAnyOrder(WireProtocol.CHAT, WireProtocol.MESSAGES,
                        WireProtocol.RESPONSES);
    }

    @Test
    @DisplayName("三个协议都能查到检测器")
    void allThreeProtocolsAreFound() {
        assertThat(registry.require(WireProtocol.CHAT)).isInstanceOf(ChatContentDetectorStage.class);
        assertThat(registry.require(WireProtocol.MESSAGES)).isInstanceOf(MessagesContentDetectorStage.class);
        assertThat(registry.require(WireProtocol.RESPONSES))
                .isInstanceOf(ResponsesContentDetectorStage.class);
    }

    /**
     * 未命中是<strong>报错</strong>，不是返回 null 或跳过。
     *
     * <p>理由：每个上游协议都必须能判空。判不了意味着空响应兜底对那条线路失效，
     * 而那是保护用户的功能 —— 没有「这种协议不需要判空」的领域解释。
     * 用空注册表构造即可验到（比手工 mock 一个缺项的容器更直接）。
     */
    @Test
    @DisplayName("未命中抛 IllegalStateException（不是静默跳过）")
    void missThrowsInsteadOfSkipping() {
        ContentDetectorRegistry empty = new ContentDetectorRegistry(List.of());

        assertThatThrownBy(() -> empty.require(WireProtocol.CHAT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT")
                .hasMessageContaining("装配错误");
    }

    /** 同一协议两个实现 → 启动即失败，不静默择一。 */
    @Test
    @DisplayName("同协议两个实现抛 IllegalStateException")
    void duplicateProtocolThrows() {
        ContentDetectorStage duplicate = new ContentDetectorStage() {
            @Override
            public WireProtocol protocol() {
                return WireProtocol.CHAT;
            }

            @Override
            public boolean hasMeaningfulPayload(String fullBody) {
                return true;
            }

            @Override
            public boolean eventHasPayload(String eventData) {
                return true;
            }
        };

        assertThatThrownBy(() -> new ContentDetectorRegistry(List.of(
                registry.require(WireProtocol.CHAT), duplicate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT")
                .hasMessageContaining("两个实现");
    }

    @Nested
    @DisplayName("方法映射方向（防「名字骗人」接反）")
    class MappingDirection {

        /**
         * 非流式：只有 {@code choices[].message} 带内容。
         *
         * <p>{@code message} 里有正文、{@code delta} 为空 —— 若实现把非流式入口接到了
         * 读 {@code delta} 的那个方法上，本断言会失败。
         */
        @Test
        @DisplayName("Chat 非流式入口读 choices[].message")
        void chatNonStreamReadsMessage() {
            String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}";

            assertThat(registry.require(WireProtocol.CHAT).hasMeaningfulPayload(body)).isTrue();
        }

        /**
         * 流式：只有 {@code choices[].delta} 带内容。
         *
         * <p>这正是那个坑的判据 —— {@code OpenAiContentDetector.hasMeaningfulPayload}
         * （名字不带 NonStream）读的是 {@code delta}。若实现把它接到了非流式入口，
         * 本断言会失败。
         */
        @Test
        @DisplayName("Chat 流式入口读 choices[].delta")
        void chatStreamReadsDelta() {
            String frame = "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}";

            assertThat(registry.require(WireProtocol.CHAT).eventHasPayload(frame)).isTrue();
        }

        /**
         * 两个入口对<strong>对方的形态</strong>都应判空（结构不匹配 → choices 里无载荷）。
         *
         * <p>与上面两条配对：那两条证明「接对了」，这条证明「确实读的是不同字段」——
         * 若两个入口都转调到同一个方法，四条里至少一条会失败。
         */
        @Test
        @DisplayName("Chat 两个入口读的是不同字段（交叉判空）")
        void chatEntriesReadDifferentFields() {
            String nonStreamOnly = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}";
            String streamOnly = "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}";
            ContentDetectorStage chat = registry.require(WireProtocol.CHAT);

            assertThat(chat.hasMeaningfulPayload(streamOnly))
                    .as("非流式入口不该从 delta 取到内容")
                    .isFalse();
            assertThat(chat.eventHasPayload(nonStreamOnly))
                    .as("流式入口不该从 message 取到内容")
                    .isFalse();
        }

        /** Anthropic 非流式读 {@code content[]}，流式按事件类型分派。 */
        @Test
        @DisplayName("MESSAGES 两个入口各按自己的取值路径")
        void messagesEntriesUseTheirOwnPaths() {
            ContentDetectorStage messages = registry.require(WireProtocol.MESSAGES);

            assertThat(messages.hasMeaningfulPayload(
                    "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}"))
                    .as("非流式：content[] 里的 text 块")
                    .isTrue();
            assertThat(messages.eventHasPayload(
                    "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}"))
                    .as("流式：content_block_delta 贡献载荷")
                    .isTrue();
            assertThat(messages.eventHasPayload("{\"type\":\"message_start\"}"))
                    .as("message_start 只有元信息，不贡献载荷 —— "
                            + "若这里判 true，正常响应的头两个事件会被判成空")
                    .isFalse();
        }

        /** Responses 非流式读 {@code output[]}，流式按事件类型分派。 */
        @Test
        @DisplayName("RESPONSES 两个入口各按自己的取值路径")
        void responsesEntriesUseTheirOwnPaths() {
            ContentDetectorStage responses = registry.require(WireProtocol.RESPONSES);

            assertThat(responses.hasMeaningfulPayload(
                    "{\"output\":[{\"type\":\"message\",\"content\":"
                            + "[{\"type\":\"output_text\",\"text\":\"hi\"}]}]}"))
                    .as("非流式：output[] 里的 output_text")
                    .isTrue();
            assertThat(responses.eventHasPayload(
                    "{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}"))
                    .as("流式：output_text.delta 贡献载荷")
                    .isTrue();
        }
    }

    /**
     * 三个执行器<strong>都拿到了查表入口</strong> —— 不是绕开查表直接调静态工具（阶段 3.6b 新增）。
     *
     * <h2>为何 3.6a 时测不了、现在才测</h2>
     * 3.6a 只让支线成形，调用点仍在直调静态工具，那时「执行器持有注册表」这件事
     * <strong>根本不存在</strong>。3.6b 把判定机制收归 {@code EmptyResponseGate} 之后，
     * 检测器改为<strong>按上游协议查表取得</strong>，这条接线才有了可断之处。
     *
     * <h2>这条断言防什么</h2>
     * 与前几个装配类同一处境，但后果更重：若某条线路漏接（字段为 null、或改回直调静态工具），
     * <strong>行为完全不变</strong> —— 空响应照样拦、照样重试、测试全绿。
     * 唯一变化是「三条线路是否真的同源」这个结构事实被破坏，
     * 于是下次修空响应兜底又要<strong>分三处改</strong>（正是 {@code dc1c4fc} 那次事故的形状）。
     *
     * <p>绑字段名是有意的：字段改名会让本测试失败，那正是应有的提醒 —— 接线要重验。
     */
    @Test
    @DisplayName("三个执行器都已拿到查表入口（而非绕开查表直接调静态工具）")
    void allThreeExecutorsHoldTheRegistry() {
        assertThat(ReflectionTestUtils.getField(genericOpenAiChatService, "contentDetectorRegistry"))
                .as("Chat：为 null 会让这条线路的空响应判定失效，而功能看起来仍然正常")
                .isNotNull();
        assertThat(ReflectionTestUtils.getField(anthropicChatService, "contentDetectorRegistry"))
                .as("MESSAGES：同上")
                .isNotNull();
        assertThat(ReflectionTestUtils.getField(responsesChatService, "contentDetectorRegistry"))
                .as("RESPONSES：同上")
                .isNotNull();
    }
}

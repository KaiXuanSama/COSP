package com.kaixuan.copilot_ollama_proxy.upstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UpstreamEventClassifier} 的终止识别，以及 {@link UpstreamEvent} 两态的基本契约。
 *
 * <h2>为何这些用例值得存在</h2>
 * 本步把「这一帧是不是说完了」从三处<strong>字符串模式匹配</strong>收成一个类型，
 * 判据此前散在三个控制器里、没有任何直接单测 —— 它们只被流式端到端用例间接覆盖，
 * 而那类用例喂的是 happy path，认不出「某个协议漏了一条终止形态」。
 *
 * <p>漏判的后果不是报错而是<strong>静默</strong>：控制器不触发收尾，
 * 下游 Toast 永远悬挂在 CHUNK 上，直到连接关闭才靠 Layer 2 兜底 ——
 * 而如果上游保持 keep-alive 不开连接，它就永远不结束。
 *
 * <h2>三个协议的终止形态互不相同，一个都不能漏</h2>
 * 这是本类存在的全部理由：Chat 是个独立帧、Anthropic 是个事件类型、
 * Responses 是<strong>一组</strong>终态事件。用同一个判据套三个协议必然漏。
 */
class UpstreamEventClassifierTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    @DisplayName("Chat 协议的终止帧")
    class ChatTerminal {

        /**
         * {@code [DONE]} 判为终止。
         *
         * <p>它是独立帧、不与内容帧同形 —— 判据是整串相等而非包含，
         * 因此一个恰好含有 {@code DONE} 字样的正文不会被误判。
         */
        @Test
        void doneMarkerIsTerminal() {
            assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.CHAT, "[DONE]")).isTrue();
            assertThat(UpstreamEventClassifier.classify(MAPPER, WireProtocol.CHAT, "[DONE]"))
                    .isInstanceOf(UpstreamEvent.Terminal.class);
        }

        @Test
        void contentChunkIsNotTerminal() {
            String chunk = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}";

            assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.CHAT, chunk)).isFalse();
            assertThat(UpstreamEventClassifier.classify(MAPPER, WireProtocol.CHAT, chunk))
                    .isInstanceOf(UpstreamEvent.Body.class);
        }

        @Test
        void contentContainingDoneWordIsNotTerminal() {
            String chunk = "{\"choices\":[{\"delta\":{\"content\":\"the job is DONE\"}}]}";

            assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.CHAT, chunk)).isFalse();
        }

        /** 终止标记即使被空白包着也认 —— 上游偶尔会多发一个换行。 */
        @Test
        void doneMarkerWithSurroundingWhitespaceIsNotTerminal() {
            // 判据是整串相等，故带空白的形态判否。这条钉住当前口径，
            // 若将来放宽为 trim 后比较，这里需要同步改。
            assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.CHAT, " [DONE] ")).isFalse();
        }
    }

    @Nested
    @DisplayName("Anthropic 协议的终止事件")
    class MessagesTerminal {

        /**
         * {@code message_stop} 判为终止。
         *
         * <p>与 Chat 不同，它藏在带 {@code type} 的 JSON 里 ——
         * 必须解析而非整串比较。
         */
        @Test
        void messageStopIsTerminal() {
            String event = "{\"type\":\"message_stop\"}";

            assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.MESSAGES, event)).isTrue();
            assertThat(UpstreamEventClassifier.classify(MAPPER, WireProtocol.MESSAGES, event))
                    .isInstanceOf(UpstreamEvent.Terminal.class);
        }

        /**
         * 其余 Anthropic 事件都不是终止 —— 尤其 {@code message_delta}。
         *
         * <p>它名字里带 {@code message_}，且是倒数第二个事件，容易被误认成收尾；
         * 但它携带 {@code stop_reason} 与 {@code usage}，之后还有 {@code message_stop}。
         * 误判会让最后一帧内容被丢掉。
         */
        @Test
        void otherEventsAreNotTerminal() {
            for (String type : new String[]{"message_start", "content_block_start", "content_block_delta",
                    "content_block_stop", "message_delta", "ping"}) {
                String event = "{\"type\":\"" + type + "\"}";

                assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.MESSAGES, event))
                        .as("事件 %s 不该被判成终止", type)
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("Responses 协议的终态事件")
    class ResponsesTerminal {

        /**
         * 终态是一<strong>组</strong>事件而非单个标记 —— 这是三个协议里最易漏的一处。
         *
         * <p>{@code response.failed} / {@code .incomplete} / {@code .canceled} 同样是
         * 流的结束，只是结局不同。只认 {@code response.completed} 会让失败流
         * 永远不触发收尾。
         */
        @Test
        void allTerminalEventTypesAreRecognized() {
            for (String type : new String[]{"response.completed", "response.failed",
                    "response.incomplete", "response.canceled"}) {
                String event = "{\"type\":\"" + type + "\"}";

                assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.RESPONSES, event))
                        .as("终态事件 %s 必须被判为终止", type)
                        .isTrue();
            }
        }

        @Test
        void deltaEventIsNotTerminal() {
            String event = "{\"type\":\"response.output_text.delta\",\"delta\":\"a\"}";

            assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.RESPONSES, event)).isFalse();
        }
    }

    @Nested
    @DisplayName("协议之间不串味")
    class ProtocolIsolation {

        /**
         * 同一份报文按不同协议判会得到不同结论 —— 这正是「按协议分派」的意义。
         *
         * <p>{@code [DONE]} 在 Chat 下是终止，在另两个协议下不是（它们的事件带 type）。
         * 若判据不接协议参数、只看内容，这个区别就表达不出来。
         */
        @Test
        void samePayloadClassifiedDifferentlyPerProtocol() {
            assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.CHAT, "[DONE]")).isTrue();
            assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.MESSAGES, "[DONE]")).isFalse();
            assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.RESPONSES, "[DONE]")).isFalse();
        }

        /**
         * Anthropic 的终止事件在 Chat 协议下<strong>不是</strong>终止。
         *
         * <p>这条钉住「谁生产帧谁分类」：一个 MESSAGES 事件被当成 Chat 帧时
         * （半轮实现态下确实会发生），不该触发 Chat 的收尾。
         */
        @Test
        void messagesTerminalIsNotChatTerminal() {
            String event = "{\"type\":\"message_stop\"}";

            assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.MESSAGES, event)).isTrue();
            assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.CHAT, event)).isFalse();
        }
    }

    @Nested
    @DisplayName("解析失败与异常输入保守判否")
    class ConservativeFallback {

        /**
         * 解析失败判<strong>否</strong>（视为载荷）。
         *
         * <p>两个方向的代价不对称：误判成终止会让下游<strong>提前收尾并丢掉后续内容</strong>，
         * 误判成载荷最多是多下发一帧。取保守的那一边。
         */
        @Test
        void unparsableEventIsNotTerminal() {
            for (WireProtocol protocol : WireProtocol.values()) {
                assertThat(UpstreamEventClassifier.isTerminal(MAPPER, protocol, "not json"))
                        .as("协议 %s 下解析失败应判否", protocol)
                        .isFalse();
            }
        }

        /** 缺 {@code type} 的合法 JSON 也判否。 */
        @Test
        void eventWithoutTypeIsNotTerminal() {
            String noType = "{\"id\":\"x\"}";

            assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.MESSAGES, noType)).isFalse();
            assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.RESPONSES, noType)).isFalse();
        }

        /** {@code type} 不是字符串时判否（不抛异常）。 */
        @Test
        void nonTextualTypeIsNotTerminal() {
            String numericType = "{\"type\":123}";

            assertThat(UpstreamEventClassifier.isTerminal(MAPPER, WireProtocol.MESSAGES, numericType)).isFalse();
        }

        /** null 报文判否 —— 上游可能发出 data 为空的帧。 */
        @Test
        void nullDataIsNotTerminal() {
            for (WireProtocol protocol : WireProtocol.values()) {
                assertThat(UpstreamEventClassifier.isTerminal(MAPPER, protocol, null)).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("两态契约")
    class EventContract {

        /**
         * {@link UpstreamEvent.Terminal} 携带原文 —— 下游状态机靠它收尾。
         *
         * <p>若做成无载荷哨兵，下发时就得再构造一次原文，
         * 等于把协议知识从「检测到它的人」手里拿走。
         */
        @Test
        void terminalCarriesItsRawPayload() {
            UpstreamEvent terminal = UpstreamEventClassifier.classify(MAPPER, WireProtocol.CHAT, "[DONE]");

            assertThat(terminal.isTerminal()).isTrue();
            assertThat(terminal.data()).isEqualTo("[DONE]");
        }

        @Test
        void bodyIsNotTerminal() {
            UpstreamEvent body = UpstreamEvent.body("{\"x\":1}");

            assertThat(body.isTerminal()).isFalse();
            assertThat(body.data()).isEqualTo("{\"x\":1}");
        }

        /**
         * 两态都不接受 null 载荷。
         *
         * <p>允许 null 会把「空帧」这个上游故障变成下游的 NPE，
         * 而它的正确处置是判空响应后重试 —— 在构造处就挡住，问题会早一步暴露。
         */
        @Test
        void bothStatesRejectNullPayload() {
            assertThatThrownBy(() -> UpstreamEvent.body(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> UpstreamEvent.terminal(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}

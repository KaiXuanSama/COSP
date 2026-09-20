package com.kaixuan.copilot_ollama_proxy.provider.stage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReasoningFallback} 的触发判定与伪 chunk 构造。
 *
 * <h2>为何这些用例值得存在</h2>
 * 本类取代的是 {@code AbstractUpstreamChatService} 的 {@code concatMap} 里
 * <strong>内联</strong>的一段判定 + 两个私有构造方法。那段判定此前没有直接单测 ——
 * 它只能通过「喂一条完整流、断言输出帧」间接观察，而那需要起上游 stub。
 *
 * <p>触发判定有四个条件，<strong>每一个都有「单独为真但不该触发」的反例</strong>，
 * 而漏判与误判的症状完全不同：
 * <ul>
 *   <li><strong>漏判</strong>（该回退没回退）—— 下游看到空白回复，用户以为模型没输出；</li>
 *   <li><strong>误判</strong>（不该回退却回退）—— 一次正常的工具调用响应末尾被凭空插入
 *       一段思考内容当正文，下游 agent 拿到一段无人要求的文字。</li>
 * </ul>
 * 因此每个条件都从「成立」与「不成立」两侧钉。
 */
class ReasoningFallbackTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 造一个 {@code finish_reason} 为指定值的终止 chunk（已清洗形态）。 */
    private static String finishChunk(String finishReason) {
        return "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + finishReason + "\"}]}";
    }

    private static AtomicBoolean contentEmitted(boolean value) {
        return new AtomicBoolean(value);
    }

    @Nested
    @DisplayName("四个触发条件")
    class TriggerConditions {

        /** 基线：四条全成立 —— 只有思考链没有正文，流以 stop 结束。 */
        @Test
        void allFourConditionsSatisfiedTriggersFallback() {
            assertThat(ReasoningFallback.shouldFallback(MAPPER, finishChunk("stop"),
                    contentEmitted(false), new StringBuilder("thought"))).isTrue();
        }

        /**
         * 条件 1 不成立：非终止 chunk。
         *
         * <p>中间帧不上触发 —— 否则流还没结束就补发正文，后面真实的正文会重复。
         */
        @Test
        void nonTerminalChunkDoesNotTrigger() {
            String midChunk = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"t\"},"
                    + "\"finish_reason\":null}]}";

            assertThat(ReasoningFallback.shouldFallback(MAPPER, midChunk,
                    contentEmitted(false), new StringBuilder("thought"))).isFalse();
        }

        /**
         * 条件 2 不成立：{@code finish_reason} 为 {@code tool_calls}。
         *
         * <p><strong>这是最重要的一条</strong>：它是终止 chunk（条件 1 成立），
         * 若只判「是不是终止」就会误触发，于是一次纯工具调用响应会被插入一段思考内容作为正文。
         * 下游 agent 拿到那段文字后既不执行工具也不报错，是极难定位的行为。
         */
        @Test
        void toolCallsFinishReasonNeverTriggersFallback() {
            assertThat(ReasoningFallback.shouldFallback(MAPPER, finishChunk("tool_calls"),
                    contentEmitted(false), new StringBuilder("thought"))).isFalse();
        }

        /** 条件 3 不成立：已经发过正文 —— 有正文就不需要拿思考内容顶替。 */
        @Test
        void alreadyEmittedContentDoesNotTrigger() {
            assertThat(ReasoningFallback.shouldFallback(MAPPER, finishChunk("stop"),
                    contentEmitted(true), new StringBuilder("thought"))).isFalse();
        }

        /** 条件 4 不成立：思考缓冲区为空 —— 没有可回退的内容。 */
        @Test
        void emptyReasoningBufferDoesNotTrigger() {
            assertThat(ReasoningFallback.shouldFallback(MAPPER, finishChunk("stop"),
                    contentEmitted(false), new StringBuilder())).isFalse();
        }

        /** 条件 4 的边界：缓冲区只有一个字符也算非空。 */
        @Test
        void singleCharacterReasoningBufferStillTriggers() {
            assertThat(ReasoningFallback.shouldFallback(MAPPER, finishChunk("stop"),
                    contentEmitted(false), new StringBuilder("x"))).isTrue();
        }

        /** 解析不了的 chunk 不触发（`isTerminalChunk` 对未知结构返回 false）。 */
        @Test
        void unparsableChunkDoesNotTrigger() {
            assertThat(ReasoningFallback.shouldFallback(MAPPER, "not json",
                    contentEmitted(false), new StringBuilder("thought"))).isFalse();
        }
    }

    @Nested
    @DisplayName("伪 chunk 对")
    class FallbackFrames {

        private List<String> frames() {
            return ReasoningFallback.buildFallbackFrames(MAPPER, "chatcmpl-x", "model-a", "the thought");
        }

        /**
         * 顺序不可交换：正文在前、结束标记在后。
         *
         * <p>下游按到达顺序累积正文，结束标记必须先看到正文才生效；
         * 反过来的话正文会被丢弃，等于回退没发生。
         */
        @Test
        void contentFrameComesBeforeFinishFrame() throws Exception {
            List<String> frames = frames();

            assertThat(frames).hasSize(2);
            JsonNode first = MAPPER.readTree(frames.get(0));
            JsonNode second = MAPPER.readTree(frames.get(1));

            assertThat(first.at("/choices/0/delta/content").asText()).isEqualTo("the thought");
            assertThat(second.at("/choices/0/delta/content").isMissingNode()).isTrue();
            assertThat(second.at("/choices/0/finish_reason").asText()).isEqualTo("stop");
        }

        /**
         * 两个伪 chunk 复用同一个 id —— 下游按 id 分组时不能被当成第二条流。
         */
        @Test
        void bothFramesCarryTheSameStreamId() throws Exception {
            List<String> frames = frames();

            assertThat(MAPPER.readTree(frames.get(0)).get("id").asText()).isEqualTo("chatcmpl-x");
            assertThat(MAPPER.readTree(frames.get(1)).get("id").asText()).isEqualTo("chatcmpl-x");
        }

        /**
         * 正文帧的 {@code finish_reason} 是 {@code null}，结束帧的是 {@code stop}。
         *
         * <p>正文帧若也带 {@code stop}，下游会在拿到正文的同一帧就认为流结束，
         * 结束帧变成多余的；反之结束帧若不写 {@code stop}，流永远不结束。
         */
        @Test
        void contentFrameHasNullFinishReasonAndFinishFrameHasStop() throws Exception {
            List<String> frames = frames();

            assertThat(MAPPER.readTree(frames.get(0)).at("/choices/0/finish_reason").isNull()).isTrue();
            assertThat(MAPPER.readTree(frames.get(1)).at("/choices/0/finish_reason").asText()).isEqualTo("stop");
        }

        /**
         * 正文帧带 {@code role: assistant} —— 只发 {@code content} 的话，
         * 严格按协议解析的客户端可能不接受一个没有 role 的 delta。
         */
        @Test
        void contentFrameDeclaresAssistantRole() throws Exception {
            JsonNode first = MAPPER.readTree(frames().get(0));

            assertThat(first.at("/choices/0/delta/role").asText()).isEqualTo("assistant");
        }

        /** 结束帧的 {@code delta} 是空对象而非 null —— 与真实结束帧形态一致。 */
        @Test
        void finishFrameCarriesEmptyDeltaObject() throws Exception {
            JsonNode second = MAPPER.readTree(frames().get(1));

            assertThat(second.at("/choices/0/delta").isObject()).isTrue();
            assertThat(second.at("/choices/0/delta").isEmpty()).isTrue();
        }

        /** 两个帧都带 {@code object} 与 {@code model}，形态上是完整的 chunk。 */
        @Test
        void bothFramesAreWellFormedChunks() throws Exception {
            for (String frame : frames()) {
                JsonNode node = MAPPER.readTree(frame);

                assertThat(node.get("object").asText()).isEqualTo("chat.completion.chunk");
                assertThat(node.get("model").asText()).isEqualTo("model-a");
                assertThat(node.get("created").isNumber()).isTrue();
                assertThat(node.at("/choices/0/index").asInt()).isZero();
            }
        }
    }
}

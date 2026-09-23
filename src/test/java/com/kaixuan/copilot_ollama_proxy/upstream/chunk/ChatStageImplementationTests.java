package com.kaixuan.copilot_ollama_proxy.upstream.chunk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.upstream.chunk.fallback.ChatReasoningFallbackStage;
import com.kaixuan.copilot_ollama_proxy.upstream.chunk.fallback.ReasoningFallback;
import com.kaixuan.copilot_ollama_proxy.upstream.chunk.normalize.ChatChunkNormalizeStage;
import com.kaixuan.copilot_ollama_proxy.upstream.chunk.normalize.UpstreamChunkNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个 Chat 支线实现的<strong>委托等价性</strong>与协议键。
 *
 * <h2>为何「等价」要单独测</h2>
 * Stage 3.1 把静态工具包成了支线实现。包一层本身不会出错，但<strong>参数顺序、副作用时机</strong>
 * 这类东西抄错时不会报错 —— 例如把 {@code contentEmitted} 与 {@code reasoningBuffer}
 * 传给对方，编译照样通过，而症状是「某类响应不再触发 fallback」，很难从集成测试定位。
 * 因此这里逐个断言：输出一致<strong>且</strong>三个流级状态的最终值一致。
 *
 * <h2>为何不起 Spring 上下文</h2>
 * 本类是纯委托，没有依赖需要容器提供（{@code ObjectMapper} 直接 new 即可）。
 * 起上下文只增加耗时，不增加覆盖。容器能否装配它们是另一件事，由
 * {@code ChatStageInjectionTests} 专门验证。
 */
class ChatStageImplementationTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatChunkNormalizeStage normalizeStage = new ChatChunkNormalizeStage(MAPPER);
    private final ChatReasoningFallbackStage fallbackStage = new ChatReasoningFallbackStage(MAPPER);

    /** 一次归一调用的完整上下文，便于比对副作用。 */
    private record Outcome(String normalized, boolean contentEmitted, String reasoning, String chunkId) {
    }

    /** 走支线实现。 */
    private Outcome viaStage(String chunk, AtomicBoolean emitted, StringBuilder buffer,
                             AtomicReference<String> id) {
        String out = normalizeStage.normalize(chunk, emitted, buffer, id);
        return new Outcome(out, emitted.get(), buffer.toString(), id.get());
    }

    /** 走静态工具（同一份输入状态，需新建实例）。 */
    private Outcome viaStatic(String chunk, AtomicBoolean emitted, StringBuilder buffer,
                              AtomicReference<String> id) {
        String out = UpstreamChunkNormalizer.normalize(MAPPER, chunk, emitted, buffer, id);
        return new Outcome(out, emitted.get(), buffer.toString(), id.get());
    }

    @Nested
    @DisplayName("协议键")
    class ProtocolKeys {

        @Test
        void normalizeStageDeclaresChat() {
            assertThat(normalizeStage.protocol()).isEqualTo(WireProtocol.CHAT);
        }

        @Test
        void fallbackStageDeclaresChat() {
            assertThat(fallbackStage.protocol()).isEqualTo(WireProtocol.CHAT);
        }

        /**
         * 两条支线都只声明 CHAT —— 另两条协议查不到实现即跳过，是预期行为。
         *
         * <p>这条断言的意义在于：若某天有人顺手把 implementation 改成「所有协议都返回」
         * （例如为省事让 {@code protocol()} 返回一个通配值），查表机制就会在另两条线路上
         * 命中一个读写 OpenAI 形态的实现，对着 Anthropic 的 {@code content_block_delta} 做无效归一。
         */
        @Test
        void bothDeclareOnlyChatNotAWildcard() {
            assertThat(List.of(normalizeStage.protocol(), fallbackStage.protocol()))
                    .containsOnly(WireProtocol.CHAT)
                    .doesNotContain(WireProtocol.MESSAGES, WireProtocol.RESPONSES);
        }
    }

    @Nested
    @DisplayName("归一：与静态工具逐字等价")
    class NormalizeEquivalence {

        /** 含别名字段思考链 + 正文的 chunk：会同时改字段名、累积 buffer、置 contentEmitted。 */
        @Test
        void normalizationAndSideEffectsMatchStaticTool() {
            String chunk = "{\"id\":\"chatcmpl-x\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"thinking\":\"想一下\",\"content\":\"正文\"},\"finish_reason\":\"\"}]}";

            AtomicBoolean stageEmitted = new AtomicBoolean(false);
            StringBuilder stageBuffer = new StringBuilder();
            AtomicReference<String> stageId = new AtomicReference<>("chatcmpl-unknown");
            Outcome viaStage = viaStage(chunk, stageEmitted, stageBuffer, stageId);

            AtomicBoolean staticEmitted = new AtomicBoolean(false);
            StringBuilder staticBuffer = new StringBuilder();
            AtomicReference<String> staticId = new AtomicReference<>("chatcmpl-unknown");
            Outcome viaStatic = viaStatic(chunk, staticEmitted, staticBuffer, staticId);

            assertThat(viaStage).isEqualTo(viaStatic);
            // 顺带钉住「确实产生了改动」，否则上面两条相同的 outcome 可能同为「什么都没做」。
            assertThat(viaStage.reasoning()).isEqualTo("想一下");
            assertThat(viaStage.contentEmitted()).isTrue();
            assertThat(viaStage.chunkId()).isEqualTo("chatcmpl-x");
        }

        /** {@code [DONE]} 直通：不改状态也不解析。 */
        @Test
        void doneMarkerPassesThroughIdentically() {
            assertThat(viaStage("[DONE]", new AtomicBoolean(false), new StringBuilder(),
                    new AtomicReference<>("id-1")))
                    .isEqualTo(viaStatic("[DONE]", new AtomicBoolean(false), new StringBuilder(),
                            new AtomicReference<>("id-1")));
        }

        /** 畸形 JSON 直通（解析失败返回入参原样），两侧一致。 */
        @Test
        void malformedJsonPassesThroughIdentically() {
            String malformed = "{not json";

            assertThat(viaStage(malformed, new AtomicBoolean(false), new StringBuilder(),
                    new AtomicReference<>("id-1")))
                    .isEqualTo(viaStatic(malformed, new AtomicBoolean(false), new StringBuilder(),
                            new AtomicReference<>("id-1")));
        }

        /**
         * 跨多次调用累积：支线与静态工具在<strong>同一份状态</strong>上逐帧走完的结果一致。
         *
         * <p>单帧等价不蕴含多帧等价 —— 累积状态（{@code contentEmitted} / {@code reasoningBuffer}）
         * 的顺序敏感，若实现里漏传或错传某个参数，只有多帧才暴露。
         */
        @Test
        void accumulationAcrossFramesMatches() {
            List<String> frames = List.of(
                    "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"先\"}}]}",
                    "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"后\"}}]}",
                    "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"答\"}}]}");

            AtomicBoolean stageEmitted = new AtomicBoolean(false);
            StringBuilder stageBuffer = new StringBuilder();
            AtomicReference<String> stageId = new AtomicReference<>("unknown");
            AtomicBoolean staticEmitted = new AtomicBoolean(false);
            StringBuilder staticBuffer = new StringBuilder();
            AtomicReference<String> staticId = new AtomicReference<>("unknown");

            for (String frame : frames) {
                assertThat(viaStage(frame, stageEmitted, stageBuffer, stageId))
                        .as("逐帧输出应一致：%s", frame)
                        .isEqualTo(viaStatic(frame, staticEmitted, staticBuffer, staticId));
            }

            assertThat(stageBuffer.toString()).isEqualTo(staticBuffer.toString()).isEqualTo("先后");
            assertThat(stageEmitted.get()).isEqualTo(staticEmitted.get()).isTrue();
        }
    }

    @Nested
    @DisplayName("fallback：与静态工具逐字等价")
    class FallbackEquivalence {

        /** 只有思考链、以 stop 结束 → 四条触发条件成立，两侧同判 true。 */
        @Test
        void shouldFallbackMatchesOnTriggeringInput() {
            String stopChunk = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},"
                    + "\"finish_reason\":\"stop\"}]}";
            AtomicBoolean emitted = new AtomicBoolean(false);
            StringBuilder buffer = new StringBuilder("思考内容");

            assertThat(fallbackStage.shouldFallback(stopChunk, emitted, buffer))
                    .isEqualTo(ReasoningFallback.shouldFallback(MAPPER, stopChunk, emitted, buffer))
                    .isTrue();
        }

        /**
         * 纯工具调用（{@code finish_reason=tool_calls}）<strong>不该</strong>触发 —— 两侧同判 false。
         *
         * <p>误判的症状是「工具调用响应末尾被凭空插入一段思考当正文」，下游 agent 会拿到
         * 一段无人要求的文字。这条是最该守住的反例。
         */
        @Test
        void shouldFallbackMatchesOnToolCallsInput() {
            String toolChunk = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},"
                    + "\"finish_reason\":\"tool_calls\"}]}";
            AtomicBoolean emitted = new AtomicBoolean(false);
            StringBuilder buffer = new StringBuilder("思考内容");

            assertThat(fallbackStage.shouldFallback(toolChunk, emitted, buffer))
                    .isEqualTo(ReasoningFallback.shouldFallback(MAPPER, toolChunk, emitted, buffer))
                    .isFalse();
        }

        /** 已发过正文 → 不触发，两侧一致。 */
        @Test
        void shouldFallbackMatchesWhenContentAlreadyEmitted() {
            String stopChunk = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},"
                    + "\"finish_reason\":\"stop\"}]}";
            AtomicBoolean emitted = new AtomicBoolean(true);
            StringBuilder buffer = new StringBuilder("思考内容");

            assertThat(fallbackStage.shouldFallback(stopChunk, emitted, buffer))
                    .isEqualTo(ReasoningFallback.shouldFallback(MAPPER, stopChunk, emitted, buffer))
                    .isFalse();
        }

        /** 伪帧构造：两侧逐字相同，且顺序为「先正文、后结束」。 */
        @Test
        void buildFallbackFramesMatches() {
            List<String> viaStage = fallbackStage.buildFallbackFrames("chatcmpl-1", "m", "思考");
            List<String> viaStatic = ReasoningFallback.buildFallbackFrames(MAPPER, "chatcmpl-1", "m", "思考");

            assertThat(viaStage).isEqualTo(viaStatic).hasSize(2);
            assertThat(viaStage.get(0)).contains("\"content\":\"思考\"");
            assertThat(viaStage.get(1)).contains("\"finish_reason\":\"stop\"");
        }
    }
}

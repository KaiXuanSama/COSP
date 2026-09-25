package com.kaixuan.copilot_ollama_proxy.pipeline.after.chunk.normalize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UpstreamChunkNormalizer} 的上游形态归一。
 *
 * <h2>为何这些用例值得存在</h2>
 * 本类取代的是 {@code AbstractUpstreamChatService} 里的 8 个私有方法。
 * 那些方法此前<strong>只被两条集成路径间接覆盖</strong>（流式 {@code chatCompletionStream}
 * 起真实上游 stub、非流式 {@code normalizeNonStreamResponse}），
 * 而它们的调用方式是<strong>反射</strong> —— 连「方法被搬走」这件事在测试里
 * 都只表现为运行时的 {@code NoSuchMethodException}，不是编译错误。
 *
 * <p>搬成公开静态方法后，可以直接喂一个 chunk 进去看它变成什么，
 * 不必再为「测一个纯函数」拉起整条上游管道。
 *
 * <h2>与 {@code AbstractUpstreamChatServiceTests} 的分工</h2>
 * 那边的清洗类用例<strong>保持原样</strong>（它们的断言逐字未改，是「搬运零行为变更」的证据）。
 * 本类补的是那些用例没覆盖到的分支：{@code [DONE]} 直通、解析失败直通、
 * <strong>结构性字段的保留补偿</strong>、以及三个流级状态的写入语义。
 */
class UpstreamChunkNormalizerTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 造一个调用上下文：contentEmitted / reasoningBuffer / chunkId。 */
    private record Context(AtomicBoolean contentEmitted, StringBuilder reasoningBuffer,
                           AtomicReference<String> chunkId) {
        static Context fresh() {
            return new Context(new AtomicBoolean(false), new StringBuilder(),
                    new AtomicReference<>("chatcmpl-unknown"));
        }
    }

    private static String normalize(String chunk, Context ctx) {
        return UpstreamChunkNormalizer.normalize(MAPPER, chunk, ctx.contentEmitted(),
                ctx.reasoningBuffer(), ctx.chunkId());
    }

    private static String normalize(String chunk) {
        return normalize(chunk, Context.fresh());
    }

    @Nested
    @DisplayName("直通：不改写也不报错")
    class Passthrough {

        /** {@code [DONE]} 是协议标记而非 JSON，解析它必然失败 —— 必须在解析之前就直通。 */
        @Test
        void doneMarkerPassesThroughBeforeAnyParsing() {
            assertThat(normalize("[DONE]")).isEqualTo("[DONE]");
        }

        /**
         * 结构未知的 chunk 原样返回。
         *
         * <p>与判定器「解析失败保守放行」同一取向：宁可把一个没见过的格式透传下去，
         * 也不要因为清洗失败把正常响应弄坏 —— 那种损坏发生在「内容其实没问题」的响应上，
         * 而下游看到的是空白。
         */
        @Test
        void unparsableChunkIsReturnedUnchanged() {
            String notJson = "this is not json at all";
            assertThat(normalize(notJson)).isEqualTo(notJson);
        }

        /**
         * 没有 {@code choices} 的 chunk 原样返回，且<strong>不</strong>被重新序列化。
         *
         * <p>断言「与输入逐字相同」而非「内容是等价的」：重新序列化会重排键序、
         * 丢掉上游的原始空白，而这一步的契约是「没得清洗就别碰」。
         */
        @Test
        void chunkWithoutChoicesIsReturnedByteForByte() {
            String usageOnly = "{\"id\":\"chatcmpl-1\",\"usage\":{\"prompt_tokens\":10}}";
            assertThat(normalize(usageOnly)).isEqualTo(usageOnly);
        }

        @Test
        void emptyChoicesArrayIsReturnedByteForByte() {
            String emptyChoices = "{\"id\":\"chatcmpl-1\",\"choices\":[]}";
            assertThat(normalize(emptyChoices)).isEqualTo(emptyChoices);
        }
    }

    @Nested
    @DisplayName("四条清洗规则")
    class CleaningRules {

        /** 规则 1：5 个兼容字段名统一到 {@code reasoning_content}。 */
        @Test
        void allFiveReasoningAliasesAreUnifiedToOneName() {
            // 一次只喂一个别名，逐个确认 —— 用「都在一起」的样本会让「哪个别名没被认」不可见
            for (String alias : new String[]{"reasoning_text", "reasoning", "thinking", "cot_summary"}) {
                String raw = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"" + alias + "\":\"thought\"}}]}";
                String normalized = normalize(raw);

                assertThat(normalized)
                        .as("别名 %s 应被改写为 reasoning_content", alias)
                        .contains("\"reasoning_content\":\"thought\"");
                assertThat(normalized)
                        .as("别名 %s 本身不应留在出站报文里", alias)
                        .doesNotContain("\"" + alias + "\":");
            }
        }

        /**
         * 别名值为空时也要<strong>删掉那个键</strong>，而不是原样保留。
         *
         * <p>这是 {@code extractReasoning} 的「副作用」分支：它无论提取成功与否都会
         * 清理别名字段。保留一个空的别名字段会让下游按别名的解析器读到空串。
         */
        @Test
        void emptyAliasKeyIsRemovedRatherThanKept() {
            String raw = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"thinking\":\"\",\"content\":\"hi\"}}]}";
            String normalized = normalize(raw);

            assertThat(normalized).doesNotContain("thinking");
            assertThat(normalized).contains("\"content\":\"hi\"");
        }

        /** 规则 2：{@code finish_reason} 空串 → {@code null}。 */
        @Test
        void blankFinishReasonBecomesNull() {
            String raw = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"\"}]}";
            assertThat(normalize(raw)).contains("\"finish_reason\":null");
        }

        /** 规则 3：空 {@code tool_calls} 必须删除，不能留下 {@code []}。 */
        @Test
        void emptyToolCallsAreRemovedEntirely() {
            String raw = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\",\"tool_calls\":[]}}]}";
            assertThat(normalize(raw)).doesNotContain("tool_calls");
        }

        /**
         * 规则 3 的反面：有实质内容的 {@code tool_calls} 不能被删。
         *
         * <p>与上一条配对才有意义 —— 只测「空数组被删」的话，把整个
         * {@code tool_calls} 无条件删掉也能让用例通过，而那样会丢掉工具调用参数。
         */
        @Test
        void meaningfulToolCallsAreKept() {
            String raw = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":"
                    + "[{\"index\":0,\"function\":{\"arguments\":\"{}\"}}]}}]}";

            assertThat(normalize(raw)).contains("\"tool_calls\"").contains("arguments");
        }

        /** 规则 4：递归剪枝删真空串，但不删纯空白。 */
        @Test
        void recursivePruningRemovesEmptyStringsButKeepsWhitespace() {
            String emptyContent = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"}}]}";
            assertThat(normalize(emptyContent)).doesNotContain("\"content\"");

            String whitespaceContent = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\\n\"}}]}";
            assertThat(normalize(whitespaceContent)).contains("\"content\":\"\\n\"");
        }

        /** 嵌套结构里的空值同样被剪掉（递归而非只扫一层）。 */
        @Test
        void pruningRecursesIntoNestedStructures() {
            String raw = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":"
                    + "[{\"index\":0,\"id\":\"\",\"function\":{\"name\":\"f\",\"arguments\":\"\"}}]}}]}";

            String normalized = normalize(raw);

            // function 里两个空串被删，但 name 与整个 tool_calls 结构保留
            assertThat(normalized).doesNotContain("\"arguments\":\"\"");
            assertThat(normalized).contains("\"name\":\"f\"");
            assertThat(normalized).contains("\"tool_calls\"");
        }
    }

    @Nested
    @DisplayName("结构性字段的保留补偿")
    class StructuralPreservation {

        /**
         * 空 delta 的 chunk 必须保留 {@code delta:{}}，不能被剪成「没有 delta 键」。
         *
         * <p>这是「剪枝与保结构」的冲突点：剪枝看到空 Map 会删它，但结束帧的
         * {@code delta:{}} 本身携带「这是一个结构完整的结束帧」的语义。
         * 用 {@code delta:null} 或整个消失都有客户端解析不出。
         */
        @Test
        void emptyDeltaObjectSurvivesPruningOnFinishChunk() {
            String raw = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":null,\"content\":null},"
                    + "\"finish_reason\":\"stop\"}]}";

            String normalized = normalize(raw);

            assertThat(normalized).contains("\"delta\":{}");
            assertThat(normalized).contains("\"finish_reason\":\"stop\"");
        }

        /**
         * 中间帧的 {@code finish_reason:null} 必须保留 —— 显式 null 与「键不存在」
         * 在部分客户端里含义不同。
         */
        @Test
        void explicitNullFinishReasonSurvivesPruning() {
            String raw = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},"
                    + "\"finish_reason\":null}]}";

            assertThat(normalize(raw)).contains("\"finish_reason\":null");
        }
    }

    @Nested
    @DisplayName("三个流级状态的写入语义")
    class StreamStateWrites {

        /** 正文非空时置 {@code contentEmitted} —— fallback 的主要判据。 */
        @Test
        void nonEmptyContentSetsContentEmittedFlag() {
            Context ctx = Context.fresh();
            normalize("{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}", ctx);

            assertThat(ctx.contentEmitted().get()).isTrue();
        }

        /**
         * 正文为空串时<strong>不</strong>置位。
         *
         * <p>这条与上一条配对：只测「有正文会置位」的话，把置位写成无条件也能通过，
         * 而那会让 fallback 在真正需要它时（模型只吐思考链）不触发。
         */
        @Test
        void emptyContentDoesNotSetContentEmittedFlag() {
            Context ctx = Context.fresh();
            normalize("{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"}}]}", ctx);

            assertThat(ctx.contentEmitted().get()).isFalse();
        }

        /** 思考内容被<strong>累积</strong>而非覆盖 —— fallback 要用整段思考补发正文。 */
        @Test
        void reasoningIsAccumulatedAcrossChunksRatherThanOverwritten() {
            Context ctx = Context.fresh();
            normalize("{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"part1 \"}}]}", ctx);
            normalize("{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"part2\"}}]}", ctx);

            assertThat(ctx.reasoningBuffer().toString()).isEqualTo("part1 part2");
        }

        /**
         * 空白的思考内容<strong>不</strong>进缓冲区。
         *
         * <p>否则纯空白思考会让 {@code reasoningBuffer} 非空，
         * 从而让 fallback 把一串空白当成正文补发出去。
         */
        @Test
        void blankReasoningIsNotBuffered() {
            Context ctx = Context.fresh();
            normalize("{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"   \"}}]}", ctx);

            assertThat(ctx.reasoningBuffer().toString()).isEmpty();
        }

        /** chunk id 被记录，供 fallback 的伪 chunk 复用同一个 id。 */
        @Test
        void chunkIdIsCapturedForFallbackFrameIdentity() {
            Context ctx = Context.fresh();
            normalize("{\"id\":\"chatcmpl-abc\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"}}]}", ctx);

            assertThat(ctx.chunkId().get()).isEqualTo("chatcmpl-abc");
        }

        /**
         * 空 id 不覆盖已记录的 id。
         *
         * <p>部分上游在后续分片里把 {@code id} 发成空串。若无条件写入，
         * chunkId 会被清空，fallback 的伪 chunk 就带一个空 id ——
         * 下游按 id 分组时可能把它当成另一条流。
         */
        @Test
        void emptyIdDoesNotOverwritePreviouslyCapturedId() {
            Context ctx = Context.fresh();
            normalize("{\"id\":\"chatcmpl-real\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"}}]}", ctx);
            normalize("{\"id\":\"\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"y\"}}]}", ctx);

            assertThat(ctx.chunkId().get()).isEqualTo("chatcmpl-real");
        }
    }

    @Nested
    @DisplayName("终止判定的两档")
    class TerminalDetection {

        /**
         * {@code stop} 既是终止 chunk 也是 stop finish reason —— 两档的分界不在它身上。
         */
        @Test
        void stopIsBothTerminalAndStopFinishReason() {
            String raw = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";

            assertThat(UpstreamChunkNormalizer.isTerminalChunk(MAPPER, raw)).isTrue();
            assertThat(UpstreamChunkNormalizer.isStopFinishReason(MAPPER, raw)).isTrue();
        }

        /**
         * {@code tool_calls} <strong>是</strong>终止 chunk，但<strong>不是</strong> stop finish reason。
         *
         * <p>这个差异就是 reasoning fallback 不误伤工具调用响应的全部依据：
         * 若 {@code isStopFinishReason} 也认 {@code tool_calls}，
         * 一次纯工具调用会在末尾被凭空插入一段思考内容作为正文。
         */
        @Test
        void toolCallsIsTerminalButNotStopFinishReason() {
            String raw = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}";

            assertThat(UpstreamChunkNormalizer.isTerminalChunk(MAPPER, raw)).isTrue();
            assertThat(UpstreamChunkNormalizer.isStopFinishReason(MAPPER, raw)).isFalse();
        }

        /** {@code length} 两档都不是。 */
        @Test
        void lengthFinishReasonIsNeitherTerminalNorStop() {
            String raw = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"length\"}]}";

            assertThat(UpstreamChunkNormalizer.isTerminalChunk(MAPPER, raw)).isFalse();
            assertThat(UpstreamChunkNormalizer.isStopFinishReason(MAPPER, raw)).isFalse();
        }

        /** {@code [DONE]} 两档都不是：它是流结束标记，不承载 {@code choices}。 */
        @Test
        void doneMarkerIsNeitherTerminalNorStop() {
            assertThat(UpstreamChunkNormalizer.isTerminalChunk(MAPPER, "[DONE]")).isFalse();
            assertThat(UpstreamChunkNormalizer.isStopFinishReason(MAPPER, "[DONE]")).isFalse();
        }

        /** 结构未知时判否（不抛异常）—— 「不知道是不是终止」应按「不是」处理。 */
        @Test
        void unparsableChunkIsNotTerminal() {
            assertThat(UpstreamChunkNormalizer.isTerminalChunk(MAPPER, "not json")).isFalse();
            assertThat(UpstreamChunkNormalizer.isStopFinishReason(MAPPER, "not json")).isFalse();
        }
    }

    /** {@code hasReasoningAliasKey} 的用途是「判断 extractReasoning 会不会产生改动」。 */
    @Nested
    @DisplayName("别名字段探测")
    class AliasKeyDetection {

        @Test
        void detectsAliasKeyEvenWhenItsValueIsEmpty() {
            // 空值也算「会被改动」：extractReasoning 会把这个键删掉
            assertThat(UpstreamChunkNormalizer.hasReasoningAliasKey(
                    new java.util.LinkedHashMap<>(java.util.Map.of("thinking", "")))).isTrue();
        }

        /** {@code reasoning_content} 是正名而非别名，它的存在不构成「会被改动」。 */
        @Test
        void reasoningContentItselfIsNotAnAlias() {
            assertThat(UpstreamChunkNormalizer.hasReasoningAliasKey(
                    new java.util.LinkedHashMap<>(java.util.Map.of("reasoning_content", "x")))).isFalse();
        }

        @Test
        void messageWithoutAnyReasoningKeyHasNoAlias() {
            assertThat(UpstreamChunkNormalizer.hasReasoningAliasKey(
                    new java.util.LinkedHashMap<>(java.util.Map.of("content", "hi")))).isFalse();
        }
    }
}

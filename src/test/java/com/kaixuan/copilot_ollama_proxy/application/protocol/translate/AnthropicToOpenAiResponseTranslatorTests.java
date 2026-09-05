package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ResponseTranslationException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.TranslationContext;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A2O 响应翻译。
 *
 * <p>重点覆盖契约第 13.1 节点名的、三个参考项目<strong>都没有测试覆盖</strong>的路径：
 * tool index 双索引域重映射、多个 thinking 块的累积、signature 不产帧、
 * usage 两事件合并且 0 不覆盖、流结束三档收尾、重试重订阅后状态重置。
 */
class AnthropicToOpenAiResponseTranslatorTests {

    /** 上游真实模型名（不含供应商前缀）。 */
    private static final String UPSTREAM_MODEL = "deepseek-v4-flash";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnthropicToOpenAiResponseTranslator translator =
            new AnthropicToOpenAiResponseTranslator(objectMapper);

    // ==================== 非流式 ====================

    @Nested
    @DisplayName("非流式映射")
    class NonStream {

        @Test
        void textBlocksAreConcatenatedIntoContent() throws Exception {
            String upstream = """
                    {"id":"msg_1","model":"claude-x","stop_reason":"end_turn",
                     "content":[{"type":"text","text":"前半"},{"type":"text","text":"后半"}]}
                    """;

            JsonNode result = translateNonStream(upstream);

            assertThat(result.path("choices").get(0).path("message").path("content").asText())
                    .isEqualTo("前半后半");
            assertThat(result.path("object").asText()).isEqualTo("chat.completion");
        }

        /**
         * 多个 thinking 块必须<strong>累积</strong>。
         *
         * <p>参考实现 new-api 这里用的是赋值而非追加，多块只剩最后一个、前面静默丢失。
         */
        @Test
        void multipleThinkingBlocksAreAccumulatedNotOverwritten() throws Exception {
            String upstream = """
                    {"id":"msg_1","model":"claude-x","stop_reason":"end_turn",
                     "content":[{"type":"thinking","thinking":"第一段"},
                                {"type":"thinking","thinking":"第二段"},
                                {"type":"text","text":"答案"}]}
                    """;

            JsonNode message = translateNonStream(upstream).path("choices").get(0).path("message");

            assertThat(message.path("reasoning_content").asText()).isEqualTo("第一段第二段");
            assertThat(message.path("content").asText()).isEqualTo("答案");
        }

        /** 思考内容无条件给出，不看有没有 tool_calls。 */
        @Test
        void thinkingIsEmittedEvenWithoutToolCalls() throws Exception {
            String upstream = """
                    {"id":"msg_1","model":"claude-x","stop_reason":"end_turn",
                     "content":[{"type":"thinking","thinking":"想一下"},{"type":"text","text":"好"}]}
                    """;

            assertThat(translateNonStream(upstream).path("choices").get(0)
                    .path("message").path("reasoning_content").asText()).isEqualTo("想一下");
        }

        @Test
        void toolUseBlocksBecomeToolCallsWithStringifiedArguments() throws Exception {
            String upstream = """
                    {"id":"msg_1","model":"claude-x","stop_reason":"tool_use",
                     "content":[{"type":"tool_use","id":"toolu_1","name":"get_weather",
                                 "input":{"city":"北京"}}]}
                    """;

            JsonNode toolCall = translateNonStream(upstream).path("choices").get(0)
                    .path("message").path("tool_calls").get(0);

            assertThat(toolCall.path("id").asText()).isEqualTo("toolu_1");
            assertThat(toolCall.path("type").asText()).isEqualTo("function");
            assertThat(toolCall.path("function").path("name").asText()).isEqualTo("get_weather");
            // arguments 是 JSON 字符串而非对象。
            assertThat(objectMapper.readTree(
                    toolCall.path("function").path("arguments").asText())
                    .path("city").asText()).isEqualTo("北京");
            assertThat(translateNonStreamRaw(upstream)).contains("\"finish_reason\":\"tool_calls\"");
        }

        /** 无参工具调用是合法形态，input 缺失映射成空对象而非报错。 */
        @Test
        void missingToolInputBecomesEmptyObject() throws Exception {
            String upstream = """
                    {"id":"msg_1","model":"claude-x","stop_reason":"tool_use",
                     "content":[{"type":"tool_use","id":"toolu_1","name":"ping"}]}
                    """;

            assertThat(translateNonStream(upstream).path("choices").get(0)
                    .path("message").path("tool_calls").get(0)
                    .path("function").path("arguments").asText()).isEqualTo("{}");
        }

        /**
         * 模型名原样透传上游返回的值。
         *
         * <p>不换成下游带前缀的请求名 —— OpenAI 直连路径下代理也不改写响应里的
         * {@code model}，两条路必须同口径。
         */
        @Test
        void upstreamModelNameIsPassedThroughVerbatim() throws Exception {
            String upstream = """
                    {"id":"msg_1","model":"claude-x","stop_reason":"end_turn",
                     "content":[{"type":"text","text":"好"}]}
                    """;

            assertThat(translateNonStream(upstream).path("model").asText()).isEqualTo("claude-x");
        }

        @Test
        void redactedThinkingAndHostedBlocksAreDropped() throws Exception {
            String upstream = """
                    {"id":"msg_1","model":"claude-x","stop_reason":"end_turn",
                     "content":[{"type":"redacted_thinking","data":"加密"},
                                {"type":"server_tool_use","id":"srv_1","name":"web_search"},
                                {"type":"text","text":"好"}]}
                    """;

            JsonNode message = translateNonStream(upstream).path("choices").get(0).path("message");

            assertThat(message.path("content").asText()).isEqualTo("好");
            assertThat(message.has("reasoning_content")).isFalse();
            assertThat(message.has("tool_calls")).isFalse();
        }

        @Test
        void unparsableUpstreamBodyRaisesTranslationException() {
            Throwable thrown = catchThrowable(() -> translateNonStreamRaw("不是 JSON"));

            assertThat(thrown).isInstanceOf(ResponseTranslationException.class);
        }

        @Test
        void blankUpstreamBodyRaisesTranslationException() {
            assertThat(catchThrowable(() -> translateNonStreamRaw("")))
                    .isInstanceOf(ResponseTranslationException.class);
        }
    }

    // ==================== usage 换算 ====================

    @Nested
    @DisplayName("usage 换算")
    class Usage {

        /**
         * Anthropic 的 input_tokens 不含缓存，OpenAI 的 prompt_tokens 含缓存。
         * 不加回去会让计费少记。
         */
        @Test
        void cacheTokensAreAddedBackIntoPromptTokens() throws Exception {
            String upstream = """
                    {"id":"msg_1","model":"claude-x","stop_reason":"end_turn",
                     "content":[{"type":"text","text":"好"}],
                     "usage":{"input_tokens":10,"output_tokens":5,
                              "cache_read_input_tokens":3,"cache_creation_input_tokens":2}}
                    """;

            JsonNode usage = translateNonStream(upstream).path("usage");

            assertThat(usage.path("prompt_tokens").asInt()).isEqualTo(15);
            assertThat(usage.path("completion_tokens").asInt()).isEqualTo(5);
            assertThat(usage.path("total_tokens").asInt()).isEqualTo(20);
            assertThat(usage.path("prompt_tokens_details").path("cached_tokens").asInt()).isEqualTo(3);
        }

        /** 没有缓存时不产出 details 对象，避免让下游误以为上游支持缓存统计。 */
        @Test
        void promptTokenDetailsOmittedWhenNoCacheUsed() throws Exception {
            String upstream = """
                    {"id":"msg_1","model":"claude-x","stop_reason":"end_turn",
                     "content":[{"type":"text","text":"好"}],
                     "usage":{"input_tokens":10,"output_tokens":5}}
                    """;

            assertThat(translateNonStream(upstream).path("usage").has("prompt_tokens_details"))
                    .isFalse();
        }

        /** Anthropic 不上报思考 token，不能凭空估算。 */
        @Test
        void reasoningTokensAreNeverSynthesized() throws Exception {
            String upstream = """
                    {"id":"msg_1","model":"claude-x","stop_reason":"end_turn",
                     "content":[{"type":"thinking","thinking":"很长的思考过程"}],
                     "usage":{"input_tokens":10,"output_tokens":50}}
                    """;

            assertThat(translateNonStream(upstream).path("usage")
                    .has("completion_tokens_details")).isFalse();
        }

        @Test
        void usageOmittedEntirelyWhenUpstreamGivesNone() throws Exception {
            String upstream = """
                    {"id":"msg_1","model":"claude-x","stop_reason":"end_turn",
                     "content":[{"type":"text","text":"好"}]}
                    """;

            assertThat(translateNonStream(upstream).has("usage")).isFalse();
        }

        /**
         * usage 分两个事件到达，合并时 0 不能覆盖已有值。
         *
         * <p>message_delta 的 usage 里 input_tokens 常常是 0，无条件覆盖会抹掉
         * message_start 记下的输入 token。
         */
        @Test
        void zeroDoesNotOverwriteAccumulatedUsageAcrossEvents() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"message_start","message":{"id":"msg_1","model":"claude-x",
                     "usage":{"input_tokens":88,"output_tokens":0,"cache_read_input_tokens":7}}}
                    """,
                    """
                    {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
                    """,
                    """
                    {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好"}}
                    """,
                    """
                    {"type":"message_delta","delta":{"stop_reason":"end_turn"},
                     "usage":{"input_tokens":0,"output_tokens":20}}
                    """), true);

            String usageChunk = chunks.stream()
                    .filter(chunk -> chunk.contains("\"usage\""))
                    .findFirst().orElseThrow();

            // 88 + 7 = 95，不能被 message_delta 的 input_tokens:0 抹成 0。
            assertThat(usageChunk).contains("\"prompt_tokens\":95");
            assertThat(usageChunk).contains("\"completion_tokens\":20");
        }
    }

    // ==================== 落库 usage 换算 ====================

    /**
     * 落库侧的 usage 换算（契约第 9.4 节）。
     *
     * <p>与出站报文的换算是同一件事，但入口不同：出站拿到的是完整 usage 节点，
     * 落库拿到的是已归一化的 {@link UsageTokens} 三元组。
     */
    @Nested
    @DisplayName("落库 usage 换算成下游口径")
    class UsageForLog {

        /**
         * 落库的 {@code prompt_tokens} 必须是<strong>下游实际收到</strong>的那个数。
         *
         * <p>这是本次修复的核心缺陷：实测同一次 A2O 调用，下游收到 22583，
         * 日志里却记着 55 —— 于是缓存占比算成 {@code 22528/55 = 40960%}。
         */
        @Test
        void promptTokensIncludeCacheSoDownstreamSeesTheSameNumber() {
            UsageTokens upstream = new UsageTokens(55, 279, 22528);

            UsageTokens forLog = AnthropicToOpenAiResponseTranslator.translateUsageForLog(upstream);

            assertThat(forLog.promptTokens()).isEqualTo(22583);
            assertThat(forLog.completionTokens()).isEqualTo(279);
            // 缓存命中量在两种协议里语义一致，原样透传。
            assertThat(forLog.cachedTokens()).isEqualTo(22528);
        }

        /** 换算后缓存占比必然落在 0~100%，这正是前端那个百分比的分母来源。 */
        @Test
        void cacheHitRateBecomesRepresentableAfterTranslation() {
            UsageTokens forLog = AnthropicToOpenAiResponseTranslator
                    .translateUsageForLog(new UsageTokens(55, 279, 22528));

            double rate = forLog.cachedTokens() / (double) forLog.promptTokens();

            assertThat(rate).isBetween(0.0, 1.0);
        }

        /**
         * null 不能当 0 参与相加。
         *
         * <p>null 表示上游未提供该字段，0 表示上游报告了真实零值 —— 把 null 折成 0
         * 会造出「上游报告了这个值」的假象，毁掉缓存占比「—」与「0.0%」的区分。
         */
        @Test
        void missingCacheFieldLeavesPromptUnchanged() {
            UsageTokens forLog = AnthropicToOpenAiResponseTranslator
                    .translateUsageForLog(new UsageTokens(100, 20, null));

            assertThat(forLog.promptTokens()).isEqualTo(100);
            assertThat(forLog.cachedTokens()).isNull();
        }

        @Test
        void missingInputWithPresentCacheStillProducesASum() {
            UsageTokens forLog = AnthropicToOpenAiResponseTranslator
                    .translateUsageForLog(new UsageTokens(null, 20, 300));

            // 输入缺失但缓存有值：分母仍然有效（全部输入都来自缓存命中）。
            assertThat(forLog.promptTokens()).isEqualTo(300);
        }

        /** 两个输入侧字段都缺失时保持 null，不能凭空造出 0。 */
        @Test
        void bothInputFieldsMissingKeepsPromptNull() {
            UsageTokens forLog = AnthropicToOpenAiResponseTranslator
                    .translateUsageForLog(new UsageTokens(null, 20, null));

            assertThat(forLog.promptTokens()).isNull();
            assertThat(forLog.completionTokens()).isEqualTo(20);
        }

        /** 真实零值必须活下来：0 缓存 + 有输入 → 占比 0.0%，而非「—」。 */
        @Test
        void realZeroCacheSurvivesTranslation() {
            UsageTokens forLog = AnthropicToOpenAiResponseTranslator
                    .translateUsageForLog(new UsageTokens(100, 20, 0));

            assertThat(forLog.promptTokens()).isEqualTo(100);
            assertThat(forLog.cachedTokens()).isZero();
        }

        @Test
        void emptyAndNullInputsYieldEmpty() {
            assertThat(AnthropicToOpenAiResponseTranslator.translateUsageForLog(null))
                    .isEqualTo(UsageTokens.EMPTY);
            assertThat(AnthropicToOpenAiResponseTranslator.translateUsageForLog(UsageTokens.EMPTY))
                    .isEqualTo(UsageTokens.EMPTY);
        }

        /**
         * 换算是幂等的<strong>吗？不是</strong> —— 这一条钉住「只能换算一次」。
         *
         * <p>重复施加会把缓存加两遍。落库路径上换算点只有一个
         * （{@code DownstreamLogView.viewUsage}），本测试防止将来有人在
         * 上游服务里再补一次「顺手」的换算。
         */
        @Test
        void translationIsNotIdempotentSoItMustBeAppliedExactlyOnce() {
            UsageTokens once = AnthropicToOpenAiResponseTranslator
                    .translateUsageForLog(new UsageTokens(55, 279, 22528));
            UsageTokens twice = AnthropicToOpenAiResponseTranslator.translateUsageForLog(once);

            assertThat(once.promptTokens()).isEqualTo(22583);
            assertThat(twice.promptTokens()).isEqualTo(45111);
        }

        /**
         * 落库值与出站报文一致（在无缓存写入时）。
         *
         * <p>两者走的是不同代码路径 —— 出站是 {@code AnthropicUsageAccumulator}
         * 读完整 usage 节点，落库是本方法读三元组 —— 必须给出同一个数，
         * 否则「日志里的数就是客户端看到的数」这个契约就不成立。
         */
        @Test
        void loggedPromptMatchesOutboundPayloadWhenNoCacheCreation() throws Exception {
            String upstream = """
                    {"id":"msg_1","model":"claude-x","stop_reason":"end_turn",
                     "content":[{"type":"text","text":"好"}],
                     "usage":{"input_tokens":55,"output_tokens":279,
                              "cache_read_input_tokens":22528,"cache_creation_input_tokens":0}}
                    """;
            int outbound = translateNonStream(upstream).path("usage").path("prompt_tokens").asInt();

            // 落库侧从归一化三元组出发（AnthropicUsageParser 的产出形态）。
            int logged = AnthropicToOpenAiResponseTranslator
                    .translateUsageForLog(new UsageTokens(55, 279, 22528))
                    .promptTokens();

            assertThat(logged).isEqualTo(outbound);
        }

        /**
         * 已知精度损失：{@code cache_creation_input_tokens} 不在
         * {@link UsageTokens} 里，因此落库值会低于出站值。
         *
         * <p>钉住这个差值而非假装它不存在 —— 补齐它需要给 {@code api_call_usage}
         * 加一列，属于契约第 9.4 节的待决事项。
         */
        @Test
        void cacheCreationIsMissingFromLoggedValueByKnownLimitation() throws Exception {
            String upstream = """
                    {"id":"msg_1","model":"claude-x","stop_reason":"end_turn",
                     "content":[{"type":"text","text":"好"}],
                     "usage":{"input_tokens":10,"output_tokens":5,
                              "cache_read_input_tokens":3,"cache_creation_input_tokens":2}}
                    """;
            int outbound = translateNonStream(upstream).path("usage").path("prompt_tokens").asInt();

            int logged = AnthropicToOpenAiResponseTranslator
                    .translateUsageForLog(new UsageTokens(10, 5, 3))
                    .promptTokens();

            assertThat(outbound).isEqualTo(15);
            // 差额恰好是 cache_creation_input_tokens。
            assertThat(logged).isEqualTo(13);
        }
    }

    // ==================== 流式：帧数不对等 ====================

    @Nested
    @DisplayName("流式帧数不对等")
    class FrameCardinality {

        /** message_start 是唯一带 role 的帧。 */
        @Test
        void messageStartEmitsTheOnlyRoleFrame() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"message_start","message":{"id":"msg_1","model":"claude-x"}}
                    """,
                    """
                    {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好"}}
                    """), false);

            assertThat(chunks.get(0)).contains("\"role\":\"assistant\"");
            assertThat(chunks.stream().filter(c -> c.contains("\"role\"")).count()).isEqualTo(1);
        }

        /**
         * 这些事件产出零帧。Chat 协议没有块生命周期概念，
         * 产出空 delta 帧只是噪声（参考实现 new-api 会因 fall-through 发出噪声帧）。
         */
        @Test
        void blockLifecycleAndPingEmitNoFrames() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
                    """,
                    "{\"type\":\"ping\"}",
                    """
                    {"type":"content_block_stop","index":0}
                    """,
                    "{\"type\":\"message_stop\"}"), false);

            // 只有收尾产出的帧，没有任何来自上述事件的帧。
            assertThat(chunks).noneMatch(chunk -> chunk.contains("\"delta\":{}"));
        }

        /** [DONE] 由流结束触发，不由 message_stop 触发。 */
        @Test
        void doneSentinelIsAlwaysLastFrame() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"message_start","message":{"id":"msg_1","model":"claude-x"}}
                    """,
                    """
                    {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好"}}
                    """,
                    """
                    {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
                    """,
                    "{\"type\":\"message_stop\"}"), false);

            assertThat(chunks.get(chunks.size() - 1)).isEqualTo("[DONE]");
            assertThat(chunks.stream().filter("[DONE]"::equals).count()).isEqualTo(1);
        }
    }

    // ==================== 流式：思考 ====================

    @Nested
    @DisplayName("流式思考内容")
    class Thinking {

        @Test
        void thinkingDeltaBecomesReasoningContent() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"content_block_delta","index":0,
                     "delta":{"type":"thinking_delta","thinking":"我们"}}
                    """), false);

            assertThat(chunks.get(0)).contains("\"reasoning_content\":\"我们\"");
            // 不能同时写 content —— 那会让思考内容混进正文。
            assertThat(chunks.get(0)).doesNotContain("\"content\":\"我们\"");
        }

        /**
         * signature_delta 吸收但不产帧，也不注入替代内容。
         *
         * <p>参考实现 new-api 把它映射成 reasoning_content = "\n"，
         * 那个换行会成为下游看到的真实内容，是凭空多出来的。
         */
        @Test
        void signatureDeltaEmitsNothingAndInjectsNoSubstitute() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"content_block_delta","index":0,
                     "delta":{"type":"thinking_delta","thinking":"想"}}
                    """,
                    """
                    {"type":"content_block_delta","index":0,
                     "delta":{"type":"signature_delta","signature":"abc123"}}
                    """), false);

            assertThat(chunks).noneMatch(chunk -> chunk.contains("abc123"));
            assertThat(chunks).noneMatch(chunk -> chunk.contains("\\n"));
            // 只有思考帧与收尾帧，signature 没有贡献任何帧。
            assertThat(chunks.stream()
                    .filter(chunk -> chunk.contains("reasoning_content")).count()).isEqualTo(1);
        }
    }

    // ==================== 流式：tool index 双索引域 ====================

    @Nested
    @DisplayName("tool index 是独立索引域")
    class ToolIndexDomain {

        /**
         * 这是本轮最容易错、且三个参考项目都没测的路径。
         *
         * <p>实测序列里 thinking 占 Anthropic index 0、tool_use 占 index 1 与 2。
         * OpenAI 的 tool_calls[].index 必须从 0 起稠密递增，
         * 直接用 Anthropic index 会产出从 1 开始的稀疏数组，下游拼不出参数。
         */
        @Test
        void toolIndexIsDenseFromZeroEvenWhenThinkingOccupiesAnthropicIndexZero() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"content_block_start","index":0,
                     "content_block":{"type":"thinking","thinking":""}}
                    """,
                    """
                    {"type":"content_block_delta","index":0,
                     "delta":{"type":"thinking_delta","thinking":"想"}}
                    """,
                    """
                    {"type":"content_block_start","index":1,
                     "content_block":{"type":"tool_use","id":"toolu_a","name":"tool_a"}}
                    """,
                    """
                    {"type":"content_block_start","index":2,
                     "content_block":{"type":"tool_use","id":"toolu_b","name":"tool_b"}}
                    """), false);

            String firstTool = chunkContaining(chunks, "toolu_a");
            String secondTool = chunkContaining(chunks, "toolu_b");

            // Anthropic index 1 → OpenAI tool index 0
            assertThat(firstTool).contains("\"index\":0");
            // Anthropic index 2 → OpenAI tool index 1
            assertThat(secondTool).contains("\"index\":1");
        }

        /** 参数增量必须按 Anthropic index 查表，落到正确的 OpenAI tool index 上。 */
        @Test
        void argumentDeltasAreRoutedToTheMappedToolIndex() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"content_block_start","index":0,
                     "content_block":{"type":"thinking","thinking":""}}
                    """,
                    """
                    {"type":"content_block_start","index":1,
                     "content_block":{"type":"tool_use","id":"toolu_a","name":"tool_a"}}
                    """,
                    """
                    {"type":"content_block_delta","index":1,
                     "delta":{"type":"input_json_delta","partial_json":"{\\"x\\":"}}
                    """,
                    """
                    {"type":"content_block_delta","index":1,
                     "delta":{"type":"input_json_delta","partial_json":"1}"}}
                    """), false);

            List<String> argumentChunks = chunks.stream()
                    .filter(chunk -> chunk.contains("arguments") && !chunk.contains("toolu_a"))
                    .toList();

            assertThat(argumentChunks).hasSize(2);
            assertThat(argumentChunks).allMatch(chunk -> chunk.contains("\"index\":0"));
        }

        /** 没见过声明的块，参数增量必须丢弃而不是猜一个序号。 */
        @Test
        void argumentDeltaWithoutPriorDeclarationIsDropped() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"content_block_delta","index":7,
                     "delta":{"type":"input_json_delta","partial_json":"{}"}}
                    """), false);

            assertThat(chunks).noneMatch(chunk -> chunk.contains("tool_calls"));
        }
    }

    // ==================== 流式：收尾三档 ====================

    @Nested
    @DisplayName("流结束的三档收尾")
    class Finalization {

        /** 第一档：message_delta 给了 stop_reason，正常映射。 */
        @Test
        void stopReasonFromMessageDeltaIsMapped() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"message_delta","delta":{"stop_reason":"max_tokens"}}
                    """), false);

            assertThat(finishChunk(chunks)).contains("\"finish_reason\":\"length\"");
        }

        /**
         * 第二档：上游没给 stop_reason 但有实质输出 —— 按截断处理。
         *
         * <p>不能报成正常完成：那会让下游把一个被切断的回答当作完整答案。
         */
        @Test
        void truncatedStreamWithOutputIsReportedAsLength() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"半句"}}
                    """), false);

            assertThat(finishChunk(chunks)).contains("\"finish_reason\":\"length\"");
        }

        /** 第二档变体：截断但见过工具调用，finish_reason 用 tool_calls。 */
        @Test
        void truncatedStreamWithToolCallIsReportedAsToolCalls() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"content_block_start","index":0,
                     "content_block":{"type":"tool_use","id":"toolu_a","name":"tool_a"}}
                    """), false);

            assertThat(finishChunk(chunks)).contains("\"finish_reason\":\"tool_calls\"");
        }

        /** 第三档：什么都没有，仍要发终止帧，否则下游会一直等。 */
        @Test
        void emptyStreamStillEmitsTerminationFrames() {
            List<String> chunks = collectStream(List.of(), false);

            assertThat(finishChunk(chunks)).contains("\"finish_reason\":\"stop\"");
            assertThat(chunks.get(chunks.size() - 1)).isEqualTo("[DONE]");
        }

        /** finish_reason 只能发一次，上游重复发 message_delta 不产生第二个。 */
        @Test
        void finishReasonIsEmittedExactlyOnce() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
                    """,
                    """
                    {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
                    """), false);

            assertThat(chunks.stream()
                    .filter(chunk -> chunk.contains("\"finish_reason\":")
                            && !chunk.contains("\"finish_reason\":null")).count())
                    .isEqualTo(1);
        }

        /** 未知 stop_reason 原样透传，不强行归一到 stop。 */
        @Test
        void unknownStopReasonIsPassedThroughVerbatim() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"message_delta","delta":{"stop_reason":"some_new_reason"}}
                    """), false);

            assertThat(finishChunk(chunks)).contains("\"finish_reason\":\"some_new_reason\"");
        }
    }

    // ==================== usage chunk 的发出条件 ====================

    @Nested
    @DisplayName("usage chunk 依赖 include_usage")
    class UsageChunkGating {

        @Test
        void usageChunkOmittedWhenDownstreamDidNotAskForIt() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"message_start","message":{"id":"msg_1",
                     "usage":{"input_tokens":10,"output_tokens":0}}}
                    """,
                    """
                    {"type":"message_delta","delta":{"stop_reason":"end_turn"},
                     "usage":{"output_tokens":5}}
                    """), false);

            assertThat(chunks).noneMatch(chunk -> chunk.contains("\"usage\""));
        }

        @Test
        void usageChunkHasEmptyChoicesArrayAndFollowsFinishChunk() {
            List<String> chunks = collectStream(List.of(
                    """
                    {"type":"message_start","message":{"id":"msg_1",
                     "usage":{"input_tokens":10,"output_tokens":0}}}
                    """,
                    """
                    {"type":"message_delta","delta":{"stop_reason":"end_turn"},
                     "usage":{"output_tokens":5}}
                    """), true);

            int finishIndex = chunks.indexOf(finishChunk(chunks));
            int usageIndex = indexOfChunkContaining(chunks, "\"usage\"");

            assertThat(usageIndex).isGreaterThan(finishIndex);
            assertThat(chunks.get(usageIndex)).contains("\"choices\":[]");
        }
    }

    // ==================== 状态隔离 ====================

    @Nested
    @DisplayName("状态按订阅隔离")
    class StateIsolation {

        /**
         * 重试会重订阅同一个 Flux，状态必须重建。
         *
         * <p>若状态跨订阅复用，第二轮的 role 帧会缺失（sentRole 已置位）、
         * tool index 会从非零开始。
         */
        @Test
        void eachSubscriptionGetsFreshState() {
            Flux<String> upstream = Flux.just("""
                    {"type":"message_start","message":{"id":"msg_1","model":"claude-x"}}
                    """);
            Flux<String> translated = translator.translateStream(
                    upstream, UPSTREAM_MODEL, new TranslationContext(true, false));

            List<String> first = translated.collectList().block(Duration.ofSeconds(5));
            List<String> second = translated.collectList().block(Duration.ofSeconds(5));

            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            // 两次订阅都必须拿到 role 帧。
            assertThat(first.get(0)).contains("\"role\":\"assistant\"");
            assertThat(second.get(0)).contains("\"role\":\"assistant\"");
        }
    }

    // ==================== 端到端：实测事件序列 ====================

    /**
     * 用实测抓包的完整事件序列做一次端到端断言。
     *
     * <p>序列来自 O2A 落地后对 DeepSeek Anthropic 端点的真实调用，
     * 含 thinking 块占 index 0、signature_delta、text 块占 index 1。
     */
    @Test
    @DisplayName("端到端：实测的 thinking + text 序列")
    void realWorldThinkingThenTextSequence() {
        List<String> chunks = collectStream(List.of(
                """
                {"type":"message_start","message":{"id":"01fd0b35","type":"message",
                 "role":"assistant","model":"deepseek-v4-flash","content":[],
                 "stop_reason":null,"usage":{"input_tokens":88,"output_tokens":0}}}
                """,
                """
                {"type":"content_block_start","index":0,
                 "content_block":{"type":"thinking","thinking":"","signature":""}}
                """,
                "{\"type\":\"ping\"}",
                """
                {"type":"content_block_delta","index":0,
                 "delta":{"type":"thinking_delta","thinking":"只需回复一个字"}}
                """,
                """
                {"type":"content_block_delta","index":0,
                 "delta":{"type":"signature_delta","signature":"01fd0b35"}}
                """,
                """
                {"type":"content_block_stop","index":0}
                """,
                """
                {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}
                """,
                """
                {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"好"}}
                """,
                """
                {"type":"content_block_stop","index":1}
                """,
                """
                {"type":"message_delta","delta":{"stop_reason":"end_turn"},
                 "usage":{"input_tokens":88,"output_tokens":20}}
                """,
                "{\"type\":\"message_stop\"}"), true);

        // 首帧 role、思考帧、正文帧、finish 帧、usage 帧、[DONE]
        assertThat(chunks.get(0)).contains("\"role\":\"assistant\"");
        assertThat(chunkContaining(chunks, "reasoning_content")).contains("只需回复一个字");
        assertThat(chunkContaining(chunks, "\"content\":\"好\"")).contains("\"content\":\"好\"");
        assertThat(finishChunk(chunks)).contains("\"finish_reason\":\"stop\"");
        assertThat(chunkContaining(chunks, "\"usage\"")).contains("\"prompt_tokens\":88");
        assertThat(chunks.get(chunks.size() - 1)).isEqualTo("[DONE]");

        // signature 没有以任何形式出现在下游。
        assertThat(chunks).noneMatch(chunk -> chunk.contains("signature"));
        // 每帧都回显上游返回的模型名。
        assertThat(chunks.stream().filter(chunk -> !"[DONE]".equals(chunk)))
                .allMatch(chunk -> chunk.contains(UPSTREAM_MODEL));
    }

    // ==================== 落库用的批量翻译 ====================

    @Nested
    @DisplayName("落库 chunk 改写")
    class LogChunkRewrite {

        /**
         * 落库要记<strong>下游实际收到的</strong> OpenAI chunk，而非上游的 Anthropic 事件。
         *
         * <p>否则排查「客户端为什么解析失败」时，日志里没有客户端真正看到的东西。
         */
        @Test
        void logChunksAreTranslatedNotRawUpstreamEvents() {
            List<String> upstreamEvents = List.of(
                    """
                    {"type":"message_start","message":{"id":"msg_1","model":"deepseek-v4-flash"}}
                    """,
                    """
                    {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好"}}
                    """,
                    """
                    {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
                    """);

            List<String> logged = translator.translateChunksForLog(
                    upstreamEvents, UPSTREAM_MODEL, false).translated();

            // 记的是 OpenAI 形态。
            assertThat(logged).allSatisfy(chunk ->
                    assertThat(chunk).satisfiesAnyOf(
                            c -> assertThat(c).contains("chat.completion.chunk"),
                            c -> assertThat(c).isEqualTo("[DONE]")));
            // 上游的事件类型不应出现在日志里。
            assertThat(logged).noneMatch(chunk -> chunk.contains("message_start"));
            assertThat(logged).noneMatch(chunk -> chunk.contains("content_block_delta"));
            assertThat(logged.get(logged.size() - 1)).isEqualTo("[DONE]");
        }

        /** 重放用独立状态，因此可以多次调用而结果一致（幂等）。 */
        @Test
        void repeatedRewriteYieldsSameFrameCount() {
            List<String> upstreamEvents = List.of(
                    """
                    {"type":"message_start","message":{"id":"msg_1","model":"deepseek-v4-flash"}}
                    """,
                    """
                    {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
                    """);

            int first = translator.translateChunksForLog(upstreamEvents, UPSTREAM_MODEL, false)
                    .translated().size();
            int second = translator.translateChunksForLog(upstreamEvents, UPSTREAM_MODEL, false)
                    .translated().size();

            assertThat(second).isEqualTo(first);
        }

        /**
         * {@code frameCounts} 与上游事件<strong>逐项等长</strong>，是日志页两栏对齐的唯一依据。
         *
         * <p>事后从两个数组反推不出映射关系：帧数不对等（零帧/一帧/多帧），
         * 只有翻译当时的循环知道每个事件产出了几帧。
         */
        @Test
        void frameCountsHasOneEntryPerUpstreamEvent() {
            List<String> upstreamEvents = List.of(
                    """
                    {"type":"message_start","message":{"id":"msg_1","model":"deepseek-v4-flash"}}
                    """,
                    """
                    {"type":"content_block_start","index":0,"content_block":{"type":"text"}}
                    """,
                    """
                    {"type":"ping"}
                    """,
                    """
                    {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好"}}
                    """,
                    """
                    {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
                    """);

            TranslatedChunkLog log = translator.translateChunksForLog(
                    upstreamEvents, UPSTREAM_MODEL, false);

            assertThat(log.frameCounts()).hasSameSizeAs(upstreamEvents);
            // 不产帧的事件记 0，而不是被跳过 —— 否则下标就错位了。
            assertThat(log.frameCounts().get(1)).isZero();
            assertThat(log.frameCounts().get(2)).isZero();
            // 收尾帧不计入 frameCounts，差额即收尾帧数。
            int mapped = log.frameCounts().stream().mapToInt(Integer::intValue).sum();
            assertThat(log.translated()).hasSizeGreaterThan(mapped);
        }

        /** 零帧不能被折叠：全是不产帧的事件时，{@code frameCounts} 仍要逐项记 0。 */
        @Test
        void zeroFrameEventsStillOccupyASlot() {
            List<String> upstreamEvents = List.of(
                    """
                    {"type":"ping"}
                    """,
                    """
                    {"type":"ping"}
                    """);

            TranslatedChunkLog log = translator.translateChunksForLog(
                    upstreamEvents, UPSTREAM_MODEL, false);

            assertThat(log.frameCounts()).containsExactly(0, 0);
        }
    }

    // ==================== 辅助 ====================

    private JsonNode translateNonStream(String upstreamBody) throws Exception {
        return objectMapper.readTree(translateNonStreamRaw(upstreamBody));
    }

    private String translateNonStreamRaw(String upstreamBody) {
        return translator.translateResponse(Mono.just(upstreamBody))
                .block(Duration.ofSeconds(5));
    }

    private List<String> collectStream(List<String> events, boolean includeUsage) {
        List<String> collected = translator.translateStream(
                        Flux.fromIterable(events), UPSTREAM_MODEL,
                        new TranslationContext(true, includeUsage))
                .collectList()
                .block(Duration.ofSeconds(5));
        assertThat(collected).isNotNull();
        return collected;
    }

    /**
     * 找出真正带终止原因的那一帧。
     *
     * <p>不能用 {@code contains("finish_reason")} —— 每一帧都有这个键，
     * 只是中间帧的值是 JSON null。要找的是值非 null 的那一帧。
     */
    private static String finishChunk(List<String> chunks) {
        return chunks.stream()
                .filter(chunk -> !chunk.contains("\"finish_reason\":null"))
                .filter(chunk -> chunk.contains("\"finish_reason\":"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有带终止原因的 chunk，实际: " + chunks));
    }

    private static String chunkContaining(List<String> chunks, String needle) {
        return chunks.stream()
                .filter(chunk -> chunk.contains(needle))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有 chunk 含: " + needle + "，实际: " + chunks));
    }

    private static int indexOfChunkContaining(List<String> chunks, String needle) {
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).contains(needle)) {
                return i;
            }
        }
        throw new AssertionError("没有 chunk 含: " + needle + "，实际: " + chunks);
    }
}

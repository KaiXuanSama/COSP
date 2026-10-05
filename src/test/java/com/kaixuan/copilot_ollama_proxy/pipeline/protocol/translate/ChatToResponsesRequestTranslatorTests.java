package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.TranslatedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C2R 去程翻译器的字段映射全表。
 *
 * <p>组织方式镜像 {@code ChatToMessagesRequestTranslatorTests}：按映射域分
 * {@code @Nested}，每组内一个决策点一条用例。静默丢弃清单逐字段钉住 ——
 * 「以为是映射其实是丢弃」的漂移只有逐字段断言能抓住。
 *
 * <p>决策依据见 docs/features/protocol-translation/chat-responses/PLAN.md §0/§2。
 */
class ChatToResponsesRequestTranslatorTests {

    private ChatToResponsesRequestTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new ChatToResponsesRequestTranslator();
    }

    /** 便捷构造：LinkedHashMap 保序，断言可读。 */
    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private Map<String, Object> translate(Map<String, Object> downstream) {
        return translator.translateRequest(downstream).body();
    }

    // ==================== 顶层标量 ====================

    @Nested
    @DisplayName("顶层标量")
    class TopLevelScalars {

        @Test
        void copiesModelAndStreamAsIs() {
            Map<String, Object> result = translate(body(
                    "model", "gpt-x", "stream", true));

            assertThat(result.get("model")).isEqualTo("gpt-x");
            assertThat(result.get("stream")).isEqualTo(true);
        }

        @Test
        void maxCompletionTokensWinsOverMaxTokens() {
            Map<String, Object> result = translate(body(
                    "max_completion_tokens", 2048, "max_tokens", 512));

            assertThat(result.get("max_output_tokens")).isEqualTo(2048);
            assertThat(result).as("两个 Chat 名都不该残留").doesNotContainKeys("max_tokens", "max_completion_tokens");
        }

        @Test
        void maxTokensUsedWhenCompletionVariantAbsent() {
            Map<String, Object> result = translate(body("max_tokens", 512));

            assertThat(result.get("max_output_tokens")).isEqualTo(512);
        }

        @Test
        void noMaxOutputTokensWhenDownstreamDidNotSay() {
            Map<String, Object> result = translate(body());

            assertThat(result).as("不补默认值，那是设置层的职责").doesNotContainKey("max_output_tokens");
        }

        @Test
        void copiesSamplingParametersWithoutScaling() {
            Map<String, Object> result = translate(body(
                    "temperature", 0.7, "top_p", 0.9, "parallel_tool_calls", false));

            assertThat(result.get("temperature")).isEqualTo(0.7);
            assertThat(result.get("top_p")).isEqualTo(0.9);
            assertThat(result.get("parallel_tool_calls")).isEqualTo(false);
        }

        @Test
        void forcesStoreFalseAndIncludeEncryptedContent() {
            Map<String, Object> result = translate(body());

            assertThat(result.get("store")).isEqualTo(false);
            assertThat(result.get("include")).isEqualTo(List.of("reasoning.encrypted_content"));
        }

        @Test
        void neverWritesInstructions() {
            Map<String, Object> result = translate(body("messages", List.of()));

            assertThat(result).as("instructions 属支线/回程域，翻译器不写").doesNotContainKey("instructions");
        }
    }

    // ==================== 思考字段 ====================

    @Nested
    @DisplayName("reasoning_effort → reasoning.effort（改名后删原字段，与 C2M 相反）")
    class Thinking {

        @Test
        void renamesEffortIntoReasoningContainer() {
            Map<String, Object> result = translate(body("reasoning_effort", "high"));

            assertThat(result.get("reasoning")).isEqualTo(Map.of("effort", "high"));
            assertThat(result).as("Responses 侧表态判定只看 reasoning.effort，原字段必须删")
                    .doesNotContainKey("reasoning_effort");
        }

        @Test
        void doesNotInjectWhenDownstreamDidNot() {
            Map<String, Object> result = translate(body());

            assertThat(result).doesNotContainKey("reasoning");
        }

        @Test
        void doesNotWriteSummary() {
            Map<String, Object> result = translate(body("reasoning_effort", "low"));

            assertThat(result.get("reasoning"))
                    .as("summary 是显式 opt-in，不与 effort 耦合")
                    .isEqualTo(Map.of("effort", "low"));
        }

        @Test
        void doesNotCarryThinkingSwitch() {
            Map<String, Object> result = translate(body(
                    "thinking", Map.of("type", "disabled")));

            assertThat(result).as("Responses 的开关在 effort 的 none 档，thinking 是 Chat 字段")
                    .doesNotContainKey("thinking");
        }
    }

    // ==================== response_format ====================

    @Nested
    @DisplayName("response_format → text.format")
    class ResponseFormat {

        @Test
        void expandsJsonSchemaIntoFlatTextFormat() {
            Map<String, Object> schema = Map.of("type", "object", "properties", Map.of());
            Map<String, Object> result = translate(body(
                    "response_format", Map.of(
                            "type", "json_schema",
                            "json_schema", Map.of("name", "out", "schema", schema, "strict", true))));

            Map<?, ?> text = (Map<?, ?>) result.get("text");
            Map<?, ?> format = (Map<?, ?>) text.get("format");
            assertThat(format.get("type")).isEqualTo("json_schema");
            assertThat(format.get("name")).isEqualTo("out");
            assertThat(format.get("schema")).isEqualTo(schema);
            assertThat(format.get("strict")).isEqualTo(true);
        }

        @Test
        void keepsPlainTypeAsIs() {
            Map<String, Object> result = translate(body(
                    "response_format", Map.of("type", "json_object")));

            Map<?, ?> format = (Map<?, ?>) ((Map<?, ?>) result.get("text")).get("format");
            assertThat(format).isEqualTo(Map.of("type", "json_object"));
        }

        @Test
        void absentWhenDownstreamDidNotAsk() {
            Map<String, Object> result = translate(body());

            assertThat(result).doesNotContainKey("text");
        }
    }

    // ==================== 消息 → input ====================

    @Nested
    @DisplayName("messages → input（按 role 分派）")
    class MessagesToInput {

        @Test
        void keepsSystemRoleUntouchedForStage() {
            Map<String, Object> result = translate(body(
                    "messages", List.of(Map.of("role", "system", "content", "be brief"))));

            List<?> input = (List<?>) result.get("input");
            assertThat(input).hasSize(1);
            Map<?, ?> item = (Map<?, ?>) input.get(0);
            assertThat(item.get("type")).isEqualTo("message");
            assertThat(item.get("role")).as("role 改写是 ResponsesSystemPromptStage 的职责")
                    .isEqualTo("system");
        }

        @Test
        void normalizesDeveloperToSystem() {
            Map<String, Object> result = translate(body(
                    "messages", List.of(Map.of("role", "developer", "content", "dev hint"))));

            Map<?, ?> item = (Map<?, ?>) ((List<?>) result.get("input")).get(0);
            assertThat(item.get("role")).isEqualTo("system");
        }

        @Test
        void wrapsStringContentIntoInputTextBlock() {
            Map<String, Object> result = translate(body(
                    "messages", List.of(Map.of("role", "user", "content", "hello"))));

            Map<?, ?> item = (Map<?, ?>) ((List<?>) result.get("input")).get(0);
            assertThat(item.get("content"))
                    .isEqualTo(List.of(Map.of("type", "input_text", "text", "hello")));
        }

        @Test
        void assistantTextBecomesOutputText() {
            Map<String, Object> result = translate(body(
                    "messages", List.of(Map.of("role", "assistant", "content", "hi there"))));

            Map<?, ?> item = (Map<?, ?>) ((List<?>) result.get("input")).get(0);
            assertThat(item.get("role")).isEqualTo("assistant");
            assertThat(item.get("content"))
                    .isEqualTo(List.of(Map.of("type", "output_text", "text", "hi there")));
        }

        @Test
        void assistantReasoningContentBecomesReasoningItem() {
            Map<String, Object> result = translate(body(
                    "messages", List.of(Map.of(
                            "role", "assistant",
                            "content", "answer",
                            "reasoning_content", "chain of thought"))));

            List<?> input = (List<?>) result.get("input");
            assertThat(input).hasSize(2);

            Map<?, ?> reasoning = (Map<?, ?>) input.get(0);
            assertThat(reasoning.get("type")).isEqualTo("reasoning");
            assertThat(reasoning.get("summary")).isEqualTo(List.of());
            assertThat(reasoning.get("content"))
                    .isEqualTo(List.of(Map.of("type", "reasoning_text", "text", "chain of thought")));
            assertThat(reasoning.get("encrypted_content")).isNull();

            Map<?, ?> message = (Map<?, ?>) input.get(1);
            assertThat(message.get("type")).isEqualTo("message");
            assertThat(message.get("role")).isEqualTo("assistant");
        }

        @Test
        @DisplayName("reasoning_content 缺失/空白时不产 reasoning item（deepseek 硬约束只要求有思考的回传）")
        void blankReasoningContentOmitsItem() {
            Map<String, Object> result = translate(body(
                    "messages", List.of(Map.of(
                            "role", "assistant",
                            "content", "answer",
                            "reasoning_content", "  "))));

            List<?> input = (List<?>) result.get("input");
            assertThat(input).hasSize(1);
            assertThat(((Map<?, ?>) input.get(0)).get("type")).isEqualTo("message");
        }

        @Test
        @DisplayName("往返闭环：R2C 产出的 reasoning_content 回传后还原为 reasoning item（顺序在正文前）")
        void roundTripReasoningSurvives() {
            // 模拟下游（Copilot BYOK）把上一轮 R2C 给它的 reasoning_content 原样回传
            Map<String, Object> result = translate(body(
                    "messages", List.of(Map.of(
                            "role", "assistant",
                            "content", "上一轮答案",
                            "reasoning_content", "上一轮思考")),
                    "stream", false));

            List<?> input = (List<?>) result.get("input");
            assertThat(input.get(0))
                    .as("reasoning item 在 message 之前——与上游产出顺序一致")
                    .extracting(i -> ((Map<?, ?>) i).get("type"))
                    .isEqualTo("reasoning");
        }

        @Test
        void assistantToolCallsBecomeSeparateFunctionCallItems() {
            Map<String, Object> result = translate(body(
                    "messages", List.of(Map.of(
                            "role", "assistant",
                            "content", "let me check",
                            "tool_calls", List.of(Map.of(
                                    "id", "call_1",
                                    "type", "function",
                                    "function", Map.of("name", "exec", "arguments", "{\"a\":1}")))))));

            List<?> input = (List<?>) result.get("input");
            assertThat(input).hasSize(2);

            Map<?, ?> call = (Map<?, ?>) input.get(1);
            assertThat(call.get("type")).isEqualTo("function_call");
            assertThat(call.get("call_id")).isEqualTo("call_1");
            assertThat(call.get("name")).isEqualTo("exec");
            assertThat(call.get("arguments")).isEqualTo("{\"a\":1}");
            assertThat(call.containsKey("id"))
                    .as("item 级 id 不造（决策 #5）")
                    .isFalse();
        }

        @Test
        void toolMessageBecomesFunctionCallOutputPairedByCallId() {
            Map<String, Object> result = translate(body(
                    "messages", List.of(
                            Map.of("role", "assistant", "content", "",
                                    "tool_calls", List.of(Map.of(
                                            "id", "call_1", "type", "function",
                                            "function", Map.of("name", "exec", "arguments", "{}")))),
                            Map.of("role", "tool", "tool_call_id", "call_1", "content", "result text"))));

            List<?> input = (List<?>) result.get("input");
            Map<?, ?> output = (Map<?, ?>) input.get(1);
            assertThat(output.get("type")).isEqualTo("function_call_output");
            assertThat(output.get("call_id")).isEqualTo("call_1");
            assertThat(output.get("output")).isEqualTo("result text");
        }

        @Test
        void orphanToolOutputIsDroppedNotFabricated() {
            Map<String, Object> result = translate(body(
                    "messages", List.of(
                            Map.of("role", "tool", "tool_call_id", "call_missing", "content", "x"))));

            assertThat((List<?>) result.get("input"))
                    .as("配不上的孤儿结果丢弃，不造 function_call 去配")
                    .isEmpty();
        }

        @Test
        void nullContentToolOutputBecomesEmptyString() {
            Map<String, Object> result = translate(body(
                    "messages", List.of(
                            Map.of("role", "assistant", "content", "",
                                    "tool_calls", List.of(Map.of(
                                            "id", "call_1", "type", "function",
                                            "function", Map.of("name", "exec", "arguments", "{}")))),
                            Map.of("role", "tool", "tool_call_id", "call_1"))));

            Map<?, ?> output = (Map<?, ?>) ((List<?>) result.get("input")).get(1);
            assertThat(output.get("output")).isEqualTo("");
        }

        @Test
        void unknownRoleIsRejected() {
            assertThatThrownBy(() -> translate(body(
                    "messages", List.of(Map.of("role", "wizard", "content", "?")))))
                    .isInstanceOf(RequestTranslationException.class)
                    .hasMessageContaining("wizard");
        }

        @Test
        void emptyUserMessageIsOmitted() {
            Map<String, Object> result = translate(body(
                    "messages", List.of(
                            Map.of("role", "user", "content", "  "),
                            Map.of("role", "user", "content", "real"))));

            assertThat((List<?>) result.get("input")).hasSize(1);
        }
    }

    // ==================== 内容块 ====================

    @Nested
    @DisplayName("内容块白名单")
    class ContentBlocks {

        @Test
        void imageUrlBecomesInputImageAndDropsDetail() {
            Map<String, Object> result = translate(body(
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(Map.of(
                                    "type", "image_url",
                                    "image_url", Map.of(
                                            "url", "https://example.org/a.png",
                                            "detail", "high")))))));

            Map<?, ?> item = (Map<?, ?>) ((List<?>) result.get("input")).get(0);
            assertThat(item.get("content"))
                    .isEqualTo(List.of(Map.of("type", "input_image", "image_url", "https://example.org/a.png")));
        }

        @Test
        void assistantImageIsDropped() {
            Map<String, Object> result = translate(body(
                    "messages", List.of(Map.of(
                            "role", "assistant",
                            "content", List.of(Map.of(
                                    "type", "image_url",
                                    "image_url", Map.of("url", "https://example.org/a.png")))))));

            assertThat((List<?>) result.get("input")).isEmpty();
        }

        @Test
        void unknownBlockTypeIsRejected() {
            assertThatThrownBy(() -> translate(body(
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(Map.of("type", "input_video", "video_url", "x")))))))
                    .isInstanceOf(RequestTranslationException.class)
                    .hasMessageContaining("input_video");
        }
    }

    // ==================== 工具与 tool_choice ====================

    @Nested
    @DisplayName("tools 嵌套→扁平 与 tool_choice 展平")
    class Tools {

        @Test
        void flattensNestedFunctionTool() {
            Map<String, Object> result = translate(body(
                    "tools", List.of(Map.of(
                            "type", "function",
                            "function", Map.of(
                                    "name", "exec_command",
                                    "description", "run a command",
                                    "parameters", Map.of("type", "object"))))));

            List<?> tools = (List<?>) result.get("tools");
            assertThat(tools).hasSize(1);
            Map<?, ?> tool = (Map<?, ?>) tools.get(0);
            assertThat(tool.get("type")).isEqualTo("function");
            assertThat(tool.get("name")).isEqualTo("exec_command");
            assertThat(tool.get("description")).isEqualTo("run a command");
            assertThat(tool.get("parameters")).isEqualTo(Map.of("type", "object"));
        }

        @Test
        void strictExplicitValueIsPassedThrough() {
            Map<String, Object> result = translate(body(
                    "tools", List.of(Map.of(
                            "type", "function",
                            "function", Map.of("name", "t", "strict", false)))));

            Map<?, ?> tool = (Map<?, ?>) ((List<?>) result.get("tools")).get(0);
            assertThat(tool.get("strict")).isEqualTo(false);
        }

        @Test
        @DisplayName("strict 缺省时显式写 false（Chat 默认 false、Responses 默认 true，省略即改语义）")
        void strictDefaultsToExplicitFalse() {
            Map<String, Object> result = translate(body(
                    "tools", List.of(Map.of(
                            "type", "function",
                            "function", Map.of("name", "t")))));

            Map<?, ?> tool = (Map<?, ?>) ((List<?>) result.get("tools")).get(0);
            assertThat(tool.get("strict")).isEqualTo(false);
        }

        @Test
        void flattensNamedToolChoice() {
            Map<String, Object> result = translate(body(
                    "tools", List.of(Map.of(
                            "type", "function",
                            "function", Map.of("name", "t"))),
                    "tool_choice", Map.of("type", "function", "function", Map.of("name", "t"))));

            assertThat(result.get("tool_choice"))
                    .isEqualTo(Map.of("type", "function", "name", "t"));
        }

        @Test
        void stringToolChoicePassesThrough() {
            Map<String, Object> result = translate(body(
                    "tools", List.of(Map.of("type", "function", "function", Map.of("name", "t"))),
                    "tool_choice", "required"));

            assertThat(result.get("tool_choice")).isEqualTo("required");
        }

        @Test
        void toolChoicePointingToUndeclaredToolIsDropped() {
            Map<String, Object> result = translate(body(
                    "tools", List.of(Map.of("type", "function", "function", Map.of("name", "t"))),
                    "tool_choice", Map.of("type", "function", "function", Map.of("name", "other"))));

            assertThat(result).doesNotContainKey("tool_choice");
        }

        @Test
        void toolChoiceDroppedWhenNoToolsDeclared() {
            Map<String, Object> result = translate(body("tool_choice", "auto"));

            assertThat(result).as("没有工具的选择策略无意义").doesNotContainKey("tool_choice");
        }

        @Test
        void nonFunctionToolTypeIsDropped() {
            Map<String, Object> result = translate(body(
                    "tools", List.of(Map.of("type", "web_search"))));

            assertThat(result).doesNotContainKey("tools");
        }
    }

    // ==================== 硬失败 ====================

    @Nested
    @DisplayName("硬失败（报错而非静默修正）")
    class HardFailures {

        @Test
        void rejectsNGreaterThanOne() {
            assertThatThrownBy(() -> translate(body("n", 3)))
                    .isInstanceOf(RequestTranslationException.class)
                    .hasMessageContaining("n");
        }

        @Test
        void allowsNEqualsOne() {
            Map<String, Object> result = translate(body("n", 1));

            assertThat(result).doesNotContainKey("n");
        }

        @Test
        void rejectsLegacyFunctionCall() {
            assertThatThrownBy(() -> translate(body("function_call", "auto")))
                    .isInstanceOf(RequestTranslationException.class)
                    .hasMessageContaining("function_call");
        }

        @Test
        void rejectsLegacyFunctions() {
            assertThatThrownBy(() -> translate(body("functions", List.of())))
                    .isInstanceOf(RequestTranslationException.class)
                    .hasMessageContaining("functions");
        }
    }

    // ==================== TranslationContext ====================

    @Nested
    @DisplayName("TranslatedRequest.context（为回程留出口）")
    class Context {

        @Test
        void recordsStreamFlag() {
            TranslatedRequest translated = translator.translateRequest(body("stream", true));

            assertThat(translated.context().wasStream()).isTrue();
        }

        @Test
        void recordsIncludeUsage() {
            TranslatedRequest translated = translator.translateRequest(body(
                    "stream", true, "stream_options", Map.of("include_usage", true)));

            assertThat(translated.context().includeUsage()).isTrue();
        }

        @Test
        void defaultsWhenNothingDeclared() {
            TranslatedRequest translated = translator.translateRequest(body());

            assertThat(translated.context().wasStream()).isFalse();
            assertThat(translated.context().includeUsage()).isFalse();
        }
    }

    // ==================== 静默丢弃清单 ====================

    @Nested
    @DisplayName("静默丢弃清单（Chat 有、Responses 无对应物 —— 逐字段钉住）")
    class SilentDrops {

        @Test
        void dropsFieldsWithoutCounterpart() {
            Map<String, Object> result = translate(body(
                    "frequency_penalty", 0.5,
                    "presence_penalty", 0.5,
                    "logit_bias", Map.of(),
                    "logprobs", true,
                    "top_logprobs", 5,
                    "seed", 42,
                    "user", "u1",
                    "stop", List.of("END"),
                    "service_tier", "auto",
                    "stream_options", Map.of("include_usage", true)));

            assertThat(result).as("白名单制：没被显式搬运的顶层字段一律丢弃")
                    .doesNotContainKeys(
                            "frequency_penalty", "presence_penalty", "logit_bias",
                            "logprobs", "top_logprobs", "seed", "user",
                            "stop", "service_tier", "stream_options");
        }
    }
}

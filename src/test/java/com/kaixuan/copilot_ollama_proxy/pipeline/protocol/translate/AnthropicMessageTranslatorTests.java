package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicMessageTranslatorTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicMessageTranslator translator = new AnthropicMessageTranslator(mapper,
            new AnthropicContentBlockTranslator());

    @Nested
    class OrdinaryMessages {

        @Test
        void preservesStringWhitespaceAndMergesOnlyAdjacentSourceRoles() {
            List<Object> result = translator.translate(List.of(message("user", "  first  "),
                    message("user", List.of(text("second"))), message("assistant", "answer"),
                    message("user", "last")));

            assertThat(result).containsExactly(message("user", "  first  \nsecond"),
                    message("assistant", "answer"), message("user", "last"));
        }

        @Test
        void keepsMixedPartsInOrderAndPreservesTerminalAssistantPrefill() {
            List<Object> result = translator.translate(List.of(message("user", List.of(text("before"), image("x"),
                    text("after"))), message("assistant", "The answer is: ")));

            assertThat(result).containsExactly(message("user", List.of(text("before"), imagePart("x"), text("after"))),
                    message("assistant", "The answer is: "));
        }

        @Test
        void doesNotInventContentForAnExplicitEmptyMessage() {
            assertThat(translator.translate(List.of(message("user", ""))))
                    .containsExactly(message("user", ""));
        }
    }

    @Nested
    class CausalPairing {

        @Test
        void collectsAdjacentSemanticTurnsAndOrdersResultsByCallsRatherThanResultArrival() {
            List<Object> result = translator.translate(List.of(
                    message("assistant", List.of(text("first"), thinking("reason one"), use("a", "read", Map.of("x", 1)))),
                    message("assistant", List.of(thinking("reason two"), text("second"), use("b", "read", Map.of()))),
                    message("user", List.of(result("b", "B", false))),
                    message("user", List.of(text("continue"), result("a", "A", false)))));

            assertThat(result).containsExactly(assistant("first\nsecond", "reason one\nreason two",
                            List.of(call("a", "read", "{\"x\":1}"), call("b", "read", "{}"))),
                    tool("a", "A"), tool("b", "B"), message("user", "continue"));
        }

        @Test
        void canMatchEarlierCallsAcrossInterveningTextButNeverChangesNormalMessageOrder() {
            List<Object> result = translator.translate(List.of(
                    message("assistant", List.of(use("a", "read", Map.of()))),
                    message("user", "intervening"), message("assistant", "acknowledged"),
                    message("user", List.of(result("a", "A", false), text("after")))));

            assertThat(result).containsExactly(assistant("", null, List.of(call("a", "read", "{}"))),
                    tool("a", "A"), message("user", "intervening"), message("assistant", "acknowledged"),
                    message("user", "after"));
        }

        @Test
        void neverPairsAnEarlierOrphanToAFutureCallOrLeaksItsMedia() {
            List<Object> result = translator.translate(List.of(
                    message("user", List.of(result("future", List.of(image("orphan")), false))),
                    message("assistant", List.of(use("future", "read", Map.of()))), message("user", "next")));

            assertThat(result).containsExactly(message("user", "next"));
        }

        @Test
        void keepsTextButDropsUnansweredCallsAndTheirThinkingReplay() {
            List<Object> result = translator.translate(List.of(message("assistant", List.of(
                    thinking("must not replay"), text("kept"), use("unanswered", "read", Map.of())))));

            assertThat(result).containsExactly(message("assistant", "kept"));
        }

        @Test
        void dropsEmptyAssistantWhenAllCallsAreUnansweredButKeepsAnsweredCallWithEmptyResult() {
            List<Object> result = translator.translate(List.of(
                    message("assistant", List.of(use("a", "read", Map.of()), use("b", "read", Map.of()))),
                    message("user", List.of(result("b", "", false)))));

            assertThat(result).containsExactly(assistant("", null, List.of(call("b", "read", "{}"))), tool("b", ""));
            assertThat(translator.translate(List.of(message("assistant", List.of(use("a", "read", Map.of())))))).isEmpty();
        }

        @ParameterizedTest
        @ArgumentsSource(EmptyResultsProvider.class)
        void keepsEveryRealEmptyResponseAsAnAnsweredCall(Object content, boolean present) {
            Map<String, Object> toolResult = new LinkedHashMap<>();
            toolResult.put("type", "tool_result");
            toolResult.put("tool_use_id", "a");
            if (present) {
                toolResult.put("content", content);
            }

            assertThat(translator.translate(List.of(message("assistant", List.of(use("a", "read", Map.of()))),
                    message("user", List.of(toolResult)))))
                    .containsExactly(assistant("", null, List.of(call("a", "read", "{}"))), tool("a", ""));
        }

        @Test
        void handlesMultipleIndependentToolRoundsWithoutCrossRequestState() {
            List<Object> source = List.of(message("user", "start"),
                    message("assistant", List.of(use("a", "read", Map.of()))),
                    message("user", List.of(result("a", "A", false))),
                    message("assistant", List.of(use("b", "read", Map.of()))),
                    message("user", List.of(result("b", "B", false))), message("assistant", "done"));
            List<Object> expected = List.of(message("user", "start"),
                    assistant("", null, List.of(call("a", "read", "{}"))), tool("a", "A"),
                    assistant("", null, List.of(call("b", "read", "{}"))), tool("b", "B"), message("assistant", "done"));

            assertThat(translator.translate(source)).isEqualTo(expected);
            assertThat(translator.translate(source)).isEqualTo(expected);
        }
    }

    public static class EmptyResultsProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(Arguments.of(null, false), Arguments.of(null, true), Arguments.of("", true),
                    Arguments.of(List.of(), true), Arguments.of(List.of(Map.of("type", "text", "text", "")), true));
        }
    }

    @Nested
    class ThinkingHistory {

        @Test
        @ExtendWith(OutputCaptureExtension.class)
        void replaysOnlyExistingPlaintextWhenSurvivingToolCallsRemain(CapturedOutput output) {
            List<Object> result = translator.translate(List.of(message("assistant", List.of(
                            thinking("existing reason"), Map.of("type", "redacted_thinking", "data", "SENSITIVE_PAYLOAD"),
                            use("a", "read", Map.of()))), message("user", List.of(result("a", "", false)))));

            assertThat(result).containsExactly(assistant("", "existing reason", List.of(call("a", "read", "{}"))), tool("a", ""));
            assertThat(output).contains("messages[0].content[1]").doesNotContain("SENSITIVE_PAYLOAD", "existing reason");
        }

        @Test
        void doesNotReplayThinkingForTextOnlyRoundsOrInventPlaceholders() {
            assertThat(translator.translate(List.of(message("assistant", List.of(thinking("not replayed"), text("answer"))))))
                    .containsExactly(message("assistant", "answer"));
            assertThat(translator.translate(List.of(message("assistant", List.of(use("a", "read", Map.of()))),
                    message("user", List.of(result("a", "ok", false))))))
                    .containsExactly(assistant("", null, List.of(call("a", "read", "{}"))), tool("a", "ok"));
        }

        @Test
        void serializesArgumentsAsJsonWithoutRemovingBusinessSignatureOrCacheKeys() throws Exception {
            Map<String, Object> input = Map.of("cache_control", Map.of("user", true), "signature", "business",
                    "nested", List.of(Map.of("text", "quote\"\nline")));
            List<Object> result = translator.translate(List.of(message("assistant", List.of(use("a", "read", input))),
                    message("user", List.of(result("a", "ok", false)))));
            Map<?, ?> function = (Map<?, ?>) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) result.getFirst()).get("tool_calls")).getFirst()).get("function");

                assertThat(mapper.readValue((String) function.get("arguments"), new TypeReference<Map<String, Object>>() {}))
                    .isEqualTo(input);
        }

        @Test
        void missingAndNullInputMeanNoArgumentsNotAnEmptyResult() {
            Map<String, Object> missing = new LinkedHashMap<>(use("a", "read", null));
            missing.remove("input");

            assertThat(translator.translate(List.of(message("assistant", List.of(missing, use("b", "read", null))),
                    message("user", List.of(result("a", "A", false), result("b", "B", false))))))
                    .containsExactly(assistant("", null, List.of(call("a", "read", "{}"), call("b", "read", "{}"))),
                            tool("a", "A"), tool("b", "B"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotMutateDeepInputAndEveryProducedContainerIsWritable() {
        List<Object> source = List.of(message("assistant", List.of(text("kept"), use("a", "read", Map.of("x", 1)))),
                message("user", List.of(result("a", List.of(image("a")), false), text("after"))));
        Object snapshot = AnthropicContentBlockTranslator.copyJson(source);
        List<Object> output = translator.translate(source);
        ((Map<String, Object>) output.getFirst()).put("content", "changed");
        ((List<Object>) ((Map<?, ?>) output.getFirst()).get("tool_calls")).clear();
        ((List<Object>) ((Map<?, ?>) output.get(2)).get("content")).add(text("extra"));
        output.add(message("user", "new"));

        assertThat(source).isEqualTo(snapshot);
        assertThat(translator.translate(source)).hasSize(4);
    }

    @ParameterizedTest
    @MethodSource("invalidMessages")
    void reportsOriginalSourceIndicesEvenAfterTurnMerging(Object source, String path) {
        assertThatThrownBy(() -> translator.translate(source))
                .isInstanceOfSatisfying(RequestTranslationException.class,
                        error -> assertThat(error.fieldPath()).isEqualTo(path));
    }

        @Test
        void sharedTranslatorDoesNotMixConcurrentRequestsWithTheSameToolId() {
        List<CompletableFuture<List<Object>>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String value = "result-" + i;
            futures.add(CompletableFuture.supplyAsync(() -> translator.translate(List.of(
                message("assistant", List.of(use("a", "read", Map.of()))),
                message("user", List.of(result("a", value, false)))))));
        }
        for (int i = 0; i < futures.size(); i++) {
            assertThat(futures.get(i).join()).containsExactly(assistant("", null, List.of(call("a", "read", "{}"))),
                tool("a", "result-" + i));
        }
        }

        @Test
        void doesNotHideDuplicateResultByDiscardingAnEarlierOrphanFirst() {
        List<Object> source = List.of(message("user", List.of(result("a", "early orphan", false))),
            message("assistant", List.of(use("a", "read", Map.of()))),
            message("user", List.of(result("a", "late result", false))));

        assertThatThrownBy(() -> translator.translate(source))
            .isInstanceOfSatisfying(RequestTranslationException.class,
                error -> assertThat(error.fieldPath()).isEqualTo("messages[2].content[0].tool_use_id"));
        }

        @Test
        void crossRoundLateResultsAreGroupedWithTheirOwnCallsWithoutReorderingOrdinaryText() {
        List<Object> source = List.of(message("assistant", List.of(use("a", "read", Map.of()), use("b", "read", Map.of()))),
            message("user", List.of(result("b", "B", false), text("between rounds"))),
            message("assistant", List.of(use("c", "read", Map.of()))),
            message("user", List.of(result("c", "C", false), text("after rounds"), result("a", "A", false))));

        assertThat(translator.translate(source)).containsExactly(
            assistant("", null, List.of(call("a", "read", "{}"), call("b", "read", "{}"))), tool("a", "A"), tool("b", "B"),
            message("user", "between rounds"), assistant("", null, List.of(call("c", "read", "{}"))), tool("c", "C"),
            message("user", "after rounds"));
        }

        @Test
        void preservesInternalLineBreaksAndNullBusinessKeysInToolArguments() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("signature", null);
        input.put("cache_control", Map.of("user", true));
        List<Object> source = List.of(message("assistant", List.of(use("a", "read", input))),
            message("user", List.of(result("a", List.of(text("a\n"), text(""), text("\nb")), false))));

        List<Object> output = translator.translate(source);
        Map<?, ?> function = (Map<?, ?>) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) output.getFirst()).get("tool_calls")).getFirst()).get("function");

        assertThat(mapper.readValue((String) function.get("arguments"), new TypeReference<Map<String, Object>>() {})).isEqualTo(input);
        assertThat(output.get(1)).isEqualTo(tool("a", "a\n\n\nb"));
        }

    private static Stream<Arguments> invalidMessages() {
        return Stream.of(
                Arguments.of("not array", "messages"), Arguments.of(null, "messages"),
                Arguments.of(List.of("not object"), "messages[0]"),
                Arguments.of(List.of(message("system", "not Messages role")), "messages[0].role"),
                Arguments.of(List.of(message("developer", "not Messages role")), "messages[0].role"),
                Arguments.of(List.of(message("tool", "not Messages role")), "messages[0].role"),
                Arguments.of(List.of(message("user", List.of(use("a", "read", Map.of())))), "messages[0].content[0].type"),
                Arguments.of(List.of(message("assistant", List.of(result("a", "x", false)))), "messages[0].content[0].type"),
                Arguments.of(List.of(message("user", List.of(thinking("x")))), "messages[0].content[0].type"),
                Arguments.of(List.of(message("assistant", List.of(use(" ", "read", Map.of())))), "messages[0].content[0].id"),
                Arguments.of(List.of(message("assistant", List.of(use("a", " ", Map.of())))), "messages[0].content[0].name"),
                Arguments.of(List.of(message("assistant", List.of(use("a", "read", "{}")))), "messages[0].content[0].input"),
                Arguments.of(List.of(message("assistant", List.of(use("a", "read", List.of())))), "messages[0].content[0].input"),
                Arguments.of(List.of(message("user", List.of(result(" ", "x", false)))), "messages[0].content[0].tool_use_id"),
                Arguments.of(List.of(message("assistant", List.of(use("a", "read", Map.of()))),
                        message("assistant", List.of(use("a", "read", Map.of())))), "messages[1].content[0].id"),
                Arguments.of(List.of(message("assistant", List.of(use("a", "read", Map.of()))),
                        message("user", List.of(result("a", "A", false))),
                        message("user", List.of(result("a", "again", false)))), "messages[2].content[0].tool_use_id"),
                Arguments.of(List.of(message("user", List.of(result("orphan", "x", false), result("orphan", "y", false)))),
                        "messages[0].content[1].tool_use_id"),
                Arguments.of(List.of(message("assistant", List.of(use("a", "read", Map.of()))),
                        message("user", List.of(Map.of("type", "tool_result", "tool_use_id", "a", "is_error", "true")))),
                        "messages[1].content[0].is_error"));
    }

    static Map<String, Object> message(String role, Object content) {
        return Map.of("role", role, "content", content);
    }

    static Map<String, Object> text(String text) {
        return Map.of("type", "text", "text", text);
    }

    static Map<String, Object> image(String id) {
        return Map.of("type", "image", "source", Map.of("type", "url", "url", "https://example.com/" + id + ".png"));
    }

    static Map<String, Object> imagePart(String id) {
        return Map.of("type", "image_url", "image_url", Map.of("url", "https://example.com/" + id + ".png"));
    }

    static Map<String, Object> thinking(String text) {
        return Map.of("type", "thinking", "thinking", text, "signature", "not replayable");
    }

    static Map<String, Object> use(String id, String name, Object input) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_use");
        block.put("id", id);
        block.put("name", name);
        block.put("input", input);
        return block;
    }

    static Map<String, Object> result(String id, Object content, boolean error) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", id);
        block.put("content", content);
        block.put("is_error", error);
        return block;
    }

    static Map<String, Object> call(String id, String name, String arguments) {
        return Map.of("id", id, "type", "function", "function", Map.of("name", name, "arguments", arguments));
    }

    static Map<String, Object> tool(String id, String content) {
        return Map.of("role", "tool", "tool_call_id", id, "content", content);
    }

    static Map<String, Object> assistant(Object content, String reasoning, List<Object> calls) {
        Map<String, Object> message = new LinkedHashMap<>(message("assistant", content));
        message.put("tool_calls", new ArrayList<>(calls));
        if (reasoning != null) {
            message.put("reasoning_content", reasoning);
        }
        return message;
    }
}
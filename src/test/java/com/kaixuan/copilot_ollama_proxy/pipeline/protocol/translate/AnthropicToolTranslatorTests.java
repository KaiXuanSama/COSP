package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicToolTranslatorTests {

    private final AnthropicToolTranslator translator = new AnthropicToolTranslator();

    @Test
    @SuppressWarnings("unchecked")
    void copiesSchemaWithoutRemovingBusinessKeysOrRewritingConstraints() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "cache_control", Map.of("type", "string", "pattern", "^keep$"),
                "signature", false, "nested", Map.of("$ref", "#/$defs/x")),
                "required", List.of("cache_control"), "additionalProperties", false,
                "$defs", Map.of("x", Map.of("type", "string", "format", "email", "examples", List.of("a@b.c"))));
        Map<String, Object> source = Map.of("name", "read", "description", "read data", "input_schema", schema,
                "strict", true, "cache_control", Map.of("type", "ephemeral"));

        List<Object> tools = translator.translateTools(List.of(source));

        Map<String, Object> function = (Map<String, Object>) ((Map<?, ?>) tools.getFirst()).get("function");
        assertThat(function).containsEntry("name", "read").containsEntry("description", "read data")
                .containsEntry("parameters", schema).containsEntry("strict", true).doesNotContainKey("cache_control");
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        ((Map<String, Object>) parameters.get("properties")).put("new", Map.of("type", "number"));
        ((List<Object>) parameters.get("required")).add("new");
                assertThat(((Map<?, ?>) schema.get("properties")).containsKey("new")).isFalse();
        assertThat(schema.get("required")).isEqualTo(List.of("cache_control"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void onlyCarriesExplicitStrictBoolean(boolean strict) {
        var tools = translator.translateTools(List.of(Map.of("name", "read", "strict", strict)));

        assertThat(((Map<?, ?>) tools.getFirst()).get("function"))
                .isEqualTo(Map.of("name", "read", "parameters", emptySchema(), "strict", strict));
        assertThat(((Map<?, ?>) translator.translateTools(List.of(Map.of("name", "read"))).getFirst()).get("function"))
                .isEqualTo(Map.of("name", "read", "parameters", emptySchema()));
    }

    @Test
    void acceptsCustomMissingOrNullTypeAndNormalizesOnlyMissingRootSchemaKeys() {
        for (Object type : new Object[]{null, "custom"}) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", type);
            tool.put("name", "read");
            tool.put("input_schema", Map.of("required", List.of(), "additionalProperties", false));

            Map<?, ?> function = (Map<?, ?>) ((Map<?, ?>) translator.translateTools(List.of(tool)).getFirst()).get("function");

            assertThat(function.get("parameters")).isEqualTo(Map.of("type", "object", "properties", Map.of(),
                    "required", List.of(), "additionalProperties", false));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"auto", "any", "none"})
    void mapsChoiceWithoutInventingParallelSetting(String type) {
        List<Object> tools = translator.translateTools(List.of(Map.of("name", "read")));

        var choice = translator.translateChoice(Map.of("type", type), tools);

        assertThat(choice.value()).isEqualTo("any".equals(type) ? "required" : type);
        assertThat(choice.parallelToolCalls()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mapsNamedChoiceAndExplicitParallelSwitch(boolean disable) {
        List<Object> tools = translator.translateTools(List.of(Map.of("name", "read")));

        var choice = translator.translateChoice(Map.of("type", "tool", "name", "read",
                "disable_parallel_tool_use", disable), tools);

        assertThat(choice.value()).isEqualTo(Map.of("type", "function", "function", Map.of("name", "read")));
        assertThat(choice.parallelToolCalls()).isEqualTo(!disable);
    }

    @Test
    void doesNotInjectChoiceOrParallelWhenNoToolsExist() {
        assertThat(translator.translateTools(null)).isEmpty();
        assertThat(translator.translateChoice(null, List.of()).value()).isNull();
        for (String type : List.of("auto", "none")) {
            var choice = translator.translateChoice(Map.of("type", type, "disable_parallel_tool_use", false), List.of());
            assertThat(choice.value()).isNull();
            assertThat(choice.parallelToolCalls()).isNull();
        }
    }

    @Test
    void treatsDefaultCallerAndFalseDeferredLoadingAsNoOps() {
        Map<String, Object> tool = new LinkedHashMap<>(Map.of("name", "read", "defer_loading", false,
                "allowed_callers", List.of("direct", "direct")));

        assertThat(translator.translateTools(List.of(tool))).isEqualTo(List.of(Map.of("type", "function",
                "function", Map.of("name", "read", "parameters", emptySchema()))));
        tool.put("allowed_callers", null);
        tool.put("defer_loading", null);
        assertThat(translator.translateTools(List.of(tool))).hasSize(1);
    }

        @ParameterizedTest
        @MethodSource("deferredLoadingFlags")
        @ExtendWith(OutputCaptureExtension.class)
        void omitsDeferredFlagAndWarnsOnlyForTrueWithoutDroppingTool(Object flag, boolean present, CapturedOutput output) {
                Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                                "defer_loading", Map.of("type", "boolean"),
                                "config", Map.of("type", "object", "properties", Map.of("defer_loading", Map.of("type", "boolean")))),
                                "required", List.of("defer_loading"), "additionalProperties", false);
                Map<String, Object> tool = new LinkedHashMap<>(Map.of("name", "PRIVATE_TOOL_NAME",
                                "description", "PRIVATE_TOOL_DESCRIPTION", "input_schema", schema, "strict", true));
                if (present) {
                        tool.put("defer_loading", flag);
                }
                List<Object> source = List.of(tool);
                Object snapshot = AnthropicContentBlockTranslator.copyJson(source);

                assertThat(translator.translateTools(source)).isEqualTo(List.of(Map.of("type", "function", "function",
                                Map.of("name", "PRIVATE_TOOL_NAME", "description", "PRIVATE_TOOL_DESCRIPTION",
                                                "parameters", schema, "strict", true))));
                assertThat(source).isEqualTo(snapshot);
                if (Boolean.TRUE.equals(flag)) {
                        assertThat(output).containsOnlyOnce("[M2C] 工具提示字段已省略: path=tools[0].defer_loading");
                } else {
                        assertThat(output).doesNotContain("path=tools[0].defer_loading");
                }
                assertThat(output).doesNotContain("PRIVATE_TOOL_NAME", "PRIVATE_TOOL_DESCRIPTION");
        }

        @Test
        @ExtendWith(OutputCaptureExtension.class)
        void deferredWarningsKeepOriginalIndicesAndDoNotRemoveOrReorderTools(CapturedOutput output) {
                List<Object> source = new ArrayList<>();
                List<Object> expected = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                        Map<String, Object> tool = new LinkedHashMap<>(Map.of("name", "tool_" + i));
                        if (i >= 13) {
                                tool.put("defer_loading", i != 14);
                        }
                        source.add(tool);
                        expected.add(Map.of("type", "function", "function", Map.of("name", "tool_" + i,
                                        "parameters", emptySchema())));
                }
                Object snapshot = AnthropicContentBlockTranslator.copyJson(source);

                assertThat(translator.translateTools(source)).isEqualTo(expected);
                assertThat(source).isEqualTo(snapshot);
                assertThat(output).containsOnlyOnce("path=tools[13].defer_loading")
                                .containsOnlyOnce("path=tools[15].defer_loading")
                                .doesNotContain("path=tools[14].defer_loading", "tool_13", "tool_15");
        }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void dropsHintsWithPathOnlyWarningsRatherThanLoggingPayload(CapturedOutput output) {
        Map<String, Object> tool = Map.of("name", "read", "input_examples", List.of(Map.of("secret", "SENSITIVE_PAYLOAD")),
                "eager_input_streaming", true, "allowed_callers", List.of());

        assertThat(translator.translateTools(List.of(tool))).hasSize(1);

        assertThat(output).contains("tools[0].input_examples", "tools[0].eager_input_streaming", "tools[0].allowed_callers")
                .doesNotContain("SENSITIVE_PAYLOAD");
    }

    @ParameterizedTest
    @MethodSource("invalidTools")
    void rejectsUnsupportedOrMalformedToolAtSourcePath(Object tools, String path) {
        assertThatThrownBy(() -> translator.translateTools(tools))
                .isInstanceOfSatisfying(RequestTranslationException.class,
                        error -> assertThat(error.fieldPath()).isEqualTo(path));
    }

    @ParameterizedTest
    @MethodSource("invalidChoices")
    void neverSilentlyRelaxesUnfulfillableChoice(Object choice, boolean declared, String path) {
        List<Object> tools = declared ? translator.translateTools(List.of(Map.of("name", "read"))) : List.of();

        assertThatThrownBy(() -> translator.translateChoice(choice, tools))
                .isInstanceOfSatisfying(RequestTranslationException.class,
                        error -> assertThat(error.fieldPath()).isEqualTo(path));
    }

    private static Stream<Arguments> invalidTools() {
        return Stream.of(
                Arguments.of("not array", "tools"), Arguments.of(List.of("not object"), "tools[0]"),
                Arguments.of(List.of(Map.of("name", " ")), "tools[0].name"),
                Arguments.of(List.of(Map.of("name", "read", "type", "web_search_20250305")), "tools[0].type"),
                Arguments.of(List.of(Map.of("name", "read", "strict", "true")), "tools[0].strict"),
                Arguments.of(List.of(Map.of("name", "read", "input_schema", false)), "tools[0].input_schema"),
                Arguments.of(List.of(Map.of("name", "read", "defer_loading", "false")), "tools[0].defer_loading"),
                Arguments.of(List.of(Map.of("name", "read", "defer_loading", "true")), "tools[0].defer_loading"),
                Arguments.of(List.of(Map.of("name", "read", "defer_loading", "")), "tools[0].defer_loading"),
                Arguments.of(List.of(Map.of("name", "read", "defer_loading", 0)), "tools[0].defer_loading"),
                Arguments.of(List.of(Map.of("name", "read", "defer_loading", 1)), "tools[0].defer_loading"),
                Arguments.of(List.of(Map.of("name", "read", "defer_loading", List.of())), "tools[0].defer_loading"),
                Arguments.of(List.of(Map.of("name", "read", "defer_loading", Map.of())), "tools[0].defer_loading"),
                Arguments.of(List.of(Map.of("name", "first", "defer_loading", true),
                        Map.of("name", "second", "defer_loading", "true")), "tools[1].defer_loading"),
                Arguments.of(List.of(Map.of("name", "read", "defer_loading", true,
                        "allowed_callers", List.of("code_execution_20250825"))), "tools[0].allowed_callers[0]"),
                Arguments.of(List.of(Map.of("name", "read", "defer_loading", true,
                        "input_schema", false)), "tools[0].input_schema"),
                Arguments.of(List.of(Map.of("name", "read", "defer_loading", true,
                        "strict", "true")), "tools[0].strict"),
                Arguments.of(List.of(Map.of("name", "read", "defer_loading", true,
                        "eager_input_streaming", "true")), "tools[0].eager_input_streaming"),
                Arguments.of(List.of(Map.of("name", "tool_search", "defer_loading", true,
                        "type", "tool_search_tool_regex_20251119")), "tools[0].type"),
                Arguments.of(List.of(Map.of("name", "read", "allowed_callers", "direct")), "tools[0].allowed_callers"),
                Arguments.of(List.of(Map.of("name", "read", "allowed_callers", List.of(12))), "tools[0].allowed_callers[0]"),
                Arguments.of(List.of(Map.of("name", "read", "allowed_callers", List.of("code_execution_20250825"))),
                        "tools[0].allowed_callers[0]"),
                Arguments.of(List.of(Map.of("name", "read", "allowed_callers", List.of("direct", "code_execution_20250825"))),
                        "tools[0].allowed_callers[1]"));
    }

    private static Stream<Arguments> invalidChoices() {
        return Stream.of(
                Arguments.of("auto", true, "tool_choice"),
                Arguments.of(Map.of("type", "unknown"), true, "tool_choice.type"),
                Arguments.of(Map.of("type", "any"), false, "tool_choice.type"),
                Arguments.of(Map.of("type", "tool", "name", "read"), false, "tool_choice.name"),
                Arguments.of(Map.of("type", "tool", "name", "missing"), true, "tool_choice.name"),
                Arguments.of(Map.of("type", "auto", "disable_parallel_tool_use", "false"), true,
                        "tool_choice.disable_parallel_tool_use"),
                Arguments.of(Map.of("disable_parallel_tool_use", false), true, "tool_choice.type"));
    }

        private static Stream<Arguments> deferredLoadingFlags() {
                return Stream.of(Arguments.of(true, true), Arguments.of(false, true),
                                Arguments.of(null, true), Arguments.of(null, false));
        }

    private static Map<String, Object> emptySchema() {
        return Map.of("type", "object", "properties", Map.of());
    }
}
package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.RequestBodyAssembler;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.TranslationContext;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.testing.PipelineContexts;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagesToChatRequestTranslatorTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> FORMAT = Map.of("type", "json_schema", "schema",
            Map.of("type", "object", "properties", Map.of("answer", Map.of("type", "string")),
                    "required", List.of("answer"), "additionalProperties", false));
    private final MessagesToChatRequestTranslator translator = new MessagesToChatRequestTranslator(MAPPER);
    private final RequestBodyAssembler assembler = new RequestBodyAssembler(
            PipelineContexts.registryWithAllBodyStages(MAPPER), new RequestBodyRuleEngine(MAPPER));

    @Test
    void declaresMessagesDownstreamAndChatUpstream() {
        assertThat(translator.downstreamProtocol()).isEqualTo(WireProtocol.MESSAGES);
        assertThat(translator.upstreamProtocol()).isEqualTo(WireProtocol.CHAT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void extensionRolesSurviveRequestTranslationAndChatAssembly(boolean stream) {
        List<Object> messages = List.of(Map.of("role", "user", "content", "hi"),
                Map.of("role", "developer", "content", "operator instruction"),
                Map.of("role", "narrator", "content", List.of(Map.of("type", "text", "text", "context"))),
                Map.of("role", " tool ", "content", "keep role whitespace"));
        Map<String, Object> source = body("stream", stream, "messages", messages);
        Map<String, Object> original = new LinkedHashMap<>(source);
        List<Object> expected = List.of(Map.of("role", "user", "content", "hi"),
                Map.of("role", "developer", "content", "operator instruction"),
                Map.of("role", "narrator", "content", "context"),
                Map.of("role", " tool ", "content", "keep role whitespace"));

        var translated = translator.translateRequest(source);
        Map<String, Object> assembled = assemble(source, "passthrough", "high", "");

        assertThat(translated.body().get("messages")).isEqualTo(expected);
        assertThat(assembled.get("messages")).isEqualTo(expected);
        assertThat(translated.context()).isEqualTo(new TranslationContext(stream, false));
        assertThat(assembled).containsEntry("stream", stream).containsEntry("model", "model-a");
        assertThat(source).isEqualTo(original);
    }

    @Nested
    class TopLevelAndUsage {

        @Test
        void preservesValuesWithoutDefaultsOrModelBasedClampingAndDropsAuxiliaryFields() {
            Map<String, Object> request = body("model", "[stub] model-a", "stream", true, "max_tokens", -1,
                    "temperature", 1.7, "top_p", 0.4, "top_k", 12, "metadata", Map.of("user_id", "x"),
                    "service_tier", "auto", "cache_control", Map.of("type", "ephemeral"), "unknown_trace", "discard");

            Map<String, Object> result = translator.translateRequest(request).body();

            assertThat(result).containsEntry("model", "[stub] model-a").containsEntry("stream", true)
                    .containsEntry("max_tokens", -1).containsEntry("temperature", 1.7).containsEntry("top_p", 0.4)
                    .doesNotContainKeys("top_k", "metadata", "service_tier", "cache_control", "unknown_trace", "thinking", "reasoning_effort");
            assertThat(translator.translateRequest(body()).body()).doesNotContainKeys("max_tokens", "temperature", "thinking");
        }

        @ParameterizedTest
        @ArgumentsSource(StreamFlagsProvider.class)
        void onlyStreamingM2cInjectsUsageAndContextAlwaysReadsSource(Object stream, boolean present) {
            Map<String, Object> request = body("stream_options", Map.of("include_usage", false, "extra", "do not copy"));
            if (present) {
                request.put("stream", stream);
            }

            var translated = translator.translateRequest(request);

            assertThat(translated.context()).isEqualTo(new TranslationContext(Boolean.TRUE.equals(stream), false));
            if (Boolean.TRUE.equals(stream)) {
                assertThat(translated.body().get("stream_options")).isEqualTo(Map.of("include_usage", true));
            } else {
                assertThat(translated.body()).doesNotContainKey("stream_options");
            }
            assertThat(request.get("stream_options")).isEqualTo(Map.of("include_usage", false, "extra", "do not copy"));
        }

        @Test
        void sourceIncludeUsageIsNotOverwrittenByAnUpstreamOption() {
            Map<String, Object> request = body("stream", true, "stream_options", Map.of("include_usage", true));

            assertThat(translator.translateRequest(request).context()).isEqualTo(TranslationContext.fromDownstreamBody(request));
        }

        @Test
        void leavesSystemAtItsIntermediateLocationUntilTheChatStageRuns() {
            List<Object> system = List.of(Map.of("type", "text", "text", "x-anthropic-billing-header: changing;"),
                    Map.of("type", "text", "text", "first"), Map.of("type", "text", "text", "second"));
            Map<String, Object> source = body("system", system);

            Map<String, Object> translated = translator.translateRequest(source).body();
            Map<String, Object> assembled = assemble(source, "passthrough", "high", "");

            assertThat(translated).containsEntry("system", system);
            assertThat(assembled).doesNotContainKey("system").containsEntry("model", "model-a");
            assertThat(assembled.get("messages")).isEqualTo(List.of(Map.of("role", "system", "content", "first\n\nsecond"),
                    Map.of("role", "user", "content", "hi")));
            assertThat(source).containsEntry("system", system);
        }

        @Test
        void userChatRulesCanTurnOffInjectedUsageAndTheirNullIsCleanedLast() {
            String rules = """
                    {"version":2,"groups":[{"id":"g","name":"g","enabled":true,"order":0,
                     "protocols":["CHAT"],"templateKeys":[],"previewBody":{},"rules":[
                      {"id":"usage","order":0,"field":"stream_options","array":false,"conditional":false,
                       "conditionMode":"all","conditions":[],"operations":[{"type":"edit_object","rules":[
                        {"id":"include","order":0,"field":"include_usage","array":false,"conditional":false,
                         "conditionMode":"all","conditions":[],"operations":[{"type":"set_value","value":false}]}]}]},
                      {"id":"null","order":1,"field":"temperature","array":false,"conditional":false,
                       "conditionMode":"all","conditions":[],"operations":[{"type":"set_value","value":null}]}]}]}
                    """;

            Map<String, Object> result = assemble(body("stream", true, "temperature", 0.5), "passthrough", "high", rules);

            assertThat(result.get("stream_options")).isEqualTo(Map.of("include_usage", false));
            assertThat(result).doesNotContainKey("temperature");
        }

        @ParameterizedTest
        @ValueSource(strings = {"container", "mcp_servers", "context_management"})
        void serverFieldsAreDroppedRegardlessOfValueInBothStreamModes(String field) {
            for (boolean stream : new boolean[]{false, true}) {
            Map<String, Object> expected = translator.translateRequest(body("stream", stream)).body();
            for (Object value : new Object[]{null, List.of(), Map.of(), Map.of("id", "x"),
                List.of(Map.of("name", "x")), "x", "", false, true, 12}) {
                Map<String, Object> source = body(field, value, "stream", stream);
                Map<String, Object> original = new LinkedHashMap<>(source);

                assertThat(translator.translateRequest(source).body()).isEqualTo(expected).doesNotContainKey(field);
                assertThat(source).isEqualTo(original);
            }
            }
        }

        @Test
        void dropsOnlyTopLevelServerFieldsAndPreservesNamesInSchemasAndToolInputs() throws Exception {
            Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "container", Map.of("type", "string"), "mcp_servers", Map.of("type", "array"),
                "context_management", Map.of("type", "object")));
            Map<String, Object> input = Map.of("container", "business-container", "mcp_servers", List.of("business-server"),
                "context_management", Map.of("business", true));
            Map<String, Object> source = body("container", Map.of("id", "server-container"),
                "mcp_servers", List.of(Map.of("name", "remote")),
                "context_management", Map.of("edits", List.of(Map.of("type", "clear_thinking_20251015"))),
                "tools", List.of(Map.of("name", "read", "input_schema", schema)),
                "output_config", Map.of("format", Map.of("type", "json_schema", "schema", schema)),
                "messages", List.of(AnthropicMessageTranslatorTests.message("assistant", List.of(
                        AnthropicMessageTranslatorTests.use("a", "read", input))),
                    AnthropicMessageTranslatorTests.message("user", List.of(
                        AnthropicMessageTranslatorTests.result("a", "ok", false)))));
            Map<String, Object> original = new LinkedHashMap<>(source);

            Map<String, Object> output = translator.translateRequest(source).body();

            assertThat(output).doesNotContainKeys("container", "mcp_servers", "context_management")
                .containsEntry("tools", List.of(Map.of("type", "function", "function",
                    Map.of("name", "read", "parameters", schema))))
                .containsEntry("response_format", Map.of("type", "json_schema", "json_schema",
                    Map.of("name", "cosp_output", "strict", true, "schema", schema)));
            Map<?, ?> assistant = (Map<?, ?>) ((List<?>) output.get("messages")).getFirst();
            Map<?, ?> call = (Map<?, ?>) ((List<?>) assistant.get("tool_calls")).getFirst();
            Map<?, ?> function = (Map<?, ?>) call.get("function");
            assertThat(MAPPER.readTree((String) function.get("arguments"))).isEqualTo(MAPPER.valueToTree(input));
            assertThat(source).isEqualTo(original);
        }

        @Test
        void stopArraysKeepOrderSingleElementAndEmptyStringWithoutSorting() {
            assertThat(translator.translateRequest(body("stop_sequences", List.of("Z", "", "A"))).body())
                    .containsEntry("stop", List.of("Z", "", "A"));
            assertThat(translator.translateRequest(body("stop_sequences", List.of("stop"))).body())
                    .containsEntry("stop", List.of("stop"));
            assertThat(translator.translateRequest(body("stop_sequences", List.of())).body()).doesNotContainKey("stop");
            assertThat(translator.translateRequest(body("stop_sequences", null)).body()).doesNotContainKey("stop");
            assertErrorPath(body("stop_sequences", "stop"), "stop_sequences");
            assertErrorPath(body("stop_sequences", List.of("valid", 12)), "stop_sequences[1]");
        }
    }

    @Nested
    class ThinkingAndFormats {

        @ParameterizedTest
        @ValueSource(strings = {"disabled", "enabled", "adaptive"})
        void onlyMapsThinkingSwitchNotBudgetToEffort(String type) {
            Map<String, Object> source = body("thinking", Map.of("type", type, "budget_tokens", 98765));

            assertThat(translator.translateRequest(source).body())
                    .containsEntry("thinking", Map.of("type", "disabled".equals(type) ? "disabled" : "enabled"))
                    .doesNotContainKeys("reasoning_effort", "output_config");
        }

        @Test
        void keepsExplicitNullOpinionsUntilTheSettingsStage() {
            assertThat(translator.translateRequest(body("thinking", null)).body()).containsEntry("thinking", null);
            assertThat(translator.translateRequest(body("output_config", null)).body()).containsEntry("reasoning_effort", null);
            assertThat(translator.translateRequest(body("output_config", fields("effort", null))).body())
                    .containsEntry("reasoning_effort", null);
            assertThat(translator.translateRequest(body("output_config", Map.of())).body()).doesNotContainKey("reasoning_effort");
        }

        @Test
        void translatesEffortAndSchemaIndependentlyWithoutChangingConstraints() {
            Map<String, Object> source = body("output_config", Map.of("effort", "unknown-future-effort", "format", FORMAT));

            Map<String, Object> result = translator.translateRequest(source).body();

            assertThat(result).containsEntry("reasoning_effort", "unknown-future-effort")
                    .containsEntry("response_format", expectedFormat()).doesNotContainKey("output_config");
            assertThat(translator.translateRequest(body("thinking", Map.of("type", "disabled"),
                    "output_config", Map.of("effort", "low"))).body())
                    .containsEntry("thinking", Map.of("type", "disabled")).containsEntry("reasoning_effort", "low");
        }

        @Test
        void convertsLegacyOutputFormatWithSameLogicAndRejectsDuplicateFormatEvenNull() {
            assertThat(translator.translateRequest(body("output_format", FORMAT)).body())
                    .containsEntry("response_format", expectedFormat()).doesNotContainKey("output_format");
            assertThat(translator.translateRequest(body("output_format", FORMAT, "output_config", Map.of("effort", "low"))).body())
                    .containsEntry("response_format", expectedFormat()).containsEntry("reasoning_effort", "low");
            assertErrorPath(body("output_format", FORMAT, "output_config", Map.of("format", FORMAT)), "output_format");
            assertErrorPath(body("output_format", null, "output_config", fields("format", null)), "output_format");
            assertThat(translator.translateRequest(body("output_format", null)).body()).doesNotContainKey("response_format");
        }

        @ParameterizedTest(name = "{0} × {1} × config={2}")
        @MethodSource("com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.MessagesToChatRequestTranslatorTests#opinionModes")
        void settingsRespectMappedOpinionAcrossAllModes(String opinion, String mode, String effort) {
            Map<String, Object> source = opinionBody(opinion);
            Map<String, Object> result = assemble(source, mode, effort, "");
            Map<String, Object> expected = mappedOpinion(opinion);
            if ("delete".equals(mode)) {
                expected.clear();
            } else if ("override".equals(mode) || ("fallback".equals(mode) && expected.isEmpty())) {
                expected.clear();
                if ("off".equals(effort)) {
                    expected.put("thinking", Map.of("type", "disabled"));
                } else {
                    expected.put("reasoning_effort", effort);
                }
            }
            expected.values().removeIf(value -> value == null);

            for (String key : List.of("thinking", "reasoning_effort")) {
                if (expected.containsKey(key)) {
                    assertThat(result).containsEntry(key, expected.get(key));
                } else {
                    assertThat(result).doesNotContainKey(key);
                }
            }
            if ("format-only".equals(opinion)) {
                assertThat(result).containsEntry("response_format", expectedFormat());
            }
            assertThat(source).isEqualTo(opinionBody(opinion));
        }

        @ParameterizedTest
        @ArgumentsSource(InvalidConfigsProvider.class)
        void configErrorsCarrySourcePath(Map<String, Object> source, String path) {
            assertErrorPath(source, path);
        }
    }

    @Test
    void wiresToolsChoicesMessagesAndUsageWithoutCrossDirectionTypes() {
        Map<String, Object> source = body("tools", List.of(Map.of("name", "read", "strict", false)),
                "tool_choice", Map.of("type", "any", "disable_parallel_tool_use", true), "stream", true,
                "messages", List.of(AnthropicMessageTranslatorTests.message("assistant", List.of(
                        AnthropicMessageTranslatorTests.thinking("existing"), AnthropicMessageTranslatorTests.use("a", "read", null))),
                        AnthropicMessageTranslatorTests.message("user", List.of(AnthropicMessageTranslatorTests.result("a", "", false)))));

        Map<String, Object> output = translator.translateRequest(source).body();

        assertThat(output).containsEntry("tool_choice", "required").containsEntry("parallel_tool_calls", false)
                .containsEntry("stream_options", Map.of("include_usage", true));
        assertThat(output.get("messages")).isEqualTo(List.of(AnthropicMessageTranslatorTests.assistant("", "existing",
                List.of(AnthropicMessageTranslatorTests.call("a", "read", "{}"))), AnthropicMessageTranslatorTests.tool("a", "")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void outputIsMutableDeepCopyAndRepeatTranslationIsDeterministic() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("signature", false,
                "cache_control", Map.of("type", "string")), "required", List.of("cache_control"));
        Map<String, Object> source = body("system", List.of(Map.of("type", "text", "text", "prompt")),
                "output_config", Map.of("format", Map.of("type", "json_schema", "schema", schema)));
        Map<String, Object> original = new LinkedHashMap<>(source);
        Map<String, Object> target = translator.translateRequest(source).body();
        ((Map<String, Object>) ((List<?>) target.get("system")).getFirst()).put("text", "changed");
        Map<String, Object> wrapper = (Map<String, Object>) ((Map<?, ?>) target.get("response_format")).get("json_schema");
        ((List<Object>) ((Map<?, ?>) wrapper.get("schema")).get("required")).add("new");

        assertThat(source).isEqualTo(original);
        assertThat(((Map<?, ?>) source.get("output_config")).get("format"))
                .isEqualTo(Map.of("type", "json_schema", "schema", schema));
        assertThat(translator.translateRequest(source).body()).isEqualTo(translator.translateRequest(source).body());
    }

    @ParameterizedTest
    @MethodSource("opinionModes")
    void settingModesNeverDeleteHistoricalReasoningOfAValidToolRound(String opinion, String mode, String effort) {
        Map<String, Object> source = opinionBody(opinion);
        source.put("messages", List.of(AnthropicMessageTranslatorTests.message("assistant", List.of(
                        AnthropicMessageTranslatorTests.thinking("existing history"), AnthropicMessageTranslatorTests.use("a", "read", Map.of()))),
                AnthropicMessageTranslatorTests.message("user", List.of(AnthropicMessageTranslatorTests.result("a", "ok", false)))));

        Map<String, Object> result = assemble(source, mode, effort, "");

        assertThat(((Map<?, ?>) ((List<?>) result.get("messages")).getFirst()).get("reasoning_content"))
                .isEqualTo("existing history");
    }

    public static class StreamFlagsProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(Arguments.of(true, true), Arguments.of(false, true),
                    Arguments.of(null, true), Arguments.of(null, false));
        }
    }

    public static class InvalidConfigsProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(Arguments.of(body("thinking", "disabled"), "thinking"),
                    Arguments.of(body("thinking", Map.of("type", "unknown")), "thinking.type"),
                    Arguments.of(body("thinking", Map.of()), "thinking.type"),
                    Arguments.of(body("output_config", "low"), "output_config"),
                    Arguments.of(body("output_config", Map.of("effort", 12)), "output_config.effort"),
                    Arguments.of(body("output_config", Map.of("format", "json")), "output_config.format"),
                    Arguments.of(body("output_format", Map.of("type", "json_object")), "output_format.type"),
                    Arguments.of(body("output_format", Map.of("type", "json_schema")), "output_format.schema"));
        }
    }

    private Map<String, Object> assemble(Map<String, Object> source, String mode, String effort, String rules) {
        var translated = translator.translateRequest(source);
        var provider = new ProviderRuntimeConfiguration("stub", "https://example.invalid/v1", "fixture", List.of(
                new ProviderRuntimeModel("model-a", 32768, true, true,
                        "{\"reasoning_effort\":\"" + effort + "\",\"overwrite_mode\":\"" + mode + "\"}")), "[]", rules);
        var ctx = RequestPipelineContext.of(translated.body(), "[stub] model-a", WireProtocol.MESSAGES,
                WireProtocol.CHAT, provider, HttpHeaders.EMPTY, "test", translated.context(),
                Boolean.TRUE.equals(source.get("stream")));
        assembler.assemble(ctx);
        return ctx.body();
    }

    private void assertErrorPath(Map<String, Object> source, String path) {
        assertThatThrownBy(() -> translator.translateRequest(source))
                .isInstanceOfSatisfying(RequestTranslationException.class,
                        error -> assertThat(error.fieldPath()).isEqualTo(path));
    }

    private static Stream<Arguments> opinionModes() {
        List<Arguments> cases = new ArrayList<>();
        for (String opinion : List.of("omitted", "disabled", "enabled", "adaptive", "thinking-null", "low", "effort-null",
                "config-null", "empty-config", "format-only", "disabled-and-low")) {
            for (String mode : List.of("override", "fallback", "passthrough", "delete")) {
                for (String effort : List.of("high", "off")) {
                    cases.add(Arguments.of(opinion, mode, effort));
                }
            }
        }
        return cases.stream();
    }

    private static Map<String, Object> opinionBody(String opinion) {
        return switch (opinion) {
            case "disabled", "enabled", "adaptive" -> body("thinking", Map.of("type", opinion));
            case "thinking-null" -> body("thinking", null);
            case "low" -> body("output_config", Map.of("effort", "low"));
            case "effort-null" -> body("output_config", fields("effort", null));
            case "config-null" -> body("output_config", null);
            case "empty-config" -> body("output_config", Map.of());
            case "format-only" -> body("output_config", Map.of("format", FORMAT));
            case "disabled-and-low" -> body("thinking", Map.of("type", "disabled"), "output_config", Map.of("effort", "low"));
            default -> body();
        };
    }

    private static Map<String, Object> mappedOpinion(String opinion) {
        return switch (opinion) {
            case "disabled" -> fields("thinking", Map.of("type", "disabled"));
            case "enabled", "adaptive" -> fields("thinking", Map.of("type", "enabled"));
            case "thinking-null" -> fields("thinking", null);
            case "low" -> fields("reasoning_effort", "low");
            case "effort-null", "config-null" -> fields("reasoning_effort", null);
            case "disabled-and-low" -> fields("thinking", Map.of("type", "disabled"), "reasoning_effort", "low");
            default -> fields();
        };
    }

    private static Map<String, Object> expectedFormat() {
        return Map.of("type", "json_schema", "json_schema", Map.of("name", "cosp_output", "strict", true, "schema", FORMAT.get("schema")));
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = fields("model", "[stub] model-a", "messages", List.of(Map.of("role", "user", "content", "hi")));
        body.putAll(fields(pairs));
        return body;
    }

    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((String) pairs[i], pairs[i + 1]);
        }
        return fields;
    }
}
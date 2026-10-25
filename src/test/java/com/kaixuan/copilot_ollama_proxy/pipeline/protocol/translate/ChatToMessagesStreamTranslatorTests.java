package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ResponseTranslationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatToMessagesStreamTranslatorTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatToMessagesStreamTranslator translator = new ChatToMessagesStreamTranslator(mapper);
    private final C2MStreamState state = new C2MStreamState("msg_fallback", "upstream-model");

    @Test
    void emptyDeltaStartsMessageOnceWithoutEmptyTextBlock() throws Exception {
        List<JsonNode> first = accept("""
                {"id":"chatcmpl_original","model":"upstream-alias","choices":[{"delta":{"role":"assistant"}}]}
                """);

        assertThat(types(first)).containsExactly("message_start");
        JsonNode message = first.get(0).path("message");
        assertThat(message.path("id").asText()).isEqualTo("chatcmpl_original");
        assertThat(message.path("model").asText()).isEqualTo("upstream-alias");
        assertThat(message.path("content")).isEmpty();
        assertThat(message.path("stop_reason").isNull()).isTrue();
        assertThat(message.path("stop_sequence").isNull()).isTrue();
        assertThat(accept(chunk(Map.of(), null))).isEmpty();
    }

    @Test
    void textAndThinkingSwitchBlocksButKeepIndividualDeltas() throws Exception {
        List<JsonNode> output = new ArrayList<>();
        output.addAll(accept(chunk(Map.of("reasoning_content", "先"), null)));
        output.addAll(accept(chunk(Map.of("reasoning_content", "想"), null)));
        output.addAll(accept(chunk(Map.of("reasoning_content", "", "content", "答"), null)));
        output.addAll(accept(chunk(Map.of("content", "案"), null)));
        output.addAll(accept(chunk(Map.of("reasoning_content", "补思考"), null)));
        output.addAll(accept("[DONE]"));

        assertThat(blockTypes(output)).containsExactly("thinking", "text", "thinking");
        assertThat(blockIndexes(output)).containsExactly(0, 1, 2);
        assertThat(deltaTexts(output, "thinking_delta", "thinking")).containsExactly("先", "想", "补思考");
        assertThat(deltaTexts(output, "text_delta", "text")).containsExactly("答", "案");
        assertThat(output).noneMatch(node -> node.toString().contains("signature"));
        assertValidSingleBlockStream(output);
    }

    @Test
    void reasoningAndTextInSameChunkBothSurvive() throws Exception {
        List<JsonNode> output = new ArrayList<>(accept(chunk(Map.of("reasoning_content", "思考", "content", "答案"), null)));
        output.addAll(accept("[DONE]"));

        assertThat(blockTypes(output)).containsExactly("thinking", "text");
        assertThat(deltaTexts(output, "thinking_delta", "thinking")).containsExactly("思考");
        assertThat(deltaTexts(output, "text_delta", "text")).containsExactly("答案");
        assertValidSingleBlockStream(output);
    }

    @Test
    void activeToolArgumentsAreSentBeforeFinishAndKeepEscapedFragmentBoundaries() throws Exception {
        List<String> fragments = List.of("{\"path\":\"C:\\", "\\temp\",\"word\":\"\\u", "4e2d\"}");
        List<JsonNode> output = new ArrayList<>();
        output.addAll(accept(chunk(Map.of("reasoning_content", "先想"), null)));
        List<JsonNode> start = accept(tool(7, "call_original", "write_file", null));
        output.addAll(start);
        assertThat(start.get(1).path("index").asInt()).isEqualTo(1);

        for (String fragment : fragments) {
            List<JsonNode> current = accept(tool(7, null, null, fragment));
            assertThat(types(current)).containsExactly("content_block_delta");
            assertThat(current.get(0).path("index").asInt()).isEqualTo(1);
            assertThat(current.get(0).path("delta").path("partial_json").asText()).isEqualTo(fragment);
            output.addAll(current);
        }
        assertThat(types(output)).doesNotContain("message_delta", "message_stop");
        output.addAll(accept(chunk(Map.of(), "tool_calls")));
        output.addAll(accept("[DONE]"));

        assertThat(deltaTexts(output, "input_json_delta", "partial_json")).isEqualTo(fragments);
        assertThat(mapper.readTree(String.join("", fragments)).path("word").asText()).isEqualTo("中");
        assertValidSingleBlockStream(output);
    }

    @Test
    void parallelToolsAreSerializedWithoutDeltaAfterStopAndWithoutMergingFragments() throws Exception {
        List<JsonNode> output = new ArrayList<>(accept(chunk(Map.of("tool_calls", List.of(
                call(4, "call_a", "tool_a", "{\"a\":"), call(9, "call_b", "tool_b", "{\"b\":"))), null)));
        output.addAll(accept(tool(9, null, null, "2}")));
        output.addAll(accept(tool(4, null, null, "1}")));

        assertThat(blockTypes(output)).containsExactly("tool_use");
        output.addAll(accept(chunk(Map.of(), "tool_calls")));
        output.addAll(accept("[DONE]"));

        assertThat(blockIndexes(output)).containsExactly(0, 1);
        assertThat(argumentsFor(output, 0)).containsExactly("{\"a\":", "1}");
        assertThat(argumentsFor(output, 1)).containsExactly("{\"b\":", "2}");
        assertValidSingleBlockStream(output);
    }

    @Test
    void toolMetadataAndTextInOneChunkAreNotDroppedByBranchPriority() throws Exception {
        List<JsonNode> output = new ArrayList<>(accept(chunk(Map.of("content", "前文", "reasoning_content", "推理",
                "tool_calls", List.of(call(0, "call_a", "tool_a", "{}"))), null)));
        output.addAll(accept("[DONE]"));

        assertThat(blockTypes(output)).containsExactly("thinking", "text", "tool_use");
        assertThat(deltaTexts(output, "text_delta", "text")).containsExactly("前文");
        assertValidSingleBlockStream(output);
    }

    @Test
    void buffersInterleavedTextThinkingAndOtherToolsWhileActiveToolStillStreams() throws Exception {
        List<JsonNode> output = new ArrayList<>(accept(tool(0, "call_a", "tool_a", "{\"a\":")));
        assertThat(accept(chunk(Map.of("content", "后"), null))).isEmpty();
        assertThat(accept(chunk(Map.of("content", "文"), null))).isEmpty();
        assertThat(accept(chunk(Map.of("reasoning_content", "补想"), null))).isEmpty();
        assertThat(accept(tool(1, "call_b", "tool_b", "{\"b\":"))).isEmpty();
        assertThat(accept(tool(1, null, null, "2}"))).isEmpty();
        output.addAll(accept(tool(0, null, null, "1}")));
        assertThat(blockTypes(output)).containsExactly("tool_use");

        output.addAll(accept(chunk(Map.of(), "tool_calls")));
        output.addAll(accept("[DONE]"));

        assertThat(blockTypes(output)).containsExactly("tool_use", "tool_use", "text", "thinking");
        assertThat(deltaTexts(output, "text_delta", "text")).containsExactly("后", "文");
        assertThat(deltaTexts(output, "thinking_delta", "thinking")).containsExactly("补想");
        assertThat(argumentsFor(output, 0)).containsExactly("{\"a\":", "1}");
        assertThat(argumentsFor(output, 1)).containsExactly("{\"b\":", "2}");
        assertValidSingleBlockStream(output);
    }

    @Test
    void waitsForIdAndNameAndFlushesPendingArgumentsAsSeparateDeltas() throws Exception {
        List<JsonNode> output = new ArrayList<>(accept(tool(5, null, null, "{\"q\":")));
        assertThat(types(output)).containsExactly("message_start");
        assertThat(accept(tool(5, null, "lookup", "\"value\""))).isEmpty();
        List<JsonNode> announced = accept(tool(5, "call_late", null, "}"));
        output.addAll(announced);

        assertThat(types(announced)).containsExactly("content_block_start", "content_block_delta",
                "content_block_delta", "content_block_delta");
        assertThat(announced.get(0).path("content_block").path("name").asText()).isEqualTo("lookup");
        assertThat(argumentsFor(output, 0)).containsExactly("{\"q\":", "\"value\"", "}");
        output.addAll(accept("[DONE]"));
        assertValidSingleBlockStream(output);
    }

    @Test
    void missingMetadataGetsFallbackOnlyAtFinalizeAndEmptyInputGetsOneObjectDelta() throws Exception {
        List<JsonNode> output = new ArrayList<>(accept(tool(6, "call_6", null, null)));
        assertThat(blockTypes(output)).isEmpty();
        output.addAll(accept("[DONE]"));

        JsonNode start = output.stream().filter(node -> "content_block_start".equals(node.path("type").asText()))
                .findFirst().orElseThrow();
        assertThat(start.path("content_block").path("id").asText()).isEqualTo("call_6");
        assertThat(start.path("content_block").path("name").asText()).isEqualTo("tool_6");
        assertThat(argumentsFor(output, 0)).containsExactly("{}");
        assertValidSingleBlockStream(output);
    }

    @Test
    void repeatedBlankToolMetadataCannotOverwriteAnnouncedIdentity() throws Exception {
        List<JsonNode> output = new ArrayList<>(accept(tool(0, "call_keep", "name_keep", null)));
        output.addAll(accept(tool(0, "call_other", "name_other", "{}")));
        output.addAll(accept(tool(0, "", "", "")));
        output.addAll(accept("[DONE]"));

        assertThat(output.toString()).contains("call_keep", "name_keep").doesNotContain("call_other", "name_other");
        assertThat(argumentsFor(output, 0)).containsExactly("{}");
        assertValidSingleBlockStream(output);
    }

    @Test
    void finishClosesBlocksButDelaysMessageDeltaUntilUsageTailAndStopsExactlyOnce() throws Exception {
        List<JsonNode> output = new ArrayList<>(accept(chunk(Map.of("content", "答案"), null)));
        List<JsonNode> finish = accept(chunk(Map.of(), "stop"));
        output.addAll(finish);
        assertThat(types(finish)).containsExactly("content_block_stop");
        assertThat(accept(chunk(Map.of(), "stop"))).isEmpty();
        List<JsonNode> tail = accept("""
                {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":7,
                 "prompt_tokens_details":{"cached_tokens":40,"cache_write_tokens":20}}}
                """);
        output.addAll(tail);

        assertThat(types(tail)).containsExactly("message_delta", "message_stop");
        assertThat(tail.get(0).path("usage").path("input_tokens").asInt()).isEqualTo(40);
        assertThat(tail.get(0).path("usage").path("output_tokens").asInt()).isEqualTo(7);
        assertThat(tail.get(0).path("usage").path("cache_read_input_tokens").asInt()).isEqualTo(40);
        assertThat(accept("[DONE]")).isEmpty();
        assertThat(translator.finalizeStream(state)).isEmpty();
        assertValidSingleBlockStream(output);
    }

    @Test
    void usageInFinishChunkIsCollectedButNotTreatedAsTrailingUsage() throws Exception {
        ObjectNode finish = (ObjectNode) mapper.readTree(chunk(Map.of("content", "答案"), "stop"));
        finish.putObject("usage").put("prompt_tokens", 12).put("completion_tokens", 3);
        List<JsonNode> output = new ArrayList<>(accept(finish.toString()));

        assertThat(types(output)).doesNotContain("message_delta", "message_stop");
        output.addAll(accept("[DONE]"));
        assertThat(output.get(output.size() - 2).path("usage").path("input_tokens").asInt()).isEqualTo(12);
        assertValidSingleBlockStream(output);
    }

    @Test
    void usageOnlyAtStartDoesNotFinishGeneration() throws Exception {
        assertThat(types(accept("{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":0}}")))
                .containsExactly("message_start");
        List<JsonNode> output = accept(chunk(Map.of("content", "答案"), null));
        assertThat(types(output)).containsExactly("content_block_start", "content_block_delta");
    }

    @Test
    void normalEofWithoutDoneFinalizesOpenBlocksAndRetainsUsage() throws Exception {
        List<JsonNode> output = new ArrayList<>(accept(chunk(Map.of("content", "答案"), null)));
        output.addAll(parse(translator.finalizeStream(state)));

        assertThat(types(output.subList(output.size() - 3, output.size())))
                .containsExactly("content_block_stop", "message_delta", "message_stop");
        assertThat(output.get(output.size() - 2).path("delta").path("stop_reason").asText()).isEqualTo("end_turn");
        assertValidSingleBlockStream(output);
    }

    @Test
    void unknownUsageCannotEraseKnownUsageAndRealZeroCanReplaceIt() throws Exception {
        accept("{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3}}");
        accept("{\"choices\":[],\"usage\":{\"credit\":9}}");
        assertThat(state.displayUsage()).containsEntry("input_tokens", 10L).containsEntry("output_tokens", 3);
        accept("{\"choices\":[],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}");
        assertThat(state.displayUsage()).containsEntry("input_tokens", 0L).containsEntry("output_tokens", 0);
    }

    @ParameterizedTest
    @CsvSource({"stop,end_turn", "length,max_tokens", "content_filter,end_turn", "tool_calls,tool_use",
            "function_call,tool_use", "unknown,end_turn"})
    void terminalReasonMappingDoesNotChangeUntilMessageDelta(String upstream, String downstream) throws Exception {
        List<JsonNode> before = accept(chunk(Map.of("content", "ok"), upstream));
        assertThat(types(before)).doesNotContain("message_delta");
        List<JsonNode> tail = accept("[DONE]");
        assertThat(tail.get(0).path("delta").path("stop_reason").asText()).isEqualTo(downstream);
    }

    @Test
    void emittedToolWithoutFinishReasonUsesToolUseAtEof() throws Exception {
        accept(tool(0, "call_1", "ping", null));
        List<JsonNode> tail = parse(translator.finalizeStream(state));

        assertThat(tail.get(tail.size() - 2).path("delta").path("stop_reason").asText()).isEqualTo("tool_use");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "[]", "{\"choices\":{}}",
            "{\"choices\":[{\"delta\":[]}]}"})
    void malformedEventsAreTranslationFailuresNotSilentSuccess(String data) {
        assertThatThrownBy(() -> translator.translateEvent(data, state)).isInstanceOf(ResponseTranslationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"index\":-1,\"id\":\"call\"}", "{\"index\":0.5,\"id\":\"call\"}",
            "{\"index\":\"0\",\"id\":\"call\"}", "{\"index\":0,\"function\":[]}",
            "{\"index\":0,\"function\":{\"arguments\":{}}}"})
    void malformedToolStructureDoesNotGetSilentEmptyInputFallback(String tool) {
        String data = "{\"choices\":[{\"delta\":{\"tool_calls\":[" + tool + "]}}]}";

        assertThatThrownBy(() -> translator.translateEvent(data, state)).isInstanceOf(ResponseTranslationException.class);
    }

    @Test
    void additionalPayloadAfterFinishCannotBeEmittedOnStoppedBlock() throws Exception {
        accept(chunk(Map.of("content", "done"), "stop"));

        assertThatThrownBy(() -> translator.translateEvent(chunk(Map.of("content", "late"), null), state))
                .isInstanceOf(ResponseTranslationException.class).hasMessageContaining("finish_reason");
    }

    @Test
    void inBandErrorIsVisibleAndDoesNotProduceSuccessfulMessageStop() throws Exception {
        accept(chunk(Map.of("content", "half"), null));

        assertThatThrownBy(() -> translator.translateEvent("{\"error\":{\"message\":\"boom\"}}", state))
                .isInstanceOf(ResponseTranslationException.class).hasMessageContaining("boom");
        assertThat(state.finalized()).isFalse();
    }

    @Test
    void normalizersCanLeaveControlFramesAndWhitespaceUntouched() throws Exception {
        assertThat(accept("{\"type\":\"ping\"}")).isEmpty();
        assertThat(accept(" ")).isEmpty();
        List<JsonNode> output = accept(chunk(Map.of("content", " \n"), null));
        assertThat(deltaTexts(output, "text_delta", "text")).containsExactly(" \n");
    }

    private String chunk(Map<String, Object> delta, String finishReason) throws Exception {
        ObjectNode root = mapper.createObjectNode().put("id", "chatcmpl_original").put("model", "upstream-alias");
        ObjectNode choice = root.putArray("choices").addObject();
        choice.set("delta", mapper.valueToTree(delta));
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        }
        return root.toString();
    }

    private String tool(int index, String id, String name, String arguments) throws Exception {
        return chunk(Map.of("tool_calls", List.of(call(index, id, name, arguments))), null);
    }

    private ObjectNode call(int index, String id, String name, String arguments) {
        ObjectNode result = mapper.createObjectNode().put("index", index);
        if (id != null) {
            result.put("id", id);
        }
        ObjectNode function = result.putObject("function");
        if (name != null) {
            function.put("name", name);
        }
        if (arguments != null) {
            function.put("arguments", arguments);
        }
        return result;
    }

    private List<JsonNode> accept(String data) throws Exception {
        return parse(translator.translateEvent(data, state));
    }

    private List<JsonNode> parse(List<String> values) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (String value : values) {
            result.add(mapper.readTree(value));
        }
        return result;
    }

    private static List<String> types(List<JsonNode> output) {
        return output.stream().map(node -> node.path("type").asText()).toList();
    }

    private static List<String> blockTypes(List<JsonNode> output) {
        return output.stream().filter(node -> "content_block_start".equals(node.path("type").asText()))
                .map(node -> node.path("content_block").path("type").asText()).toList();
    }

    private static List<Integer> blockIndexes(List<JsonNode> output) {
        return output.stream().filter(node -> "content_block_start".equals(node.path("type").asText()))
                .map(node -> node.path("index").asInt()).toList();
    }

    private static List<String> deltaTexts(List<JsonNode> output, String type, String field) {
        return output.stream().filter(node -> type.equals(node.path("delta").path("type").asText()))
                .map(node -> node.path("delta").path(field).asText()).toList();
    }

    private static List<String> argumentsFor(List<JsonNode> output, int blockIndex) {
        return output.stream().filter(node -> "input_json_delta".equals(node.path("delta").path("type").asText())
                        && node.path("index").asInt() == blockIndex)
                .map(node -> node.path("delta").path("partial_json").asText()).toList();
    }

    /** 逐帧校验生命周期：不能同时开两个块，不能 start 前或 stop 后发 delta。 */
    private static void assertValidSingleBlockStream(List<JsonNode> output) {
        Integer open = null;
        int nextIndex = 0;
        for (JsonNode event : output) {
            switch (event.path("type").asText()) {
                case "content_block_start" -> {
                    assertThat(open).as("新块打开前旧块必须关闭").isNull();
                    assertThat(event.path("index").asInt()).isEqualTo(nextIndex++);
                    open = event.path("index").asInt();
                }
                case "content_block_delta" -> assertThat(open).as("delta 必须归属于当前打开的块")
                        .isEqualTo(event.path("index").asInt());
                case "content_block_stop" -> {
                    assertThat(open).isEqualTo(event.path("index").asInt());
                    open = null;
                }
                case "message_delta", "message_stop" -> assertThat(open).isNull();
                default -> {
                }
            }
        }
        assertThat(open).isNull();
        assertThat(types(output).stream().filter("message_start"::equals)).hasSize(1);
        assertThat(types(output).stream().filter("message_delta"::equals)).hasSize(1);
        assertThat(types(output).stream().filter("message_stop"::equals)).hasSize(1);
    }
}
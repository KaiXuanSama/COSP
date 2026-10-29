package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ResponseTranslationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatToMessagesNonStreamTranslatorTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatToMessagesNonStreamTranslator translator = new ChatToMessagesNonStreamTranslator(mapper);

    @Test
    void preservesUpstreamIdentityAndBuildsMessagesEnvelope() throws Exception {
        JsonNode result = translate("""
                {"id":"chatcmpl_upstream","model":"upstream-alias","choices":[
                  {"message":{"role":"assistant","content":"答案"},"finish_reason":"stop"}]}
                """);

        assertThat(result.path("id").asText()).isEqualTo("chatcmpl_upstream");
        assertThat(result.path("model").asText()).isEqualTo("upstream-alias");
        assertThat(result.path("type").asText()).isEqualTo("message");
        assertThat(result.path("role").asText()).isEqualTo("assistant");
        assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("答案");
        assertThat(result.path("stop_sequence").isNull()).isTrue();
        assertThat(result.has("choices")).isFalse();
    }

    @Test
    void emitsReasoningBeforeTextWithoutInventingSignature() throws Exception {
        JsonNode blocks = translate("""
                {"choices":[{"message":{"reasoning_content":"先想一下","content":"答案"},"finish_reason":"stop"}]}
                """).path("content");

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).path("type").asText()).isEqualTo("thinking");
        assertThat(blocks.get(0).path("thinking").asText()).isEqualTo("先想一下");
        assertThat(blocks.get(0).has("signature")).isFalse();
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("text");
    }

    @Test
    void textArraysAndRefusalPreserveOrderAndWhitespace() throws Exception {
        JsonNode content = translate("""
                {"choices":[{"message":{"content":[{"type":"text","text":" "},
                 {"type":"output_text","text":"第二段"},{"type":"refusal","refusal":"拒绝"}],
                 "refusal":"补充"},"finish_reason":"content_filter"}]}
                """).path("content");

        assertThat(content).hasSize(4);
        assertThat(content.get(0).path("text").asText()).isEqualTo(" ");
        assertThat(content.get(1).path("text").asText()).isEqualTo("第二段");
        assertThat(content.get(2).path("text").asText()).isEqualTo("拒绝");
        assertThat(content.get(3).path("text").asText()).isEqualTo("补充");
    }

    @Test
    void toolInputIsObjectAndMissingNamesGetFallbacks() throws Exception {
        JsonNode result = translate("""
                {"choices":[{"message":{"tool_calls":[
                  {"id":"call_original","function":{"arguments":"{\\"q\\":\\"北京\\"}"}},
                  {"function":{"name":"ping","arguments":""}}]},"finish_reason":"tool_calls"}]}
                """);

        JsonNode blocks = result.path("content");
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).path("id").asText()).isEqualTo("call_original");
        assertThat(blocks.get(0).path("name").asText()).isEqualTo("tool_0");
        assertThat(blocks.get(0).path("input").path("q").asText()).isEqualTo("北京");
        assertThat(blocks.get(1).path("id").asText()).startsWith("toolu_cosp_");
        assertThat(blocks.get(1).path("input").isObject()).isTrue();
        assertThat(blocks.get(1).path("input")).isEmpty();
        assertThat(result.path("stop_reason").asText()).isEqualTo("tool_use");
    }

    @Test
    void generatedToolIdsAreStablePerMessageButDistinctAcrossConversationTurns() throws Exception {
        String template = "{\"id\":\"%s\",\"choices\":[{\"message\":{\"tool_calls\":["
                + "{\"function\":{\"name\":\"ping\",\"arguments\":\"{}\"}}]}}]}";
        String first = translate(template.formatted("chatcmpl_first")).path("content").get(0).path("id").asText();
        String replay = translate(template.formatted("chatcmpl_first")).path("content").get(0).path("id").asText();
        String second = translate(template.formatted("chatcmpl_second")).path("content").get(0).path("id").asText();

        assertThat(first).startsWith("toolu_cosp_").isEqualTo(replay).isNotEqualTo(second);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "null", "[]", "[1]", "\"text\"", "12"})
    void invalidOrNonObjectArgumentsBecomeEmptyObject(String arguments) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode tool = mapper.createObjectNode().put("id", "call_1");
        tool.putObject("function").put("name", "ping").put("arguments", arguments);
        root.putArray("choices").addObject().putObject("message").putArray("tool_calls").add(tool);

        JsonNode input = translate(root.toString()).path("content").get(0).path("input");

        assertThat(input.isObject()).isTrue();
        assertThat(input).isEmpty();
    }

    @Test
    void reasoningOnlyGetsVisibleTextFallbackButToolRoundsDoNot() throws Exception {
        JsonNode pure = translate("""
                {"choices":[{"message":{"reasoning_content":"仅思考","content":null}}]}
                """).path("content");
        JsonNode tool = translate("""
                {"choices":[{"message":{"reasoning_content":"调工具","tool_calls":[
                 {"id":"call_1","function":{"name":"ping","arguments":"{}"}}]}}]}
                """).path("content");

        assertThat(pure).hasSize(2);
        assertThat(pure.get(0).path("thinking").asText()).isEqualTo("仅思考");
        assertThat(pure.get(1).path("text").asText()).isEqualTo("仅思考");
        assertThat(tool).hasSize(2);
        assertThat(tool.get(1).path("type").asText()).isEqualTo("tool_use");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "{}", "{\"choices\":[]}", "{\"choices\":null}"})
    void emptyBodyOrChoicesGetsEmptyMessageAfterNativeGate(String body) throws Exception {
        JsonNode result = translate(body);

        assertThat(result.path("content")).hasSize(1);
        assertThat(result.path("content").get(0).path("text").asText()).isEmpty();
        assertThat(result.path("stop_reason").asText()).isEqualTo("end_turn");
        assertThat(result.path("id").asText()).startsWith("msg_cosp_");
        assertThat(result.path("usage").path("input_tokens").asInt()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"stop,end_turn", "length,max_tokens", "tool_calls,tool_use", "function_call,tool_use",
            "content_filter,end_turn", "unknown,end_turn"})
    void finishReasonsUseConfirmedMapping(String upstream, String downstream) throws Exception {
        JsonNode result = translate("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\""
                + upstream + "\"}]}");

        assertThat(result.path("stop_reason").asText()).isEqualTo(downstream);
    }

    @Test
    void firstChoiceOnlyIsUsedWithoutMergingAlternatives() throws Exception {
        JsonNode content = translate("""
                {"choices":[{"message":{"content":"第一候选"}}, {"message":{"content":"第二候选"}}]}
                """).path("content");

        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("text").asText()).isEqualTo("第一候选");
    }

    @Test
    void usageConvertsToFreshInputAndCacheBuckets() throws Exception {
        JsonNode usage = translate("""
                {"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":100,"completion_tokens":7,
                 "prompt_tokens_details":{"cached_tokens":40,"cache_write_tokens":20},"credit":0.1}}
                """).path("usage");

        assertThat(usage.path("input_tokens").asInt()).isEqualTo(40);
        assertThat(usage.path("output_tokens").asInt()).isEqualTo(7);
        assertThat(usage.path("cache_read_input_tokens").asInt()).isEqualTo(40);
        assertThat(usage.path("cache_creation_input_tokens").asInt()).isEqualTo(20);
        assertThat(usage.has("credit")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "null", "[]", "{\"choices\":{}}", "{\"choices\":[{}]}",
            "{\"choices\":[{\"message\":null}]}", "{\"error\":{\"message\":\"boom\"}}"})
    void malformedResponsesFailWithTranslationException(String body) {
        assertThatThrownBy(() -> translator.translate(body)).isInstanceOf(ResponseTranslationException.class);
    }

    private JsonNode translate(String body) throws Exception {
        return mapper.readTree(translator.translate(body));
    }
}
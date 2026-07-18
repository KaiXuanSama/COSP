package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodyRuleEngineTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RequestBodyRuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RequestBodyRuleEngine(objectMapper);
    }

    @Test
    void transformsNestedObjectAndPreservesOriginalInput() {
        Map<String, Object> input = Map.of(
                "stream_options", Map.of("include_usage", true, "keep", "value"),
                "model", "original");

        RequestBodyRuleEngine.TransformResult result = engine.transform(input, """
                {
                  "version": 1,
                  "rules": [{
                    "id":"stream-options", "order":0, "field":"stream_options", "array":false,
                    "conditional":false, "conditionMode":"all", "conditions":[],
                    "operations":[{"type":"edit_object","rules":[
                      {"id":"disable-usage","order":0,"field":"include_usage","array":false,
                       "conditional":false,"conditionMode":"all","conditions":[],
                       "operations":[{"type":"set_value","value":false}]},
                      {"id":"drop-keep","order":1,"field":"keep","array":false,
                       "conditional":false,"conditionMode":"all","conditions":[],
                       "operations":[{"type":"delete"}]}
                    ]}]
                  }]
                }
                """);

        assertThat(result.warnings()).isEmpty();
        assertThat(result.output()).containsEntry("model", "original");
        Map<?, ?> outputOptions = (Map<?, ?>) result.output().get("stream_options");
        Map<?, ?> inputOptions = (Map<?, ?>) input.get("stream_options");
        assertThat(outputOptions.get("include_usage")).isEqualTo(false);
        assertThat(outputOptions.containsKey("keep")).isFalse();
        assertThat(inputOptions.get("include_usage")).isEqualTo(true);
        assertThat(inputOptions.get("keep")).isEqualTo("value");
    }

    @Test
    void arrayRuleUsesAllConditionsAndOnlyChangesMatchingObjectElements() {
        Map<String, Object> input = Map.of("messages", List.of(
                Map.of("role", "tool", "content", List.of(Map.of(
                        "type", "image_url", "image_url", Map.of("url", "data:image/png;base64,abc"))),
                        "tool_call_id", "call-image"),
                Map.of("role", "tool", "content", "plain tool response", "tool_call_id", "call-text"),
                Map.of("role", "user", "content", List.of(Map.of(
                        "type", "image_url", "image_url", Map.of("url", "data:image/png;base64,def"))),
                        "tool_call_id", "call-user")));

        RequestBodyRuleEngine.TransformResult result = engine.transform(input, mimoImageToolRuleSet());

        List<Map<String, Object>> messages = messages(result.output());
        assertThat(messages.get(0)).containsEntry("role", "user").doesNotContainKey("tool_call_id");
        assertThat(messages.get(0)).extractingByKey("content").isInstanceOf(List.class);
        assertThat(((Map<?, ?>) ((List<?>) messages.get(0).get("content")).get(0)).get("image_url"))
                .isEqualTo(Map.of("url", "data:image/png;base64,abc"));
        assertThat(messages.get(1)).containsEntry("role", "tool").containsEntry("tool_call_id", "call-text");
        assertThat(messages.get(2)).containsEntry("role", "user").containsEntry("tool_call_id", "call-user");
    }

    @Test
    void appliesRulesInAscendingOrderRegardlessOfJsonArrayOrder() {
        Map<String, Object> input = Map.of("temperature", 0.1);

        RequestBodyRuleEngine.TransformResult result = engine.transform(input, """
                {"version":1,"rules":[
                  {"id":"set-second","order":10,"field":"temperature","array":false,"conditional":false,
                   "conditionMode":"all","conditions":[],"operations":[{"type":"set_value","value":0.8}]},
                  {"id":"set-first","order":0,"field":"temperature","array":false,"conditional":false,
                   "conditionMode":"all","conditions":[],"operations":[{"type":"set_value","value":0.2}]}
                ]}
                """);

        assertThat(result.output()).containsEntry("temperature", 0.8);
    }

    @Test
    void equalsConditionUsesJsonTypesAndDoesNotMatchStringAgainstNumber() {
        Map<String, Object> input = Map.of("max_tokens", 10, "reasoning_effort", "medium");

        RequestBodyRuleEngine.TransformResult result = engine.transform(input, """
                {"version":1,"rules":[
                  {"id":"number-match","order":0,"field":"reasoning_effort","array":false,"conditional":true,
                   "conditionMode":"all","conditions":[{"path":"./max_tokens","operator":"equals","value":10}],
                   "operations":[{"type":"set_value","value":"high"}]},
                  {"id":"string-mismatch","order":1,"field":"max_tokens","array":false,"conditional":true,
                   "conditionMode":"all","conditions":[{"path":"./reasoning_effort","operator":"equals","value":10}],
                   "operations":[{"type":"set_value","value":20}]}
                ]}
                """);

        assertThat(result.output()).containsEntry("reasoning_effort", "high").containsEntry("max_tokens", 10);
    }

    @Test
    void returnsWarningsForUnsupportedArrayOperationsAndInvalidObjectTarget() {
        Map<String, Object> input = Map.of("messages", List.of(Map.of("role", "user")), "temperature", 0.1);

        RequestBodyRuleEngine.TransformResult result = engine.transform(input, """
                {"version":1,"rules":[
                  {"id":"array-set","order":0,"field":"messages","array":true,"conditional":false,
                   "conditionMode":"all","conditions":[],"operations":[{"type":"set_value","value":"ignored"}]},
                  {"id":"bad-edit","order":1,"field":"temperature","array":false,"conditional":false,
                   "conditionMode":"all","conditions":[],"operations":[{"type":"edit_object","rules":[]}]}
                ]}
                """);

        assertThat(result.output()).containsEntry("temperature", 0.1);
        assertThat(result.warnings()).extracting(RequestBodyRuleEngine.TransformWarning::message)
                .contains("数组模式下\"设置字段值\"应通过嵌套规则定位字段", "字段值不是对象，无法执行\"调整对象内容\"");
    }

    @Test
    void invalidRuleSetReturnsDeepCopyAndConciseWarning() {
        Map<String, Object> input = Map.of("model", "mimo-v2.5-pro", "nested", Map.of("enabled", true));

        RequestBodyRuleEngine.TransformResult result = engine.transform(input, "{\"version\":2,\"rules\":[]}");

        assertThat(result.output()).isEqualTo(input).isNotSameAs(input);
        assertThat(result.warnings()).singleElement()
                .extracting(RequestBodyRuleEngine.TransformWarning::message)
                .isEqualTo("请求体规则集必须是 version=1 且包含 rules 数组，已跳过");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> messages(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("messages");
    }

    private String mimoImageToolRuleSet() {
        return """
                {
                  "version":1,
                  "rules":[{
                    "id":"mimo-messages","order":0,"field":"messages","array":true,"conditional":true,
                    "conditionMode":"all","conditions":[
                      {"path":"./content[*]/image_url","operator":"exists","value":null},
                      {"path":"./role","operator":"equals","value":"tool"}
                    ],
                    "operations":[{"type":"edit_object","rules":[
                      {"id":"mimo-role","order":0,"field":"role","array":false,"conditional":false,
                       "conditionMode":"all","conditions":[],"operations":[{"type":"set_value","value":"user"}]},
                      {"id":"mimo-tool-call-id","order":1,"field":"tool_call_id","array":false,"conditional":false,
                       "conditionMode":"all","conditions":[],"operations":[{"type":"delete"}]}
                    ]}]
                  }]
                }
                """;
    }
}

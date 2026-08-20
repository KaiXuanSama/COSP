package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
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

    /**
     * 默认走 OpenAI 线路的简便入口。
     *
     * <p>大多数用例验证的是规则**执行语义**（路径、条件、操作、顺序），
     * 与协议无关；每处都写上协议入参只会遮蔽真正在验证的东西。
     * 协议筛选由尾部一组专门的用例覆盖。
     */
    private RequestBodyRuleEngine.TransformResult transform(Map<String, Object> input, String bodyRulesJson) {
        return engine.transform(input, bodyRulesJson, WireProtocol.OPENAI);
    }

    @Test
    void transformsNestedObjectAndPreservesOriginalInput() {
        Map<String, Object> input = Map.of(
                "stream_options", Map.of("include_usage", true, "keep", "value"),
                "model", "original");

        RequestBodyRuleEngine.TransformResult result = transform(input, """
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

        RequestBodyRuleEngine.TransformResult result = transform(input, mimoImageToolRuleSet());

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

        RequestBodyRuleEngine.TransformResult result = transform(input, """
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

        RequestBodyRuleEngine.TransformResult result = transform(input, """
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

        RequestBodyRuleEngine.TransformResult result = transform(input, """
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

    /**
     * 根结构无法识别时原样放行。
     *
     * <p>这里用 {@code version:2} 配 {@code rules}（V2 该用 {@code groups}）—— 版本号对得上
     * 而载荷键对不上，是「手工编辑规则 JSON 时最容易犯的错」，也是引擎唯一需要保守放行的场景。
     */
    @Test
    void invalidRuleSetReturnsDeepCopyAndConciseWarning() {
        Map<String, Object> input = Map.of("model", "mimo-v2.5-pro", "nested", Map.of("enabled", true));

        RequestBodyRuleEngine.TransformResult result = transform(input, "{\"version\":2,\"rules\":[]}");

        assertThat(result.output()).isEqualTo(input).isNotSameAs(input);
        assertThat(result.warnings()).singleElement()
                .extracting(RequestBodyRuleEngine.TransformWarning::message)
                .isEqualTo("请求体规则集必须是 version=1 含 rules 或 version=2 含 groups，已跳过");
    }

    /** V2 规则组按组的 order 依次执行，组内再按规则的 order。 */
    @Test
    void ruleGroupsAreExecutedInGroupOrderThenRuleOrder() {
        Map<String, Object> input = Map.of("marker", "none");

        RequestBodyRuleEngine.TransformResult result = transform(input, """
                {"version":2,"groups":[
                  {"id":"second","name":"\u540e\u6267\u884c","order":1,"enabled":true,
                   "protocols":["OPENAI"],"templateKeys":["custom"],"previewBody":{},
                   "rules":[{"id":"r2","order":0,"field":"marker","array":false,"conditional":false,
                    "conditionMode":"all","conditions":[],
                    "operations":[{"type":"set_value","value":"last-wins"}]}]},
                  {"id":"first","name":"\u5148\u6267\u884c","order":0,"enabled":true,
                   "protocols":["OPENAI"],"templateKeys":["custom"],"previewBody":{},
                   "rules":[{"id":"r1","order":0,"field":"marker","array":false,"conditional":false,
                    "conditionMode":"all","conditions":[],
                    "operations":[{"type":"set_value","value":"first"}]}]}
                ]}
                """);

        assertThat(result.output()).containsEntry("marker", "last-wins");
        assertThat(result.warnings()).isEmpty();
    }

    /** 已禁用的规则组整组跳过。 */
    @Test
    void disabledRuleGroupIsSkipped() {
        Map<String, Object> input = Map.of("marker", "kept");

        RequestBodyRuleEngine.TransformResult result = transform(input, """
                {"version":2,"groups":[
                  {"id":"off","name":"\u5df2\u7981\u7528","order":0,"enabled":false,
                   "protocols":["OPENAI"],"templateKeys":["custom"],"previewBody":{},
                   "rules":[{"id":"r1","order":0,"field":"marker","array":false,"conditional":false,
                    "conditionMode":"all","conditions":[],
                    "operations":[{"type":"set_value","value":"should-not-apply"}]}]}
                ]}
                """);

        assertThat(result.output()).containsEntry("marker", "kept");
    }

    /** 空 groups 是合法的 V2 规则集，不产生警告。 */
    @Test
    void emptyRuleGroupsProduceNoWarning() {
        Map<String, Object> input = Map.of("model", "m");

        RequestBodyRuleEngine.TransformResult result = transform(input, "{\"version\":2,\"groups\":[]}");

        assertThat(result.output()).isEqualTo(input);
        assertThat(result.warnings()).isEmpty();
    }

    // ==================== 协议筛选 ====================

    /** 只执行 protocols 包含当前线路的组。 */
    @Test
    void onlyGroupsDeclaringCurrentProtocolAreExecuted() {
        Map<String, Object> input = Map.of("marker", "none");
        String rules = """
                {"version":2,"groups":[
                  {"id":"openai-only","name":"o","order":0,"enabled":true,
                   "protocols":["OPENAI"],"templateKeys":["custom"],"previewBody":{},
                   "rules":[{"id":"r1","order":0,"field":"marker","array":false,"conditional":false,
                    "conditionMode":"all","conditions":[],
                    "operations":[{"type":"set_value","value":"from-openai"}]}]},
                  {"id":"anthropic-only","name":"a","order":1,"enabled":true,
                   "protocols":["ANTHROPIC"],"templateKeys":["custom"],"previewBody":{},
                   "rules":[{"id":"r2","order":0,"field":"marker","array":false,"conditional":false,
                    "conditionMode":"all","conditions":[],
                    "operations":[{"type":"set_value","value":"from-anthropic"}]}]}
                ]}
                """;

        assertThat(engine.transform(input, rules, WireProtocol.OPENAI).output())
                .containsEntry("marker", "from-openai");
        assertThat(engine.transform(input, rules, WireProtocol.ANTHROPIC).output())
                .containsEntry("marker", "from-anthropic");
    }

    /**
     * {@code protocols} 缺失视为全协议适用。
     *
     * <p>一个 V2 组能存在说明它写于协议概念之后，作者省略该字段更可能是「没在意」。
     */
    @Test
    void groupWithoutProtocolsFieldAppliesToEveryProtocol() {
        Map<String, Object> input = Map.of("marker", "none");
        String rules = """
                {"version":2,"groups":[
                  {"id":"g","name":"g","order":0,"enabled":true,
                   "templateKeys":["custom"],"previewBody":{},
                   "rules":[{"id":"r1","order":0,"field":"marker","array":false,"conditional":false,
                    "conditionMode":"all","conditions":[],
                    "operations":[{"type":"set_value","value":"applied"}]}]}
                ]}
                """;

        assertThat(engine.transform(input, rules, WireProtocol.OPENAI).output())
                .containsEntry("marker", "applied");
        assertThat(engine.transform(input, rules, WireProtocol.ANTHROPIC).output())
                .containsEntry("marker", "applied");
    }

    /**
     * {@code protocols} 为空数组的组永不执行。
     *
     * <p>空数组是显式的「哪条都不要」，与字段缺失是不同意图。
     */
    @Test
    void groupWithEmptyProtocolsNeverApplies() {
        Map<String, Object> input = Map.of("marker", "kept");
        String rules = """
                {"version":2,"groups":[
                  {"id":"g","name":"g","order":0,"enabled":true,
                   "protocols":[],"templateKeys":["custom"],"previewBody":{},
                   "rules":[{"id":"r1","order":0,"field":"marker","array":false,"conditional":false,
                    "conditionMode":"all","conditions":[],
                    "operations":[{"type":"set_value","value":"should-not-apply"}]}]}
                ]}
                """;

        assertThat(engine.transform(input, rules, WireProtocol.OPENAI).output())
                .containsEntry("marker", "kept");
        assertThat(engine.transform(input, rules, WireProtocol.ANTHROPIC).output())
                .containsEntry("marker", "kept");
    }

    /**
     * V1 规则集只在 OpenAI 线路执行。
     *
     * <p>V1 规则的字段路径是照 OpenAI 请求体写的，作用在 Anthropic 请求体上
     * 多数匹配不到 —— 静默失效比不执行更难排查。
     */
    @Test
    void legacyV1RuleSetIsSkippedOnNonOpenAiProtocol() {
        Map<String, Object> input = Map.of("temperature", 0.1);
        String rules = """
                {"version":1,"rules":[
                  {"id":"r1","order":0,"field":"temperature","array":false,"conditional":false,
                   "conditionMode":"all","conditions":[],"operations":[{"type":"set_value","value":0.9}]}
                ]}
                """;

        assertThat(engine.transform(input, rules, WireProtocol.OPENAI).output())
                .containsEntry("temperature", 0.9);
        assertThat(engine.transform(input, rules, WireProtocol.ANTHROPIC).output())
                .containsEntry("temperature", 0.1);
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

package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则引擎的路径解析与边界语义。
 *
 * <h2>为何单独一个测试类</h2>
 * 这些用例原本是前端 {@code engine.spec.ts} / {@code path.spec} 的覆盖 —— 那份 TS 引擎
 * 在预览接口化后被删除，但它测到的语义仍然有效，且多数是**后端此前没测过的边界**：
 * 条件路径对 null / false / 0 / 空字符串的存在性判定、空数组通配符、
 * 缺失字段上的各类操作。搬过来而不是随实现一起删掉，否则这次收敛会顺带丢掉覆盖。
 *
 * <p>与 {@link RequestBodyRuleEngineTests} 分开：那个类验证规则集结构、组顺序与协议筛选，
 * 这个类验证单条规则的取值与匹配语义。混在一起会让两组关注点互相淹没。
 */
class RequestBodyRuleEnginePathSemanticsTests {

    private RequestBodyRuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RequestBodyRuleEngine(new ObjectMapper());
    }

    // ==================== 条件路径的存在性判定 ====================

    /**
     * 字段值为 null / false / 0 / 空字符串时，{@code exists} 仍判为存在。
     *
     * <p>「存在」问的是键在不在，不是值真不真。若按真值判定，
     * 一条针对 {@code temperature} 的规则会在 {@code temperature: 0} 时莫名不生效。
     */
    @Test
    void existsConditionTreatsFalsyValuesAsPresent() {
        for (Object falsy : List.of(false, 0, "")) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("probe", falsy);
            input.put("marker", "before");

            assertThat(transformWithExistsOn(input, "./probe"))
                    .as("falsy=%s", falsy)
                    .containsEntry("marker", "after");
        }

        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("probe", null);
        withNull.put("marker", "before");
        assertThat(transformWithExistsOn(withNull, "./probe")).containsEntry("marker", "after");
    }

    /** 键不存在时 {@code exists} 判为不存在。 */
    @Test
    void existsConditionFailsWhenKeyIsAbsent() {
        Map<String, Object> input = Map.of("marker", "before");

        assertThat(transformWithExistsOn(input, "./absent")).containsEntry("marker", "before");
    }

    /**
     * 空数组的通配符路径判为不存在。
     *
     * <p>{@code ./items[*]/x} 的语义是「某个元素上有 x」；没有元素自然就没有满足者。
     */
    @Test
    void arrayWildcardOnEmptyArrayDoesNotExist() {
        Map<String, Object> input = Map.of("items", List.of(), "marker", "before");

        assertThat(transformWithExistsOn(input, "./items[*]/x")).containsEntry("marker", "before");
    }

    /** 通配符路径只要有一个元素满足即判为存在。 */
    @Test
    void arrayWildcardMatchesWhenAnyElementHasTheField() {
        Map<String, Object> input = Map.of(
                "content", List.of(Map.of("type", "text"), Map.of("type", "image_url", "image_url", Map.of())),
                "marker", "before");

        assertThat(transformWithExistsOn(input, "./content[*]/image_url")).containsEntry("marker", "after");
    }

    /** 多级嵌套路径逐层下钻。 */
    @Test
    void nestedPathDescendsThroughEveryLevel() {
        Map<String, Object> input = Map.of("a", Map.of("b", Map.of("c", 1)), "marker", "before");

        assertThat(transformWithExistsOn(input, "./a/b/c")).containsEntry("marker", "after");
        assertThat(transformWithExistsOn(input, "./a/b/missing")).containsEntry("marker", "before");
    }

    /**
     * 不以 {@code ./} 开头的路径视为无效，条件判定为假。
     *
     * <p>无效路径不匹配而非报错：路径由用户在编辑器里输入，写错时该条规则不生效，
     * 而不是让整次转换失败。
     */
    @Test
    void pathNotStartingWithDotSlashNeverMatches() {
        Map<String, Object> input = Map.of("probe", 1, "marker", "before");

        assertThat(transformWithExistsOn(input, "probe")).containsEntry("marker", "before");
        assertThat(transformWithExistsOn(input, "")).containsEntry("marker", "before");
        assertThat(transformWithExistsOn(input, "./")).containsEntry("marker", "before");
    }

    // ==================== equals 的类型严格性 ====================

    /** {@code equals} 比较 JSON 值，对象与数组按结构相等而非引用相等。 */
    @Test
    void equalsConditionComparesObjectsAndArraysStructurally() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("opts", Map.of("b", 2, "a", 1));
        input.put("marker", "before");

        String rules = """
                {"version":2,"groups":[{"id":"g","name":"g","order":0,"enabled":true,
                 "protocols":["OPENAI"],"templateKeys":["custom"],"previewBody":{},
                 "rules":[{"id":"r","order":0,"field":"marker","array":false,"conditional":true,
                  "conditionMode":"all","conditions":[{"path":"./opts","operator":"equals",
                   "value":{"a":1,"b":2}}],
                  "operations":[{"type":"set_value","value":"after"}]}]}]}
                """;

        assertThat(engine.transform(input, rules, WireProtocol.OPENAI).output())
                .containsEntry("marker", "after");
    }

    // ==================== 缺失字段上的操作 ====================

    /** {@code set_value} 作用于不存在的字段时创建它。 */
    @Test
    void setValueCreatesAbsentField() {
        Map<String, Object> output = engine.transform(
                Map.of("model", "m"), singleOperationRuleSet("added", "set_value", "\"value\":42"),
                WireProtocol.OPENAI).output();

        assertThat(output).containsEntry("added", 42);
    }

    /** {@code delete} 作用于不存在的字段是空操作，且不产警告。 */
    @Test
    void deleteOnAbsentFieldIsNoOp() {
        RequestBodyRuleEngine.TransformResult result = engine.transform(
                Map.of("model", "m"), singleOperationRuleSet("absent", "delete", null),
                WireProtocol.OPENAI);

        assertThat(result.output()).containsExactlyEntriesOf(Map.of("model", "m"));
        assertThat(result.warnings()).isEmpty();
    }

    /** {@code edit_object} 作用于不存在的字段既不创建也不报警。 */
    @Test
    void editObjectOnAbsentFieldIsSilentNoOp() {
        RequestBodyRuleEngine.TransformResult result = engine.transform(
                Map.of("model", "m"),
                singleOperationRuleSet("absent", "edit_object", "\"rules\":[]"),
                WireProtocol.OPENAI);

        assertThat(result.output()).doesNotContainKey("absent");
        assertThat(result.warnings()).isEmpty();
    }

    // ==================== 数组遍历的容错 ====================

    /** 目标字段不是数组时，数组模式规则静默跳过。 */
    @Test
    void arrayRuleOnNonArrayFieldIsSilentlySkipped() {
        RequestBodyRuleEngine.TransformResult result = engine.transform(
                Map.of("messages", "not-an-array"), """
                        {"version":2,"groups":[{"id":"g","name":"g","order":0,"enabled":true,
                         "protocols":["OPENAI"],"templateKeys":["custom"],"previewBody":{},
                         "rules":[{"id":"r","order":0,"field":"messages","array":true,
                          "conditional":false,"conditionMode":"all","conditions":[],
                          "operations":[{"type":"edit_object","rules":[]}]}]}]}
                        """, WireProtocol.OPENAI);

        assertThat(result.output()).containsEntry("messages", "not-an-array");
        assertThat(result.warnings()).isEmpty();
    }

    /** 数组里的非对象元素被跳过，对象元素照常处理。 */
    @Test
    void arrayRuleSkipsNonObjectElements() {
        Map<String, Object> input = Map.of("messages",
                List.of("plain", 42, new LinkedHashMap<>(Map.of("role", "tool"))));

        Map<String, Object> output = engine.transform(input, """
                {"version":2,"groups":[{"id":"g","name":"g","order":0,"enabled":true,
                 "protocols":["OPENAI"],"templateKeys":["custom"],"previewBody":{},
                 "rules":[{"id":"r","order":0,"field":"messages","array":true,
                  "conditional":false,"conditionMode":"all","conditions":[],
                  "operations":[{"type":"edit_object","rules":[
                    {"id":"n","order":0,"field":"role","array":false,"conditional":false,
                     "conditionMode":"all","conditions":[],
                     "operations":[{"type":"set_value","value":"user"}]}]}]}]}]}
                """, WireProtocol.OPENAI).output();

        List<?> messages = (List<?>) output.get("messages");
        assertThat(messages.get(0)).isEqualTo("plain");
        assertThat(messages.get(1)).isEqualTo(42);
        // 转成 Map<String, Object> 再断言：Map<?, ?> 的键类型是通配符捕获，
        // containsEntry 无法接受字符串字面量。
        @SuppressWarnings("unchecked")
        Map<String, Object> third = (Map<String, Object>) messages.get(2);
        assertThat(third).containsEntry("role", "user");
    }

    // ==================== set_value 的类型保真 ====================

    /** {@code set_value} 保持 JSON 类型：数字不变字符串、布尔不变数字、对象与数组按原样写入。 */
    @Test
    void setValuePreservesJsonTypes() {
        assertThat(engine.transform(Map.of("x", "old"),
                singleOperationRuleSet("x", "set_value", "\"value\":0.7"), WireProtocol.OPENAI).output())
                .containsEntry("x", 0.7);
        assertThat(engine.transform(Map.of("x", "old"),
                singleOperationRuleSet("x", "set_value", "\"value\":true"), WireProtocol.OPENAI).output())
                .containsEntry("x", true);
        assertThat(engine.transform(Map.of("x", "old"),
                singleOperationRuleSet("x", "set_value", "\"value\":\"0.7\""), WireProtocol.OPENAI).output())
                .containsEntry("x", "0.7");
        assertThat(engine.transform(Map.of("x", "old"),
                singleOperationRuleSet("x", "set_value", "\"value\":{\"a\":[1,2]}"), WireProtocol.OPENAI).output())
                .containsEntry("x", Map.of("a", List.of(1, 2)));
    }

    // ==================== 辅助 ====================

    /** 构造一条「当 path 存在时把 marker 设为 after」的规则并执行。 */
    private Map<String, Object> transformWithExistsOn(Map<String, Object> input, String path) {
        String rules = """
                {"version":2,"groups":[{"id":"g","name":"g","order":0,"enabled":true,
                 "protocols":["OPENAI"],"templateKeys":["custom"],"previewBody":{},
                 "rules":[{"id":"r","order":0,"field":"marker","array":false,"conditional":true,
                  "conditionMode":"all","conditions":[{"path":"%s","operator":"exists","value":null}],
                  "operations":[{"type":"set_value","value":"after"}]}]}]}
                """.formatted(path);
        return engine.transform(input, rules, WireProtocol.OPENAI).output();
    }

    /** 构造只含一条规则、一个操作的 V2 规则集。 */
    private String singleOperationRuleSet(String field, String operationType, String extraOperationJson) {
        String operation = extraOperationJson == null
                ? "{\"type\":\"%s\"}".formatted(operationType)
                : "{\"type\":\"%s\",%s}".formatted(operationType, extraOperationJson);
        return """
                {"version":2,"groups":[{"id":"g","name":"g","order":0,"enabled":true,
                 "protocols":["OPENAI"],"templateKeys":["custom"],"previewBody":{},
                 "rules":[{"id":"r","order":0,"field":"%s","array":false,"conditional":false,
                  "conditionMode":"all","conditions":[],"operations":[%s]}]}]}
                """.formatted(field, operation);
    }
}

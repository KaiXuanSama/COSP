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
 * {@code equals} 条件对各 JSON 类型的匹配语义。
 *
 * <h2>为何专门一个类</h2>
 * 编辑器为条件比较值提供了显式类型档位（字符串 / 数值 / 列表 / 布尔 / null），
 * 于是「用户能表达哪些值」从原先的「JSON 能猜出来的那些」扩展到了全部五类。
 * 引擎侧的比较靠 Jackson 的 {@code JsonNode.equals} —— 它本就是类型严格的结构相等，
 * 因此这次不需要功能改动；但「不需要改」这个结论必须由测试支撑，
 * 否则下次有人「优化」成 {@code asText()} 比较时没有任何东西会拦住他。
 *
 * <p>与 {@link RequestBodyRuleEnginePathSemanticsTests} 的分工：那边验路径取值与存在性，
 * 这边验取到值之后的相等判定。
 */
class RequestBodyRuleEngineEqualsSemanticsTests {

    private RequestBodyRuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RequestBodyRuleEngine(new ObjectMapper());
    }

    // ==================== null ====================

    /**
     * 字段值为 null 时匹配 {@code "value": null}。
     *
     * <p>这是 TokenRhythm 那两条「值为 null 就删掉该字段」规则依赖的行为 ——
     * 上游不接受 {@code tool_call_id: null}，而下游会送来这种形态。
     */
    @Test
    void nullValueMatchesExplicitNull() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tool_call_id", null);
        input.put("marker", "before");

        assertThat(transformWithEquals(input, "./tool_call_id", "null"))
                .containsEntry("marker", "after");
    }

    /**
     * 字段**不存在**时不匹配 null。
     *
     * <p>「字段值是 null」与「字段不存在」是两件事：前者上游会拒收、需要删掉，
     * 后者本就干净、不该触发任何操作。若把两者混同，一条「值为 null 就删除」的规则
     * 会在字段缺失时也去执行删除 —— 虽然结果恰好无害，但同样的混同放在
     * 「值为 null 就设默认值」上就会给本不该有该字段的请求凭空加字段。
     */
    @Test
    void absentFieldDoesNotMatchNull() {
        Map<String, Object> input = Map.of("marker", "before");

        assertThat(transformWithEquals(input, "./tool_call_id", "null"))
                .containsEntry("marker", "before");
    }

    /** null 不等于空字符串 —— 正是编辑器加类型档位要消除的那处混淆。 */
    @Test
    void nullDoesNotMatchEmptyString() {
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("probe", null);
        withNull.put("marker", "before");

        assertThat(transformWithEquals(withNull, "./probe", "\"\""))
                .containsEntry("marker", "before");

        Map<String, Object> withEmptyString = new LinkedHashMap<>();
        withEmptyString.put("probe", "");
        withEmptyString.put("marker", "before");

        assertThat(transformWithEquals(withEmptyString, "./probe", "null"))
                .containsEntry("marker", "before");
    }

    // ==================== 数值与字符串 ====================

    /** 数字 10 不等于字符串 "10"，两个方向都不等。 */
    @Test
    void numberDoesNotMatchNumericString() {
        Map<String, Object> withNumber = new LinkedHashMap<>();
        withNumber.put("probe", 10);
        withNumber.put("marker", "before");

        assertThat(transformWithEquals(withNumber, "./probe", "\"10\""))
                .containsEntry("marker", "before");
        assertThat(transformWithEquals(withNumber, "./probe", "10"))
                .containsEntry("marker", "after");

        Map<String, Object> withString = new LinkedHashMap<>();
        withString.put("probe", "10");
        withString.put("marker", "before");

        assertThat(transformWithEquals(withString, "./probe", "10"))
                .containsEntry("marker", "before");
        assertThat(transformWithEquals(withString, "./probe", "\"10\""))
                .containsEntry("marker", "after");
    }

    /**
     * 整数与小数在数值上相等时匹配。
     *
     * <p>Jackson 的 {@code IntNode(2)} 与 {@code DoubleNode(2.0)} 是不同的节点类型，
     * 其 {@code equals} 按数值比较而非按节点类型 —— 若哪天换成类型敏感的比较，
     * 用户填的 {@code 2} 就匹配不上上游送来的 {@code 2.0}，而他没有任何办法知道为什么。
     */
    @Test
    void integerMatchesEquivalentDecimal() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("probe", 2.0);
        input.put("marker", "before");

        assertThat(transformWithEquals(input, "./probe", "2"))
                .containsEntry("marker", "after");
    }

    // ==================== 布尔 ====================

    /** 布尔按值比较，且不等于同名字符串或 0/1。 */
    @Test
    void booleanMatchesOnlyBoolean() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("probe", false);
        input.put("marker", "before");

        assertThat(transformWithEquals(input, "./probe", "false"))
                .containsEntry("marker", "after");
        assertThat(transformWithEquals(input, "./probe", "true"))
                .containsEntry("marker", "before");
        assertThat(transformWithEquals(input, "./probe", "\"false\""))
                .containsEntry("marker", "before");
        assertThat(transformWithEquals(input, "./probe", "0"))
                .containsEntry("marker", "before");
    }

    // ==================== 列表 ====================

    /** 列表按元素顺序与类型逐个比较。 */
    @Test
    void listMatchesElementwiseAndOrderSensitive() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("probe", List.of(1, "a", true));
        input.put("marker", "before");

        assertThat(transformWithEquals(input, "./probe", "[1,\"a\",true]"))
                .containsEntry("marker", "after");
        assertThat(transformWithEquals(input, "./probe", "[\"a\",1,true]"))
                .containsEntry("marker", "before");
        assertThat(transformWithEquals(input, "./probe", "[1,\"a\"]"))
                .containsEntry("marker", "before");
    }

    /** 空列表匹配空列表，但不匹配 null 或缺失。 */
    @Test
    void emptyListMatchesOnlyEmptyList() {
        Map<String, Object> withEmptyList = new LinkedHashMap<>();
        withEmptyList.put("probe", List.of());
        withEmptyList.put("marker", "before");

        assertThat(transformWithEquals(withEmptyList, "./probe", "[]"))
                .containsEntry("marker", "after");
        assertThat(transformWithEquals(withEmptyList, "./probe", "null"))
                .containsEntry("marker", "before");
    }

    /** 列表内含 null 元素时按结构比较，null 元素不被忽略。 */
    @Test
    void listWithNullElementComparesStructurally() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("probe", java.util.Arrays.asList(1, null));
        input.put("marker", "before");

        assertThat(transformWithEquals(input, "./probe", "[1,null]"))
                .containsEntry("marker", "after");
        assertThat(transformWithEquals(input, "./probe", "[1]"))
                .containsEntry("marker", "before");
    }

    // ==================== 缺失的 value 键 ====================

    /**
     * 条件缺 {@code value} 键时永不匹配。
     *
     * <p>缺键得到的是 Jackson 的 {@code MissingNode}，它不等于任何实际值（包括 NullNode）。
     * 保存路径要求条件必带 {@code value}，因此这种规则只可能来自手写 JSON；
     * 让它不匹配而非等同于 null 是保守选择 —— 一条写错的规则不生效，
     * 比它悄悄按某种猜测生效要容易排查。
     */
    @Test
    void conditionWithoutValueKeyNeverMatches() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("probe", null);
        input.put("marker", "before");

        String rules = """
                {"version":2,"groups":[{"id":"g","name":"g","order":0,"enabled":true,
                 "protocols":["OPENAI"],"templateKeys":["custom"],"previewBody":{},
                 "rules":[{"id":"r","order":0,"field":"marker","array":false,"conditional":true,
                  "conditionMode":"all","conditions":[{"path":"./probe","operator":"equals"}],
                  "operations":[{"type":"set_value","value":"after"}]}]}]}
                """;

        assertThat(engine.transform(input, rules, WireProtocol.OPENAI).output())
                .containsEntry("marker", "before");
    }

    // ==================== 辅助 ====================

    /**
     * 构造一条「当 path 的值等于 valueJson 时把 marker 设为 after」的规则并执行。
     *
     * @param valueJson 比较值的 JSON 字面量，直接嵌入规则
     */
    private Map<String, Object> transformWithEquals(Map<String, Object> input, String path, String valueJson) {
        String rules = """
                {"version":2,"groups":[{"id":"g","name":"g","order":0,"enabled":true,
                 "protocols":["OPENAI"],"templateKeys":["custom"],"previewBody":{},
                 "rules":[{"id":"r","order":0,"field":"marker","array":false,"conditional":true,
                  "conditionMode":"all","conditions":[{"path":"%s","operator":"equals","value":%s}],
                  "operations":[{"type":"set_value","value":"after"}]}]}]}
                """.formatted(path, valueJson);
        return engine.transform(input, rules, WireProtocol.OPENAI).output();
    }
}

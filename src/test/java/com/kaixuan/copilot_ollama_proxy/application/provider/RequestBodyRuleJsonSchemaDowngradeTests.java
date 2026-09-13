package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把 {@code text.format} 从 {@code json_schema} 降级为 {@code json_object}。
 *
 * <h2>场景</h2>
 * Codex 经 COSP 打 MiMo 时，标题生成等请求会带上 Structured Outputs 配置：
 * <pre>
 * {"text":{"format":{"type":"json_schema","name":"...","schema":{...},"strict":true}}}
 * </pre>
 * 而 MiMo 的网关只接受 {@code text} 与 {@code json_object} 两种取值，直接返回：
 * <pre>
 * {"error":{"code":"responses_feature_not_supported",
 *           "message":"text.format type 'json_schema' is not supported,
 *                      only 'text' and 'json_object' are allowed."}}
 * </pre>
 *
 * <p>这是<strong>上游能力差异</strong>而非 COSP 缺陷：COSP 在 Responses 线路上原样透传
 * 未建模字段（{@code text} 正是其一），透传是刻意的设计。差异按既定原则用请求体规则表达。
 *
 * <h2>官方三种形态（决定了要删哪些字段）</h2>
 * <pre>
 * ResponseFormatText                 object { type }                     → type: "text"（默认）
 * ResponseFormatTextJSONSchemaConfig object { name, schema, type, 2 more } → type: "json_schema"
 *                                     ↑ name 与 schema 必填，description / strict 可选
 * ResponseFormatJSONObject           object { type }                     → type: "json_object"
 * </pre>
 * 后者的对象里<strong>只有 type</strong>，所以降级不能只改 {@code type} ——
 * {@code name} / {@code schema} / {@code strict} / {@code description} 都得多余地留给上游。
 * 本测试因此整体替换 {@code format} 对象，而不是改一个字段再逐个删除。
 *
 * <h2>条件路径为何是 {@code ./text/format/type}</h2>
 * 标量模式下条件以<strong>字段所在的对象</strong>为作用域（同级寻址），
 * 而数组模式以<strong>元素</strong>为作用域 —— 两者不同，写混了会静默不命中。
 * 本文件顶部这条规则作用在根对象的 {@code text} 字段上，因此路径要从根写起。
 */
@DisplayName("text.format 降级为 json_object")
class RequestBodyRuleJsonSchemaDowngradeTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RequestBodyRuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RequestBodyRuleEngine(objectMapper);
    }

    /**
     * 用户实际要用的规则。
     *
     * <p>结构：{@code text} 上条件命中 {@code json_schema} → {@code edit_object} 进入
     * {@code text} 作用域 → 其中 {@code format} 用 {@code set_value} 整体替换成
     * {@code {"type":"json_object"}}。两层嵌套即可，不必逐字段删除。
     */
    private static final String DOWNGRADE_RULES = """
            {"version":2,"groups":[{"id":"g","name":"降级 json_schema 为 json_object","order":0,
             "enabled":true,"protocols":["RESPONSES"],"templateKeys":["custom"],"previewBody":{},
             "rules":[{"id":"r-text","order":0,"field":"text","array":false,"conditional":true,
              "conditionMode":"all",
              "conditions":[{"path":"./text/format/type","operator":"equals","value":"json_schema"}],
              "operations":[{"type":"edit_object","rules":[
               {"id":"r-format","order":0,"field":"format","array":false,"conditional":false,
                "conditionMode":"all","conditions":[],
                "operations":[{"type":"set_value","value":{"type":"json_object"}}]}]}]}]}]}
            """;

    /** Codex 标题生成请求的最小复现。 */
    private static Map<String, Object> structuredOutputBody() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("model", "mimo-v2.5");
        input.put("input", "Generate a title");
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("name", "title_schema");
        format.put("strict", true);
        format.put("schema", Map.of("type", "object",
                "properties", Map.of("title", Map.of("type", "string")),
                "required", List.of("title")));
        input.put("text", Map.of("format", format));
        return input;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> formatOf(Map<String, Object> output) {
        return (Map<String, Object>) ((Map<String, Object>) output.get("text")).get("format");
    }

    @Test
    @DisplayName("整体替换 format，多余字段一并消失")
    void 降级后只剩type字段() {
        RequestBodyRuleEngine.TransformResult result =
                engine.transform(structuredOutputBody(), DOWNGRADE_RULES, WireProtocol.RESPONSES);

        assertThat(result.warnings()).isEmpty();
        // 这四处多余字段一并消失 —— 只改 type 会让上游收到 json_object 配 schema 的矛盾形态。
        assertThat(formatOf(result.output()))
                .containsEntry("type", "json_object")
                .doesNotContainKeys("name", "schema", "strict", "description");
    }

    /** 其余请求字段不受影响。 */
    @Test
    @DisplayName("降级不波及其它字段")
    void 降级不波及其它字段() {
        RequestBodyRuleEngine.TransformResult result =
                engine.transform(structuredOutputBody(), DOWNGRADE_RULES, WireProtocol.RESPONSES);

        assertThat(result.output()).containsEntry("model", "mimo-v2.5")
                .containsEntry("input", "Generate a title")
                .containsKey("text");
    }

    /**
     * 不是 {@code json_schema} 时不动 —— 条件的作用范围就体现在这里。
     *
     * <p>纯文本请求（{@code text.format.type == "text"}）与已降级的请求都必须原样放行，
     * 否则会把一次普通对话也强行改成 JSON 输出。
     */
    @Test
    @DisplayName("非 json_schema 时一概不动")
    void 非jsonSchema时不动() {
        Map<String, Object> plainText = new LinkedHashMap<>();
        plainText.put("model", "mimo-v2.5");
        plainText.put("text", Map.of("format", Map.of("type", "text")));

        Map<String, Object> alreadyJsonObject = new LinkedHashMap<>();
        alreadyJsonObject.put("model", "mimo-v2.5");
        alreadyJsonObject.put("text", Map.of("format", Map.of("type", "json_object")));

        assertThat(formatOf(engine.transform(plainText, DOWNGRADE_RULES, WireProtocol.RESPONSES).output()))
                .containsEntry("type", "text");
        assertThat(formatOf(engine.transform(alreadyJsonObject, DOWNGRADE_RULES, WireProtocol.RESPONSES).output()))
                .containsEntry("type", "json_object");
    }

    /** 没有 {@code text} 字段的请求（多数对话请求）静默放行。 */
    @Test
    @DisplayName("缺少 text 字段时静默放行")
    void 缺少text字段时静默放行() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("model", "mimo-v2.5");
        input.put("input", "hi");

        RequestBodyRuleEngine.TransformResult result =
                engine.transform(input, DOWNGRADE_RULES, WireProtocol.RESPONSES);

        assertThat(result.warnings()).isEmpty();
        assertThat(result.output()).doesNotContainKey("text");
    }

    /** {@code text} 存在但没有 {@code format} 时也不动。 */
    @Test
    @DisplayName("text 存在但无 format 时不动")
    void text无format时不动() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("model", "mimo-v2.5");
        input.put("text", Map.of("verbosity", "low"));

        RequestBodyRuleEngine.TransformResult result =
                engine.transform(input, DOWNGRADE_RULES, WireProtocol.RESPONSES);

        assertThat(result.warnings()).isEmpty();
        Map<?, ?> text = (Map<?, ?>) result.output().get("text");
        assertThat(text).hasSize(1);
        assertThat(text.get("verbosity")).isEqualTo("low");
    }

    /**
     * 条件路径写错时的症状：静默无操作。
     *
     * <p>{@code ./format/type}（数组模式那种从元素写起的形态）在标量模式下会去找
     * 根对象的 {@code format} 字段 —— 根上没有它，恒不命中且不告警。
     * 这条把「路径写错 = 全静默」这个最难排查的症状固定下来。
     */
    @Test
    @DisplayName("条件路径写成元素作用域时静默失效")
    void 条件路径写错时静默失效() {
        String wrongScope = DOWNGRADE_RULES.replace("./text/format/type", "./format/type");

        RequestBodyRuleEngine.TransformResult result =
                engine.transform(structuredOutputBody(), wrongScope, WireProtocol.RESPONSES);

        assertThat(result.warnings()).isEmpty();
        assertThat(formatOf(result.output())).containsEntry("type", "json_schema");
    }

    /**
     * 只声明给其它协议的组不会作用到 Responses 请求上。
     *
     * <p>同一条规则组若声明 CHAT，对 {@code /v1/responses} 的请求应当完全无影响。
     */
    @Test
    @DisplayName("协议不匹配时不执行")
    void 协议不匹配时不执行() {
        String chatOnly = DOWNGRADE_RULES.replace("[\"RESPONSES\"]", "[\"CHAT\"]");

        RequestBodyRuleEngine.TransformResult result =
                engine.transform(structuredOutputBody(), chatOnly, WireProtocol.RESPONSES);

        assertThat(formatOf(result.output())).containsEntry("type", "json_schema");
    }

    /**
     * 换成 {@code text} 的降级形态同样可行 —— MiMo 也只接受它。
     *
     * <p>取舍：{@code json_object} 保住了「返回 JSON」的意图（官方注明该模式仍需
     * 提示词明确要求 JSON，Codex 的标题生成提示词本就在要求）；{@code text} 则彻底退化成
     * 纯文本，下游若按 JSON 解析会失败。默认推荐前者，本用例只是钉住另一条路也走得通。
     */
    @Test
    @DisplayName("降级为 text 也可行")
    void 降级为text也可行() {
        String toText = DOWNGRADE_RULES.replace("{\"type\":\"json_object\"}", "{\"type\":\"text\"}");

        RequestBodyRuleEngine.TransformResult result =
                engine.transform(structuredOutputBody(), toText, WireProtocol.RESPONSES);

        assertThat(result.warnings()).isEmpty();
        assertThat(formatOf(result.output())).containsEntry("type", "text");
    }
}

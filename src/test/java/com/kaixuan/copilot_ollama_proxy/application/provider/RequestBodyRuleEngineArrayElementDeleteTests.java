package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数组模式下的「删除字段」= 移除被选中的元素。
 *
 * <h2>这个语义从哪来</h2>
 * 场景：Codex 经 COSP 打 MiMo（{@code [mimo-tokenplan] mimo-v2.5}）时，上游返回
 * {@code responses_feature_not_supported} / "tool type 'web_search' is not supported by
 * this gateway phase" —— Codex 的请求体里带着托管 {@code web_search} 工具，而 MiMo 的
 * 网关拒收它。需要表达的规则是「把 {@code tools} 里 type 为 web_search 的那一项去掉」。
 *
 * <p>此前的引擎做不到：数组模式只支持 {@code edit_object}，{@code delete} 会退化成一个
 * 告警（「数组模式下"删除字段"应通过嵌套规则定位字段」）。而条件的存在意义本就是
 * <strong>选中元素</strong> —— 能选中却删不掉，语义是断的。
 *
 * <p>现在的分工：
 * <ul>
 *   <li>{@code array: true} + {@code delete} → 移除<strong>匹配到的元素</strong>；</li>
 *   <li>{@code array: true} + {@code edit_object} → 改元素<strong>内部</strong>的字段
 *       （嵌套规则的 {@code delete} 在那里仍是删字段）；</li>
 *   <li>{@code array: false} + {@code delete} → 删掉<strong>整个字段</strong>（原语义不变）。</li>
 * </ul>
 * 三者共用同一个 {@code delete} 操作名，由 {@code array} 开关决定作用层级 ——
 * 界面上不新增操作类型。
 */
@DisplayName("数组模式的删除语义")
class RequestBodyRuleEngineArrayElementDeleteTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RequestBodyRuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RequestBodyRuleEngine(objectMapper);
    }

    private RequestBodyRuleEngine.TransformResult transform(Map<String, Object> input, String bodyRulesJson) {
        return engine.transform(input, bodyRulesJson, WireProtocol.CHAT);
    }

    /**
     * 组装一条字段级规则。
     *
     * @param array       目标字段是否按数组模式处理
     * @param conditional 是否启用条件
     * @param conditions  条件 JSON（已是 {@code [...]} 形态）
     * @param operations  操作 JSON（已是 {@code [...]} 形态）
     */
    private static String rule(String field, boolean array, boolean conditional,
                               String conditions, String operations) {
        return """
                {"version":1,"rules":[{"id":"r","order":0,"field":"%s","array":%s,"conditional":%s,
                 "conditionMode":"all","conditions":%s,"operations":%s}]}
                """.formatted(field, array, conditional, conditions, operations);
    }

    private static String deleteMatching(String path, String value) {
        return rule("tools", true, true,
                "[{\"path\":\"" + path + "\",\"operator\":\"equals\",\"value\":\"" + value + "\"}]",
                "[{\"type\":\"delete\"}]");
    }

    private static Map<String, Object> toolsBody(Object... types) {
        Map<String, Object> input = new LinkedHashMap<>();
        List<Map<String, Object>> tools = new java.util.ArrayList<>();
        for (Object type : types) {
            tools.add(Map.of("type", type, "detail", "d-" + type));
        }
        input.put("tools", tools);
        return input;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toolsOf(Map<String, Object> output) {
        return (List<Map<String, Object>>) output.get("tools");
    }

    // ==================== 核心行为 ====================

    @Nested
    @DisplayName("移除匹配元素")
    class 移除匹配元素 {

        /** 故障现场的修复验证：只摘掉 web_search，其余工具保留。 */
        @Test
        void 按类型删除只移除匹配的那一项() {
            RequestBodyRuleEngine.TransformResult result = transform(
                    toolsBody("web_search", "function", "custom"),
                    deleteMatching("./type", "web_search"));

            assertThat(result.warnings()).isEmpty();
            assertThat(toolsOf(result.output()))
                    .extracting(tool -> tool.get("type"))
                    .containsExactly("function", "custom");
        }

        /**
         * <strong>相邻的多个匹配项必须全部删除。</strong>
         *
         * <p>这是实现上最容易错的一条：边遍历边移除会让后续下标整体前移，
         * {@code [web_search, web_search, function]} 里第二个 web_search 会因
         * 下标坍缩被跳过，留下一个本该消失的工具。引擎改为「先收集下标、最后倒序移除」，
         * 本用例就是钉住那个改法。
         */
        @Test
        void 相邻的多个匹配元素全部被删除() {
            RequestBodyRuleEngine.TransformResult result = transform(
                    toolsBody("web_search", "web_search", "function"),
                    deleteMatching("./type", "web_search"));

            assertThat(toolsOf(result.output()))
                    .extracting(tool -> tool.get("type"))
                    .containsExactly("function");
        }

        /** 匹配项分散在首尾与中间时同样全删。 */
        @Test
        void 分散的多个匹配元素全部被删除() {
            RequestBodyRuleEngine.TransformResult result = transform(
                    toolsBody("web_search", "function", "web_search", "custom", "web_search"),
                    deleteMatching("./type", "web_search"));

            assertThat(toolsOf(result.output()))
                    .extracting(tool -> tool.get("type"))
                    .containsExactly("function", "custom");
        }

        /** 全部匹配时数组清空 —— 字段本身保留为空数组，不做「顺手删掉字段」的隐式动作。 */
        @Test
        void 全部匹配时数组被清空但字段保留() {
            RequestBodyRuleEngine.TransformResult result = transform(
                    toolsBody("web_search", "web_search"),
                    deleteMatching("./type", "web_search"));

            assertThat(result.output()).containsKey("tools");
            assertThat(toolsOf(result.output())).isEmpty();
        }

        /**
         * 没匹配到任何元素是<strong>正常空操作</strong>，且不产生告警。
         *
         * <p>规则的用途本就是「上游带了某个东西才处理」：Codex 是否发送 web_search
         * 取决于模型与配置，不带时规则应当悄悄放行。若这里发告警，日志会被刷满噪音。
         */
        @Test
        void 未匹配时原样放行且不告警() {
            Map<String, Object> input = toolsBody("function", "custom");

            RequestBodyRuleEngine.TransformResult result =
                    transform(input, deleteMatching("./type", "web_search"));

            assertThat(result.warnings()).isEmpty();
            assertThat(toolsOf(result.output())).hasSize(2);
        }

        /** 字段不存在或不是数组时静默跳过（与 edit_object 同一取向）。 */
        @Test
        void 字段缺失或非数组时静默跳过() {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("model", "m");
            Map<String, Object> notArray = new LinkedHashMap<>();
            notArray.put("tools", "not-an-array");

            String rules = deleteMatching("./type", "web_search");

            assertThat(transform(missing, rules).warnings()).isEmpty();
            assertThat(transform(missing, rules).output()).doesNotContainKey("tools");
            assertThat(transform(notArray, rules).warnings()).isEmpty();
            assertThat(transform(notArray, rules).output()).containsEntry("tools", "not-an-array");
        }

        /** 数组里的非对象元素无法被条件匹配，原样保留。 */
        @Test
        void 非对象元素被原样保留() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("tools", new java.util.ArrayList<>(List.of("plain-string", Map.of("type", "web_search"))));

            RequestBodyRuleEngine.TransformResult result =
                    transform(input, deleteMatching("./type", "web_search"));

            assertThat(result.warnings()).isEmpty();
            List<?> bareTools = (List<?>) result.output().get("tools");
            assertThat(bareTools).hasSize(1);
            assertThat(bareTools.get(0)).isEqualTo("plain-string");
        }

        /**
         * 条件未启用时不筛选元素 —— 删除全部。
         *
         * <p>语义上一致（无条件 = 全部满足），但这是个破坏性操作：想表达「删掉整个数组」
         * 应把 {@code array} 关掉，那样连字段一起移除。本用例把两者的差别钉住。
         */
        @Test
        void 无条件下删除全部元素() {
            RequestBodyRuleEngine.TransformResult result = transform(
                    toolsBody("web_search", "function"),
                    rule("tools", true, false, "[]", "[{\"type\":\"delete\"}]"));

            assertThat(result.warnings()).isEmpty();
            assertThat(toolsOf(result.output())).isEmpty();
        }
    }

    // ==================== 条件作用域 ====================

    @Nested
    @DisplayName("条件作用域")
    class 条件作用域 {

        /**
         * 数组模式下条件以<strong>元素</strong>为作用域，因此路径写 {@code ./type}。
         *
         * <p>写成 {@code ./tools[*]/type} 会去元素内部找 {@code tools} 字段，恒不命中，
         * 于是规则静默无操作 —— 这正是最初「规则没生效」的直接原因。
         * 本用例把两种写法的差别固定下来，供前端提示文案与排查参考。
         */
        @Test
        void 路径以元素为作用域而非根对象() {
            String elementScope = deleteMatching("./type", "web_search");
            String rootScope = deleteMatching("./tools[*]/type", "web_search");

            assertThat(toolsOf(transform(toolsBody("web_search", "function"), elementScope).output()))
                    .as("元素作用域：命中并删除")
                    .hasSize(1);
            assertThat(toolsOf(transform(toolsBody("web_search", "function"), rootScope).output()))
                    .as("根作用域写法：恒不命中，静默无操作")
                    .hasSize(2);
        }

        /** 多个条件同时满足才删除（全部满足语义）。 */
        @Test
        void 多条件需全部满足() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("tools", List.of(
                    Map.of("type", "web_search", "mode", "hosted"),
                    Map.of("type", "web_search", "mode", "local"),
                    Map.of("type", "function", "mode", "hosted")));

            String rules = rule("tools", true, true,
                    "[{\"path\":\"./type\",\"operator\":\"equals\",\"value\":\"web_search\"},"
                            + "{\"path\":\"./mode\",\"operator\":\"equals\",\"value\":\"hosted\"}]",
                    "[{\"type\":\"delete\"}]");

            RequestBodyRuleEngine.TransformResult result = transform(input, rules);

            assertThat(result.warnings()).isEmpty();
            assertThat(toolsOf(result.output())).hasSize(2);
            assertThat(toolsOf(result.output()))
                    .extracting(tool -> tool.get("mode"))
                    .containsExactly("local", "hosted");
        }

        /** {@code exists} 条件同样可用于筛选。 */
        @Test
        void exists条件可用于筛选() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("tools", List.of(
                    Map.of("type", "web_search", "external_web_access", false),
                    Map.of("type", "function", "name", "shell")));

            String rules = rule("tools", true, true,
                    "[{\"path\":\"./external_web_access\",\"operator\":\"exists\",\"value\":null}]",
                    "[{\"type\":\"delete\"}]");

            assertThat(toolsOf(transform(input, rules).output()))
                    .extracting(tool -> tool.get("type"))
                    .containsExactly("function");
        }
    }

    // ==================== 回归 ====================

    @Nested
    @DisplayName("既有语义不变")
    class 既有语义不变 {

        /** 数组模式 + {@code edit_object} 仍逐元素修改内部字段。 */
        @Test
        void edit_object仍逐元素修改字段() {
            String rules = rule("tools", true, true,
                    "[{\"path\":\"./type\",\"operator\":\"equals\",\"value\":\"web_search\"}]",
                    "[{\"type\":\"edit_object\",\"rules\":["
                            + "{\"id\":\"n\",\"order\":0,\"field\":\"type\",\"array\":false,"
                            + "\"conditional\":false,\"conditionMode\":\"all\",\"conditions\":[],"
                            + "\"operations\":[{\"type\":\"set_value\",\"value\":\"function\"}]}]}]");

            RequestBodyRuleEngine.TransformResult result =
                    transform(toolsBody("web_search", "function"), rules);

            assertThat(result.warnings()).isEmpty();
            assertThat(toolsOf(result.output()))
                    .extracting(tool -> tool.get("type"))
                    .containsExactly("function", "function");
        }

        /** 嵌套规则里的 {@code delete} 仍是「删元素内部的字段」，与元素移除不冲突。 */
        @Test
        void 嵌套删除仍作用于元素内部字段() {
            String rules = rule("tools", true, true,
                    "[{\"path\":\"./type\",\"operator\":\"equals\",\"value\":\"web_search\"}]",
                    "[{\"type\":\"edit_object\",\"rules\":["
                            + "{\"id\":\"n\",\"order\":0,\"field\":\"detail\",\"array\":false,"
                            + "\"conditional\":false,\"conditionMode\":\"all\",\"conditions\":[],"
                            + "\"operations\":[{\"type\":\"delete\"}]}]}]");

            RequestBodyRuleEngine.TransformResult result =
                    transform(toolsBody("web_search", "function"), rules);

            assertThat(result.warnings()).isEmpty();
            List<Map<String, Object>> tools = toolsOf(result.output());
            assertThat(tools).hasSize(2);
            assertThat(tools.get(0)).as("web_search 元素留下，但内部 detail 被删").doesNotContainKey("detail");
            assertThat(tools.get(1)).as("未匹配的元素一字未动").containsEntry("detail", "d-function");
        }

        /** 嵌套数组（元素内部的数组字段）也能按同样语义删除元素。 */
        @Test
        @SuppressWarnings("unchecked")
        void 嵌套数组的元素同样可删除() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("messages", List.of(Map.of(
                    "role", "user",
                    "content", List.of(Map.of("type", "image_url"), Map.of("type", "text")))));

            String rules = """
                    {"version":1,"rules":[{"id":"outer","order":0,"field":"messages","array":true,
                     "conditional":false,"conditionMode":"all","conditions":[],
                     "operations":[{"type":"edit_object","rules":[
                      {"id":"inner","order":0,"field":"content","array":true,"conditional":true,
                       "conditionMode":"all",
                       "conditions":[{"path":"./type","operator":"equals","value":"image_url"}],
                       "operations":[{"type":"delete"}]}]}]}]}
                    """;

            RequestBodyRuleEngine.TransformResult result = transform(input, rules);

            assertThat(result.warnings()).isEmpty();
            List<Map<String, Object>> messages = (List<Map<String, Object>>) result.output().get("messages");
            List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(0).get("content");
            assertThat(content).extracting(part -> part.get("type")).containsExactly("text");
        }

        /** {@code array: false} + {@code delete} 仍删掉整个字段。 */
        @Test
        void 字段级删除仍移除整个字段() {
            RequestBodyRuleEngine.TransformResult result = transform(
                    toolsBody("web_search", "function"),
                    rule("tools", false, false, "[]", "[{\"type\":\"delete\"}]"));

            assertThat(result.warnings()).isEmpty();
            assertThat(result.output()).doesNotContainKey("tools");
        }

        /** 数组模式 + {@code set_value} 仍不受支持 —— 它是字段级操作，在元素层级无意义。 */
        @Test
        void 数组模式的设置字段值仍告警且不生效() {
            RequestBodyRuleEngine.TransformResult result = transform(
                    toolsBody("web_search"),
                    rule("tools", true, false, "[]", "[{\"type\":\"set_value\",\"value\":\"ignored\"}]"));

            assertThat(result.warnings()).extracting(RequestBodyRuleEngine.TransformWarning::message)
                    .containsExactly("数组模式下\"设置字段值\"应通过嵌套规则定位字段");
            assertThat(toolsOf(result.output())).hasSize(1);
        }

        /**
         * {@code edit_object} 与 {@code delete} 写进同一规则时，元素最终被移除。
         *
         * <p>两者作用层级不同（改内容 / 删元素），先改后删与直接删的结果一致。
         * 前端每条规则只放一个操作，这条覆盖的是手写 JSON 的形态。
         */
        @Test
        void 同一规则内编辑与删除并存时以删除收场() {
            String rules = rule("tools", true, true,
                    "[{\"path\":\"./type\",\"operator\":\"equals\",\"value\":\"web_search\"}]",
                    "[{\"type\":\"edit_object\",\"rules\":[" 
                            + "{\"id\":\"n\",\"order\":0,\"field\":\"detail\",\"array\":false,"
                            + "\"conditional\":false,\"conditionMode\":\"all\",\"conditions\":[],"
                            + "\"operations\":[{\"type\":\"set_value\",\"value\":\"changed\"}]}]},"
                            + "{\"type\":\"delete\"}]");

            RequestBodyRuleEngine.TransformResult result =
                    transform(toolsBody("web_search", "function"), rules);

            assertThat(toolsOf(result.output()))
                    .extracting(tool -> tool.get("type"))
                    .containsExactly("function");
        }
    }

    // ==================== 端到端形态 ====================

    @Nested
    @DisplayName("V2 规则组（生产形态）")
    class V2规则组 {

        /**
         * 用户实际要写的规则，按修正后的路径构造。
         *
         * <p>与 {@link RequestBodyRuleEngineArrayElementDeleteTests} 里其它用例的区别是
         * 走 {@code version: 2} 的规则组形态并声明 {@code RESPONSES} 协议 ——
         * 即生产链路真正执行的形状。
         */
        @Test
        void 从请求体中移除web_search工具() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("model", "mimo-v2.5");
            input.put("input", "hi");
            input.put("tools", List.of(
                    Map.of("type", "web_search", "external_web_access", false),
                    Map.of("type", "function", "name", "shell")));

            String rules = """
                    {"version":2,"groups":[{"id":"g","name":"Mimo去除web_search工具","order":0,"enabled":true,
                     "protocols":["RESPONSES"],"templateKeys":["custom"],"previewBody":{},
                     "rules":[{"id":"r","order":0,"field":"tools","array":true,"conditional":true,
                      "conditionMode":"all",
                      "conditions":[{"path":"./type","operator":"equals","value":"web_search"}],
                      "operations":[{"type":"delete"}]}]}]}
                    """;

            RequestBodyRuleEngine.TransformResult result =
                    engine.transform(input, rules, WireProtocol.RESPONSES);

            assertThat(result.warnings()).isEmpty();
            assertThat((List<?>) result.output().get("tools")).hasSize(1);
            assertThat(result.output()).containsEntry("model", "mimo-v2.5");
        }

        /** 只声明 CHAT 的组不会作用到 RESPONSES 请求上。 */
        @Test
        void 协议不匹配的组不执行删除() {
            Map<String, Object> input = new LinkedHashMap<>(toolsBody("web_search", "function"));

            String rules = """
                    {"version":2,"groups":[{"id":"g","name":"g","order":0,"enabled":true,
                     "protocols":["CHAT"],"templateKeys":["custom"],"previewBody":{},
                     "rules":[{"id":"r","order":0,"field":"tools","array":true,"conditional":true,
                      "conditionMode":"all",
                      "conditions":[{"path":"./type","operator":"equals","value":"web_search"}],
                      "operations":[{"type":"delete"}]}]}]}
                    """;

            RequestBodyRuleEngine.TransformResult result =
                    engine.transform(input, rules, WireProtocol.RESPONSES);

            assertThat((List<?>) result.output().get("tools")).hasSize(2);
        }
    }
}

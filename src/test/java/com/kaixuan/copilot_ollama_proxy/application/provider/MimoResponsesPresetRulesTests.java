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
 * MiMo 预设自带的两个 Responses 规则组，配自带的调试样本，端到端跑一遍。
 *
 * <h2>为什么需要这个类</h2>
 * 前端的 `presets.spec.ts` 只能断言规则与样本的**结构**（字段名、条件路径、
 * 操作类型），无法回答最关键的问题：<strong>这套组合跑出来对不对</strong>。
 * 而编辑器预览走的是后端这份引擎，所以只有在这里跑才算验证。
 *
 * <p>规则与样本在前端是模块级常量（`defaultRequestBody.ts`），本类把它们
 * 逐字节抄成 JSON —— 抄错会立刻在断言上暴露，而共享一份定义需要跨语言导出，
 * 代价远大于收益。
 *
 * <p>「样本能触发规则」这件事本身就是要验的：预设里那两组用 `custom` 模板键，
 * 它自身不产生任何内容，样本完全靠手写。写歪了的症状是预览两栏一模一样，
 * 用户会以为规则没生效。
 */
@DisplayName("MiMo 预设的 Responses 规则组")
class MimoResponsesPresetRulesTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RequestBodyRuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RequestBodyRuleEngine(objectMapper);
    }

    // ==================== web_search 组 ====================

    /** 预设里 `MIMO_RESPONSES_WEB_SEARCH_RULESET` 的等价 V2 规则集。 */
    private static final String WEB_SEARCH_RULES = """
            {"version":2,"groups":[{"id":"g","name":"移除 web_search 工具","order":0,"enabled":true,
             "protocols":["RESPONSES"],"templateKeys":["custom"],"previewBody":{},
             "rules":[{"id":"mimo-drop-web-search","order":0,"field":"tools","array":true,
              "conditional":true,"conditionMode":"all",
              "conditions":[{"path":"./type","operator":"equals","value":"web_search"}],
              "operations":[{"type":"delete"}]}]}]}
            """;

    /** 预设里 `MIMO_RESPONSES_WEB_SEARCH_PREVIEW` 的等价样本。 */
    private static Map<String, Object> webSearchPreview() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "<string>");
        body.put("input", "<string>");
        body.put("tools", List.of(
                Map.of("type", "web_search", "external_web_access", false),
                Map.of("type", "function", "name", "shell",
                        "description", "<string>", "parameters", "<object>")));
        return body;
    }

    /**
     * 样本 + 规则跑出来只剩 `function` 工具。
     *
     * <p>这条同时验证两件事：规则正确（只删匹配项），以及<strong>样本选得对</strong> ——
     * 若样本里只有 web_search，本条与「删掉整个 tools」的结果无法区分，
     * 预览就失去了说明力。
     */
    @Test
    @DisplayName("只摘掉 web_search，其余工具保留")
    void 只删除web_search工具() {
        RequestBodyRuleEngine.TransformResult result =
                engine.transform(webSearchPreview(), WEB_SEARCH_RULES, WireProtocol.RESPONSES);

        assertThat(result.warnings()).isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.output().get("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0)).containsEntry("type", "function").containsEntry("name", "shell");
    }

    /** 预览必须看得出变化 —— 输出与输入不同，否则编辑器等于瞎的。 */
    @Test
    @DisplayName("预览产出与样本不同")
    void web_search样本能触发规则() {
        Map<String, Object> input = webSearchPreview();
        RequestBodyRuleEngine.TransformResult result =
                engine.transform(input, WEB_SEARCH_RULES, WireProtocol.RESPONSES);

        assertThat(result.output()).isNotEqualTo(input);
    }

    /** 同级字段不受波及。 */
    @Test
    @DisplayName("不波及 model 与 input")
    void web_search规则不动其它字段() {
        RequestBodyRuleEngine.TransformResult result =
                engine.transform(webSearchPreview(), WEB_SEARCH_RULES, WireProtocol.RESPONSES);

        assertThat(result.output()).containsEntry("model", "<string>")
                .containsEntry("input", "<string>");
    }

    // ==================== text.format 组 ====================

    /** 预设里 `MIMO_RESPONSES_TEXT_FORMAT_RULESET` 的等价 V2 规则集。 */
    private static final String TEXT_FORMAT_RULES = """
            {"version":2,"groups":[{"id":"g","name":"json_schema 降级为 json_object","order":0,
             "enabled":true,"protocols":["RESPONSES"],"templateKeys":["custom"],"previewBody":{},
             "rules":[{"id":"mimo-downgrade-json-schema","order":0,"field":"text","array":false,
              "conditional":true,"conditionMode":"all",
              "conditions":[{"path":"./text/format/type","operator":"equals","value":"json_schema"}],
              "operations":[{"type":"edit_object","rules":[
               {"id":"mimo-json-object","order":0,"field":"format","array":false,
                "conditional":false,"conditionMode":"all","conditions":[],
                "operations":[{"type":"set_value","value":{"type":"json_object"}}]}]}]}]}]}
            """;

    /** 预设里 `MIMO_RESPONSES_TEXT_FORMAT_PREVIEW` 的等价样本。 */
    private static Map<String, Object> textFormatPreview() {
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("name", "<string>");
        format.put("strict", true);
        format.put("schema", "<object>");

        Map<String, Object> text = new LinkedHashMap<>();
        text.put("format", format);
        text.put("verbosity", "medium");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "<string>");
        body.put("input", "<string>");
        body.put("text", text);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> formatOf(Map<String, Object> output) {
        return (Map<String, Object>) ((Map<String, Object>) output.get("text")).get("format");
    }

    /**
     * 降级后 `format` 只剩 `type`。
     *
     * <p>样本里那三个多余字段（`name` / `strict` / `schema`）正是「不能只改 type」
     * 的证据：它们随整体替换一起消失。若样本只有 `type`，预览会退化成
     * 「改一个字符串」，看不出为什么要整体替换。
     */
    @Test
    @DisplayName("整体替换 format，多余字段一并消失")
    void 降级后只剩type() {
        RequestBodyRuleEngine.TransformResult result =
                engine.transform(textFormatPreview(), TEXT_FORMAT_RULES, WireProtocol.RESPONSES);

        assertThat(result.warnings()).isEmpty();
        assertThat(formatOf(result.output()))
                .containsEntry("type", "json_object")
                .doesNotContainKeys("name", "schema", "strict");
    }

    /**
     * `text` 里的同级字段保留。
     *
     * <p>样本刻意放了一个 `verbosity`：它与 `format` 同属 `text` 对象但与规则无关，
     * 在预览里保持不变，正好说明规则没有把整个 `text` 换掉。
     */
    @Test
    @DisplayName("text 内的 verbosity 不受影响")
    void 保留同级的verbosity() {
        RequestBodyRuleEngine.TransformResult result =
                engine.transform(textFormatPreview(), TEXT_FORMAT_RULES, WireProtocol.RESPONSES);

        @SuppressWarnings("unchecked")
        Map<String, Object> text = (Map<String, Object>) result.output().get("text");
        assertThat(text).containsEntry("verbosity", "medium");
    }

    @Test
    @DisplayName("预览产出与样本不同")
    void text_format样本能触发规则() {
        Map<String, Object> input = textFormatPreview();
        RequestBodyRuleEngine.TransformResult result =
                engine.transform(input, TEXT_FORMAT_RULES, WireProtocol.RESPONSES);

        assertThat(result.output()).isNotEqualTo(input);
    }

    // ==================== 协议隔离 ====================

    /**
     * 两组都只在 Responses 线路执行。
     *
     * <p>MiMo 预设同时带一个仅适用 CHAT 的图片兼容组，三组共存于同一供应商。
     * 若协议筛选失效，Chat 请求会被这两条规则动到 —— 而 Chat 请求体里
     * 根本没有 `tools`（Codex 的工具在 Chat 侧叫别的）与 `text`，
     * 症状是静默无操作，但那是巧合而非设计。
     */
    @Test
    @DisplayName("两组在 CHAT 与 MESSAGES 线路上都不执行")
    void 只在Responses线路执行() {
        for (WireProtocol protocol : List.of(WireProtocol.CHAT, WireProtocol.MESSAGES)) {
            Map<String, Object> toolsInput = webSearchPreview();
            assertThat(engine.transform(toolsInput, WEB_SEARCH_RULES, protocol).output())
                    .as("web_search 组在 %s 上不应执行", protocol)
                    .isEqualTo(toolsInput);

            Map<String, Object> textInput = textFormatPreview();
            assertThat(engine.transform(textInput, TEXT_FORMAT_RULES, protocol).output())
                    .as("text.format 组在 %s 上不应执行", protocol)
                    .isEqualTo(textInput);
        }
    }
}

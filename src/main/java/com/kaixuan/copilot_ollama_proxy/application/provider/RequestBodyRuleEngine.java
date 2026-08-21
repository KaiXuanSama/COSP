package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 请求体 RuleSet 转换引擎。
 *
 * 该类是独立的纯转换组件，仅将输入请求体深拷贝后按 body_rules_json 执行，不读取数据库。
 * 上游服务在把聊天请求发往上游前调用它；规则语义与前端 request-body-rules/engine.ts 保持一致。
 *
 * <h2>为何放在 application 而不是某个协议包下</h2>
 * 规则本身只是 JSON 路径操作，与报文格式无关 —— OpenAI 与 Anthropic 两侧共用同一份实现。
 * 它原先在 {@code provider/generic/openai} 下，那个位置暗示「这是 OpenAI 专属能力」，
 * 而 Anthropic 侧接入后该暗示就是错的。协议差异只体现在<strong>规则组声明适用哪条线路</strong>，
 * 由 {@link #transform(Map, String, WireProtocol)} 的筛选完成，不影响执行语义。
 *
 * <p>同时接受 V1（扁平 {@code rules}）与 V2（{@code groups} 规则组）两种根结构，见 {@link #parseRules}。
 */
@Component
public final class RequestBodyRuleEngine {

    private final ObjectMapper objectMapper;

    /**
     * 创建请求体 RuleSet 转换引擎。
     *
     * @param objectMapper JSON 序列化器
     */
    public RequestBodyRuleEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 对请求体执行规则集转换。
     *
     * 输入对象不会被修改。无效的规则 JSON 或规则集根结构会返回原请求体的深拷贝和结构化警告。
     *
     * @param input 原始请求体
     * @param bodyRulesJson body_rules_json 内容
     * @param protocol 本次调用实际使用的上游线路协议
     * @return 转换结果
     */
    public TransformResult transform(Map<String, Object> input, String bodyRulesJson, WireProtocol protocol) {
        List<TransformWarning> warnings = new ArrayList<>();
        ObjectNode output = copyInput(input);
        for (JsonNode rule : parseRules(bodyRulesJson, protocol, warnings)) {
            executeRule(output, rule, "", warnings);
        }
        return new TransformResult(toMap(output), List.copyOf(warnings));
    }

    /**
     * 对请求体执行<strong>单一规则列表</strong>，不经规则集根结构与协议筛选。
     *
     * <p>供管理后台的规则预览端点使用：编辑器里每个规则组各带一份调试样本，
     * 预览的语义正是「这一组的规则作用在这一组的样本上」，与协议筛选无关 ——
     * 那一层由用户在卡片上勾选「适用协议」表达，预览不应替他做筛选。
     *
     * <p>入参是 {@code JsonNode} 而非规则 record：规则来自正在编辑中的表单，
     * 可能缺字段、可能有未知操作类型。引擎对这些情况本就产出警告而非抛错，
     * 强类型反序列化反而会在用户打字的中途整个失败。
     *
     * @param input 原始请求体
     * @param rules 规则数组节点；非数组时按空列表处理
     * @return 转换结果
     */
    public TransformResult transformWithRules(Map<String, Object> input, JsonNode rules) {
        List<TransformWarning> warnings = new ArrayList<>();
        ObjectNode output = copyInput(input);
        for (JsonNode rule : sortedRules(rules == null ? objectMapper.createArrayNode() : rules)) {
            executeRule(output, rule, "", warnings);
        }
        return new TransformResult(toMap(output), List.copyOf(warnings));
    }

    private ObjectNode copyInput(Map<String, Object> input) {
        JsonNode source = objectMapper.valueToTree(input == null ? Map.of() : input);
        return source instanceof ObjectNode objectNode ? objectNode.deepCopy() : objectMapper.createObjectNode();
    }

    /**
     * 解析规则集并展平出待执行的规则序列。
     *
     * <p>接受两种根结构：
     * <ul>
     *   <li>V1 {@code {version:1, rules:[...]}} —— 单一扁平规则列表；</li>
     *   <li>V2 {@code {version:2, groups:[...]}} —— 规则装在「规则组」里，每组声明适用线路协议。</li>
     * </ul>
     *
     * <p>V1 只在 {@link WireProtocol#OPENAI} 下执行：那些规则的字段路径是照 OpenAI 请求体写的
     * （{@code messages} 里含 system、无 {@code max_tokens}），作用在 Anthropic 请求体上多数
     * 匹配不到 —— 静默失效比不执行更难排查。这与前端迁移把 V1 归一为
     * {@code protocols:['OPENAI']} 单组是同一个判断。写入路径已不再接受 V1，
     * 但读取仍需兼容：迁移未跑完的窗口里若不认 V1，既有规则会全部静默失效。
     *
     * <p>保持全局 {@code order} 语义：组内规则的 {@code order} 只在组内有效，跨组顺序由组的
     * {@code order} 决定，因此展平必须逐组排序后依次追加，而不能把所有规则混在一起按
     * {@code order} 排。
     *
     * <p>解析失败一律返回空列表并留下警告，即原请求体原样放行 —— 与其他保守放行的判定同调：
     * 宁可让一条没识别的规则不生效，也不要因为结构陌生就把用户的请求改坏。
     */
    private List<JsonNode> parseRules(String bodyRulesJson, WireProtocol protocol, List<TransformWarning> warnings) {
        if (bodyRulesJson == null || bodyRulesJson.isBlank()) {
            warnings.add(new TransformWarning("", "", "请求体规则 JSON 为空，已跳过"));
            return List.of();
        }
        try {
            JsonNode ruleSet = objectMapper.readTree(bodyRulesJson);
            if (!ruleSet.isObject()) {
                warnings.add(new TransformWarning("", "", "请求体规则集必须是 JSON 对象，已跳过"));
                return List.of();
            }
            int version = ruleSet.path("version").asInt(-1);
            if (version == 1 && ruleSet.path("rules").isArray()) {
                return protocol == WireProtocol.OPENAI ? sortedRules(ruleSet.path("rules")) : List.of();
            }
            if (version == 2 && ruleSet.path("groups").isArray()) {
                return flattenGroups(ruleSet.path("groups"), protocol);
            }
            warnings.add(new TransformWarning("", "",
                    "请求体规则集必须是 version=1 含 rules 或 version=2 含 groups，已跳过"));
            return List.of();
        } catch (Exception exception) {
            warnings.add(new TransformWarning("", "", "请求体规则 JSON 无效，已跳过"));
            return List.of();
        }
    }

    /**
     * 按组的 {@code order} 展平适用于当前协议的已启用规则组。
     *
     * <p>{@code protocols} 缺失时视为**全协议适用**：一个 V2 组能存在说明它写于协议概念之后，
     * 作者省略该字段更可能是「没在意」而非「只要某一条线路」。而空数组是显式的「哪条都不要」，
     * 与缺失是不同意图，因此不能用 {@code isEmpty()} 统一处理。
     */
    private List<JsonNode> flattenGroups(JsonNode groups, WireProtocol protocol) {
        List<JsonNode> ordered = new ArrayList<>();
        groups.forEach(ordered::add);
        ordered.sort(Comparator.comparingInt(group -> group.path("order").asInt(0)));

        List<JsonNode> rules = new ArrayList<>();
        for (JsonNode group : ordered) {
            if (group.path("enabled").asBoolean(true) && groupAppliesTo(group, protocol)) {
                rules.addAll(sortedRules(group.path("rules")));
            }
        }
        return rules;
    }

    private boolean groupAppliesTo(JsonNode group, WireProtocol protocol) {
        JsonNode protocols = group.path("protocols");
        if (!protocols.isArray()) {
            return true;
        }
        for (JsonNode candidate : protocols) {
            if (candidate.isTextual() && protocol.name().equals(candidate.asText())) {
                return true;
            }
        }
        return false;
    }

    private void executeRule(ObjectNode scope, JsonNode rule, String parentPath, List<TransformWarning> warnings) {
        String ruleId = rule.path("id").asText("");
        String field = rule.path("field").asText("");
        if (field.isBlank()) {
            warnings.add(new TransformWarning(ruleId, parentPath, "规则未选择目标字段，已跳过"));
            return;
        }

        String fieldPath = parentPath.isBlank() ? field : parentPath + "." + field;
        if (rule.path("array").asBoolean(false)) {
            executeArrayRule(scope, rule, field, fieldPath, warnings);
        } else {
            executeScalarRule(scope, rule, field, fieldPath, warnings);
        }
    }

    private void executeArrayRule(ObjectNode scope, JsonNode rule, String field, String fieldPath,
                                  List<TransformWarning> warnings) {
        JsonNode array = scope.get(field);
        if (!(array instanceof ArrayNode arrayNode)) {
            return;
        }
        for (int index = 0; index < arrayNode.size(); index++) {
            JsonNode item = arrayNode.get(index);
            if (!(item instanceof ObjectNode element) || !conditionsMatch(element, rule)) {
                continue;
            }
            String elementPath = fieldPath + "[" + index + "]";
            for (JsonNode operation : operations(rule)) {
                executeArrayOperation(element, operation, elementPath, rule.path("id").asText(""), warnings);
            }
        }
    }

    private void executeScalarRule(ObjectNode scope, JsonNode rule, String field, String fieldPath,
                                   List<TransformWarning> warnings) {
        if (!conditionsMatch(scope, rule)) {
            return;
        }
        String ruleId = rule.path("id").asText("");
        for (JsonNode operation : operations(rule)) {
            String type = operation.path("type").asText("");
            switch (type) {
                case "edit_object" -> editObject(scope, field, fieldPath, ruleId, operation, warnings);
                // 「设置字段值」留空即置 null 是既定语义：编辑器为该操作提供显式的类型档位
                // （字符串 / 数值 / 列表 / 布尔 / null），null 是其中一档而非某种留空的副作用。
                // 缺 value 的规则只可能来自手写 JSON，按同一语义落 null。
                case "set_value" -> scope.set(field, operation.has("value")
                        ? operation.path("value").deepCopy() : NullNode.getInstance());
                case "delete" -> scope.remove(field);
                default -> warnings.add(new TransformWarning(ruleId, fieldPath, "未知操作类型: " + type));
            }
        }
    }

    private void editObject(ObjectNode scope, String field, String fieldPath, String ruleId, JsonNode operation,
                            List<TransformWarning> warnings) {
        JsonNode target = scope.get(field);
        if (target == null) {
            return;
        }
        if (!(target instanceof ObjectNode targetObject)) {
            warnings.add(new TransformWarning(ruleId, fieldPath, "字段值不是对象，无法执行\"调整对象内容\""));
            return;
        }
        for (JsonNode nestedRule : sortedRules(operation.path("rules"))) {
            executeRule(targetObject, nestedRule, fieldPath, warnings);
        }
    }

    private void executeArrayOperation(ObjectNode element, JsonNode operation, String fieldPath, String ruleId,
                                       List<TransformWarning> warnings) {
        String type = operation.path("type").asText("");
        switch (type) {
            case "edit_object" -> {
                for (JsonNode nestedRule : sortedRules(operation.path("rules"))) {
                    executeRule(element, nestedRule, fieldPath, warnings);
                }
            }
            case "set_value" -> warnings.add(new TransformWarning(
                    ruleId, fieldPath, "数组模式下\"设置字段值\"应通过嵌套规则定位字段"));
            case "delete" -> warnings.add(new TransformWarning(
                    ruleId, fieldPath, "数组模式下\"删除字段\"应通过嵌套规则定位字段"));
            default -> warnings.add(new TransformWarning(ruleId, fieldPath, "未知操作类型: " + type));
        }
    }

    private boolean conditionsMatch(ObjectNode scope, JsonNode rule) {
        if (!rule.path("conditional").asBoolean(false) || !rule.path("conditions").isArray()
                || rule.path("conditions").isEmpty()) {
            return true;
        }
        for (JsonNode condition : rule.path("conditions")) {
            if (!conditionMatches(scope, condition)) {
                return false;
            }
        }
        return true;
    }

    private boolean conditionMatches(JsonNode scope, JsonNode condition) {
        String path = condition.path("path").asText("");
        if (path.isBlank()) {
            return false;
        }
        return switch (condition.path("operator").asText()) {
            case "exists" -> pathExists(scope, parsePath(path), 0);
            case "equals" -> {
                JsonNode value = pathValue(scope, parsePath(path), 0);
                yield value != null && jsonEquals(value, condition.path("value"));
            }
            default -> false;
        };
    }

    /**
     * JSON 值相等判定。
     *
     * <h2>为何不直接用 {@code JsonNode.equals}</h2>
     * Jackson 的 {@code equals} 对数值是<strong>节点类型敏感</strong>的：
     * {@code IntNode(2)} 不等于 {@code DoubleNode(2.0)}，{@code IntNode(2)} 也不等于
     * {@code LongNode(2)}。而 JSON 规范里数值没有整数 / 浮点之分，{@code 2} 与 {@code 2.0}
     * 是同一个值 —— 用户在编辑器里填 {@code 2}，上游送来 {@code 2.0}，规则就静默不匹配，
     * 且他没有任何办法看出原因。数值一律按 {@link java.math.BigDecimal} 的数值比较。
     *
     * <p>递归而非只处理顶层：否则会出现「{@code 2} 匹配 {@code 2.0}，但 {@code [2]}
     * 不匹配 {@code [2.0]}」这种无法解释的分裂 —— 同一条语义在嵌套一层后就变了。
     *
     * <p>NaN / Infinity 交回 {@code equals} 处理：它们不是合法 JSON 值，
     * 只可能来自 Jackson 对特殊浮点的宽松解析，而 {@code decimalValue()} 对它们会抛异常。
     * 判定用 {@code isDouble()/isFloat()} 配 {@code Double.isFinite}，
     * 因为 {@code JsonNode} 本身没有 {@code isNaN()}（那是 {@code DoubleNode} 的内部细节）。
     */
    private boolean jsonEquals(JsonNode actual, JsonNode expected) {
        if (actual.isNumber() && expected.isNumber()) {
            if (isNonFinite(actual) || isNonFinite(expected)) {
                return actual.equals(expected);
            }
            return actual.decimalValue().compareTo(expected.decimalValue()) == 0;
        }
        if (actual.isArray() && expected.isArray()) {
            if (actual.size() != expected.size()) {
                return false;
            }
            for (int index = 0; index < actual.size(); index++) {
                if (!jsonEquals(actual.get(index), expected.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (actual.isObject() && expected.isObject()) {
            if (actual.size() != expected.size()) {
                return false;
            }
            for (var field : actual.properties()) {
                JsonNode counterpart = expected.get(field.getKey());
                if (counterpart == null || !jsonEquals(field.getValue(), counterpart)) {
                    return false;
                }
            }
            return true;
        }
        return actual.equals(expected);
    }

    /** 判断数值节点是否为 NaN 或无穷 —— 这类值不能进 {@code decimalValue()}。 */
    private boolean isNonFinite(JsonNode number) {
        return (number.isDouble() || number.isFloat()) && !Double.isFinite(number.doubleValue());
    }

    private boolean pathExists(JsonNode current, List<PathStep> steps, int index) {
        if (steps == null) {
            return false;
        }
        if (index >= steps.size()) {
            return true;
        }
        if (current == null || !current.isObject()) {
            return false;
        }
        PathStep step = steps.get(index);
        JsonNode next = current.get(step.field());
        if (step.arrayWildcard()) {
            if (!(next instanceof ArrayNode arrayNode)) {
                return false;
            }
            for (JsonNode item : arrayNode) {
                if (pathExists(item, steps, index + 1)) {
                    return true;
                }
            }
            return false;
        }
        return next != null && pathExists(next, steps, index + 1);
    }

    private JsonNode pathValue(JsonNode current, List<PathStep> steps, int index) {
        if (steps == null || current == null) {
            return null;
        }
        if (index >= steps.size()) {
            return current;
        }
        if (!current.isObject()) {
            return null;
        }
        PathStep step = steps.get(index);
        JsonNode next = current.get(step.field());
        if (next == null) {
            return null;
        }
        if (step.arrayWildcard()) {
            if (!(next instanceof ArrayNode arrayNode)) {
                return null;
            }
            for (JsonNode item : arrayNode) {
                JsonNode value = pathValue(item, steps, index + 1);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }
        return pathValue(next, steps, index + 1);
    }

    private List<PathStep> parsePath(String path) {
        if (path == null || !path.startsWith("./")) {
            return null;
        }
        String body = path.substring(2);
        if (body.isBlank()) {
            return null;
        }
        List<PathStep> steps = new ArrayList<>();
        for (String segment : body.split("/")) {
            if (segment.isBlank()) {
                return null;
            }
            boolean wildcard = segment.endsWith("[*]") && segment.length() > 3;
            String field = wildcard ? segment.substring(0, segment.length() - 3) : segment;
            if (field.isBlank()) {
                return null;
            }
            steps.add(new PathStep(field, wildcard));
        }
        return steps;
    }

    private List<JsonNode> sortedRules(JsonNode rules) {
        List<JsonNode> result = new ArrayList<>();
        if (rules.isArray()) {
            rules.forEach(result::add);
        }
        result.sort(Comparator.comparingInt(rule -> rule.path("order").asInt(0)));
        return result;
    }

    private List<JsonNode> operations(JsonNode rule) {
        List<JsonNode> result = new ArrayList<>();
        if (rule.path("operations").isArray()) {
            rule.path("operations").forEach(result::add);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(ObjectNode output) {
        return objectMapper.convertValue(output, Map.class);
    }

    /**
     * RuleSet 转换结果。
     *
     * @param output 转换后的独立请求体副本
     * @param warnings 转换过程中的结构化警告
     */
    public record TransformResult(Map<String, Object> output, List<TransformWarning> warnings) {
    }

    /**
     * 单条规则执行警告。
     *
     * @param ruleId 规则标识
     * @param fieldPath 当前字段路径
     * @param message 人类可读警告
     */
    public record TransformWarning(String ruleId, String fieldPath, String message) {
    }

    private record PathStep(String field, boolean arrayWildcard) {
    }
}

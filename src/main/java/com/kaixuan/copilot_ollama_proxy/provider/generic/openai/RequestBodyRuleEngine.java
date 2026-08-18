package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 请求体 RuleSet 转换引擎。
 *
 * 该类是独立的纯转换组件，仅将输入请求体深拷贝后按 body_rules_json 执行，不读取数据库。
 * 它由 {@link GenericOpenAiChatService} 在生产聊天请求发送到上游前调用；规则语义与前端
 * request-body-rules/engine.ts 保持一致。
 *
 * <p>同时接受 V1（扁平 {@code rules}）与 V2（{@code groups} 规则组）两种根结构，
 * 见 {@link #parseRules}。
 */
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
     * @return 转换结果
     */
    public TransformResult transform(Map<String, Object> input, String bodyRulesJson) {
        List<TransformWarning> warnings = new ArrayList<>();
        ObjectNode output = copyInput(input);
        for (JsonNode rule : parseRules(bodyRulesJson, warnings)) {
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
     * <p>V2 分支目前把**全部已启用组**按 {@code order} 展平，尚不按协议筛选 ——
     * 协议筛选连同 Anthropic 侧接入是下一步的事。保持全局 {@code order} 语义：
     * 组内规则的 {@code order} 只在组内有效，跨组顺序由组的 {@code order} 决定，
     * 因此展平必须逐组排序后依次追加，而不能把所有规则混在一起按 {@code order} 排。
     *
     * <p>解析失败一律返回空列表并留下警告，即原请求体原样放行 —— 与其他保守放行的判定同调：
     * 宁可让一条没识别的规则不生效，也不要因为结构陌生就把用户的请求改坏。
     */
    private List<JsonNode> parseRules(String bodyRulesJson, List<TransformWarning> warnings) {
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
                return sortedRules(ruleSet.path("rules"));
            }
            if (version == 2 && ruleSet.path("groups").isArray()) {
                return flattenGroups(ruleSet.path("groups"));
            }
            warnings.add(new TransformWarning("", "",
                    "请求体规则集必须是 version=1 含 rules 或 version=2 含 groups，已跳过"));
            return List.of();
        } catch (Exception exception) {
            warnings.add(new TransformWarning("", "", "请求体规则 JSON 无效，已跳过"));
            return List.of();
        }
    }

    private List<JsonNode> flattenGroups(JsonNode groups) {
        List<JsonNode> ordered = new ArrayList<>();
        groups.forEach(ordered::add);
        ordered.sort(Comparator.comparingInt(group -> group.path("order").asInt(0)));

        List<JsonNode> rules = new ArrayList<>();
        for (JsonNode group : ordered) {
            if (!group.path("enabled").asBoolean(true)) {
                continue;
            }
            rules.addAll(sortedRules(group.path("rules")));
        }
        return rules;
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
                yield value != null && value.equals(condition.path("value"));
            }
            default -> false;
        };
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

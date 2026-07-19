package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

/**
 * 供应商请求转换配置保存服务。
 *
 * 该服务负责将请求头和请求体规则保存到 provider_request_transform。
 */
@Service
public class ProviderRequestTransformService {

    /** 默认模板选择。 */
    public static final String DEFAULT_TEMPLATE_KEYS_JSON = "[\"base\"]";
    /** 默认基础参数预览。 */
    public static final String DEFAULT_BODY_PREVIEW_JSON = "{"
            + "\"model\":\"<string>\",\"temperature\":0.1,\"top_p\":1.0,"
            + "\"stream\":true,\"n\":1,\"stream_options\":{\"include_usage\":true},"
            + "\"reasoning_effort\":\"medium\"}";
    /** 默认空规则集。 */
    public static final String EMPTY_BODY_RULES_JSON = "{\"version\":1,\"rules\":[]}";

    private static final Set<String> TEMPLATE_KEYS = Set.of(
            "base", "message-start", "message-assistant", "message-tool-basic",
            "message-tool-image", "tools", "custom");
    private static final Set<String> CONDITION_OPERATORS = Set.of("exists", "equals");
    private static final Set<String> OPERATION_TYPES = Set.of("edit_object", "set_value", "delete");

    private final ProviderConfigRepository providerConfigRepository;
    private final ProviderRequestTransformRepository requestTransformRepository;
    private final ObjectMapper objectMapper;

    /**
     * 创建供应商请求转换配置保存服务。
     *
     * @param providerConfigRepository 供应商配置仓储
     * @param requestTransformRepository 请求转换配置仓储
     * @param objectMapper JSON 序列化器
     */
    public ProviderRequestTransformService(ProviderConfigRepository providerConfigRepository,
                                           ProviderRequestTransformRepository requestTransformRepository,
                                           ObjectMapper objectMapper) {
        this.providerConfigRepository = providerConfigRepository;
        this.requestTransformRepository = requestTransformRepository;
        this.objectMapper = objectMapper;
    }

    /**
    * 新增供应商并原子保存请求转换配置。
     *
     * @param providerKey 供应商标识
     * @param baseUrl API 基础地址
    * @param headerRulesJson 请求头规则 JSON
     * @param templateKeysJson 编辑器模板键 JSON
     * @param bodyPreviewJson 编辑器预览请求体 JSON
     * @param bodyRulesJson 请求体规则集 JSON
     * @return 供应商主键
     */
    @Transactional
    public int createProvider(String providerKey, String displayName, String baseUrl, String headerRulesJson,
                                    String templateKeysJson, String bodyPreviewJson,
                                    String bodyRulesJson) {
        ValidatedTransform transform = validate(
            headerRulesJson, templateKeysJson, bodyPreviewJson, bodyRulesJson);
        int providerId = providerConfigRepository.saveProvider(
            providerKey, displayName, true, baseUrl);
        saveRequestTransform(providerId, transform);
        return providerId;
    }

    /**
    * 编辑供应商并原子保存请求转换配置。
     *
     * @param providerId 稳定的供应商主键
     * @param oldProviderKey 原供应商标识
     * @param newProviderKey 新供应商标识
     * @param baseUrl API 基础地址
    * @param headerRulesJson 请求头规则 JSON
     * @param templateKeysJson 编辑器模板键 JSON
     * @param bodyPreviewJson 编辑器预览请求体 JSON
     * @param bodyRulesJson 请求体规则集 JSON
     */
    @Transactional
    public void updateProvider(int providerId, String oldProviderKey, String newProviderKey,
                                     String displayName,
                                     String baseUrl, String headerRulesJson,
                                     String templateKeysJson, String bodyPreviewJson,
                                     String bodyRulesJson) {
        ValidatedTransform transform = validate(
            headerRulesJson, templateKeysJson, bodyPreviewJson, bodyRulesJson);
        providerConfigRepository.updateProviderKeyAndBaseUrl(oldProviderKey, newProviderKey, displayName, baseUrl);
        saveRequestTransform(providerId, transform);
    }

    private void saveRequestTransform(int providerId, ValidatedTransform transform) {
        requestTransformRepository.upsert(
                providerId, 1, transform.headerRulesJson(),
                transform.templateKeysJson(), transform.bodyPreviewJson(),
                1, transform.bodyRulesJson());
    }

    private ValidatedTransform validate(String headerRulesJson, String templateKeysJson,
                                         String bodyPreviewJson, String bodyRulesJson) {
        try {
            JsonNode headers = objectMapper.readTree(headerRulesJson);
            validateHeaders(headers);

            JsonNode templateKeys = objectMapper.readTree(templateKeysJson);
            validateTemplateKeys(templateKeys);
            JsonNode preview = parseObject(bodyPreviewJson, "bodyPreviewJson");
            JsonNode rules = parseObject(bodyRulesJson, "bodyRulesJson");
            validateRuleSet(rules);

            return new ValidatedTransform(
                    objectMapper.writeValueAsString(headers),
                    objectMapper.writeValueAsString(templateKeys),
                    objectMapper.writeValueAsString(preview),
                    objectMapper.writeValueAsString(rules));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求转换配置 JSON 无效: " + e.getMessage(), e);
        }
    }

    private JsonNode parseObject(String json, String fieldName) throws Exception {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        JsonNode node = objectMapper.readTree(json);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(fieldName + " 必须是 JSON 对象");
        }
        return node;
    }

    private void validateHeaders(JsonNode headers) {
        if (!headers.isArray()) {
            throw new IllegalArgumentException("custom_headers 必须是 JSON 数组");
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < headers.size(); i++) {
            JsonNode header = headers.get(i);
            if (!header.isObject() || !header.path("key").isTextual()
                    || !header.path("value").isTextual()) {
                throw new IllegalArgumentException("custom_headers[" + i + "] 必须包含字符串 key 和 value");
            }
            String name = header.path("key").asText().trim().toLowerCase(java.util.Locale.ROOT);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("custom_headers[" + i + "].key 不能为空");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("请求头名称不能重复: " + header.path("key").asText());
            }
        }
    }

    private void validateTemplateKeys(JsonNode keys) {
        if (keys == null || !keys.isArray() || keys.isEmpty()) {
            throw new IllegalArgumentException("bodyTemplateKeysJson 必须是非空字符串数组");
        }
        Set<String> values = new HashSet<>();
        for (JsonNode key : keys) {
            if (!key.isTextual() || !TEMPLATE_KEYS.contains(key.asText())) {
                throw new IllegalArgumentException("不支持的请求体模板键: " + key.asText());
            }
            if (!values.add(key.asText())) {
                throw new IllegalArgumentException("请求体模板键不能重复: " + key.asText());
            }
        }
        if (values.contains("custom") && values.size() != 1) {
            throw new IllegalArgumentException("custom 模板不能与其他模板同时选择");
        }
    }

    private void validateRuleSet(JsonNode root) {
        if (root.path("version").asInt(-1) != 1 || !root.path("rules").isArray()) {
            throw new IllegalArgumentException("bodyRulesJson 必须是 version=1 的规则集");
        }
        validateRules(root.path("rules"), "rules");
    }

    private void validateRules(JsonNode rules, String path) {
        for (int i = 0; i < rules.size(); i++) {
            JsonNode rule = rules.get(i);
            String rulePath = path + "[" + i + "]";
            if (!rule.isObject() || !rule.path("id").isTextual()
                    || !rule.path("order").isIntegralNumber()
                    || !rule.path("order").canConvertToInt() || rule.path("order").asInt() < 0
                    || !rule.path("field").isTextual() || !rule.path("array").isBoolean()
                    || !rule.path("conditional").isBoolean()
                    || !"all".equals(rule.path("conditionMode").asText())
                    || !rule.path("conditions").isArray() || !rule.path("operations").isArray()) {
                throw new IllegalArgumentException(rulePath + " 结构无效");
            }
            validateConditions(rule.path("conditions"), rulePath + ".conditions");
            validateOperations(rule.path("operations"), rulePath + ".operations");
        }
    }

    private void validateConditions(JsonNode conditions, String path) {
        for (int i = 0; i < conditions.size(); i++) {
            JsonNode condition = conditions.get(i);
            if (!condition.isObject() || !condition.path("path").isTextual()
                    || !CONDITION_OPERATORS.contains(condition.path("operator").asText())
                    || !condition.has("value")) {
                throw new IllegalArgumentException(path + "[" + i + "] 结构无效");
            }
        }
    }

    private void validateOperations(JsonNode operations, String path) {
        for (int i = 0; i < operations.size(); i++) {
            JsonNode operation = operations.get(i);
            String type = operation.path("type").asText();
            if (!operation.isObject() || !OPERATION_TYPES.contains(type)) {
                throw new IllegalArgumentException(path + "[" + i + "] 结构无效");
            }
            if ("edit_object".equals(type)) {
                if (!operation.path("rules").isArray()) {
                    throw new IllegalArgumentException(path + "[" + i + "].rules 必须是数组");
                }
                validateRules(operation.path("rules"), path + "[" + i + "].rules");
            }
        }
    }

    private record ValidatedTransform(
            String headerRulesJson,
            String templateKeysJson,
            String bodyPreviewJson,
            String bodyRulesJson) {
    }
}

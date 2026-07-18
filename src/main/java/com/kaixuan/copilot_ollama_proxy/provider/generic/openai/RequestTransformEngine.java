package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;

/**
 * 自定义供应商的请求转换引擎。
 * <p>
 * 执行自定义供应商的新请求头规则。
 */
public final class RequestTransformEngine {

    private static final Logger log = LoggerFactory.getLogger(RequestTransformEngine.class);
    private static final String DEL_MARKER = "/del/";
    private static final TypeReference<List<Map<String, String>>> MAP_LIST_TYPE = new TypeReference<>() {};

    private RequestTransformEngine() {
    }

    /**
    * 应用新表保存的请求头规则。
    *
     * 支持 "{apiKey}" 模板变量，会被替换为实际的 API Key。
     *
     * @param headers           当前请求头（可修改）
     * @param apiKey            当前供应商的 API Key（用于模板替换）
    * @param headerRulesJson   provider_request_transform.header_rules_json 数组
     * @param objectMapper      Jackson ObjectMapper
     */
    public static void applyHeaderRules(HttpHeaders headers, String apiKey, String headerRulesJson, ObjectMapper objectMapper) {
        List<Map<String, String>> headerRules = parseHeaderRules(headerRulesJson, objectMapper);
        if (headerRules.isEmpty()) {
            return;
        }
        for (Map<String, String> entry : headerRules) {
            String key = entry.get("key");
            String value = entry.get("value");
            if (key == null || key.isBlank()) {
                continue;
            }
            key = key.trim();
            if (DEL_MARKER.equals(value)) {
                headers.remove(key);
                log.debug("[Transform] 删除请求头: {}", key);
            } else {
                String resolved = resolveTemplate(value, apiKey);
                headers.set(key, resolved);
                log.debug("[Transform] 设置请求头: {} = {}", key, maskValue(key, resolved));
            }
        }
    }

    // ==================== 内部方法 ====================

    private static List<Map<String, String>> parseHeaderRules(String json, ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(json, MAP_LIST_TYPE);
        } catch (Exception e) {
            log.warn("[Transform] 解析请求头规则失败: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * 替换模板变量。当前支持 "{apiKey}"。
     */
    private static String resolveTemplate(String value, String apiKey) {
        if (value == null) {
            return "";
        }
        return value.replace("{apiKey}", apiKey == null ? "" : apiKey);
    }

    /**
     * 对敏感 header 值进行脱敏。
     */
    private static String maskValue(String headerName, String value) {
        if (value == null) return "null";
        String lower = headerName.toLowerCase();
        if (lower.contains("authorization") || lower.contains("api-key") || lower.contains("token")) {
            return value.length() > 8 ? value.substring(0, 4) + "****" : "****";
        }
        return value;
    }
}

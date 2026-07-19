package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 准备数据库供应商的通用出站请求头与 URL。
 *
 * 默认 Bearer 认证先写入，请求头规则随后可覆盖、补充或删除该默认值。
 */
@Service
public class ProviderRequestHeaderService {

    private static final Logger log = LoggerFactory.getLogger(ProviderRequestHeaderService.class);
    private static final String DELETE_MARKER = "/del/";
    private static final TypeReference<List<Map<String, String>>> HEADER_RULE_LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public ProviderRequestHeaderService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 写入默认 Bearer 认证并应用请求头规则。
     */
    public void applyHeaders(HttpHeaders headers, String apiKey, String headerRulesJson) {
        headers.setBearerAuth(apiKey == null ? "" : apiKey);
        for (Map<String, String> rule : parseHeaderRules(headerRulesJson)) {
            String key = rule.get("key");
            String value = rule.get("value");
            if (key == null || key.isBlank()) {
                continue;
            }
            String trimmedKey = key.trim();
            if (DELETE_MARKER.equals(value)) {
                headers.remove(trimmedKey);
                log.debug("[Transform] 删除请求头: {}", trimmedKey);
                continue;
            }
            String resolvedValue = value == null ? "" : value.replace("{apiKey}", apiKey == null ? "" : apiKey);
            headers.set(trimmedKey, resolvedValue);
            log.debug("[Transform] 设置请求头: {} = {}", trimmedKey, maskValue(trimmedKey, resolvedValue));
        }
    }

    /**
     * 规范化上游 Base URL，移除首尾空白与尾部斜杠。
     */
    public String normalizeBaseUrl(String rawBaseUrl) {
        return rawBaseUrl == null ? "" : rawBaseUrl.trim().replaceAll("/+$", "");
    }

    /**
     * 将规范化后的 Base URL 与路径拼接；路径缺省时由调用方决定。
     */
    public String buildRequestUrl(String rawBaseUrl, String rawPath) {
        String path = rawPath == null ? "" : rawPath.trim();
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        return normalizeBaseUrl(rawBaseUrl) + path;
    }

    private List<Map<String, String>> parseHeaderRules(String headerRulesJson) {
        try {
            return objectMapper.readValue(headerRulesJson == null ? "[]" : headerRulesJson, HEADER_RULE_LIST_TYPE);
        } catch (Exception exception) {
            log.warn("[Transform] 解析请求头规则失败: {}", exception.getMessage());
            return List.of();
        }
    }

    private String maskValue(String headerName, String value) {
        String lowerCaseName = headerName.toLowerCase();
        if (lowerCaseName.contains("authorization") || lowerCaseName.contains("api-key") || lowerCaseName.contains("token")) {
            return value.length() > 8 ? value.substring(0, 4) + "****" : "****";
        }
        return value;
    }
}
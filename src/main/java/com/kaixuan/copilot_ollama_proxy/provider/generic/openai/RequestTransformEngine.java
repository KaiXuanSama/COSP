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
 * 根据数据库中配置的 custom_transforms JSON，对请求头和请求体进行：
 * <ul>
 *   <li>插入 — key 不存在时新增</li>
 *   <li>覆写 — key 已存在时替换</li>
 *   <li>删除 — value 为 "/del/" 时移除该字段</li>
 * </ul>
 */
public final class RequestTransformEngine {

    private static final Logger log = LoggerFactory.getLogger(RequestTransformEngine.class);
    private static final String DEL_MARKER = "/del/";
    private static final TypeReference<List<Map<String, String>>> MAP_LIST_TYPE = new TypeReference<>() {};

    private RequestTransformEngine() {
    }

    /**
     * 应用自定义请求头。
     * <p>
     * 遍历 custom_headers 列表，对每个条目：
     * <ul>
     *   <li>value 为 "/del/" → 移除该 header</li>
     *   <li>其他 → 设置（覆盖或新增）该 header</li>
     * </ul>
     * 支持 "{apiKey}" 模板变量，会被替换为实际的 API Key。
     *
     * @param headers           当前请求头（可修改）
     * @param apiKey            当前供应商的 API Key（用于模板替换）
     * @param customTransforms  custom_transforms JSON 字符串
     * @param objectMapper      Jackson ObjectMapper
     */
    public static void applyCustomHeaders(HttpHeaders headers, String apiKey, String customTransforms, ObjectMapper objectMapper) {
        List<Map<String, String>> customHeaders = parseCustomHeaders(customTransforms, objectMapper);
        if (customHeaders.isEmpty()) {
            return;
        }
        for (Map<String, String> entry : customHeaders) {
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

    /**
     * 应用请求体转换。
     * <p>
     * 遍历 body_transforms 列表，对每个条目：
     * <ul>
     *   <li>value 为 "/del/" → 从请求体中移除该字段</li>
     *   <li>其他 → 设置（覆盖或新增）该字段，值尝试解析为 JSON 类型</li>
     * </ul>
     * 支持顶层字段名（如 "temperature"）和点号路径（如 "messages.0.role"）。
     *
     * @param body              请求体 Map（可修改）
     * @param customTransforms  custom_transforms JSON 字符串
     * @param objectMapper      Jackson ObjectMapper
     */
    public static void applyBodyTransforms(Map<String, Object> body, String customTransforms, ObjectMapper objectMapper) {
        List<Map<String, String>> bodyTransforms = parseBodyTransforms(customTransforms, objectMapper);
        if (bodyTransforms.isEmpty()) {
            return;
        }
        for (Map<String, String> entry : bodyTransforms) {
            String path = entry.get("key");
            String value = entry.get("value");
            if (path == null || path.isBlank()) {
                continue;
            }
            path = path.trim();
            if (DEL_MARKER.equals(value)) {
                removeField(body, path);
                log.debug("[Transform] 删除请求体字段: {}", path);
            } else {
                setField(body, path, parseValue(value));
                log.debug("[Transform] 设置请求体字段: {} = {}", path, value);
            }
        }
    }

    // ==================== 内部方法 ====================

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> parseCustomHeaders(String json, ObjectMapper objectMapper) {
        try {
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            Object headers = root.get("custom_headers");
            if (headers instanceof List<?> list) {
                return objectMapper.convertValue(list, MAP_LIST_TYPE);
            }
        } catch (Exception e) {
            log.warn("[Transform] 解析 custom_headers 失败: {}", e.getMessage());
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> parseBodyTransforms(String json, ObjectMapper objectMapper) {
        try {
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            Object transforms = root.get("body_transforms");
            if (transforms instanceof List<?> list) {
                return objectMapper.convertValue(list, MAP_LIST_TYPE);
            }
        } catch (Exception e) {
            log.warn("[Transform] 解析 body_transforms 失败: {}", e.getMessage());
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
     * 尝试将字符串值解析为合适的 JSON 类型。
     * "0.7" → 0.7 (double), "true" → true (boolean), "42" → 42 (int), 其他 → string
     */
    private static Object parseValue(String value) {
        if (value == null) {
            return null;
        }
        // boolean
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        // number
        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }
            long l = Long.parseLong(value);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return (int) l;
            }
            return l;
        } catch (NumberFormatException ignored) {
        }
        return value;
    }

    /**
     * 设置字段值，支持点号分隔的路径（如 "messages.0.role"）。
     */
    @SuppressWarnings("unchecked")
    private static void setField(Map<String, Object> body, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = body;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (next instanceof Map<?, ?> map) {
                current = (Map<String, Object>) map;
            } else if (next instanceof List<?> list && i + 1 < parts.length) {
                // 数组索引访问
                try {
                    int index = Integer.parseInt(parts[i + 1]);
                    if (index >= 0 && index < list.size()) {
                        Object item = list.get(index);
                        if (item instanceof Map<?, ?> map) {
                            current = (Map<String, Object>) map;
                            i++; // 跳过索引部分
                            continue;
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
                return; // 路径无效
            } else {
                return; // 路径无效
            }
        }
        current.put(parts[parts.length - 1], value);
    }

    /**
     * 删除字段，支持点号分隔的路径。
     */
    @SuppressWarnings("unchecked")
    private static void removeField(Map<String, Object> body, String path) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = body;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (next instanceof Map<?, ?> map) {
                current = (Map<String, Object>) map;
            } else if (next instanceof List<?> list && i + 1 < parts.length) {
                try {
                    int index = Integer.parseInt(parts[i + 1]);
                    if (index >= 0 && index < list.size()) {
                        Object item = list.get(index);
                        if (item instanceof Map<?, ?> map) {
                            current = (Map<String, Object>) map;
                            i++;
                            continue;
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
                return;
            } else {
                return;
            }
        }
        current.remove(parts[parts.length - 1]);
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

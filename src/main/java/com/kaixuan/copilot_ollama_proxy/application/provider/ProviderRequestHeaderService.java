package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        private static final Set<String> NON_FORWARDABLE_HEADERS = Set.of(
            "connection", "content-length", "host", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    private final ObjectMapper objectMapper;

    public ProviderRequestHeaderService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 合并默认头、下游请求头和供应商请求头规则。
     *
     * 优先级从低到高：默认认证/媒体类型、下游可透传头、供应商规则。
     * Host、Content-Length 和 hop-by-hop 头不跨请求透传，由上游 HTTP 客户端重新计算。
     */
    public void applyHeaders(HttpHeaders headers, HttpHeaders downstreamHeaders, String apiKey,
                             String headerRulesJson, boolean stream) {
        headers.setBearerAuth(apiKey == null ? "" : apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(stream ? MediaType.TEXT_EVENT_STREAM : MediaType.ALL));
        copyForwardableHeaders(headers, downstreamHeaders);
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
     * 向后兼容的默认请求头构造入口，供独立单元测试和无下游上下文的调用使用。
     */
    public void applyHeaders(HttpHeaders headers, String apiKey, String headerRulesJson) {
        applyHeaders(headers, HttpHeaders.EMPTY, apiKey, headerRulesJson, false);
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

    /**
     * 为调用日志生成请求头安全快照。
     *
     * 调用方应传入 WebClient 请求过滤器收到的 headers，以确保快照已经包含默认头、
     * 规则头和请求级头（如 Accept）。敏感值统一脱敏，避免 API Key、Cookie 或令牌落库。
     */
    public Map<String, String> createLogSnapshot(HttpHeaders headers) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            String value = String.join(", ", values);
            snapshot.put(canonicalHeaderName(name), isSensitiveHeader(name) ? "****" : value);
        });
        return snapshot;
    }

    /**
     * 将后续捕获的请求头合并到现有日志快照，头名按 HTTP 语义忽略大小写。
     *
     * WebClient 层与 Reactor Netty 层看到的头集合并不完全相同：前者包含规则头和
     * 编码器头，后者包含 User-Agent、Host、Transfer-Encoding 等传输层头。
     */
    public void mergeLogSnapshot(Map<String, String> target, HttpHeaders headers) {
        createLogSnapshot(headers).forEach((name, value) -> {
            String existingName = target.keySet().stream()
                    .filter(key -> key.equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
            if (existingName == null) {
                target.put(name, value);
            } else {
                target.put(existingName, value);
            }
        });
    }

    private void copyForwardableHeaders(HttpHeaders target, HttpHeaders source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        source.forEach((name, values) -> {
            if (!isForwardableHeader(name)) {
                return;
            }
            target.remove(name);
            target.put(name, List.copyOf(values));
        });
    }

    private boolean isForwardableHeader(String headerName) {
        return headerName != null && !NON_FORWARDABLE_HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
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
        if (isSensitiveHeader(headerName)) {
            return value.length() > 8 ? value.substring(0, 4) + "****" : "****";
        }
        return value;
    }

    private boolean isSensitiveHeader(String headerName) {
        String normalizedName = headerName == null ? "" : headerName.toLowerCase();
        return normalizedName.contains("authorization")
                || normalizedName.contains("api-key")
                || normalizedName.contains("apikey")
                || normalizedName.contains("api_key")
                || normalizedName.contains("token")
                || normalizedName.contains("secret")
                || normalizedName.contains("password")
                || normalizedName.contains("cookie");
    }

    private String canonicalHeaderName(String headerName) {
        if (headerName == null) {
            return "";
        }
        return switch (headerName.toLowerCase()) {
            case "accept" -> HttpHeaders.ACCEPT;
            case "authorization" -> HttpHeaders.AUTHORIZATION;
            case "content-length" -> HttpHeaders.CONTENT_LENGTH;
            case "content-type" -> HttpHeaders.CONTENT_TYPE;
            case "cookie" -> HttpHeaders.COOKIE;
            case "host" -> HttpHeaders.HOST;
            case "transfer-encoding" -> HttpHeaders.TRANSFER_ENCODING;
            case "user-agent" -> HttpHeaders.USER_AGENT;
            default -> headerName;
        };
    }
}
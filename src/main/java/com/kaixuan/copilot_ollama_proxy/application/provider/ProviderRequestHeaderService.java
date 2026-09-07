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
 * 装配分三层，后者覆盖前者：先完整透传下游的端到端头（只排除
 * {@link #NON_FORWARDABLE_HEADERS} 里那些描述连接本身的头），再覆盖鉴权头，
 * 最后由供应商请求头规则覆盖、补充或删除任何头。
 *
 * 三层的职责边界不要混：第一层只做传输层正确性，第二层保证出站凭据是供应商配置的
 * 而非下游透传来的，第三层承载「这个上游需要什么」的全部特例。因此凡是超出传输层
 * 正确性的取舍都不应下沉到第一层 —— 规则层拥有最终决定权是有意的设计。
 */
@Service
public class ProviderRequestHeaderService {

    private static final Logger log = LoggerFactory.getLogger(ProviderRequestHeaderService.class);
    private static final String DELETE_MARKER = "/del/";
    private static final TypeReference<List<Map<String, String>>> HEADER_RULE_LIST_TYPE = new TypeReference<>() {};

    /**
     * 不跨连接透传的请求头。
     *
     * 这份清单只关心传输层正确性，不是鉴权策略、也不是「哪些头不该给上游看」的黑名单。
     * 收录标准只有一条：该头描述的是「下游到 COSP 这一段连接」而非这条消息本身，
     * 因此它的值对新建的上游连接一律无效，必须由发起方重新生成。
     *
     * 前八项是 RFC 7230 §6.1 的 hop-by-hop 头。其中 transfer-encoding 说的是下游请求体
     * 怎么分帧的，而出站请求体由 Reactor Netty 重新编码，带着旧值出站会让报文自述与实际
     * 线格式矛盾；proxy-authorization 与 proxy-authenticate 是给中间代理的凭据，语义上
     * 只作用于当前这一跳，转发出去等于把代理凭据交给上游。
     *
     * 另两项是必须重算而非必须隐藏：host 要反映目标 authority，下游那个
     * localhost:11434 带到上游会打错虚拟主机或让 TLS SNI 对不上；content-length 要等于
     * 实际字节数，而 COSP 一路在改请求体（模型名替换、协议翻译、请求体规则、null 清洗），
     * 长度几乎必然变，带着旧长度比不带更糟 —— 上游要么在错误的偏移截断，要么一直等
     * 永远不会来的字节。
     *
     * 鉴权头刻意不在此列。Authorization 与 x-api-key 都是端到端头，描述消息而非连接，
     * 「完全透传下游请求头」是本服务的前提；它们由第二层的鉴权覆盖处理，而非在这里剥离。
     * 把鉴权头加进来会把「覆盖」偷换成「剥离」，还会打断那些正靠透传自定义鉴权头工作的配置。
     *
     * 同理，Cookie 与 Accept-Encoding 也不在此列 —— 它们透传后可能带来问题
     * （如上游返回 Brotli 压缩的 SSE 流导致解码失败），但那属于「这个上游需要什么」，
     * 该由供应商的请求头规则按需删除或改写。本清单管的是「不这么做协议就不成立」，
     * 保持最小化，其余取舍一律交给规则层。
     */
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
     * 优先级从低到高：下游可透传头、默认认证/媒体类型、供应商规则。
     * Host、Content-Length 和 hop-by-hop 头不跨请求透传，由上游 HTTP 客户端重新计算。
     */
    public void applyHeaders(HttpHeaders headers, HttpHeaders downstreamHeaders, String apiKey,
                             String headerRulesJson, boolean stream) {
        copyForwardableHeaders(headers, downstreamHeaders);
        headers.setBearerAuth(apiKey == null ? "" : apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(stream ? MediaType.TEXT_EVENT_STREAM : MediaType.ALL));
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
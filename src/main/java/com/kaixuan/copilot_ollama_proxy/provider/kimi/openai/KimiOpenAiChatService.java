package com.kaixuan.copilot_ollama_proxy.provider.kimi.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Kimi 上游 OpenAI 实现 —— 将请求转发到 Kimi 的 OpenAI 兼容端点。
 * <p>
 * 所有运行时配置（API Key、Base URL）均从数据库读取。
 * Kimi API 使用 Bearer Token 认证，完全兼容 OpenAI 协议。
 * <p>
 * 特殊处理：当 Base URL 为 Coding 端点（{@code api.kimi.com/coding/v1}）时，
 * 强制设置 {@code temperature=1} 和 {@code top_p=0.95} 以满足其请求体限制。
 */
@Service
public class KimiOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    /** Kimi Coding 端点标识，用于判断是否需要强制覆盖请求参数。 */
    private static final String CODING_ENDPOINT_MARKER = "api.kimi.com/coding";

    public KimiOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog,
            @Value("${kimi.default-model:kimi-k2.5}") String fallbackDefaultModel,
            ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
    }

    @Override
    public String getProviderKey() {
        return "kimi";
    }

    @Override
    protected String providerDisplayName() {
        return "Kimi";
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.moonshot.cn/v1";
    }

    @Override
    protected String normalizeBaseUrl(String rawBaseUrl) {
        return rawBaseUrl.replaceAll("/+$", "");
    }

    @Override
    protected void applyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
    }

    @Override
    protected String chatCompletionsUri() {
        return "/chat/completions";
    }

    /**
     * Kimi Coding 端点特殊处理：移除 temperature 和 top_p 字段，避免触发上游限制。
     */
    @Override
    protected void customizeRequestBody(Map<String, Object> body, String resolvedModel) {
        if (isCodingEndpoint()) {
            body.remove("temperature");
            body.remove("top_p");
            log.debug("Kimi Coding 端点：已移除 temperature 和 top_p 字段");
        }
    }

    /**
     * 判断当前配置的 Base URL 是否为 Kimi Coding 端点。
     */
    private boolean isCodingEndpoint() {
        ProviderRuntimeConfiguration config = getActiveProviderConfiguration();
        if (config != null && config.baseUrl() != null) {
            return config.baseUrl().contains(CODING_ENDPOINT_MARKER);
        }
        return false;
    }
}

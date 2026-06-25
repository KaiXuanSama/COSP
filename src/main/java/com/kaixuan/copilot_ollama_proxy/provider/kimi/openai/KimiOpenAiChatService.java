package com.kaixuan.copilot_ollama_proxy.provider.kimi.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * Kimi 上游 OpenAI 实现 —— 将请求转发到 Kimi 的 OpenAI 兼容端点。
 * <p>
 * 所有运行时配置（API Key、Base URL）均从数据库读取。
 * Kimi API 使用 Bearer Token 认证，完全兼容 OpenAI 协议。
 */
@Service
public class KimiOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

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
}

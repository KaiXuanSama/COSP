package com.kaixuan.copilot_ollama_proxy.provider.sensenova.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * SenseNova 上游 OpenAI 实现 —— 将请求转发到 SenseNova 的 OpenAI 兼容端点。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class SenseNovaOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    public SenseNovaOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog, @Value("${sensenova.default-model:sensenova-6.7-flash-lite}") String fallbackDefaultModel,
            ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
    }

    @Override
    public String getProviderKey() {
        return "sensenova";
    }

    @Override
    protected String providerDisplayName() {
        return "SenseNova";
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://token.sensenova.cn";
    }

    @Override
    protected String normalizeBaseUrl(String rawBaseUrl) {
        String normalized = rawBaseUrl.replaceAll("/+$", "");
        // SenseNova 的 API 路径已包含 /v1，确保不以 /v1 结尾避免重复
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    @Override
    protected void applyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
    }

    @Override
    protected String chatCompletionsUri() {
        return "/v1/chat/completions";
    }
}

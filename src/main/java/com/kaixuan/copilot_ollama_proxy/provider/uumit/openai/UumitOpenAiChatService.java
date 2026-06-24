package com.kaixuan.copilot_ollama_proxy.provider.uumit.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * Uumit 上游 OpenAI 实现 —— 将请求转发到 Uumit 的 OpenAI 兼容端点。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class UumitOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    public UumitOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog, @Value("${uumit.default-model:Doubao-Seed-2.0-Lite}") String fallbackDefaultModel, ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
    }

    @Override
    public String getProviderKey() {
        return "uumit";
    }

    @Override
    protected String providerDisplayName() {
        return "Uumit";
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://agent.uumit.com/v1";
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
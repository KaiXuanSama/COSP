package com.kaixuan.copilot_ollama_proxy.provider.agnes.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * Agnes 上游 OpenAI 实现 —— 将请求转发到 Agnes 的 OpenAI 兼容端点。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class AgnesOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    public AgnesOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog, @Value("${agnes.default-model:agnes-2.0-flash}") String fallbackDefaultModel, ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
    }

    @Override
    public String getProviderKey() {
        return "agnes";
    }

    @Override
    protected String providerDisplayName() {
        return "Agnes";
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://apihub.agnes-ai.com/v1";
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
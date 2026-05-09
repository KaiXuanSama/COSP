package com.kaixuan.copilot_ollama_proxy.provider.deepseek.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * DeepSeek 上游 OpenAI 实现 —— 将请求转发到 DeepSeek 的 OpenAI 兼容端点。
 * 所有运行时配置（API Key、Base URL）均从数据库读取。
 */
@Service
public class DeepSeekOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    public DeepSeekOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog, @Value("${deepseek.default-model:deepseek-v4-flash}") String fallbackDefaultModel, ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
    }

    @Override
    public String getProviderKey() {
        return "deepseek";
    }

    @Override
    protected String providerDisplayName() {
        return "DeepSeek";
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.deepseek.com";
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
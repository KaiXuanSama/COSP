package com.kaixuan.copilot_ollama_proxy.provider.longcat.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * LongCat 上游 OpenAI 实现 —— 将请求转发到 LongCat 的 OpenAI 兼容端点。
 * 所有运行时配置（API Key、Base URL）均从数据库读取。
 */
@Service
public class LongCatOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    public LongCatOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog,
            @Value("${longcat.default-model:LongCat-Flash-Chat}") String fallbackDefaultModel,
            ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
    }

    @Override
    public String getProviderKey() {
        return "longcat";
    }

    @Override
    protected String providerDisplayName() {
        return "LongCat";
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.longcat.chat/openai/v1";
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

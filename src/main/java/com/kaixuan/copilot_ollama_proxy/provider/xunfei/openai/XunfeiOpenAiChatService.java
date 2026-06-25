package com.kaixuan.copilot_ollama_proxy.provider.xunfei.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * 讯飞星火上游 OpenAI 实现 —— 将请求转发到讯飞的 OpenAI 兼容端点。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class XunfeiOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    public XunfeiOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog,
            @Value("${xunfei.default-model:xdeepseekv3}") String fallbackDefaultModel,
            ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
    }

    @Override
    public String getProviderKey() {
        return "xunfei";
    }

    @Override
    protected String providerDisplayName() {
        return "Xunfei";
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://maas-api.cn-huabei-1.xf-yun.com/v2";
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

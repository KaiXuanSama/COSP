package com.kaixuan.copilot_ollama_proxy.provider.deepseek.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.AbstractUpstreamChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * DeepSeek 上游 OpenAI 实现 —— 将请求转发到 DeepSeek 的 OpenAI 兼容端点。
 * <p>
 * 特性：
 * <ul>
 *   <li>所有运行时配置（API Key、Base URL）均从数据库读取</li>
 *   <li>自动缓存工具调用时的 reasoning_content，并在后续请求中回填</li>
 *   <li>缓存未命中时注入空字符串作为保底方案</li>
 * </ul>
 */
@Service
public class DeepSeekOpenAiChatService extends AbstractUpstreamChatService {

    public DeepSeekOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog,
            @Value("${deepseek.default-model:deepseek-v4-flash}") String fallbackDefaultModel,
            ObjectMapper objectMapper) {
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
        return "https://api.deepseek.com/v1";
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
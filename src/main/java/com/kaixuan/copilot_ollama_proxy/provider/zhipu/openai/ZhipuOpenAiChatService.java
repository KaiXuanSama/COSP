package com.kaixuan.copilot_ollama_proxy.provider.zhipu.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * 智谱AI 上游 OpenAI 实现 —— 将请求转发到智谱的 OpenAI 兼容端点。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 *
 * 智谱 API 完全兼容 OpenAI 协议：
 * - Base URL: https://open.bigmodel.cn/api/paas/v4
 * - 认证方式: Bearer Token
 * - 支持流式响应、thinking 模式、函数调用
 */
@Service
public class ZhipuOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    public ZhipuOpenAiChatService(
            RuntimeProviderCatalog runtimeProviderCatalog,
            @Value("${zhipu.default-model:glm-4.5-air}") String fallbackDefaultModel,
            ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
    }

    @Override
    public String getProviderKey() {
        return "zhipu";
    }

    @Override
    protected String providerDisplayName() {
        return "Zhipu";
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://open.bigmodel.cn";
    }

    @Override
    protected String normalizeBaseUrl(String rawBaseUrl) {
        String normalized = rawBaseUrl.replaceAll("/+$", "");
        // 自动拼接智谱 API 路径后缀
        if (!normalized.endsWith("/api/paas/v4")) {
            normalized = normalized + "/api/paas/v4";
        }
        return normalized;
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

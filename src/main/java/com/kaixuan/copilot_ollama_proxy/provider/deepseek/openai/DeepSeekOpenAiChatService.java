package com.kaixuan.copilot_ollama_proxy.provider.deepseek.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek 上游 OpenAI 实现 —— 将请求转发到 DeepSeek 的 OpenAI 兼容端点。
 * 所有运行时配置（API Key、Base URL）均从数据库读取。
 */
@Service
public class DeepSeekOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekOpenAiChatService.class);

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

    @Override @SuppressWarnings("unchecked")
    protected void customizeRequestBody(Map<String, Object> body, String resolvedModel) {
        // 向工具调用链中注入空思考内容（reasoning_content），以骗过 DeepSeek
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> messages)) {
            return;
        }
        boolean modified = false;
        for (int i = 0; i < messages.size(); i++) {
            Object msgObj = messages.get(i);
            if (!(msgObj instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> msg = (Map<String, Object>) msgObj;
            // 只处理 assistant 角色且带有 tool_calls 的消息
            if ("assistant".equals(msg.get("role")) && msg.containsKey("tool_calls") && !msg.containsKey("reasoning_content")) {
                msg.put("reasoning_content", "");
                modified = true;
            }
        }
        if (modified) {
            log.debug("【验证测试】已向带 tool_calls 的 assistant 消息注入空 reasoning_content");
        }
    }
}
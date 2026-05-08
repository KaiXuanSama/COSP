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

    // 构造函数，注入运行时 Provider 目录、JSON 对象映射器和默认模型名称。
    public LongCatOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog, @Value("${longcat.default-model:LongCat-Flash-Chat}") String fallbackDefaultModel, ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
    }

    // LongCat 上游服务的提供者键，用于在运行时配置中标识该服务。
    @Override
    public String getProviderKey() {
        return "longcat";
    }

    // LongCat 上游服务的显示名称，用于日志输出和错误提示等场景。
    @Override
    protected String providerDisplayName() {
        return "LongCat";
    }

    // LongCat 上游服务的默认 Base URL，如果运行时配置中没有指定则使用该值。
    @Override
    protected String defaultBaseUrl() {
        return "https://api.longcat.chat";
    }

    // LongCat 上游服务的 Base URL 规范化方法，确保最终的 Base URL 以 "/openai" 结尾，并去除多余的斜杠。
    @Override
    protected String normalizeBaseUrl(String rawBaseUrl) {
        return rawBaseUrl.replaceAll("/+$", "") + "/openai";
    }

    // 在请求体中添加 LongCat 特定的字段或格式转换，例如将模型名称转换为 LongCat 识别的格式。
    @Override
    protected void applyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
    }

    // LongCat 上游服务的 Chat Completions 端点 URI，通常为 "/v1/chat/completions"。
    @Override
    protected String chatCompletionsUri() {
        return "/v1/chat/completions";
    }
}

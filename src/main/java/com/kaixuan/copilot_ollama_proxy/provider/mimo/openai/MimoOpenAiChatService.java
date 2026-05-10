package com.kaixuan.copilot_ollama_proxy.provider.mimo.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/**
 * MiMo 上游 OpenAI 实现 —— 将请求转发到 MiMo 的 OpenAI 兼容端点。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class MimoOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    // 构造函数，注入运行时 Provider 目录、JSON 对象映射器和默认模型名称。
    public MimoOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog, @Value("${mimo.default-model:mimo-v2.5-pro}") String fallbackDefaultModel, ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
    }

    // MiMo 上游服务的提供者键，用于在运行时配置中标识该服务。
    @Override
    public String getProviderKey() {
        return "mimo";
    }

    // MiMo 上游服务的显示名称，用于日志输出和错误提示等场景。
    @Override
    protected String providerDisplayName() {
        return "MiMo";
    }

    // MiMo 上游服务的默认 Base URL，如果运行时配置中没有指定则使用该值。
    @Override
    protected String defaultBaseUrl() {
        return "https://api.xiaomimimo.com";
    }

    // MiMo 上游服务的 Base URL 规范化方法，确保最终的 Base URL 以 "/v1" 结尾，并去除多余的斜杠。
    @Override
    protected String normalizeBaseUrl(String rawBaseUrl) {
        String normalized = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        if (normalized.isEmpty()) {
            return "https://api.xiaomimimo.com/v1";
        }

        // 去除末尾的斜杠，以兼容用户输入的各种 Base URL 形式。
        normalized = normalized.replaceAll("/+$", "");

        // 确保最终的 Base URL 以 "/v1" 结尾。
        if (!normalized.endsWith("/v1")) {
            normalized = normalized + "/v1";
        }
        return normalized;
    }

    // 在请求体中添加 MiMo 特定的字段或格式转换，例如将模型名称转换为 MiMo 识别的格式。
    @Override
    protected void customizeRequestBody(Map<String, Object> body, String resolvedModel) {
        // MiMo 的图片消息格式特殊，需要进行转换以兼容 MiMo 的格式要求。
        if (resolvedModel.contains("mimo")) {
            convertImageFormatForMimo(body);
        }
    }

    // 在请求头中添加 MiMo 特定的认证信息，例如 API Key。
    @Override
    protected void applyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
        headers.set("api-key", apiKey);
        headers.set("x-api-key", apiKey);
    }

    // MiMo 上游服务的 Chat Completions 端点 URI，通常为 "/chat/completions"。
    @Override
    protected String chatCompletionsUri() {
        return "/chat/completions";
    }

    /**
     * MiMo 的图片消息格式特殊，
     * 需要将 content 中的图片项从 {type: "image_url", image_url: {...}} 
     * 转换为 {type: "image_url", url: ...}，并且如果 role 是 "tool" 则转换为 "user"，
     * 以兼容 MiMo 的格式要求。
     * @param body 原始请求体，可能包含 messages 字段，其中的 content 可能包含图片项。
     */
    @SuppressWarnings("unchecked")
    private void convertImageFormatForMimo(Map<String, Object> body) {
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> messages)) {
            return;
        }
        for (Object msgObj : messages) {
            if (!(msgObj instanceof Map<?, ?> msg)) {
                continue;
            }
            Object contentObj = msg.get("content");
            if (!(contentObj instanceof List<?> contentList)) {
                continue;
            }
            boolean hasImage = false;
            for (Object itemObj : contentList) {
                if (!(itemObj instanceof Map<?, ?> item)) {
                    continue;
                }
                if (!"image_url".equals(item.get("type"))) {
                    continue;
                }
                hasImage = true;
                Object imageObj = item.get("image_url");
                if (imageObj instanceof Map<?, ?> imageMap) {
                    if (imageMap.containsKey("media_type")) {
                        ((Map<String, Object>) imageMap).remove("media_type");
                        ((Map<String, Object>) imageMap).put("type", "image_url");
                        log.debug("MiMo 图片格式转换: media_type -> type=image_url");
                    }
                }
            }
            if (hasImage && "tool".equals(msg.get("role"))) {
                ((Map<String, Object>) msg).put("role", "user");
                ((Map<String, Object>) msg).remove("tool_call_id");
                log.debug("MiMo 图片消息 role 转换: tool -> user");
            }
        }
    }
}

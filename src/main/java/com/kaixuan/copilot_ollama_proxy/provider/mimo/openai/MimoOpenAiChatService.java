package com.kaixuan.copilot_ollama_proxy.provider.mimo.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MiMo 上游 OpenAI 实现 —— 将请求转发到 MiMo 的 OpenAI 兼容端点。
 * <p>
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 * <p>
 * MiMo 具体处理：请求体中注入 JSON 工具调用格式提示、图片消息格式转换。
 */
@Service
public class MimoOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    public MimoOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog,
                                 @Value("${mimo.default-model:mimo-v2.5-pro}") String fallbackDefaultModel,
                                 ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
    }

    // ==================== 提供者基本信息 ====================

    @Override
    public String getProviderKey() {
        return "mimo";
    }

    @Override
    protected String providerDisplayName() {
        return "MiMo";
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.xiaomimimo.com";
    }

    @Override
    protected String normalizeBaseUrl(String rawBaseUrl) {
        String normalized = rawBaseUrl == null ? "" : rawBaseUrl.trim().replaceAll("/+$", "");
        if (normalized.isEmpty()) {
            return "https://api.xiaomimimo.com/v1";
        }
        return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
    }

    // ==================== 请求定制 ====================

    @Override
    protected void applyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
        headers.set("api-key", apiKey);
        headers.set("x-api-key", apiKey);
    }

    @Override
    protected String chatCompletionsUri() {
        return "/chat/completions";
    }

    @Override
    protected void customizeRequestBody(Map<String, Object> body, String resolvedModel) {
        if (resolvedModel.contains("mimo")) {
            convertImageFormatForMimo(body);
        }
        injectJsonToolCallGuidance(body);
    }

    // ==================== JSON 工具调用格式提示注入 ====================

    /**
     * 向 system prompt 注入约束，要求 MiMo 使用标准 JSON tool_calls 格式,
     * 禁止输出 XML 格式的工具调用。
     */
    @SuppressWarnings("unchecked")
    private void injectJsonToolCallGuidance(Map<String, Object> body) {
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> rawMessages)) {
            return;
        }
        List<Map<String, Object>> messages = (List<Map<String, Object>>) rawMessages;

        String guidance = """
                IMPORTANT: When you need to call tools, you MUST use the standard JSON tool_calls format. NEVER use XML format.

                Correct JSON format example:
                {
                  "tool_calls": [
                    {
                      "id": "call_xxx",
                      "type": "function",
                      "function": {
                        "name": "replace_string_in_file",
                        "arguments": {
                          "filePath": "/path/to/file",
                          "oldString": "old text",
                          "newString": "new text"
                        }
                      }
                    }
                  ]
                }

                Special notes:
                1. Do NOT use any XML tags such as <tool_calls>, <tool_call>, <function>, <parameter>.
                2. Do NOT output placeholder text like "...existing code...".
                3. For string replacement scenarios (oldString/newString), always use JSON format, never XML.
                4. Always use standard JSON format for all tool calls.
                """;

        for (Object msgObj : messages) {
            if (msgObj instanceof Map<?, ?> msg && "system".equals(msg.get("role"))) {
                String existingContent = msg.get("content") instanceof String s ? s : "";
                ((Map<String, Object>) msgObj).put("content", existingContent + "\n\n" + guidance);
                return;
            }
        }

        List<Map<String, Object>> mutableMessages = new ArrayList<>(messages);
        Map<String, Object> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", guidance);
        mutableMessages.add(0, systemMsg);
        body.put("messages", mutableMessages);
    }

    // ==================== 图片格式转换 ====================

    /**
     * MiMo 的图片消息格式特殊：
     * 将 content 中的图片项从 {type: "image_url", image_url: {url, media_type}} 转换为 {type: "image_url", image_url: {url, type: "image_url"}}，
     * 并且如果 role 是 "tool" 则转换为 "user"。
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
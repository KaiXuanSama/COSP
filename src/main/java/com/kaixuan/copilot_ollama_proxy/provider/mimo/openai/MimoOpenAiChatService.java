package com.kaixuan.copilot_ollama_proxy.provider.mimo.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ReasoningCacheRepository;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * MiMo 具体处理：请求体中注入 JSON 工具调用格式提示、图片消息格式转换、
 * 自动缓存工具调用时的 reasoning_content 并在后续请求中回填。
 */
@Service
public class MimoOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    private static final Logger log = LoggerFactory.getLogger(MimoOpenAiChatService.class);

    private final ReasoningCacheRepository reasoningCacheRepository;

    /**
     * 当前流式响应中累积的 reasoning_content（跨多个 SSE chunk）。
     */
    private final StringBuilder reasoningBuffer = new StringBuilder();

    /**
     * 当前流式响应中正在构建的 tool_call ID 列表。
     */
    private final List<String> pendingToolCallIds = new ArrayList<>();

    public MimoOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog,
                                 @Value("${mimo.default-model:mimo-v2.5-pro}") String fallbackDefaultModel,
                                 ObjectMapper objectMapper,
                                 ReasoningCacheRepository reasoningCacheRepository) {
        super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
        this.reasoningCacheRepository = reasoningCacheRepository;
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
        return "https://api.xiaomimimo.com/v1";
    }

    @Override
    protected String normalizeBaseUrl(String rawBaseUrl) {
        String normalized = rawBaseUrl == null ? "" : rawBaseUrl.trim().replaceAll("/+$", "");
        if (normalized.isEmpty()) {
            return "https://api.xiaomimimo.com/v1";
        }
        return normalized;
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

    // ==================== 思考链缓存：写入 ====================

    @Override @SuppressWarnings("unchecked")
    protected void onRawStreamChunk(String rawChunkJson) {
        if ("[DONE]".equals(rawChunkJson)) {
            return;
        }
        try {
            Map<String, Object> chunk = objectMapper.readValue(rawChunkJson, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
            if (choices == null || choices.isEmpty()) {
                return;
            }

            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
            if (delta == null) {
                return;
            }

            // 1. 累积 reasoning_content
            Object reasoningObj = delta.get("reasoning_content");
            if (reasoningObj instanceof String reasoning && !reasoning.isEmpty()) {
                reasoningBuffer.append(reasoning);
            }

            // 2. 捕获 tool_call ID（只在第一个 tool_calls chunk 中携带 id）
            Object toolCallsObj = delta.get("tool_calls");
            if (toolCallsObj instanceof List<?> toolCalls) {
                for (Object tcObj : toolCalls) {
                    if (tcObj instanceof Map<?, ?> tc) {
                        Object idObj = tc.get("id");
                        if (idObj instanceof String id && !id.isEmpty() && !pendingToolCallIds.contains(id)) {
                            pendingToolCallIds.add(id);
                        }
                    }
                }
            }

            // 3. 流结束时判断是否需要持久化
            Object finishReason = choices.get(0).get("finish_reason");
            if ("tool_calls".equals(finishReason) && !pendingToolCallIds.isEmpty()) {
                String reasoning = reasoningBuffer.toString();
                if (!reasoning.isEmpty()) {
                    for (String toolCallId : pendingToolCallIds) {
                        reasoningCacheRepository.save(toolCallId, reasoning);
                    }
                    log.debug("已缓存 {} 条工具调用思考链 (reasoning 长度: {})", pendingToolCallIds.size(), reasoning.length());
                }
                reasoningBuffer.setLength(0);
                pendingToolCallIds.clear();
            } else if ("stop".equals(finishReason) || "length".equals(finishReason)) {
                // 非工具调用结束，清空状态
                reasoningBuffer.setLength(0);
                pendingToolCallIds.clear();
            }
        } catch (Exception e) {
            // 解析异常不影响主流程
        }
    }

    @Override @SuppressWarnings("unchecked")
    protected void customizeRequestBody(Map<String, Object> body, String resolvedModel) {
        if (resolvedModel.contains("mimo")) {
            convertImageFormatForMimo(body);
        }
        injectJsonToolCallGuidance(body);

        // ==================== 思考链缓存：读取与注入 ====================
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> messages)) {
            return;
        }

        boolean modified = false;
        for (Object msgObj : messages) {
            if (!(msgObj instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> msg = (Map<String, Object>) msgObj;

            // 只处理 assistant 角色且带有 tool_calls 但没有 reasoning_content 的消息
            if (!"assistant".equals(msg.get("role")) || !msg.containsKey("tool_calls") || msg.containsKey("reasoning_content")) {
                continue;
            }

            // 从缓存中查找 reasoning_content（遍历 tool_calls，找到第一个命中的即停止）
            String cachedReasoning = null;
            Object toolCallsObj = msg.get("tool_calls");
            if (toolCallsObj instanceof List<?> toolCalls) {
                for (Object tcObj : toolCalls) {
                    if (tcObj instanceof Map<?, ?> tc) {
                        Object idObj = tc.get("id");
                        if (idObj instanceof String id) {
                            cachedReasoning = reasoningCacheRepository.findByToolCallId(id);
                            if (cachedReasoning != null) {
                                break;
                            }
                        }
                    }
                }
            }

            // 注入：缓存命中用真实内容，未命中用空字符串保底
            msg.put("reasoning_content", cachedReasoning != null ? cachedReasoning : "");
            modified = true;
        }

        if (modified) {
            log.debug("已向带 tool_calls 的 assistant 消息注入 reasoning_content");
        }
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
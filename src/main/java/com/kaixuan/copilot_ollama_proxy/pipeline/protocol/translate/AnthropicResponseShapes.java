package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.after.content.OpenAiContentDetector;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ResponseTranslationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** C2M 两态共用的 Messages 外壳、内容提取与停止原因映射，不持有流级状态。 */
final class AnthropicResponseShapes {

    private static final Logger log = LoggerFactory.getLogger(AnthropicResponseShapes.class);

    private AnthropicResponseShapes() {
    }

    static Map<String, Object> message(String id, String model, List<?> content,
                                       String stopReason, Map<String, Object> usage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("type", "message");
        result.put("role", "assistant");
        result.put("model", model);
        result.put("content", content);
        result.put("stop_reason", stopReason);
        result.put("stop_sequence", null);
        result.put("usage", usage);
        return result;
    }

    static Map<String, Object> textBlock(String type, String text) {
        return Map.of("type", type, "thinking".equals(type) ? "thinking" : "text", text);
    }

    static Map<String, Object> toolBlock(String id, String name, Object input) {
        return Map.of("type", "tool_use", "id", id, "name", name, "input", input);
    }

    /** 同一消息重放稳定、不同消息不复用工具 ID；仅用于上游从未提供 ID 的情况。 */
    static String fallbackToolId(String messageId, int upstreamIndex) {
        byte[] identity = (messageId + ":" + upstreamIndex).getBytes(StandardCharsets.UTF_8);
        return "toolu_cosp_" + UUID.nameUUIDFromBytes(identity).toString().replace("-", "");
    }

    static String stopReason(String finishReason, boolean hasTools) {
        if (finishReason == null || finishReason.isBlank()) {
            return hasTools ? "tool_use" : "end_turn";
        }
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls", "function_call" -> "tool_use";
            // ADR-0015 的已确认兼容策略，不更改上游日志里的 finish_reason。
            case "content_filter" -> "end_turn";
            default -> {
                log.warn("C2M 翻译未知 finish_reason，按 end_turn 输出: {}", finishReason);
                yield "end_turn";
            }
        };
    }

    static String reasoning(JsonNode carrier) {
        // 与 Chat 判空/归一化共用字段清单；生产输入通常已经归一为 reasoning_content。
        for (String key : OpenAiContentDetector.REASONING_KEYS) {
            String value = text(carrier, key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    static List<String> contentTexts(JsonNode content) {
        List<String> result = new ArrayList<>();
        if (content == null || content.isNull()) {
            return result;
        }
        if (content.isTextual()) {
            addNonEmpty(result, content.textValue());
        } else if (content.isArray()) {
            for (JsonNode part : content) {
                String type = text(part, "type");
                if ("text".equals(type) || "output_text".equals(type)) {
                    addNonEmpty(result, text(part, "text"));
                } else if ("refusal".equals(type)) {
                    addNonEmpty(result, text(part, "refusal"));
                } else {
                    log.debug("C2M 翻译忽略无对应文本形态的 content part: {}", type);
                }
            }
        } else {
            throw new ResponseTranslationException("C2M 响应的 content 必须是字符串、数组或 null");
        }
        return result;
    }

    static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    static String nonBlankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static String serialize(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseTranslationException("C2M 响应序列化失败: " + exception.getOriginalMessage());
        }
    }

    private static void addNonEmpty(List<String> target, String value) {
        if (value != null && !value.isEmpty()) {
            target.add(value);
        }
    }
}
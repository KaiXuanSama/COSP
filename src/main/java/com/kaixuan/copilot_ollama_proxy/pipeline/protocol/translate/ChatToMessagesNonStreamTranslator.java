package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ResponseTranslationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Chat 非流式响应 → Messages message；上游判空和 usage 落库均已在执行器内完成。 */
final class ChatToMessagesNonStreamTranslator {

    private final ObjectMapper objectMapper;

    ChatToMessagesNonStreamTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String translate(String body) {
        JsonNode root = parse(body);
        if (root.hasNonNull("error")) {
            throw new ResponseTranslationException("C2M 上游返回错误响应: " + root.get("error"));
        }
        String id = AnthropicResponseShapes.nonBlankOr(
            AnthropicResponseShapes.text(root, "id"), placeholderId());
        List<Map<String, Object>> blocks = new ArrayList<>();
        JsonNode choices = root.get("choices");
        if (choices != null && !choices.isNull() && !choices.isArray()) {
            throw new ResponseTranslationException("C2M 响应的 choices 必须是数组");
        }
        String finishReason = null;
        boolean hasTools = false;
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode choice = choices.get(0);
            JsonNode message = choice.get("message");
            if (message == null || !message.isObject()) {
                throw new ResponseTranslationException("C2M 响应缺少 choices[0].message 对象");
            }
            finishReason = AnthropicResponseShapes.text(choice, "finish_reason");
            String reasoning = AnthropicResponseShapes.reasoning(message);
            if (reasoning != null && !reasoning.isEmpty()) {
                blocks.add(AnthropicResponseShapes.textBlock("thinking", reasoning));
            }
            List<String> texts = AnthropicResponseShapes.contentTexts(message.get("content"));
            String refusal = AnthropicResponseShapes.text(message, "refusal");
            if (refusal != null && !refusal.isEmpty()) {
                texts.add(refusal);
            }

            JsonNode tools = message.get("tool_calls");
            if (tools != null && !tools.isNull() && !tools.isArray()) {
                throw new ResponseTranslationException("C2M 响应的 tool_calls 必须是数组");
            }
            hasTools = tools != null && tools.isArray() && !tools.isEmpty();
            JsonNode legacyTool = message.get("function_call");
            if (!hasTools && legacyTool != null && legacyTool.isObject()) {
                hasTools = true;
            }
            // ADR-0015：纯思考轮保留 thinking，同时给出正文兜底；工具轮不复制思考为正文。
            if (texts.isEmpty() && !hasTools && reasoning != null && !reasoning.isBlank()) {
                texts.add(reasoning);
            }
            for (String text : texts) {
                blocks.add(AnthropicResponseShapes.textBlock("text", text));
            }
            if (tools != null && tools.isArray()) {
                for (int index = 0; index < tools.size(); index++) {
                    JsonNode tool = tools.get(index);
                    JsonNode function = tool.get("function");
                    if (function == null || !function.isObject()) {
                        throw new ResponseTranslationException(
                                "C2M 响应缺少 tool_calls[" + index + "].function 对象");
                    }
                    blocks.add(toolBlock(tool, function, index, id));
                }
            }
            if (legacyTool != null && legacyTool.isObject() && (tools == null || tools.isEmpty())) {
                blocks.add(toolBlock(legacyTool, legacyTool, 0, id));
            }
        }
        if (blocks.isEmpty()) {
            blocks.add(AnthropicResponseShapes.textBlock("text", ""));
        }
        String model = AnthropicResponseShapes.nonBlankOr(AnthropicResponseShapes.text(root, "model"), "");
        return AnthropicResponseShapes.serialize(objectMapper, AnthropicResponseShapes.message(
                id, model, blocks, AnthropicResponseShapes.stopReason(finishReason, hasTools),
                ChatToMessagesUsageConverter.toAnthropicUsage(root.get("usage"))));
    }

    private Map<String, Object> toolBlock(JsonNode tool, JsonNode function, int index, String messageId) {
        String id = AnthropicResponseShapes.nonBlankOr(
                AnthropicResponseShapes.text(tool, "id"), AnthropicResponseShapes.fallbackToolId(messageId, index));
        String name = AnthropicResponseShapes.nonBlankOr(
                AnthropicResponseShapes.text(function, "name"), "tool_" + index);
        return AnthropicResponseShapes.toolBlock(id, name, toolInput(function.get("arguments")));
    }

    private Object toolInput(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return Map.of();
        }
        if (arguments.isObject()) {
            return arguments.deepCopy();
        }
        if (arguments.isTextual() && !arguments.textValue().isBlank()) {
            try {
                JsonNode input = objectMapper.readTree(arguments.textValue());
                if (input != null && input.isObject()) {
                    return input;
                }
            } catch (JsonProcessingException ignored) {
                // ADR-0015 的非流式参数兜底，不修补已有 JSON，也不套到流式分片上。
            }
        }
        return Map.of();
    }

    private JsonNode parse(String body) {
        // native gate 耗尽后可能放行 0 字节。这里仅包成空 message，不另开重试或覆盖原生日志。
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new ResponseTranslationException("C2M 上游响应必须是 JSON 对象");
            }
            return root;
        } catch (JsonProcessingException exception) {
            throw new ResponseTranslationException("C2M 上游响应不是有效 JSON: " + exception.getOriginalMessage());
        }
    }

    static String placeholderId() {
        return "msg_cosp_" + UUID.randomUUID().toString().replace("-", "");
    }
}
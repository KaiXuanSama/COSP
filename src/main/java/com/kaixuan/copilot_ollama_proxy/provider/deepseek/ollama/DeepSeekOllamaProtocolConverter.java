package com.kaixuan.copilot_ollama_proxy.provider.deepseek.ollama;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * DeepSeek 模型的 Ollama 协议转换器。
 * <p>
 * 负责非流式协议映射：
 * 把 OllamaChatRequest 转成 DeepSeek 可接受的 OpenAI Chat Completions 请求体，
 * 再把 DeepSeek 返回的 OpenAI JSON 响应还原成 OllamaChatResponse。
 * <p>
 * DeepSeek 特有字段：
 * - thinking: 控制思考模式 (enabled/disabled)
 * - reasoning_effort: 推理强度 (high/max)
 */
final class DeepSeekOllamaProtocolConverter {

    record Support(Function<String, String> modelResolver, ToIntFunction<Map<String, Object>> maxTokensResolver, Function<Object, String> contentExtractor, Supplier<String> timestampSupplier) {
        Support {
            Objects.requireNonNull(modelResolver, "modelResolver");
            Objects.requireNonNull(maxTokensResolver, "maxTokensResolver");
            Objects.requireNonNull(contentExtractor, "contentExtractor");
            Objects.requireNonNull(timestampSupplier, "timestampSupplier");
        }
    }

    private final ObjectMapper objectMapper;

    DeepSeekOllamaProtocolConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将 Ollama 请求映射为 DeepSeek OpenAI 兼容请求。
     * <p>
     * DeepSeek 支持 thinking 和 reasoning_effort 参数，
     * 这些参数在转换时保留以充分利用 DeepSeek 的思考能力。
     */
    Map<String, Object> toOpenAiRequest(OllamaChatRequest ollamaReq, Support support) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", support.modelResolver().apply(ollamaReq.getModel()));
        body.put("max_tokens", support.maxTokensResolver().applyAsInt(ollamaReq.getOptions()));

        // 保留 DeepSeek 特有的 thinking 控制
        if (ollamaReq.getOptions() != null && ollamaReq.getOptions().containsKey("thinking")) {
            Object thinking = ollamaReq.getOptions().get("thinking");
            if (thinking instanceof String s) {
                body.put("thinking", Map.of("type", s));
            }
        }

        // 保留 DeepSeek 特有的 reasoning_effort
        if (ollamaReq.getOptions() != null && ollamaReq.getOptions().containsKey("reasoning_effort")) {
            body.put("reasoning_effort", ollamaReq.getOptions().get("reasoning_effort"));
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        for (var ollamaMsg : ollamaReq.getMessages()) {
            String role = ollamaMsg.getRole();

            if ("system".equals(role)) {
                messages.add(Map.of("role", "system", "content", support.contentExtractor().apply(ollamaMsg.getContent())));
            } else if ("tool".equals(role)) {
                messages.add(Map.of("role", "tool", "content", support.contentExtractor().apply(ollamaMsg.getContent())));
            } else if ("assistant".equals(role) && ollamaMsg.getToolCalls() != null) {
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("role", "assistant");
                String text = support.contentExtractor().apply(ollamaMsg.getContent());
                message.put("content", text != null ? text : "");

                List<Map<String, Object>> toolCalls = new ArrayList<>();
                for (var toolCall : ollamaMsg.getToolCalls()) {
                    Map<String, Object> openAiToolCall = new LinkedHashMap<>();
                    openAiToolCall.put("id", "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
                    openAiToolCall.put("type", "function");
                    openAiToolCall.put("function", Map.of("name", toolCall.getFunction().getName(), "arguments", serializeArguments(toolCall.getFunction().getArguments())));
                    toolCalls.add(openAiToolCall);
                }
                message.put("tool_calls", toolCalls);
                messages.add(message);
            } else {
                String text = support.contentExtractor().apply(ollamaMsg.getContent());
                messages.add(Map.of("role", role, "content", text != null ? text : ""));
            }
        }

        body.put("messages", messages);

        if (ollamaReq.getTools() != null && !ollamaReq.getTools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (var ollamaTool : ollamaReq.getTools()) {
                if (ollamaTool.getFunction() != null) {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("type", "function");
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", ollamaTool.getFunction().getName());
                    if (ollamaTool.getFunction().getDescription() != null) {
                        function.put("description", ollamaTool.getFunction().getDescription());
                    }
                    if (ollamaTool.getFunction().getParameters() != null) {
                        function.put("parameters", ollamaTool.getFunction().getParameters());
                    }
                    tool.put("function", function);
                    tools.add(tool);
                }
            }
            body.put("tools", tools);
        }

        return body;
    }

    /**
     * 将 DeepSeek 的 OpenAI 非流式响应还原为 OllamaChatResponse。
     * <p>
     * DeepSeek 返回的 reasoning_content 会被映射到 Ollama 的 thinking 字段。
     */
    @SuppressWarnings("unchecked")
    OllamaChatResponse toOllamaResponse(String openAiJson, String requestModel, Support support) throws IOException {
        Map<String, Object> openAi = objectMapper.readValue(openAiJson, Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) openAi.get("choices");

        if (choices == null || choices.isEmpty()) {
            return createResponse(requestModel, true, "stop", createMessage("assistant", ""), support);
        }

        Map<String, Object> choice = choices.get(0);
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        String finishReason = (String) choice.get("finish_reason");

        var ollamaMessage = new OllamaChatResponse.ResponseMessage();
        ollamaMessage.setRole("assistant");

        if (message != null) {
            Object contentObj = message.get("content");
            ollamaMessage.setContent(contentObj instanceof String stringContent ? stringContent : "");

            // DeepSeek 的 reasoning_content → Ollama thinking
            Object reasoningObj = message.get("reasoning_content");
            if (reasoningObj instanceof String reasoning && !reasoning.isEmpty()) {
                ollamaMessage.setThinking(reasoning);
            }

            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
            if (toolCalls != null && !toolCalls.isEmpty()) {
                List<OllamaChatResponse.ToolCallResult> toolCallResults = new ArrayList<>();
                for (var toolCall : toolCalls) {
                    Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
                    if (function != null) {
                        var toolCallResult = new OllamaChatResponse.ToolCallResult();
                        var toolFunction = new OllamaChatResponse.ToolCallFunction();
                        toolFunction.setName((String) function.get("name"));
                        toolFunction.setArguments(parseArguments(function.get("arguments")));
                        toolCallResult.setFunction(toolFunction);
                        toolCallResults.add(toolCallResult);
                    }
                }
                ollamaMessage.setToolCalls(toolCallResults);
            }
        }

        return createResponse(requestModel, true, "tool_calls".equals(finishReason) ? "tool_calls" : "stop", ollamaMessage, support);
    }

    private String serializeArguments(Map<String, Object> arguments) {
        try {
            return arguments != null ? objectMapper.writeValueAsString(arguments) : "{}";
        } catch (Exception ignored) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(Object arguments) {
        if (arguments instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (arguments instanceof String json) {
            try {
                return objectMapper.readValue(json, new TypeReference<>() {
                });
            } catch (Exception ignored) {
            }
        }
        return Map.of();
    }

    private OllamaChatResponse createResponse(String model, boolean done, String doneReason, OllamaChatResponse.ResponseMessage message, Support support) {
        var response = new OllamaChatResponse();
        response.setModel(model);
        response.setCreatedAt(support.timestampSupplier().get());
        response.setDone(done);
        response.setDoneReason(doneReason);
        response.setMessage(message);
        return response;
    }

    private OllamaChatResponse.ResponseMessage createMessage(String role, String content) {
        var message = new OllamaChatResponse.ResponseMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
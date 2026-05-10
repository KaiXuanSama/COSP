package com.kaixuan.copilot_ollama_proxy.provider.ollama;

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
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * 通用的 Ollama 协议转换器。
 * <p>
 * 把 OllamaChatRequest 转成 OpenAI Chat Completions 请求体，
 * 再把 OpenAI JSON 响应还原成 OllamaChatResponse。
 * <p>
 * 各 provider 的特化行为（如 DeepSeek 的 thinking/reasoning_effort）通过
 * {@link #customizeOpenAiRequest} 钩子注入。
 */
public class OllamaProtocolConverter {

    /**
     * 转换器依赖的一组运行时能力。
     */
    public record Support(Function<String, String> modelResolver, ToIntFunction<Map<String, Object>> maxTokensResolver, Function<Object, String> contentExtractor, Supplier<String> timestampSupplier) {
        public Support {
            Objects.requireNonNull(modelResolver, "modelResolver");
            Objects.requireNonNull(maxTokensResolver, "maxTokensResolver");
            Objects.requireNonNull(contentExtractor, "contentExtractor");
            Objects.requireNonNull(timestampSupplier, "timestampSupplier");
        }
    }

    private final ObjectMapper objectMapper;
    private final BiConsumer<Map<String, Object>, OllamaChatRequest> customizeOpenAiRequest;

    /**
     * @param objectMapper          用于协议字段序列化与反序列化
     * @param customizeOpenAiRequest 可选钩子，用于在请求体中添加 provider 特有字段（如 DeepSeek 的 thinking）
     */
    public OllamaProtocolConverter(ObjectMapper objectMapper, BiConsumer<Map<String, Object>, OllamaChatRequest> customizeOpenAiRequest) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.customizeOpenAiRequest = customizeOpenAiRequest;
    }

    /**
     * 无特化钩子的构造器。
     */
    public OllamaProtocolConverter(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    /**
     * 将 Ollama 请求映射为 OpenAI 兼容请求。
     */
    public Map<String, Object> toOpenAiRequest(OllamaChatRequest ollamaReq, Support support) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", support.modelResolver().apply(ollamaReq.getModel()));
        body.put("max_tokens", support.maxTokensResolver().applyAsInt(ollamaReq.getOptions()));

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

        // 调用 provider 特化钩子
        if (customizeOpenAiRequest != null) {
            customizeOpenAiRequest.accept(body, ollamaReq);
        }

        return body;
    }

    /**
     * 将 OpenAI 非流式响应还原为 OllamaChatResponse。
     */
    @SuppressWarnings("unchecked")
    public OllamaChatResponse toOllamaResponse(String openAiJson, String requestModel, Support support) throws IOException {
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

            // 提取 reasoning_content → thinking 字段
            Object reasoningObj = message.get("reasoning_content");
            if (reasoningObj instanceof String reasoning && !reasoning.isEmpty()) {
                ollamaMessage.setThinking(reasoning);
            }

            // 处理工具调用
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
            if (toolCalls != null && !toolCalls.isEmpty()) {
                List<OllamaChatResponse.ToolCallResult> results = new ArrayList<>();
                for (Map<String, Object> tc : toolCalls) {
                    Map<String, Object> func = (Map<String, Object>) tc.get("function");
                    if (func != null) {
                        var result = new OllamaChatResponse.ToolCallResult();
                        var fn = new OllamaChatResponse.ToolCallFunction();
                        fn.setName((String) func.get("name"));
                        Object args = func.get("arguments");
                        if (args instanceof String argsStr) {
                            try {
                                fn.setArguments(objectMapper.readValue(argsStr, Map.class));
                            } catch (Exception e) {
                                fn.setArguments(Map.of());
                            }
                        } else if (args instanceof Map) {
                            fn.setArguments((Map<String, Object>) args);
                        }
                        result.setFunction(fn);
                        results.add(result);
                    }
                }
                ollamaMessage.setToolCalls(results);
            }
        }

        boolean done = "stop".equals(finishReason) || "tool_calls".equals(finishReason);
        return createResponse(requestModel, done, finishReason != null ? finishReason : "stop", ollamaMessage, support);
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

    private static OllamaChatResponse.ResponseMessage createMessage(String role, String content) {
        var msg = new OllamaChatResponse.ResponseMessage();
        msg.setRole(role);
        msg.setContent(content);
        return msg;
    }

    private String serializeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (Exception e) {
            return "{}";
        }
    }
}
package com.kaixuan.copilot_ollama_proxy.provider.longcat.ollama;

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
 * LongCat 模型的 Ollama 协议转换器。
 * <p>
 * 这个类只负责非流式协议映射：
 * 把 OllamaChatRequest 转成 LongCat 可接受的 OpenAI Chat Completions 请求体，
 * 再把 LongCat 返回的 OpenAI JSON 响应还原成 OllamaChatResponse。
 * 与之对应的流式状态累计逻辑放在 LongCatOllamaStreamTranslator 中，避免两类职责重新缠在一起。
 */
final class LongCatOllamaProtocolConverter {

    /**
     * 转换器依赖的一组运行时能力。
     * <p>
     * 这里不直接依赖 service 或基类，而是显式传入模型解析、token 解析、文本提取和时间戳生成能力，
     * 这样转换器可以保持纯协议层，不感知更高层的 provider 细节。
     */
    record Support(Function<String, String> modelResolver, ToIntFunction<Map<String, Object>> maxTokensResolver, Function<Object, String> contentExtractor, Supplier<String> timestampSupplier) {
        Support {
            Objects.requireNonNull(modelResolver, "modelResolver");
            Objects.requireNonNull(maxTokensResolver, "maxTokensResolver");
            Objects.requireNonNull(contentExtractor, "contentExtractor");
            Objects.requireNonNull(timestampSupplier, "timestampSupplier");
        }
    }

    /** Jackson 用于序列化工具参数与解析上游 JSON。 */
    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper 用于协议字段序列化与反序列化的 Jackson 对象映射器
     */
    LongCatOllamaProtocolConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将 Ollama 请求映射为 LongCat OpenAI 兼容请求。
     * <p>
     * 这里处理的重点不是简单字段复制，而是角色和工具调用的语义对齐：
     * assistant/tool_calls 需要被还原成 OpenAI function calling 结构，
     * tools 也需要从 Ollama 的 function 定义转成 OpenAI tools 数组。
     *
     * @param ollamaReq 原始 Ollama 请求对象
     * @param support 转换时依赖的运行时能力
     * @return 可直接发送给 LongCat OpenAI 兼容端点的请求体 Map
     */
    Map<String, Object> toOpenAiRequest(OllamaChatRequest ollamaReq, Support support) {
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

        return body;
    }

    /**
        * 将 LongCat 的 OpenAI 非流式响应还原为 OllamaChatResponse。
        * <p>
        * 这里会保留 reasoning_content 和 tool_calls 这两类对 Copilot 很关键的字段，
        * 避免在非流式路径上丢失思考内容或函数调用结果。
        *
     * @param openAiJson OpenAI 格式的响应 JSON 字符串
     * @param requestModel 原始请求中的模型名称，用于构建 OllamaChatResponse 的 model 字段
     * @param support 协议支持对象，包含内容提取和时间戳生成等功能接口
     * @return 转换后的 OllamaChatResponse 对象
     * @throws IOException 如果 JSON 解析失败
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

    /**
     * 把 OpenAI function.arguments 统一还原成 Map。
     * <p>
     * LongCat 上游既可能返回字符串化 JSON，也可能已经是对象结构，因此这里做一次兼容性收口。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(Object arguments) throws IOException {
        if (arguments instanceof String argsString) {
            return objectMapper.readValue(argsString, new TypeReference<>() {
            });
        }
        if (arguments instanceof Map<?, ?> argsMap) {
            return (Map<String, Object>) argsMap;
        }
        return Map.of();
    }

    /**
     * 构造一个标准的 Ollama 响应对象。
     */
    private OllamaChatResponse createResponse(String modelName, boolean done, String doneReason, OllamaChatResponse.ResponseMessage message, Support support) {
        var response = new OllamaChatResponse();
        response.setModel(modelName);
        response.setCreatedAt(support.timestampSupplier().get());
        response.setDone(done);
        response.setDoneReason(doneReason);
        response.setMessage(message);
        return response;
    }

    /**
     * 构造一个简单的 assistant 消息体。
     */
    private OllamaChatResponse.ResponseMessage createMessage(String role, String content) {
        var message = new OllamaChatResponse.ResponseMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
package com.kaixuan.copilot_ollama_proxy.provider.mimo.ollama;

import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Mimo 模型的 Ollama 协议转换器。
 * <p>
 * 它负责 MiMo 非流式链路中的协议映射：
 * 把 OllamaChatRequest 映射为 AnthropicRequest，
 * 再把 AnthropicResponse 还原成 OllamaChatResponse。
 * 与流式状态累计相关的逻辑则由 MimoOllamaStreamTranslator 负责。
 */
final class MimoOllamaProtocolConverter {

    /**
     * 转换器依赖的运行时能力集合。
     */
    record Support(Function<String, String> modelResolver, ToIntFunction<Map<String, Object>> maxTokensResolver, Function<Object, String> contentExtractor, Supplier<String> timestampSupplier) {
        Support {
            Objects.requireNonNull(modelResolver, "modelResolver");
            Objects.requireNonNull(maxTokensResolver, "maxTokensResolver");
            Objects.requireNonNull(contentExtractor, "contentExtractor");
            Objects.requireNonNull(timestampSupplier, "timestampSupplier");
        }
    }

    /**
        * 将 Ollama 请求映射为 MiMo Anthropic 兼容请求。
        * <p>
        * 这里最重要的差异不是字段名，而是消息组织方式：
        * system 要上提到顶层字段，tool 消息要改写成 user/tool_result block，
        * assistant 的 tool_calls 则需要转成 Anthropic 的 tool_use block。
        *
        * @param ollamaReq Ollama 请求对象
        * @param support 转换时依赖的运行时能力
        * @return 可直接发送给 MiMo Anthropic 兼容端点的请求对象
     */
    AnthropicRequest toAnthropicRequest(OllamaChatRequest ollamaReq, Support support) {
        var request = new AnthropicRequest();
        request.setModel(support.modelResolver().apply(ollamaReq.getModel()));
        request.setMaxTokens(support.maxTokensResolver().applyAsInt(ollamaReq.getOptions()));
        request.setStream(false);

        List<AnthropicRequest.SystemContent> systemParts = new ArrayList<>();
        List<AnthropicRequest.Message> messages = new ArrayList<>();

        for (var ollamaMsg : ollamaReq.getMessages()) {
            String role = ollamaMsg.getRole();

            if ("system".equals(role)) {
                systemParts.add(new AnthropicRequest.SystemContent(support.contentExtractor().apply(ollamaMsg.getContent())));
            } else if ("tool".equals(role)) {
                List<Map<String, Object>> toolResultContent = new ArrayList<>();
                Map<String, Object> block = new HashMap<>();
                block.put("type", "tool_result");
                block.put("tool_use_id", "toolu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                block.put("content", support.contentExtractor().apply(ollamaMsg.getContent()));
                toolResultContent.add(block);
                messages.add(new AnthropicRequest.Message("user", toolResultContent));
            } else {
                List<Object> contentBlocks = new ArrayList<>();

                if (ollamaMsg.getToolCalls() != null) {
                    for (var toolCall : ollamaMsg.getToolCalls()) {
                        Map<String, Object> toolUseBlock = new HashMap<>();
                        toolUseBlock.put("type", "tool_use");
                        toolUseBlock.put("id", "toolu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                        toolUseBlock.put("name", toolCall.getFunction().getName());
                        toolUseBlock.put("input", toolCall.getFunction().getArguments() != null ? toolCall.getFunction().getArguments() : Map.of());
                        contentBlocks.add(toolUseBlock);
                    }
                }

                String text = support.contentExtractor().apply(ollamaMsg.getContent());
                if (text != null && !text.isEmpty()) {
                    if (contentBlocks.isEmpty()) {
                        messages.add(new AnthropicRequest.Message(role, text));
                        continue;
                    }
                    Map<String, Object> textBlock = new HashMap<>();
                    textBlock.put("type", "text");
                    textBlock.put("text", text);
                    contentBlocks.add(0, textBlock);
                }

                if (!contentBlocks.isEmpty()) {
                    messages.add(new AnthropicRequest.Message(role, contentBlocks));
                }
            }
        }

        if (!systemParts.isEmpty()) {
            request.setSystem(systemParts);
        }
        request.setMessages(messages);

        if (ollamaReq.getTools() != null && !ollamaReq.getTools().isEmpty()) {
            List<AnthropicRequest.Tool> tools = new ArrayList<>();
            for (var ollamaTool : ollamaReq.getTools()) {
                if (ollamaTool.getFunction() != null) {
                    var tool = new AnthropicRequest.Tool();
                    tool.setName(ollamaTool.getFunction().getName());
                    tool.setDescription(ollamaTool.getFunction().getDescription());
                    tool.setInputSchema(ollamaTool.getFunction().getParameters());
                    tools.add(tool);
                }
            }
            request.setTools(tools);
        }

        return request;
    }

    /**
     * 将 MiMo Anthropic 非流式响应还原成 Ollama 响应对象。
     * <p>
     * 文本、thinking 和 tool_use 三类 block 都会在这里被保留下来，
     * 以便 Copilot 在非流式场景下仍能拿到完整的能力信息。
     */
    OllamaChatResponse toOllamaResponse(AnthropicResponse anthropicResp, String requestModel, Support support) {
        var response = new OllamaChatResponse();
        response.setModel(requestModel);
        response.setCreatedAt(support.timestampSupplier().get());
        response.setDone(true);
        response.setDoneReason("stop".equals(anthropicResp.getStopReason()) ? "stop" : "tool_calls");

        var message = new OllamaChatResponse.ResponseMessage();
        message.setRole("assistant");

        if (anthropicResp.getContent() != null) {
            StringBuilder textBuilder = new StringBuilder();
            List<OllamaChatResponse.ToolCallResult> toolCalls = new ArrayList<>();

            for (var block : anthropicResp.getContent()) {
                if ("text".equals(block.getType())) {
                    textBuilder.append(block.getText());
                } else if ("thinking".equals(block.getType())) {
                    message.setThinking(block.getThinking());
                } else if ("tool_use".equals(block.getType())) {
                    var toolCallResult = new OllamaChatResponse.ToolCallResult();
                    var function = new OllamaChatResponse.ToolCallFunction();
                    function.setName(block.getName());
                    function.setArguments(block.getInput());
                    toolCallResult.setFunction(function);
                    toolCalls.add(toolCallResult);
                }
            }

            message.setContent(textBuilder.toString());
            if (!toolCalls.isEmpty()) {
                message.setToolCalls(toolCalls);
            }
        }

        response.setMessage(message);
        return response;
    }
}
package com.kaixuan.copilot_ollama_proxy.provider.mimo.ollama;

import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MimoOllamaProtocolConverterTests {

    private static final String FIXED_TIMESTAMP = "2026-05-07T12:00:00Z";

    private final MimoOllamaProtocolConverter converter = new MimoOllamaProtocolConverter();

    private final MimoOllamaProtocolConverter.Support support = new MimoOllamaProtocolConverter.Support(model -> model == null || model.isBlank() ? "mimo-v2.5-pro" : model,
            options -> options != null && options.get("num_predict") instanceof Number number ? number.intValue() : 8192, this::extractStringContent, () -> FIXED_TIMESTAMP);

    @Test
    void convertsSystemToolAndAssistantMessagesToAnthropicRequest() {
        OllamaChatRequest request = new OllamaChatRequest();
        request.setModel(null);
        request.setOptions(Map.of("num_predict", 512));

        OllamaChatRequest.Message systemMessage = new OllamaChatRequest.Message();
        systemMessage.setRole("system");
        systemMessage.setContent("be concise");

        OllamaChatRequest.Message toolMessage = new OllamaChatRequest.Message();
        toolMessage.setRole("tool");
        toolMessage.setContent("file content");

        OllamaChatRequest.ToolCall toolCall = new OllamaChatRequest.ToolCall();
        OllamaChatRequest.ToolFunction toolCallFunction = new OllamaChatRequest.ToolFunction();
        toolCallFunction.setName("read_file");
        toolCallFunction.setArguments(Map.of("path", "README.md"));
        toolCall.setFunction(toolCallFunction);

        OllamaChatRequest.Message assistantMessage = new OllamaChatRequest.Message();
        assistantMessage.setRole("assistant");
        assistantMessage.setContent("calling tool");
        assistantMessage.setToolCalls(List.of(toolCall));

        OllamaChatRequest.Tool tool = new OllamaChatRequest.Tool();
        tool.setType("function");
        OllamaChatRequest.ToolFunction toolFunction = new OllamaChatRequest.ToolFunction();
        toolFunction.setName("read_file");
        toolFunction.setDescription("Read file");
        toolFunction.setParameters(Map.of("type", "object"));
        tool.setFunction(toolFunction);

        request.setMessages(List.of(systemMessage, toolMessage, assistantMessage));
        request.setTools(List.of(tool));

        AnthropicRequest anthropicRequest = converter.toAnthropicRequest(request, support);

        assertThat(anthropicRequest.getModel()).isEqualTo("mimo-v2.5-pro");
        assertThat(anthropicRequest.getMaxTokens()).isEqualTo(512);
        assertThat(anthropicRequest.getStream()).isFalse();
        assertThat(anthropicRequest.getSystem()).hasSize(1);
        assertThat(anthropicRequest.getSystem().get(0).getText()).isEqualTo("be concise");
        assertThat(anthropicRequest.getMessages()).hasSize(2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolResultContent = (List<Map<String, Object>>) anthropicRequest.getMessages().get(0).getContent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assistantContent = (List<Map<String, Object>>) anthropicRequest.getMessages().get(1).getContent();

        assertThat(anthropicRequest.getMessages().get(0).getRole()).isEqualTo("user");
        assertThat(toolResultContent.get(0)).containsEntry("type", "tool_result").containsEntry("content", "file content");
        assertThat(assistantContent.get(0)).containsEntry("type", "text").containsEntry("text", "calling tool");
        assertThat(assistantContent.get(1)).containsEntry("type", "tool_use").containsEntry("name", "read_file");
        assertThat(anthropicRequest.getTools()).hasSize(1);
        assertThat(anthropicRequest.getTools().get(0).getInputSchema()).containsEntry("type", "object");
    }

    @Test
    void convertsAnthropicThinkingAndToolUseToOllamaResponse() {
        AnthropicResponse.ContentBlock thinkingBlock = new AnthropicResponse.ContentBlock();
        thinkingBlock.setType("thinking");
        thinkingBlock.setThinking("chain of thought");

        AnthropicResponse.ContentBlock textBlock = new AnthropicResponse.ContentBlock();
        textBlock.setType("text");
        textBlock.setText("answer");

        AnthropicResponse.ContentBlock toolUseBlock = new AnthropicResponse.ContentBlock();
        toolUseBlock.setType("tool_use");
        toolUseBlock.setName("read_file");
        toolUseBlock.setInput(Map.of("path", "README.md"));

        AnthropicResponse response = new AnthropicResponse();
        response.setStopReason("tool_use");
        response.setContent(List.of(thinkingBlock, textBlock, toolUseBlock));

        OllamaChatResponse ollamaResponse = converter.toOllamaResponse(response, "mimo-v2.5-pro", support);

        assertThat(ollamaResponse.getModel()).isEqualTo("mimo-v2.5-pro");
        assertThat(ollamaResponse.getCreatedAt()).isEqualTo(FIXED_TIMESTAMP);
        assertThat(ollamaResponse.isDone()).isTrue();
        assertThat(ollamaResponse.getDoneReason()).isEqualTo("tool_calls");
        assertThat(ollamaResponse.getMessage().getRole()).isEqualTo("assistant");
        assertThat(ollamaResponse.getMessage().getThinking()).isEqualTo("chain of thought");
        assertThat(ollamaResponse.getMessage().getContent()).isEqualTo("answer");
        assertThat(ollamaResponse.getMessage().getToolCalls()).hasSize(1);
        assertThat(ollamaResponse.getMessage().getToolCalls().get(0).getFunction().getName()).isEqualTo("read_file");
        assertThat(ollamaResponse.getMessage().getToolCalls().get(0).getFunction().getArguments()).containsEntry("path", "README.md");
    }

    private String extractStringContent(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String stringContent) {
            return stringContent;
        }
        if (content instanceof List<?> listContent) {
            StringBuilder builder = new StringBuilder();
            for (Object item : listContent) {
                if (item instanceof Map<?, ?> mapContent) {
                    Object text = mapContent.get("text");
                    if (text != null) {
                        builder.append(text);
                    }
                }
            }
            return builder.toString();
        }
        return content.toString();
    }
}
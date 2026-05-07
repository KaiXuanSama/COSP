package com.kaixuan.copilot_ollama_proxy.provider.longcat.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LongCatOllamaProtocolConverterTests {

    private static final String FIXED_TIMESTAMP = "2026-05-07T12:00:00Z";

    private final LongCatOllamaProtocolConverter converter = new LongCatOllamaProtocolConverter(new ObjectMapper());

    private final LongCatOllamaProtocolConverter.Support support = new LongCatOllamaProtocolConverter.Support(model -> model == null || model.isBlank() ? "fallback-model" : model,
            options -> options != null && options.get("num_predict") instanceof Number number ? number.intValue() : 8192, this::extractStringContent, () -> FIXED_TIMESTAMP);

    @Test
    void convertsAssistantToolCallsToOpenAiPayload() {
        OllamaChatRequest request = new OllamaChatRequest();
        request.setModel(null);
        request.setOptions(Map.of("num_predict", 256));

        OllamaChatRequest.ToolCall toolCall = new OllamaChatRequest.ToolCall();
        OllamaChatRequest.ToolFunction toolFunction = new OllamaChatRequest.ToolFunction();
        toolFunction.setName("read_file");
        toolFunction.setArguments(Map.of("path", "README.md"));
        toolCall.setFunction(toolFunction);

        OllamaChatRequest.Message assistantMessage = new OllamaChatRequest.Message();
        assistantMessage.setRole("assistant");
        assistantMessage.setContent("let me inspect that");
        assistantMessage.setToolCalls(List.of(toolCall));

        OllamaChatRequest.Tool requestTool = new OllamaChatRequest.Tool();
        requestTool.setType("function");
        OllamaChatRequest.ToolFunction requestToolFunction = new OllamaChatRequest.ToolFunction();
        requestToolFunction.setName("read_file");
        requestToolFunction.setDescription("Read file content");
        requestToolFunction.setParameters(Map.of("type", "object"));
        requestTool.setFunction(requestToolFunction);

        request.setMessages(List.of(assistantMessage));
        request.setTools(List.of(requestTool));

        Map<String, Object> openAiRequest = converter.toOpenAiRequest(request, support);

        assertThat(openAiRequest).containsEntry("model", "fallback-model");
        assertThat(openAiRequest).containsEntry("max_tokens", 256);

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) ((List<?>) openAiRequest.get("messages")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> openAiToolCall = (Map<String, Object>) ((List<?>) message.get("tool_calls")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) openAiToolCall.get("function");
        @SuppressWarnings("unchecked")
        Map<String, Object> tool = (Map<String, Object>) ((List<?>) openAiRequest.get("tools")).get(0);

        assertThat(message).containsEntry("role", "assistant").containsEntry("content", "let me inspect that");
        assertThat(openAiToolCall).containsEntry("type", "function");
        assertThat(function).containsEntry("name", "read_file");
        assertThat((String) function.get("arguments")).contains("README.md");
        assertThat(tool).containsEntry("type", "function");
    }

    @Test
    void convertsOpenAiToolCallsAndReasoningToOllamaResponse() throws Exception {
        String openAiJson = new ObjectMapper().writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message",
                Map.of("content", "", "reasoning_content", "thinking", "tool_calls", List.of(Map.of("function", Map.of("name", "read_file", "arguments", "{\"path\":\"README.md\"}"))))))));

        OllamaChatResponse response = converter.toOllamaResponse(openAiJson, "LongCat-Flash-Chat", support);

        assertThat(response.getModel()).isEqualTo("LongCat-Flash-Chat");
        assertThat(response.getCreatedAt()).isEqualTo(FIXED_TIMESTAMP);
        assertThat(response.isDone()).isTrue();
        assertThat(response.getDoneReason()).isEqualTo("tool_calls");
        assertThat(response.getMessage().getRole()).isEqualTo("assistant");
        assertThat(response.getMessage().getThinking()).isEqualTo("thinking");
        assertThat(response.getMessage().getToolCalls()).hasSize(1);
        assertThat(response.getMessage().getToolCalls().get(0).getFunction().getName()).isEqualTo("read_file");
        assertThat(response.getMessage().getToolCalls().get(0).getFunction().getArguments()).containsEntry("path", "README.md");
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
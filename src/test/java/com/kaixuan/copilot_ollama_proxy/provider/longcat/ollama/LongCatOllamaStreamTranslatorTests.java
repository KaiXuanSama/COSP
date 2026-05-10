package com.kaixuan.copilot_ollama_proxy.provider.longcat.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.provider.ollama.OllamaStreamTranslator;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LongCatOllamaStreamTranslatorTests {

    private static final String FIXED_TIMESTAMP = "2026-05-07T12:00:00Z";

    @Test
    void streamsContentAndFinalDoneChunk() throws Exception {
        OllamaStreamTranslator translator = newTranslator();
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("delta", Map.of("content", "hello"));
        choice.put("finish_reason", null);
        String contentChunk = new ObjectMapper().writeValueAsString(Map.of("choices", List.of(choice)));

        List<OllamaChatResponse> firstResults = translator.translate(contentChunk, "LongCat-Flash-Chat");
        List<OllamaChatResponse> doneResults = translator.translate("[DONE]", "LongCat-Flash-Chat");

        assertThat(firstResults).hasSize(1);
        assertThat(firstResults.get(0).isDone()).isFalse();
        assertThat(firstResults.get(0).getMessage().getContent()).isEqualTo("hello");

        assertThat(doneResults).hasSize(1);
        assertThat(doneResults.get(0).isDone()).isTrue();
        assertThat(doneResults.get(0).getDoneReason()).isEqualTo("stop");
        assertThat(doneResults.get(0).getCreatedAt()).isEqualTo(FIXED_TIMESTAMP);
        assertThat(doneResults.get(0).getMessage().getContent()).isEqualTo("hello");
    }

    @Test
    void accumulatesToolCallUntilDoneChunk() throws Exception {
        OllamaStreamTranslator translator = newTranslator();
        String toolCallChunk = new ObjectMapper().writeValueAsString(Map.of("choices",
                List.of(Map.of("delta", Map.of("tool_calls", List.of(Map.of("function", Map.of("name", "read_file", "arguments", "{\"path\":\"README.md\"}")))), "finish_reason", "tool_calls"))));

        List<OllamaChatResponse> toolResults = translator.translate(toolCallChunk, "LongCat-Flash-Chat");
        List<OllamaChatResponse> doneResults = translator.translate("[DONE]", "LongCat-Flash-Chat");

        assertThat(toolResults).isEmpty();
        assertThat(doneResults).hasSize(1);
        assertThat(doneResults.get(0).getDoneReason()).isEqualTo("tool_calls");
        assertThat(doneResults.get(0).getMessage().getContent()).isEmpty();
        assertThat(doneResults.get(0).getMessage().getToolCalls()).hasSize(1);
        assertThat(doneResults.get(0).getMessage().getToolCalls().get(0).getFunction().getName()).isEqualTo("read_file");
        assertThat(doneResults.get(0).getMessage().getToolCalls().get(0).getFunction().getArguments()).containsEntry("path", "README.md");
    }

    @Test
    void fallsBackToReasoningWhenNoContentWasEmitted() throws Exception {
        OllamaStreamTranslator translator = newTranslator();
        String reasoningChunk = new ObjectMapper().writeValueAsString(Map.of("choices", List.of(Map.of("delta", Map.of("reasoning_content", "thinking"), "finish_reason", "stop"))));

        List<OllamaChatResponse> results = translator.translate(reasoningChunk, "LongCat-Flash-Chat");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).isDone()).isFalse();
        assertThat(results.get(0).getMessage().getContent()).isEqualTo("thinking");
        assertThat(results.get(1).isDone()).isTrue();
        assertThat(results.get(1).getDoneReason()).isEqualTo("stop");
        assertThat(results.get(1).getMessage().getContent()).isEqualTo("thinking");
    }

    private OllamaStreamTranslator newTranslator() {
        return new OllamaStreamTranslator(new ObjectMapper(), new OllamaStreamTranslator.Support(this::createChunk, this::createCompletion));
    }

    private OllamaChatResponse createChunk(String modelName, String content) {
        return createResponse(modelName, false, null, content, null);
    }

    private OllamaChatResponse createCompletion(String modelName, String content, List<OllamaChatResponse.ToolCallResult> toolCalls) {
        return createResponse(modelName, true, !toolCalls.isEmpty() ? "tool_calls" : "stop", toolCalls.isEmpty() ? content : "", toolCalls);
    }

    private OllamaChatResponse createResponse(String modelName, boolean done, String doneReason, String content, List<OllamaChatResponse.ToolCallResult> toolCalls) {
        var response = new OllamaChatResponse();
        response.setModel(modelName);
        response.setCreatedAt(FIXED_TIMESTAMP);
        response.setDone(done);
        response.setDoneReason(doneReason);

        var message = new OllamaChatResponse.ResponseMessage();
        message.setRole("assistant");
        message.setContent(content);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            message.setToolCalls(new ArrayList<>(toolCalls));
        }
        response.setMessage(message);
        return response;
    }
}
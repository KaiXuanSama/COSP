package com.kaixuan.copilot_ollama_proxy.provider.mimo.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicStreamEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MimoOllamaStreamTranslatorTests {

    private static final String FIXED_TIMESTAMP = "2026-05-07T12:00:00Z";

    @Test
    void streamsTextDeltaAndFinalMessageStop() {
        MimoOllamaStreamTranslator translator = newTranslator();

        AnthropicStreamEvent textDelta = new AnthropicStreamEvent();
        textDelta.setType("content_block_delta");
        textDelta.setDelta(Map.of("type", "text_delta", "text", "hello"));

        AnthropicStreamEvent messageStop = new AnthropicStreamEvent();
        messageStop.setType("message_stop");

        List<OllamaChatResponse> deltaResults = translator.translate(textDelta, "mimo-v2.5-pro");
        List<OllamaChatResponse> stopResults = translator.translate(messageStop, "mimo-v2.5-pro");

        assertThat(deltaResults).hasSize(1);
        assertThat(deltaResults.get(0).isDone()).isFalse();
        assertThat(deltaResults.get(0).getMessage().getContent()).isEqualTo("hello");

        assertThat(stopResults).hasSize(1);
        assertThat(stopResults.get(0).isDone()).isTrue();
        assertThat(stopResults.get(0).getDoneReason()).isEqualTo("stop");
        assertThat(stopResults.get(0).getMessage().getContent()).isEqualTo("hello");
    }

    @Test
    void accumulatesPartialToolJsonAcrossMultipleDeltas() {
        MimoOllamaStreamTranslator translator = newTranslator();

        AnthropicStreamEvent blockStart = new AnthropicStreamEvent();
        blockStart.setType("content_block_start");
        blockStart.setContentBlock(Map.of("type", "tool_use", "name", "read_file"));

        AnthropicStreamEvent deltaOne = new AnthropicStreamEvent();
        deltaOne.setType("content_block_delta");
        deltaOne.setDelta(Map.of("type", "input_json_delta", "partial_json", "{\"path\":\"README"));

        AnthropicStreamEvent deltaTwo = new AnthropicStreamEvent();
        deltaTwo.setType("content_block_delta");
        deltaTwo.setDelta(Map.of("type", "input_json_delta", "partial_json", ".md\"}"));

        AnthropicStreamEvent blockStop = new AnthropicStreamEvent();
        blockStop.setType("content_block_stop");

        AnthropicStreamEvent messageStop = new AnthropicStreamEvent();
        messageStop.setType("message_stop");

        assertThat(translator.translate(blockStart, "mimo-v2.5-pro")).isEmpty();
        assertThat(translator.translate(deltaOne, "mimo-v2.5-pro")).isEmpty();
        assertThat(translator.translate(deltaTwo, "mimo-v2.5-pro")).isEmpty();
        assertThat(translator.translate(blockStop, "mimo-v2.5-pro")).isEmpty();

        List<OllamaChatResponse> stopResults = translator.translate(messageStop, "mimo-v2.5-pro");

        assertThat(stopResults).hasSize(1);
        assertThat(stopResults.get(0).getDoneReason()).isEqualTo("stop");
        assertThat(stopResults.get(0).getMessage().getContent()).isEmpty();
        assertThat(stopResults.get(0).getMessage().getToolCalls()).hasSize(1);
        assertThat(stopResults.get(0).getMessage().getToolCalls().get(0).getFunction().getName()).isEqualTo("read_file");
        assertThat(stopResults.get(0).getMessage().getToolCalls().get(0).getFunction().getArguments()).containsEntry("path", "README.md");
    }

    private MimoOllamaStreamTranslator newTranslator() {
        return new MimoOllamaStreamTranslator(new ObjectMapper(), new MimoOllamaStreamTranslator.Support(this::createChunk, this::createCompletion));
    }

    private OllamaChatResponse createChunk(String modelName, String content) {
        return createResponse(modelName, false, null, content, null);
    }

    private OllamaChatResponse createCompletion(String modelName, String content, List<OllamaChatResponse.ToolCallResult> toolCalls) {
        return createResponse(modelName, true, "stop", toolCalls.isEmpty() ? content : "", toolCalls);
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
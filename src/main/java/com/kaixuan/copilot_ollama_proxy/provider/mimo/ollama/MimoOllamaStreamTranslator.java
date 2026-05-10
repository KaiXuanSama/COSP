package com.kaixuan.copilot_ollama_proxy.provider.mimo.ollama;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class MimoOllamaStreamTranslator {

    @FunctionalInterface
    interface AssistantChunkFactory {
        OllamaChatResponse create(String modelName, String content);
    }

    @FunctionalInterface
    interface AssistantCompletionFactory {
        OllamaChatResponse create(String modelName, String content, List<OllamaChatResponse.ToolCallResult> toolCalls);
    }

    record Support(AssistantChunkFactory assistantChunkFactory, AssistantCompletionFactory assistantCompletionFactory) {
        Support {
            Objects.requireNonNull(assistantChunkFactory, "assistantChunkFactory");
            Objects.requireNonNull(assistantCompletionFactory, "assistantCompletionFactory");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MimoOllamaStreamTranslator.class);

    private final ObjectMapper objectMapper;
    private final Support support;
    private final StringBuilder textBuffer = new StringBuilder();
    private final StringBuilder reasoningBuffer = new StringBuilder();
    private final List<OllamaChatResponse.ToolCallResult> toolCalls = new ArrayList<>();

    private boolean contentEmitted;
    private String currentToolName;
    private Map<String, Object> currentToolInput;

    MimoOllamaStreamTranslator(ObjectMapper objectMapper, Support support) {
        this.objectMapper = objectMapper;
        this.support = support;
    }

    @SuppressWarnings("unchecked")
    List<OllamaChatResponse> translate(String chunk, String modelName) {
        List<OllamaChatResponse> results = new ArrayList<>();

        if ("[DONE]".equals(chunk)) {
            results.add(support.assistantCompletionFactory().create(modelName, textBuffer.toString(), List.copyOf(toolCalls)));
            return results;
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(chunk, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
            if (choices == null || choices.isEmpty()) {
                return results;
            }

            Map<String, Object> choice = choices.get(0);
            Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
            String finishReason = (String) choice.get("finish_reason");

            if (delta != null) {
                Object contentObj = delta.get("content");
                if (contentObj instanceof String content && !content.isEmpty()) {
                    textBuffer.append(content);
                    contentEmitted = true;
                    results.add(support.assistantChunkFactory().create(modelName, content));
                }

                Object reasoningObj = delta.get("reasoning_content");
                if (reasoningObj instanceof String reasoning && !reasoning.isEmpty()) {
                    reasoningBuffer.append(reasoning);
                }

                List<Map<String, Object>> deltaToolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
                if (deltaToolCalls != null) {
                    for (Map<String, Object> toolCall : deltaToolCalls) {
                        Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
                        if (function != null) {
                            if (function.containsKey("name") && function.get("name") != null) {
                                currentToolName = (String) function.get("name");
                                currentToolInput = new LinkedHashMap<>();
                            }
                            Object argsObj = function.get("arguments");
                            if (argsObj instanceof String args && !args.isEmpty() && currentToolInput != null) {
                                try {
                                    Map<String, Object> partial = objectMapper.readValue(args, new TypeReference<>() {
                                    });
                                    currentToolInput.putAll(partial);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }
                }
            }

            if ("tool_calls".equals(finishReason) && currentToolName != null) {
                var toolCall = new OllamaChatResponse.ToolCallResult();
                var function = new OllamaChatResponse.ToolCallFunction();
                function.setName(currentToolName);
                function.setArguments(currentToolInput != null ? currentToolInput : Map.of());
                toolCall.setFunction(function);
                toolCalls.add(toolCall);
                currentToolName = null;
                currentToolInput = null;
            }

            if ("stop".equals(finishReason) && !contentEmitted && !reasoningBuffer.isEmpty()) {
                String fallbackContent = reasoningBuffer.toString();
                results.add(support.assistantChunkFactory().create(modelName, fallbackContent));
                results.add(support.assistantCompletionFactory().create(modelName, fallbackContent, List.copyOf(toolCalls)));
            }
        } catch (Exception e) {
            log.warn("MiMo SSE parse failed: {}", chunk, e);
        }

        return results;
    }
}
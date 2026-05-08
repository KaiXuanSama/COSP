package com.kaixuan.copilot_ollama_proxy.provider.sensenova.ollama;

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

/**
 * SenseNova 模型的 Ollama 流式响应翻译器。
 * <p>
 * 这个类专门负责流式状态机：
 * 它接收 SenseNova OpenAI SSE 的 data chunk，维护文本缓冲、思考缓冲和工具调用缓冲，
 * 再把这些状态翻译成 Ollama 所需的增量 chunk 与最终完成包。
 */
final class SenseNovaOllamaStreamTranslator {

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

    private static final Logger log = LoggerFactory.getLogger(SenseNovaOllamaStreamTranslator.class);

    private final ObjectMapper objectMapper;
    private final Support support;
    private final StringBuilder textBuffer = new StringBuilder();
    private final StringBuilder reasoningBuffer = new StringBuilder();
    private final List<OllamaChatResponse.ToolCallResult> toolCalls = new ArrayList<>();

    private boolean contentEmitted;
    private String currentToolName;
    private Map<String, Object> currentToolInput;

    SenseNovaOllamaStreamTranslator(ObjectMapper objectMapper, Support support) {
        this.objectMapper = objectMapper;
        this.support = support;
    }

    /**
     * 翻译 SenseNova 返回的单个 SSE data chunk。
     *
     * @param chunk     SenseNova SSE 中 data 字段的原始内容
     * @param modelName 当前请求的模型名称
     * @return 由这个 chunk 导出的 0 到多个 Ollama 响应对象
     */
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
                // 处理内容的增量更新
                Object contentObj = delta.get("content");
                if (contentObj instanceof String content && !content.isEmpty()) {
                    textBuffer.append(content);
                    contentEmitted = true;
                    results.add(support.assistantChunkFactory().create(modelName, content));
                }

                // 处理思考内容的增量更新
                Object reasoningObj = delta.get("reasoning_content");
                if (reasoningObj instanceof String reasoning && !reasoning.isEmpty()) {
                    reasoningBuffer.append(reasoning);
                }

                // 处理工具调用的增量更新
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
                                    log.debug("工具参数 JSON 片段累积: {}", args);
                                }
                            }
                        }
                    }
                }
            }

            // 当工具调用完成时
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

            // 当完成原因是 stop 且没有输出过内容但有思考内容时，回退使用思考内容
            if ("stop".equals(finishReason) && !contentEmitted && !reasoningBuffer.isEmpty()) {
                log.warn("模型未输出正文，回退使用思考内容作为回复 (长度: {})", reasoningBuffer.length());
                String fallbackContent = reasoningBuffer.toString();
                results.add(support.assistantChunkFactory().create(modelName, fallbackContent));
                results.add(support.assistantCompletionFactory().create(modelName, fallbackContent, List.copyOf(toolCalls)));
            }
        } catch (Exception e) {
            log.warn("SenseNova SSE 解析失败: {}", chunk, e);
        }

        return results;
    }
}

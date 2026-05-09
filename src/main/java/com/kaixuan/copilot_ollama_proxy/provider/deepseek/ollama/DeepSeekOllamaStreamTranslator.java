package com.kaixuan.copilot_ollama_proxy.provider.deepseek.ollama;

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
 * DeepSeek 模型的 Ollama 流式响应翻译器。
 * <p>
 * 负责流式状态机：接收 DeepSeek OpenAI SSE 的 data chunk，
 * 维护文本缓冲、思考缓冲和工具调用缓冲，
 * 再把这些状态翻译成 Ollama 所需的增量 chunk 与最终完成包。
 * <p>
 * DeepSeek 特有处理：
 * - reasoning_content 增量 → Ollama thinking 字段
 * - 思考模式下的 content 可能为空，需回退处理
 */
final class DeepSeekOllamaStreamTranslator {

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

    private static final Logger log = LoggerFactory.getLogger(DeepSeekOllamaStreamTranslator.class);

    private final ObjectMapper objectMapper;
    private final Support support;
    private final StringBuilder textBuffer = new StringBuilder();
    private final StringBuilder reasoningBuffer = new StringBuilder();
    private final List<OllamaChatResponse.ToolCallResult> toolCalls = new ArrayList<>();

    private boolean contentEmitted;
    private String currentToolName;
    private Map<String, Object> currentToolInput;

    DeepSeekOllamaStreamTranslator(ObjectMapper objectMapper, Support support) {
        this.objectMapper = objectMapper;
        this.support = support;
    }

    /**
     * 翻译 DeepSeek 返回的单个 SSE data chunk。
     * <p>
     * 关键分支：
     * 1. content 增量 → Ollama 中间 chunk
     * 2. reasoning_content 增量 → 累积到 reasoningBuffer
     * 3. tool_calls finish_reason → 收束工具调用
     * 4. 仅输出 reasoning 未输出 content → 回退用 reasoning 作为回复
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
                // 处理 content 增量
                Object contentObj = delta.get("content");
                if (contentObj instanceof String content && !content.isEmpty()) {
                    textBuffer.append(content);
                    contentEmitted = true;
                    results.add(support.assistantChunkFactory().create(modelName, content));
                }

                // 处理 reasoning_content 增量（DeepSeek 特有）
                Object reasoningObj = delta.get("reasoning_content");
                if (reasoningObj instanceof String reasoning && !reasoning.isEmpty()) {
                    reasoningBuffer.append(reasoning);
                }

                // 处理工具调用增量
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

            // 工具调用完成时收束
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

            // 仅输出 reasoning 未输出 content 时回退
            if ("stop".equals(finishReason) && !contentEmitted && !reasoningBuffer.isEmpty()) {
                log.warn("DeepSeek 模型未输出正文，回退使用思考内容作为回复 (长度: {})", reasoningBuffer.length());
                String fallbackContent = reasoningBuffer.toString();
                results.add(support.assistantChunkFactory().create(modelName, fallbackContent));
                results.add(support.assistantCompletionFactory().create(modelName, fallbackContent, List.copyOf(toolCalls)));
            }
        } catch (Exception e) {
            log.warn("DeepSeek SSE 解析失败: {}", chunk, e);
        }

        return results;
    }
}
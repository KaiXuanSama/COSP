package com.kaixuan.copilot_ollama_proxy.provider.ollama;

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
 * 通用的 Ollama 流式响应翻译器。
 * <p>
 * 接收 OpenAI SSE 的 data chunk，维护文本缓冲、思考缓冲和工具调用缓冲，
 * 再把这些状态翻译成 Ollama 所需的增量 chunk 与最终完成包。
 * <p>
 * 所有 provider 的 OpenAI SSE 格式一致，因此此类可通用。
 */
public class OllamaStreamTranslator {

    @FunctionalInterface
    public interface AssistantChunkFactory {
        OllamaChatResponse create(String modelName, String content);
    }

    @FunctionalInterface
    public interface AssistantCompletionFactory {
        OllamaChatResponse create(String modelName, String content, List<OllamaChatResponse.ToolCallResult> toolCalls);
    }

    public record Support(AssistantChunkFactory assistantChunkFactory, AssistantCompletionFactory assistantCompletionFactory) {
        public Support {
            Objects.requireNonNull(assistantChunkFactory, "assistantChunkFactory");
            Objects.requireNonNull(assistantCompletionFactory, "assistantCompletionFactory");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(OllamaStreamTranslator.class);

    private final ObjectMapper objectMapper;
    private final Support support;
    private final StringBuilder textBuffer = new StringBuilder();
    private final StringBuilder reasoningBuffer = new StringBuilder();
    private final List<OllamaChatResponse.ToolCallResult> toolCalls = new ArrayList<>();

    private boolean contentEmitted;
    private String currentToolName;
    private Map<String, Object> currentToolInput;

    public OllamaStreamTranslator(ObjectMapper objectMapper, Support support) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.support = Objects.requireNonNull(support, "support");
    }

    /**
     * 翻译单个 SSE data chunk。
     *
     * @param chunk     SSE data 字段的原始内容
     * @param modelName 当前请求的模型名称
     * @return 由这个 chunk 导出的 0 到多个 Ollama 响应对象
     */
    @SuppressWarnings("unchecked")
    public List<OllamaChatResponse> translate(String chunk, String modelName) {
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

                // 处理 reasoning_content 增量
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

            // tool_calls finish_reason 收束
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

            // 仅输出 reasoning 未输出 content → 回退用 reasoning 作为回复
            if ("stop".equals(finishReason) && !contentEmitted && !reasoningBuffer.isEmpty()) {
                String fallbackContent = reasoningBuffer.toString();
                results.add(support.assistantChunkFactory().create(modelName, fallbackContent));
                results.add(support.assistantCompletionFactory().create(modelName, fallbackContent, List.of()));
            }

        } catch (Exception e) {
            log.warn("SSE chunk 解析失败: {}", e.getMessage());
        }

        return results;
    }
}
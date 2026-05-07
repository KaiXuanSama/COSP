package com.kaixuan.copilot_ollama_proxy.provider.longcat.ollama;

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
 * LongCat 模型的 Ollama 流式响应翻译器。
 * <p>
 * 这个类专门负责流式状态机：
 * 它接收 LongCat OpenAI SSE 的 data chunk，维护文本缓冲、思考缓冲和工具调用缓冲，
 * 再把这些状态翻译成 Ollama 所需的增量 chunk 与最终完成包。
 */
final class LongCatOllamaStreamTranslator {

    /** 用于创建普通增量 chunk 的工厂。 */
    @FunctionalInterface
    interface AssistantChunkFactory {
        OllamaChatResponse create(String modelName, String content);
    }

    /** 用于创建最终完成包的工厂。 */
    @FunctionalInterface
    interface AssistantCompletionFactory {
        OllamaChatResponse create(String modelName, String content, List<OllamaChatResponse.ToolCallResult> toolCalls);
    }

    /**
     * 翻译器依赖的输出工厂集合。
     * <p>
     * 这样 translator 只负责状态累计和分支判断，不直接依赖具体的 Ollama 响应构造实现。
     */
    record Support(AssistantChunkFactory assistantChunkFactory, AssistantCompletionFactory assistantCompletionFactory) {
        Support {
            Objects.requireNonNull(assistantChunkFactory, "assistantChunkFactory");
            Objects.requireNonNull(assistantCompletionFactory, "assistantCompletionFactory");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(LongCatOllamaStreamTranslator.class);

    private final ObjectMapper objectMapper;
    private final Support support;
    private final StringBuilder textBuffer = new StringBuilder();
    private final StringBuilder reasoningBuffer = new StringBuilder();
    private final List<OllamaChatResponse.ToolCallResult> toolCalls = new ArrayList<>();

    private boolean contentEmitted;
    private String currentToolName;
    private Map<String, Object> currentToolInput;

    /**
     * @param objectMapper 用于解析增量工具参数 JSON 片段
     * @param support 用于输出普通 chunk 和完成包的工厂集合
     */
    LongCatOllamaStreamTranslator(ObjectMapper objectMapper, Support support) {
        this.objectMapper = objectMapper;
        this.support = support;
    }

    /**
     * 翻译 LongCat 返回的单个 SSE data chunk。
     * <p>
     * 这里有三个关键分支：
     * 1. 普通 content 增量直接转成 Ollama 中间 chunk。
     * 2. tool_calls finish_reason 会把当前累计的工具调用收束到最终完成包里。
     * 3. 若模型只输出 reasoning_content 而未输出正文，则回退用 reasoning 作为最终可见回复。
     *
     * @param chunk LongCat SSE 中 data 字段的原始内容
     * @param modelName 当前请求的模型名称
     * @return 由这个 chunk 导出的 0 到多个 Ollama 响应对象
     */
    @SuppressWarnings("unchecked")
    List<OllamaChatResponse> translate(String chunk, String modelName) {
        List<OllamaChatResponse> results = new ArrayList<>();

        // 当收到特殊的 [DONE] 片段时，表示流式响应结束，输出最终的完成响应，包含累积的内容和工具调用结果。
        if ("[DONE]".equals(chunk)) {
            results.add(support.assistantCompletionFactory().create(modelName, textBuffer.toString(), List.copyOf(toolCalls)));
            return results;
        }

        try {
            // 解析 SSE 片段中的 JSON 内容，提取增量更新的文本、思考内容，以及工具调用信息，并根据完成原因进行相应处理。
            Map<String, Object> parsed = objectMapper.readValue(chunk, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
            if (choices == null || choices.isEmpty()) {
                return results;
            }

            // 目前 LongCat 的 SSE 每次只会包含一个 choice，因此直接取第一个进行处理。
            Map<String, Object> choice = choices.get(0);
            Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
            String finishReason = (String) choice.get("finish_reason");

            if (delta != null) {
                // 处理内容的增量更新，累积内容以便在完成时一起输出。
                Object contentObj = delta.get("content");
                if (contentObj instanceof String content && !content.isEmpty()) {
                    textBuffer.append(content);
                    contentEmitted = true;
                    results.add(support.assistantChunkFactory().create(modelName, content));
                }

                // 处理思考内容的增量更新，累积思考内容以便在模型未输出正文时回退使用。
                Object reasoningObj = delta.get("reasoning_content");
                if (reasoningObj instanceof String reasoning && !reasoning.isEmpty()) {
                    reasoningBuffer.append(reasoning);
                }

                // 处理工具调用的增量更新，累积工具调用的参数直到完成时一起输出。
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

            // 当工具调用完成时，将当前累积的工具调用结果添加到列表中，并重置当前工具状态。
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

            // 当完成原因是 stop 且没有输出过内容但有思考内容时，回退使用思考内容作为回复，确保最终用户能看到模型的思考过程。
            if ("stop".equals(finishReason) && !contentEmitted && !reasoningBuffer.isEmpty()) {
                log.warn("模型未输出正文，回退使用思考内容作为回复 (长度: {})", reasoningBuffer.length());
                String fallbackContent = reasoningBuffer.toString();
                results.add(support.assistantChunkFactory().create(modelName, fallbackContent));
                results.add(support.assistantCompletionFactory().create(modelName, fallbackContent, List.copyOf(toolCalls)));
            }
        } catch (Exception e) {
            log.warn("LongCat SSE 解析失败: {}", chunk, e);
        }

        return results;
    }
}
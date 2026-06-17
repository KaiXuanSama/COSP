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
 * 通用的 Ollama 流式响应翻译器（线程安全版本）。
 * <p>
 * 翻译器本身是无状态的（只持有 ObjectMapper 和 Support 配置），
 * 所有流式累积状态封装在 {@link TranslateSession} 中。
 * <p>
 * 使用方式：
 * 1. 调用 {@link #newSession()} 创建一个请求级别的会话
 * 2. 对该会话调用 {@link #translate(TranslateSession, String, String)} 翻译每个 chunk
 * 3. 每个并发请求必须使用独立的 session，避免状态串扰
 * <p>
 * 旧的 {@link #translate(String, String)} 方法已标记为过时，不应在并发场景使用。
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

    /**
     * 请求级别的翻译会话，封装单次流式请求的所有累积状态。
     * 每个并发请求必须持有独立的 TranslateSession 实例。
     */
    public static class TranslateSession {
        private final StringBuilder textBuffer = new StringBuilder();
        private final StringBuilder reasoningBuffer = new StringBuilder();
        private final List<OllamaChatResponse.ToolCallResult> toolCalls = new ArrayList<>();
        private boolean contentEmitted;
        private String currentToolName;
        private Map<String, Object> currentToolInput;
    }

    private static final Logger log = LoggerFactory.getLogger(OllamaStreamTranslator.class);

    private final ObjectMapper objectMapper;
    private final Support support;

    public OllamaStreamTranslator(ObjectMapper objectMapper, Support support) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.support = Objects.requireNonNull(support, "support");
    }

    /**
     * 创建一个新的翻译会话，用于单次流式请求。
     * <p>
     * 每个并发请求必须调用此方法获取独立会话，避免多请求间的状态串扰。
     */
    public TranslateSession newSession() {
        return new TranslateSession();
    }

    /**
     * 翻译单个 SSE data chunk（线程安全版本，推荐使用）。
     *
     * @param session   当前请求的翻译会话，通过 {@link #newSession()} 获取
     * @param chunk     SSE data 字段的原始内容
     * @param modelName 当前请求的模型名称
     * @return 由这个 chunk 导出的 0 到多个 Ollama 响应对象
     */
    @SuppressWarnings("unchecked")
    public List<OllamaChatResponse> translate(TranslateSession session, String chunk, String modelName) {
        List<OllamaChatResponse> results = new ArrayList<>();

        if ("[DONE]".equals(chunk)) {
            results.add(support.assistantCompletionFactory().create(modelName, session.textBuffer.toString(), List.copyOf(session.toolCalls)));
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
                    session.textBuffer.append(content);
                    session.contentEmitted = true;
                    results.add(support.assistantChunkFactory().create(modelName, content));
                }

                // 处理 reasoning_content 增量
                Object reasoningObj = delta.get("reasoning_content");
                if (reasoningObj instanceof String reasoning && !reasoning.isEmpty()) {
                    session.reasoningBuffer.append(reasoning);
                }

                // 处理工具调用增量
                List<Map<String, Object>> deltaToolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
                if (deltaToolCalls != null) {
                    for (Map<String, Object> toolCall : deltaToolCalls) {
                        Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
                        if (function != null) {
                            if (function.containsKey("name") && function.get("name") != null) {
                                session.currentToolName = (String) function.get("name");
                                session.currentToolInput = new LinkedHashMap<>();
                            }
                            Object argsObj = function.get("arguments");
                            if (argsObj instanceof String args && !args.isEmpty() && session.currentToolInput != null) {
                                try {
                                    Map<String, Object> partial = objectMapper.readValue(args, new TypeReference<>() {
                                    });
                                    session.currentToolInput.putAll(partial);
                                } catch (Exception ignored) {
                                    log.debug("工具参数 JSON 片段累积: {}", args);
                                }
                            }
                        }
                    }
                }
            }

            // tool_calls finish_reason 收束
            if ("tool_calls".equals(finishReason) && session.currentToolName != null) {
                var toolCall = new OllamaChatResponse.ToolCallResult();
                var function = new OllamaChatResponse.ToolCallFunction();
                function.setName(session.currentToolName);
                function.setArguments(session.currentToolInput != null ? session.currentToolInput : Map.of());
                toolCall.setFunction(function);
                session.toolCalls.add(toolCall);
                session.currentToolName = null;
                session.currentToolInput = null;
            }

            // 仅输出 reasoning 未输出 content → 回退用 reasoning 作为回复
            if ("stop".equals(finishReason) && !session.contentEmitted && !session.reasoningBuffer.isEmpty()) {
                String fallbackContent = session.reasoningBuffer.toString();
                results.add(support.assistantChunkFactory().create(modelName, fallbackContent));
                results.add(support.assistantCompletionFactory().create(modelName, fallbackContent, List.of()));
            }

        } catch (Exception e) {
            log.warn("SSE chunk 解析失败: {}", e.getMessage());
        }

        return results;
    }

    /**
     * @deprecated 使用 {@link #newSession()} + {@link #translate(TranslateSession, String, String)} 代替。
     *             此方法在并发场景下会导致不同请求间的流式状态串扰。
     */
    @Deprecated
    public List<OllamaChatResponse> translate(String chunk, String modelName) {
        // 退化行为：每次调用创建临时 session，仅用于单次非并发场景的向后兼容
        // 注意：这会导致累积状态在 chunk 间丢失，仅适合单 chunk 测试等边缘场景
        return translate(newSession(), chunk, modelName);
    }

    /**
     * 创建一个新的翻译器实例（含新的 ObjectMapper 和 Support）。
     * <p>
     * 与 {@link #newSession()} 不同，此方法创建完全独立的翻译器，
     * 适用于需要不同配置的场景。
     */
    public OllamaStreamTranslator newInstance() {
        return new OllamaStreamTranslator(objectMapper, support);
    }
}
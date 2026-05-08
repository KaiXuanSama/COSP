package com.kaixuan.copilot_ollama_proxy.provider.mimo.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.openai.UpstreamChatService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Anthropic API 实现 —— 将 OpenAI 格式请求转换为 Anthropic Messages API 调用，
 * 再将 Anthropic 响应转换回 OpenAI 格式。
 */
@Service
public class MimoAnthropicChatService implements UpstreamChatService {

    private static final Logger log = LoggerFactory.getLogger(MimoAnthropicChatService.class);

    private final MimoAnthropicClient anthropicClient;
    private final RuntimeProviderCatalog runtimeProviderCatalog;
    private final ObjectMapper objectMapper;

    public MimoAnthropicChatService(MimoAnthropicClient anthropicClient, RuntimeProviderCatalog runtimeProviderCatalog, ObjectMapper objectMapper) {
        this.anthropicClient = anthropicClient;
        this.runtimeProviderCatalog = runtimeProviderCatalog;
        this.objectMapper = objectMapper;
    }

    // ========== 路由支持 ==========

    @Override
    public String getProviderKey() {
        return "mimo";
    }

    @Override
    public String getUpstreamApiFormat() {
        return "anthropic";
    }

    @Override
    public boolean supportsModel(String modelName) {
        ProviderRuntimeConfiguration config = runtimeProviderCatalog.getActiveProvider("mimo");
        return config != null && "anthropic".equals(config.apiFormat()) && config.supportsModel(modelName);
    }

    // ========== UpstreamChatService 接口实现 ==========

    @Override
    public Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model) {
        Map<String, Object> anthropicBody = convertToAnthropicRequest(openAiRequest);
        log.info("OpenAI -> Anthropic 转换完成，模型: {}, 流式: false", model);
        // log.debug("Anthropic 请求体: {}", toLogJson(anthropicBody));

        return anthropicClient.sendMessage(anthropicBody).map(anthropicResp -> convertAnthropicToOpenAi(anthropicResp, model));
    }

    @Override
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
        Map<String, Object> anthropicBody = convertToAnthropicRequest(openAiRequest);
        anthropicBody.put("stream", true);
        log.info("OpenAI -> Anthropic 转换完成，模型: {}, 流式: true", model);
        // log.debug("Anthropic 请求体: {}", toLogJson(anthropicBody));

        AtomicReference<String> messageId = new AtomicReference<>("chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        AtomicReference<Boolean> finishSent = new AtomicReference<>(false);
        AtomicReference<StringBuilder> textContentBuffer = new AtomicReference<>(new StringBuilder());
        AtomicReference<StringBuilder> thinkingContentBuffer = new AtomicReference<>(new StringBuilder());
        AtomicReference<StringBuilder> thinkingSignature = new AtomicReference<>(new StringBuilder());
        AtomicReference<Integer> thinkingBlockIndex = new AtomicReference<>(-1);
        AtomicReference<String> stopReason = new AtomicReference<>(null);
        AtomicReference<Integer> outputTokens = new AtomicReference<>(0);

        return anthropicClient.streamMessages(anthropicBody).flatMap(event -> {
            log.debug("Anthropic 事件 [{}]: {}", event.getType(), toLogJson(event));
            List<String> chunks = convertSseEventToOpenAi(event, messageId, finishSent, textContentBuffer, thinkingContentBuffer, thinkingSignature, thinkingBlockIndex, stopReason, outputTokens,
                    model);
            for (String chunk : chunks) {
                log.debug("OpenAI  chunk: {}", chunk);
            }
            return Flux.fromIterable(chunks);
        }).concatWith(Flux.defer(() -> {
            return Flux.just("[DONE]");
        }));
    }

    // ========== 请求转换：OpenAI → Anthropic ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertToAnthropicRequest(Map<String, Object> req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.get("model"));
        Object maxTokens = req.get("max_tokens");
        body.put("max_tokens", maxTokens != null ? maxTokens : 65536);

        List<Map<String, Object>> systemParts = new ArrayList<>();
        List<Map<String, Object>> messages = new ArrayList<>();

        List<Map<String, Object>> reqMessages = (List<Map<String, Object>>) req.get("messages");
        if (reqMessages != null) {
            for (var msg : reqMessages) {
                String role = (String) msg.get("role");

                if ("system".equals(role)) {
                    systemParts.add(Map.of("type", "text", "text", extractTextContent(msg.get("content"))));
                } else if ("tool".equals(role)) {
                    Map<String, Object> toolResult = new LinkedHashMap<>();
                    toolResult.put("type", "tool_result");
                    if (msg.get("tool_call_id") != null) {
                        toolResult.put("tool_use_id", msg.get("tool_call_id"));
                    }
                    toolResult.put("content", extractTextContent(msg.get("content")));
                    messages.add(Map.of("role", "user", "content", List.of(toolResult)));
                } else if ("assistant".equals(role) && msg.get("tool_calls") != null) {
                    List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) msg.get("tool_calls");
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        List<Object> contentBlocks = new ArrayList<>();
                        String text = extractTextContent(msg.get("content"));
                        if (text != null && !text.isEmpty()) {
                            contentBlocks.add(Map.of("type", "text", "text", text));
                        }
                        for (var tc : toolCalls) {
                            Map<String, Object> toolUse = new LinkedHashMap<>();
                            toolUse.put("type", "tool_use");
                            toolUse.put("id", tc.get("id") != null ? tc.get("id") : "toolu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                            Map<String, Object> func = (Map<String, Object>) tc.get("function");
                            toolUse.put("name", func.get("name"));
                            toolUse.put("input", parseJsonArguments((String) func.get("arguments")));
                            contentBlocks.add(toolUse);
                        }
                        messages.add(Map.of("role", "assistant", "content", contentBlocks));
                    }
                } else {
                    String text = extractTextContent(msg.get("content"));
                    messages.add(Map.of("role", role, "content", text != null ? text : ""));
                }
            }
        }

        if (!systemParts.isEmpty()) {
            body.put("system", systemParts);
        }
        body.put("messages", messages);

        List<Map<String, Object>> tools = (List<Map<String, Object>>) req.get("tools");
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> anthropicTools = new ArrayList<>();
            for (var tool : tools) {
                Map<String, Object> func = (Map<String, Object>) tool.get("function");
                if (func != null) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("name", func.get("name"));
                    if (func.get("description") != null) {
                        t.put("description", func.get("description"));
                    }
                    if (func.get("parameters") != null) {
                        t.put("input_schema", func.get("parameters"));
                    }
                    anthropicTools.add(t);
                }
            }
            body.put("tools", anthropicTools);
        }

        return body;
    }

    // ========== 非流式：Anthropic 响应 → OpenAI 响应 ==========

    private String convertAnthropicToOpenAi(String anthropicJson, String model) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> anthropic = objectMapper.readValue(anthropicJson, Map.class);

            Map<String, Object> openai = new LinkedHashMap<>();
            openai.put("id", "chatcmpl-" + anthropic.get("id"));
            openai.put("object", "chat.completion");
            openai.put("created", System.currentTimeMillis() / 1000);
            openai.put("model", model);

            StringBuilder textContent = new StringBuilder();
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) anthropic.get("content");
            if (content != null) {
                for (Map<String, Object> block : content) {
                    String type = (String) block.get("type");
                    if ("text".equals(type)) {
                        textContent.append(block.get("text"));
                    } else if ("thinking".equals(type)) {
                        // thinking 块在 OpenAI 格式中无对应，直接丢弃
                    } else if ("tool_use".equals(type)) {
                        Map<String, Object> tc = new LinkedHashMap<>();
                        tc.put("id", block.get("id"));
                        tc.put("type", "function");
                        tc.put("function", Map.of("name", block.get("name"), "arguments", objectMapper.writeValueAsString(block.get("input"))));
                        toolCalls.add(tc);
                    }
                }
            }

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            message.put("content", textContent.length() > 0 ? textContent.toString() : null);
            if (!toolCalls.isEmpty()) {
                message.put("tool_calls", toolCalls);
            }

            String stopReason = (String) anthropic.get("stop_reason");
            String finishReason = "tool_use".equals(stopReason) ? "tool_calls" : "stop";

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", finishReason);

            openai.put("choices", List.of(choice));

            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) anthropic.get("usage");
            if (usage != null) {
                openai.put("usage", Map.of("prompt_tokens", usage.getOrDefault("input_tokens", 0), "completion_tokens", usage.getOrDefault("output_tokens", 0), "total_tokens",
                        ((Number) usage.getOrDefault("input_tokens", 0)).intValue() + ((Number) usage.getOrDefault("output_tokens", 0)).intValue()));
            }

            return objectMapper.writeValueAsString(openai);
        } catch (Exception e) {
            log.error("Anthropic → OpenAI 转换失败", e);
            return "{\"error\":\"conversion_failed\"}";
        }
    }

    // ========== 流式：Anthropic SSE → OpenAI SSE ==========

    private List<String> convertSseEventToOpenAi(AnthropicStreamEvent event, AtomicReference<String> messageId, AtomicReference<Boolean> finishSent, AtomicReference<StringBuilder> textContentBuffer,
            AtomicReference<StringBuilder> thinkingContentBuffer, AtomicReference<StringBuilder> thinkingSignature, AtomicReference<Integer> thinkingBlockIndex, AtomicReference<String> stopReason,
            AtomicReference<Integer> outputTokens, String model) {
        try {
            List<String> result = new ArrayList<>();

            switch (event.getType()) {
            case "message_start" -> {
                Map<String, Object> message = event.getMessage();
                if (message != null && message.get("id") != null) {
                    messageId.set("chatcmpl-" + message.get("id"));
                }
                result.add(buildOpenAiChunk(messageId.get(), model, "assistant", null, null));
            }
            case "content_block_start" -> {
                Map<String, Object> block = event.getContentBlock();
                if (block != null) {
                    String blockType = (String) block.get("type");
                    if ("thinking".equals(blockType)) {
                        int idx = event.getIndex() != null ? event.getIndex() : 0;
                        thinkingBlockIndex.set(idx);
                        thinkingSignature.set(new StringBuilder());
                        result.add(buildOpenAiReasoningChunk(messageId.get(), model, "thinking_" + idx, null));
                    } else if ("redacted_thinking".equals(blockType)) {
                        String data = (String) block.get("data");
                        if (data != null) {
                            result.add(buildOpenAiReasoningChunk(messageId.get(), model, data, null));
                        }
                    } else if ("tool_use".equals(blockType)) {
                        String toolId = (String) block.get("id");
                        String toolName = (String) block.get("name");
                        result.add(buildOpenAiToolStartChunk(messageId.get(), model, toolId, toolName));
                    }
                }
            }
            case "content_block_delta" -> {
                Map<String, Object> delta = event.getDelta();
                if (delta != null) {
                    String deltaType = (String) delta.get("type");
                    if ("text_delta".equals(deltaType)) {
                        String text = (String) delta.get("text");
                        if (text != null) {
                            textContentBuffer.get().append(text);
                            result.add(buildOpenAiChunk(messageId.get(), model, null, text, null));
                        }
                    } else if ("thinking_delta".equals(deltaType)) {
                        String thinking = (String) delta.get("thinking");
                        if (thinking != null) {
                            thinkingContentBuffer.get().append(thinking);
                            result.add(buildOpenAiReasoningChunk(messageId.get(), model, null, thinking));
                        }
                    } else if ("signature_delta".equals(deltaType)) {
                        String signature = (String) delta.get("signature");
                        if (signature != null)
                            thinkingSignature.get().append(signature);
                    } else if ("input_json_delta".equals(deltaType)) {
                        String json = (String) delta.get("partial_json");
                        if (json != null)
                            result.add(buildOpenAiChunk(messageId.get(), model, null, null, json));
                    }
                }
            }
            case "content_block_stop" -> {
                if (thinkingBlockIndex.get() >= 0) {
                    String sig = thinkingSignature.get().toString();
                    if (!sig.isEmpty()) {
                        result.add(buildOpenAiReasoningChunk(messageId.get(), model, sig, null));
                    }
                    thinkingBlockIndex.set(-1);
                    thinkingSignature.set(new StringBuilder());
                }
            }
            case "message_delta" -> {
                Map<String, Object> delta = event.getDelta();
                if (delta != null) {
                    String reason = (String) delta.get("stop_reason");
                    if (reason != null)
                        stopReason.set(reason);
                    if ("tool_use".equals(reason)) {
                        result.add(buildOpenAiFinishChunk(messageId.get(), model, "tool_calls"));
                        finishSent.set(true);
                    }
                }
                Map<String, Object> usage = event.getUsage();
                if (usage != null && usage.get("output_tokens") instanceof Number n) {
                    outputTokens.set(n.intValue());
                }
            }
            case "message_stop" -> {
                if (!finishSent.get()) {
                    if (textContentBuffer.get().isEmpty()) {
                        String fallback = buildThinkingFallback(thinkingContentBuffer, stopReason.get(), outputTokens.get());
                        result.add(buildOpenAiChunk(messageId.get(), model, null, fallback, null));
                    }
                    result.add(buildOpenAiFinishChunk(messageId.get(), model, "stop"));
                }
            }
            }
            return result;
        } catch (Exception e) {
            log.warn("SSE 事件解析失败: {}", event.getType(), e);
            return List.of();
        }
    }

    // ========== OpenAI Chunk 构建方法 ==========

    private String buildOpenAiChunk(String id, String model, String role, String text, String toolJson) {
        try {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);

            Map<String, Object> delta = new LinkedHashMap<>();
            if (role != null)
                delta.put("role", role);
            if (text != null)
                delta.put("content", text);
            if (toolJson != null) {
                delta.put("tool_calls", List.of(Map.of("index", 0, "function", Map.of("arguments", toolJson))));
            }

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", null);

            chunk.put("choices", List.of(choice));
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildOpenAiToolStartChunk(String id, String model, String toolId, String toolName) {
        try {
            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("index", 0);
            toolCall.put("id", toolId);
            toolCall.put("type", "function");
            toolCall.put("function", Map.of("name", toolName, "arguments", ""));

            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("tool_calls", List.of(toolCall));

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", null);

            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);
            chunk.put("choices", List.of(choice));

            return objectMapper.writeValueAsString(chunk);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildOpenAiReasoningChunk(String id, String model, String opaque, String text) {
        try {
            Map<String, Object> delta = new LinkedHashMap<>();
            if (opaque != null)
                delta.put("reasoning_opaque", opaque);
            if (text != null)
                delta.put("reasoning_text", text);

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", null);

            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);
            chunk.put("choices", List.of(choice));

            return objectMapper.writeValueAsString(chunk);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildOpenAiFinishChunk(String id, String model, String finishReason) {
        try {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", Map.of());
            choice.put("finish_reason", finishReason);

            chunk.put("choices", List.of(choice));
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildThinkingFallback(AtomicReference<StringBuilder> thinkingContentBuffer, String stopReason, int outputTokens) {
        String thinking = thinkingContentBuffer.get().toString();

        String reason;
        if (outputTokens < 100 && !"tool_use".equals(stopReason)) {
            reason = "上游 API 异常终止 (stop_reason=" + stopReason + ", output_tokens=" + outputTokens + ")";
        } else {
            reason = "模型思考过程耗尽了输出预算 (output_tokens=" + outputTokens + ")";
        }

        if (thinking.isEmpty()) {
            return "（" + reason + "，未产生可见文本回复）";
        }
        int maxLen = 2000;
        String tail = thinking.length() > maxLen ? "..." + thinking.substring(thinking.length() - maxLen) : thinking;
        return "[" + reason + "]\n\n" + tail;
    }

    // ========== 辅助方法 ==========

    private String extractTextContent(Object content) {
        if (content == null)
            return "";
        if (content instanceof String s)
            return s;
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object text = map.get("text");
                    if (text != null)
                        sb.append(text);
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private Map<String, Object> parseJsonArguments(String arguments) {
        if (arguments == null || arguments.isBlank())
            return Map.of();
        try {
            return objectMapper.readValue(arguments, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("工具参数 JSON 解析失败: {}", arguments);
            return Map.of();
        }
    }

    private String toLogJson(AnthropicStreamEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            return String.valueOf(event);
        }
    }

    // private String toLogJson(Map<String, Object> map) {
    //     try {
    //         return objectMapper.writeValueAsString(map);
    //     } catch (JsonProcessingException exception) {
    //         return String.valueOf(map);
    //     }
    // }
}

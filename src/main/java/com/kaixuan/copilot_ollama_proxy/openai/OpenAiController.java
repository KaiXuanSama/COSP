package com.kaixuan.copilot_ollama_proxy.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.anthropic.AnthropicStreamEvent;
import com.kaixuan.copilot_ollama_proxy.proxy.MimoProxyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI 兼容 API 控制器 —— 处理 Copilot 发出的 OpenAI 格式请求。
 * <p>
 * 端点：POST /v1/chat/completions
 * <p>
 * 工作流程：
 * <ol>
 *   <li>接收 OpenAI 格式的请求（{@link OpenAiChatRequest}）</li>
 *   <li>转换为 Anthropic Messages API 格式（{@link #convertToAnthropicRequest}）</li>
 *   <li>调用 Mimo 后端的 Anthropic 兼容接口</li>
 *   <li>将 Anthropic 响应转换为 OpenAI 格式返回给 Copilot</li>
 * </ol>
 */
@RestController
public class OpenAiController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiController.class);

    private final MimoProxyService proxyService;
    private final ObjectMapper objectMapper;

    public OpenAiController(MimoProxyService proxyService, ObjectMapper objectMapper) {
        this.proxyService = proxyService;
        this.objectMapper = objectMapper;
    }

    /**
     * OpenAI Chat Completions 端点。
     * 接收 OpenAI 格式请求，转换为 Anthropic 格式调用 Mimo，
     * 再将 Anthropic 响应转换回 OpenAI 格式返回。
     */
    @PostMapping(value = "/v1/chat/completions")
    public ResponseEntity<?> chatCompletions(@RequestBody OpenAiChatRequest request) {
        Map<String, Object> anthropicBody = convertToAnthropicRequest(request);
        log.info("OpenAI -> Anthropic 转换完成，模型: {}, 流式: {}", request.getModel(), request.isStream());

        if (request.isStream()) {
            SseEmitter emitter = streamChat(anthropicBody, request.getModel());
            return ResponseEntity.ok().contentType(new MediaType("text", "event-stream"))
                    .header("Cache-Control", "no-cache").body(emitter);
        }

        // 非流式：调用 Anthropic API，将响应转换为 OpenAI 格式
        String anthropicResp = proxyService.getWebClient().post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(anthropicBody).retrieve().bodyToMono(String.class)
                .block();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(convertAnthropicToOpenAi(anthropicResp, request.getModel()));
    }

    // ========== 流式处理：Anthropic SSE → OpenAI SSE ==========

    /**
     * 流式对话 —— 将 Anthropic SSE 事件转换为 OpenAI 格式的 SSE chunk，通过 SseEmitter 逐条发送。
     * 这里直接订阅上游的 Anthropic 事件流，收到一个事件就转换并立即回传，
     * 避免先把整段响应缓冲完成再统一处理导致明显首包延迟。
     */
    private SseEmitter streamChat(Map<String, Object> anthropicBody, String model) {
        anthropicBody.put("stream", true);
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时

        AtomicReference<String> messageId = new AtomicReference<>(
                "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        AtomicReference<Boolean> finishSent = new AtomicReference<>(false);

        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        Disposable subscription = proxyService.streamAnthropicMessages(anthropicBody).subscribe(event -> {
            try {
                List<String> chunks = convertSseEventToOpenAi(event, messageId, finishSent, model);
                for (String chunk : chunks) {
                    if (chunk != null) {
                        emitter.send(SseEmitter.event().data(chunk));
                    }
                }
            } catch (Exception exception) {
                if (isClientDisconnect(exception)) {
                    Disposable activeSubscription = subscriptionRef.get();
                    if (activeSubscription != null) {
                        activeSubscription.dispose();
                    }
                    emitter.complete();
                    return;
                }
                log.error("OpenAI SSE 转发异常", exception);
                Disposable activeSubscription = subscriptionRef.get();
                if (activeSubscription != null) {
                    activeSubscription.dispose();
                }
                emitter.completeWithError(exception);
            }
        }, error -> {
            if (isClientDisconnect(error)) {
                emitter.complete();
                return;
            }
            log.error("Anthropic API 调用异常", error);
            emitter.completeWithError(error);
        }, () -> {
            try {
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (Exception exception) {
                if (isClientDisconnect(exception)) {
                    emitter.complete();
                    return;
                }
                log.error("OpenAI SSE 结束发送失败", exception);
                emitter.completeWithError(exception);
            }
        });
        subscriptionRef.set(subscription);

        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> {
            log.warn("OpenAI SSE emitter 超时，模型: {}", model);
            subscription.dispose();
            emitter.complete();
        });
        emitter.onError(error -> subscription.dispose());

        return emitter;
    }

    /**
     * 将单个 Anthropic SSE 事件转换为 OpenAI chunk JSON。
     * 返回空列表表示此事件不需要输出。
     */
    private List<String> convertSseEventToOpenAi(AnthropicStreamEvent event, AtomicReference<String> messageId,
            AtomicReference<Boolean> finishSent, String model) {
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
                    if ("tool_use".equals(block.get("type"))) {
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
                        if (text != null)
                            result.add(buildOpenAiChunk(messageId.get(), model, null, text, null));
                    } else if ("thinking_delta".equals(deltaType)) {
                        // 思考块：转换为 Copilot 可识别的 thinking_delta 格式
                        String thinking = (String) delta.get("thinking");
                        if (thinking != null)
                            result.add(buildOpenAiThinkingChunk(messageId.get(), model, thinking));
                    } else if ("input_json_delta".equals(deltaType)) {
                        String json = (String) delta.get("partial_json");
                        if (json != null)
                            result.add(buildOpenAiChunk(messageId.get(), model, null, null, json));
                    }
                }
            }
            case "content_block_stop" -> {
            }
            case "message_delta" -> {
                // 消息级别更新：包含 stop_reason（"end_turn" 或 "tool_use"）
                Map<String, Object> delta = event.getDelta();
                if (delta != null) {
                    String stopReason = (String) delta.get("stop_reason");
                    if ("tool_use".equals(stopReason)) {
                        result.add(buildOpenAiFinishChunk(messageId.get(), model, "tool_calls"));
                        finishSent.set(true);
                    }
                }
            }
            case "message_stop" -> {
                // 消息结束。如果 message_delta 已发送过 finish chunk，则跳过
                if (!finishSent.get()) {
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

    /**
     * 构建 OpenAI 格式的 SSE chunk（文本/工具参数增量）。
     */
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

    /**
     * 构建工具调用起始 chunk —— 包含 tool call id、type 和 function name。
     * 这是 Copilot 识别工具调用的关键 chunk。
     */
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

    /**
     * 构建思考块 chunk —— delta 中携带 type: "thinking_delta" 和 thinking 字段。
     * Copilot 会将此格式渲染为可折叠的思考过程块。
     */
    private String buildOpenAiThinkingChunk(String id, String model, String thinking) {
        try {
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("type", "thinking_delta");
            delta.put("thinking", thinking);

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

    /**
     * 构建流式结束 chunk（包含 finish_reason）。
     */
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

    // ========== 非流式：Anthropic 响应 → OpenAI 响应 ==========

    /**
     * 将 Anthropic 非流式响应转换为 OpenAI 格式。
     */
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
                        tc.put("function", Map.of("name", block.get("name"), "arguments",
                                objectMapper.writeValueAsString(block.get("input"))));
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
                openai.put("usage",
                        Map.of("prompt_tokens", usage.getOrDefault("input_tokens", 0), "completion_tokens",
                                usage.getOrDefault("output_tokens", 0), "total_tokens",
                                ((Number) usage.getOrDefault("input_tokens", 0)).intValue()
                                        + ((Number) usage.getOrDefault("output_tokens", 0)).intValue()));
            }

            return objectMapper.writeValueAsString(openai);
        } catch (Exception e) {
            log.error("Anthropic → OpenAI 转换失败", e);
            return "{\"error\":\"conversion_failed\"}";
        }
    }

    // ========== 请求转换：OpenAI → Anthropic ==========

    /**
     * 将 OpenAI 格式请求转换为 Anthropic Messages API 格式。
     */
    private Map<String, Object> convertToAnthropicRequest(OpenAiChatRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.getModel());
        body.put("max_tokens", req.getMaxTokens() != null ? req.getMaxTokens() : 8192);

        List<Map<String, Object>> systemParts = new ArrayList<>();
        List<Map<String, Object>> messages = new ArrayList<>();

        for (var msg : req.getMessages()) {
            String role = msg.getRole();

            if ("system".equals(role)) {
                systemParts.add(Map.of("type", "text", "text", extractOpenAiTextContent(msg.getContent())));
            } else if ("tool".equals(role)) {
                Map<String, Object> toolResult = new LinkedHashMap<>();
                toolResult.put("type", "tool_result");
                if (msg.getToolCallId() != null) {
                    toolResult.put("tool_use_id", msg.getToolCallId());
                }
                toolResult.put("content", extractOpenAiTextContent(msg.getContent()));
                messages.add(Map.of("role", "user", "content", List.of(toolResult)));
            } else if ("assistant".equals(role) && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                List<Object> contentBlocks = new ArrayList<>();
                String text = extractOpenAiTextContent(msg.getContent());
                if (text != null && !text.isEmpty()) {
                    contentBlocks.add(Map.of("type", "text", "text", text));
                }
                for (var tc : msg.getToolCalls()) {
                    Map<String, Object> toolUse = new LinkedHashMap<>();
                    toolUse.put("type", "tool_use");
                    toolUse.put("id", tc.getId() != null ? tc.getId()
                            : "toolu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                    toolUse.put("name", tc.getFunction().getName());
                    toolUse.put("input", parseJsonArguments(tc.getFunction().getArguments()));
                    contentBlocks.add(toolUse);
                }
                messages.add(Map.of("role", "assistant", "content", contentBlocks));
            } else {
                String text = extractOpenAiTextContent(msg.getContent());
                messages.add(Map.of("role", role, "content", text != null ? text : ""));
            }
        }

        if (!systemParts.isEmpty()) {
            body.put("system", systemParts);
        }
        body.put("messages", messages);

        if (req.getTools() != null && !req.getTools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (var tool : req.getTools()) {
                if (tool.getFunction() != null) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("name", tool.getFunction().getName());
                    if (tool.getFunction().getDescription() != null) {
                        t.put("description", tool.getFunction().getDescription());
                    }
                    if (tool.getFunction().getParameters() != null) {
                        t.put("input_schema", tool.getFunction().getParameters());
                    }
                    tools.add(t);
                }
            }
            body.put("tools", tools);
        }

        return body;
    }

    // ========== 辅助方法 ==========

    private String extractOpenAiTextContent(Object content) {
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
                } else if (item instanceof OpenAiChatRequest.ContentPart part) {
                    if ("text".equals(part.getType()) && part.getText() != null) {
                        sb.append(part.getText());
                    }
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

    private boolean isClientDisconnect(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AsyncRequestNotUsableException) {
                return true;
            }

            String simpleName = current.getClass().getSimpleName();
            if ("ClientAbortException".equals(simpleName) || "EOFException".equals(simpleName)) {
                return true;
            }

            current = current.getCause();
        }
        return false;
    }
}

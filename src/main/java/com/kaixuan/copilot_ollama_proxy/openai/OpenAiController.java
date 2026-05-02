package com.kaixuan.copilot_ollama_proxy.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.proxy.MimoProxyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.BodyExtractors;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI 兼容 API 控制器 —— 处理 Copilot 发出的 OpenAI 格式请求。
 * 端点：POST /v1/chat/completions
 * 工作流程：
 *   接收 OpenAI 格式的请求（{@link OpenAiChatRequest}）
 *   转换为 Anthropic Messages API 格式（{@link #convertToAnthropicRequest}）
 *   调用 Mimo 后端的 Anthropic 兼容接口
 *   将 Anthropic 响应转换为 OpenAI 格式返回给 Copilot
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
     * 再将 Anthropic 响应转换为 OpenAI 格式返回。
     */
    @PostMapping(value = "/v1/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> chatCompletions(@RequestBody OpenAiChatRequest request) {
        Map<String, Object> anthropicBody = convertToAnthropicRequest(request);
        log.info("OpenAI -> Anthropic 转换完成，模型: {}, 流式: {}", request.getModel(), request.isStream());

        if (request.isStream()) {
            return streamChat(anthropicBody, request.getModel());
        }

        // 非流式：调用 Anthropic API，将响应转换为 OpenAI 格式
        return proxyService.getWebClient().post().uri("/v1/messages").bodyValue(anthropicBody).retrieve()
                .bodyToMono(String.class)
                .map(anthropicResp -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                        .body(convertAnthropicToOpenAi(anthropicResp, request.getModel())));
    }

    // ========== 流式处理：Anthropic SSE → OpenAI SSE ==========

    /**
     * 流式对话 —— 将 Anthropic SSE 事件逐步转换为 OpenAI 格式的 SSE chunk。
     * Anthropic SSE 格式：
     * event: content_block_delta
     * data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}
     * OpenAI SSE 格式（Copilot 期望的格式）：
     * data: {"id":"chatcmpl-xxx","choices":[{"delta":{"content":"Hello"}}]}
     */
    private Mono<ResponseEntity<String>> streamChat(Map<String, Object> anthropicBody, String model) {
        anthropicBody.put("stream", true);

        // 用 AtomicReference 跨事件保持状态
        AtomicReference<String> messageId = new AtomicReference<>(
                "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        AtomicReference<String> currentBlockType = new AtomicReference<>();

        Flux<String> openAiChunks = proxyService.getWebClient().post().uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(anthropicBody).exchangeToFlux(response -> {
                    Flux<DataBuffer> dataBuffers = response.body(BodyExtractors.toDataBuffers());
                    // 逐行处理 SSE 数据
                    return DataBufferUtils.join(dataBuffers).flux().concatMap(joined -> {
                        String raw = joined.toString(StandardCharsets.UTF_8);
                        DataBufferUtils.release(joined);
                        return Flux.fromArray(raw.split("\n"));
                    });
                })
                // 用状态机解析 SSE：累积 event: 和 data: 行，遇到空行时处理
                .bufferUntil(line -> line.isBlank()).concatMap(lines -> {
                    String eventType = null;
                    String dataJson = null;
                    for (String line : lines) {
                        if (line.startsWith("event: "))
                            eventType = line.substring(7).trim();
                        if (line.startsWith("data: "))
                            dataJson = line.substring(6).trim();
                    }
                    if (eventType == null || dataJson == null)
                        return Flux.empty();

                    return Flux.fromIterable(
                            convertSseEventToOpenAi(eventType, dataJson, messageId, currentBlockType, model));
                }).filter(Objects::nonNull);

        // 拼接所有 chunk + 结束标记
        return openAiChunks.collectList().map(chunks -> {
            StringBuilder sb = new StringBuilder();
            for (String chunk : chunks) {
                sb.append("data: ").append(chunk).append("\n\n");
            }
            sb.append("data: [DONE]\n\n");
            return ResponseEntity.ok().contentType(new MediaType("text", "event-stream"))
                    .header("Cache-Control", "no-cache").header("x-request-id", messageId.get()).body(sb.toString());
        });
    }

    /**
     * 将单个 Anthropic SSE 事件转换为 OpenAI chunk JSON。
     * 返回 null 表示此事件不需要输出。
     */
    private List<String> convertSseEventToOpenAi(String eventType, String dataJson, AtomicReference<String> messageId,
            AtomicReference<String> currentBlockType, String model) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(dataJson, Map.class);
            List<String> result = new ArrayList<>();

            switch (eventType) {
            case "message_start" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> message = (Map<String, Object>) data.get("message");
                if (message != null && message.get("id") != null) {
                    messageId.set("chatcmpl-" + message.get("id"));
                }
                // 发送初始 chunk（role: assistant）
                result.add(buildOpenAiChunk(messageId.get(), model, "assistant", null, null, null));
            }
            case "content_block_start" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> block = (Map<String, Object>) data.get("content_block");
                if (block != null) {
                    currentBlockType.set((String) block.get("type"));
                }
            }
            case "content_block_delta" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> delta = (Map<String, Object>) data.get("delta");
                if (delta != null) {
                    String deltaType = (String) delta.get("type");

                    if ("text_delta".equals(deltaType)) {
                        // 文本增量 → OpenAI delta.content
                        String text = (String) delta.get("text");
                        if (text != null) {
                            result.add(buildOpenAiChunk(messageId.get(), model, null, text, null, null));
                        }
                    } else if ("thinking_delta".equals(deltaType)) {
                        // 思考增量 → 包装为 [thinking] 标签的文本
                        String thinking = (String) delta.get("thinking");
                        if (thinking != null) {
                            result.add(buildOpenAiChunk(messageId.get(), model, null, null, thinking, null));
                        }
                    } else if ("input_json_delta".equals(deltaType)) {
                        // 工具参数增量 → OpenAI delta.tool_calls
                        String json = (String) delta.get("partial_json");
                        if (json != null) {
                            result.add(buildOpenAiChunk(messageId.get(), model, null, null, null, json));
                        }
                    }
                }
            }
            case "content_block_stop" -> {
                currentBlockType.set(null);
            }
            case "message_stop" -> {
                // 发送结束 chunk（finish_reason: stop）
                result.add(buildOpenAiFinishChunk(messageId.get(), model, "stop"));
            }
            }

            return result;
        } catch (Exception e) {
            log.warn("Failed to parse SSE event: {}", dataJson, e);
            return List.of();
        }
    }

    /**
     * 构建 OpenAI 格式的 SSE chunk。
     *
     * @param role     角色（仅在第一个 chunk 中设置）
     * @param text     文本增量
     * @param thinking 思考增量（转换为 [thinking] 标签）
     * @param toolJson 工具参数增量
     */
    private String buildOpenAiChunk(String id, String model, String role, String text, String thinking,
            String toolJson) {
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
            if (thinking != null)
                delta.put("content", "[thinking]" + thinking + "[/thinking]");
            if (toolJson != null) {
                // 工具参数增量：以增量方式传递
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
     * 构建 OpenAI 格式的结束 chunk（包含 finish_reason）。
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
     * Anthropic 响应：
     * {"id":"msg_xxx","content":[{"type":"thinking","thinking":"..."},{"type":"text","text":"Hello"}]}
     * OpenAI 响应：
     * {"id":"chatcmpl-xxx","choices":[{"message":{"role":"assistant","content":"Hello"},"finish_reason":"stop"}]}
     */
    private String convertAnthropicToOpenAi(String anthropicJson, String model) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> anthropic = objectMapper.readValue(anthropicJson, Map.class);

            Map<String, Object> openai = new LinkedHashMap<>();
            String msgId = (String) anthropic.get("id");
            openai.put("id", "chatcmpl-" + msgId);
            openai.put("object", "chat.completion");
            openai.put("created", System.currentTimeMillis() / 1000);
            openai.put("model", model);

            // 解析 content blocks
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
                        // 包装为 [thinking] 标签
                        textContent.append("[thinking]").append(block.get("thinking")).append("[/thinking]");
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

            // 构建 message
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            message.put("content", textContent.length() > 0 ? textContent.toString() : null);
            if (!toolCalls.isEmpty()) {
                message.put("tool_calls", toolCalls);
            }

            // finish_reason
            String stopReason = (String) anthropic.get("stop_reason");
            String finishReason = "tool_use".equals(stopReason) ? "tool_calls" : "stop";

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", finishReason);

            openai.put("choices", List.of(choice));

            // usage
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
            log.error("Failed to convert Anthropic response to OpenAI format", e);
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
            log.warn("Failed to parse tool arguments JSON: {}", arguments);
            return Map.of();
        }
    }
}

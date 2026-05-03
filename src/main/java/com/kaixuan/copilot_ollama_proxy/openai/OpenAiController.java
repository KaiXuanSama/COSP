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
 *   <li><b>直接返回 Anthropic 原始响应</b>（不做格式转换，Copilot 原生支持 Anthropic 格式）</li>
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
     * 直接将 Anthropic 响应透传给 Copilot。
     */
    @PostMapping(value = "/v1/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> chatCompletions(@RequestBody OpenAiChatRequest request) {
        Map<String, Object> anthropicBody = convertToAnthropicRequest(request);
        log.info("OpenAI -> Anthropic 转换完成，模型: {}, 流式: {}", request.getModel(), request.isStream());

        if (request.isStream()) {
            return streamChat(anthropicBody);
        }

        // 非流式：直接返回 Anthropic 原始 JSON
        return proxyService.getWebClient().post().uri("/v1/messages").bodyValue(anthropicBody).retrieve()
                .bodyToMono(String.class)
                .map(anthropicResp -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(anthropicResp));
    }

    // ========== 流式透传：直接转发 Anthropic SSE 原始字节 ==========

    /**
     * 流式对话 —— 直接将 Anthropic SSE 原始数据透传给 Copilot。
     * 不做任何格式转换，Copilot 原生支持 Anthropic SSE 格式（包含 thinking 块等）。
     */
    private Mono<ResponseEntity<String>> streamChat(Map<String, Object> anthropicBody) {
        anthropicBody.put("stream", true);

        return proxyService.getWebClient().post().uri("/v1/messages").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(anthropicBody).exchangeToFlux(response -> {
                    Flux<DataBuffer> dataBuffers = response.body(BodyExtractors.toDataBuffers());
                    // 逐字节读取上游响应，原样转发
                    return DataBufferUtils.join(dataBuffers).flux().concatMap(joined -> {
                        String raw = joined.toString(StandardCharsets.UTF_8);
                        DataBufferUtils.release(joined);
                        return Flux.just(raw);
                    });
                }).collectList().map(chunks -> {
                    StringBuilder sb = new StringBuilder();
                    for (String chunk : chunks)
                        sb.append(chunk);
                    return ResponseEntity.ok().contentType(new MediaType("text", "event-stream"))
                            .header("Cache-Control", "no-cache").body(sb.toString());
                });
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

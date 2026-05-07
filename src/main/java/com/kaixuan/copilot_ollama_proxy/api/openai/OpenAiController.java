package com.kaixuan.copilot_ollama_proxy.api.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.openai.CompositeUpstreamChatService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.ApiUsageCollector;
import com.kaixuan.copilot_ollama_proxy.protocol.openai.OpenAiChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAI 兼容 API 控制器 —— 处理 Copilot 发出的 OpenAI 格式请求。
 * <p>
 * 端点：POST /v1/chat/completions
 * <p>
 * 控制器只负责 HTTP 层（请求接收、响应封装、SSE 流转发），
 * 协议转换逻辑由 {@link UpstreamChatService} 的实现类处理。
 */
@RestController
public class OpenAiController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiController.class);

    private final CompositeUpstreamChatService upstreamChatService;
    private final ObjectMapper objectMapper;
    private final ApiUsageCollector apiUsageCollector;

    public OpenAiController(CompositeUpstreamChatService upstreamChatService, ObjectMapper objectMapper,
            ApiUsageCollector apiUsageCollector) {
        this.upstreamChatService = upstreamChatService;
        this.objectMapper = objectMapper;
        this.apiUsageCollector = apiUsageCollector;
    }

    @PostMapping(value = "/v1/chat/completions")
    public Object chatCompletions(@RequestBody OpenAiChatRequest request) {
        Map<String, Object> requestBody = buildRequestBody(request);

        if (request.isStream()) {
            return streamResponse(requestBody, request.getModel());
        }

        return upstreamChatService.chatCompletion(requestBody, request.getModel()).doOnNext(this::recordUsage)
                .map(openAiJson -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(openAiJson));
    }

    private ResponseEntity<SseEmitter> streamResponse(Map<String, Object> requestBody, String model) {
        SseEmitter emitter = new SseEmitter(300_000L);

        AtomicInteger streamInputTokens = new AtomicInteger(0);
        AtomicInteger streamOutputTokens = new AtomicInteger(0);

        Disposable subscription = upstreamChatService.chatCompletionStream(requestBody, model).subscribe(chunk -> {
            try {
                emitter.send(SseEmitter.event().data(chunk));
                accumulateStreamUsage(chunk, streamInputTokens, streamOutputTokens);
            } catch (Exception exception) {
                if (isClientDisconnect(exception)) {
                    return;
                }
                log.error("SSE 发送异常", exception);
            }
        }, error -> {
            if (isClientDisconnect(error)) {
                return;
            }
            log.error("上游 API 调用异常", error);
            emitter.completeWithError(error);
        }, () -> {
            apiUsageCollector.record(streamInputTokens.get(), streamOutputTokens.get());
            try {
                emitter.complete();
            } catch (Exception exception) {
                if (isClientDisconnect(exception)) {
                    return;
                }
                log.error("SSE 结束发送失败", exception);
                emitter.completeWithError(exception);
            }
        });

        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> {
            log.warn("SSE emitter 超时，模型: {}", model);
            subscription.dispose();
            emitter.complete();
        });
        emitter.onError(error -> subscription.dispose());

        return ResponseEntity.ok().contentType(new MediaType("text", "event-stream"))
                .header("Cache-Control", "no-cache").body(emitter);
    }

    /**
     * 将 OpenAiChatRequest 转换为通用的 Map 格式，供 UpstreamChatService 使用。
     * 通过 ObjectMapper 序列化/反序列化，确保嵌套对象（messages、tools）为纯 Map 结构。
     */
    private Map<String, Object> buildRequestBody(OpenAiChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.put("top_p", request.getTopP());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        body.put("stream", request.isStream());
        body.put("messages", objectMapper.convertValue(request.getMessages(), List.class));
        if (request.getTools() != null) {
            body.put("tools", objectMapper.convertValue(request.getTools(), List.class));
        }
        if (request.getToolChoice() != null) {
            body.put("tool_choice", objectMapper.convertValue(request.getToolChoice(), Object.class));
        }
        if (request.getN() != null) {
            body.put("n", request.getN());
        }
        if (request.getStreamOptions() != null) {
            body.put("stream_options", objectMapper.convertValue(request.getStreamOptions(), Object.class));
        }
        return body;
    }

    /**
     * 从非流式响应 JSON 中提取 usage 并记录。
     */
    private void recordUsage(String openAiJson) {
        try {
            JsonNode root = objectMapper.readTree(openAiJson);
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                int inputTokens = usage.path("prompt_tokens").asInt(0);
                int outputTokens = usage.path("completion_tokens").asInt(0);
                apiUsageCollector.record(inputTokens, outputTokens);
            }
        } catch (Exception e) {
            log.warn("非流式响应 usage 提取失败: {}", e.getMessage());
        }
    }

    /**
     * 从流式 SSE chunk 中累加 token 数。
     * 流式响应中 usage 可能出现在最后一个 content chunk 或单独的 usage chunk 中。
     */
    private void accumulateStreamUsage(String chunk, AtomicInteger inputTokens, AtomicInteger outputTokens) {
        try {
            JsonNode root = objectMapper.readTree(chunk);
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                inputTokens.set(usage.path("prompt_tokens").asInt(0));
                outputTokens.set(usage.path("completion_tokens").asInt(0));
            }
        } catch (Exception e) {
            // 流式 chunk 可能不是合法 JSON（如 [DONE]），忽略
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

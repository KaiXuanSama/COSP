package com.kaixuan.copilot_ollama_proxy.api.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.openai.UpstreamChatService;
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

    private final UpstreamChatService upstreamChatService;
    private final ObjectMapper objectMapper;

    public OpenAiController(UpstreamChatService upstreamChatService, ObjectMapper objectMapper) {
        this.upstreamChatService = upstreamChatService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/v1/chat/completions")
    public Object chatCompletions(@RequestBody OpenAiChatRequest request) {
        Map<String, Object> requestBody = buildRequestBody(request);

        if (request.isStream()) {
            return streamResponse(requestBody, request.getModel());
        }

        return upstreamChatService.chatCompletion(requestBody, request.getModel())
                .map(openAiJson -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(openAiJson));
    }

    private ResponseEntity<SseEmitter> streamResponse(Map<String, Object> requestBody, String model) {
        SseEmitter emitter = new SseEmitter(300_000L);

        Disposable subscription = upstreamChatService.chatCompletionStream(requestBody, model).subscribe(chunk -> {
            try {
                emitter.send(SseEmitter.event().data(chunk));
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

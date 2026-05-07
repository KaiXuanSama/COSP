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

    /**
     * 构造函数注入 CompositeUpstreamChatService、ObjectMapper 和 ApiUsageCollector。
     * @param upstreamChatService 上游聊天服务组合
     * @param objectMapper JSON 对象映射器
     * @param apiUsageCollector API 使用量收集器
     */
    public OpenAiController(CompositeUpstreamChatService upstreamChatService, ObjectMapper objectMapper, ApiUsageCollector apiUsageCollector) {
        this.upstreamChatService = upstreamChatService;
        this.objectMapper = objectMapper;
        this.apiUsageCollector = apiUsageCollector;
    }

    /**
     * 处理聊天完成请求，支持流式和非流式两种模式。
     * @param request OpenAI 格式的聊天请求
     * @return 非流式返回 ResponseEntity 包含完整 JSON，流式返回 SseEmitter 逐片段发送数据
     */
    @PostMapping(value = "/v1/chat/completions")
    public Object chatCompletions(@RequestBody OpenAiChatRequest request) {
        Map<String, Object> requestBody = buildRequestBody(request);

        // 根据请求中的 stream 参数决定使用非流式还是流式响应处理。
        if (request.isStream()) {
            // 流式调用上游服务，逐片段发送响应给客户端，并在完成时记录累计的 token 使用量。
            return streamResponse(requestBody, request.getModel());
        } else {
            // 非流式调用上游服务，获取完整响应后提取 usage 进行记录，并返回给客户端。
            return upstreamChatService.chatCompletion(requestBody, request.getModel()).doOnNext(this::recordUsage)
                    .map(openAiJson -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(openAiJson));
        }
    }

    /**
     * 处理流式响应的辅助方法，使用 SseEmitter 将上游服务的 SSE 片段逐个发送给客户端，并在完成时记录累计的 token 使用量。
     * @param requestBody 请求体内容，已转换为 Map 格式
     * @param model 模型名称
     * @return ResponseEntity 包含 SseEmitter，用于流式发送数据
     */
    private ResponseEntity<SseEmitter> streamResponse(Map<String, Object> requestBody, String model) {
        // SseEmitter 设置较长的超时时间（5 分钟），以适应可能较长的生成过程。
        SseEmitter emitter = new SseEmitter(300_000L);

        // 使用 AtomicInteger 来累积流式响应中的输入输出 token 数，确保线程安全。
        AtomicInteger streamInputTokens = new AtomicInteger(0);
        AtomicInteger streamOutputTokens = new AtomicInteger(0);

        // 调用上游服务的流式接口，订阅每个 SSE 片段，并将其发送给客户端，同时累积 token 使用量。
        Disposable subscription = upstreamChatService.chatCompletionStream(requestBody, model).subscribe(chunk -> {
            try {
                // 将上游服务的 SSE 片段原样发送给客户端，保持流式响应的实时性。
                emitter.send(SseEmitter.event().data(chunk));
                // 从 SSE 片段中提取 usage 信息并累积 token 数，流式响应中 usage 可能出现在最后一个 content chunk 或单独的 usage chunk 中。
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

        // 注册 SseEmitter 的完成、超时和错误回调，确保在客户端断开连接或发生异常时正确清理资源。
        emitter.onCompletion(subscription::dispose);
        // SseEmitter 没有 onTimeout 方法，使用 onCompletion 来处理超时情况，记录日志并清理资源。
        emitter.onTimeout(() -> {
            log.warn("SSE emitter 超时，模型: {}", model);
            subscription.dispose();
            emitter.complete();
        });
        // 注册错误回调，记录日志并清理资源，避免因异常导致的资源泄漏。
        emitter.onError(error -> subscription.dispose());

        // 返回包含 SseEmitter 的 ResponseEntity，设置 Content-Type 为 text/event-stream，并添加 no-cache 头以防止缓存。
        return ResponseEntity.ok().contentType(new MediaType("text", "event-stream")).header("Cache-Control", "no-cache").body(emitter);
    }

    /**
     * 构建请求体，将 OpenAiChatRequest 中的字段转换为 Map 格式，适配上游服务的请求要求。
     * @param request OpenAiChatRequest 对象，包含模型名称、消息列表、工具调用信息等字段
     * @return Map<String, Object> 格式的请求体，包含模型名称、消息列表、工具调用信息等字段，适配上游服务的请求要求
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
     * @param openAiJson 非流式响应的 JSON 字符串，包含 usage 字段
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
     * @param chunk SSE chunk 字符串
     * @param inputTokens 输入 token 累加器
     * @param outputTokens 输出 token 累加器
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

    /**
     * 判断异常是否由客户端断开连接引起，常见的异常类型包括 AsyncRequestNotUsableException、ClientAbortException、EOFException 等。
     * @param throwable 异常对象
     * @return 如果异常或其原因链中包含客户端断开连接的异常类型，则返回 true；否则返回 false
     */
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

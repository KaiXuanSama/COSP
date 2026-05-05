package com.kaixuan.copilot_ollama_proxy.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI 上游实现 —— 将 OpenAI Chat Completions 请求直接转发到 MiMo 的 OpenAI 兼容端点。
 */
@Service @ConditionalOnProperty(name = "proxy.upstream-chat-service", havingValue = "openai", matchIfMissing = true)
public class OpenAiChatService implements UpstreamChatService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatService.class);

    private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE = new ParameterizedTypeReference<>() {
    };

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public OpenAiChatService(@Value("${mimo.api-key}") String apiKey, @Value("${mimo.base-url}") String baseUrl,
            @Value("${mimo.default-model}") String defaultModel, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
        this.webClient = WebClient.builder().baseUrl(normalizeOpenAiBaseUrl(baseUrl)).defaultHeader("api-key", apiKey)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
    }

    @Override
    public Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model) {
        Map<String, Object> requestBody = prepareRequestBody(openAiRequest, false, model);
        log.info("OpenAI 上游直连，模型: {},X 流式: false", requestBody.get("model"));
        // log.debug("OpenAI 请求体: {}", toLogJson(requestBody));

        return webClient.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody)
                .retrieve().bodyToMono(String.class).retryWhen(buildRetrySpec("chatCompletion"))
                .doOnNext(response -> log.debug("OpenAI 响应: {}", response));
    }

    @Override
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
        Map<String, Object> requestBody = prepareRequestBody(openAiRequest, true, model);
        log.info("OpenAI 上游直连，模型: {}, 流式: true", requestBody.get("model"));
        log.debug("OpenAI 请求体: {}", toLogJson(requestBody));

        AtomicBoolean reasoningEmitted = new AtomicBoolean(false);
        AtomicBoolean contentEmitted = new AtomicBoolean(false);
        StringBuilder reasoningBuffer = new StringBuilder();
        AtomicReference<String> chunkId = new AtomicReference<>("chatcmpl-unknown");
        AtomicReference<String> finishReason = new AtomicReference<>(null);

        return webClient.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(requestBody).retrieve().bodyToFlux(STRING_SSE_TYPE)
                .retryWhen(buildRetrySpec("chatCompletionStream")).mapNotNull(ServerSentEvent::data)
                .filter(chunk -> !chunk.isBlank()).doOnNext(raw -> log.debug("OpenAI 原始: {}", raw)).concatMap(chunk -> {
                    if (!"[DONE]".equals(chunk) && chunk.contains("\"finish_reason\":\"stop\"") && !contentEmitted.get()
                            && !reasoningBuffer.isEmpty()) {
                        log.warn("模型未输出正文，回退使用思考内容作为回复 (长度: {})", reasoningBuffer.length());
                        String fallbackContent = buildFallbackContentChunk(chunkId.get(), model,
                                reasoningBuffer.toString());
                        String fallbackFinish = buildFallbackFinishChunk(chunkId.get(), model);
                        return Flux.just(fallbackContent, fallbackFinish);
                    }
                    return Flux.just(translateChunk(chunk, reasoningEmitted, contentEmitted, reasoningBuffer, chunkId,
                            finishReason));
                }).doOnNext(chunk -> log.debug("OpenAI 翻译: {}", chunk));
    }

    private Map<String, Object> prepareRequestBody(Map<String, Object> openAiRequest, boolean stream, String model) {
        Map<String, Object> body = new LinkedHashMap<>(openAiRequest);
        String resolvedModel = resolveModel(body.get("model"), model);
        body.put("model", resolvedModel);
        body.put("stream", stream);
        body.values().removeIf(Objects::isNull);

        if (resolvedModel.contains("mimo")) {
            convertImageFormatForMimo(body);
        }

        return body;
    }

    @SuppressWarnings("unchecked")
    private void convertImageFormatForMimo(Map<String, Object> body) {
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> messages)) {
            return;
        }
        for (Object msgObj : messages) {
            if (!(msgObj instanceof Map<?, ?> msg)) {
                continue;
            }
            Object contentObj = msg.get("content");
            if (!(contentObj instanceof List<?> contentList)) {
                continue;
            }
            boolean hasImage = false;
            for (Object itemObj : contentList) {
                if (!(itemObj instanceof Map<?, ?> item)) {
                    continue;
                }
                if (!"image_url".equals(item.get("type"))) {
                    continue;
                }
                hasImage = true;
                Object imageObj = item.get("image_url");
                if (imageObj instanceof Map<?, ?> imageMap) {
                    if (imageMap.containsKey("media_type")) {
                        ((Map<String, Object>) imageMap).remove("media_type");
                        ((Map<String, Object>) imageMap).put("type", "image_url");
                        log.debug("MiMo 图片格式转换: media_type -> type=image_url");
                    }
                }
            }
            if (hasImage && "tool".equals(msg.get("role"))) {
                ((Map<String, Object>) msg).put("role", "user");
                ((Map<String, Object>) msg).remove("tool_call_id");
                log.debug("MiMo 图片消息 role 转换: tool -> user");
            }
        }
    }

    private String resolveModel(Object requestModel, String fallbackModel) {
        if (requestModel instanceof String value && !value.isBlank()) {
            return value;
        }
        if (fallbackModel != null && !fallbackModel.isBlank()) {
            return fallbackModel;
        }
        return defaultModel;
    }

    private Retry buildRetrySpec(String method) {
        return Retry.fixedDelay(5, Duration.ofSeconds(5))
                .filter(ex -> (ex instanceof WebClientResponseException
                        && (((WebClientResponseException) ex).getStatusCode().is5xxServerError()
                                || ((WebClientResponseException) ex).getStatusCode().value() == 400))
                        || ex instanceof WebClientRequestException)
                .doBeforeRetry(signal -> log.warn("[{}] 上游 API 调用失败，重试第 {} 次: {}", method, signal.totalRetries() + 1,
                        signal.failure().getMessage()));
    }

    private String normalizeOpenAiBaseUrl(String rawBaseUrl) {
        String normalized = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        if (normalized.isEmpty()) {
            return "https://api.xiaomimimo.com/v1";
        }

        normalized = normalized.replaceAll("/+$", "");
        if (normalized.endsWith("/anthropic")) {
            normalized = normalized.substring(0, normalized.length() - "/anthropic".length());
        }
        if (!normalized.endsWith("/v1")) {
            normalized = normalized + "/v1";
        }
        return normalized;
    }

    private String toLogJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException exception) {
            return String.valueOf(map);
        }
    }

    /**
     * 将 MiMo 的 reasoning_content 字段翻译为 Copilot 可识别的 reasoning_opaque + reasoning_text。
     * 首个思考 chunk 输出 reasoning_opaque:"thinking" 标记，后续 chunk 只输出 reasoning_text。
     * 同时追踪是否有正文输出，用于回退逻辑。
     */
    @SuppressWarnings("unchecked")
    private String translateChunk(String chunkJson, AtomicBoolean reasoningEmitted, AtomicBoolean contentEmitted,
            StringBuilder reasoningBuffer, AtomicReference<String> chunkId, AtomicReference<String> finishReason) {
        try {
            Map<String, Object> chunk = objectMapper.readValue(chunkJson, Map.class);

            // 记录 chunk ID
            Object id = chunk.get("id");
            if (id instanceof String idStr && !idStr.isEmpty()) {
                chunkId.set(idStr);
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
            if (choices == null || choices.isEmpty()) {
                return chunkJson;
            }
            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
            if (delta == null) {
                // 检查 finish_reason
                Object fr = choices.get(0).get("finish_reason");
                if (fr instanceof String reason) {
                    finishReason.set(reason);
                }
                return chunkJson;
            }

            // 追踪正文输出
            Object contentObj = delta.get("content");
            if (contentObj instanceof String content && !content.isEmpty()) {
                contentEmitted.set(true);
            }

            // 追踪 finish_reason
            Object fr = choices.get(0).get("finish_reason");
            if (fr instanceof String reason) {
                finishReason.set(reason);
            }

            Object reasoningObj = delta.get("reasoning_content");
            if (reasoningObj instanceof String reasoning && !reasoning.isEmpty()) {
                reasoningBuffer.append(reasoning);
                delta.remove("reasoning_content");
                delta.put("content", null);
                if (!reasoningEmitted.getAndSet(true)) {
                    delta.put("reasoning_opaque", "thinking");
                }
                delta.put("reasoning_text", reasoning);
                return objectMapper.writeValueAsString(chunk);
            }
            return chunkJson;
        } catch (Exception e) {
            return chunkJson;
        }
    }

    /**
     * 构建回退内容 chunk：当模型未输出正文但有思考内容时，将思考内容作为正文输出。
     */
    private String buildFallbackContentChunk(String id, String model, String reasoningContent) {
        try {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);

            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("role", "assistant");
            delta.put("content", reasoningContent);

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

    private String buildFallbackFinishChunk(String id, String model) {
        try {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);

            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("role", null);
            delta.put("content", null);

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", "stop");

            chunk.put("choices", List.of(choice));
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception e) {
            return "{}";
        }
    }
}
package com.kaixuan.copilot_ollama_proxy.provider.longcat.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.openai.UpstreamChatService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * LongCat 上游 OpenAI 实现 —— 将请求转发到 LongCat 的 OpenAI 兼容端点。
 * 所有运行时配置（API Key、Base URL）均从数据库读取。
 */
@Service
public class LongCatOpenAiChatService implements UpstreamChatService {

    private static final Logger log = LoggerFactory.getLogger(LongCatOpenAiChatService.class);

    private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE = new ParameterizedTypeReference<>() {
    };

    private final ProviderConfigRepository providerConfigRepository;
    private final ObjectMapper objectMapper;
    private final String fallbackDefaultModel;

    public LongCatOpenAiChatService(ProviderConfigRepository providerConfigRepository,
            @Value("${longcat.default-model:LongCat-Flash-Chat}") String fallbackDefaultModel,
            ObjectMapper objectMapper) {
        this.providerConfigRepository = providerConfigRepository;
        this.objectMapper = objectMapper;
        this.fallbackDefaultModel = fallbackDefaultModel;
    }

    /**
     * 从数据库读取 LongCat 配置并构建 WebClient。
     */
    private WebClient buildWebClient() {
        Map<String, Object> config = providerConfigRepository.findActiveProviderByKey("longcat");
        String apiKey = config != null ? (String) config.getOrDefault("apiKey", "") : "";
        String baseUrl = config != null ? (String) config.getOrDefault("baseUrl", "https://api.longcat.chat")
                : "https://api.longcat.chat";
        String normalizedUrl = baseUrl.replaceAll("/+$", "") + "/openai";
        return WebClient.builder().baseUrl(normalizedUrl).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
    }

    // ========== 路由支持 ==========

    @Override
    public String getProviderKey() {
        return "longcat";
    }

    @Override
    public boolean supportsModel(String modelName) {
        Map<String, Object> config = providerConfigRepository.findActiveProviderByKey("longcat");
        if (config == null) {
            return false;
        }
        int providerId = (Integer) config.get("id");
        List<Map<String, Object>> dbModels = providerConfigRepository.findModelsByProviderId(providerId);
        if (dbModels != null) {
            for (Map<String, Object> m : dbModels) {
                if (Boolean.TRUE.equals(m.get("enabled")) && modelName.equals(m.get("modelName"))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model) {
        Map<String, Object> requestBody = prepareRequestBody(openAiRequest, false, model);
        log.info("LongCat OpenAI 上游，模型: {}, 流式: false", requestBody.get("model"));

        return buildWebClient().post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody).retrieve().bodyToMono(String.class).retryWhen(buildRetrySpec("chatCompletion"))
                .doOnNext(response -> log.debug("LongCat 响应: {}", response));
    }

    @Override
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
        Map<String, Object> requestBody = prepareRequestBody(openAiRequest, true, model);
        log.info("LongCat OpenAI 上游，模型: {}, 流式: true", requestBody.get("model"));

        AtomicBoolean reasoningEmitted = new AtomicBoolean(false);
        AtomicBoolean contentEmitted = new AtomicBoolean(false);
        StringBuilder reasoningBuffer = new StringBuilder();
        AtomicReference<String> chunkId = new AtomicReference<>("chatcmpl-unknown");
        AtomicReference<String> finishReason = new AtomicReference<>(null);

        return buildWebClient().post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(requestBody).retrieve().bodyToFlux(STRING_SSE_TYPE)
                .retryWhen(buildRetrySpec("chatCompletionStream")).mapNotNull(ServerSentEvent::data)
                .filter(chunk -> !chunk.isBlank()).doOnNext(raw -> log.debug("LongCat 原始: {}", raw))
                .concatMap(chunk -> {
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
                }).doOnNext(chunk -> log.debug("LongCat 翻译: {}", chunk));
    }

    private Map<String, Object> prepareRequestBody(Map<String, Object> openAiRequest, boolean stream, String model) {
        Map<String, Object> body = new LinkedHashMap<>(openAiRequest);
        String resolvedModel = resolveModel(body.get("model"), model);
        body.put("model", resolvedModel);
        body.put("stream", stream);
        body.values().removeIf(Objects::isNull);
        return body;
    }

    private String resolveModel(Object requestModel, String fallbackModel) {
        if (requestModel instanceof String value && !value.isBlank()) {
            return value;
        }
        if (fallbackModel != null && !fallbackModel.isBlank()) {
            return fallbackModel;
        }
        return fallbackDefaultModel;
    }

    private Retry buildRetrySpec(String method) {
        return Retry.fixedDelay(5, Duration.ofSeconds(5))
                .filter(ex -> (ex instanceof WebClientResponseException
                        && (((WebClientResponseException) ex).getStatusCode().is5xxServerError()
                                || ((WebClientResponseException) ex).getStatusCode().value() == 400))
                        || ex instanceof WebClientRequestException)
                .doBeforeRetry(signal -> log.warn("[{}] LongCat API 调用失败，重试第 {} 次: {}", method,
                        signal.totalRetries() + 1, signal.failure().getMessage()));
    }

    @SuppressWarnings("unchecked")
    private String translateChunk(String chunkJson, AtomicBoolean reasoningEmitted, AtomicBoolean contentEmitted,
            StringBuilder reasoningBuffer, AtomicReference<String> chunkId, AtomicReference<String> finishReason) {
        try {
            Map<String, Object> chunk = objectMapper.readValue(chunkJson, Map.class);

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
                Object fr = choices.get(0).get("finish_reason");
                if (fr instanceof String reason) {
                    finishReason.set(reason);
                }
                return chunkJson;
            }

            Object contentObj = delta.get("content");
            if (contentObj instanceof String content && !content.isEmpty()) {
                contentEmitted.set(true);
            }

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

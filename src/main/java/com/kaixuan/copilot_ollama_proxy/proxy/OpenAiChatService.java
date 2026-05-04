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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
        log.info("OpenAI 上游直连，模型: {}, 流式: false", requestBody.get("model"));
        log.debug("OpenAI 请求体: {}", toLogJson(requestBody));

        return webClient.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody)
                .retrieve().bodyToMono(String.class).doOnNext(response -> log.debug("OpenAI 响应: {}", response));
    }

    @Override
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
        Map<String, Object> requestBody = prepareRequestBody(openAiRequest, true, model);
        log.info("OpenAI 上游直连，模型: {}, 流式: true", requestBody.get("model"));
        log.debug("OpenAI 请求体: {}", toLogJson(requestBody));

        return webClient.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(requestBody).retrieve().bodyToFlux(STRING_SSE_TYPE)
                .map(ServerSentEvent::data).filter(Objects::nonNull).filter(chunk -> !chunk.isBlank())
                .doOnNext(chunk -> log.debug("OpenAI chunk: {}", chunk));
    }

    private Map<String, Object> prepareRequestBody(Map<String, Object> openAiRequest, boolean stream, String model) {
        Map<String, Object> body = new LinkedHashMap<>(openAiRequest);
        body.put("model", resolveModel(body.get("model"), model));
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
        return defaultModel;
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
}
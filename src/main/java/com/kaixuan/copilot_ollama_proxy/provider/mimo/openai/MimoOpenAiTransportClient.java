package com.kaixuan.copilot_ollama_proxy.provider.mimo.openai;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
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
import java.util.Map;

/**
 * MiMo OpenAI 兼容传输客户端。
 * <p>
 * 只负责与 MiMo 的 OpenAI 兼容端点进行 HTTP 交互：
 * 构建 WebClient、应用鉴权头、处理默认 Base URL 以及统一的重试策略。
 */
@Service
public class MimoOpenAiTransportClient {
    private static final String PROVIDER_KEY = "mimo";
    private static final String DEFAULT_BASE_URL = "https://api.xiaomimimo.com";
    private static final String CHAT_COMPLETIONS_URI = "/v1/chat/completions";

    private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE = new ParameterizedTypeReference<>() {
    };

    private final RuntimeProviderCatalog runtimeProviderCatalog;
    private final WebClient.Builder webClientBuilder;

    public MimoOpenAiTransportClient(RuntimeProviderCatalog runtimeProviderCatalog, WebClient.Builder webClientBuilder) {
        this.runtimeProviderCatalog = runtimeProviderCatalog;
        this.webClientBuilder = webClientBuilder;
    }

    public Mono<String> sendChatCompletion(Map<String, Object> requestBody) {
        return buildWebClient().post().uri(CHAT_COMPLETIONS_URI).contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody).retrieve().bodyToMono(String.class).retryWhen(buildRetrySpec("chat"));
    }

    public Flux<String> streamChatCompletion(Map<String, Object> requestBody) {
        return buildWebClient().post().uri(CHAT_COMPLETIONS_URI).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).bodyValue(requestBody).retrieve()
                .bodyToFlux(STRING_SSE_TYPE).retryWhen(buildRetrySpec("chatStream")).mapNotNull(ServerSentEvent::data).filter(chunk -> !chunk.isBlank());
    }

    private WebClient buildWebClient() {
        ProviderRuntimeConfiguration config = runtimeProviderCatalog.getActiveProvider(PROVIDER_KEY);
        String baseUrl = (config != null && config.baseUrl() != null && !config.baseUrl().isBlank()) ? normalizeBaseUrl(config.baseUrl()) : DEFAULT_BASE_URL + "/v1";
        String apiKey = (config != null) ? config.apiKey() : "";

        return webClientBuilder.clone().baseUrl(baseUrl).defaultHeaders(headers -> applyAuthHeaders(headers, apiKey)).build();
    }

    private String normalizeBaseUrl(String rawBaseUrl) {
        String normalized = rawBaseUrl.trim();
        normalized = normalized.replaceAll("/+$", "");
        if (!normalized.endsWith("/v1")) {
            normalized = normalized + "/v1";
        }
        return normalized;
    }

    private void applyAuthHeaders(HttpHeaders headers, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("api-key", apiKey);
            headers.set("x-api-key", apiKey);
        }
    }

    private Retry buildRetrySpec(String operation) {
        return Retry.backoff(2, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(5))
                .filter(ex -> ex instanceof WebClientResponseException.TooManyRequests || ex instanceof WebClientResponseException.ServiceUnavailable || ex instanceof WebClientRequestException)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }
}
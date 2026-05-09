package com.kaixuan.copilot_ollama_proxy.provider.deepseek.openai;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * DeepSeek OpenAI 兼容传输客户端。
 * <p>
 * 负责与 DeepSeek 的 OpenAI 兼容端点进行 HTTP 交互：
 * 构建 WebClient、应用鉴权头、处理默认 Base URL 以及统一的重试策略。
 */
@Service
public class DeepSeekOpenAiTransportClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekOpenAiTransportClient.class);

    private static final String PROVIDER_KEY = "deepseek";
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String CHAT_COMPLETIONS_URI = "/chat/completions";

    private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE = new ParameterizedTypeReference<>() {
    };

    private final RuntimeProviderCatalog runtimeProviderCatalog;
    private final WebClient.Builder webClientBuilder;

    public DeepSeekOpenAiTransportClient(RuntimeProviderCatalog runtimeProviderCatalog, WebClient.Builder webClientBuilder) {
        this.runtimeProviderCatalog = runtimeProviderCatalog;
        this.webClientBuilder = webClientBuilder;
    }

    /**
     * 发送一次非流式 Chat Completions 请求。
     */
    public Mono<String> sendChatCompletion(Map<String, Object> requestBody) {
        return buildWebClient().post().uri(CHAT_COMPLETIONS_URI).contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody).retrieve().bodyToMono(String.class).retryWhen(buildRetrySpec("chat"));
    }

    /**
     * 发送一次流式 Chat Completions 请求。
     */
    public Flux<String> streamChatCompletion(Map<String, Object> requestBody) {
        return buildWebClient().post().uri(CHAT_COMPLETIONS_URI).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).bodyValue(requestBody).retrieve()
                .bodyToFlux(STRING_SSE_TYPE).retryWhen(buildRetrySpec("chatStream")).mapNotNull(ServerSentEvent::data).filter(chunk -> !chunk.isBlank());
    }

    private WebClient buildWebClient() {
        ProviderRuntimeConfiguration config = runtimeProviderCatalog.getActiveProvider(PROVIDER_KEY);
        String apiKey = config != null ? config.apiKey() : "";
        String normalizedBaseUrl = resolveBaseUrl(config);

        return webClientBuilder.clone().baseUrl(normalizedBaseUrl).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
    }

    private String resolveBaseUrl(ProviderRuntimeConfiguration config) {
        return (config == null || config.baseUrl().isBlank()) ? DEFAULT_BASE_URL : config.baseUrl().replaceAll("/+$", "");
    }

    private Retry buildRetrySpec(String method) {
        return Retry.fixedDelay(5, Duration.ofSeconds(5))
                .filter(exception -> (exception instanceof WebClientResponseException responseException
                        && (responseException.getStatusCode().is5xxServerError() || responseException.getStatusCode().value() == 400 || hasNetworkCause(responseException)))
                        || exception instanceof WebClientRequestException)
                .doBeforeRetry(signal -> log.warn("[{}] DeepSeek API 调用失败，重试第 {} 次: {}", method, signal.totalRetries() + 1, signal.failure().getMessage()));
    }

    private static boolean hasNetworkCause(Throwable throwable) {
        Throwable cause = throwable.getCause();
        while (cause != null) {
            if (cause instanceof java.io.IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
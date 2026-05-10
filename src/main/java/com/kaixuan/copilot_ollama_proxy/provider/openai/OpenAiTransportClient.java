package com.kaixuan.copilot_ollama_proxy.provider.openai;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 通用的 OpenAI 兼容传输客户端。
 * <p>
 * 封装了与任意 OpenAI 兼容端点进行 HTTP 交互的公共逻辑：
 * WebClient 构建、鉴权头注入、Base URL 规范化以及统一的重试策略。
 * 各 provider 的特化行为通过 {@link Config} 配置注入。
 * <p>
 * 此类不是 Spring Bean，由各 OllamaService 自行实例化。
 */
public class OpenAiTransportClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTransportClient.class);

    private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE = new ParameterizedTypeReference<>() {
    };

    private final RuntimeProviderCatalog runtimeProviderCatalog;
    private final WebClient.Builder webClientBuilder;
    private final Config config;

    /**
     * @param runtimeProviderCatalog 运行时 provider 配置目录
     * @param webClientBuilder       Spring 注入的 WebClient 构建器
     * @param config                 provider 特化配置
     */
    public OpenAiTransportClient(RuntimeProviderCatalog runtimeProviderCatalog, WebClient.Builder webClientBuilder, Config config) {
        this.runtimeProviderCatalog = Objects.requireNonNull(runtimeProviderCatalog, "runtimeProviderCatalog");
        this.webClientBuilder = Objects.requireNonNull(webClientBuilder, "webClientBuilder");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * 发送一次非流式 Chat Completions 请求。
     */
    public Mono<String> sendChatCompletion(Map<String, Object> requestBody) {
        return buildWebClient().post().uri(config.chatCompletionsUri).contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody).retrieve().bodyToMono(String.class)
                .retryWhen(buildRetrySpec("chat"));
    }

    /**
     * 发送一次流式 Chat Completions 请求。
     */
    public Flux<String> streamChatCompletion(Map<String, Object> requestBody) {
        return buildWebClient().post().uri(config.chatCompletionsUri).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).bodyValue(requestBody).retrieve()
                .bodyToFlux(STRING_SSE_TYPE).retryWhen(buildRetrySpec("chatStream")).mapNotNull(ServerSentEvent::data).filter(chunk -> !chunk.isBlank());
    }

    private WebClient buildWebClient() {
        ProviderRuntimeConfiguration providerConfig = runtimeProviderCatalog.getActiveProvider(config.providerKey);
        String apiKey = providerConfig != null ? providerConfig.apiKey() : "";
        String baseUrl = resolveBaseUrl(providerConfig);

        return webClientBuilder.clone().baseUrl(baseUrl).defaultHeaders(headers -> {
            config.authHeaderApplier.accept(headers, apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
        }).build();
    }

    private String resolveBaseUrl(ProviderRuntimeConfiguration providerConfig) {
        String raw = (providerConfig == null || providerConfig.baseUrl().isBlank()) ? config.defaultBaseUrl : providerConfig.baseUrl();
        return config.baseUrlNormalizer.apply(raw);
    }

    private Retry buildRetrySpec(String method) {
        return Retry.fixedDelay(5, Duration.ofSeconds(5))
                .filter(ex -> (ex instanceof WebClientResponseException responseException
                        && (responseException.getStatusCode().is5xxServerError() || responseException.getStatusCode().value() == 400 || hasNetworkCause(responseException)))
                        || ex instanceof WebClientRequestException)
                .doBeforeRetry(signal -> log.warn("[{}] {} API 调用失败，重试第 {} 次: {}", method, config.providerKey, signal.totalRetries() + 1, signal.failure().getMessage()));
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

    /**
     * Provider 特化配置。
     *
     * @param providerKey        服务商标识
     * @param defaultBaseUrl     默认 Base URL
     * @param chatCompletionsUri Chat Completions 端点 URI
     * @param authHeaderApplier  认证头注入逻辑
     * @param baseUrlNormalizer  Base URL 规范化逻辑
     */
    public record Config(String providerKey, String defaultBaseUrl, String chatCompletionsUri, BiConsumer<HttpHeaders, String> authHeaderApplier, Function<String, String> baseUrlNormalizer) {

        public Config {
            Objects.requireNonNull(providerKey, "providerKey");
            Objects.requireNonNull(defaultBaseUrl, "defaultBaseUrl");
            Objects.requireNonNull(chatCompletionsUri, "chatCompletionsUri");
            Objects.requireNonNull(authHeaderApplier, "authHeaderApplier");
            Objects.requireNonNull(baseUrlNormalizer, "baseUrlNormalizer");
        }
    }
}
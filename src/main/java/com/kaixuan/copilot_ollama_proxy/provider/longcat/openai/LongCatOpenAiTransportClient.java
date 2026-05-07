package com.kaixuan.copilot_ollama_proxy.provider.longcat.openai;

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
 * LongCat OpenAI 兼容传输客户端。
 * <p>
 * 这个类只负责与 LongCat 的 OpenAI 兼容端点进行 HTTP 交互：
 * 构建 WebClient、应用鉴权头、处理默认 Base URL 以及统一的重试策略。
 * 具体的 Ollama/OpenAI 协议转换不在这里处理，仍然由上层的 converter 和 translator 负责。
 */
@Service
public class LongCatOpenAiTransportClient {

    private static final Logger log = LoggerFactory.getLogger(LongCatOpenAiTransportClient.class);

    private static final String PROVIDER_KEY = "longcat";
    private static final String DEFAULT_BASE_URL = "https://api.longcat.chat";
    private static final String CHAT_COMPLETIONS_URI = "/v1/chat/completions";

    /**
     * SSE 场景下，LongCat 返回的每一行 data 都是一个 OpenAI chunk JSON 或 [DONE] 标记。
     */
    private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE = new ParameterizedTypeReference<>() {
    };

    private final RuntimeProviderCatalog runtimeProviderCatalog;
    private final WebClient.Builder webClientBuilder;

    /**
     * @param runtimeProviderCatalog 运行时 provider 配置目录，用于读取 API Key 与 Base URL
     * @param webClientBuilder Spring 注入的 WebClient 构建器，用于创建针对 LongCat 的客户端实例
     */
    public LongCatOpenAiTransportClient(RuntimeProviderCatalog runtimeProviderCatalog, WebClient.Builder webClientBuilder) {
        this.runtimeProviderCatalog = runtimeProviderCatalog;
        this.webClientBuilder = webClientBuilder;
    }

    /**
     * 发送一次非流式 Chat Completions 请求。
     *
     * @param requestBody 已经完成协议转换的 OpenAI 请求体
     * @return LongCat 返回的原始 OpenAI JSON 字符串
     */
    public Mono<String> sendChatCompletion(Map<String, Object> requestBody) {
        return buildWebClient().post().uri(CHAT_COMPLETIONS_URI).contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody)
                .retrieve().bodyToMono(String.class).retryWhen(buildRetrySpec("chat"));
    }

    /**
     * 发送一次流式 Chat Completions 请求。
     * <p>
     * 该方法会把 HTTP 层的 SSE 事件解包为纯 data 字符串，
     * 这样上层 translator 只需要处理协议 chunk，而不需要再关心 SSE 包装细节。
     *
     * @param requestBody 已经完成协议转换的 OpenAI 请求体
     * @return 按顺序发出的 chunk data，可能是 OpenAI chunk JSON 或 [DONE]
     */
    public Flux<String> streamChatCompletion(Map<String, Object> requestBody) {
        return buildWebClient().post().uri(CHAT_COMPLETIONS_URI).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(requestBody).retrieve().bodyToFlux(STRING_SSE_TYPE)
                .retryWhen(buildRetrySpec("chatStream")).mapNotNull(ServerSentEvent::data).filter(chunk -> !chunk.isBlank());
    }

    /**
     * 根据运行时配置动态构建 LongCat 专用 WebClient。
     * <p>
     * 这里保留了一个重要兜底：当数据库中的 baseUrl 为空字符串时，仍然回退到官方默认地址，
     * 避免运行时目录把 null 规整成空串后产生无效 URL。
     */
    private WebClient buildWebClient() {
        ProviderRuntimeConfiguration config = runtimeProviderCatalog.getActiveProvider(PROVIDER_KEY);
        String apiKey = config != null ? config.apiKey() : "";
        String normalizedBaseUrl = resolveBaseUrl(config);

        return webClientBuilder.clone().baseUrl(normalizedBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
    }

    /**
     * 统一解析 LongCat 的上游地址，并保证最终地址始终指向 OpenAI 兼容前缀。
     */
    private String resolveBaseUrl(ProviderRuntimeConfiguration config) {
        String baseUrl = (config == null || config.baseUrl().isBlank()) ? DEFAULT_BASE_URL : config.baseUrl();
        return baseUrl.replaceAll("/+$", "") + "/openai";
    }

    /**
     * LongCat 传输层的统一重试策略。
     * <p>
     * 这里只覆盖典型的上游瞬时失败场景：
     * 5xx、LongCat 某些可重试的 400，以及底层网络连接异常。
     */
    private Retry buildRetrySpec(String method) {
        return Retry.fixedDelay(5, Duration.ofSeconds(5))
                .filter(exception -> (exception instanceof WebClientResponseException responseException
                        && (responseException.getStatusCode().is5xxServerError()
                                || responseException.getStatusCode().value() == 400))
                        || exception instanceof WebClientRequestException)
                .doBeforeRetry(signal -> log.warn("[{}] LongCat API 调用失败，重试第 {} 次: {}", method,
                        signal.totalRetries() + 1, signal.failure().getMessage()));
    }
}
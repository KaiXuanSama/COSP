package com.kaixuan.copilot_ollama_proxy.provider.mimo.anthropic;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * MiMo Anthropic 传输客户端。
 * <p>
 * 这个类只处理与 MiMo Anthropic 兼容端点的 HTTP 交互：
 * 运行时 Base URL 解析、鉴权头注入、流式事件请求以及统一的重试策略。
 * 协议级请求/响应转换仍然由上层 service 和 converter 负责。
 */
@Service
public class MimoAnthropicClient {

    private static final Logger log = LoggerFactory.getLogger(MimoAnthropicClient.class);

    private static final String PROVIDER_KEY = "mimo";
    private static final String DEFAULT_BASE_URL = "https://token-plan-cn.xiaomimimo.com/anthropic";
    private static final String MESSAGES_URI = "/v1/messages";

    private final RuntimeProviderCatalog runtimeProviderCatalog;
    private final WebClient.Builder webClientBuilder;

    /**
     * @param runtimeProviderCatalog 运行时 provider 配置目录，用于读取 MiMo 的 API Key 与 Base URL
     * @param webClientBuilder Spring 注入的 WebClient 构建器，用于创建 MiMo 专用客户端实例
     */
    public MimoAnthropicClient(RuntimeProviderCatalog runtimeProviderCatalog, WebClient.Builder webClientBuilder) {
        this.runtimeProviderCatalog = runtimeProviderCatalog;
        this.webClientBuilder = webClientBuilder;
    }

    /**
     * 从运行时配置动态构建 MiMo Anthropic 客户端。
     * <p>
     * 这里显式处理 blank baseUrl 的回退逻辑，避免运行时目录把 null 规整为空串后拼出无效地址。
     */
    private WebClient buildWebClient() {
        ProviderRuntimeConfiguration config = runtimeProviderCatalog.getActiveProvider(PROVIDER_KEY);
        String apiKey = config != null ? config.apiKey() : "";
        String normalizedBaseUrl = resolveBaseUrl(config);

        return webClientBuilder.clone().baseUrl(normalizedBaseUrl).defaultHeader("x-api-key", apiKey).defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
    }

    /**
     * 统一解析 MiMo 的上游地址。
     */
    private String resolveBaseUrl(ProviderRuntimeConfiguration config) {
        String baseUrl = (config == null || config.baseUrl().isBlank()) ? DEFAULT_BASE_URL : config.baseUrl();
        return baseUrl.replaceAll("/+$", "");
    }

    /**
     * 发送一次非流式 Anthropic Messages 请求，并返回原始 JSON 响应。
     */
    public Mono<String> sendMessage(Map<String, Object> requestBody) {
        return postMessages(requestBody).retrieve().bodyToMono(String.class).retryWhen(buildRetrySpec("sendMessage"));
    }

    /**
     * 发送一次非流式 Anthropic Messages 请求，并直接反序列化为 DTO。
     */
    public Mono<AnthropicResponse> sendMessage(AnthropicRequest requestBody) {
        return postMessages(requestBody).retrieve().bodyToMono(AnthropicResponse.class).retryWhen(buildRetrySpec("sendMessageDto"));
    }

    /**
     * 发送一次流式 Anthropic Messages 请求。
     * <p>
     * 这里会过滤掉没有 type 的空事件，避免上层 translator 再处理协议噪声。
     */
    public Flux<AnthropicStreamEvent> streamMessages(Map<String, Object> requestBody) {
        return streamMessagesInternal(requestBody);
    }

    /**
     * 发送一次流式 Anthropic Messages 请求，并把事件流反序列化为 DTO。
     */
    public Flux<AnthropicStreamEvent> streamMessages(AnthropicRequest requestBody) {
        return streamMessagesInternal(requestBody);
    }

    /**
     * 统一构建 MiMo Messages POST 请求，避免四个公开方法重复拼装 HTTP 细节。
     */
    private WebClient.RequestHeadersSpec<?> postMessages(Object requestBody) {
        return buildWebClient().post().uri(MESSAGES_URI).contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody);
    }

    private Flux<AnthropicStreamEvent> streamMessagesInternal(Object requestBody) {
        return postMessages(requestBody).accept(MediaType.TEXT_EVENT_STREAM).retrieve().bodyToFlux(AnthropicStreamEvent.class).retryWhen(buildRetrySpec("streamMessages"))
                .filter(event -> event.getType() != null);
    }

    /**
     * MiMo 传输层的统一重试策略。
     * <p>
     * 覆盖三类可恢复场景：
     * <ol>
     *   <li>429 限流、5xx 服务端错误</li>
     *   <li>网络层异常：包括 WebClientRequestException（连接建立失败）
     *       以及 WebClientResponseException 的 cause chain 中的 IOException
     *      （如 SocketException: Connection reset，即 HTTP 200 但 SSE 流中途断开）</li>
     * </ol>
     */
    private Retry buildRetrySpec(String method) {
        return Retry.fixedDelay(5, Duration.ofSeconds(5))
                .filter(exception -> (exception instanceof WebClientResponseException responseException
                        && (responseException.getStatusCode().value() == 429 || responseException.getStatusCode().is5xxServerError() || hasNetworkCause(responseException)))
                        || exception instanceof WebClientRequestException)
                .doBeforeRetry(signal -> log.warn("[{}] MiMo API 调用失败，重试第 {} 次: {}", method, signal.totalRetries() + 1, signal.failure().getMessage()));
    }

    /**
     * 判断异常的 cause chain 中是否包含网络层异常（IOException 及其子类，如 SocketException）。
     * <p>
     * 这类异常通常表现为 HTTP 200 但 SSE 流中途断开，需要重试。
     */
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

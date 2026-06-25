package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * 通用 OpenAI 上游服务 —— 处理所有 custom-* 前缀的自定义供应商。
 * 从数据库动态读取配置，按标准 OpenAI 兼容协议转发请求。
 */
@Service
public class GenericOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    private static final String PROVIDER_KEY = "__generic__";
    private final RuntimeProviderCatalog runtimeProviderCatalog;

    public GenericOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog, ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, objectMapper, "");
        this.runtimeProviderCatalog = runtimeProviderCatalog;
    }

    @Override
    public String getProviderKey() {
        return PROVIDER_KEY;
    }

    @Override
    protected String providerDisplayName() {
        return "Generic";
    }

    @Override
    protected String defaultBaseUrl() {
        return "";
    }

    @Override
    protected String normalizeBaseUrl(String rawBaseUrl) {
        return rawBaseUrl.replaceAll("/+$", "");
    }

    @Override
    protected void applyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
    }

    @Override
    protected String chatCompletionsUri() {
        return "/chat/completions";
    }

    /**
     * 判断此服务是否能处理给定的 providerKey。
     */
    public boolean supports(String providerKey) {
        return providerKey != null && providerKey.startsWith("custom-");
    }

    /**
     * 重写请求准备，动态解析实际的 providerKey 和 Base URL。
     */
    @Override
    protected WebClient buildWebClient() {
        // 这个方法在父类中被调用，但我们需要动态解析 providerKey
        // 实际的 WebClient 构建会在 chatCompletion 和 chatCompletionStream 中通过重写处理
        return WebClient.builder().build();
    }

    /**
     * 重写非流式聊天，动态解析 provider 配置。
     */
    @Override
    public reactor.core.publisher.Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model) {
        String providerKey = resolveProviderKey(model);
        ProviderRuntimeConfiguration config = runtimeProviderCatalog.getActiveProvider(providerKey);
        if (config == null) {
            return reactor.core.publisher.Mono.error(
                    new IllegalStateException("找不到自定义供应商配置: " + providerKey));
        }
        String baseUrl = normalizeBaseUrl(config.baseUrl());
        String apiKey = config.apiKey();

        WebClient client = WebClient.builder()
                .baseUrl(baseUrl + chatCompletionsUri())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();

        return client.post()
                .bodyValue(openAiRequest)
                .retrieve()
                .bodyToMono(String.class);
    }

    /**
     * 重写流式聊天，动态解析 provider 配置。
     */
    @Override
    public reactor.core.publisher.Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
        String providerKey = resolveProviderKey(model);
        ProviderRuntimeConfiguration config = runtimeProviderCatalog.getActiveProvider(providerKey);
        if (config == null) {
            return reactor.core.publisher.Flux.error(
                    new IllegalStateException("找不到自定义供应商配置: " + providerKey));
        }
        String baseUrl = normalizeBaseUrl(config.baseUrl());
        String apiKey = config.apiKey();

        WebClient client = WebClient.builder()
                .baseUrl(baseUrl + chatCompletionsUri())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();

        return client.post()
                .bodyValue(openAiRequest)
                .retrieve()
                .bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                })
                .flatMap(chunk -> {
                    // 解析 SSE 格式
                    String[] lines = chunk.split("\n");
                    reactor.core.publisher.Flux<String> result = reactor.core.publisher.Flux.empty();
                    for (String line : lines) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            if (!data.isEmpty()) {
                                result = result.concatWith(reactor.core.publisher.Mono.just(data));
                            }
                        }
                    }
                    return result;
                });
    }

    private String resolveProviderKey(String model) {
        var parsed = ModelNameUtil.parse(model);
        if (parsed.hasProviderPrefix()) {
            String key = parsed.providerKey().toLowerCase();
            if (runtimeProviderCatalog.getActiveProvider(key) != null) {
                return key;
            }
            String customKey = "custom-" + key;
            if (runtimeProviderCatalog.getActiveProvider(customKey) != null) {
                return customKey;
            }
        }
        // 无前缀时搜索
        for (var provider : runtimeProviderCatalog.getActiveProviders()) {
            if (provider.providerKey().startsWith("custom-") && provider.supportsModel(parsed.modelName())) {
                return provider.providerKey();
            }
        }
        return null;
    }
}

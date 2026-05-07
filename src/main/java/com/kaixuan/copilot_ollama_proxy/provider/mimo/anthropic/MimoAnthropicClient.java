package com.kaixuan.copilot_ollama_proxy.provider.mimo.anthropic;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicStreamEvent;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * MiMo Anthropic 协议客户端 —— 所有配置从数据库动态读取。
 */
@Service
public class MimoAnthropicClient {

    private final ProviderConfigRepository providerConfigRepository;

    public MimoAnthropicClient(ProviderConfigRepository providerConfigRepository) {
        this.providerConfigRepository = providerConfigRepository;
    }

    /**
     * 从数据库读取 MiMo 配置并构建 WebClient。
     */
    private WebClient buildWebClient() {
        Map<String, Object> config = providerConfigRepository.findActiveProviderByKey("mimo");
        String apiKey = config != null ? (String) config.getOrDefault("apiKey", "") : "";
        String baseUrl = config != null
                ? (String) config.getOrDefault("baseUrl", "https://token-plan-cn.xiaomimimo.com/anthropic")
                : "https://token-plan-cn.xiaomimimo.com/anthropic";
        String normalizedUrl = baseUrl.replaceAll("/+$", "");
        return WebClient.builder().baseUrl(normalizedUrl).defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE).build();
    }

    public Mono<String> sendMessage(Map<String, Object> requestBody) {
        return buildWebClient().post().uri("/v1/messages").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody).retrieve().bodyToMono(String.class);
    }

    public Mono<AnthropicResponse> sendMessage(AnthropicRequest requestBody) {
        return buildWebClient().post().uri("/v1/messages").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody).retrieve().bodyToMono(AnthropicResponse.class);
    }

    public Flux<AnthropicStreamEvent> streamMessages(Map<String, Object> requestBody) {
        return buildWebClient().post().uri("/v1/messages").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody).retrieve().bodyToFlux(AnthropicStreamEvent.class)
                .filter(event -> event.getType() != null);
    }

    public Flux<AnthropicStreamEvent> streamMessages(AnthropicRequest requestBody) {
        return buildWebClient().post().uri("/v1/messages").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody).retrieve().bodyToFlux(AnthropicStreamEvent.class)
                .filter(event -> event.getType() != null);
    }
}

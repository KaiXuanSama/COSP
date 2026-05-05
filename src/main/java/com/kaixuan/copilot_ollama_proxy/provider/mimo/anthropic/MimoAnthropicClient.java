package com.kaixuan.copilot_ollama_proxy.provider.mimo.anthropic;

import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicStreamEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class MimoAnthropicClient {

    private final WebClient webClient;

    public MimoAnthropicClient(@Value("${mimo.api-key}") String apiKey, @Value("${mimo.base-url}") String baseUrl) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE).build();
    }

    public Mono<String> sendMessage(Map<String, Object> requestBody) {
        return webClient.post().uri("/v1/messages").contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody)
                .retrieve().bodyToMono(String.class);
    }

    public Mono<AnthropicResponse> sendMessage(AnthropicRequest requestBody) {
        return webClient.post().uri("/v1/messages").contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody)
                .retrieve().bodyToMono(AnthropicResponse.class);
    }

    public Flux<AnthropicStreamEvent> streamMessages(Map<String, Object> requestBody) {
        return webClient.post().uri("/v1/messages").contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody)
                .retrieve().bodyToFlux(AnthropicStreamEvent.class).filter(event -> event.getType() != null);
    }

    public Flux<AnthropicStreamEvent> streamMessages(AnthropicRequest requestBody) {
        return webClient.post().uri("/v1/messages").contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody)
                .retrieve().bodyToFlux(AnthropicStreamEvent.class).filter(event -> event.getType() != null);
    }
}
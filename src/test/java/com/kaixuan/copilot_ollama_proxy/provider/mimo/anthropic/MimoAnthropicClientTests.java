package com.kaixuan.copilot_ollama_proxy.provider.mimo.anthropic;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicStreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MimoAnthropicClientTests {

    /**
     * 验证 MiMo transport client 会回退到默认 Anthropic Base URL，并附带鉴权头。
     */
    @Test
    void sendsMessageWithRuntimeHeadersAndFallbackBaseUrl() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return reactor.core.publisher.Mono.just(ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body("{\"id\":\"msg_1\"}").build());
        };

        RuntimeProviderCatalog catalog = () -> List.of(new ProviderRuntimeConfiguration("mimo", "", "secret-key", "anthropic", List.of()));
        MimoAnthropicClient client = new MimoAnthropicClient(catalog, WebClient.builder().exchangeFunction(exchangeFunction));

        String response = client.sendMessage(Map.of("model", "mimo-v2.5-pro")).block();

        assertThat(response).contains("\"id\":\"msg_1\"");
        assertThat(capturedRequest.get()).isNotNull();
        assertThat(capturedRequest.get().url().toString()).isEqualTo("https://token-plan-cn.xiaomimimo.com/anthropic/v1/messages");
        assertThat(capturedRequest.get().headers().getFirst("x-api-key")).isEqualTo("secret-key");
        assertThat(capturedRequest.get().headers().getFirst("anthropic-version")).isEqualTo("2023-06-01");
    }

    /**
     * 验证流式请求会声明 text/event-stream，并过滤掉没有 type 的空事件。
     */
    @Test
    void streamsTypedEventsAndFiltersEmptyOnes() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return reactor.core.publisher.Mono.just(ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_NDJSON_VALUE)
                    .body("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}\n{\"delta\":{\"type\":\"text_delta\",\"text\":\"skip\"}}\n").build());
        };

        RuntimeProviderCatalog catalog = () -> List.of(new ProviderRuntimeConfiguration("mimo", "https://custom.mimo.example/anthropic/", "secret-key", "anthropic", List.of()));
        MimoAnthropicClient client = new MimoAnthropicClient(catalog, WebClient.builder().exchangeFunction(exchangeFunction));

        AnthropicRequest request = new AnthropicRequest();
        request.setModel("mimo-v2.5-pro");

        List<AnthropicStreamEvent> events = client.streamMessages(request).collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo("content_block_delta");
        assertThat(capturedRequest.get()).isNotNull();
        assertThat(capturedRequest.get().url().toString()).isEqualTo("https://custom.mimo.example/anthropic/v1/messages");
        assertThat(capturedRequest.get().headers().getAccept()).contains(MediaType.TEXT_EVENT_STREAM);
    }
}
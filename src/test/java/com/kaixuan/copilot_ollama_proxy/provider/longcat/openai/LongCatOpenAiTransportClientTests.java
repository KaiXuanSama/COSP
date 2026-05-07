package com.kaixuan.copilot_ollama_proxy.provider.longcat.openai;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
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

class LongCatOpenAiTransportClientTests {

        /**
         * 验证 transport client 会正确应用默认 Base URL，并附带 Bearer 鉴权头。
         */
        @Test
        void sendsChatCompletionWithRuntimeHeadersAndFallbackBaseUrl() {
                AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
                ExchangeFunction exchangeFunction = request -> {
                        capturedRequest.set(request);
                        return reactor.core.publisher.Mono.just(ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body("{\"ok\":true}").build());
                };

                RuntimeProviderCatalog catalog = () -> List.of(new ProviderRuntimeConfiguration("longcat", "", "secret-key", "openai", List.of()));
                LongCatOpenAiTransportClient client = new LongCatOpenAiTransportClient(catalog, WebClient.builder().exchangeFunction(exchangeFunction));

                String response = client.sendChatCompletion(Map.of("model", "LongCat-Flash-Chat")).block();

                assertThat(response).contains("\"ok\":true");
                assertThat(capturedRequest.get()).isNotNull();
                assertThat(capturedRequest.get().url().toString()).isEqualTo("https://api.longcat.chat/openai/v1/chat/completions");
                assertThat(capturedRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer secret-key");
        }

        /**
         * 验证流式请求会声明 text/event-stream，并把 SSE data 还原为原始 chunk 字符串。
         */
        @Test
        void streamsSseChunkDataAsPlainStrings() {
                AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
                ExchangeFunction exchangeFunction = request -> {
                        capturedRequest.set(request);
                        return reactor.core.publisher.Mono.just(ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                                        .body("data: {\"chunk\":1}\n\ndata: [DONE]\n\n").build());
                };

                RuntimeProviderCatalog catalog = () -> List.of(new ProviderRuntimeConfiguration("longcat", "https://custom.longcat.example/", "secret-key", "openai", List.of()));
                LongCatOpenAiTransportClient client = new LongCatOpenAiTransportClient(catalog, WebClient.builder().exchangeFunction(exchangeFunction));

                List<String> chunks = client.streamChatCompletion(Map.of("model", "LongCat-Flash-Chat")).collectList().block();

                assertThat(chunks).containsExactly("{\"chunk\":1}", "[DONE]");
                assertThat(capturedRequest.get()).isNotNull();
                assertThat(capturedRequest.get().url().toString()).isEqualTo("https://custom.longcat.example/openai/v1/chat/completions");
                assertThat(capturedRequest.get().headers().getAccept()).contains(MediaType.TEXT_EVENT_STREAM);
        }
}
package com.kaixuan.copilot_ollama_proxy.application.ollama;

import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaTagsResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class CompositeOllamaServiceTests {

    @Test
    void aggregatesModelListsWithoutFailingWholeResponseWhenOneProviderErrors() {
        OllamaService okService = new StubOllamaService("mimo", tags("mimo-v2.5-pro"));
        OllamaService brokenService = mock(OllamaService.class);
        given(brokenService.getProviderKey()).willReturn("longcat");
        given(brokenService.listModels()).willReturn(Mono.error(new IllegalStateException("boom")));

        OllamaServiceResolver resolver = mock(OllamaServiceResolver.class);
        CompositeOllamaService composite = new CompositeOllamaService(List.of(okService, brokenService), resolver);

        OllamaTagsResponse response = composite.listModels().block();

        assertThat(response).isNotNull();
        assertThat(response.getModels()).hasSize(1);
        assertThat(response.getModels().get(0).getName()).isEqualTo("mimo-v2.5-pro");
    }

    private OllamaTagsResponse tags(String... names) {
        OllamaTagsResponse response = new OllamaTagsResponse();
        response.setModels(java.util.Arrays.stream(names).map(name -> {
            OllamaTagsResponse.ModelInfo info = new OllamaTagsResponse.ModelInfo();
            info.setName(name);
            info.setModel(name);
            return info;
        }).toList());
        return response;
    }

    private static final class StubOllamaService implements OllamaService {

        private final String providerKey;
        private final OllamaTagsResponse tagsResponse;

        private StubOllamaService(String providerKey, OllamaTagsResponse tagsResponse) {
            this.providerKey = providerKey;
            this.tagsResponse = tagsResponse;
        }

        @Override
        public String getProviderKey() {
            return providerKey;
        }

        @Override
        public boolean supportsModel(String modelName) {
            return false;
        }

        @Override
        public Mono<OllamaTagsResponse> listModels() {
            return Mono.just(tagsResponse);
        }

        @Override
        public OllamaShowResponse showModel(String modelName) {
            return null;
        }

        @Override
        public Mono<OllamaChatResponse> chat(OllamaChatRequest request) {
            return Mono.empty();
        }

        @Override
        public Flux<OllamaChatResponse> chatStream(OllamaChatRequest request) {
            return Flux.empty();
        }
    }
}
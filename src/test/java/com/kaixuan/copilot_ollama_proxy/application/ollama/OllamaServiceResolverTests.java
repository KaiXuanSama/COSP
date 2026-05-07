package com.kaixuan.copilot_ollama_proxy.application.ollama;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
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

class OllamaServiceResolverTests {

    @Test
    void resolvesServiceByProviderKeyFromRuntimeCatalog() {
        RuntimeProviderCatalog catalog = mock(RuntimeProviderCatalog.class);
        given(catalog.getActiveProviders()).willReturn(List.of(provider("mimo", "mimo-v2.5-pro")));

        OllamaService mimo = new StubOllamaService("mimo");
        OllamaService longcat = new StubOllamaService("longcat");

        OllamaServiceResolver resolver = new OllamaServiceResolver(catalog, List.of(longcat, mimo));

        assertThat(resolver.resolve("mimo-v2.5-pro")).isSameAs(mimo);
    }

    @Test
    void fallsBackToFirstRegisteredServiceWhenModelIsUnknown() {
        RuntimeProviderCatalog catalog = mock(RuntimeProviderCatalog.class);
        given(catalog.getActiveProviders()).willReturn(List.of(provider("mimo", "mimo-v2.5-pro")));

        OllamaService fallback = new StubOllamaService("longcat");
        OllamaService second = new StubOllamaService("mimo");

        OllamaServiceResolver resolver = new OllamaServiceResolver(catalog, List.of(fallback, second));

        assertThat(resolver.resolve("unknown-model")).isSameAs(fallback);
    }

    private ProviderRuntimeConfiguration provider(String providerKey, String... modelNames) {
        List<ProviderRuntimeModel> models = java.util.Arrays.stream(modelNames).map(modelName -> new ProviderRuntimeModel(modelName, 0, false, false)).toList();
        return new ProviderRuntimeConfiguration(providerKey, "", "", "openai", models);
    }

    private static final class StubOllamaService implements OllamaService {

        private final String providerKey;

        private StubOllamaService(String providerKey) {
            this.providerKey = providerKey;
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
            return Mono.empty();
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
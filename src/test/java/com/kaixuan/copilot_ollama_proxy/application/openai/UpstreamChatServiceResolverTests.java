package com.kaixuan.copilot_ollama_proxy.application.openai;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class UpstreamChatServiceResolverTests {

    @Test
    void resolvesOpenAiImplementationByProviderKeyAndApiFormat() {
        RuntimeProviderCatalog catalog = mock(RuntimeProviderCatalog.class);
        given(catalog.getActiveProviders()).willReturn(List.of(provider("mimo", "openai", "mimo-v2.5-pro")));

        UpstreamChatService openAi = new StubUpstreamChatService("mimo", "openai");

        UpstreamChatServiceResolver resolver = new UpstreamChatServiceResolver(catalog, List.of(openAi));

        assertThat(resolver.resolve("mimo-v2.5-pro")).isSameAs(openAi);
    }

    @Test
    void fallsBackToFirstRegisteredServiceWhenModelIsUnknown() {
        RuntimeProviderCatalog catalog = mock(RuntimeProviderCatalog.class);
        given(catalog.getActiveProviders()).willReturn(List.of(provider("mimo", "openai", "mimo-v2.5-pro")));

        UpstreamChatService fallback = new StubUpstreamChatService("longcat", "openai");
        UpstreamChatService second = new StubUpstreamChatService("mimo", "openai");

        UpstreamChatServiceResolver resolver = new UpstreamChatServiceResolver(catalog, List.of(fallback, second));

        assertThat(resolver.resolve("unknown-model")).isSameAs(fallback);
    }

    private ProviderRuntimeConfiguration provider(String providerKey, String apiFormat, String... modelNames) {
        List<ProviderRuntimeModel> models = java.util.Arrays.stream(modelNames).map(modelName -> new ProviderRuntimeModel(modelName, 0, false, false)).toList();
        return new ProviderRuntimeConfiguration(providerKey, "", "", apiFormat, models);
    }

    private static final class StubUpstreamChatService implements UpstreamChatService {

        private final String providerKey;
        private final String apiFormat;

        private StubUpstreamChatService(String providerKey, String apiFormat) {
            this.providerKey = providerKey;
            this.apiFormat = apiFormat;
        }

        @Override
        public String getProviderKey() {
            return providerKey;
        }

        @Override
        public String getUpstreamApiFormat() {
            return apiFormat;
        }

        @Override
        public boolean supportsModel(String modelName) {
            return false;
        }

        @Override
        public Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model) {
            return Mono.empty();
        }

        @Override
        public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
            return Flux.empty();
        }
    }
}
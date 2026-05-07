package com.kaixuan.copilot_ollama_proxy.provider.ollama;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaTagsResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractRuntimeCatalogOllamaServiceTests {

    @Test
    void supportsModelAndListsModelsFromRuntimeCatalog() {
        RuntimeProviderCatalog catalog = () -> List.of(new ProviderRuntimeConfiguration("stub", "", "", "openai", List.of(new ProviderRuntimeModel("model-a", 4096, true, false))));
        TestOllamaService service = new TestOllamaService(catalog);

        OllamaTagsResponse response = service.listModels().block();

        assertThat(service.supportsModel("model-a")).isTrue();
        assertThat(service.supportsModel("model-b")).isFalse();
        assertThat(response).isNotNull();
        assertThat(response.getModels()).hasSize(1);
        assertThat(response.getModels().get(0).getName()).isEqualTo("model-a");
        assertThat(response.getModels().get(0).getDetails().getFormat()).isEqualTo("stub");
    }

    @Test
    void resolvesDefaultModelAndRequiresContextLength() {
        RuntimeProviderCatalog catalog = () -> List
                .of(new ProviderRuntimeConfiguration("stub", "", "", "openai", List.of(new ProviderRuntimeModel("model-a", 4096, true, false), new ProviderRuntimeModel("model-b", 0, false, false))));
        TestOllamaService service = new TestOllamaService(catalog);

        assertThat(service.exposeResolveModelOrDefault(null)).isEqualTo("model-a");
        assertThat(service.exposeRequireContextLength("model-a")).isEqualTo(4096);
        assertThatThrownBy(() -> service.exposeRequireContextLength("model-b")).isInstanceOf(IllegalStateException.class).hasMessageContaining("context_size");
    }

    private static final class TestOllamaService extends AbstractRuntimeCatalogOllamaService {

        private TestOllamaService(RuntimeProviderCatalog runtimeProviderCatalog) {
            super(runtimeProviderCatalog, "fallback-model");
        }

        private String exposeResolveModelOrDefault(String modelName) {
            return resolveModelOrDefault(modelName);
        }

        private int exposeRequireContextLength(String modelName) {
            return requireContextLength(modelName);
        }

        @Override
        public String getProviderKey() {
            return "stub";
        }

        @Override
        protected String providerFormat() {
            return "stub";
        }

        @Override
        protected String providerFamily() {
            return "Stub";
        }

        @Override
        protected List<String> providerFamilies() {
            return List.of("Stub");
        }

        @Override
        protected String providerParameterSize() {
            return "1B";
        }

        @Override
        protected String providerLicense() {
            return "MIT";
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
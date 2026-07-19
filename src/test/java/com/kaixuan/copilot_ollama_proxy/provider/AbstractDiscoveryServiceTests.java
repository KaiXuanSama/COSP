package com.kaixuan.copilot_ollama_proxy.provider;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractDiscoveryServiceTests {

    @Test
    void supportsModelFromRuntimeCatalog() {
        RuntimeProviderCatalog catalog = () -> List.of(new ProviderRuntimeConfiguration("stub", "", "", "openai", List.of(new ProviderRuntimeModel("model-a", 4096, true, false, "Medium"))));
        TestDiscoveryService service = new TestDiscoveryService(catalog);

        assertThat(service.supportsModel("model-a")).isTrue();
        assertThat(service.supportsModel("model-b")).isFalse();
    }

    @Test
    void resolvesDefaultModelAndRequiresContextLength() {
        RuntimeProviderCatalog catalog = () -> List
                .of(new ProviderRuntimeConfiguration("stub", "", "", "openai", List.of(new ProviderRuntimeModel("model-a", 4096, true, false, "Medium"), new ProviderRuntimeModel("model-b", 0, false, false, "Medium"))));
        TestDiscoveryService service = new TestDiscoveryService(catalog);

        assertThat(service.exposeResolveModelOrDefault(null)).isEqualTo("model-a");
        assertThat(service.exposeRequireContextLength("model-a")).isEqualTo(4096);
        assertThatThrownBy(() -> service.exposeRequireContextLength("model-b")).isInstanceOf(IllegalStateException.class).hasMessageContaining("context_size");
    }

    private static final class TestDiscoveryService extends AbstractDiscoveryService {

        private TestDiscoveryService(RuntimeProviderCatalog runtimeProviderCatalog) {
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
    }
}

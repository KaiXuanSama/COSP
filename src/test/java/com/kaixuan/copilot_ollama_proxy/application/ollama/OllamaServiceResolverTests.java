package com.kaixuan.copilot_ollama_proxy.application.ollama;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.generic.discovery.GenericDiscoveryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class OllamaServiceResolverTests {

    @Test
    void resolvesEveryConfiguredProviderToTheSingleGenericImplementation() {
        RuntimeProviderCatalog catalog = mock(RuntimeProviderCatalog.class);
        given(catalog.getActiveProviders()).willReturn(List.of(provider("mimo", "mimo-v2.5-pro")));

        GenericDiscoveryService generic = mock(GenericDiscoveryService.class);

        OllamaServiceResolver resolver = new OllamaServiceResolver(catalog, generic);

        assertThat(resolver.resolve("mimo-v2.5-pro")).isSameAs(generic);
    }

    @Test
    void returnsNullWhenModelIsUnknownRatherThanUsingAnArbitraryProvider() {
        RuntimeProviderCatalog catalog = mock(RuntimeProviderCatalog.class);
        given(catalog.getActiveProviders()).willReturn(List.of(provider("mimo", "mimo-v2.5-pro")));

        GenericDiscoveryService generic = mock(GenericDiscoveryService.class);

        OllamaServiceResolver resolver = new OllamaServiceResolver(catalog, generic);

        assertThat(resolver.resolve("unknown-model")).isNull();
    }

    private ProviderRuntimeConfiguration provider(String providerKey, String... modelNames) {
        List<ProviderRuntimeModel> models = java.util.Arrays.stream(modelNames).map(modelName -> new ProviderRuntimeModel(modelName, 0, false, false, "Medium")).toList();
        return new ProviderRuntimeConfiguration(providerKey, "", "", "openai", models);
    }
}
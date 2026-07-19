package com.kaixuan.copilot_ollama_proxy.application.openai;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.GenericOpenAiChatService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class UpstreamChatServiceResolverTests {

    @Test
    void resolvesEveryConfiguredProviderToTheSingleGenericImplementation() {
        RuntimeProviderCatalog catalog = mock(RuntimeProviderCatalog.class);
        given(catalog.getActiveProviders()).willReturn(List.of(provider("mimo", "openai", "mimo-v2.5-pro")));

        GenericOpenAiChatService generic = mock(GenericOpenAiChatService.class);

        UpstreamChatServiceResolver resolver = new UpstreamChatServiceResolver(catalog, generic);

        assertThat(resolver.resolve("mimo-v2.5-pro")).isSameAs(generic);
    }

    @Test
    void returnsNullWhenModelIsUnknownRatherThanUsingAnArbitraryProvider() {
        RuntimeProviderCatalog catalog = mock(RuntimeProviderCatalog.class);
        given(catalog.getActiveProviders()).willReturn(List.of(provider("mimo", "openai", "mimo-v2.5-pro")));

        GenericOpenAiChatService generic = mock(GenericOpenAiChatService.class);

        UpstreamChatServiceResolver resolver = new UpstreamChatServiceResolver(catalog, generic);

        assertThat(resolver.resolve("unknown-model")).isNull();
    }

    private ProviderRuntimeConfiguration provider(String providerKey, String apiFormat, String... modelNames) {
        List<ProviderRuntimeModel> models = java.util.Arrays.stream(modelNames).map(modelName -> new ProviderRuntimeModel(modelName, 0, false, false, "Medium")).toList();
        return new ProviderRuntimeConfiguration(providerKey, "", "", apiFormat, models);
    }
}
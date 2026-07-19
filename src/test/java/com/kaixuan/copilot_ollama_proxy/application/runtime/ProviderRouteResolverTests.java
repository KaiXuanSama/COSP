package com.kaixuan.copilot_ollama_proxy.application.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRouteResolverTests {

    @Test
    void resolvesPrefixedModelAgainstItsExactProvider() {
        ProviderRouteResolver resolver = resolver(provider("mimo", "mimo-v2.5-pro"));

        ResolvedProviderRoute route = resolver.resolve("[MiMo] mimo-v2.5-pro");

        assertThat(route).isNotNull();
        assertThat(route.provider().providerKey()).isEqualTo("mimo");
        assertThat(route.model()).isEqualTo("mimo-v2.5-pro");
    }

    @Test
    void rejectsPrefixedModelWhenTheProviderDoesNotOfferIt() {
        ProviderRouteResolver resolver = resolver(provider("mimo", "mimo-v2.5-pro"));

        assertThat(resolver.resolve("[MiMo] unknown-model")).isNull();
        assertThat(resolver.resolve("[DeepSeek] mimo-v2.5-pro")).isNull();
    }

    @Test
    void resolvesUnprefixedModelOnlyWhenItBelongsToOneProvider() {
        ProviderRouteResolver resolver = resolver(
                provider("mimo", "mimo-v2.5-pro"),
                provider("deepseek", "deepseek-chat"));

        ResolvedProviderRoute route = resolver.resolve("deepseek-chat");

        assertThat(route).isNotNull();
        assertThat(route.provider().providerKey()).isEqualTo("deepseek");
    }

    @Test
    void rejectsUnprefixedModelWhenItsProviderIsAmbiguous() {
        ProviderRouteResolver resolver = resolver(
                provider("first", "shared-model"),
                provider("second", "shared-model"));

        assertThat(resolver.resolve("shared-model")).isNull();
    }

    @Test
    void rejectsUnknownModels() {
        ProviderRouteResolver resolver = resolver(provider("mimo", "mimo-v2.5-pro"));

        assertThat(resolver.resolve("unknown-model")).isNull();
    }

    private ProviderRouteResolver resolver(ProviderRuntimeConfiguration... providers) {
        RuntimeProviderCatalog catalog = () -> List.of(providers);
        return new ProviderRouteResolver(catalog);
    }

    private ProviderRuntimeConfiguration provider(String key, String... models) {
        List<ProviderRuntimeModel> runtimeModels = java.util.Arrays.stream(models)
                .map(model -> new ProviderRuntimeModel(model, 8192, false, false, "medium"))
                .toList();
        return new ProviderRuntimeConfiguration(key, "", "", "openai", runtimeModels);
    }
}
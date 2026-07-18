package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderModelRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseRuntimeProviderCatalogTests {

    @Test
        void activeProviderUsesHeaderRulesFromNewTransformTable() {
        ProviderConfigRepository providerConfigRepository = mock(ProviderConfigRepository.class);
        ProviderApiKeyRepository providerApiKeyRepository = mock(ProviderApiKeyRepository.class);
        ProviderRequestTransformRepository requestTransformRepository = mock(ProviderRequestTransformRepository.class);
        ProviderConfigRow provider = new ProviderConfigRow(
                42, "custom-mimo-user", true, "https://api.example/v1", "openai",
                "2026-07-18T00:00:00", List.of(new ProviderModelRow(
                        1, 42, "mimo-v2.5-pro", true, 32768, 8192,
                        true, false, "Medium", 0)));
        ProviderRequestTransformRow transform = new ProviderRequestTransformRow(
                42, 1, "[{\"key\":\"X-New\",\"value\":\"new\"}]",
                "[\"base\"]", "{}", 1, "{\"version\":1,\"rules\":[{\"id\":\"rule-1\"}]}",
                "2026-07-18T00:00:00", "2026-07-18T00:00:00");
        when(providerConfigRepository.findAllActiveProvidersWithEnabledModels()).thenReturn(List.of(provider));
        when(providerApiKeyRepository.resolveActiveApiKey(42)).thenReturn("test-key");
        when(requestTransformRepository.findByProviderIds(List.of(42))).thenReturn(Map.of(42, transform));

        DatabaseRuntimeProviderCatalog catalog = new DatabaseRuntimeProviderCatalog(
                providerConfigRepository, providerApiKeyRepository, requestTransformRepository);

        ProviderRuntimeConfiguration configuration = catalog.getActiveProvider("custom-mimo-user");
        assertThat(configuration.headerRulesJson()).isEqualTo("[{\"key\":\"X-New\",\"value\":\"new\"}]");
        assertThat(configuration.bodyRulesJson()).isEqualTo("{\"version\":1,\"rules\":[{\"id\":\"rule-1\"}]}");
    }

    @Test
    void activeProviderWithoutNewTransformRowUsesNoHeaderRulesRatherThanLegacyFallback() {
        ProviderConfigRepository providerConfigRepository = mock(ProviderConfigRepository.class);
        ProviderApiKeyRepository providerApiKeyRepository = mock(ProviderApiKeyRepository.class);
        ProviderRequestTransformRepository requestTransformRepository = mock(ProviderRequestTransformRepository.class);
        ProviderConfigRow provider = new ProviderConfigRow(
                42, "custom-mimo-user", true, "https://api.example/v1", "openai",
                "2026-07-18T00:00:00", List.of());
        when(providerConfigRepository.findAllActiveProvidersWithEnabledModels()).thenReturn(List.of(provider));
        when(providerApiKeyRepository.resolveActiveApiKey(42)).thenReturn("test-key");
        when(requestTransformRepository.findByProviderIds(List.of(42))).thenReturn(Map.of());

        DatabaseRuntimeProviderCatalog catalog = new DatabaseRuntimeProviderCatalog(
                providerConfigRepository, providerApiKeyRepository, requestTransformRepository);

        ProviderRuntimeConfiguration configuration = catalog.getActiveProvider("custom-mimo-user");
        assertThat(configuration.headerRulesJson()).isEqualTo("[]");
                assertThat(configuration.bodyRulesJson()).isEqualTo("{\"version\":1,\"rules\":[]}");
    }
}

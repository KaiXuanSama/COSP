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
                42, "mimo-user", "Mimo User", true, "https://api.example/v1",
                "[\"OPENAI\",\"ANTHROPIC\"]", "",
                "2026-07-18T00:00:00", List.of(new ProviderModelRow(
                        1, 42, "mimo-v2.5-pro", true, 32768,
                        "{\"max_output_tokens\":8192,\"overwrite_mode\":\"fallback\"}",
                        true, false, "Medium",
                        "{\"thinking_type\":\"adaptive\",\"overwrite_mode\":\"fallback\"}", -1, 0)));
        ProviderRequestTransformRow transform = new ProviderRequestTransformRow(
                42, 1, "[{\"key\":\"X-New\",\"value\":\"new\"}]",
                "[\"base\"]", "{}", 2, "{\"version\":2,\"groups\":[{\"id\":\"g1\"}]}", 2,
                "2026-07-18T00:00:00", "2026-07-18T00:00:00");
        when(providerConfigRepository.findAllActiveProvidersWithEnabledModels()).thenReturn(List.of(provider));
        when(providerApiKeyRepository.resolveActiveApiKey(42)).thenReturn("test-key");
        when(requestTransformRepository.findByProviderIds(List.of(42))).thenReturn(Map.of(42, transform));

        DatabaseRuntimeProviderCatalog catalog = new DatabaseRuntimeProviderCatalog(
                providerConfigRepository, providerApiKeyRepository, requestTransformRepository);

        ProviderRuntimeConfiguration configuration = catalog.getActiveProvider("mimo-user");
        assertThat(configuration.headerRulesJson()).isEqualTo("[{\"key\":\"X-New\",\"value\":\"new\"}]");
        assertThat(configuration.bodyRulesJson()).isEqualTo("{\"version\":2,\"groups\":[{\"id\":\"g1\"}]}");
    }

    /**
     * 最大输出配置原样搬进运行时快照。
     *
     * <p>钉的是「原文不被解析、不被改写」：聊天链路拿到的必须是数据库里那串 JSON，
     * 解析由消费侧的 {@code MaxOutputTokensSetting.parse} 负责。这一层若擅自解析，
     * 快照就得跟着持久化形态的每次演进改签名。
     */
    @Test
    void maxOutputTokensRawValueReachesRuntimeSnapshot() {
        ProviderConfigRepository providerConfigRepository = mock(ProviderConfigRepository.class);
        ProviderApiKeyRepository providerApiKeyRepository = mock(ProviderApiKeyRepository.class);
        ProviderRequestTransformRepository requestTransformRepository = mock(ProviderRequestTransformRepository.class);
        String maxOutputJson = "{\"max_output_tokens\":8192,\"overwrite_mode\":\"override\"}";
        ProviderConfigRow provider = new ProviderConfigRow(
                42, "mimo-user", "Mimo User", true, "https://api.example/v1",
                "[\"ANTHROPIC\"]", "",
                "2026-07-18T00:00:00", List.of(new ProviderModelRow(
                        1, 42, "mimo-v2.5-pro", true, 32768,
                        maxOutputJson, true, false, "Medium",
                        "{\"thinking_type\":\"adaptive\",\"overwrite_mode\":\"fallback\"}", -1, 0)));
        when(providerConfigRepository.findAllActiveProvidersWithEnabledModels()).thenReturn(List.of(provider));
        when(providerApiKeyRepository.resolveActiveApiKey(42)).thenReturn("test-key");
        when(requestTransformRepository.findByProviderIds(List.of(42))).thenReturn(Map.of());

        DatabaseRuntimeProviderCatalog catalog = new DatabaseRuntimeProviderCatalog(
                providerConfigRepository, providerApiKeyRepository, requestTransformRepository);

        ProviderRuntimeConfiguration configuration = catalog.getActiveProvider("mimo-user");
        assertThat(configuration.models()).singleElement()
                .extracting(ProviderRuntimeModel::maxOutputTokens)
                .isEqualTo(maxOutputJson);
    }

    /**
     * 列为空时原样传 {@code null}，不做字面量兜底。
     *
     * <p>「空」的含义由消费侧决定（落到默认值）。这一层若替它填一个数字，
     * 就等于把默认值的定义拆成两处 —— 而 V9 之前的库里这一列确实可能是空的。
     */
    @Test
    void missingMaxOutputTokensStaysNullInSnapshot() {
        ProviderConfigRepository providerConfigRepository = mock(ProviderConfigRepository.class);
        ProviderApiKeyRepository providerApiKeyRepository = mock(ProviderApiKeyRepository.class);
        ProviderRequestTransformRepository requestTransformRepository = mock(ProviderRequestTransformRepository.class);
        ProviderConfigRow provider = new ProviderConfigRow(
                42, "mimo-user", "Mimo User", true, "https://api.example/v1",
                "[\"ANTHROPIC\"]", "",
                "2026-07-18T00:00:00", List.of(new ProviderModelRow(
                        1, 42, "mimo-v2.5-pro", true, 32768,
                        null, true, false, "Medium", null, -1, 0)));
        when(providerConfigRepository.findAllActiveProvidersWithEnabledModels()).thenReturn(List.of(provider));
        when(providerApiKeyRepository.resolveActiveApiKey(42)).thenReturn("test-key");
        when(requestTransformRepository.findByProviderIds(List.of(42))).thenReturn(Map.of());

        DatabaseRuntimeProviderCatalog catalog = new DatabaseRuntimeProviderCatalog(
                providerConfigRepository, providerApiKeyRepository, requestTransformRepository);

        ProviderRuntimeConfiguration configuration = catalog.getActiveProvider("mimo-user");
        assertThat(configuration.models()).singleElement()
                .extracting(ProviderRuntimeModel::maxOutputTokens)
                .isNull();
    }

    @Test
    void activeProviderWithoutNewTransformRowUsesNoHeaderRulesRatherThanLegacyFallback() {
        ProviderConfigRepository providerConfigRepository = mock(ProviderConfigRepository.class);
        ProviderApiKeyRepository providerApiKeyRepository = mock(ProviderApiKeyRepository.class);
        ProviderRequestTransformRepository requestTransformRepository = mock(ProviderRequestTransformRepository.class);
        ProviderConfigRow provider = new ProviderConfigRow(
                42, "mimo-user", "Mimo User", true, "https://api.example/v1",
                "[\"OPENAI\",\"ANTHROPIC\"]", "",
                "2026-07-18T00:00:00", List.of());
        when(providerConfigRepository.findAllActiveProvidersWithEnabledModels()).thenReturn(List.of(provider));
        when(providerApiKeyRepository.resolveActiveApiKey(42)).thenReturn("test-key");
        when(requestTransformRepository.findByProviderIds(List.of(42))).thenReturn(Map.of());

        DatabaseRuntimeProviderCatalog catalog = new DatabaseRuntimeProviderCatalog(
                providerConfigRepository, providerApiKeyRepository, requestTransformRepository);

        ProviderRuntimeConfiguration configuration = catalog.getActiveProvider("mimo-user");
        assertThat(configuration.headerRulesJson()).isEqualTo("[]");
                assertThat(configuration.bodyRulesJson()).isEqualTo("{\"version\":2,\"groups\":[]}");
    }
}

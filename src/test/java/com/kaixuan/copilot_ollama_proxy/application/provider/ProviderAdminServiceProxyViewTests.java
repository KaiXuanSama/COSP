package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 供应商管理列表对出站代理状态的响应契约。 */
class ProviderAdminServiceProxyViewTests {

    @Test
    void listProvidersExposesUseProxyForFrontendEditing() {
        ProviderConfigRepository providerConfigRepository = mock(ProviderConfigRepository.class);
        ProviderApiKeyRepository apiKeyRepository = mock(ProviderApiKeyRepository.class);
        ProviderRequestTransformRepository transformRepository = mock(ProviderRequestTransformRepository.class);
        ProviderConfigRow provider = new ProviderConfigRow(1, "relay", "Relay", true,
                "https://relay.example.com/v1", "[\"OPENAI\"]", "", true, "", List.of());
        when(providerConfigRepository.findAllWithModels()).thenReturn(List.of(provider));
        when(transformRepository.findByProviderIds(List.of(1))).thenReturn(Map.of());
        when(apiKeyRepository.findByProviderId(1)).thenReturn(List.of());

        ProviderAdminService service = new ProviderAdminService(providerConfigRepository,
                apiKeyRepository, transformRepository, mock(ProviderRequestTransformService.class),
                mock(OutboundProxyTargetProjector.class), new ObjectMapper());

        Map<String, Object> result = service.listProviders().block();

        assertThat(result).isNotNull();
        assertThat(result.get("relay")).isInstanceOfSatisfying(Map.class,
            view -> assertThat(view.get("useProxy")).isEqualTo(true));
    }
}
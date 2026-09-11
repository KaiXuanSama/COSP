package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单条 API Key 明文 reveal。
 *
 * <p>钉住「列表接口只返回脱敏值、明文仅在管理员显式请求时按需解密回传」的安全口径：
 * 解密复用 {@link ProviderApiKeyRepository#decrypt}，供应商不存在或 keyUuid 不匹配时
 * 返回空串（控制器据此回 404），且未知 keyUuid 绝不触发任何解密调用。
 */
class ProviderAdminServiceRevealTests {

    private ProviderConfigRepository providerConfigRepository;
    private ProviderApiKeyRepository providerApiKeyRepository;
    private ProviderAdminService service;

    @BeforeEach
    void setUp() {
        providerConfigRepository = mock(ProviderConfigRepository.class);
        providerApiKeyRepository = mock(ProviderApiKeyRepository.class);
        service = new ProviderAdminService(providerConfigRepository,
                providerApiKeyRepository,
                mock(ProviderRequestTransformRepository.class),
                mock(ProviderRequestTransformService.class),
                mock(OutboundProxyTargetProjector.class),
                new ObjectMapper());
    }

    private static ProviderConfigRow provider(int id, String providerKey) {
        return new ProviderConfigRow(id, providerKey, providerKey, true, "https://api.example.com/v1",
                "[\"OPENAI\"]", "", false, "2026-09-10T10:00:00", List.of());
    }

    private static ProviderApiKeyRow keyRow(String keyUuid, String keyName) {
        return new ProviderApiKeyRow(1L, keyUuid, 1, keyName, "cipher", "nonce", 1, false, 0);
    }

    @Test
    void revealsPlaintextForKnownKey() {
        ProviderConfigRow relay = provider(1, "relay");
        when(providerConfigRepository.findByKey("relay")).thenReturn(relay);
        when(providerApiKeyRepository.findByProviderId(1))
                .thenReturn(List.of(keyRow("u1", "old"), keyRow("u2", "second")));
        when(providerApiKeyRepository.decrypt(keyRow("u2", "second"))).thenReturn("sk-plaintext-2");

        String plaintext = service.revealProviderApiKey("relay", "u2").block();

        assertThat(plaintext).isEqualTo("sk-plaintext-2");
    }

    @Test
    void returnsEmptyForUnknownKeyUuidWithoutDecrypting() {
        ProviderConfigRow relay = provider(1, "relay");
        when(providerConfigRepository.findByKey("relay")).thenReturn(relay);
        when(providerApiKeyRepository.findByProviderId(1))
                .thenReturn(List.of(keyRow("u1", "old"), keyRow("u2", "second")));

        String plaintext = service.revealProviderApiKey("relay", "gone").block();

        assertThat(plaintext).isEmpty();
        // 关键断言：未知 uuid 绝不能触发解密，避免对不存在的 Key 做无谓的解密尝试。
        verify(providerApiKeyRepository, never()).decrypt(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsEmptyForUnknownProvider() {
        when(providerConfigRepository.findByKey("ghost")).thenReturn(null);

        String plaintext = service.revealProviderApiKey("ghost", "u1").block();

        assertThat(plaintext).isEmpty();
        verify(providerApiKeyRepository, never()).findByProviderId(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void returnsEmptyForBlankKeyUuid() {
        ProviderConfigRow relay = provider(1, "relay");
        when(providerConfigRepository.findByKey("relay")).thenReturn(relay);

        assertThat(service.revealProviderApiKey("relay", "").block()).isEmpty();
        assertThat(service.revealProviderApiKey("relay", "  ").block()).isEmpty();
        verify(providerApiKeyRepository, never()).findByProviderId(org.mockito.ArgumentMatchers.anyInt());
    }
}

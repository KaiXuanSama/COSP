package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRepository;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    /**
     * 表单带了 {@code useProxy} 时，修改接口自己把它写下去，不需要额外的专项请求。
     */
    @Test
    void updateProviderPersistsUseProxyFromForm() {
        Fixture fixture = new Fixture();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("displayName", "Relay");
        form.add("baseUrl", "https://relay.example.com/v1");
        form.add("useProxy", "true");

        fixture.service.updateProvider("relay", form).block();

        verify(fixture.providerConfigRepository).updateProviderProxy("relay", true);
        // 写完必须重投影，否则内存目标集合还是旧的，开关等于没生效。
        verify(fixture.projector).reprojectProxiedTargets();
    }

    /**
     * 表单<strong>未带</strong> {@code useProxy} 时一律不碰该列。
     *
     * <p>这是本字段最重要的语义：编辑抽屉之类只改模型的保存路径不提交它，
     * 若把「缺失」当成 false，那条路径会把用户已开的代理静默关掉。
     */
    @Test
    void updateProviderLeavesUseProxyUntouchedWhenFormOmitsIt() {
        Fixture fixture = new Fixture();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("displayName", "Relay");
        form.add("baseUrl", "https://relay.example.com/v1");

        fixture.service.updateProvider("relay", form).block();

        verify(fixture.providerConfigRepository, never()).updateProviderProxy(any(), anyBoolean());
    }

    /** 空串与无法识别的值同样视为「未提供」，宁可不改也不猜成 false。 */
    @Test
    void blankUseProxyIsTreatedAsAbsent() {
        Fixture fixture = new Fixture();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("displayName", "Relay");
        form.add("baseUrl", "https://relay.example.com/v1");
        form.add("useProxy", "   ");

        fixture.service.updateProvider("relay", form).block();

        verify(fixture.providerConfigRepository, never()).updateProviderProxy(any(), anyBoolean());
    }

    /** 三个用例共用的 mock 装配。 */
    private static final class Fixture {
        private final ProviderConfigRepository providerConfigRepository = mock(ProviderConfigRepository.class);
        private final OutboundProxyTargetProjector projector = mock(OutboundProxyTargetProjector.class);
        private final ProviderAdminService service;

        private Fixture() {
            ProviderConfigRow existing = new ProviderConfigRow(1, "relay", "Relay", true,
                    "https://relay.example.com/v1", "[\"OPENAI\"]", "", false, "", List.of());
            when(providerConfigRepository.findByKey("relay")).thenReturn(existing);
            service = new ProviderAdminService(providerConfigRepository,
                    mock(ProviderApiKeyRepository.class), mock(ProviderRequestTransformRepository.class),
                    mock(ProviderRequestTransformService.class), projector, new ObjectMapper());
        }
    }
}
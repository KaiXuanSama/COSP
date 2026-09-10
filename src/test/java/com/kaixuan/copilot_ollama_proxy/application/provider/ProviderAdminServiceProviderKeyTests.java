package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 供应商展示名 → 路由标识的入库前校验。
 *
 * 关注点是「展示名派生不出标识」这一类输入：{@code toProviderKey} 只保留 ASCII
 * 字母数字，纯中文或全角名称会得到空串，而 SQLite 的 {@code NOT NULL} 并不拒绝
 * 空串，故必须在服务层拦截，否则会静默产生一条无法被精确路由的供应商记录。
 */
class ProviderAdminServiceProviderKeyTests {

    private ProviderConfigRepository providerConfigRepository;
    private ProviderRequestTransformService transformService;
    private ProviderAdminService service;

    @BeforeEach
    void setUp() {
        providerConfigRepository = mock(ProviderConfigRepository.class);
        transformService = mock(ProviderRequestTransformService.class);
        service = new ProviderAdminService(providerConfigRepository,
                mock(ProviderApiKeyRepository.class),
                mock(ProviderRequestTransformRepository.class),
                transformService,
                mock(OutboundProxyTargetProjector.class),
                new ObjectMapper());
    }

    private static MultiValueMap<String, String> form(String displayName) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.put("displayName", List.of(displayName));
        return form;
    }

    @Nested
    class AddProvider {

        @Test
        void rejectsNameWithoutAsciiAlphanumeric() {
            ProviderAdminService.Outcome outcome = service.addProvider(form("深度求索")).block();

            assertThat(outcome).isNotNull();
            assertThat(outcome.status()).isEqualTo(400);
            assertThat(outcome.body()).containsEntry("error", ProviderAdminService.EMPTY_PROVIDER_KEY_ERROR);
            // 关键断言：绝不能走到写库
            verify(transformService, never()).createProvider(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void rejectsFullWidthOnlyName() {
            ProviderAdminService.Outcome outcome = service.addProvider(form("ＭｉＭｏ")).block();

            assertThat(outcome).isNotNull();
            assertThat(outcome.status()).isEqualTo(400);
            assertThat(outcome.body()).containsEntry("error", ProviderAdminService.EMPTY_PROVIDER_KEY_ERROR);
        }

        @Test
        void rejectsSeparatorOnlyName() {
            ProviderAdminService.Outcome outcome = service.addProvider(form("---")).block();

            assertThat(outcome).isNotNull();
            assertThat(outcome.status()).isEqualTo(400);
        }

        @Test
        void acceptsMixedNameKeepingAsciiPart() {
            when(providerConfigRepository.findByKey("ai")).thenReturn(null);

            ProviderAdminService.Outcome outcome = service.addProvider(form("智谱AI")).block();

            assertThat(outcome).isNotNull();
            assertThat(outcome.status()).isEqualTo(200);
            // 展示名保留完整中文，只有路由标识被裁剪
            assertThat(outcome.body()).containsEntry("providerKey", "ai");
            assertThat(outcome.body()).containsEntry("displayName", "智谱AI");
        }

        @Test
        void acceptsPlainAsciiName() {
            when(providerConfigRepository.findByKey("mimo")).thenReturn(null);

            ProviderAdminService.Outcome outcome = service.addProvider(form("MiMo")).block();

            assertThat(outcome).isNotNull();
            assertThat(outcome.status()).isEqualTo(200);
            assertThat(outcome.body()).containsEntry("providerKey", "mimo");
        }

        @Test
        void stillRejectsBlankName() {
            ProviderAdminService.Outcome outcome = service.addProvider(form("   ")).block();

            assertThat(outcome).isNotNull();
            assertThat(outcome.status()).isEqualTo(400);
            assertThat(outcome.body()).containsEntry("error", "供应商名称不能为空");
        }
    }

    @Nested
    class UpdateProvider {

        @Test
        void rejectsRenameToNameWithoutAsciiAlphanumeric() {
            when(providerConfigRepository.findByKey("mimo"))
                    .thenReturn(new ProviderConfigRow(1, "mimo", "MiMo", true, "",
                            "[\"OPENAI\",\"ANTHROPIC\"]", "", false, "", List.of()));

            ProviderAdminService.Outcome outcome = service.updateProvider("mimo", form("深度求索")).block();

            assertThat(outcome).isNotNull();
            assertThat(outcome.status()).isEqualTo(400);
            assertThat(outcome.body()).containsEntry("error", ProviderAdminService.EMPTY_PROVIDER_KEY_ERROR);
            verify(transformService, never()).updateProvider(anyInt(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void allowsRenameKeepingAsciiPart() {
            when(providerConfigRepository.findByKey("mimo"))
                    .thenReturn(new ProviderConfigRow(1, "mimo", "MiMo", true, "",
                            "[\"OPENAI\",\"ANTHROPIC\"]", "", false, "", List.of()));
            when(providerConfigRepository.findByKey("xiaomi-mimo")).thenReturn(null);

            ProviderAdminService.Outcome outcome = service.updateProvider("mimo", form("Xiaomi MiMo")).block();

            assertThat(outcome).isNotNull();
            assertThat(outcome.status()).isEqualTo(200);
            assertThat(outcome.body()).containsEntry("providerKey", "xiaomi-mimo");
        }
    }
}

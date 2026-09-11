package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 保存供应商配置时的<strong>激活 Key 判定</strong>。
 *
 * <p>钉住「新增并选中一条尚未落库的 Key、保存后激活项回退到旧 Key」这个 bug 的修复：
 * 激活项全程用 {@code keyUuid} 标识，而新增条目此刻没有 uuid，于是过去只能兜底把第一条
 * （通常是旧 Key）设为激活。修复引入 {@code activeKeyIndex}：uuid 匹配不到时按下标命中。
 *
 * <p>测试从传给 {@code saveProviderConfigWithModels} 的 {@code List<ApiKeyInput>} 里
 * 断言哪一条 {@code active=true}，因此不需要真数据库，只 mock 仓储。
 */
class ProviderAdminServiceApiKeyActivationTests {

    private ProviderConfigRepository providerConfigRepository;
    private ProviderAdminService service;

    @BeforeEach
    void setUp() {
        providerConfigRepository = mock(ProviderConfigRepository.class);
        service = new ProviderAdminService(providerConfigRepository,
                mock(ProviderApiKeyRepository.class),
                mock(ProviderRequestTransformRepository.class),
                mock(ProviderRequestTransformService.class),
                mock(OutboundProxyTargetProjector.class),
                new ObjectMapper());
    }

    /** 构造编辑抽屉的保存表单。apiKeys 为 JSON 数组字符串。 */
    private static MultiValueMap<String, String> form(String apiKeysJson, String activeKeyUuid,
                                                       String activeKeyIndex) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.put("baseUrl", List.of("https://api.example.com/v1"));
        form.put("apiKeys", List.of(apiKeysJson));
        form.put("activeKeyUuid", List.of(activeKeyUuid));
        if (activeKeyIndex != null) {
            form.put("activeKeyIndex", List.of(activeKeyIndex));
        }
        return form;
    }

    @SuppressWarnings("unchecked")
    private List<ProviderApiKeyRepository.ApiKeyInput> captureSavedInputs() {
        ArgumentCaptor<List<ProviderApiKeyRepository.ApiKeyInput>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(providerConfigRepository).saveProviderConfigWithModels(
                eq("relay"), anyString(), captor.capture(), anyList());
        return captor.getValue();
    }

    private static int activeIndexOf(List<ProviderApiKeyRepository.ApiKeyInput> inputs) {
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).active()) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void activeKeyUuidTakesPrecedenceWhenPresent() {
        // 两条已保存 Key，选中第二条（有 uuid）。activeKeyIndex 缺失也应命中 uuid。
        String json = "[{\"keyUuid\":\"u1\",\"name\":\"old\"},{\"keyUuid\":\"u2\",\"name\":\"second\"}]";
        service.saveProviderConfig("relay", form(json, "u2", null)).block();

        List<ProviderApiKeyRepository.ApiKeyInput> inputs = captureSavedInputs();
        assertThat(activeIndexOf(inputs)).isEqualTo(1);
    }

    @Test
    void newUnsavedKeyBecomesActiveViaIndex() {
        // bug 的核心场景：index 0 是旧 Key（有 uuid），index 1 是新增未保存 Key（无 uuid）。
        // 用户选中新增项 → activeKeyUuid 为空、activeKeyIndex=1。修复前会兜底激活第 0 条。
        String json = "[{\"keyUuid\":\"u1\",\"name\":\"old\"},{\"name\":\"fresh\",\"apiKey\":\"sk-fresh\"}]";
        service.saveProviderConfig("relay", form(json, "", "1")).block();

        List<ProviderApiKeyRepository.ApiKeyInput> inputs = captureSavedInputs();
        assertThat(activeIndexOf(inputs)).isEqualTo(1);
        assertThat(inputs.get(1).keyName()).isEqualTo("fresh");
        assertThat(inputs.get(1).keyUuid()).isNull();
    }

    @Test
    void fallsBackToFirstWhenNeitherUuidNorIndexMatches() {
        // activeKeyUuid 匹配不到、activeKeyIndex 缺失 → 保留原有兜底：第一条为激活。
        String json = "[{\"keyUuid\":\"u1\",\"name\":\"old\"},{\"name\":\"fresh\",\"apiKey\":\"sk-fresh\"}]";
        service.saveProviderConfig("relay", form(json, "gone", null)).block();

        List<ProviderApiKeyRepository.ApiKeyInput> inputs = captureSavedInputs();
        assertThat(activeIndexOf(inputs)).isEqualTo(0);
    }

    @Test
    void outOfRangeIndexFallsBackToFirst() {
        // 越界下标不应抛异常，也不越界访问，退回第一条。
        String json = "[{\"keyUuid\":\"u1\",\"name\":\"old\"},{\"name\":\"fresh\",\"apiKey\":\"sk-fresh\"}]";
        service.saveProviderConfig("relay", form(json, "", "9")).block();

        List<ProviderApiKeyRepository.ApiKeyInput> inputs = captureSavedInputs();
        assertThat(activeIndexOf(inputs)).isEqualTo(0);
    }

    @Test
    void malformedIndexIsTreatedAsUnspecified() {
        // 非数字 activeKeyIndex 当成未指定，走 uuid 优先 / 兜底第一条。
        String json = "[{\"keyUuid\":\"u1\",\"name\":\"old\"},{\"name\":\"fresh\",\"apiKey\":\"sk-fresh\"}]";
        service.saveProviderConfig("relay", form(json, "", "abc")).block();

        List<ProviderApiKeyRepository.ApiKeyInput> inputs = captureSavedInputs();
        assertThat(activeIndexOf(inputs)).isEqualTo(0);
    }

    @Test
    void singleNewKeyBecomesActive() {
        // 只有一条新增 Key、选中它：index=0，且不因无 uuid 而落空。
        String json = "[{\"name\":\"only\",\"apiKey\":\"sk-only\"}]";
        service.saveProviderConfig("relay", form(json, "", "0")).block();

        List<ProviderApiKeyRepository.ApiKeyInput> inputs = captureSavedInputs();
        assertThat(activeIndexOf(inputs)).isEqualTo(0);
        assertThat(inputs.get(0).keyName()).isEqualTo("only");
    }
}

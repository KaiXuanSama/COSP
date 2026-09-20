package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.AuthHeaderSetting;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 供应商级出站鉴权头装配方式的落库契约。
 *
 * <p>覆盖三条保存路径（编辑抽屉 / 新建 / 改名）与回传形态。真正的头部装配行为
 * 不在这里验 —— 本阶段落库后还没有任何运行时代码读取该列。
 */
class ProviderAdminServiceAuthHeaderTests {

    /**
     * 回传 JSON <strong>原文</strong>而不是解析后的对象。
     *
     * <p>钉的是与表单字段的对称性：前端提交的也是同一串 JSON，两侧互为逆运算。
     * 若改成回对象，就得额外约定一套大小写（后端枚举名大写 vs 前端小写联合类型），
     * 而那是一个纯为传输而生的映射层。
     */
    @Test
    void listProvidersExposesAuthHeaderJsonVerbatim() {
        Fixture fixture = new Fixture(rowWithAuthHeader("{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}"));

        Map<String, Object> result = fixture.service.listProviders().block();

        assertThat(result).isNotNull();
        assertThat(result.get("relay")).isInstanceOfSatisfying(Map.class,
                view -> assertThat(view.get("authHeaderJson"))
                        .isEqualTo("{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}"));
    }

    /**
     * 列为空（未迁移的库、旧夹具）时兜到列缺省值，不让前端面对一个 null。
     *
     * <p>否则前端要么自己复制一份缺省值（三处默认值立刻变成四处），要么把 null 渲染成空配置。
     */
    @Test
    void listProvidersFallsBackToColumnDefaultWhenColumnIsBlank() {
        Fixture fixture = new Fixture(rowWithAuthHeader(""));

        Map<String, Object> result = fixture.service.listProviders().block();

        assertThat(result).isNotNull();
        assertThat(result.get("relay")).isInstanceOfSatisfying(Map.class,
                view -> assertThat(view.get("authHeaderJson"))
                        .isEqualTo(AuthHeaderSetting.DEFAULT_AUTH_HEADER_JSON));
    }

    /** 表单带了该字段时由修改接口自己写下去，不需要额外的专项请求。 */
    @Test
    void updateProviderPersistsAuthHeaderFromForm() {
        Fixture fixture = new Fixture(rowWithAuthHeader(""));
        MultiValueMap<String, String> form = baseForm();
        form.add("authHeaderJson", "{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}");

        fixture.service.updateProvider("relay", form).block();

        verify(fixture.providerConfigRepository)
                .updateProviderAuthHeader("relay", "{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}");
    }

    /**
     * 落库前收敛成规范形态：枚举名大写、只留两个键。
     *
     * <p>不规范化就得在读取侧同时兼容大小写与拼写变体，而列上的 {@code json_valid}
     * 只保证「是 JSON」，不保证「是约定的那两种写法」。
     */
    @Test
    void updateProviderNormalizesAuthHeaderCasingAndDropsUnknownKeys() {
        Fixture fixture = new Fixture(rowWithAuthHeader(""));
        MultiValueMap<String, String> form = baseForm();
        form.add("authHeaderJson", "  {\"mode\":\"configured\",\"header\":\"x_api_key\",\"note\":\"hi\"}  ");

        fixture.service.updateProvider("relay", form).block();

        verify(fixture.providerConfigRepository)
                .updateProviderAuthHeader("relay", "{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}");
    }

    /**
     * 表单<strong>未带</strong>该字段时一律不碰该列。
     *
     * <p>这是本字段最重要的语义：编辑抽屉之类只改模型的保存路径不提交它，
     * 若把「缺失」当成「回默认值」，那条路径会把用户已配好的方式抹平 ——
     * 而鉴权头发错头名的表现是上游 401/403，排查会指向凭据而不是配置。
     */
    @Test
    void updateProviderLeavesAuthHeaderUntouchedWhenFormOmitsIt() {
        Fixture fixture = new Fixture(rowWithAuthHeader(""));

        fixture.service.updateProvider("relay", baseForm()).block();

        verify(fixture.providerConfigRepository, never()).updateProviderAuthHeader(any(), any());
    }

    /** 空串与空白同样视为「未提供」，宁可不改也不写成默认值。 */
    @Test
    void blankAuthHeaderIsTreatedAsAbsent() {
        Fixture fixture = new Fixture(rowWithAuthHeader(""));
        MultiValueMap<String, String> form = baseForm();
        form.add("authHeaderJson", "   ");

        fixture.service.updateProvider("relay", form).block();

        verify(fixture.providerConfigRepository, never()).updateProviderAuthHeader(any(), any());
    }

    /**
     * 认不出的值报 400，<strong>不</strong>静默兜底写默认值。
     *
     * <p>静默兜底的代价是把用户的配置悄悄改掉，而且是在一次看起来成功的保存之后 ——
     * 写入严格、读取宽容这条分工正是为此。
     */
    @Test
    void invalidAuthHeaderIsRejectedWithoutWriting() {
        for (String raw : new String[] {
                "not-json", "[1,2]", "{\"mode\":", "{\"mode\":\"bogus\",\"header\":\"X_API_KEY\"}",
                "{\"mode\":\"CONFIGURED\",\"header\":\"X-Api-Key\"}", "{\"header\":\"AUTHORIZATION\"}"}) {
            Fixture fixture = new Fixture(rowWithAuthHeader(""));
            MultiValueMap<String, String> form = baseForm();
            form.add("authHeaderJson", raw);

            ProviderAdminService.Outcome outcome = fixture.service.updateProvider("relay", form).block();

            assertThat(outcome).as("输入: %s", raw).isNotNull();
            assertThat(outcome.status()).as("输入: %s", raw).isEqualTo(400);
            assertThat(outcome.body()).as("输入: %s", raw).containsEntry("ok", false);
            verify(fixture.providerConfigRepository, never()).updateProviderAuthHeader(any(), any());
        }
    }

    /** 编辑抽屉路径同样落库，否则「配了但没生效」只在这一条路径上出现。 */
    @Test
    void saveProviderConfigPersistsAuthHeaderFromForm() {
        Fixture fixture = new Fixture(rowWithAuthHeader(""));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("baseUrl", "https://relay.example.com/v1");
        form.add("authHeaderJson", "{\"mode\":\"DOWNSTREAM\",\"header\":\"X_API_KEY\"}");

        ProviderAdminService.Outcome outcome = fixture.service.saveProviderConfig("relay", form).block();

        assertThat(outcome).isNotNull();
        assertThat(outcome.status()).isEqualTo(200);
        verify(fixture.providerConfigRepository)
                .updateProviderAuthHeader("relay", "{\"mode\":\"DOWNSTREAM\",\"header\":\"X_API_KEY\"}");
    }

    /** 新建路径也落库，否则新建的供应商只能用列缺省值。 */
    @Test
    void addProviderPersistsAuthHeaderFromForm() {
        // 该路径要求 key 尚未存在，否则会以「名称已存在」提前返回。
        Fixture fixture = new Fixture(null);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("displayName", "Relay");
        form.add("baseUrl", "https://relay.example.com/v1");
        form.add("authHeaderJson", "{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}");

        ProviderAdminService.Outcome outcome = fixture.service.addProvider(form).block();

        assertThat(outcome).isNotNull();
        assertThat(outcome.status()).isEqualTo(200);
        verify(fixture.providerConfigRepository)
                .updateProviderAuthHeader("relay", "{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}");
    }

    /**
     * 改名路径必须用<strong>改名后的</strong> key 定位。
     *
     * <p>用旧 key 会匹配 0 行且 UPDATE 不报错，表现为该字段静默丢失 ——
     * 与协议集合、代理开关当年踩的是同一个坑。
     */
    @Test
    void updateProviderWritesAuthHeaderUnderTheRenamedKey() {
        Fixture fixture = new Fixture(rowWithAuthHeader(""));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("displayName", "Renamed");
        form.add("baseUrl", "https://relay.example.com/v1");
        form.add("authHeaderJson", "{\"mode\":\"CONFIGURED\",\"header\":\"AUTHORIZATION\"}");

        fixture.service.updateProvider("relay", form).block();

        verify(fixture.providerConfigRepository).updateProviderAuthHeader(eq("renamed"), any());
        verify(fixture.providerConfigRepository, never()).updateProviderAuthHeader(eq("relay"), any());
    }

    private static MultiValueMap<String, String> baseForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("displayName", "Relay");
        form.add("baseUrl", "https://relay.example.com/v1");
        return form;
    }

    private static ProviderConfigRow rowWithAuthHeader(String authHeaderJson) {
        return new ProviderConfigRow(1, "relay", "Relay", true, "https://relay.example.com/v1",
                "[\"CHAT\"]", "", "", false, authHeaderJson, "", List.of());
    }

    /**
     * 各用例共用的 mock 装配。
     *
     * @param existing 已存在的供应商行；为 {@code null} 表示库中还没有该 key（新建路径）
     */
    private static final class Fixture {
        private final ProviderConfigRepository providerConfigRepository = mock(ProviderConfigRepository.class);
        private final ProviderRequestTransformRepository transformRepository =
                mock(ProviderRequestTransformRepository.class);
        private final ProviderAdminService service;

        private Fixture(ProviderConfigRow existing) {
            if (existing != null) {
                when(providerConfigRepository.findByKey(existing.providerKey())).thenReturn(existing);
                when(providerConfigRepository.findAllWithModels()).thenReturn(List.of(existing));
                when(transformRepository.findByProviderIds(List.of(existing.id()))).thenReturn(Map.of());
            }
            service = new ProviderAdminService(providerConfigRepository,
                    mock(ProviderApiKeyRepository.class), transformRepository,
                    mock(ProviderRequestTransformService.class),
                    mock(OutboundProxyTargetProjector.class), new ObjectMapper());
        }
    }
}

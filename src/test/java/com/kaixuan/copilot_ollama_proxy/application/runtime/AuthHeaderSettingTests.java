package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthHeaderSetting} 的解析、序列化与「用哪个头」决策。
 *
 * <p>本类只覆盖无 I/O 的部分 —— 真正的头部装配（探测、剥离、注入）由
 * {@code ProviderRequestHeaderServiceTests} 覆盖。
 */
class AuthHeaderSettingTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    class 缺省值 {

        /**
         * 缺省取「取下游 + Authorization」，而不是「取设置 + x-api-key」。
         *
         * <p>钉的是升级不变性：下游带了什么就还发什么。若哪天把缺省改成 CONFIGURED，
         * 所有存量 Messages 供应商的出站头会被立刻改写，而它们的调用方
         * 往往正是用 x-api-key 的那批。
         */
        @Test
        void defaultsToDownstreamWithAuthorization() {
            AuthHeaderSetting setting = AuthHeaderSetting.defaults();

            assertThat(setting.mode()).isEqualTo(AuthHeaderSetting.Mode.DOWNSTREAM);
            assertThat(setting.header()).isEqualTo(AuthHeaderSetting.Header.AUTHORIZATION);
        }

        /** 常量必须与 schema.sql 的 DEFAULT 逐字一致（三处分叉会让新库与旧库看起来是两种配置）。 */
        @Test
        void defaultJsonMatchesTheColumnDefaultVerbatim() {
            assertThat(AuthHeaderSetting.DEFAULT_AUTH_HEADER_JSON)
                    .isEqualTo("{\"mode\":\"DOWNSTREAM\",\"header\":\"AUTHORIZATION\"}");
            assertThat(AuthHeaderSetting.defaults().serialize())
                    .isEqualTo(AuthHeaderSetting.DEFAULT_AUTH_HEADER_JSON);
        }

        /** 两个维度各自独立归一：只缺一个时，另一个仍按落库值生效。 */
        @Test
        void nullComponentsFallBackIndependently() {
            assertThat(new AuthHeaderSetting(null, AuthHeaderSetting.Header.X_API_KEY).mode())
                    .isEqualTo(AuthHeaderSetting.Mode.DOWNSTREAM);
            assertThat(new AuthHeaderSetting(AuthHeaderSetting.Mode.CONFIGURED, null).header())
                    .isEqualTo(AuthHeaderSetting.Header.AUTHORIZATION);
        }
    }

    @Nested
    class 解析 {

        @Test
        void jsonBeingParsedIntoModeAndHeader() {
            AuthHeaderSetting setting = AuthHeaderSetting.parse(
                    "{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}", objectMapper);

            assertThat(setting.mode()).isEqualTo(AuthHeaderSetting.Mode.CONFIGURED);
            assertThat(setting.header()).isEqualTo(AuthHeaderSetting.Header.X_API_KEY);
        }

        /** 枚举名大小写不敏感：手工改库或旧版前端可能写成小写。 */
        @Test
        void enumNamesAreCaseInsensitive() {
            AuthHeaderSetting setting = AuthHeaderSetting.parse(
                    "{\"mode\":\"configured\",\"header\":\"x_api_key\"}", objectMapper);

            assertThat(setting.mode()).isEqualTo(AuthHeaderSetting.Mode.CONFIGURED);
            assertThat(setting.header()).isEqualTo(AuthHeaderSetting.Header.X_API_KEY);
        }

        /**
         * 值来自数据库，一行脏数据不该让整条聊天链路失败。
         *
         * <p>注意这里与<strong>表单写入路径</strong>口径相反：那边认不出就 400（写入严格），
         * 这边认不出就兜底（读取宽容）。两条路径的判据不同是刻意的，不是遗漏。
         */
        @Test
        void malformedInputFallsBackToDefaultsInsteadOfThrowing() {
            for (String raw : new String[] {null, "", "   ", "not-json", "[1,2]", "{\"mode\":", "12345"}) {
                assertThat(AuthHeaderSetting.parse(raw, objectMapper))
                        .isEqualTo(AuthHeaderSetting.defaults());
            }
        }

        /** 认不出的维度只影响自己那一维，另一维照常生效。 */
        @Test
        void unknownModeAndHeaderFallBackIndependently() {
            AuthHeaderSetting unknownMode = AuthHeaderSetting.parse(
                    "{\"mode\":\"bogus\",\"header\":\"X_API_KEY\"}", objectMapper);
            assertThat(unknownMode.mode()).isEqualTo(AuthHeaderSetting.Mode.DOWNSTREAM);
            assertThat(unknownMode.header()).isEqualTo(AuthHeaderSetting.Header.X_API_KEY);

            AuthHeaderSetting unknownHeader = AuthHeaderSetting.parse(
                    "{\"mode\":\"CONFIGURED\",\"header\":\"X-Api-Key\"}", objectMapper);
            assertThat(unknownHeader.mode()).isEqualTo(AuthHeaderSetting.Mode.CONFIGURED);
            assertThat(unknownHeader.header()).isEqualTo(AuthHeaderSetting.Header.AUTHORIZATION);
        }

        /** 缺 ObjectMapper 时退默认值而不是崩，与同族设置类的约定一致。 */
        @Test
        void nullObjectMapperFallsBackToDefaults() {
            assertThat(AuthHeaderSetting.parse("{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}", null))
                    .isEqualTo(AuthHeaderSetting.defaults());
        }
    }

    @Nested
    class 序列化 {

        @Test
        void serializesToCanonicalJsonWithEnumNames() {
            assertThat(new AuthHeaderSetting(AuthHeaderSetting.Mode.CONFIGURED,
                    AuthHeaderSetting.Header.X_API_KEY).serialize())
                    .isEqualTo("{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}");
        }

        /** 往返必须收敛到自身，否则每次读取再写入都会产生一次无意义的 diff。 */
        @Test
        void parseAndSerializeRoundTrip() {
            for (AuthHeaderSetting.Mode mode : AuthHeaderSetting.Mode.values()) {
                for (AuthHeaderSetting.Header header : AuthHeaderSetting.Header.values()) {
                    AuthHeaderSetting setting = new AuthHeaderSetting(mode, header);
                    assertThat(AuthHeaderSetting.parse(setting.serialize(), objectMapper))
                            .isEqualTo(setting);
                }
            }
        }

        /** 落库的是枚举名而不是头名字面量 —— 头名有大小写与拼写变体，不是稳定标识。 */
        @Test
        void headerNameIsNotPersisted() {
            assertThat(AuthHeaderSetting.defaults().serialize()).doesNotContain("x-api-key");
            assertThat(AuthHeaderSetting.Header.X_API_KEY.headerName()).isEqualTo("x-api-key");
            assertThat(AuthHeaderSetting.Header.AUTHORIZATION.headerName()).isEqualTo("Authorization");
        }
    }

    @Nested
    class 决定用哪个头 {

        /** 取设置时忽略下游的一切选择 —— 这正是它存在的理由。 */
        @Test
        void configuredModeIgnoresDownstreamEntirely() {
            AuthHeaderSetting setting = new AuthHeaderSetting(
                    AuthHeaderSetting.Mode.CONFIGURED, AuthHeaderSetting.Header.X_API_KEY);

            assertThat(setting.resolveHeader(true, false)).isEqualTo(AuthHeaderSetting.Header.X_API_KEY);
            assertThat(setting.resolveHeader(false, true)).isEqualTo(AuthHeaderSetting.Header.X_API_KEY);
            assertThat(setting.resolveHeader(false, false)).isEqualTo(AuthHeaderSetting.Header.X_API_KEY);
            assertThat(setting.resolveHeader(true, true)).isEqualTo(AuthHeaderSetting.Header.X_API_KEY);
        }

        /** 取下游且下游恰好带一个：认它。 */
        @Test
        void downstreamModeFollowsTheSingleHeaderDownstreamSent() {
            AuthHeaderSetting setting = new AuthHeaderSetting(
                    AuthHeaderSetting.Mode.DOWNSTREAM, AuthHeaderSetting.Header.AUTHORIZATION);

            assertThat(setting.resolveHeader(true, false)).isEqualTo(AuthHeaderSetting.Header.AUTHORIZATION);
            assertThat(setting.resolveHeader(false, true)).isEqualTo(AuthHeaderSetting.Header.X_API_KEY);
        }

        /**
         * 取下游但下游带 0 个或 2 个：无法判断意图，兜底用配置的头。
         *
         * <p>「带 2 个」是真实存在的：同时设了 {@code ANTHROPIC_API_KEY} 与
         * {@code ANTHROPIC_AUTH_TOKEN} 的客户端两个头都会发。此时不能随便取一个 ——
         * 那等于把「哪个头有效」的猜测带回本服务，而本次改动的目的正是取消这类猜测。
         */
        @Test
        void downstreamModeFallsBackWhenTheDownstreamChoiceIsAmbiguous() {
            AuthHeaderSetting setting = new AuthHeaderSetting(
                    AuthHeaderSetting.Mode.DOWNSTREAM, AuthHeaderSetting.Header.X_API_KEY);

            assertThat(setting.resolveHeader(false, false)).isEqualTo(AuthHeaderSetting.Header.X_API_KEY);
            assertThat(setting.resolveHeader(true, true)).isEqualTo(AuthHeaderSetting.Header.X_API_KEY);
        }

        /** 兜底档必须真的两档通用 —— 界面上它被标注为「将作为兜底使用」，而不是灰显。 */
        @Test
        void fallbackHeaderIsHonouredForBothDownstreamModes() {
            AuthHeaderSetting setting = new AuthHeaderSetting(
                    AuthHeaderSetting.Mode.DOWNSTREAM, AuthHeaderSetting.Header.AUTHORIZATION);

            assertThat(setting.resolveHeader(false, false)).isEqualTo(AuthHeaderSetting.Header.AUTHORIZATION);
            assertThat(setting.resolveHeader(true, true)).isEqualTo(AuthHeaderSetting.Header.AUTHORIZATION);
        }
    }
}

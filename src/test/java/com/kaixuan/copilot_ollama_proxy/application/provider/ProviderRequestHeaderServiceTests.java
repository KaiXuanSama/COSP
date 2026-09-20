package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.AuthHeaderSetting;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRequestHeaderServiceTests {

    private static final String API_KEY_HEADER = ProviderRequestHeaderService.API_KEY_HEADER;

    private final ProviderRequestHeaderService service = new ProviderRequestHeaderService(new ObjectMapper());

    @Test
    void applyHeadersAddsDefaultBearerThenAllowsRulesToOverrideAndDeleteIt() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Remove", "obsolete");

        service.applyHeaders(headers, "actual-api-key", """
                [
                  {"key":"Authorization","value":"Custom {apiKey}"},
                  {"key":"X-Provider","value":"generic"},
                  {"key":"X-Remove","value":"/del/"}
                ]
                """, AuthHeaderSetting.defaults());

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Custom actual-api-key");
        assertThat(headers.getFirst("X-Provider")).isEqualTo("generic");
        assertThat(headers).doesNotContainKey("X-Remove");
    }

    @Test
    void applyHeadersUsesBearerAuthenticationWhenThereAreNoRules() {
        HttpHeaders headers = new HttpHeaders();

        service.applyHeaders(headers, "actual-api-key", "[]", AuthHeaderSetting.defaults());

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer actual-api-key");
    }

    @Test
    void buildRequestUrlNormalizesBaseUrlAndPath() {
        assertThat(service.buildRequestUrl(" https://api.example/v1/// ", "models"))
                .isEqualTo("https://api.example/v1/models");
        assertThat(service.buildRequestUrl("https://api.example/v1", "/chat/completions"))
                .isEqualTo("https://api.example/v1/chat/completions");
    }

    @Test
    void applyHeadersIgnoresInvalidRulesWithoutRemovingDefaultAuthentication() {
        HttpHeaders headers = new HttpHeaders();

        service.applyHeaders(headers, "actual-api-key", "not-json", AuthHeaderSetting.defaults());

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer actual-api-key");
    }

    @Test
    void createLogSnapshotPreservesNonSensitiveHeadersAndMasksCredentials() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "text/event-stream");
        headers.set("X-Request-Id", "request-123");
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer actual-api-key");
        headers.set("X-Api-Key", "actual-api-key");
        headers.set("X-Auth-Token", "actual-token");
        headers.set(HttpHeaders.COOKIE, "session=secret-value");

        var snapshot = service.createLogSnapshot(headers);

        assertThat(snapshot).containsEntry(HttpHeaders.ACCEPT, "text/event-stream");
        assertThat(snapshot).containsEntry("X-Request-Id", "request-123");
        assertThat(snapshot).containsEntry(HttpHeaders.AUTHORIZATION, "****");
        assertThat(snapshot).containsEntry("X-Api-Key", "****");
        assertThat(snapshot).containsEntry("X-Auth-Token", "****");
        assertThat(snapshot).containsEntry(HttpHeaders.COOKIE, "****");
        assertThat(snapshot.values()).doesNotContain("actual-api-key", "actual-token", "session=secret-value");
    }

    @Test
    void applyHeadersForwardsSafeDownstreamHeadersThenLetsRulesOverrideOrDeleteThem() {
        HttpHeaders downstreamHeaders = new HttpHeaders();
        downstreamHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer downstream-token");
        downstreamHeaders.set(HttpHeaders.COOKIE, "session=downstream-cookie");
        downstreamHeaders.set(HttpHeaders.USER_AGENT, "DownstreamClient/1.0");
        downstreamHeaders.set("X-Trace-Id", "trace-123");
        downstreamHeaders.set(HttpHeaders.HOST, "localhost:11434");
        downstreamHeaders.set(HttpHeaders.CONTENT_LENGTH, "99999");
        downstreamHeaders.set(HttpHeaders.CONNECTION, "keep-alive");

        HttpHeaders headers = new HttpHeaders();
        service.applyHeaders(headers, downstreamHeaders, "provider-api-key", """
                [
                  {"key":"Authorization","value":"Provider {apiKey}"},
                  {"key":"Cookie","value":"/del/"},
                  {"key":"X-Trace-Id","value":"provider-trace"}
                ]
                """, true, AuthHeaderSetting.defaults());

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Provider provider-api-key");
        assertThat(headers).doesNotContainKey(HttpHeaders.COOKIE);
        assertThat(headers.getFirst(HttpHeaders.USER_AGENT)).isEqualTo("DownstreamClient/1.0");
        assertThat(headers.getFirst("X-Trace-Id")).isEqualTo("provider-trace");
        assertThat(headers.getFirst(HttpHeaders.ACCEPT)).isEqualTo(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(headers).doesNotContainKeys(HttpHeaders.HOST, HttpHeaders.CONTENT_LENGTH, HttpHeaders.CONNECTION);
    }

    @Test
    void applyHeadersUsesProviderApiKeyInsteadOfForwardedDownstreamAuthorization() {
        HttpHeaders downstreamHeaders = new HttpHeaders();
        downstreamHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer downstream-token");
        downstreamHeaders.set("X-Trace-Id", "trace-123");

        HttpHeaders headers = new HttpHeaders();
        service.applyHeaders(headers, downstreamHeaders, "provider-api-key", "[]", false,
                AuthHeaderSetting.defaults());

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer provider-api-key");
        assertThat(headers.getFirst("X-Trace-Id")).isEqualTo("trace-123");
    }

    /**
     * 鉴权头按<strong>供应商级配置</strong>装配：决定头名、删两个、注一个。
     *
     * <h2>判据从协议换成了配置</h2>
     * 这批用例此前叫 {@code AuthenticationHeadersByUpstreamProtocol}，断言全部以
     * 「上游是哪个协议」为判据。那条映射实测不成立（头名由下游用的凭据变量决定，
     * 且部分中转站只认 {@code Authorization}），因此整批重排 —— 不是修断言，
     * 是换判据。
     *
     * <h2>每条都要能单独红</h2>
     * 「取下游」模式下若把配置那一维写成恒 {@code AUTHORIZATION}，兜底类用例就与
     * 「恒发 Bearer」的实现区分不开。故凡是验兜底的，配置一律给
     * {@link AuthHeaderSetting.Header#X_API_KEY} —— 那是默认值的反面。
     */
    @Nested
    class AuthenticationHeaderAssembly {

        /** 取设置 + Authorization：下游带的 x-api-key 被忽略且不出站。 */
        @Test
        void configuredModeUsesTheConfiguredAuthorizationHeader() {
            HttpHeaders downstreamHeaders = new HttpHeaders();
            downstreamHeaders.set(API_KEY_HEADER, "downstream-chosen-key");

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaders(headers, downstreamHeaders, "provider-api-key", "[]", false,
                    configured(AuthHeaderSetting.Header.AUTHORIZATION));

            assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer provider-api-key");
            assertThat(headers).doesNotContainKey(API_KEY_HEADER);
        }

        /** 取设置 + x-api-key：下游带的 Authorization 被忽略且不出站。 */
        @Test
        void configuredModeUsesTheConfiguredApiKeyHeader() {
            HttpHeaders downstreamHeaders = new HttpHeaders();
            downstreamHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer downstream-chosen-key");

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaders(headers, downstreamHeaders, "provider-api-key", "[]", false,
                    configured(AuthHeaderSetting.Header.X_API_KEY));

            assertThat(headers.getFirst(API_KEY_HEADER)).isEqualTo("provider-api-key");
            assertThat(headers).doesNotContainKey(HttpHeaders.AUTHORIZATION);
        }

        /**
         * 取下游 + 下游只带 Authorization：发 Bearer，<strong>配置的那一维不生效</strong>。
         *
         * <p>配置刻意给 x-api-key —— 这一条正是「取下游」的全部意义所在：
         * 下游已经替用户做了选择，而它比本服务更清楚自己用的是哪个凭据变量。
         * 若实现忘了读探测结果而直接用配置值，这条会红。
         */
        @Test
        void downstreamModeFollowsTheSingleDownstreamAuthorization() {
            HttpHeaders downstreamHeaders = new HttpHeaders();
            downstreamHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer downstream-chosen-key");

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaders(headers, downstreamHeaders, "provider-api-key", "[]", false,
                    downstream(AuthHeaderSetting.Header.X_API_KEY));

            assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer provider-api-key");
            assertThat(headers).doesNotContainKey(API_KEY_HEADER);
        }

        /**
         * 取下游 + 下游只带 x-api-key：发 x-api-key。
         *
         * <p>这是本次改动要修的那个场景（Claude Code 用 {@code ANTHROPIC_API_KEY} 时
         * 只发 x-api-key），配置给默认的 Authorization 以证明探测结果优先。
         */
        @Test
        void downstreamModeFollowsTheSingleDownstreamApiKey() {
            HttpHeaders downstreamHeaders = new HttpHeaders();
            downstreamHeaders.set(API_KEY_HEADER, "downstream-chosen-key");

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaders(headers, downstreamHeaders, "provider-api-key", "[]", false,
                    downstream(AuthHeaderSetting.Header.AUTHORIZATION));

            assertThat(headers.getFirst(API_KEY_HEADER)).isEqualTo("provider-api-key");
            assertThat(headers).doesNotContainKey(HttpHeaders.AUTHORIZATION);
        }

        /** 取下游但下游一个都没带：无从跟随，兜底用配置的头。 */
        @Test
        void downstreamModeFallsBackWhenDownstreamSentNeither() {
            HttpHeaders headers = new HttpHeaders();
            service.applyHeaders(headers, new HttpHeaders(), "provider-api-key", "[]", false,
                    downstream(AuthHeaderSetting.Header.X_API_KEY));

            assertThat(headers.getFirst(API_KEY_HEADER)).isEqualTo("provider-api-key");
            assertThat(headers).doesNotContainKey(HttpHeaders.AUTHORIZATION);
        }

        /**
         * 取下游但下游两个都带：同样无从判断意图，兜底。
         *
         * <p>「两个都带」是真实存在的 —— 同时设了 {@code ANTHROPIC_API_KEY} 与
         * {@code ANTHROPIC_AUTH_TOKEN} 的客户端两个头都会发。此时随便取一个等于
         * 把「哪个头有效」的猜测搬回代码里。
         */
        @Test
        void downstreamModeFallsBackWhenDownstreamSentBoth() {
            HttpHeaders downstreamHeaders = new HttpHeaders();
            downstreamHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer downstream-token");
            downstreamHeaders.set(API_KEY_HEADER, "downstream-key");

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaders(headers, downstreamHeaders, "provider-api-key", "[]", false,
                    downstream(AuthHeaderSetting.Header.X_API_KEY));

            assertThat(headers.getFirst(API_KEY_HEADER)).isEqualTo("provider-api-key");
            assertThat(headers).doesNotContainKey(HttpHeaders.AUTHORIZATION);
        }

        /**
         * 空白值不算「下游做了选择」。
         *
         * <p>{@code HttpHeaders.containsHeader} 只判键在不在，用它会把
         * {@code Authorization: ""} 当成一次表态，于是这里会误判成「两个都带」而走兜底
         * （配置是 Authorization，断言就会看到 Bearer）。按值判空才会正确地认出
         * 下游只表达了 x-api-key 这一种。
         */
        @Test
        void blankDownstreamHeaderDoesNotCountAsPresent() {
            HttpHeaders downstreamHeaders = new HttpHeaders();
            downstreamHeaders.set(HttpHeaders.AUTHORIZATION, "   ");
            downstreamHeaders.set(API_KEY_HEADER, "downstream-chosen-key");

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaders(headers, downstreamHeaders, "provider-api-key", "[]", false,
                    downstream(AuthHeaderSetting.Header.AUTHORIZATION));

            assertThat(headers.getFirst(API_KEY_HEADER)).isEqualTo("provider-api-key");
            assertThat(headers).doesNotContainKey(HttpHeaders.AUTHORIZATION);
        }

        /**
         * 下游的凭据<strong>值</strong>永不出站，与选了哪个头名正交。
         *
         * <p>这是安全性质而非形态约定：跟随下游的是「用哪个头」，绝不是「用哪把 key」。
         * 实现若把「跟随」误解成「保留下游那个头不动」，头名断言仍会通过，只有这条会红。
         */
        @Test
        void downstreamCredentialValueNeverReachesUpstream() {
            for (AuthHeaderSetting setting : new AuthHeaderSetting[] {
                    downstream(AuthHeaderSetting.Header.AUTHORIZATION),
                    downstream(AuthHeaderSetting.Header.X_API_KEY),
                    configured(AuthHeaderSetting.Header.AUTHORIZATION),
                    configured(AuthHeaderSetting.Header.X_API_KEY)}) {
                HttpHeaders downstreamHeaders = new HttpHeaders();
                downstreamHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer downstream-secret");
                downstreamHeaders.set(API_KEY_HEADER, "downstream-secret");

                HttpHeaders headers = new HttpHeaders();
                service.applyHeaders(headers, downstreamHeaders, "provider-api-key", "[]", false, setting);

                assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION))
                        .as("配置: %s", setting).isNotEqualTo("Bearer downstream-secret");
                assertThat(headers.getFirst(API_KEY_HEADER))
                        .as("配置: %s", setting).isNotEqualTo("downstream-secret");
            }
        }

        /**
         * 无下游上下文的重载（模型拉取走这条）：两种模式都用配置的头。
         *
         * <p>没有下游请求就没有可跟随的选择，「取下游」在这里必然落到兜底 ——
         * 这正是那一档下配置项仍有意义的证明。
         */
        @Test
        void headerlessOverloadAlwaysUsesTheConfiguredHeader() {
            for (AuthHeaderSetting setting : new AuthHeaderSetting[] {
                    downstream(AuthHeaderSetting.Header.X_API_KEY),
                    configured(AuthHeaderSetting.Header.X_API_KEY)}) {
                HttpHeaders headers = new HttpHeaders();

                service.applyHeaders(headers, "provider-api-key", "[]", setting);

                assertThat(headers.getFirst(API_KEY_HEADER)).as("配置: %s", setting)
                        .isEqualTo("provider-api-key");
                assertThat(headers).as("配置: %s", setting).doesNotContainKey(HttpHeaders.AUTHORIZATION);
            }
        }

        /**
         * 规则层保留最终决定权：需要双头并存的中转站可以把被删的那个加回来。
         *
         * 装配刻意在规则之前执行，正是为了留出这个出口 —— 默认给配置的那一种，
         * 特例交给规则。
         */
        @Test
        void rulesRunAfterAssemblyAndCanRestoreTheDroppedHeader() {
            HttpHeaders headers = new HttpHeaders();

            service.applyHeaders(headers, "provider-api-key", """
                    [{"key":"Authorization","value":"Bearer {apiKey}"}]
                    """, configured(AuthHeaderSetting.Header.X_API_KEY));

            assertThat(headers.getFirst(API_KEY_HEADER)).isEqualTo("provider-api-key");
            assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer provider-api-key");
        }

        /** 规则也能反向删掉装配出来的鉴权头，用于自带凭据在别处的上游。 */
        @Test
        void rulesCanDeleteTheAssembledAuthenticationHeader() {
            HttpHeaders headers = new HttpHeaders();

            service.applyHeaders(headers, "provider-api-key", """
                    [{"key":"x-api-key","value":"/del/"}]
                    """, configured(AuthHeaderSetting.Header.X_API_KEY));

            assertThat(headers).doesNotContainKey(API_KEY_HEADER);
            assertThat(headers).doesNotContainKey(HttpHeaders.AUTHORIZATION);
        }

        private AuthHeaderSetting downstream(AuthHeaderSetting.Header header) {
            return new AuthHeaderSetting(AuthHeaderSetting.Mode.DOWNSTREAM, header);
        }

        private AuthHeaderSetting configured(AuthHeaderSetting.Header header) {
            return new AuthHeaderSetting(AuthHeaderSetting.Mode.CONFIGURED, header);
        }
    }
}
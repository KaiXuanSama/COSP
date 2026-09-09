package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRequestHeaderServiceTests {

    private static final String ANTHROPIC_KEY_HEADER = ProviderRequestHeaderService.ANTHROPIC_API_KEY_HEADER;

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
                """, WireProtocol.OPENAI);

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Custom actual-api-key");
        assertThat(headers.getFirst("X-Provider")).isEqualTo("generic");
        assertThat(headers).doesNotContainKey("X-Remove");
    }

    @Test
    void applyHeadersUsesBearerAuthenticationWhenThereAreNoRules() {
        HttpHeaders headers = new HttpHeaders();

        service.applyHeaders(headers, "actual-api-key", "[]", WireProtocol.OPENAI);

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

        service.applyHeaders(headers, "actual-api-key", "not-json", WireProtocol.OPENAI);

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
                """, true, WireProtocol.OPENAI);

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
                WireProtocol.OPENAI);

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer provider-api-key");
        assertThat(headers.getFirst("X-Trace-Id")).isEqualTo("trace-123");
    }

    /**
     * 鉴权头按出站协议装配：写本协议那一个、删另一个。
     *
     * 判据是上游协议而非下游协议 —— 翻译路线上两者不同，而鉴权头必须匹配真正收到
     * 这个请求的那一端。这些用例同时钉住「删噪音」：另一种协议的鉴权头无论来自下游透传
     * 还是翻译残留，都不该出站。
     */
    @Nested
    class AuthenticationHeadersByUpstreamProtocol {

        @Test
        void openAiUpstreamSendsBearerAndDropsAnthropicKey() {
            HttpHeaders headers = new HttpHeaders();

            service.applyHeaders(headers, "provider-api-key", "[]", WireProtocol.OPENAI);

            assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer provider-api-key");
            assertThat(headers).doesNotContainKey(ANTHROPIC_KEY_HEADER);
        }

        @Test
        void anthropicUpstreamSendsApiKeyAndDropsAuthorization() {
            HttpHeaders headers = new HttpHeaders();

            service.applyHeaders(headers, "provider-api-key", "[]", WireProtocol.ANTHROPIC);

            assertThat(headers.getFirst(ANTHROPIC_KEY_HEADER)).isEqualTo("provider-api-key");
            assertThat(headers).doesNotContainKey(HttpHeaders.AUTHORIZATION);
        }

        /** 下游按 Anthropic 惯例带来的 x-api-key 不得泄露给 OpenAI 上游。 */
        @Test
        void openAiUpstreamDropsForwardedDownstreamAnthropicKey() {
            HttpHeaders downstreamHeaders = new HttpHeaders();
            downstreamHeaders.set(ANTHROPIC_KEY_HEADER, "downstream-leaked-key");
            downstreamHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer downstream-gateway-key");

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaders(headers, downstreamHeaders, "provider-api-key", "[]", false,
                    WireProtocol.OPENAI);

            assertThat(headers).doesNotContainKey(ANTHROPIC_KEY_HEADER);
            assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer provider-api-key");
        }

        /**
         * 下游携带 x-api-key 时，供应商 key 仍必须覆盖它。
         *
         * 这一条曾经不成立：x-api-key 那一侧是「缺失才设」，下游带了就补不进去，
         * 请求会带着下游的值打到上游，而排查时 Authorization 看起来是对的。
         */
        @Test
        void anthropicUpstreamOverridesForwardedDownstreamApiKey() {
            HttpHeaders downstreamHeaders = new HttpHeaders();
            downstreamHeaders.set(ANTHROPIC_KEY_HEADER, "downstream-leaked-key");
            downstreamHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer downstream-gateway-key");

            HttpHeaders headers = new HttpHeaders();
            service.applyHeaders(headers, downstreamHeaders, "provider-api-key", "[]", false,
                    WireProtocol.ANTHROPIC);

            assertThat(headers.getFirst(ANTHROPIC_KEY_HEADER)).isEqualTo("provider-api-key");
            assertThat(headers).doesNotContainKey(HttpHeaders.AUTHORIZATION);
        }

        /**
         * 规则层保留最终决定权：需要双头并存的中转站可以把被删的那个加回来。
         *
         * 装配刻意在规则之前执行，正是为了留出这个出口 —— 默认给协议上正确的那一种，
         * 特例交给规则。
         */
        @Test
        void headerRulesCanRestoreTheDroppedAuthenticationHeader() {
            HttpHeaders headers = new HttpHeaders();

            service.applyHeaders(headers, "provider-api-key", """
                    [{"key":"Authorization","value":"Bearer {apiKey}"}]
                    """, WireProtocol.ANTHROPIC);

            assertThat(headers.getFirst(ANTHROPIC_KEY_HEADER)).isEqualTo("provider-api-key");
            assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer provider-api-key");
        }

        /** 规则也能反向删掉本协议的鉴权头，用于自带凭据在别处的上游。 */
        @Test
        void headerRulesCanDeleteTheProtocolAuthenticationHeader() {
            HttpHeaders headers = new HttpHeaders();

            service.applyHeaders(headers, "provider-api-key", """
                    [{"key":"x-api-key","value":"/del/"}]
                    """, WireProtocol.ANTHROPIC);

            assertThat(headers).doesNotContainKey(ANTHROPIC_KEY_HEADER);
            assertThat(headers).doesNotContainKey(HttpHeaders.AUTHORIZATION);
        }
    }
}
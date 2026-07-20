package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRequestHeaderServiceTests {

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
                """);

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Custom actual-api-key");
        assertThat(headers.getFirst("X-Provider")).isEqualTo("generic");
        assertThat(headers).doesNotContainKey("X-Remove");
    }

    @Test
    void applyHeadersUsesBearerAuthenticationWhenThereAreNoRules() {
        HttpHeaders headers = new HttpHeaders();

        service.applyHeaders(headers, "actual-api-key", "[]");

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

        service.applyHeaders(headers, "actual-api-key", "not-json");

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
                """, true);

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
        service.applyHeaders(headers, downstreamHeaders, "provider-api-key", "[]", false);

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer provider-api-key");
        assertThat(headers.getFirst("X-Trace-Id")).isEqualTo("trace-123");
    }
}
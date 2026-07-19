package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

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
}
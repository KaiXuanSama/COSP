package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTransformEngineTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void applyHeaderRulesAddsOverridesDeletesAndResolvesApiKeyTemplate() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("old-key");
        headers.set("X-Remove", "obsolete");

        RequestTransformEngine.applyHeaderRules(headers, "actual-api-key", """
                [
                  {"key":"Authorization","value":"Custom {apiKey}"},
                  {"key":"X-Provider","value":"mimo-user"},
                  {"key":"X-Remove","value":"/del/"}
                ]
                """, objectMapper);

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Custom actual-api-key");
        assertThat(headers.getFirst("X-Provider")).isEqualTo("mimo-user");
        assertThat(headers).doesNotContainKey("X-Remove");
    }

    @Test
    void applyHeaderRulesDoesNotReadLegacyCustomTransformsObject() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Existing", "keep");

        RequestTransformEngine.applyHeaderRules(headers, "actual-api-key", """
                {"custom_headers":[{"key":"X-Legacy","value":"must-not-apply"}]}
                """, objectMapper);

        assertThat(headers.getFirst("X-Existing")).isEqualTo("keep");
        assertThat(headers).doesNotContainKey("X-Legacy");
    }

    @Test
    void applyHeaderRulesIgnoresInvalidJsonWithoutChangingHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Existing", "keep");

        RequestTransformEngine.applyHeaderRules(headers, "actual-api-key", "not-json", objectMapper);

        assertThat(headers.getFirst("X-Existing")).isEqualTo("keep");
    }
}

package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GenericOpenAiChatServiceRequestBodyRulesTests {

    @Test
    void customizeRequestBodyUsesNewRuleSetAndDoesNotExecuteLegacyBodyTransforms() {
        ProviderRuntimeConfiguration configuration = new ProviderRuntimeConfiguration(
                "mimo-user", "https://api.example/v1", "key", "openai", List.of(),
                "[]", """
                        {"version":1,"rules":[{
                          "id":"new-rule","order":0,"field":"temperature","array":false,
                          "conditional":false,"conditionMode":"all","conditions":[],
                          "operations":[{"type":"set_value","value":0.2}]
                        }]}
                        """);
        TestGenericOpenAiChatService service = new TestGenericOpenAiChatService(configuration);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("temperature", 0.1);

        service.applyBodyRules(body);

        assertThat(body).containsEntry("temperature", 0.2);
    }

    @Test
    void customizeRequestBodyAppliesMimoImageToolMessageRuleFromNewTableConfiguration() {
        ProviderRuntimeConfiguration configuration = new ProviderRuntimeConfiguration(
                "mimo-user", "https://api.example/v1", "key", "openai", List.of(),
            "[]", mimoImageToolRuleSet());
        TestGenericOpenAiChatService service = new TestGenericOpenAiChatService(configuration);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", List.of(new LinkedHashMap<>(Map.of(
                "role", "tool",
                "tool_call_id", "call-image",
                "content", List.of(Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,abc")))))));

        service.applyBodyRules(body);

        @SuppressWarnings("unchecked")
        Map<String, Object> message = ((List<Map<String, Object>>) body.get("messages")).getFirst();
        assertThat(message).containsEntry("role", "user").doesNotContainKey("tool_call_id");
    }

    private String mimoImageToolRuleSet() {
        return """
                {"version":1,"rules":[{
                  "id":"mimo-messages","order":0,"field":"messages","array":true,"conditional":true,
                  "conditionMode":"all","conditions":[
                    {"path":"./content[*]/image_url","operator":"exists","value":null},
                    {"path":"./role","operator":"equals","value":"tool"}
                  ],"operations":[{"type":"edit_object","rules":[
                    {"id":"mimo-role","order":0,"field":"role","array":false,"conditional":false,
                     "conditionMode":"all","conditions":[],"operations":[{"type":"set_value","value":"user"}]},
                    {"id":"mimo-tool-call-id","order":1,"field":"tool_call_id","array":false,"conditional":false,
                     "conditionMode":"all","conditions":[],"operations":[{"type":"delete"}]}
                  ]}]
                }]}
                """;
    }

    private static final class TestGenericOpenAiChatService extends GenericOpenAiChatService {

        private final ProviderRuntimeConfiguration configuration;

        private TestGenericOpenAiChatService(ProviderRuntimeConfiguration configuration) {
            super((RuntimeProviderCatalog) List::of, new ObjectMapper());
            this.configuration = configuration;
        }

        private void applyBodyRules(Map<String, Object> body) {
            customizeRequestBody(body, "mimo-v2.5-pro");
        }

        @Override
        protected ProviderRuntimeConfiguration getActiveProviderConfiguration() {
            return configuration;
        }
    }
}

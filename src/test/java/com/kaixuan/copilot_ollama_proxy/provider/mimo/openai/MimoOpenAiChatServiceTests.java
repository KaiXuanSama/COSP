package com.kaixuan.copilot_ollama_proxy.provider.mimo.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MimoOpenAiChatServiceTests {

    @Test
    void convertsImageMessagesForMimoModels() {
        TestableMimoOpenAiChatService service = new TestableMimoOpenAiChatService(List::of);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(new LinkedHashMap<>(Map.of("role", "tool", "tool_call_id", "call_1", "content",
                List.of(new LinkedHashMap<>(Map.of("type", "image_url", "image_url", new LinkedHashMap<>(Map.of("url", "https://example.com/a.png", "media_type", "image/png")))))))));

        Map<String, Object> prepared = service.exposePrepareRequestBody(request, true, "mimo-v2.5-pro");

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) ((List<?>) prepared.get("messages")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> contentItem = (Map<String, Object>) ((List<?>) message.get("content")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> imageUrl = (Map<String, Object>) contentItem.get("image_url");

        assertThat(message.get("role")).isEqualTo("user");
        assertThat(message).doesNotContainKey("tool_call_id");
        assertThat(imageUrl).containsEntry("type", "image_url");
        assertThat(imageUrl).doesNotContainKey("media_type");
    }

    private static final class TestableMimoOpenAiChatService extends MimoOpenAiChatService {

        private TestableMimoOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog) {
            super(runtimeProviderCatalog, "mimo-v2.5-pro", new ObjectMapper());
        }

        private Map<String, Object> exposePrepareRequestBody(Map<String, Object> request, boolean stream, String model) {
            return prepareRequestBody(request, stream, model);
        }
    }
}
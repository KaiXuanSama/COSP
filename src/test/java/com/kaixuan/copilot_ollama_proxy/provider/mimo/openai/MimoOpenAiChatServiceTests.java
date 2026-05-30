package com.kaixuan.copilot_ollama_proxy.provider.mimo.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ReasoningCacheRepository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MimoOpenAiChatServiceTests {

    @Test
    void convertsImageMessagesForMimoModels() {
        TestableMimoOpenAiChatService service = new TestableMimoOpenAiChatService(List::of, null);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(new LinkedHashMap<>(Map.of("role", "tool", "tool_call_id", "call_1", "content",
                List.of(new LinkedHashMap<>(Map.of("type", "image_url", "image_url", new LinkedHashMap<>(Map.of("url", "https://example.com/a.png", "media_type", "image/png")))))))));

        Map<String, Object> prepared = service.exposePrepareRequestBody(request, true, "mimo-v2.5-pro");

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) ((List<?>) prepared.get("messages")).get(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> contentItem = (Map<String, Object>) ((List<?>) message.get("content")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> imageUrl = (Map<String, Object>) contentItem.get("image_url");

        assertThat(message.get("role")).isEqualTo("user");
        assertThat(message).doesNotContainKey("tool_call_id");
        assertThat(imageUrl).containsEntry("type", "image_url");
        assertThat(imageUrl).doesNotContainKey("media_type");
    }

    @Test
    void injectsReasoningContentFromCacheForAssistantToolCalls() {
        // 创建一个 ReasoningCacheRepository 模拟，当查询 "call_xyz" 时返回缓存的思考链
        ReasoningCacheRepository mockRepo = new ReasoningCacheRepository(null) {
            @Override
            public String findByToolCallId(String toolCallId) {
                return "call_xyz".equals(toolCallId) ? "cached reasoning content" : null;
            }
        };
        TestableMimoOpenAiChatService service = new TestableMimoOpenAiChatService(List::of, mockRepo);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                new LinkedHashMap<>(Map.of("role", "system", "content", "You are an assistant.")),
                new LinkedHashMap<>(Map.of("role", "assistant", "content", "",
                        "tool_calls", List.of(
                                Map.of("id", "call_xyz", "type", "function",
                                        "function", Map.of("name", "test", "arguments", "{}"))
                        ))
                )
        ));

        Map<String, Object> prepared = service.exposePrepareRequestBody(request, false, "mimo-v2.5-pro");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) prepared.get("messages");
        Map<String, Object> assistantMsg = messages.stream()
                .filter(m -> "assistant".equals(m.get("role")))
                .findFirst().orElseThrow();

        // 验证已注入 reasoning_content
        assertThat(assistantMsg).containsKey("reasoning_content");
        assertThat(assistantMsg.get("reasoning_content")).isEqualTo("cached reasoning content");
    }

    @Test
    void injectsEmptyReasoningContentWhenCacheMisses() {
        ReasoningCacheRepository mockRepo = new ReasoningCacheRepository(null) {
            @Override
            public String findByToolCallId(String toolCallId) {
                return null; // 缓存未命中
            }
        };
        TestableMimoOpenAiChatService service = new TestableMimoOpenAiChatService(List::of, mockRepo);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", List.of(
                new LinkedHashMap<>(Map.of("role", "system", "content", "You are an assistant.")),
                new LinkedHashMap<>(Map.of("role", "assistant", "content", "",
                        "tool_calls", List.of(
                                Map.of("id", "call_miss", "type", "function",
                                        "function", Map.of("name", "test", "arguments", "{}"))
                        ))
                )
        ));

        Map<String, Object> prepared = service.exposePrepareRequestBody(request, false, "mimo-v2.5-pro");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) prepared.get("messages");
        Map<String, Object> assistantMsg = messages.stream()
                .filter(m -> "assistant".equals(m.get("role")))
                .findFirst().orElseThrow();

        assertThat(assistantMsg).containsKey("reasoning_content");
        assertThat(assistantMsg.get("reasoning_content")).isEqualTo(""); // 保底空字符串
    }

    private static final class TestableMimoOpenAiChatService extends MimoOpenAiChatService {

        private TestableMimoOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog,
                                              ReasoningCacheRepository reasoningCacheRepository) {
            super(runtimeProviderCatalog, "mimo-v2.5-pro", new ObjectMapper(), reasoningCacheRepository);
        }

        private Map<String, Object> exposePrepareRequestBody(Map<String, Object> request, boolean stream, String model) {
            return prepareRequestBody(request, stream, model);
        }
    }
}
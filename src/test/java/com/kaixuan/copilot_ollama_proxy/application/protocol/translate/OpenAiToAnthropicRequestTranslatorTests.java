package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.TranslatedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OpenAiToAnthropicRequestTranslator} 的单元测试。
 *
 * <p>本测试类覆盖契约里的两条必须先钉测试的不变式（第 8.1 节）：
 * <ol>
 *   <li>无损搬运「下游已表态」—— 丢了 {@code reasoning_effort} 会让设置层兜底档
 *       静默退化成覆写档（第 2 节）</li>
 *   <li>{@code tool_use} / {@code tool_result} 配对修复的 merge → pair → merge
 *       三步顺序（第 3.5 节）</li>
 * </ol>
 */
class OpenAiToAnthropicRequestTranslatorTests {

    private OpenAiToAnthropicRequestTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new OpenAiToAnthropicRequestTranslator(new ObjectMapper());
    }

    @Nested
    @DisplayName("不变式 1：无损搬运「下游已表态」")
    class LosslessOpinionCarryover {

        @Test
        @DisplayName("reasoning_effort 必须搬运，否则兜底档退化成覆写档")
        void shouldCarryReasoningEffort() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "reasoning_effort", "low",
                    "messages", List.of(Map.of("role", "user", "content", "test"))
            );

            TranslatedRequest translated = translator.translateRequest(request);

            // 必须同时存在两个字段：
            // 1. output_config.effort 供上游消费（Anthropic 的深度字段）
            // 2. reasoning_effort 供设置层判定「下游已表态」（那一层是为 OpenAI 侧写的）
            assertTrue(translated.body().containsKey("reasoning_effort"),
                    "reasoning_effort 必须保留供设置层判定");
            assertTrue(translated.body().containsKey("output_config"),
                    "output_config 必须存在");
            @SuppressWarnings("unchecked")
            Map<String, Object> outputConfig = (Map<String, Object>) translated.body().get("output_config");
            assertEquals("low", outputConfig.get("effort"),
                    "output_config.effort 必须从 reasoning_effort 映射过来");
        }

        @Test
        @DisplayName("thinking 字段必须原样搬运")
        void shouldCarryThinking() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "thinking", Map.of("type", "disabled"),
                    "messages", List.of(Map.of("role", "user", "content", "test"))
            );

            TranslatedRequest translated = translator.translateRequest(request);

            assertTrue(translated.body().containsKey("thinking"));
            @SuppressWarnings("unchecked")
            Map<String, Object> thinking = (Map<String, Object>) translated.body().get("thinking");
            assertEquals("disabled", thinking.get("type"));
        }

        @Test
        @DisplayName("reasoning_effort 与 thinking 同时存在时都必须搬运")
        void shouldCarryBothWhenPresent() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "reasoning_effort", "medium",
                    "thinking", Map.of("type", "enabled"),
                    "messages", List.of(Map.of("role", "user", "content", "test"))
            );

            TranslatedRequest translated = translator.translateRequest(request);

            assertTrue(translated.body().containsKey("reasoning_effort"));
            assertTrue(translated.body().containsKey("thinking"));
            assertTrue(translated.body().containsKey("output_config"));
        }

        @Test
        @DisplayName("下游没表态时不应凭空补字段")
        void shouldNotInjectWhenDownstreamDidNot() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "messages", List.of(Map.of("role", "user", "content", "test"))
            );

            TranslatedRequest translated = translator.translateRequest(request);

            assertFalse(translated.body().containsKey("reasoning_effort"),
                    "下游没带就不该有，交给设置层");
            assertFalse(translated.body().containsKey("thinking"),
                    "下游没带就不该有，交给设置层");
            assertFalse(translated.body().containsKey("output_config"),
                    "下游没带深度时也不该有 output_config");
        }
    }

    @Nested
    @DisplayName("不变式 2：tool_use / tool_result 配对修复")
    class ToolPairingNormalization {

        @Test
        @DisplayName("未被应答的 tool_use 必须丢弃")
        void shouldDropUnansweredToolUse() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "messages", List.of(
                            Map.of("role", "user", "content", "call tool"),
                            Map.of("role", "assistant", "content", "",
                                    "tool_calls", List.of(
                                            Map.of("id", "call_1", "type", "function",
                                                    "function", Map.of("name", "tool_a", "arguments", "{}"))
                                    ))
                            // 缺失 tool_call_id=call_1 的 tool 消息 → 未被应答
                    )
            );

            TranslatedRequest translated = translator.translateRequest(request);

            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) translated.body().get("messages");
            // 原本两条消息：user + assistant。assistant 只有未被应答的 tool_use 且无其他内容 → 整条丢掉。
            assertEquals(1, messages.size(), "未被应答的 assistant 消息应被整条丢弃");
        }

        @Test
        @DisplayName("tool_result 必须紧邻前一条 assistant 的 tool_use")
        void shouldPlaceToolResultsAdjacentToToolUses() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "messages", List.of(
                            Map.of("role", "assistant", "content", "",
                                    "tool_calls", List.of(
                                            Map.of("id", "call_1", "type", "function",
                                                    "function", Map.of("name", "tool_a", "arguments", "{}"))
                                    )),
                            Map.of("role", "user", "content", "some text"),
                            Map.of("role", "tool", "tool_call_id", "call_1", "content", "result")
                    )
            );

            TranslatedRequest translated = translator.translateRequest(request);

            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) translated.body().get("messages");
            // 修复后顺序：assistant（tool_use）→ user（tool_result + 正文合并）
            // 原本的正文 user 与 tool_result user 会被合并成一条
            assertEquals(2, messages.size());
            @SuppressWarnings("unchecked")
            Map<String, Object> firstMessage = (Map<String, Object>) messages.get(0);
            assertEquals("assistant", firstMessage.get("role"));

            @SuppressWarnings("unchecked")
            Map<String, Object> secondMessage = (Map<String, Object>) messages.get(1);
            assertEquals("user", secondMessage.get("role"));
            @SuppressWarnings("unchecked")
            List<Object> secondContent = (List<Object>) secondMessage.get("content");
            @SuppressWarnings("unchecked")
            Map<String, Object> firstBlock = (Map<String, Object>) secondContent.get(0);
            assertEquals("tool_result", firstBlock.get("type"),
                    "tool_result 必须紧邻 assistant");
        }

        @Test
        @DisplayName("孤儿 tool_result（无对应 tool_use）必须丢弃")
        void shouldDropOrphanToolResults() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "messages", List.of(
                            Map.of("role", "user", "content", "test"),
                            // 这个 tool_result 没有对应的 tool_use
                            Map.of("role", "tool", "tool_call_id", "orphan_id", "content", "result")
                    )
            );

            TranslatedRequest translated = translator.translateRequest(request);

            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) translated.body().get("messages");
            // 孤儿 tool_result 所在的消息应被丢弃（因为剥掉 tool_result 后该消息为空）
            assertEquals(1, messages.size(), "孤儿 tool_result 应被丢弃");
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) messages.get(0);
            assertEquals("user", message.get("role"));
            // 合并时会升格成块数组
            @SuppressWarnings("unchecked")
            List<Object> content = (List<Object>) message.get("content");
            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) content.get(0);
            assertEquals("text", block.get("type"));
            assertEquals("test", block.get("text"));
        }

        @Test
        @DisplayName("并行调用的 tool_result 顺序必须与 tool_use 一致")
        void shouldPreserveParallelCallOrder() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "messages", List.of(
                            Map.of("role", "assistant", "content", "",
                                    "tool_calls", List.of(
                                            Map.of("id", "call_1", "type", "function",
                                                    "function", Map.of("name", "tool_a", "arguments", "{}")),
                                            Map.of("id", "call_2", "type", "function",
                                                    "function", Map.of("name", "tool_b", "arguments", "{}"))
                                    )),
                            // 结果的顺序与调用相反
                            Map.of("role", "tool", "tool_call_id", "call_2", "content", "result_b"),
                            Map.of("role", "tool", "tool_call_id", "call_1", "content", "result_a")
                    )
            );

            TranslatedRequest translated = translator.translateRequest(request);

            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) translated.body().get("messages");
            @SuppressWarnings("unchecked")
            Map<String, Object> userMessage = (Map<String, Object>) messages.get(1);
            @SuppressWarnings("unchecked")
            List<Object> content = (List<Object>) userMessage.get("content");

            // 顺序必须重新按 tool_use 的出现顺序排列：call_1 在前、call_2 在后
            assertEquals(2, content.size());
            @SuppressWarnings("unchecked")
            Map<String, Object> firstResult = (Map<String, Object>) content.get(0);
            assertEquals("call_1", firstResult.get("tool_use_id"),
                    "tool_result 顺序必须与 tool_use 一致");
            @SuppressWarnings("unchecked")
            Map<String, Object> secondResult = (Map<String, Object>) content.get(1);
            assertEquals("call_2", secondResult.get("tool_use_id"));
        }
    }

    @Nested
    @DisplayName("顶层字段映射")
    class TopLevelFields {

        @Test
        @DisplayName("model / stream / max_tokens 原样搬运")
        void shouldCopyBasicFields() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "stream", true,
                    "max_tokens", 1000,
                    "messages", List.of(Map.of("role", "user", "content", "test"))
            );

            TranslatedRequest translated = translator.translateRequest(request);

            assertEquals("test-model", translated.body().get("model"));
            assertEquals(true, translated.body().get("stream"));
            assertEquals(1000, translated.body().get("max_tokens"));
        }

        @Test
        @DisplayName("stop 字符串转单元素数组")
        void shouldConvertStopStringToArray() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "stop", "END",
                    "messages", List.of(Map.of("role", "user", "content", "test"))
            );

            TranslatedRequest translated = translator.translateRequest(request);

            @SuppressWarnings("unchecked")
            List<String> stopSequences = (List<String>) translated.body().get("stop_sequences");
            assertEquals(List.of("END"), stopSequences);
        }

        @Test
        @DisplayName("stop 数组逐项校验，非字符串元素报错")
        void shouldRejectNonStringStopElements() {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", "test-model");
            request.put("stop", List.of("a", 123, "b"));
            request.put("messages", List.of(Map.of("role", "user", "content", "test")));

            RequestTranslationException ex = assertThrows(RequestTranslationException.class,
                    () -> translator.translateRequest(request));
            assertTrue(ex.getMessage().contains("stop[1]"),
                    "错误消息应指出具体的元素位置");
        }

        @Test
        @DisplayName("temperature / top_p / top_k 原样搬运，不缩放")
        void shouldCopySamplingParametersWithoutScaling() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "temperature", 0.7,
                    "top_p", 0.9,
                    "top_k", 40,
                    "messages", List.of(Map.of("role", "user", "content", "test"))
            );

            TranslatedRequest translated = translator.translateRequest(request);

            assertEquals(0.7, translated.body().get("temperature"));
            assertEquals(0.9, translated.body().get("top_p"));
            assertEquals(40, translated.body().get("top_k"));
        }

        @Test
        @DisplayName("无对应物字段全部丢弃")
        void shouldDropFieldsWithoutCounterpart() {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", "test-model");
            request.put("frequency_penalty", 0.5);
            request.put("presence_penalty", 0.3);
            request.put("n", 2);
            request.put("logprobs", true);
            request.put("seed", 42);
            request.put("user", "test-user");
            request.put("messages", List.of(Map.of("role", "user", "content", "test")));

            TranslatedRequest translated = translator.translateRequest(request);

            assertFalse(translated.body().containsKey("frequency_penalty"));
            assertFalse(translated.body().containsKey("presence_penalty"));
            assertFalse(translated.body().containsKey("n"));
            assertFalse(translated.body().containsKey("logprobs"));
            assertFalse(translated.body().containsKey("seed"));
            assertFalse(translated.body().containsKey("user"));
        }
    }

    @Nested
    @DisplayName("消息角色映射")
    class RoleMapping {

        @Test
        @DisplayName("developer 角色改写成 system")
        void shouldMapDeveloperToSystem() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "messages", List.of(
                            Map.of("role", "developer", "content", "You are a helpful assistant")
                    )
            );

            TranslatedRequest translated = translator.translateRequest(request);

            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) translated.body().get("messages");
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) messages.get(0);
            assertEquals("system", message.get("role"),
                    "developer 应被改写成 system");
        }

        @Test
        @DisplayName("未知 role 报错")
        void shouldRejectUnknownRole() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "messages", List.of(
                            Map.of("role", "custom_role", "content", "test")
                    )
            );

            RequestTranslationException ex = assertThrows(RequestTranslationException.class,
                    () -> translator.translateRequest(request));
            assertTrue(ex.getMessage().contains("custom_role"));
            assertTrue(ex.getMessage().contains("未知角色"));
        }

        @Test
        @DisplayName("空 content 的消息整条丢弃")
        void shouldDropMessagesWithEmptyContent() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "messages", List.of(
                            Map.of("role", "user", "content", ""),
                            Map.of("role", "user", "content", "real content")
                    )
            );

            TranslatedRequest translated = translator.translateRequest(request);

            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) translated.body().get("messages");
            assertEquals(1, messages.size(), "空内容的消息应被丢弃");
        }
    }

    @Nested
    @DisplayName("内容块白名单")
    class ContentBlockWhitelist {

        @Test
        @DisplayName("text 块原样搬运")
        void shouldCopyTextBlocks() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "messages", List.of(
                            Map.of("role", "user", "content", List.of(
                                    Map.of("type", "text", "text", "Hello")
                            ))
                    )
            );

            TranslatedRequest translated = translator.translateRequest(request);

            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) translated.body().get("messages");
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) messages.get(0);
            @SuppressWarnings("unchecked")
            List<Object> content = (List<Object>) message.get("content");
            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) content.get(0);
            assertEquals("text", block.get("type"));
            assertEquals("Hello", block.get("text"));
        }

        @Test
        @DisplayName("data URL 就地解析成 base64 源")
        void shouldParseDataUrl() {
            String dataUrl = "data:image/png;base64,iVBORw0KGgoAAAANS";
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "messages", List.of(
                            Map.of("role", "user", "content", List.of(
                                    Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
                            ))
                    )
            );

            TranslatedRequest translated = translator.translateRequest(request);

            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) translated.body().get("messages");
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) messages.get(0);
            @SuppressWarnings("unchecked")
            List<Object> content = (List<Object>) message.get("content");
            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) content.get(0);
            assertEquals("image", block.get("type"));
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) block.get("source");
            assertEquals("base64", source.get("type"));
            assertEquals("image/png", source.get("media_type"));
            assertEquals("iVBORw0KGgoAAAANS", source.get("data"));
        }

        @Test
        @DisplayName("http URL 用 Anthropic 原生 URL 形态")
        void shouldUseNativeUrlForHttpImages() {
            String httpUrl = "https://example.com/image.png";
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "messages", List.of(
                            Map.of("role", "user", "content", List.of(
                                    Map.of("type", "image_url", "image_url", Map.of("url", httpUrl))
                            ))
                    )
            );

            TranslatedRequest translated = translator.translateRequest(request);

            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) translated.body().get("messages");
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) messages.get(0);
            @SuppressWarnings("unchecked")
            List<Object> content = (List<Object>) message.get("content");
            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) content.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) block.get("source");
            assertEquals("url", source.get("type"),
                    "http URL 应使用 Anthropic 原生 URL 形态，不下载");
            assertEquals(httpUrl, source.get("url"));
        }

        @Test
        @DisplayName("未知块类型丢弃")
        void shouldDropUnknownBlockTypes() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "messages", List.of(
                            Map.of("role", "user", "content", List.of(
                                    Map.of("type", "custom_block", "data", "something"),
                                    Map.of("type", "text", "text", "real content")
                            ))
                    )
            );

            TranslatedRequest translated = translator.translateRequest(request);

            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) translated.body().get("messages");
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) messages.get(0);
            @SuppressWarnings("unchecked")
            List<Object> content = (List<Object>) message.get("content");
            assertEquals(1, content.size(), "未知块类型应被丢弃");
            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) content.get(0);
            assertEquals("text", block.get("type"));
        }
    }

    @Nested
    @DisplayName("工具映射")
    class ToolMapping {

        @Test
        @DisplayName("工具定义：function.parameters → input_schema")
        void shouldMapToolDefinitions() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "tools", List.of(
                            Map.of("type", "function",
                                    "function", Map.of(
                                            "name", "get_weather",
                                            "description", "Get weather info",
                                            "parameters", Map.of("type", "object", "properties", Map.of())
                                    ))
                    ),
                    "messages", List.of(Map.of("role", "user", "content", "test"))
            );

            TranslatedRequest translated = translator.translateRequest(request);

            @SuppressWarnings("unchecked")
            List<Object> tools = (List<Object>) translated.body().get("tools");
            @SuppressWarnings("unchecked")
            Map<String, Object> tool = (Map<String, Object>) tools.get(0);
            assertEquals("get_weather", tool.get("name"));
            assertEquals("Get weather info", tool.get("description"));
            assertNotNull(tool.get("input_schema"));
        }

        @Test
        @DisplayName("空 parameters 补成空 object schema")
        void shouldNormalizeEmptyParameters() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "tools", List.of(
                            Map.of("type", "function",
                                    "function", Map.of("name", "no_param_tool"))
                    ),
                    "messages", List.of(Map.of("role", "user", "content", "test"))
            );

            TranslatedRequest translated = translator.translateRequest(request);

            @SuppressWarnings("unchecked")
            List<Object> tools = (List<Object>) translated.body().get("tools");
            @SuppressWarnings("unchecked")
            Map<String, Object> tool = (Map<String, Object>) tools.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) tool.get("input_schema");
            assertEquals("object", schema.get("type"));
            assertNotNull(schema.get("properties"));
        }

        @Test
        @DisplayName("tool_choice: auto/required/none")
        void shouldMapToolChoice() {
            Map<String, Object> autoRequest = Map.of(
                    "model", "test-model",
                    "tools", List.of(Map.of("type", "function", "function", Map.of("name", "tool_a"))),
                    "tool_choice", "auto",
                    "messages", List.of(Map.of("role", "user", "content", "test"))
            );

            TranslatedRequest translated = translator.translateRequest(autoRequest);
            @SuppressWarnings("unchecked")
            Map<String, Object> toolChoice = (Map<String, Object>) translated.body().get("tool_choice");
            assertEquals("auto", toolChoice.get("type"));
        }

        @Test
        @DisplayName("tool_choice 指向未声明的工具时丢弃")
        void shouldDropToolChoicePointingToUndeclaredTool() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "tools", List.of(Map.of("type", "function", "function", Map.of("name", "tool_a"))),
                    "tool_choice", Map.of("type", "function", "function", Map.of("name", "undeclared_tool")),
                    "messages", List.of(Map.of("role", "user", "content", "test"))
            );

            TranslatedRequest translated = translator.translateRequest(request);

            assertFalse(translated.body().containsKey("tool_choice"),
                    "指向未声明工具的 tool_choice 应被丢弃");
        }
    }

    @Nested
    @DisplayName("TranslationContext")
    class TranslationContextTests {

        @Test
        @DisplayName("stream=true 记录在 context")
        void shouldRecordStreamInContext() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "stream", true,
                    "messages", List.of(Map.of("role", "user", "content", "test"))
            );

            TranslatedRequest translated = translator.translateRequest(request);

            assertTrue(translated.context().wasStream());
        }

        @Test
        @DisplayName("stream=false 或缺失时 context.wasStream 为 false")
        void shouldRecordNonStreamInContext() {
            Map<String, Object> request = Map.of(
                    "model", "test-model",
                    "messages", List.of(Map.of("role", "user", "content", "test"))
            );

            TranslatedRequest translated = translator.translateRequest(request);

            assertFalse(translated.context().wasStream());
        }
    }
}

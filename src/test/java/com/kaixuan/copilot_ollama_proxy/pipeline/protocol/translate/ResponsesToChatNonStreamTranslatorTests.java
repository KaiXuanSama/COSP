package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ResponseTranslationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R2C 非流式翻译的字段映射全表。
 *
 * <p>fixture 贴三类真实形态：{@code samples/} 抓包（stepfun 16 位 hex id）、
 * 2026-10-04 三家联测（deepseek 官方 UUID id / mimo）。
 * 决策依据见 R2C-PLAN §0 与 R2C-RESEARCH §1/§9。
 */
class ResponsesToChatNonStreamTranslatorTests {

    private ResponsesToChatNonStreamTranslator translator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        translator = new ResponsesToChatNonStreamTranslator(objectMapper);
    }

    /** 便捷构造：文本块。 */
    private static Map<String, Object> outputText(String text) {
        return Map.of("type", "output_text", "text", text);
    }

    private Map<String, Object> translate(String responsesJson) throws Exception {
        String chat = translator.translate(responsesJson);
        return objectMapper.readValue(chat, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> message(Map<String, Object> chatResponse) {
        return (Map<String, Object>) ((List<Object>) chatResponse.get("choices")).get(0) instanceof Map
                ? (Map<String, Object>) ((Map<String, Object>) ((List<Object>) chatResponse.get("choices")).get(0)).get("message")
                : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstChoice(Map<String, Object> chatResponse) {
        return (Map<String, Object>) ((List<Object>) chatResponse.get("choices")).get(0);
    }

    // ==================== message → content ====================

    @Nested
    @DisplayName("message item → content")
    class MessageContent {

        @Test
        void singleMessageBecomesContent() throws Exception {
            Map<String, Object> chat = translate("""
                    {"id":"resp_1","model":"m","status":"completed","created_at":1791091847,
                     "output":[{"type":"message","role":"assistant","content":[
                        {"type":"output_text","text":"你好"}]}]}
                    """);

            assertThat(message(chat).get("content")).isEqualTo("你好");
        }

        @Test
        void multiplePartsAreConcatenatedWithBlankLine() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[{"type":"message","content":[
                        {"type":"output_text","text":"第一段"},
                        {"type":"output_text","text":"第二段"}]}]}
                    """);

            assertThat(message(chat).get("content")).isEqualTo("第一段\n\n第二段");
        }

        @Test
        void multipleMessageItemsAreSeparatedByBlankLine() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[
                        {"type":"message","content":[{"type":"output_text","text":"A"}]},
                        {"type":"reasoning","content":[{"type":"reasoning_text","text":"想"}]},
                        {"type":"message","content":[{"type":"output_text","text":"B"}]}]}
                    """);

            assertThat(message(chat).get("content")).isEqualTo("A\n\nB");
        }

        @Test
        @DisplayName("分隔不叠加：两侧已带换行时只补足到 2（new-api appendSeparatedText 语义）")
        void separatorNeverStacks() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[
                        {"type":"message","content":[{"type":"output_text","text":"A\\n"}]},
                        {"type":"message","content":[{"type":"output_text","text":"\\nB"}]}]}
                    """);

            assertThat(message(chat).get("content")).isEqualTo("A\n\nB");
        }

        @Test
        @DisplayName("空文本给 \"\" 而非 null（CPA 钉过的坑：null 会覆盖/让客户端误判形态）")
        void emptyContentBecomesEmptyString() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[{"type":"message","content":[
                        {"type":"output_text","text":""}]}]}
                    """);

            assertThat(message(chat).get("content")).isEqualTo("");
        }

        @Test
        @DisplayName("input_text part 被忽略（请求侧形态，不是给下游的内容）")
        void inputTextPartsAreIgnored() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[{"type":"message","content":[
                        {"type":"input_text","text":"echo"},
                        {"type":"output_text","text":"real"}]}]}
                    """);

            assertThat(message(chat).get("content")).isEqualTo("real");
        }
    }

    // ==================== reasoning ====================

    @Nested
    @DisplayName("reasoning item → reasoning_content（content 优先、summary 兜底）")
    class ReasoningContent {

        @Test
        void contentTextWins() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[
                        {"type":"reasoning",
                         "summary":[{"type":"summary_text","text":"概括"}],
                         "content":[{"type":"reasoning_text","text":"详细思考"}]},
                        {"type":"message","content":[{"type":"output_text","text":"答"}]}]}
                    """);

            assertThat(message(chat).get("reasoning_content")).isEqualTo("详细思考");
        }

        @Test
        void summaryIsFallbackWhenContentEmpty() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[
                        {"type":"reasoning",
                         "summary":[{"type":"summary_text","text":"只有概括"}],
                         "content":[]},
                        {"type":"message","content":[{"type":"output_text","text":"答"}]}]}
                    """);

            assertThat(message(chat).get("reasoning_content")).isEqualTo("只有概括");
        }

        @Test
        void noReasoningOmitsTheField() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[{"type":"message","content":[
                        {"type":"output_text","text":"答"}]}]}
                    """);

            assertThat(message(chat)).doesNotContainKey("reasoning_content");
        }

        @Test
        void multipleReasoningItemsAccumulateInOrder() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[
                        {"type":"reasoning","content":[{"type":"reasoning_text","text":"第一想"}]},
                        {"type":"reasoning","content":[{"type":"reasoning_text","text":"第二想"}]}]}
                    """);

            assertThat(message(chat).get("reasoning_content")).isEqualTo("第一想\n\n第二想");
        }
    }

    // ==================== function_call → tool_calls ====================

    @Nested
    @DisplayName("function_call / custom_tool_call → tool_calls")
    class ToolCalls {

        @Test
        void functionCallBecomesToolCall() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[
                        {"type":"function_call","call_id":"call_1","name":"get_time",
                         "arguments":"{\\"tz\\":\\"CST\\"}"}]}
                    """);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls =
                    (List<Map<String, Object>>) message(chat).get("tool_calls");
            assertThat(toolCalls).hasSize(1);
            Map<String, Object> call = toolCalls.get(0);
            assertThat(call.get("id")).isEqualTo("call_1");
            assertThat(call.get("type")).isEqualTo("function");
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) call.get("function");
            assertThat(function.get("name")).isEqualTo("get_time");
            assertThat(function.get("arguments")).isEqualTo("{\"tz\":\"CST\"}");
        }

        @Test
        @DisplayName("id 优先 call_id，空则回落 item id（三家共识）")
        void callIdWinsWithIdFallback() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[
                        {"type":"function_call","id":"94353fb5f5fa5ea7","call_id":"","name":"t","arguments":"{}"},
                        {"type":"function_call","id":"item2","name":"t2","arguments":"{}"}]}
                    """);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls =
                    (List<Map<String, Object>>) message(chat).get("tool_calls");
            assertThat(toolCalls.get(0).get("id")).isEqualTo("94353fb5f5fa5ea7");
            assertThat(toolCalls.get(1).get("id")).isEqualTo("item2");
        }

        @Test
        void missingArgumentsBecomesEmptyObject() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[
                        {"type":"function_call","call_id":"c","name":"noargs"}]}
                    """);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls =
                    (List<Map<String, Object>>) message(chat).get("tool_calls");
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) toolCalls.get(0).get("function");
            assertThat(function.get("arguments")).isEqualTo("{}");
        }

        @Test
        @DisplayName("custom_tool_call 按 function 翻译，arguments 取 input（R2C-PLAN §6-2）")
        void customToolCallUsesInputAsArguments() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[
                        {"type":"custom_tool_call","call_id":"c2","name":"apply_patch",
                         "input":"*** Begin Patch\\n+line\\n*** End Patch"}]}
                    """);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls =
                    (List<Map<String, Object>>) message(chat).get("tool_calls");
            assertThat(toolCalls.get(0).get("type")).isEqualTo("function");
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) toolCalls.get(0).get("function");
            assertThat(function.get("arguments")).isEqualTo("*** Begin Patch\n+line\n*** End Patch");
        }

        @Test
        void multipleCallsKeepOrderWithDenseIndex() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[
                        {"type":"reasoning","content":[{"type":"reasoning_text","text":"x"}]},
                        {"type":"message","content":[{"type":"output_text","text":"y"}]},
                        {"type":"function_call","call_id":"c1","name":"a","arguments":"{}"},
                        {"type":"function_call","call_id":"c2","name":"b","arguments":"{}"}]}
                    """);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls =
                    (List<Map<String, Object>>) message(chat).get("tool_calls");
            assertThat(toolCalls.get(0).get("index")).isEqualTo(0);
            assertThat(toolCalls.get(1).get("index")).isEqualTo(1);
        }
    }

    // ==================== finish_reason ====================

    @Nested
    @DisplayName("status / incomplete_details → finish_reason")
    class FinishReason {

        @Test
        void completedWithoutToolsIsStop() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[{"type":"message","content":[
                        {"type":"output_text","text":"答"}]}]}
                    """);

            assertThat(firstChoice(chat).get("finish_reason")).isEqualTo("stop");
        }

        @Test
        void toolCallsOverrideEverything() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                     "output":[{"type":"function_call","call_id":"c","name":"t","arguments":"{}"}]}
                    """);

            assertThat(firstChoice(chat).get("finish_reason"))
                    .as("含完整 tool_calls 必须报 tool_calls，否则下游放弃执行工具（M2C 同判）")
                    .isEqualTo("tool_calls");
        }

        @Test
        void incompleteMaxTokensMapsToLength() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                     "output":[{"type":"message","content":[{"type":"output_text","text":"半"}]}]}
                    """);

            assertThat(firstChoice(chat).get("finish_reason")).isEqualTo("length");
        }

        @Test
        void incompleteContentFilterMapsToContentFilter() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"incomplete","incomplete_details":{"reason":"content_filter"},
                     "output":[]}
                    """);

            assertThat(firstChoice(chat).get("finish_reason")).isEqualTo("content_filter");
        }

        @Test
        void unknownIncompleteReasonAndMissingStatusBothFallToStop() throws Exception {
            Map<String, Object> unknown = translate("""
                    {"status":"incomplete","incomplete_details":{"reason":"whatever"},"output":[]}
                    """);
            Map<String, Object> noStatus = translate("""
                    {"output":[{"type":"message","content":[{"type":"output_text","text":"x"}]}]}
                    """);

            assertThat(firstChoice(unknown).get("finish_reason")).isEqualTo("stop");
            assertThat(firstChoice(noStatus).get("finish_reason")).isEqualTo("stop");
        }
    }

    // ==================== usage ====================

    @Nested
    @DisplayName("usage 换算（prompt=input，不加缓存；reasoning_tokens 归位 details）")
    class Usage {

        @Test
        void mapsDirectlyWithoutCacheAddition() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[],"usage":{
                        "input_tokens":229,"output_tokens":115,"total_tokens":344,
                        "input_tokens_details":{"cached_tokens":0},
                        "output_tokens_details":{"reasoning_tokens":62}}}
                    """);

            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) chat.get("usage");
            assertThat(usage.get("prompt_tokens")).isEqualTo(229);
            assertThat(usage.get("completion_tokens")).isEqualTo(115);
            assertThat(usage.get("total_tokens")).isEqualTo(344);
            assertThat(usage)
                    .as("cached=0 与 reasoning>0：details 只给非零的那份（全零 details 是噪声）")
                    .doesNotContainKey("prompt_tokens_details");
            @SuppressWarnings("unchecked")
            Map<String, Object> completionDetails =
                    (Map<String, Object>) usage.get("completion_tokens_details");
            assertThat(completionDetails.get("reasoning_tokens")).isEqualTo(62);
        }

        @Test
        void cachedTokensGoToPromptDetails() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[],"usage":{
                        "input_tokens":100,"output_tokens":10,"total_tokens":110,
                        "input_tokens_details":{"cached_tokens":80}}}
                    """);

            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) chat.get("usage");
            @SuppressWarnings("unchecked")
            Map<String, Object> promptDetails =
                    (Map<String, Object>) usage.get("prompt_tokens_details");
            assertThat(promptDetails.get("cached_tokens")).isEqualTo(80);
        }

        @Test
        void usageOmittedEntirelyWhenUpstreamGivesNone() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[]}
                    """);

            assertThat(chat).doesNotContainKey("usage");
        }
    }

    // ==================== 头部字段 ====================

    @Nested
    @DisplayName("id / model / created（透传口径）")
    class HeaderFields {

        @Test
        void idAndModelPassThroughVerbatim() throws Exception {
            Map<String, Object> chat = translate("""
                    {"id":"resp_9a47f5d0","model":"mimo-v2.6-flash","status":"completed",
                     "created_at":1791091847,"output":[]}
                    """);

            assertThat(chat.get("id")).isEqualTo("resp_9a47f5d0");
            assertThat(chat.get("model"))
                    .as("上游裸名透传（R2C-PLAN §6-1：三家下游 agent 实测无问题，与 M2C/直连同口径）")
                    .isEqualTo("mimo-v2.6-flash");
            assertThat(chat.get("created")).isEqualTo(1791091847);
            assertThat(chat.get("object")).isEqualTo("chat.completion");
        }

        @Test
        void missingIdAndCreatedAtGetLocalFallbacks() throws Exception {
            Map<String, Object> chat = translate("""
                    {"model":"m","status":"completed","output":[]}
                    """);
            long before = System.currentTimeMillis() / 1000 - 5;

            assertThat((long) ((Integer) chat.get("created")).longValue()).isGreaterThan(before);
        }
    }

    // ==================== 丢弃清单 ====================

    @Nested
    @DisplayName("丢弃与异常")
    class DropsAndErrors {

        @Test
        void unknownItemTypesAreDropped() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[
                        {"type":"web_search_call","id":"ws_1"},
                        {"type":"image_generation_call","result":"base64"},
                        {"type":"message","content":[{"type":"output_text","text":"答"}]}]}
                    """);

            assertThat(message(chat).get("content")).isEqualTo("答");
            assertThat(message(chat)).doesNotContainKey("tool_calls");
        }

        @Test
        void encryptedContentAndPhaseAreNotRead() throws Exception {
            Map<String, Object> chat = translate("""
                    {"status":"completed","output":[
                        {"type":"reasoning","encrypted_content":"gAAAA","phase":"commentary",
                         "content":[{"type":"reasoning_text","text":"想"}]},
                        {"type":"message","content":[{"type":"output_text","text":"答"}]}]}
                    """);

            assertThat(message(chat).get("reasoning_content")).isEqualTo("想");
        }

        @Test
        void unparsableBodyThrows() {
            assertThatThrownBy(() -> translator.translate("not json"))
                    .isInstanceOf(ResponseTranslationException.class);
        }

        @Test
        void blankBodyThrows() {
            assertThatThrownBy(() -> translator.translate("  "))
                    .isInstanceOf(ResponseTranslationException.class);
        }
    }
}

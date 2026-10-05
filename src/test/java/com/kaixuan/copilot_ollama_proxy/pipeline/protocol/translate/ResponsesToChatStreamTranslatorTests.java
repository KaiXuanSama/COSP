package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2C 流式翻译的事件→动作全表验证。
 *
 * <p>规格依据 R2C-PLAN §2.3（事件表）与 §4 阶段二的重点用例组。
 * fixture 贴三类真实形态：{@code samples/} 抓包（形态 A/B/C）、
 * deepseek 官方（单 delta 全量参数）、MiniMax 乱序（done 先于 delta）。
 */
class ResponsesToChatStreamTranslatorTests {

    private ResponsesToChatStreamTranslator translator;
    private R2CStreamState state;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        translator = new ResponsesToChatStreamTranslator(objectMapper);
        state = new R2CStreamState("chatcmpl-placeholder", "upstream-model", false);
    }

    /** 逐事件翻译并收尾，返回完整 chunk 列表（含 finalize 帧）。 */
    private List<String> run(String... events) {
        List<String> all = new ArrayList<>();
        for (String event : events) {
            all.addAll(translator.translateEvent(event, state));
        }
        all.addAll(translator.finalizeStream(state));
        return all;
    }

    private Map<String, Object> parse(String chunk) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(chunk, Map.class);
        return parsed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> delta(Map<String, Object> chunk) throws Exception {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
        return choices.isEmpty() ? Map.of() : (Map<String, Object>) choices.get(0).get("delta");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstChoice(Map<String, Object> chunk) throws Exception {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
        return choices.isEmpty() ? Map.of() : choices.get(0);
    }

    private static String createdEvent() {
        return """
                {"type":"response.created","response":{"id":"resp_1","model":"m-up","created_at":1791091847}}
                """;
    }

    // ==================== 基本骨架 ====================

    @Nested
    @DisplayName("基本骨架：role 帧 / 噪声事件 / 终态吸收")
    class Skeleton {

        @Test
        void createdEmitsTheOnlyRoleFrame() throws Exception {
            List<String> frames = run(createdEvent());

            Map<String, Object> role = delta(parse(frames.get(0)));
            assertThat(role.get("role")).isEqualTo("assistant");
            assertThat(frames.get(frames.size() - 1)).isEqualTo("[DONE]");
        }

        @Test
        void inProgressAndPartEventsEmitNothing() throws Exception {
            List<String> frames = run(
                    createdEvent(),
                    """
                    {"type":"response.in_progress","response":{}}
                    """,
                    """
                    {"type":"response.content_part.added","output_index":1,"content_index":0}
                    """,
                    """
                    {"type":"response.output_text.done","output_index":1,"text":"x"}
                    """);

            // 只有 role 帧 + 收尾三帧（finish/usage 略、DONE）
            assertThat(frames.get(0)).contains("\"role\"");
            assertThat(frames).last().isEqualTo("[DONE]");
        }

        @Test
        void terminalIsAbsorbedIntoFinishChunkNotPassedThrough() throws Exception {
            List<String> frames = run(
                    createdEvent(),
                    """
                    {"type":"response.output_text.delta","delta":"答案"}
                    """,
                    """
                    {"type":"response.completed","response":{"id":"resp_1","status":"completed","output":[]}}
                    """);

            // 终态原文不出现在任何 chunk 里；finish chunk 带 stop
            assertThat(String.join("", frames)).doesNotContain("\"response.completed\"");
            boolean hasFinish = frames.stream().anyMatch(f -> {
                try {
                    return "stop".equals(firstChoice(parse(f)).get("finish_reason"));
                } catch (Exception e) {
                    return false;
                }
            });
            assertThat(hasFinish).as("completed 被吸收为 finish_reason=stop").isTrue();
        }

        @Test
        void doneSentinelIsAlwaysLastAndEmittedEvenWithoutTerminal() throws Exception {
            List<String> frames = run(
                    createdEvent(),
                    """
                    {"type":"response.output_text.delta","delta":"半"}
                    """);
            // 上游没发终态就断流：finalize 兜底 finish(length) + DONE

            assertThat(frames).last().isEqualTo("[DONE]");
            boolean hasLength = frames.stream().anyMatch(f -> {
                try {
                    return "length".equals(firstChoice(parse(f)).get("finish_reason"));
                } catch (Exception e) {
                    return false;
                }
            });
            assertThat(hasLength).as("无终态但有内容 → 按截断收尾").isTrue();
        }
    }

    // ==================== 内容增量 ====================

    @Nested
    @DisplayName("文本与思考增量")
    class ContentDeltas {

        @Test
        void textDeltaBecomesContent() throws Exception {
            run(createdEvent());
            List<String> frames = translator.translateEvent(
                    """
                    {"type":"response.output_text.delta","delta":"你好"}
                    """, state);

            assertThat(delta(parse(frames.get(0))).get("content")).isEqualTo("你好");
        }

        @Test
        @DisplayName("无 created 时首个内容事件带上 role（混合兜底 §9-3）")
        void roleFallbackOnFirstContentEvent() throws Exception {
            List<String> frames = translator.translateEvent(
                    """
                    {"type":"response.output_text.delta","delta":"你好"}
                    """, state);

            Map<String, Object> d = delta(parse(frames.get(0)));
            assertThat(d.get("role")).isEqualTo("assistant");
            assertThat(d.get("content")).isEqualTo("你好");
        }

        @Test
        void reasoningDeltaBecomesReasoningContent() throws Exception {
            run(createdEvent());
            List<String> frames = translator.translateEvent(
                    """
                    {"type":"response.reasoning_text.delta","delta":"思考中"}
                    """, state);

            assertThat(delta(parse(frames.get(0))).get("reasoning_content")).isEqualTo("思考中");
        }

        @Test
        @DisplayName("reasoning 分隔「标记+差额」：done 后 delta 自带 \\n\\n 开头则不补（§9-5）")
        void reasoningBreakNeverStacks() throws Exception {
            run(createdEvent());
            translator.translateEvent(
                    """
                    {"type":"response.reasoning_text.delta","delta":"第一段"}
                    """, state);
            translator.translateEvent(
                    """
                    {"type":"response.reasoning_text.done"}
                    """, state);
            List<String> frames = translator.translateEvent(
                    """
                    {"type":"response.reasoning_text.delta","delta":"\\n\\n第二段"}
                    """, state);

            assertThat(delta(parse(frames.get(0))).get("reasoning_content"))
                    .as("自带 \\n\\n 开头时不重复补")
                    .isEqualTo("\n\n第二段");
        }

        @Test
        void reasoningBreakAddsTwoNewlinesWhenDeltaHasNone() throws Exception {
            run(createdEvent());
            translator.translateEvent(
                    """
                    {"type":"response.reasoning_text.delta","delta":"第一段"}
                    """, state);
            translator.translateEvent(
                    """
                    {"type":"response.reasoning_text.done"}
                    """, state);
            List<String> frames = translator.translateEvent(
                    """
                    {"type":"response.reasoning_text.delta","delta":"第二段"}
                    """, state);

            assertThat(delta(parse(frames.get(0))).get("reasoning_content"))
                    .isEqualTo("\n\n第二段");
        }
    }

    // ==================== 工具调用 ====================

    @Nested
    @DisplayName("工具调用：本地 index / 前缀补齐 / pending / 乱序")
    class ToolCalls {

        private static final String TOOL_ADDED = """
                {"type":"response.output_item.added","output_index":2,
                 "item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"get_time","arguments":""}}
                """;

        @Test
        @DisplayName("tool index 本地重编号：reasoning 占 output_index 0/1，工具仍从 0 开始")
        void toolIndexIsDenseFromZero() throws Exception {
            run(createdEvent(),
                    """
                    {"type":"response.output_item.added","output_index":0,
                     "item":{"type":"reasoning","id":"rs_1"}}
                    """,
                    """
                    {"type":"response.output_item.added","output_index":1,
                     "item":{"type":"message","id":"msg_1"}}
                    """,
                    TOOL_ADDED);

            List<String> frames = translator.translateEvent(
                    """
                    {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{}"}
                    """, state);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls =
                    (List<Map<String, Object>>) delta(parse(frames.get(0))).get("tool_calls");
            assertThat(toolCalls.get(0).get("index")).isEqualTo(0);
        }

        @Test
        @DisplayName("首个工具帧带 index/id/name，arguments 增量帧不带 name（防覆盖坑）")
        void nameOnlyOnFirstFrame() throws Exception {
            run(createdEvent(), TOOL_ADDED);

            List<String> argsFrames = translator.translateEvent(
                    """
                    {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\\"k\\":1}"}
                    """, state);

            @SuppressWarnings("unchecked")
            Map<String, Object> toolCall = (Map<String, Object>)
                    ((List<Object>) delta(parse(argsFrames.get(0))).get("tool_calls")).get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
            assertThat(function.get("arguments")).isEqualTo("{\"k\":1}");
            assertThat(function)
                    .as("arguments 增量帧只有 arguments 键——name 键不存在即防住了 sub2api 的覆盖坑")
                    .doesNotContainKey("name");
        }

        @Test
        @DisplayName("前缀补齐（正常流）：done 与累积一致时只补空差量（不发帧）")
        void doneWithConsistentArgumentsEmitsNothing() throws Exception {
            run(createdEvent(), TOOL_ADDED);
            translator.translateEvent(
                    """
                    {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\\"k\\":1}"}
                    """, state);
            List<String> frames = translator.translateEvent(
                    """
                    {"type":"response.function_call_arguments.done","item_id":"fc_1","arguments":"{\\"k\\":1}","name":"get_time"}
                    """, state);

            assertThat(frames).as("delta 已发全，done 差量为空").isEmpty();
        }

        @Test
        @DisplayName("前缀补齐（部分流）：delta 只发了一半时 done 补差量")
        void doneSupplementsMissingRemainder() throws Exception {
            run(createdEvent(), TOOL_ADDED);
            translator.translateEvent(
                    """
                    {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\\"k\\":"}
                    """, state);
            List<String> frames = translator.translateEvent(
                    """
                    {"type":"response.function_call_arguments.done","item_id":"fc_1","arguments":"{\\"k\\":1}"}
                    """, state);

            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>)
                    ((Map<String, Object>) ((List<Object>) delta(parse(frames.get(0))).get("tool_calls")).get(0)).get("function");
            assertThat(function.get("arguments")).isEqualTo("1}");
        }

        @Test
        @DisplayName("MiniMax 乱序（done 先到参数空、完整参数在随后的 delta）不丢参数")
        void minimaxStyleOutOfOrderDoneThenDelta() throws Exception {
            run(createdEvent(), TOOL_ADDED);
            // done 先到，参数为空（MiniMax 实测形态）
            translator.translateEvent(
                    """
                    {"type":"response.function_call_arguments.done","item_id":"fc_1","arguments":""}
                    """, state);
            // 随后 delta 携带完整参数
            List<String> frames = translator.translateEvent(
                    """
                    {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\\"path\\":\\"x\\"}"}
                    """, state);

            assertThat(frames).as("晚到的 delta 直接透传（不设 Done 门）").hasSize(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>)
                    ((Map<String, Object>) ((List<Object>) delta(parse(frames.get(0))).get("tool_calls")).get(0)).get("function");
            assertThat(function.get("arguments")).isEqualTo("{\"path\":\"x\"}");
        }

        @Test
        @DisplayName("pending（§9-4）：delta 早于 added 到达，注册时拼入")
        void deltaBeforeAddedIsStashedThenApplied() throws Exception {
            run(createdEvent());
            // delta 先到，无对应工具
            translator.translateEvent(
                    """
                    {"type":"response.function_call_arguments.delta","item_id":"fc_9","delta":"{\\"x\":"}
                    """, state);
            // added 后到：仍是 name 声明帧（arguments 空占位），随后 delta 继续时从头拼接正确
            List<String> frames = translator.translateEvent(
                    """
                    {"type":"response.output_item.added","output_index":2,
                     "item":{"type":"function_call","id":"fc_9","call_id":"call_9","name":"t","arguments":""}}
                    """, state);
            assertThat(frames).as("added 产 name 声明帧").hasSize(1);
            assertThat(frames.get(0)).contains("\"name\":\"t\"").contains("\"call_9\"");

            List<String> more = translator.translateEvent(
                    """
                    {"type":"response.function_call_arguments.delta","item_id":"fc_9","delta":"1}"}
                    """, state);
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>)
                    ((Map<String, Object>) ((List<Object>) delta(parse(more.get(0))).get("tool_calls")).get(0)).get("function");
            assertThat(function.get("arguments")).isEqualTo("1}");
        }

        @Test
        @DisplayName("工具调用的 finish_reason=tool_calls 优先（与 M2C 同源判定）")
        void toolCallOverridesFinishReason() throws Exception {
            List<String> frames = run(
                    createdEvent(),
                    """
                    {"type":"response.incomplete","response":{
                        "status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                        "output":[{"type":"function_call","call_id":"call_1","name":"t","arguments":"{}"}]}}
                    """);

            boolean hasToolCallsFinish = frames.stream().anyMatch(f -> {
                try {
                    return "tool_calls".equals(firstChoice(parse(f)).get("finish_reason"));
                } catch (Exception e) {
                    return false;
                }
            });
            assertThat(hasToolCallsFinish).as("incomplete+工具 → 仍报 tool_calls").isTrue();
        }
    }

    // ==================== 终态补发 ====================

    @Nested
    @DisplayName("终态 output[] 补发（只发终态不发 delta 的上游）")
    class TerminalSupplement {

        @Test
        void messageTextInTerminalIsEmittedWhenNoDeltaWasSeen() throws Exception {
            List<String> frames = run(
                    createdEvent(),
                    """
                    {"type":"response.completed","response":{
                        "id":"resp_1","status":"completed",
                        "output":[{"type":"message","content":[{"type":"output_text","text":"完整答案"}]}]}}
                    """);

            boolean hasContent = frames.stream().anyMatch(f -> {
                try {
                    return "完整答案".equals(delta(parse(f)).get("content"));
                } catch (Exception e) {
                    return false;
                }
            });
            assertThat(hasContent).as("零 delta 上游的内容由终态补发").isTrue();
        }

        @Test
        void functionCallInTerminalIsEmittedAsCompleteToolCall() throws Exception {
            List<String> frames = run(
                    createdEvent(),
                    """
                    {"type":"response.completed","response":{
                        "status":"completed",
                        "output":[{"type":"function_call","call_id":"call_1","name":"get_time","arguments":"{\\"tz\\":\\"CST\\"}"}]}}
                    """);

            boolean hasFullArgs = frames.stream().anyMatch(f -> {
                try {
                    Object toolCalls = delta(parse(f)).get("tool_calls");
                    if (!(toolCalls instanceof List<?> calls) || calls.isEmpty()) {
                        return false;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> function = (Map<String, Object>)
                            ((Map<String, Object>) calls.get(0)).get("function");
                    return "{\"tz\":\"CST\"}".equals(function.get("arguments"));
                } catch (Exception e) {
                    return false;
                }
            });
            assertThat(hasFullArgs).as("终态补发的工具调用一次给全参数").isTrue();
        }

        @Test
        void alreadyStreamedContentIsNotDuplicatedByTerminal() throws Exception {
            List<String> frames = run(
                    createdEvent(),
                    """
                    {"type":"response.output_text.delta","delta":"流式内容"}
                    """,
                    """
                    {"type":"response.completed","response":{
                        "status":"completed",
                        "output":[{"type":"message","content":[{"type":"output_text","text":"流式内容"}]}]}}
                    """);

            long contentCount = frames.stream().filter(f -> {
                try {
                    return "流式内容".equals(delta(parse(f)).get("content"));
                } catch (Exception e) {
                    return false;
                }
            }).count();
            assertThat(contentCount).as("delta 已发过，终态不重复补发").isEqualTo(1);
        }
    }

    // ==================== 失败终态 ====================

    @Nested
    @DisplayName("failed / error / cancelled：不产内容帧")
    class FailureTerminals {

        @Test
        void failedEmitsNoContentFrames() throws Exception {
            List<String> frames = run(
                    createdEvent(),
                    """
                    {"type":"response.failed","response":{"status":"failed","error":{"message":"boom"}}}
                    """);

            // 只有 role 帧 + 收尾（finish=stop + DONE），无任何内容帧
            assertThat(frames).hasSize(3);
            assertThat(frames.get(2)).isEqualTo("[DONE]");
        }

        @Test
        void errorEventEmitsNoContentFrames() throws Exception {
            List<String> frames = run(
                    createdEvent(),
                    """
                    {"type":"error","code":"upstream_error","message":"x"}
                    """);

            assertThat(frames).hasSize(3);
        }

        @Test
        void emptyStreamStillEmitsTerminationFrames() throws Exception {
            List<String> frames = run(createdEvent());

            // role 帧 + finish(stop) + DONE = 3 帧
            assertThat(frames).hasSize(3);
            assertThat(frames.get(1)).contains("\"stop\"");
            assertThat(frames.get(2)).isEqualTo("[DONE]");
        }
    }

    // ==================== usage ====================

    @Nested
    @DisplayName("usage chunk：收尾统一发、finish 在前、仅 includeUsage 时")
    class UsageChunk {

        private String terminalWithUsage() {
            return """
                    {"type":"response.completed","response":{
                        "status":"completed","output":[],
                        "usage":{"input_tokens":10,"output_tokens":20,"total_tokens":30,
                                 "output_tokens_details":{"reasoning_tokens":5}}}}
                    """;
        }

        @Test
        void usageChunkEmittedOnlyWhenAsked() throws Exception {
            List<String> withoutUsage = run(createdEvent(), terminalWithUsage());

            setUp();
            R2CStreamState withUsageState =
                    new R2CStreamState("chatcmpl-x", "m", true);
            List<String> withUsage = new ArrayList<>();
            withUsage.addAll(translator.translateEvent(createdEvent(), withUsageState));
            withUsage.addAll(translator.translateEvent(terminalWithUsage(), withUsageState));
            withUsage.addAll(translator.finalizeStream(withUsageState));

            assertThat(withoutUsage.stream().filter(f -> f.contains("\"usage\"")).count())
                    .as("下游没要就不发").isZero();
            assertThat(withUsage.stream().filter(f -> f.contains("\"usage\"")).count())
                    .as("要了且上游给了 → 恰一个 usage chunk").isEqualTo(1);
        }

        @Test
        void usageChunkHasEmptyChoicesAndFollowsFinish() throws Exception {
            R2CStreamState withUsageState =
                    new R2CStreamState("chatcmpl-x", "m", true);
            List<String> frames = new ArrayList<>();
            frames.addAll(translator.translateEvent(createdEvent(), withUsageState));
            frames.addAll(translator.translateEvent(terminalWithUsage(), withUsageState));
            frames.addAll(translator.finalizeStream(withUsageState));

            int finishIdx = -1;
            int usageIdx = -1;
            for (int i = 0; i < frames.size(); i++) {
                if (frames.get(i).contains("\"finish_reason\":\"stop\"")) {
                    finishIdx = i;
                }
                if (frames.get(i).contains("\"usage\"")) {
                    usageIdx = i;
                }
            }
            assertThat(finishIdx).isGreaterThanOrEqualTo(0);
            assertThat(usageIdx)
                    .as("顺序不变量：finish 在前、usage 在后")
                    .isGreaterThan(finishIdx);
            Map<String, Object> usageChunk = parse(frames.get(usageIdx));
            assertThat((List<?>) usageChunk.get("choices")).isEmpty();
            assertThat(usageChunk.get("usage").toString())
                    .contains("reasoning_tokens=5")
                    .contains("prompt_tokens=10");
        }
    }

    // ==================== 真实形态回放 ====================

    @Nested
    @DisplayName("真实事件序列回放（samples 形态 B/C 的骨架）")
    class RealSequences {

        @Test
        @DisplayName("形态 C（stepfun-midway）：reasoning + 文本 + 工具，34 事件骨架的关键段")
        void formCReasoningTextToolSequence() throws Exception {
            // 依据 samples/stepfun-5-codex-image-midway.json 的事件骨架（抽样关键事件）
            List<String> frames = run(
                    createdEvent(),
                    """
                    {"type":"response.output_item.added","output_index":0,
                     "item":{"type":"reasoning","id":"rs_1","summary":[],"content":[],"status":"in_progress"}}
                    """,
                    """
                    {"type":"response.reasoning_part.added","output_index":0,"content_index":0,
                     "part":{"type":"reasoning_text","text":""}}
                    """,
                    """
                    {"type":"response.reasoning_text.delta","output_index":0,"delta":"需要看图"}
                    """,
                    """
                    {"type":"response.reasoning_text.done","output_index":0,"text":"需要看图"}
                    """,
                    """
                    {"type":"response.output_item.done","output_index":0,
                     "item":{"type":"reasoning","id":"rs_1","status":"completed"}}
                    """,
                    """
                    {"type":"response.output_item.added","output_index":1,
                     "item":{"type":"message","id":"msg_1","status":"in_progress","content":[]}}
                    """,
                    """
                    {"type":"response.output_text.delta","output_index":1,"delta":"我先查看"}
                    """,
                    """
                    {"type":"response.output_item.done","output_index":1,
                     "item":{"type":"message","id":"msg_1","status":"completed"}}
                    """,
                    """
                    {"type":"response.output_item.added","output_index":2,
                     "item":{"type":"function_call","id":"a8deb7f7","call_id":"call_ac5f","name":"view_image","arguments":"","status":"in_progress"}}
                    """,
                    """
                    {"type":"response.function_call_arguments.delta","item_id":"a8deb7f7","output_index":2,"delta":"{"}
                    """,
                    """
                    {"type":"response.function_call_arguments.delta","item_id":"a8deb7f7","output_index":2,"delta":"\\"path\\":\\"x\\"}"}
                    """,
                    """
                    {"type":"response.function_call_arguments.done","item_id":"a8deb7f7","output_index":2,
                     "arguments":"{\\"path\\":\\"x\\"}","name":"view_image"}
                    """,
                    """
                    {"type":"response.output_item.done","output_index":2,
                     "item":{"type":"function_call","id":"a8deb7f7","call_id":"call_ac5f","arguments":"{\\"path\\":\\"x\\"}","status":"completed"}}
                    """,
                    """
                    {"type":"response.completed","response":{"id":"resp_1","status":"completed","output":[],
                        "usage":{"input_tokens":8,"output_tokens":73,"total_tokens":81,
                                 "output_tokens_details":{"reasoning_tokens":62}}}}
                    """);

            // 断言：role、reasoning、text、tool start、args ×2、finish、DONE
            assertThat(frames).last().isEqualTo("[DONE]");
            assertThat(String.join("\n", frames))
                    .contains("\"reasoning_content\":\"需要看图\"")
                    .contains("\"content\":\"我先查看\"")
                    .contains("\"name\":\"view_image\"")
                    .contains("call_ac5f")
                    .contains("\"tool_calls\"");
            boolean hasToolCallsFinish = frames.stream().anyMatch(f -> {
                try {
                    return "tool_calls".equals(firstChoice(parse(f)).get("finish_reason"));
                } catch (Exception e) {
                    return false;
                }
            });
            assertThat(hasToolCallsFinish).isTrue();
        }

        @Test
        @DisplayName("deepseek 形态：单 delta 全量参数（分片粒度无关性）")
        void deepseekSingleDeltaFullArguments() throws Exception {
            List<String> frames = run(
                    createdEvent(),
                    """
                    {"type":"response.output_item.added","output_index":1,
                     "item":{"type":"function_call","id":"b3d0ae33","call_id":"call_00_x","name":"get_time","arguments":""}}
                    """,
                    """
                    {"type":"response.function_call_arguments.delta","item_id":"b3d0ae33","output_index":1,"delta":"{}"}
                    """,
                    """
                    {"type":"response.function_call_arguments.done","item_id":"b3d0ae33","output_index":1,"arguments":"{}"}
                    """,
                    """
                    {"type":"response.completed","response":{"status":"completed","output":[]}}
                    """);

            long argsFrames = frames.stream().filter(f -> f.contains("\"arguments\":\"{}\"")).count();
            assertThat(argsFrames).as("全量 delta 发一次，done 差量为空不再发").isEqualTo(1);
        }
    }

    // ==================== 落库重译 ====================

    @Nested
    @DisplayName("translateChunksForLog：独立状态重放、帧数对齐")
    class ChunksForLog {

        @Test
        void frameCountsAlignWithEvents() throws Exception {
            ResponsesToChatResponseTranslator facade =
                    new ResponsesToChatResponseTranslator(objectMapper);
            List<String> events = List.of(
                    createdEvent().trim(),
                    """
                    {"type":"response.in_progress","response":{}}
                    """.trim(),
                    """
                    {"type":"response.output_text.delta","delta":"hi"}
                    """.trim(),
                    """
                    {"type":"response.completed","response":{"status":"completed","output":[]}}
                    """.trim());

            TranslatedChunkLog log = facade.translateChunksForLog(events, "m", false);

            assertThat(log.frameCounts())
                    .as("逐事件产帧数：role=1、in_progress=0、delta=1、终态=1")
                    .containsExactly(1, 0, 1, 1);
            assertThat(log.translated().size())
                    .as("总帧数 = 事件帧之和 + 收尾（终态已发 finish，finalize 只补 DONE）")
                    .isEqualTo(3 + 1);
            assertThat(log.translated()).last().isEqualTo("[DONE]");
        }
    }
}

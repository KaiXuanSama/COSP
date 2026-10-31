package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolResultMediaLifter} 的边界与不变量（ADR-0019）。
 *
 * <p>端到端形态由 {@code ChatToMessagesRequestTranslatorTests} 覆盖；这里钉住
 * 「不该改的不改」与「改的时候不丢、不乱」。
 */
class ToolResultMediaLifterTests {

    private final ToolResultMediaLifter lifter = new ToolResultMediaLifter(new ObjectMapper());

    @Test
    @DisplayName("没有含媒体的 tool_result 时返回同一对象")
    void returnsSameObjectsWhenNothingToLift() {
        Map<String, Object> plainResult = toolResult("call_1", "text output");
        Map<String, Object> textBlocksResult = toolResult("call_2", List.of(text("a"), text("b")));
        Map<String, Object> user = message("user", List.of(plainResult, textBlocksResult, text("next")));
        Map<String, Object> userString = message("user", "plain string");
        Map<String, Object> assistant = message("assistant", List.of(image("https://example.com/x.png")));
        List<Object> source = List.of(user, userString, assistant);

        List<Object> output = lifter.lift(source);

        assertThat(output).hasSize(3);
        assertThat(output.get(0)).isSameAs(user);
        assertThat(output.get(1)).isSameAs(userString);
        assertThat(output.get(2)).as("assistant 消息不处理").isSameAs(assistant);
    }

    @Test
    @DisplayName("移动后 tool_result 只含字符串，媒体与其它键不丢")
    void movesMediaAndKeepsOtherKeys() {
        Map<String, Object> result = toolResult("call_1", List.of(text("one"), image("https://e.com/1.png"),
                text("two"), image("https://e.com/2.png")));
        result.put("is_error", true);
        result.put("cache_control", Map.of("type", "ephemeral"));

        List<Object> content = contentOf(lifter.lift(List.of(message("user", List.of(result)))).getFirst());

        assertThat(content).hasSize(4);
        Map<?, ?> lifted = (Map<?, ?>) content.get(0);
        assertThat(lifted.get("content")).isEqualTo("one\ntwo\n" + ToolResultMediaLifter.MEDIA_HINT);
        assertThat(lifted.get("is_error")).isEqualTo(true);
        assertThat(lifted.get("cache_control")).isEqualTo(Map.of("type", "ephemeral"));
        assertThat(lifted.get("tool_use_id")).isEqualTo("call_1");
        assertThat(((Map<?, ?>) content.get(1)).get("text")).isEqualTo("[media from tool_use_id=\"call_1\"]");
        assertThat(content.subList(2, 4)).containsExactly(image("https://e.com/1.png"), image("https://e.com/2.png"));
    }

    @Test
    @DisplayName("来源标记中的 id 经 JSON 转义")
    void escapesToolUseIdInMarker() {
        Map<String, Object> result = toolResult("id \"x\"\n]", List.of(image("https://e.com/a.png")));

        List<Object> content = contentOf(lifter.lift(List.of(message("user", List.of(result)))).getFirst());

        assertThat(((Map<?, ?>) content.get(1)).get("text"))
                .isEqualTo("[media from tool_use_id=\"id \\\"x\\\"\\n]\"]");
    }

    @Test
    @DisplayName("tool_result 在前，媒体居中，原有其它块排在最后且保序")
    void ordersResultsMediaThenOthers() {
        Map<String, Object> withMedia = toolResult("call_1", List.of(image("https://e.com/a.png")));
        Map<String, Object> plain = toolResult("call_2", "plain");
        Map<String, Object> userText = text("user text");
        Map<String, Object> userImage = image("https://e.com/user.png");

        List<Object> content = contentOf(lifter.lift(List.of(
                message("user", List.of(withMedia, userText, plain, userImage)))).getFirst());

        assertThat(content).hasSize(6);
        assertThat(((Map<?, ?>) content.get(0)).get("tool_use_id")).isEqualTo("call_1");
        assertThat(((Map<?, ?>) content.get(1)).get("tool_use_id")).isEqualTo("call_2");
        assertThat(content.get(1)).as("纯文本结果原样保留").isSameAs(plain);
        assertThat(((Map<?, ?>) content.get(2)).get("text")).isEqualTo("[media from tool_use_id=\"call_1\"]");
        assertThat(content.get(3)).isEqualTo(image("https://e.com/a.png"));
        assertThat(content.subList(4, 6)).containsExactly(userText, userImage);
    }

    @Test
    @DisplayName("不修改输入的消息与块")
    void doesNotMutateInput() {
        Map<String, Object> result = toolResult("call_1", new ArrayList<>(List.of(text("t"), image("https://e.com/a.png"))));
        Map<String, Object> user = message("user", new ArrayList<>(List.of(result)));
        Object resultContentBefore = new ArrayList<>((List<?>) result.get("content"));

        lifter.lift(List.of(user));

        assertThat(result.get("content")).isEqualTo(resultContentBefore);
        assertThat(contentOf(user)).containsExactly(result);
    }

    private static List<Object> contentOf(Object message) {
        @SuppressWarnings("unchecked")
        List<Object> content = (List<Object>) ((Map<?, ?>) message).get("content");
        return content;
    }

    private static Map<String, Object> message(String role, Object content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static Map<String, Object> toolResult(String id, Object content) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", id);
        block.put("content", content);
        return block;
    }

    private static Map<String, Object> text(String text) {
        return Map.of("type", "text", "text", text);
    }

    private static Map<String, Object> image(String url) {
        return Map.of("type", "image", "source", Map.of("type", "url", "url", url));
    }
}

package com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Responses 侧 system role 改写（{@link SystemPromptNormalizer#rewriteSystemToDeveloper}）
 * 的纯逻辑验证。
 *
 * <p>与 {@code MessagesSystemPromptStage} 的抬升不同，这里是<strong>就地改写</strong>：
 * item 数量、顺序、位置都不变，只有 role 值变化 —— 因此断言的重点是
 * 「改了该改的、没碰不该碰的」。决策依据见 C2R 计划 §0 决策 #1。
 */
class ResponsesSystemPromptStageTests {

    private final ResponsesSystemPromptStage stage = new ResponsesSystemPromptStage();

    private static Map<String, Object> messageItem(String role) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "message");
        item.put("role", role);
        item.put("content", List.of(Map.of("type", "input_text", "text", "x")));
        return item;
    }

    private static Map<String, Object> functionCallItem() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "function_call");
        item.put("call_id", "call_1");
        item.put("name", "exec");
        item.put("arguments", "{}");
        return item;
    }

    @Test
    @DisplayName("system message item 的 role 改写为 developer")
    void rewritesSystemRoleToDeveloper() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", new ArrayList<>(List.of(
                messageItem("system"),
                messageItem("user"))));

        stage.apply(body);

        List<?> input = (List<?>) body.get("input");
        assertThat(((Map<?, ?>) input.get(0)).get("role")).isEqualTo("developer");
        assertThat(((Map<?, ?>) input.get(1)).get("role")).isEqualTo("user");
    }

    @Test
    @DisplayName("多条 system 全部改写，顺序与位置不变")
    void rewritesAllSystemItemsInPlace() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", new ArrayList<>(List.of(
                messageItem("user"),
                messageItem("system"),
                messageItem("user"),
                messageItem("system"))));

        stage.apply(body);

        List<?> input = (List<?>) body.get("input");
        assertThat(input).hasSize(4);
        assertThat(((Map<?, ?>) input.get(1)).get("role")).isEqualTo("developer");
        assertThat(((Map<?, ?>) input.get(3)).get("role")).isEqualTo("developer");
    }

    @Test
    @DisplayName("非 message item（function_call 等）不受影响")
    void leavesNonMessageItemsAlone() {
        Map<String, Object> call = functionCallItem();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", List.of(call, messageItem("system")));

        stage.apply(body);

        assertThat(call).as("function_call 没有 role 概念，不应被塞入")
                .doesNotContainKey("role");
    }

    @Test
    @DisplayName("developer role 的 message 不重复处理（幂等）")
    void alreadyDeveloperStaysDeveloper() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", List.of(messageItem("developer")));

        stage.apply(body);

        assertThat(((Map<?, ?>) ((List<?>) body.get("input")).get(0)).get("role"))
                .isEqualTo("developer");
    }

    @Test
    @DisplayName("没有 system 时 body 原样（含 input 缺失的情形）")
    void noOpWhenNothingToRewrite() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", List.of(messageItem("user"), functionCallItem()));
        body.put("model", "m");

        stage.apply(body);

        assertThat(((Map<?, ?>) ((List<?>) body.get("input")).get(0)).get("role"))
                .isEqualTo("user");

        Map<String, Object> noInput = new LinkedHashMap<>();
        stage.apply(noInput);
        assertThat(noInput).isEmpty();
    }

    @Test
    @DisplayName("不可变元素（Map.of 造的 item）也能改写 —— 换元素而非原 Map put")
    void toleratesImmutableItems() {
        // wiring 冒烟测试曾用 Map.of 造 fixture，暴露了「原 Map put」在不可变元素上
        // 抛 UnsupportedOperationException 的问题 —— 改写实现改为替换元素后，
        // 这条用例把该行为钉住（数据来源是否可变不是本步骤能假设的）。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", List.of(
                Map.of("type", "message", "role", "system", "content", List.of()),
                Map.of("type", "message", "role", "user", "content", List.of())));

        stage.apply(body);

        assertThat(((Map<?, ?>) ((List<?>) body.get("input")).get(0)).get("role"))
                .isEqualTo("developer");
    }
}

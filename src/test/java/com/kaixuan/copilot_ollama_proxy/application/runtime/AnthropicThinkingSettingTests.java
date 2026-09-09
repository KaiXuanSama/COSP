package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnthropicThinkingSetting} 的解析、序列化与注入行为。
 *
 * <p>与 {@code ReasoningEffortSettingTests} / {@code MaxOutputTokensSettingTests}
 * 结构一致，但多一组「预算与形态的互动」—— 那是本字段独有的：预算只在
 * {@code enabled} 形态下有意义，而没配预算的 {@code enabled} 必须退化而不是发一个
 * 上游会拒绝的请求体。
 */
class AnthropicThinkingSettingTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("解析")
    class Parsing {

        @Test
        void v10JsonBeingParsedIntoTypeAndMode() {
            AnthropicThinkingSetting setting = AnthropicThinkingSetting.parse(
                    "{\"thinking_type\":\"enabled\",\"overwrite_mode\":\"override\"}", 8192, objectMapper);

            assertThat(setting.type()).isEqualTo(AnthropicThinkingSetting.Type.ENABLED);
            assertThat(setting.mode()).isEqualTo(AnthropicThinkingSetting.Mode.OVERRIDE);
            assertThat(setting.budgetTokens()).isEqualTo(8192);
        }

        /** 默认必须是 adaptive + 兜底：那正是 V10 之前那段硬编码的行为。 */
        @Test
        void defaultsMatchThePreV10HardcodedBehaviour() {
            AnthropicThinkingSetting setting = AnthropicThinkingSetting.defaults();

            assertThat(setting.type()).isEqualTo(AnthropicThinkingSetting.Type.ADAPTIVE);
            assertThat(setting.mode()).isEqualTo(AnthropicThinkingSetting.Mode.FALLBACK);
            assertThat(setting.hasBudget()).isFalse();
        }

        @Test
        void blankAndNullRawFallBackToDefaultsWhileKeepingBudget() {
            assertThat(AnthropicThinkingSetting.parse(null, 4096, objectMapper).type())
                    .isEqualTo(AnthropicThinkingSetting.Type.ADAPTIVE);
            // 预算是另一列，不该因为形态那一列为空就被丢掉。
            assertThat(AnthropicThinkingSetting.parse(null, 4096, objectMapper).budgetTokens())
                    .isEqualTo(4096);
            assertThat(AnthropicThinkingSetting.parse("   ", 0, objectMapper).mode())
                    .isEqualTo(AnthropicThinkingSetting.Mode.FALLBACK);
        }

        /** 脏数据不该让聊天链路失败 —— 值来自数据库。 */
        @Test
        void malformedJsonFallsBackInsteadOfThrowing() {
            AnthropicThinkingSetting setting = AnthropicThinkingSetting.parse(
                    "{\"thinking_type\":", 0, objectMapper);

            assertThat(setting.type()).isEqualTo(AnthropicThinkingSetting.Type.ADAPTIVE);
            assertThat(setting.mode()).isEqualTo(AnthropicThinkingSetting.Mode.FALLBACK);
        }

        @Test
        void unknownTypeAndModeNamesFallBackIndividually() {
            AnthropicThinkingSetting setting = AnthropicThinkingSetting.parse(
                    "{\"thinking_type\":\"telepathy\",\"overwrite_mode\":\"sideways\"}", 0, objectMapper);

            assertThat(setting.type()).isEqualTo(AnthropicThinkingSetting.Type.ADAPTIVE);
            assertThat(setting.mode()).isEqualTo(AnthropicThinkingSetting.Mode.FALLBACK);
        }

        /**
         * {@code delete} 在本字段上不存在，必须回退而不是被接受。
         *
         * <p>强制剥离 {@code thinking} 用请求体规则表达；若这里默默接受 delete，
         * 界面上到不了的档位就会从数据库里溜进运行时。
         */
        @Test
        void deleteModeIsNotAcceptedBecauseThisFieldHasOnlyThreeModes() {
            AnthropicThinkingSetting setting = AnthropicThinkingSetting.parse(
                    "{\"thinking_type\":\"adaptive\",\"overwrite_mode\":\"delete\"}", 0, objectMapper);

            assertThat(setting.mode()).isEqualTo(AnthropicThinkingSetting.Mode.FALLBACK);
        }

        @Test
        void caseInsensitiveNames() {
            AnthropicThinkingSetting setting = AnthropicThinkingSetting.parse(
                    "{\"thinking_type\":\"ENABLED\",\"overwrite_mode\":\"PassThrough\"}", 1024, objectMapper);

            assertThat(setting.type()).isEqualTo(AnthropicThinkingSetting.Type.ENABLED);
            assertThat(setting.mode()).isEqualTo(AnthropicThinkingSetting.Mode.PASSTHROUGH);
        }

        /** 0 与负数都折成哨兵：它们表达的都是「没有可用的预算值」。 */
        @Test
        void nonPositiveBudgetsCollapseToTheSentinel() {
            assertThat(AnthropicThinkingSetting.parse(null, 0, objectMapper).budgetTokens())
                    .isEqualTo(AnthropicThinkingSetting.UNSET_BUDGET_TOKENS);
            assertThat(AnthropicThinkingSetting.parse(null, -7, objectMapper).budgetTokens())
                    .isEqualTo(AnthropicThinkingSetting.UNSET_BUDGET_TOKENS);
        }
    }

    @Nested
    @DisplayName("序列化")
    class Serializing {

        /** 序列化<strong>不含</strong>预算 —— 那是另一列。 */
        @Test
        void serializeOmitsBudgetBecauseItLivesInItsOwnColumn() {
            String json = new AnthropicThinkingSetting(
                    AnthropicThinkingSetting.Type.ENABLED,
                    AnthropicThinkingSetting.Mode.OVERRIDE, 8192).serialize();

            assertThat(json).isEqualTo("{\"thinking_type\":\"enabled\",\"overwrite_mode\":\"override\"}");
            assertThat(json).doesNotContain("8192");
        }

        @Test
        void roundTripsThroughParse() {
            AnthropicThinkingSetting original = new AnthropicThinkingSetting(
                    AnthropicThinkingSetting.Type.ENABLED,
                    AnthropicThinkingSetting.Mode.PASSTHROUGH, 2048);

            AnthropicThinkingSetting reparsed = AnthropicThinkingSetting.parse(
                    original.serialize(), original.budgetTokens(), objectMapper);

            assertThat(reparsed).isEqualTo(original);
        }

        /** 序列化结果必须满足列上的 json_valid 约束，也就是能被解析回来。 */
        @Test
        void defaultsSerializeToTheColumnDefault() {
            assertThat(AnthropicThinkingSetting.defaults().serialize())
                    .isEqualTo("{\"thinking_type\":\"adaptive\",\"overwrite_mode\":\"fallback\"}");
        }
    }

    @Nested
    @DisplayName("注入模式")
    class Applying {

        @Test
        void overrideReplacesWhateverDownstreamSent() {
            Map<String, Object> body = bodyWith("thinking",
                    Map.of("type", "enabled", "budget_tokens", 999));

            new AnthropicThinkingSetting(AnthropicThinkingSetting.Type.ADAPTIVE,
                    AnthropicThinkingSetting.Mode.OVERRIDE, 0).applyTo(body);

            assertThat(body.get("thinking")).isEqualTo(Map.of("type", "adaptive"));
        }

        @Test
        void fallbackKeepsDownstreamValue() {
            Map<String, Object> body = bodyWith("thinking", Map.of("type", "enabled"));

            new AnthropicThinkingSetting(AnthropicThinkingSetting.Type.ADAPTIVE,
                    AnthropicThinkingSetting.Mode.FALLBACK, 0).applyTo(body);

            assertThat(body.get("thinking")).isEqualTo(Map.of("type", "enabled"));
        }

        @Test
        void fallbackInjectsWhenDownstreamDidNotSend() {
            Map<String, Object> body = new LinkedHashMap<>();

            new AnthropicThinkingSetting(AnthropicThinkingSetting.Type.ADAPTIVE,
                    AnthropicThinkingSetting.Mode.FALLBACK, 0).applyTo(body);

            assertThat(body.get("thinking")).isEqualTo(Map.of("type", "adaptive"));
        }

        /**
         * 下游显式发 {@code thinking: null} 也算表态，兜底层不得越权改写。
         *
         * <p>那个 null 由调用方链条末尾的 null 清洗移除，不是本层的职责。
         */
        @Test
        void fallbackTreatsExplicitNullAsDownstreamHavingSpoken() {
            Map<String, Object> body = bodyWith("thinking", null);

            new AnthropicThinkingSetting(AnthropicThinkingSetting.Type.ADAPTIVE,
                    AnthropicThinkingSetting.Mode.FALLBACK, 0).applyTo(body);

            assertThat(body).containsKey("thinking");
            assertThat(body.get("thinking")).isNull();
        }

        @Test
        void passthroughNeitherReplacesNorInjects() {
            Map<String, Object> withValue = bodyWith("thinking", Map.of("type", "enabled"));
            Map<String, Object> withoutValue = new LinkedHashMap<>();
            AnthropicThinkingSetting setting = new AnthropicThinkingSetting(
                    AnthropicThinkingSetting.Type.ADAPTIVE,
                    AnthropicThinkingSetting.Mode.PASSTHROUGH, 4096);

            setting.applyTo(withValue);
            setting.applyTo(withoutValue);

            assertThat(withValue.get("thinking")).isEqualTo(Map.of("type", "enabled"));
            assertThat(withoutValue).doesNotContainKey("thinking");
        }
    }

    @Nested
    @DisplayName("预算与形态的互动")
    class BudgetAndType {

        @Test
        void enabledWithBudgetEmitsBothFields() {
            Map<String, Object> body = new LinkedHashMap<>();

            new AnthropicThinkingSetting(AnthropicThinkingSetting.Type.ENABLED,
                    AnthropicThinkingSetting.Mode.OVERRIDE, 8192).applyTo(body);

            assertThat(body.get("thinking"))
                    .isEqualTo(Map.of("type", "enabled", "budget_tokens", 8192));
        }

        /**
         * {@code enabled} 但没配预算时退化为 {@code adaptive}。
         *
         * <p>{@code {"type":"enabled"}} 缺 {@code budget_tokens} 会被上游拒绝，
         * 而用户恰恰<strong>没有</strong>表达预算 —— 退化到「交给上游决定」是唯一
         * 能出站的解释。这不违反「不自动降级」：降级是改变用户表达过的意图。
         */
        @Test
        void enabledWithoutBudgetDegradesToAdaptiveRatherThanEmittingAnInvalidBody() {
            Map<String, Object> body = new LinkedHashMap<>();

            new AnthropicThinkingSetting(AnthropicThinkingSetting.Type.ENABLED,
                    AnthropicThinkingSetting.Mode.OVERRIDE,
                    AnthropicThinkingSetting.UNSET_BUDGET_TOKENS).applyTo(body);

            assertThat(body.get("thinking")).isEqualTo(Map.of("type", "adaptive"));
        }

        /** adaptive 形态永不携带预算，即便配了一个 —— 协议不接受。 */
        @Test
        void adaptiveNeverCarriesBudgetEvenWhenOneIsConfigured() {
            Map<String, Object> body = new LinkedHashMap<>();

            new AnthropicThinkingSetting(AnthropicThinkingSetting.Type.ADAPTIVE,
                    AnthropicThinkingSetting.Mode.OVERRIDE, 8192).applyTo(body);

            assertThat(body.get("thinking")).isEqualTo(Map.of("type", "adaptive"));
        }

        /**
         * 越界预算原样发出，不校验不钳制。
         *
         * <p>Anthropic 旧形态要求 {@code >= 1024} 且 {@code < max_tokens}，
         * 但那是用户显式填的数 —— 越界让上游用错误码回答，与「不自动降级」一致。
         */
        @Test
        void outOfRangeBudgetIsSentVerbatimForUpstreamToReject() {
            Map<String, Object> body = new LinkedHashMap<>();

            new AnthropicThinkingSetting(AnthropicThinkingSetting.Type.ENABLED,
                    AnthropicThinkingSetting.Mode.OVERRIDE, 1).applyTo(body);

            assertThat(body.get("thinking"))
                    .isEqualTo(Map.of("type", "enabled", "budget_tokens", 1));
        }
    }

    /** 允许 null 值的辅助构造，{@code Map.of} 不接受 null。 */
    private static Map<String, Object> bodyWith(String key, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(key, value);
        return body;
    }
}

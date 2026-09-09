package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MaxOutputTokensSetting} 的解析、序列化与注入行为。
 *
 * <p>与 {@code ReasoningEffortSettingTests} 结构一致，但少两组用例 ——
 * 本字段只有两种模式，不存在「透传」与「删除」。
 */
class MaxOutputTokensSettingTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    class 解析 {

        @Test
        void v9JsonBeingParsedIntoTokensAndMode() {
            MaxOutputTokensSetting setting = MaxOutputTokensSetting.parse(
                    "{\"max_output_tokens\":32000,\"overwrite_mode\":\"override\"}", objectMapper);

            assertThat(setting.maxOutputTokens()).isEqualTo(32000);
            assertThat(setting.mode()).isEqualTo(MaxOutputTokensSetting.Mode.OVERRIDE);
        }

        /** 旧的裸整数按兜底解析 —— V9 之前这个值压根没进过请求体。 */
        @Test
        void legacyPlainIntegerDefaultsToFallback() {
            MaxOutputTokensSetting setting = MaxOutputTokensSetting.parse("128000", objectMapper);

            assertThat(setting.maxOutputTokens()).isEqualTo(128000);
            assertThat(setting.mode()).isEqualTo(MaxOutputTokensSetting.Mode.FALLBACK);
        }

        /**
         * 解析<strong>不</strong>做档位重映射。
         *
         * <p>「128K 降为 4K」是 V9 迁移的一次性语义调整，只该发生在迁移里。
         * 若读取路径也做同样的映射，任何一个手工设成 128000 的值都会在每次读取时变成 4000,
         * 而用户看到的界面值与自己填的不一致 —— 一次纯读取改变了配置的呈现。
         */
        @Test
        void parseDoesNotApplyThePresetRemapping() {
            assertThat(MaxOutputTokensSetting.parse("128000", objectMapper).maxOutputTokens())
                    .isEqualTo(128000);
            assertThat(MaxOutputTokensSetting.parse("512000", objectMapper).maxOutputTokens())
                    .isEqualTo(512000);
        }

        /** 0 在旧形态里表示「未配置」，到 V9 该语义由模式承担，归一化成默认值。 */
        @Test
        void zeroAndNegativeFallBackToDefaultTokens() {
            assertThat(MaxOutputTokensSetting.parse("0", objectMapper).maxOutputTokens()).isEqualTo(4000);
            assertThat(MaxOutputTokensSetting.parse("-1", objectMapper).maxOutputTokens()).isEqualTo(4000);
        }

        @Test
        void blankAndNullFallBackToDefaults() {
            for (String raw : new String[] {null, "", "   "}) {
                MaxOutputTokensSetting setting = MaxOutputTokensSetting.parse(raw, objectMapper);
                assertThat(setting.maxOutputTokens()).isEqualTo(4000);
                assertThat(setting.mode()).isEqualTo(MaxOutputTokensSetting.Mode.FALLBACK);
            }
        }

        /** 值来自数据库，一行脏数据不该让整条聊天链路失败。 */
        @Test
        void malformedInputFallsBackToDefaultsInsteadOfThrowing() {
            assertThat(MaxOutputTokensSetting.parse("{\"max_output_tokens\":", objectMapper).maxOutputTokens())
                    .isEqualTo(4000);
            assertThat(MaxOutputTokensSetting.parse("not-a-number", objectMapper).maxOutputTokens())
                    .isEqualTo(4000);
        }

        @Test
        void unknownModeFallsBackToDefaultWhileTokensStillApply() {
            MaxOutputTokensSetting setting = MaxOutputTokensSetting.parse(
                    "{\"max_output_tokens\":16000,\"overwrite_mode\":\"bogus\"}", objectMapper);

            assertThat(setting.maxOutputTokens()).isEqualTo(16000);
            assertThat(setting.mode()).isEqualTo(MaxOutputTokensSetting.Mode.FALLBACK);
        }

        /** 缺 ObjectMapper 时不能崩：裸整数仍要能解析，JSON 形态退默认。 */
        @Test
        void nullObjectMapperStillHandlesLegacyFormat() {
            assertThat(MaxOutputTokensSetting.parse("8000", null).maxOutputTokens()).isEqualTo(8000);
            assertThat(MaxOutputTokensSetting.parse("{\"max_output_tokens\":8000}", null).maxOutputTokens())
                    .isEqualTo(4000);
        }
    }

    @Nested
    class 序列化 {

        @Test
        void serializesToCanonicalV9Json() {
            assertThat(new MaxOutputTokensSetting(4000, MaxOutputTokensSetting.Mode.FALLBACK).serialize())
                    .isEqualTo("{\"max_output_tokens\":4000,\"overwrite_mode\":\"fallback\"}");
        }

        /** token 上限是数字而非字符串 —— 界面与迁移都靠 json_extract 直接取整数。 */
        @Test
        void tokensAreSerializedAsJsonNumber() {
            assertThat(new MaxOutputTokensSetting(32000, MaxOutputTokensSetting.Mode.OVERRIDE).serialize())
                    .contains("\"max_output_tokens\":32000")
                    .doesNotContain("\"32000\"");
        }

        @Test
        void roundTripsThroughParse() {
            for (MaxOutputTokensSetting.Mode mode : MaxOutputTokensSetting.Mode.values()) {
                MaxOutputTokensSetting original = new MaxOutputTokensSetting(64000, mode);
                assertThat(MaxOutputTokensSetting.parse(original.serialize(), objectMapper))
                        .isEqualTo(original);
            }
        }
    }

    @Nested
    class 注入模式 {

        @Test
        void overrideReplacesDownstreamValue() {
            Map<String, Object> body = bodyWith(999);

            new MaxOutputTokensSetting(4000, MaxOutputTokensSetting.Mode.OVERRIDE).applyTo(body);

            assertThat(body).containsEntry("max_tokens", 4000);
        }

        @Test
        void fallbackKeepsDownstreamValue() {
            Map<String, Object> body = bodyWith(999);

            new MaxOutputTokensSetting(4000, MaxOutputTokensSetting.Mode.FALLBACK).applyTo(body);

            assertThat(body).containsEntry("max_tokens", 999);
        }

        @Test
        void fallbackInjectsConfiguredLimitWhenDownstreamOmitted() {
            Map<String, Object> body = new LinkedHashMap<>();

            new MaxOutputTokensSetting(8000, MaxOutputTokensSetting.Mode.FALLBACK).applyTo(body);

            assertThat(body).containsEntry("max_tokens", 8000);
        }

        /**
         * 下游显式传 null 时，兜底模式不该用配置值把它顶掉 —— 与思考深度一致。
         *
         * <p>那个 null 随后由调用方的 null 清洗移除。用 {@code containsKey} 而非判空
         * 正是为了这个：显式的 null 是「它表达过意见」。
         */
        @Test
        void fallbackRespectsExplicitNullFromDownstream() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("max_tokens", null);

            new MaxOutputTokensSetting(8000, MaxOutputTokensSetting.Mode.FALLBACK).applyTo(body);

            assertThat(body).containsKey("max_tokens");
            assertThat(body.get("max_tokens")).isNull();
        }

        /**
         * 只有两种模式。
         *
         * <p>没有 passthrough / delete 不是简化：Anthropic 侧 {@code max_tokens} 缺失会直接 400,
         * 「不补」或「剥离」在那条线路上等于必然失败。这条断言把这个决定钉在测试里，
         * 将来若有人顺手补齐四档，会先看到它。
         */
        @Test
        void onlyTwoModesExist() {
            assertThat(MaxOutputTokensSetting.Mode.values())
                    .containsExactly(MaxOutputTokensSetting.Mode.OVERRIDE,
                            MaxOutputTokensSetting.Mode.FALLBACK);
        }

        private Map<String, Object> bodyWith(int maxTokens) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("max_tokens", maxTokens);
            return body;
        }
    }
}

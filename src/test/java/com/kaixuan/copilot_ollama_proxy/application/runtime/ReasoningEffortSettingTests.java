package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 思考深度配置的解析与注入。
 *
 * <p>这个类的价值集中在两处：**历史形态的兼容口径**（尤其旧的 {@code None}），
 * 以及**三种注入模式对下游已有值的处置**。前者决定升级后行为是否改变，
 * 后者是这次扩展的全部目的。
 */
class ReasoningEffortSettingTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    class 解析 {

        @Test
        void v2JsonBeingParsedIntoEffortAndMode() {
            ReasoningEffortSetting setting = ReasoningEffortSetting.parse(
                    "{\"reasoning_effort\":\"high\",\"overwrite_mode\":\"override\"}", objectMapper);

            assertThat(setting.effort()).isEqualTo("high");
            assertThat(setting.mode()).isEqualTo(ReasoningEffortSetting.Mode.OVERRIDE);
        }

        /** 档位一律小写：上游协议里该字段取值是小写的，统一在解析处收敛。 */
        @Test
        void effortIsLowercasedRegardlessOfStoredCase() {
            assertThat(ReasoningEffortSetting.parse(
                    "{\"reasoning_effort\":\"XHigh\",\"overwrite_mode\":\"delete\"}", objectMapper).effort())
                    .isEqualTo("xhigh");
        }

        /** 旧的纯档位按兜底解析 —— 那正是 V2 之前的唯一行为（只在下游未携带时注入）。 */
        @Test
        void legacyPlainEffortDefaultsToFallback() {
            ReasoningEffortSetting setting = ReasoningEffortSetting.parse("Medium", objectMapper);

            assertThat(setting.effort()).isEqualTo("medium");
            assertThat(setting.mode()).isEqualTo(ReasoningEffortSetting.Mode.FALLBACK);
        }

        @Test
        void legacyCommaSeparatedValueTakesFirstEntry() {
            assertThat(ReasoningEffortSetting.parse("High,Max", objectMapper).effort()).isEqualTo("high");
        }

        /**
         * 这是兼容逻辑存在的主要理由。
         *
         * <p>旧的 {@code None} 表达的是「不发送」。若把它当作认不出的档位回退成
         * {@code medium}，那些模型会突然开始向上游发送 {@code medium} ——
         * 一次纯粹的读取行为改变了运行时行为，且用户无从察觉。
         */
        @Test
        void legacyNoneMapsToDeleteModeRatherThanFallingBackToDefaultEffort() {
            assertThat(ReasoningEffortSetting.parse("None", objectMapper).mode())
                    .isEqualTo(ReasoningEffortSetting.Mode.DELETE);
            assertThat(ReasoningEffortSetting.parse("none", objectMapper).mode())
                    .isEqualTo(ReasoningEffortSetting.Mode.DELETE);
        }

        /**
         * 新的 off 档位不能被上面那条兼容规则吞掉。
         *
         * <p>两者语义相反：旧的裸 {@code "None"} 是「两个字段都不发」（DELETE），
         * 而 off 档是「发送 {@code thinking:{"type":"disabled"}} 明确要求不思考」。
         * V2 JSON 走独立的解析分支，因此不会撞上那条规则 —— 这条用例钉住这个边界，
         * 混淆会让一个明确的要求退化成沉默。
         */
        @Test
        void v2JsonWithOffEffortKeepsItsModeInsteadOfBecomingDelete() {
            ReasoningEffortSetting setting = ReasoningEffortSetting.parse(
                    "{\"reasoning_effort\":\"off\",\"overwrite_mode\":\"override\"}", objectMapper);

            assertThat(setting.effort()).isEqualTo(ReasoningEffortSetting.EFFORT_OFF);
            assertThat(setting.mode()).isEqualTo(ReasoningEffortSetting.Mode.OVERRIDE);
        }

        /** 新增的两个档位不被改写 —— 本类不校验档位取值，用户配了什么就是什么。 */
        @Test
        void newEffortTiersArePreservedVerbatim() {
            for (String effort : new String[] {"off", "minimal", "xhigh", "max"}) {
                assertThat(ReasoningEffortSetting.parse(
                        "{\"reasoning_effort\":\"" + effort + "\",\"overwrite_mode\":\"override\"}",
                        objectMapper).effort())
                        .isEqualTo(effort);
            }
        }

        /**
         * 上游不认识的档位也原样保留，不做任何降级。
         *
         * <p>参考实现（new-api）会把 {@code xhigh} 在不支持的模型上退成 {@code max} 或
         * {@code high}。本服务刻意不做 —— 用户配了什么就发什么，
         * 「上游认不认」由上游用错误码回答，而不是本服务替它猜。
         */
        @Test
        void unknownEffortTierIsNotDowngraded() {
            assertThat(ReasoningEffortSetting.parse(
                    "{\"reasoning_effort\":\"ultra\",\"overwrite_mode\":\"override\"}",
                    objectMapper).effort())
                    .isEqualTo("ultra");
        }

        @Test
        void blankAndNullFallBackToDefaults() {
            for (String raw : new String[] {null, "", "   "}) {
                ReasoningEffortSetting setting = ReasoningEffortSetting.parse(raw, objectMapper);
                assertThat(setting.effort()).isEqualTo("medium");
                assertThat(setting.mode()).isEqualTo(ReasoningEffortSetting.Mode.FALLBACK);
            }
        }

        /** 值来自数据库，一行脏数据不该让整条聊天链路失败。 */
        @Test
        void malformedJsonFallsBackToDefaultsInsteadOfThrowing() {
            ReasoningEffortSetting setting = ReasoningEffortSetting.parse(
                    "{\"reasoning_effort\":", objectMapper);

            assertThat(setting.effort()).isEqualTo("medium");
            assertThat(setting.mode()).isEqualTo(ReasoningEffortSetting.Mode.FALLBACK);
        }

        @Test
        void unknownModeFallsBackToDefaultWhileEffortStillApplies() {
            ReasoningEffortSetting setting = ReasoningEffortSetting.parse(
                    "{\"reasoning_effort\":\"low\",\"overwrite_mode\":\"bogus\"}", objectMapper);

            assertThat(setting.effort()).isEqualTo("low");
            assertThat(setting.mode()).isEqualTo(ReasoningEffortSetting.Mode.FALLBACK);
        }

        /** 缺 ObjectMapper 时不能崩：非 JSON 形态仍要能解析，JSON 形态退默认。 */
        @Test
        void nullObjectMapperStillHandlesLegacyFormats() {
            assertThat(ReasoningEffortSetting.parse("Max", null).effort()).isEqualTo("max");
            assertThat(ReasoningEffortSetting.parse("{\"reasoning_effort\":\"max\"}", null).effort())
                    .isEqualTo("medium");
        }
    }

    @Nested
    class 序列化 {

        @Test
        void serializesToCanonicalV2Json() {
            assertThat(new ReasoningEffortSetting("High", ReasoningEffortSetting.Mode.OVERRIDE).serialize())
                    .isEqualTo("{\"reasoning_effort\":\"high\",\"overwrite_mode\":\"override\"}");
        }

        @Test
        void roundTripsThroughParse() {
            for (ReasoningEffortSetting.Mode mode : ReasoningEffortSetting.Mode.values()) {
                ReasoningEffortSetting original = new ReasoningEffortSetting("max", mode);
                ReasoningEffortSetting parsed =
                        ReasoningEffortSetting.parse(original.serialize(), objectMapper);
                assertThat(parsed).isEqualTo(original);
            }
        }
    }

    @Nested
    class 注入模式 {

        /** 覆写：无视下游带来的值。 */
        @Test
        void overrideReplacesDownstreamValue() {
            Map<String, Object> body = bodyWith("low");

            new ReasoningEffortSetting("max", ReasoningEffortSetting.Mode.OVERRIDE).applyTo(body);

            assertThat(body).containsEntry("reasoning_effort", "max");
        }

        @Test
        void overrideInjectsWhenDownstreamOmitted() {
            Map<String, Object> body = new LinkedHashMap<>();

            new ReasoningEffortSetting("max", ReasoningEffortSetting.Mode.OVERRIDE).applyTo(body);

            assertThat(body).containsEntry("reasoning_effort", "max");
        }

        /** 兜底：下游有意见就听它的。 */
        @Test
        void fallbackKeepsDownstreamValue() {
            Map<String, Object> body = bodyWith("low");

            new ReasoningEffortSetting("max", ReasoningEffortSetting.Mode.FALLBACK).applyTo(body);

            assertThat(body).containsEntry("reasoning_effort", "low");
        }

        /**
         * 下游只发了 {@code thinking:{"type":"disabled"}} 时，兜底档必须尊重它。
         *
         * <p>这是本组用例里最关键的一条。{@code thinking} 与 {@code reasoning_effort}
         * 是正交字段，只看后者会漏掉这种表态 —— 结果是给一个明确要求「别思考」的请求
         * 又补了一个「思考要多深」，两个矛盾指令一起发给上游。
         */
        @Test
        void fallbackRespectsDownstreamThinkingDisabled() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("thinking", Map.of("type", "disabled"));

            new ReasoningEffortSetting("high", ReasoningEffortSetting.Mode.FALLBACK).applyTo(body);

            assertThat(body).containsOnlyKeys("thinking");
            assertThat(body).doesNotContainKey("reasoning_effort");
        }

        /** 下游发的是 enabled 也算表态 —— 兜底档一样不干预。 */
        @Test
        void fallbackRespectsDownstreamThinkingEnabled() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("thinking", Map.of("type", "enabled"));

            new ReasoningEffortSetting("low", ReasoningEffortSetting.Mode.FALLBACK).applyTo(body);

            assertThat(body).containsOnlyKeys("thinking");
        }

        /**
         * off 档写出的是 {@code thinking:{"type":"disabled"}}，不是一个档位值。
         *
         * <p>{@code reasoning_effort} 在 OpenAI Chat Completions 协议里没有 {@code none}
         * 这一档（DeepSeek 只认 low/high/max），那个值属于 Responses 协议的
         * {@code reasoning.effort}。写错会让上游拒绝请求。
         */
        @Test
        void offTierWritesThinkingDisabledRatherThanAnEffortValue() {
            Map<String, Object> body = new LinkedHashMap<>();

            new ReasoningEffortSetting(ReasoningEffortSetting.EFFORT_OFF,
                    ReasoningEffortSetting.Mode.OVERRIDE).applyTo(body);

            assertThat(body).containsOnlyKeys("thinking");
            assertThat(body).containsEntry("thinking", Map.of("type", "disabled"));
            assertThat(body).doesNotContainKey("reasoning_effort");
        }

        /**
         * 覆写档配成 off 时，要清掉下游的档位再写开关。
         *
         * <p>只写 {@code thinking} 而不删 {@code reasoning_effort} 会让请求体同时含有
         * 「不要思考」和「思考要多深」—— 这正是覆写档最容易出错的地方。
         */
        @Test
        void overrideWithOffClearsDownstreamEffort() {
            Map<String, Object> body = bodyWith("high");

            new ReasoningEffortSetting(ReasoningEffortSetting.EFFORT_OFF,
                    ReasoningEffortSetting.Mode.OVERRIDE).applyTo(body);

            assertThat(body).containsOnlyKeys("thinking");
            assertThat(body).containsEntry("thinking", Map.of("type", "disabled"));
        }

        /**
         * 反方向：覆写档配成具体档位时，要清掉下游的 thinking 开关。
         *
         * <p>下游说「别思考」而配置说「思考到 high」—— 尊重用户配置优先于下游意图，
         * 这正是覆写档的定义。留着那个 disabled 会让配置的档位形同虚设。
         */
        @Test
        void overrideWithTierClearsDownstreamThinkingDisabled() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("thinking", Map.of("type", "disabled"));

            new ReasoningEffortSetting("high", ReasoningEffortSetting.Mode.OVERRIDE).applyTo(body);

            assertThat(body).containsOnlyKeys("reasoning_effort");
            assertThat(body).containsEntry("reasoning_effort", "high");
        }

        /** 覆写档同时清掉两个字段再重建，不留任何下游残留。 */
        @Test
        void overrideClearsBothFieldsBeforeWriting() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reasoning_effort", "low");
            body.put("thinking", Map.of("type", "enabled"));

            new ReasoningEffortSetting("max", ReasoningEffortSetting.Mode.OVERRIDE).applyTo(body);

            assertThat(body).containsOnlyKeys("reasoning_effort");
            assertThat(body).containsEntry("reasoning_effort", "max");
        }

        /** 删除档检测到哪个删哪个，两个都在就都删。 */
        @Test
        void deleteRemovesBothFields() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reasoning_effort", "low");
            body.put("thinking", Map.of("type", "enabled"));
            body.put("model", "keep-me");

            new ReasoningEffortSetting("high", ReasoningEffortSetting.Mode.DELETE).applyTo(body);

            assertThat(body).containsOnlyKeys("model");
        }

        /** 删除档只有 thinking 时也要删掉它。 */
        @Test
        void deleteRemovesThinkingAlone() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("thinking", Map.of("type", "disabled"));

            new ReasoningEffortSetting("high", ReasoningEffortSetting.Mode.DELETE).applyTo(body);

            assertThat(body).isEmpty();
        }

        /** 透传档两个字段都不碰。 */
        @Test
        void passthroughLeavesThinkingUntouched() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("thinking", Map.of("type", "disabled"));

            new ReasoningEffortSetting("high", ReasoningEffortSetting.Mode.PASSTHROUGH).applyTo(body);

            assertThat(body).containsOnlyKeys("thinking");
            assertThat(body).containsEntry("thinking", Map.of("type", "disabled"));
        }

        @Test
        void fallbackInjectsConfiguredEffortWhenDownstreamOmitted() {
            Map<String, Object> body = new LinkedHashMap<>();

            new ReasoningEffortSetting("high", ReasoningEffortSetting.Mode.FALLBACK).applyTo(body);

            assertThat(body).containsEntry("reasoning_effort", "high");
        }

        /**
         * 下游显式传 null 时，兜底模式不该用配置值把它顶掉。
         *
         * <p>用 {@code containsKey} 而非判空正是为了这个：显式的 null 是「它表达过意见」。
         * 那个 null 随后由调用方的 {@code removeIf(Objects::isNull)} 清掉，
         * 最终等效于不发送 —— 而若这里用配置值填上，结果会与下游的意图相反。
         */
        @Test
        void fallbackRespectsExplicitNullFromDownstream() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reasoning_effort", null);

            new ReasoningEffortSetting("high", ReasoningEffortSetting.Mode.FALLBACK).applyTo(body);

            assertThat(body).containsKey("reasoning_effort");
            assertThat(body.get("reasoning_effort")).isNull();
        }

        /** 透传：完全不干预，下游带什么就是什么。 */
        @Test
        void passthroughKeepsDownstreamValue() {
            Map<String, Object> body = bodyWith("low");

            new ReasoningEffortSetting("max", ReasoningEffortSetting.Mode.PASSTHROUGH).applyTo(body);

            assertThat(body).containsEntry("reasoning_effort", "low");
        }

        /**
         * 这一条是透传与兜底的唯一分野，也是四档里最容易被合并掉的一档。
         *
         * <p>兜底会在这里补上配置档位，透传什么都不做 —— 配置的档位只作为界面上的
         * 记忆值存在。少了这条断言，把 PASSTHROUGH 实现成 FALLBACK 不会有任何用例变红。
         */
        @Test
        void passthroughDoesNotInjectWhenDownstreamOmitted() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "m");

            new ReasoningEffortSetting("high", ReasoningEffortSetting.Mode.PASSTHROUGH).applyTo(body);

            assertThat(body).containsOnlyKeys("model");
        }

        /** 删除：连下游自己带的也一并移除。 */
        @Test
        void deleteRemovesDownstreamValue() {
            Map<String, Object> body = bodyWith("low");

            new ReasoningEffortSetting("max", ReasoningEffortSetting.Mode.DELETE).applyTo(body);

            assertThat(body).doesNotContainKey("reasoning_effort");
        }

        @Test
        void deleteIsNoOpWhenFieldAbsent() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "m");

            new ReasoningEffortSetting("max", ReasoningEffortSetting.Mode.DELETE).applyTo(body);

            assertThat(body).containsOnlyKeys("model");
        }

        private Map<String, Object> bodyWith(String effort) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reasoning_effort", effort);
            return body;
        }
    }
}

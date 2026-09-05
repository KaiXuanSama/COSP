package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Anthropic 线路的注入。
     *
     * <p>与上一组是<strong>同一份配置、同一套四档语义</strong>，只有出站字段名与形态不同：
     * 深度写顶层 {@code output_config.effort} 而非 {@code reasoning_effort}。
     * 因此这里不重复验证模式语义，只钉住三件本线路独有的事：
     * <ol>
     *   <li>写的是 {@code output_config.effort}；</li>
     *   <li>{@code off} 档借 {@code thinking:{"type":"disabled"}} 表达，并返回 true
     *       让调用方跳过思考方式那一维；</li>
     *   <li>不越权改写 {@code adaptive} / {@code enabled} 形态的 {@code thinking}。</li>
     * </ol>
     */
    @Nested
    class Anthropic注入模式 {

        @Test
        void overrideWritesOutputConfigEffort() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "claude-x");

            boolean disabled = new ReasoningEffortSetting("high",
                    ReasoningEffortSetting.Mode.OVERRIDE).applyToAnthropic(body);

            assertThat(disabled).isFalse();
            assertThat(effortOf(body)).isEqualTo("high");
            // 绝不写 OpenAI 的字段名 —— 上游认不出来，等于这一维没生效。
            assertThat(body).doesNotContainKey("reasoning_effort");
        }

        /** 覆写档要清掉下游那个与档位直接冲突的 disabled。 */
        @Test
        void overrideClearsDownstreamDisabledThinking() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("thinking", Map.of("type", "disabled"));

            new ReasoningEffortSetting("max", ReasoningEffortSetting.Mode.OVERRIDE).applyToAnthropic(body);

            assertThat(body).doesNotContainKey("thinking");
            assertThat(effortOf(body)).isEqualTo("max");
        }

        /**
         * 但**只**清 disabled。
         *
         * <p>{@code adaptive} 与 {@code enabled+budget_tokens} 属于思考<strong>方式</strong>
         * 那一维、与深度正交，顺手清掉等于替另一个维度做决定。
         */
        @Test
        void overrideKeepsAdaptiveAndEnabledThinking() {
            for (Map<String, Object> thinking : List.<Map<String, Object>>of(
                    Map.of("type", "adaptive"),
                    Map.of("type", "enabled", "budget_tokens", 4096))) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("thinking", thinking);

                new ReasoningEffortSetting("high", ReasoningEffortSetting.Mode.OVERRIDE)
                        .applyToAnthropic(body);

                assertThat(body.get("thinking")).isEqualTo(thinking);
                assertThat(effortOf(body)).isEqualTo("high");
            }
        }

        /**
         * {@code off} 档只能靠 {@code thinking} 表达 —— 五档里没有「不思考」。
         *
         * <p>返回 true 是给调用方的信号：这次调用不思考，思考方式那一组整体失去意义。
         * 不跳过的话，方式的覆写档会把 disabled 改写成 adaptive，用户配的「关闭思考」
         * 就被静默丢弃了。
         */
        @Test
        void offTierWritesDisabledThinkingAndReportsIt() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "claude-x");

            boolean disabled = new ReasoningEffortSetting(ReasoningEffortSetting.EFFORT_OFF,
                    ReasoningEffortSetting.Mode.OVERRIDE).applyToAnthropic(body);

            assertThat(disabled).isTrue();
            assertThat(thinkingTypeOf(body)).isEqualTo("disabled");
            // 「别思考」与「想这么深」并存是自相矛盾的请求体。
            assertThat(body).doesNotContainKey("output_config");
        }

        /** off 档遇到下游残留的档位时也要清掉，理由同上。 */
        @Test
        void offTierClearsDownstreamEffort() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("output_config", new LinkedHashMap<>(Map.of("effort", "max")));

            new ReasoningEffortSetting(ReasoningEffortSetting.EFFORT_OFF,
                    ReasoningEffortSetting.Mode.OVERRIDE).applyToAnthropic(body);

            assertThat(body).doesNotContainKey("output_config");
            assertThat(thinkingTypeOf(body)).isEqualTo("disabled");
        }

        /** 摘掉 effort 后容器里还有别的键时，保留容器与那些键。 */
        @Test
        void removingEffortPreservesOtherOutputConfigKeys() {
            Map<String, Object> body = new LinkedHashMap<>();
            Map<String, Object> outputConfig = new LinkedHashMap<>();
            outputConfig.put("effort", "low");
            outputConfig.put("verbosity", "high");
            body.put("output_config", outputConfig);

            new ReasoningEffortSetting(ReasoningEffortSetting.EFFORT_OFF,
                    ReasoningEffortSetting.Mode.OVERRIDE).applyToAnthropic(body);

            assertThat(outputConfigOf(body)).containsOnlyKeys("verbosity");
        }

        /** 覆写档写入档位时同样保留容器里的其它键。 */
        @Test
        void writingEffortPreservesOtherOutputConfigKeys() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("output_config", new LinkedHashMap<>(Map.of("verbosity", "low")));

            new ReasoningEffortSetting("xhigh", ReasoningEffortSetting.Mode.OVERRIDE)
                    .applyToAnthropic(body);

            assertThat(outputConfigOf(body))
                    .containsEntry("effort", "xhigh")
                    .containsEntry("verbosity", "low");
        }

        @Test
        void fallbackInjectsWhenDownstreamSilent() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "claude-x");

            new ReasoningEffortSetting("low", ReasoningEffortSetting.Mode.FALLBACK).applyToAnthropic(body);

            assertThat(effortOf(body)).isEqualTo("low");
        }

        /**
         * 「下游已表态」的三个来源都要认。
         *
         * <p>漏掉 {@code thinking} 会让兜底档给一个明确要求「别思考」的请求再补一个深度；
         * 漏掉 {@code reasoning_effort} 会在直连线路上忽略下游发错字段名的表态。
         */
        @Test
        void fallbackRespectsEveryFormOfDownstreamOpinion() {
            for (Map.Entry<String, Object> opinion : List.<Map.Entry<String, Object>>of(
                    Map.entry("thinking", Map.of("type", "disabled")),
                    Map.entry("thinking", Map.of("type", "adaptive")),
                    Map.entry("reasoning_effort", "low"),
                    Map.entry("output_config", Map.of("effort", "low")))) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put(opinion.getKey(), opinion.getValue());

                new ReasoningEffortSetting("max", ReasoningEffortSetting.Mode.FALLBACK)
                        .applyToAnthropic(body);

                assertThat(body).as("表态来源 %s 应阻止兜底注入", opinion.getKey())
                        .containsExactlyEntriesOf(Map.of(opinion.getKey(), opinion.getValue()));
            }
        }

        /**
         * 空的 {@code output_config} 不算深度表态。
         *
         * <p>那个容器还承载别的设置，仅仅出现它不代表下游对深度有意见 ——
         * 据此跳过注入会让兜底档在用户什么都没说时失效。
         */
        @Test
        void fallbackStillInjectsWhenOutputConfigCarriesNoEffort() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("output_config", new LinkedHashMap<>(Map.of("verbosity", "low")));

            new ReasoningEffortSetting("high", ReasoningEffortSetting.Mode.FALLBACK).applyToAnthropic(body);

            assertThat(outputConfigOf(body)).containsEntry("effort", "high");
        }

        @Test
        void passthroughNeverTouchesTheBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "claude-x");

            new ReasoningEffortSetting("high", ReasoningEffortSetting.Mode.PASSTHROUGH)
                    .applyToAnthropic(body);

            assertThat(body).containsOnlyKeys("model");
        }

        /** 删除档要同时清掉原生字段与 O2A 留下的兼容副本。 */
        @Test
        void deleteRemovesBothEffortForms() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("output_config", new LinkedHashMap<>(Map.of("effort", "max")));
            body.put("reasoning_effort", "max");
            body.put("model", "claude-x");

            boolean disabled = new ReasoningEffortSetting("high",
                    ReasoningEffortSetting.Mode.DELETE).applyToAnthropic(body);

            assertThat(disabled).isFalse();
            assertThat(body).containsOnlyKeys("model");
        }

        /**
         * 删除档不碰 {@code thinking}。
         *
         * <p>这是与 OpenAI 侧 {@code applyTo} 的刻意差异：那边界面把开关与深度压成一个
         * 选择器，所以删除要清两个字段；这边思考方式是独立控件、有自己的注入模式，
         * 深度的删除档无权替它做决定。要强制剥离 {@code thinking} 用仅适用 ANTHROPIC
         * 的请求体规则。
         */
        @Test
        void deleteLeavesThinkingToItsOwnDimension() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("thinking", Map.of("type", "adaptive"));

            new ReasoningEffortSetting("high", ReasoningEffortSetting.Mode.DELETE).applyToAnthropic(body);

            assertThat(thinkingTypeOf(body)).isEqualTo("adaptive");
        }

        /**
         * 不校验档位是否落在 Anthropic 的五档内。
         *
         * <p>{@code minimal} 在那边无对应档，原样发出由上游用错误码回答 ——
         * 与「不做自动降级」一致，也与 OpenAI 侧的口径一致。
         */
        @Test
        void tierOutsideAnthropicRangeIsSentVerbatim() {
            Map<String, Object> body = new LinkedHashMap<>();

            new ReasoningEffortSetting("minimal", ReasoningEffortSetting.Mode.OVERRIDE)
                    .applyToAnthropic(body);

            assertThat(effortOf(body)).isEqualTo("minimal");
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> outputConfigOf(Map<String, Object> body) {
            return (Map<String, Object>) body.get("output_config");
        }

        private Object effortOf(Map<String, Object> body) {
            return outputConfigOf(body).get("effort");
        }

        @SuppressWarnings("unchecked")
        private Object thinkingTypeOf(Map<String, Object> body) {
            return ((Map<String, Object>) body.get("thinking")).get("type");
        }
    }
}

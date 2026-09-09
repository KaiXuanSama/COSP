package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 模型的 Anthropic 思考方式配置：形态 + 注入模式 + 预算。
 *
 * <h2>与思考深度是两个正交维度</h2>
 * 思考深度（{@link ReasoningEffortSetting}）回答「想多深」，本设置回答「怎么算预算」：
 *
 * <pre>
 * thinking: {"type":"adaptive"}                    上游自行决定预算
 * thinking: {"type":"enabled","budget_tokens":N}   手动指定预算
 * </pre>
 *
 * 两者可以并存 —— DeepSeek 官方示例就把 {@code thinking} 与 {@code reasoning_effort}
 * 并列给出。所以「深度 = High」配「方式 = adaptive」不矛盾：前者说想多想，
 * 后者说预算交给上游定。
 *
 * <h2>为何没有 disabled 形态</h2>
 * 关闭思考已经由思考深度的 {@code Off} 档表达，而且那一档在 OpenAI 与 Anthropic
 * 两条线路上都成立。若这里再给一个 {@code disabled}，同一个意图就有两个入口，
 * 而且各自带注入模式 —— 「深度=High 覆写」配「方式=disabled 覆写」会产出自相矛盾的
 * 请求体。思考开关因此只留在深度一处，本设置只回答「开了之后预算怎么定」。
 *
 * <h2>为何模式只有三档</h2>
 * 没有 {@code DELETE}：强制剥离 {@code thinking} 属于高级行为，用仅适用于
 * {@code ANTHROPIC} 的请求体规则即可表达，不值得在基础 UI 上多一档。
 * 约束照三档写，与前端 {@code AnthropicThinkingOverwriteMode} 逐一对应 ——
 * 留一个界面上到不了的档位会让人以为漏了 UI。
 *
 * <h2>预算没有自己的注入模式</h2>
 * 预算是 {@code enabled} 形态的<strong>附属参数</strong>，跟着本设置的模式走。
 * 给它单独一档模式会允许表达「方式覆写但预算兜底」这种没有真实意图的组合。
 * 因此它在数据库里是裸 {@code INTEGER}，不是第三个 {@code {值, 模式}} JSON。
 *
 * <p>{@link #UNSET_BUDGET_TOKENS}（{@code -1}）是「未设置」哨兵而非可出站的值：
 * {@code adaptive} 形态本就不接受预算，而 {@code enabled} 形态下若仍是 -1，
 * 说明用户没填 —— 那时退化成 {@code adaptive}（见 {@link #applyTo}），
 * 而不是发一个上游必然拒绝的负数。
 *
 * <h2>持久化形态</h2>
 * 形态与模式合存于 {@code provider_model.thinking_mode}（V10 起的 JSON）：
 * <pre>{@code {"thinking_type":"adaptive","overwrite_mode":"fallback"}}</pre>
 * 预算单独存在 {@code provider_model.thinking_budget_tokens}。
 *
 * <h2>接线范围</h2>
 * 只有 <strong>Anthropic</strong> 线路消费本设置
 * （{@code GenericAnthropicChatService.prepareRequestBody}）。OpenAI 侧不接 ——
 * {@code thinking} 在那边虽然也存在（小米 MiMo 把 {@code thinking.type} 列为必选），
 * 但接上去会改变所有现存 OpenAI 供应商的出站请求体，需要单独评估影响面。
 */
public record AnthropicThinkingSetting(Type type, Mode mode, int budgetTokens) {

    /**
     * 思考方式的形态。
     *
     * <p>没有 {@code DISABLED}，理由见类注释。
     */
    public enum Type {
        /** 自适应：上游自行决定思考预算，不接受 {@code budget_tokens}。 */
        ADAPTIVE,
        /** 手动：需要配合 {@code budget_tokens} 指定预算。 */
        ENABLED
    }

    /**
     * 注入模式。
     *
     * <pre>
     *              下游带了 thinking     下游没带
     * OVERRIDE     用配置的形态          用配置的形态
     * FALLBACK     用下游的              用配置的形态
     * PASSTHROUGH  用下游的              也不添加
     * </pre>
     *
     * <p>{@code FALLBACK} 是默认，与 V10 之前那段硬编码的临时语义一致 ——
     * 那时无条件「下游没带就补 adaptive」，正是本模式的行为。默认选它，
     * 升级前后的出站请求体不变。
     */
    public enum Mode {
        /** 覆写：无论下游有没有带，都用配置的形态。 */
        OVERRIDE,
        /** 兜底：下游带了就用它的，没带才用配置的形态。 */
        FALLBACK,
        /** 透传：下游带了就用它的，没带也不补。 */
        PASSTHROUGH
    }

    /** 上游请求体里思考方式的字段名。 */
    public static final String REQUEST_FIELD = "thinking";

    /** 持久化 JSON 里承载形态的键名。 */
    public static final String TYPE_KEY = "thinking_type";

    /** 持久化 JSON 里承载注入模式的键名，与另外两个设置同名。 */
    public static final String MODE_KEY = "overwrite_mode";

    /**
     * 预算的「未设置」哨兵。
     *
     * <p>取 -1 而非 0：0 落在 Anthropic 旧形态的合法区间之外但看着像个真实值，
     * 而负数一眼就是哨兵。数据库列的默认值也是它。
     */
    public static final int UNSET_BUDGET_TOKENS = -1;

    private static final Type DEFAULT_TYPE = Type.ADAPTIVE;
    private static final Mode DEFAULT_MODE = Mode.FALLBACK;

    /** 配置缺失时的兜底：adaptive + 兜底模式 + 未设置预算。 */
    public static AnthropicThinkingSetting defaults() {
        return new AnthropicThinkingSetting(DEFAULT_TYPE, DEFAULT_MODE, UNSET_BUDGET_TOKENS);
    }

    /**
     * 归一化：null 回退默认，非正预算折成哨兵。
     *
     * <p>把 0 与负数都折成 {@link #UNSET_BUDGET_TOKENS}，是因为它们表达的是同一件事
     * （没有可用的预算值），而下游只需要区分「有没有」。
     */
    public AnthropicThinkingSetting {
        type = type == null ? DEFAULT_TYPE : type;
        mode = mode == null ? DEFAULT_MODE : mode;
        budgetTokens = budgetTokens > 0 ? budgetTokens : UNSET_BUDGET_TOKENS;
    }

    /** 是否配了可用的预算。 */
    public boolean hasBudget() {
        return budgetTokens > 0;
    }

    /**
     * 解析持久化的形态与模式，预算由调用方单独给出。
     *
     * <p>两个入参分列是因为它们在数据库里就是两列 —— 这里不把预算塞进 JSON 再解出来，
     * 那样只是为了「一个参数」而多一道序列化。
     *
     * <p>解析失败回退默认值而不抛异常：值来自数据库，一行脏数据不该让聊天链路失败。
     *
     * @param rawJson      {@code thinking_mode} 列的原文，可为 null
     * @param budgetTokens {@code thinking_budget_tokens} 列的值，非正视为未设置
     * @param objectMapper JSON 解析器；为 null 时直接回退默认
     */
    public static AnthropicThinkingSetting parse(String rawJson, int budgetTokens,
                                                 ObjectMapper objectMapper) {
        if (rawJson == null || rawJson.isBlank() || objectMapper == null) {
            return new AnthropicThinkingSetting(DEFAULT_TYPE, DEFAULT_MODE, budgetTokens);
        }
        try {
            JsonNode parsed = objectMapper.readTree(rawJson.trim());
            return new AnthropicThinkingSetting(
                    parseType(parsed.path(TYPE_KEY).asText(null)),
                    parseMode(parsed.path(MODE_KEY).asText(null)),
                    budgetTokens);
        } catch (Exception exception) {
            return new AnthropicThinkingSetting(DEFAULT_TYPE, DEFAULT_MODE, budgetTokens);
        }
    }

    /** 认不出的形态名回退默认，而不是让整条配置失效。 */
    private static Type parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TYPE;
        }
        for (Type candidate : Type.values()) {
            if (candidate.name().equalsIgnoreCase(raw.trim())) {
                return candidate;
            }
        }
        return DEFAULT_TYPE;
    }

    /** 认不出的模式名回退默认。历史上不存在的 {@code delete} 也会落到这里。 */
    private static Mode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MODE;
        }
        for (Mode candidate : Mode.values()) {
            if (candidate.name().equalsIgnoreCase(raw.trim())) {
                return candidate;
            }
        }
        return DEFAULT_MODE;
    }

    /**
     * 序列化形态与模式为持久化 JSON。<strong>不含预算</strong> —— 那是另一列。
     *
     * <p>手工拼接而不用 {@code ObjectMapper}：两个字段都是枚举名，不存在需要转义的内容。
     */
    public String serialize() {
        return "{\"" + TYPE_KEY + "\":\"" + type.name().toLowerCase(Locale.ROOT)
                + "\",\"" + MODE_KEY + "\":\"" + mode.name().toLowerCase(Locale.ROOT) + "\"}";
    }

    /**
     * 按当前模式把思考方式写入请求体。
     *
     * <h2>为何用 containsKey 判断「下游已表态」</h2>
     * 与另外两个设置一致：下游显式发 {@code thinking: null} 也算表态，
     * 兜底层不该越权改成 adaptive。那个 null 由调用方链条末尾的 null 清洗移除。
     *
     * <h2>enabled 但没配预算时退化为 adaptive</h2>
     * {@code {"type":"enabled"}} 缺 {@code budget_tokens} 会被上游拒绝，
     * 而 {@code adaptive} 表达的「让上游决定预算」正是用户没填预算时的实际意图。
     * 这不是自动降级 —— 降级是改变用户表达过的意图，而这里用户恰恰<strong>没有</strong>
     * 表达预算，退化到「交给上游」是唯一能出站的解释。
     *
     * <p>相对地，本设置<strong>不</strong>校验预算是否落在 Anthropic 要求的区间
     * （旧形态为 {@code >= 1024} 且 {@code < max_tokens}）：那是用户显式填的数，
     * 越界就让上游用错误码回答，与「不自动降级」的原则一致。
     */
    public void applyTo(Map<String, Object> body) {
        switch (mode) {
            case OVERRIDE -> body.put(REQUEST_FIELD, buildThinking());
            case FALLBACK -> {
                if (!body.containsKey(REQUEST_FIELD)) {
                    body.put(REQUEST_FIELD, buildThinking());
                }
            }
            case PASSTHROUGH -> {
                // 下游带什么就是什么，没带也不补。
            }
        }
    }

    /** 出站的 {@code thinking} 对象。 */
    private Map<String, Object> buildThinking() {
        if (type == Type.ENABLED && hasBudget()) {
            Map<String, Object> thinking = new LinkedHashMap<>();
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", budgetTokens);
            return thinking;
        }
        // adaptive，以及 enabled 但没配预算的退化情形。
        return Map.of("type", Type.ADAPTIVE.name().toLowerCase(Locale.ROOT));
    }
}

package com.kaixuan.copilot_ollama_proxy.application.usage;

/**
 * 从上游 usage 对象解析出的核心 token 指标。
 *
 * <p>三个字段均为可空 {@link Integer}，遵循 null vs 0 语义区分（核心约束）：
 * <ul>
 *   <li>{@code null} —— 上游未提供该数据（字段缺失）；对"缓存命中率"应显示"—/不支持"。</li>
 *   <li>{@code 0} —— 上游报告了该字段但值为零；对"缓存命中率"应显示真实 0%。</li>
 * </ul>
 *
 * <p>解析一律按"字段存在性"进行（见 {@link UsageParser}），绝不使用 {@code asInt(0)}，
 * 否则会把"缺失"与"0"抹平，毁掉上述区分。
 *
 * <p>{@code total_tokens} 不在此列——它是 {@code prompt + completion} 的派生值，
 * 需要时由调用方相加；权威值仍保留在原始 usage JSON 中。
 */
public record UsageTokens(Integer promptTokens, Integer completionTokens, Integer cachedTokens) {

    /** 无任何 usage 数据（上游未返回 usage 或非法）。 */
    public static final UsageTokens EMPTY = new UsageTokens(null, null, null);

    /** 三个字段全为 null 时视为空。 */
    public boolean isEmpty() {
        return promptTokens == null && completionTokens == null && cachedTokens == null;
    }

    /**
     * 输入 token，缺失时按 0 返回。
     *
     * <p>仅用于喂给只接受 int 的既有日聚合收集器，保持其历史行为（缺失记 0）；
     * 落库时应直接使用可空的 {@link #promptTokens()} 以保留 null 语义。
     */
    public int promptOrZero() {
        return promptTokens == null ? 0 : promptTokens;
    }

    /**
     * 输出 token，缺失时按 0 返回。
     *
     * <p>用途同 {@link #promptOrZero()}。
     */
    public int completionOrZero() {
        return completionTokens == null ? 0 : completionTokens;
    }
}

package com.kaixuan.copilot_ollama_proxy.application.runtime;

/**
 * 运行时模型快照。
 * 仅承载路由和展示所需的最小配置，不暴露底层 Map 结构。
 *
 * <p>{@code reasoningEffort}、{@code maxOutputTokens} 与 {@code thinkingMode} 都是
 * <strong>持久化原文</strong>而非解析结果：第一个可能是 V2 JSON
 * （{@code {"reasoning_effort":...,"overwrite_mode":...}}）、旧的裸档位、或遗留的逗号多值；
 * 第二个可能是 V9 JSON（{@code {"max_output_tokens":...,"overwrite_mode":...}}）或迁移前的裸整数。
 * 解析分别交给 {@link ReasoningEffortSetting#parse}、{@link MaxOutputTokensSetting#parse}
 * 与 {@link AnthropicThinkingSetting#parse}，与 {@code headerRulesJson} / {@code bodyRulesJson}
 * 同一风格 —— 本 record 是数据库行的直接快照，解析由各自的消费者负责。
 *
 * <p>{@code thinkingBudgetTokens} 是例外：库里就是裸整数，没有要延后解析的形态。
 */
public record ProviderRuntimeModel(String modelName, int contextSize, boolean capsTools, boolean capsVision,
                                   String reasoningEffort, String maxOutputTokens,
                                   String thinkingMode, int thinkingBudgetTokens) {

    /**
     * 不带最大输出与思考方式配置的便利构造器。
     *
     * <p>两个 JSON 列传 {@code null} 表示「库里没有这一列的值」，各自的
     * {@code parse} 会落到默认值；预算传未设置哨兵。
     * 保留这个重载是为了让只关心路由与能力的调用方（尤其是测试）不必写一串无意义的占位值 ——
     * 与 {@code ProviderRuntimeConfiguration} 的两个便利重载同一动机。
     */
    public ProviderRuntimeModel(String modelName, int contextSize, boolean capsTools, boolean capsVision,
                                String reasoningEffort) {
        this(modelName, contextSize, capsTools, capsVision, reasoningEffort, null,
                null, AnthropicThinkingSetting.UNSET_BUDGET_TOKENS);
    }

    /**
     * 只带最大输出、不带思考方式的便利构造器。
     *
     * <p>保留它是为了让 V10 之前就存在的调用方（尤其是 Anthropic 侧的
     * {@code max_tokens} 测试）不必为新增的两个参数全部重写一遗。
     */
    public ProviderRuntimeModel(String modelName, int contextSize, boolean capsTools, boolean capsVision,
                                String reasoningEffort, String maxOutputTokens) {
        this(modelName, contextSize, capsTools, capsVision, reasoningEffort, maxOutputTokens,
                null, AnthropicThinkingSetting.UNSET_BUDGET_TOKENS);
    }
}
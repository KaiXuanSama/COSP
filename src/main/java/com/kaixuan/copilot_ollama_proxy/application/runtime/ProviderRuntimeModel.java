package com.kaixuan.copilot_ollama_proxy.application.runtime;

/**
 * 运行时模型快照。
 * 仅承载路由和展示所需的最小配置，不暴露底层 Map 结构。
 *
 * <p>{@code reasoningEffort} 与 {@code maxOutputTokens} 都是<strong>持久化原文</strong>
 * 而非解析结果：前者可能是 V2 JSON（{@code {"reasoning_effort":...,"overwrite_mode":...}}）、
 * 旧的裸档位、或遗留的逗号多值；后者可能是 V9 JSON
 * （{@code {"max_output_tokens":...,"overwrite_mode":...}}）或迁移前的裸整数。
 * 解析分别交给 {@link ReasoningEffortSetting#parse} 与 {@link MaxOutputTokensSetting#parse}，
 * 与 {@code headerRulesJson} / {@code bodyRulesJson} 同一风格 —— 本 record 是
 * 数据库行的直接快照，解析由各自的消费者负责。
 */
public record ProviderRuntimeModel(String modelName, int contextSize, boolean capsTools, boolean capsVision,
                                   String reasoningEffort, String maxOutputTokens) {

    /**
     * 不带最大输出配置的便利构造器。
     *
     * <p>{@code maxOutputTokens} 传 {@code null} 表示「库里没有这一列的值」，
     * 消费侧的 {@link MaxOutputTokensSetting#parse} 会落到默认值。
     * 保留这个重载是为了让只关心路由与能力的调用方（尤其是测试）不必写一个无意义的 null ——
     * 与 {@code ProviderRuntimeConfiguration} 的两个便利重载同一动机。
     */
    public ProviderRuntimeModel(String modelName, int contextSize, boolean capsTools, boolean capsVision,
                                String reasoningEffort) {
        this(modelName, contextSize, capsTools, capsVision, reasoningEffort, null);
    }
}
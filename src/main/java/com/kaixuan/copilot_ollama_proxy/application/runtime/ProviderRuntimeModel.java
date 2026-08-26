package com.kaixuan.copilot_ollama_proxy.application.runtime;

/**
 * 运行时模型快照。
 * 仅承载路由和展示所需的最小配置，不暴露底层 Map 结构。
 *
 * <p>{@code reasoningEffort} 是<strong>持久化原文</strong>而非解析结果：
 * 它可能是 V2 JSON（{@code {"reasoning_effort":...,"overwrite_mode":...}}）、
 * 旧的裸档位、或遗留的逗号多值。解析交给 {@link ReasoningEffortSetting#parse}，
 * 与 {@code headerRulesJson} / {@code bodyRulesJson} 同一风格 —— 本 record 是
 * 数据库行的直接快照，解析由各自的消费者负责。
 */
public record ProviderRuntimeModel(String modelName, int contextSize, boolean capsTools, boolean capsVision, String reasoningEffort) {
}
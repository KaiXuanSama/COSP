package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

/**
 * 供应商模型行 — 对应 provider_model 表的一行记录。
 *
 * @param id主键
 * @param providerId     所属供应商 ID
 * @param modelName      模型名称
 * @param enabled        是否启用
 * @param contextSize    上下文窗口大小
 * @param maxOutputTokens 最大输出配置的<strong>持久化原文</strong>，见下
 * @param capsTools      是否支持工具调用
 * @param capsVision     是否支持视觉
 * @param reasoningEffort 思考深度配置的持久化原文
 * @param sortOrder      排序权重
 *
 * <p>{@code maxOutputTokens} 与 {@code reasoningEffort} 都是原文而非解析结果：
 * 前者可能是 V9 JSON（{@code {"max_output_tokens":4000,"overwrite_mode":"fallback"}}）
 * 或升级前遗留的裸整数。本 record 是数据库行的直接快照，解析交给
 * {@code MaxOutputTokensSetting.parse} 与 {@code ReasoningEffortSetting.parse}。
 */
public record ProviderModelRow(int id, int providerId, String modelName, boolean enabled, int contextSize,
                               String maxOutputTokens, boolean capsTools, boolean capsVision,
                               String reasoningEffort, int sortOrder) {
}

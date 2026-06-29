package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

/**
 * 供应商模型行 — 对应 provider_model 表的一行记录。
 *
 * @param id主键
 * @param providerId     所属供应商 ID
 * @param modelName      模型名称
 * @param enabled        是否启用
 * @param contextSize    上下文窗口大小
 * @param capsTools      是否支持工具调用
 * @param capsVision     是否支持视觉
 * @param reasoningEffort 思考深度
 * @param sortOrder      排序权重
 */
public record ProviderModelRow(int id, int providerId, String modelName, boolean enabled, int contextSize,
                               boolean capsTools, boolean capsVision, String reasoningEffort, int sortOrder) {
}

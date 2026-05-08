package com.kaixuan.copilot_ollama_proxy.application.runtime;

/**
 * 运行时模型快照。
 * 仅承载路由和展示所需的最小配置，不暴露底层 Map 结构。
 */
public record ProviderRuntimeModel(String modelName, int contextSize, boolean capsTools, boolean capsVision) {
}
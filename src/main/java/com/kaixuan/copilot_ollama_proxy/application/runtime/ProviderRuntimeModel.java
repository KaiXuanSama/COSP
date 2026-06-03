package com.kaixuan.copilot_ollama_proxy.application.runtime;

/**
 * 运行时模型快照。
 * 仅承载路由和展示所需的最小配置，不暴露底层 Map 结构。
 */
public record ProviderRuntimeModel(String modelName, int contextSize, boolean capsTools, boolean capsVision, String reasoningEffort) {
    /** 返回思考深度列表（逗号分隔解析），默认 Medium */
    public java.util.List<String> reasoningEffortList() {
        if (reasoningEffort == null || reasoningEffort.isBlank()) {
            return java.util.List.of("Medium");
        }
        return java.util.Arrays.stream(reasoningEffort.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
package com.kaixuan.copilot_ollama_proxy.application.runtime;

import java.util.List;

/**
 * 运行时 Provider 配置快照。
 */
public record ProviderRuntimeConfiguration(String providerKey, String baseUrl, String apiKey, String apiFormat, List<ProviderRuntimeModel> models) {

    public ProviderRuntimeConfiguration {
        providerKey = providerKey == null ? "" : providerKey;
        baseUrl = baseUrl == null ? "" : baseUrl;
        apiKey = apiKey == null ? "" : apiKey;
        apiFormat = (apiFormat == null || apiFormat.isBlank()) ? "openai" : apiFormat;
        models = models == null ? List.of() : List.copyOf(models);
    }

    public boolean supportsModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        return models.stream().anyMatch(model -> modelName.equals(model.modelName()));
    }
}
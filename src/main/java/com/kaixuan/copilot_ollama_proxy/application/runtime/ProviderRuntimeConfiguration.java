package com.kaixuan.copilot_ollama_proxy.application.runtime;

import java.util.List;

/**
 * 运行时 Provider 配置快照。
 *
 * 请求头规则从 provider_request_transform 读取；customTransforms 在迁移期仅保存旧请求体转换配置。
 */
public record ProviderRuntimeConfiguration(String providerKey, String baseUrl, String apiKey, String apiFormat,
                                           List<ProviderRuntimeModel> models, String customTransforms,
                                           String headerRulesJson) {

    /**
     * 创建无自定义转换的运行时供应商配置。
     *
     * @param providerKey 供应商标识
     * @param baseUrl API 基础地址
     * @param apiKey 激活 API Key
     * @param apiFormat API 协议格式
     * @param models 供应商模型
     */
    public ProviderRuntimeConfiguration(String providerKey, String baseUrl, String apiKey, String apiFormat, List<ProviderRuntimeModel> models) {
        this(providerKey, baseUrl, apiKey, apiFormat, models, "{}", "[]");
    }

    /**
     * 创建使用旧请求体转换配置的运行时供应商配置。
     *
     * @param providerKey 供应商标识
     * @param baseUrl API 基础地址
     * @param apiKey 激活 API Key
     * @param apiFormat API 协议格式
     * @param models 供应商模型
     * @param customTransforms 旧请求体转换配置
     */
    public ProviderRuntimeConfiguration(String providerKey, String baseUrl, String apiKey, String apiFormat,
                                        List<ProviderRuntimeModel> models, String customTransforms) {
        this(providerKey, baseUrl, apiKey, apiFormat, models, customTransforms, "[]");
    }

    public ProviderRuntimeConfiguration {
        providerKey = providerKey == null ? "" : providerKey;
        baseUrl = baseUrl == null ? "" : baseUrl;
        apiKey = apiKey == null ? "" : apiKey;
        apiFormat = (apiFormat == null || apiFormat.isBlank()) ? "openai" : apiFormat;
        models = models == null ? List.of() : List.copyOf(models);
        customTransforms = (customTransforms == null || customTransforms.isBlank()) ? "{}" : customTransforms;
        headerRulesJson = (headerRulesJson == null || headerRulesJson.isBlank()) ? "[]" : headerRulesJson;
    }

    public boolean supportsModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        return models.stream().anyMatch(model -> modelName.equals(model.modelName()));
    }
}
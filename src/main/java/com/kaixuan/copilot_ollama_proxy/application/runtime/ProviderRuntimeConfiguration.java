package com.kaixuan.copilot_ollama_proxy.application.runtime;

import java.util.List;

/**
 * 运行时 Provider 配置快照。
 *
 * 请求头和请求体规则均从 provider_request_transform 读取。
 */
public record ProviderRuntimeConfiguration(String providerKey, String baseUrl, String apiKey,
                                           List<ProviderRuntimeModel> models, String headerRulesJson,
                                           String bodyRulesJson) {

    /** 空规则集（V2）。 */
    private static final String EMPTY_BODY_RULES_JSON = "{\"version\":2,\"groups\":[]}";

    /**
     * 创建无自定义转换的运行时供应商配置。
     *
     * @param providerKey 供应商标识
     * @param baseUrl API 基础地址
     * @param apiKey 激活 API Key
     * @param models 供应商模型
     */
    public ProviderRuntimeConfiguration(String providerKey, String baseUrl, String apiKey,
                                        List<ProviderRuntimeModel> models) {
        this(providerKey, baseUrl, apiKey, models, "[]", EMPTY_BODY_RULES_JSON);
    }

    public ProviderRuntimeConfiguration {
        providerKey = providerKey == null ? "" : providerKey;
        baseUrl = baseUrl == null ? "" : baseUrl;
        apiKey = apiKey == null ? "" : apiKey;
        models = models == null ? List.of() : List.copyOf(models);
        headerRulesJson = (headerRulesJson == null || headerRulesJson.isBlank()) ? "[]" : headerRulesJson;
        bodyRulesJson = (bodyRulesJson == null || bodyRulesJson.isBlank())
            ? EMPTY_BODY_RULES_JSON : bodyRulesJson;
    }

    public boolean supportsModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        return models.stream().anyMatch(model -> modelName.equals(model.modelName()));
    }
}
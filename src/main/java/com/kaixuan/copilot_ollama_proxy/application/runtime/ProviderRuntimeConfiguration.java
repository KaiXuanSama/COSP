package com.kaixuan.copilot_ollama_proxy.application.runtime;

import java.util.List;

/**
 * 运行时 Provider 配置快照。
 *
 * 请求头和请求体规则均从 provider_request_transform 读取。
 *
 * <h2>为何协议集合是 JSON 原文而不是 {@code Set<WireProtocol>}</h2>
 * 与 {@code headerRulesJson} / {@code bodyRulesJson} 同一风格：本 record 是数据库行的
 * 直接快照，解析交给各自的消费者（协议集合由 {@code ProviderProtocolSupport} 解析，
 * 正如规则集由规则引擎解析）。更实际的原因是包依赖 —— {@code WireProtocol} 在
 * {@code application.protocol}，而那个包已经依赖本包；若本 record 反过来引用枚举，
 * 两个包就互相依赖了。
 */
public record ProviderRuntimeConfiguration(String providerKey, String baseUrl, String apiKey,
                                           List<ProviderRuntimeModel> models, String headerRulesJson,
                                           String bodyRulesJson, String supportedProtocolsJson,
                                           String anthropicBaseUrl) {

    /** 空规则集（V2）。 */
    private static final String EMPTY_BODY_RULES_JSON = "{\"version\":2,\"groups\":[]}";

    /**
     * 协议集合缺失时的回退值：两种协议都支持。
     *
     * <p>只在<strong>字段为空</strong>时使用，与显式的空数组 {@code []} 是两回事：
     * 后者表示用户声明「一种协议都不支持」，调度器会明确报错，不该被悄悄补成全集 ——
     * 那会让「配置为空集」这一非法状态永远无法被发现。
     */
    public static final String DEFAULT_SUPPORTED_PROTOCOLS_JSON = "[\"OPENAI\",\"ANTHROPIC\"]";

    /**
     * 创建无自定义转换的运行时供应商配置。
     *
     * <p>协议集合与 Anthropic 端点取默认值：两种协议都支持、端点回退到 {@code baseUrl}，
     * 与协议支持落库之前的行为完全一致。
     *
     * @param providerKey 供应商标识
     * @param baseUrl API 基础地址
     * @param apiKey 激活 API Key
     * @param models 供应商模型
     */
    public ProviderRuntimeConfiguration(String providerKey, String baseUrl, String apiKey,
                                        List<ProviderRuntimeModel> models) {
        this(providerKey, baseUrl, apiKey, models, "[]", EMPTY_BODY_RULES_JSON,
                DEFAULT_SUPPORTED_PROTOCOLS_JSON, "");
    }

    /**
     * 创建带请求转换规则、但协议配置取默认值的运行时供应商配置。
     *
     * <p>保留这个六参重载是为了让只关心规则转换的调用方不必写出协议维度。
     */
    public ProviderRuntimeConfiguration(String providerKey, String baseUrl, String apiKey,
                                        List<ProviderRuntimeModel> models, String headerRulesJson,
                                        String bodyRulesJson) {
        this(providerKey, baseUrl, apiKey, models, headerRulesJson, bodyRulesJson,
                DEFAULT_SUPPORTED_PROTOCOLS_JSON, "");
    }

    public ProviderRuntimeConfiguration {
        providerKey = providerKey == null ? "" : providerKey;
        baseUrl = baseUrl == null ? "" : baseUrl;
        apiKey = apiKey == null ? "" : apiKey;
        models = models == null ? List.of() : List.copyOf(models);
        headerRulesJson = (headerRulesJson == null || headerRulesJson.isBlank()) ? "[]" : headerRulesJson;
        bodyRulesJson = (bodyRulesJson == null || bodyRulesJson.isBlank())
            ? EMPTY_BODY_RULES_JSON : bodyRulesJson;
        supportedProtocolsJson = (supportedProtocolsJson == null || supportedProtocolsJson.isBlank())
            ? DEFAULT_SUPPORTED_PROTOCOLS_JSON : supportedProtocolsJson;
        anthropicBaseUrl = anthropicBaseUrl == null ? "" : anthropicBaseUrl;
    }

    /**
     * 解析出 Anthropic 线路实际使用的基础地址。
     *
     * <p>未单独配置时回退到 {@link #baseUrl()} —— 这既是 V8.8 之前的行为，也覆盖
     * 「该供应商两个协议共用同一个网关地址」这一常见情形。
     */
    public String resolveAnthropicBaseUrl() {
        return anthropicBaseUrl.isBlank() ? baseUrl : anthropicBaseUrl;
    }

    public boolean supportsModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        return models.stream().anyMatch(model -> modelName.equals(model.modelName()));
    }
}
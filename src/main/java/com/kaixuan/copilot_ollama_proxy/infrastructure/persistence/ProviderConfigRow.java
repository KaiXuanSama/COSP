package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import java.util.List;

/**
 * 服务商配置行 — 对应 provider_config 表的一行记录。
 *
 * API Key 和请求转换配置分别由 provider_api_key、provider_request_transform 表管理。
 *
 * @param id                  主键
 * @param providerKey         供应商唯一标识
 * @param displayName         前端完整显示名
 * @param enabled             是否启用
 * @param baseUrl             OpenAI 协议的 API 基础地址
 * @param supportedProtocolsJson 支持的线路协议集合（JSON 字符串数组原文）
 * @param anthropicBaseUrl    Anthropic 协议的独立基础地址，空串表示回退到 {@code baseUrl}
 * @param responsesBaseUrl    OpenAI Responses 协议的独立基础地址，空串表示回退到 {@code baseUrl}
 * @param useProxy            该供应商的出站请求是否经由 HTTP 代理（对应 use_proxy 列）
 * @param authHeaderJson      出站鉴权头装配方式的 JSON 原文（对应 auth_header 列）
 * @param updatedAt           更新时间
 * @param models              关联的模型列表
 */
public record ProviderConfigRow(int id, String providerKey, String displayName, boolean enabled, String baseUrl,
                                String supportedProtocolsJson, String anthropicBaseUrl, String responsesBaseUrl,
                                boolean useProxy, String authHeaderJson, String updatedAt,
                                List<ProviderModelRow> models) {

    /**
     * 不带出站鉴权头装配方式的便捷构造器。
     *
     * <p>传入空串而非 {@link com.kaixuan.copilot_ollama_proxy.application.runtime.AuthHeaderSetting}
     * 的默认值：本 record 是数据库行的<strong>原文快照</strong>，不替消费者决定「空代表什么」——
     * 与 {@code maxOutputTokens} 的 null 原样传递、{@code thinking_budget_tokens} 保留未设置哨兵
     * 是同一条口径（见 {@code DatabaseRuntimeProviderCatalog#toModel} 的说明）。
     * 解析与兜底分别由 {@code AuthHeaderSetting.parse}（运行时）与
     * {@code ProviderAdminService#buildProviderView}（回传前端）负责。
     *
     * <p>存在的意义是让已有的一批夹具（它们只关心协议集合、端点或代理开关）不必为一个
     * 正交维度补参数污染 diff。
     */
    public ProviderConfigRow(int id, String providerKey, String displayName, boolean enabled, String baseUrl,
                             String supportedProtocolsJson, String anthropicBaseUrl, String responsesBaseUrl,
                             boolean useProxy, String updatedAt, List<ProviderModelRow> models) {
        this(id, providerKey, displayName, enabled, baseUrl, supportedProtocolsJson, anthropicBaseUrl,
                responsesBaseUrl, useProxy, "", updatedAt, models);
    }
}

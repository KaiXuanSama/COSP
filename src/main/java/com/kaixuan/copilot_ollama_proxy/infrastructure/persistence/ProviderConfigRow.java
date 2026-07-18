package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import java.util.List;

/**
 * 服务商配置行 — 对应 provider_config 表的一行记录。
 *
 * API Key 和请求转换配置分别由 provider_api_key、provider_request_transform 表管理。
 *
 * @param id                主键
 * @param providerKey       供应商唯一标识
 * @param displayName       前端完整显示名
 * @param enabled           是否启用
 * @param baseUrl           API 基础地址
 * @param apiFormat         协议格式（如 "openai"）
 * @param updatedAt         更新时间
 * @param models            关联的模型列表
 */
public record ProviderConfigRow(int id, String providerKey, String displayName, boolean enabled, String baseUrl,
                                String apiFormat, String updatedAt,
                                List<ProviderModelRow> models) {
}

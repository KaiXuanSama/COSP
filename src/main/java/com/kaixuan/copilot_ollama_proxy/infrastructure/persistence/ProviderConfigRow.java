package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import java.util.List;

/**
 * 服务商配置行 — 对应 provider_config 表的一行记录。
 *
 * @param id              主键
 * @param providerKey     供应商唯一标识
 * @param enabled是否启用
 * @param baseUrl         API 基础地址
 * @param apiKey          API 密钥
 * @param apiFormat       协议格式（如 "openai"）
 * @param customTransforms 自定义转换配置 JSON
 * @param updatedAt       更新时间
 * @param models          关联的模型列表
 */
public record ProviderConfigRow(int id, String providerKey, boolean enabled, String baseUrl, String apiKey,
                                String apiFormat, String customTransforms, String updatedAt,
                                List<ProviderModelRow> models) {
}

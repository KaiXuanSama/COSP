package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

/**
 * 供应商请求转换配置行，对应 provider_request_transform 表。
 *
 * @param providerId 供应商主键
 * @param headerRulesVersion 请求头规则版本
 * @param headerRulesJson 请求头规则 JSON 数组
 * @param bodyTemplateKeysJson 请求体编辑器模板键 JSON 数组（legacy，已下沉进首个规则组）
 * @param bodyPreviewJson 请求体编辑器预览 JSON 对象（legacy，同上）
 * @param bodyRulesVersion 请求体规则版本
 * @param bodyRulesJson 请求体规则集 JSON，V8.7 起为 V2 规则组格式
 * @param bodyRulesSchema 规则集结构版本，V8.7 迁移的结构性标记
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record ProviderRequestTransformRow(
        int providerId,
        int headerRulesVersion,
        String headerRulesJson,
        String bodyTemplateKeysJson,
        String bodyPreviewJson,
        int bodyRulesVersion,
        String bodyRulesJson,
        int bodyRulesSchema,
        String createdAt,
        String updatedAt) {
}

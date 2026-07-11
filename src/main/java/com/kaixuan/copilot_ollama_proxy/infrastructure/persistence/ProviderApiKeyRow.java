package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

/**
 * 服务商 API Key 行 — 对应 provider_api_key 表的一行记录。
 *
 * encryptedApiKey 与 nonce 均为 Base64 字符串，明文不落库；
 * 需要明文时通过 ApiKeyCryptoService 解密。
 *
 * @param id                主键
 * @param keyUuid           不可变 UUID
 * @param providerId        所属供应商 ID
 * @param keyName           Key 名称（明文）
 * @param encryptedApiKey   AES-256-GCM 密文（Base64）
 * @param nonce             加密 nonce（Base64）
 * @param encryptionVersion 加密格式版本
 * @param active            是否为当前激活 Key
 * @param sortOrder         展示顺序
 */
public record ProviderApiKeyRow(long id, String keyUuid, int providerId, String keyName,
                                String encryptedApiKey, String nonce, int encryptionVersion,
                                boolean active, int sortOrder) {
}

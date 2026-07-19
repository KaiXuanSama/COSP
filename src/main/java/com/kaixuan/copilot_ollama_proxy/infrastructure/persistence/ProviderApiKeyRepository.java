package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.kaixuan.copilot_ollama_proxy.infrastructure.security.ApiKeyCryptoService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务商 API Key 数据访问层 — 操作 provider_api_key 表。
 *
 * 写入时通过 {@link ApiKeyCryptoService} 加密明文 Key，读取原始行时保留密文，
 * 需要明文的调用方（如运行时目录）再单独解密。管理后台不需要明文，
 * 因此保存采用按 UUID 合并的语义，未修改的 Key 直接沿用原密文。
 */
@Repository
public class ProviderApiKeyRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ApiKeyCryptoService cryptoService;

    public ProviderApiKeyRepository(JdbcTemplate jdbcTemplate, ApiKeyCryptoService cryptoService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cryptoService = cryptoService;
    }

    /**
     * 查询某个供应商的全部 API Key 行，按展示顺序排列。
     *
     * @param providerId 供应商 ID
     * @return API Key 行列表，可能为空
     */
    public List<ProviderApiKeyRow> findByProviderId(int providerId) {
        return jdbcTemplate.query(
                "SELECT id, key_uuid, provider_id, key_name, encrypted_api_key, nonce, encryption_version, is_active, sort_order "
                        + "FROM provider_api_key WHERE provider_id = ? ORDER BY sort_order, id",
                (rs, rowNum) -> mapRow(rs), providerId);
    }

    /**
     * 查询某个供应商当前激活的 API Key 行。
     *
     * @param providerId 供应商 ID
     * @return 激活的 API Key 行，不存在时返回 null
     */
    public ProviderApiKeyRow findActiveByProviderId(int providerId) {
        return jdbcTemplate.query(
                "SELECT id, key_uuid, provider_id, key_name, encrypted_api_key, nonce, encryption_version, is_active, sort_order "
                        + "FROM provider_api_key WHERE provider_id = ? AND is_active = 1 LIMIT 1",
                rs -> rs.next() ? mapRow(rs) : null, providerId);
    }

    /**
     * 解密激活 API Key 并返回明文，供运行时调用上游使用。
     *
     * @param providerId 供应商 ID
     * @return 明文 API Key，无激活 Key 时返回空串
     */
    public String resolveActiveApiKey(int providerId) {
        ProviderApiKeyRow active = findActiveByProviderId(providerId);
        if (active == null) {
            return "";
        }
        return cryptoService.decrypt(active.nonce(), active.encryptedApiKey());
    }

    /**
     * 解密单条 API Key 行，返回明文。
     *
     * @param row API Key 行
     * @return 明文 API Key
     */
    public String decrypt(ProviderApiKeyRow row) {
        return cryptoService.decrypt(row.nonce(), row.encryptedApiKey());
    }

    /**
     * 按 UUID 合并保存某个供应商的 API Key 列表。
     *
     * 合并语义：
     * 保留并更新携带已有 UUID 的项，删除本次未提交的旧项，插入没有 UUID 的新项。
     * 明文为 null 表示未修改，沿用原有密文；否则重新加密。
     *
     * @param providerId 供应商 ID
     * @param inputs 前端提交的 API Key 列表
     */
    @Transactional
    public void saveKeys(int providerId, List<ApiKeyInput> inputs) {
        List<ProviderApiKeyRow> existing = findByProviderId(providerId);
        List<String> keptUuids = new ArrayList<>();

        // provider_id + is_active=1 是部分唯一索引。切换激活项时必须先撤销旧项，
        // 否则表单顺序先写入新激活项会短暂产生两个激活 Key 并触发约束异常。
        jdbcTemplate.update("UPDATE provider_api_key SET is_active = 0 WHERE provider_id = ? AND is_active = 1",
            providerId);

        int activeCount = 0;
        for (ApiKeyInput input : inputs) {
            if (input.active()) {
                activeCount++;
            }
        }

        for (int i = 0; i < inputs.size(); i++) {
            ApiKeyInput input = inputs.get(i);
            boolean active = input.active();
            // 若存在多个激活项，只保留第一个，避免违反部分唯一索引
            if (active && activeCount > 1) {
                active = keptUuids.isEmpty() && i == firstActiveIndex(inputs);
            }
            String uuid = input.keyUuid();
            if (uuid != null && !uuid.isBlank()) {
                ProviderApiKeyRow current = findByUuid(existing, uuid);
                if (current == null) {
                    // UUID 无效，作为新增处理
                    insertKey(providerId, UUID.randomUUID().toString(), input.keyName(),
                            requirePlaintext(input), active, i);
                } else if (input.plaintext() != null) {
                    updateKeyWithNewPlaintext(current.id(), input.keyName(), input.plaintext(), active, i);
                } else {
                    updateKeyMetadata(current.id(), input.keyName(), active, i);
                }
                keptUuids.add(uuid);
            } else {
                insertKey(providerId, UUID.randomUUID().toString(), input.keyName(),
                        requirePlaintext(input), active, i);
            }
        }

        // 删除本次未保留的旧 Key
        for (ProviderApiKeyRow row : existing) {
            if (!keptUuids.contains(row.keyUuid())) {
                jdbcTemplate.update("DELETE FROM provider_api_key WHERE id = ?", row.id());
            }
        }
    }

    private int firstActiveIndex(List<ApiKeyInput> inputs) {
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).active()) {
                return i;
            }
        }
        return -1;
    }

    private String requirePlaintext(ApiKeyInput input) {
        if (input.plaintext() == null) {
            throw new IllegalArgumentException("新增 API Key 缺少明文内容");
        }
        return input.plaintext();
    }

    private ProviderApiKeyRow findByUuid(List<ProviderApiKeyRow> rows, String uuid) {
        return rows.stream().filter(r -> uuid.equals(r.keyUuid())).findFirst().orElse(null);
    }

    private void insertKey(int providerId, String uuid, String keyName, String plaintext,
                           boolean active, int sortOrder) {
        ApiKeyCryptoService.EncryptedValue encrypted = cryptoService.encrypt(plaintext);
        jdbcTemplate.update(
                "INSERT INTO provider_api_key "
                        + "(key_uuid, provider_id, key_name, encrypted_api_key, nonce, encryption_version, is_active, sort_order) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                uuid, providerId, keyName, encrypted.ciphertextBase64(), encrypted.nonceBase64(),
                ApiKeyCryptoService.ENCRYPTION_VERSION, active ? 1 : 0, sortOrder);
    }

    private void updateKeyWithNewPlaintext(long id, String keyName, String plaintext,
                                           boolean active, int sortOrder) {
        ApiKeyCryptoService.EncryptedValue encrypted = cryptoService.encrypt(plaintext);
        jdbcTemplate.update(
                "UPDATE provider_api_key SET key_name = ?, encrypted_api_key = ?, nonce = ?, "
                        + "encryption_version = ?, is_active = ?, sort_order = ?, "
                        + "updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE id = ?",
                keyName, encrypted.ciphertextBase64(), encrypted.nonceBase64(),
                ApiKeyCryptoService.ENCRYPTION_VERSION, active ? 1 : 0, sortOrder, id);
    }

    private void updateKeyMetadata(long id, String keyName, boolean active, int sortOrder) {
        jdbcTemplate.update(
                "UPDATE provider_api_key SET key_name = ?, is_active = ?, sort_order = ?, "
                        + "updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE id = ?",
                keyName, active ? 1 : 0, sortOrder, id);
    }

    private ProviderApiKeyRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProviderApiKeyRow(
                rs.getLong("id"),
                rs.getString("key_uuid"),
                rs.getInt("provider_id"),
                rs.getString("key_name"),
                rs.getString("encrypted_api_key"),
                rs.getString("nonce"),
                rs.getInt("encryption_version"),
                rs.getInt("is_active") == 1,
                rs.getInt("sort_order"));
    }

    /**
     * API Key 保存输入 — 管理后台提交的单条 Key。
     *
     * plaintext 为 null 表示 Key 未修改，沿用数据库中已有密文；否则重新加密。
     *
     * @param keyUuid   已有 Key 的 UUID，新增时为 null 或空串
     * @param keyName   Key 名称（明文）
     * @param plaintext 新明文，未修改时为 null
     * @param active    是否为激活 Key
     */
    public record ApiKeyInput(String keyUuid, String keyName, String plaintext, boolean active) {
    }
}

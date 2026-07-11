package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务商配置数据访问层 — 操作 provider_config 与 provider_model 表。
 */
@Repository
public class ProviderConfigRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ProviderApiKeyRepository providerApiKeyRepository;

    public ProviderConfigRepository(JdbcTemplate jdbcTemplate, ProviderApiKeyRepository providerApiKeyRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.providerApiKeyRepository = providerApiKeyRepository;
    }

    // ==================== 查询 ====================

    /**
     * 查询所有服务商配置（含模型列表）。
     */
    public List<ProviderConfigRow> findAllWithModels() {
        return loadProvidersWithModels(null, false, false);
    }

    /**
     * 根据 provider_key 查询单个服务商配置。
     */
    public ProviderConfigRow findByKey(String providerKey) {
        List<ProviderConfigRow> providers = loadProvidersWithModels(providerKey, false, false);
        return providers.isEmpty() ? null : providers.get(0);
    }

    /**
     * 查询某个服务商的模型列表。
     */
    public List<ProviderModelRow> findModelsByProviderId(int providerId) {
        return jdbcTemplate.query(
                "SELECT id, provider_id, model_name, enabled, context_size, max_output_tokens, caps_tools, caps_vision, reasoning_effort, sort_order " + "FROM provider_model WHERE provider_id = ? ORDER BY sort_order, id",
                (rs, rowNum) -> new ProviderModelRow(
                        rs.getInt("id"),
                        rs.getInt("provider_id"),
                        rs.getString("model_name"),
                        rs.getInt("enabled") == 1,
                        rs.getInt("context_size"),
                        rs.getInt("max_output_tokens"),
                        rs.getInt("caps_tools") == 1,
                        rs.getInt("caps_vision") == 1,
                        rs.getString("reasoning_effort"),
                        rs.getInt("sort_order")
                ), providerId);
    }

    // ==================== 写入 ====================

    /**
     * 保存服务商配置（UPSERT），不涉及 API Key（已迁移到 provider_api_key 表）。
     * 如果 provider_key 已存在则更新，否则插入。
     *
     * @return 对应的 provider_config.id
     */
    public int saveProvider(String providerKey, boolean enabled, String baseUrl, String apiFormat, String customTransforms) {
        jdbcTemplate.update(
            "INSERT INTO provider_config (provider_key, enabled, base_url, api_format, custom_transforms) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT(provider_key) DO UPDATE SET "
                + "enabled = excluded.enabled, base_url = excluded.base_url, "
                + "api_format = excluded.api_format, custom_transforms = excluded.custom_transforms, "
                + "updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')",
            providerKey, enabled ? 1 : 0, baseUrl, apiFormat, customTransforms);
        return jdbcTemplate.queryForObject("SELECT id FROM provider_config WHERE provider_key = ?", Integer.class, providerKey);
    }

    /**
     * 仅更新服务商的 base_url 与 api_format，不修改 enabled 状态，也不涉及 API Key。
     * 如果指定的 providerKey 不存在，则自动插入一条新记录（enabled = 0）。
     * @return 对应的 provider_config.id
     */
    public int updateProviderConfig(String providerKey, String baseUrl, String apiFormat) {
        jdbcTemplate.update(
            "INSERT INTO provider_config (provider_key, enabled, base_url, api_format) "
                + "VALUES (?, 0, ?, ?) ON CONFLICT(provider_key) DO UPDATE SET "
                + "base_url = excluded.base_url, api_format = excluded.api_format, "
                + "updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')",
            providerKey, baseUrl, apiFormat);
        return jdbcTemplate.queryForObject("SELECT id FROM provider_config WHERE provider_key = ?", Integer.class, providerKey);
    }

    /**
     * 在同一事务内保存供应商配置、API Key 列表和完整模型列表。
     *
     * 任意一步失败时，配置更新、API Key 变更、旧模型删除和新模型插入会整体回滚，
     * 避免只保存了一部分。API Key 通过 {@link ProviderApiKeyRepository} 加密后按 UUID 合并。
     *
     * @param providerKey 服务商标识
     * @param baseUrl API 基础地址
     * @param apiKeys API Key 输入列表（明文按需加密，未修改项沿用原密文）
     * @param apiFormat API 协议格式
     * @param models 完整模型列表
     * @return 对应的 provider_config.id
     */
    @Transactional
    public int saveProviderConfigWithModels(String providerKey, String baseUrl,
                                            List<ProviderApiKeyRepository.ApiKeyInput> apiKeys, String apiFormat,
                                            List<Map<String, Object>> models) {
        int providerId = updateProviderConfig(providerKey, baseUrl, apiFormat);
        providerApiKeyRepository.saveKeys(providerId, apiKeys);
        saveModels(providerId, models);
        return providerId;
    }

    /**
     * 保存模型列表（先删除旧列表，再批量插入）。
     */
    @Transactional
    public void saveModels(int providerId, List<Map<String, Object>> models) {
        // 删除该服务商下所有旧模型
        jdbcTemplate.update("DELETE FROM provider_model WHERE provider_id = ?", providerId);
        // 批量插入新模型
        if (models == null || models.isEmpty())
            return;
        for (int i = 0; i < models.size(); i++) {
            Map<String, Object> m = models.get(i);
            String modelName = (String) m.getOrDefault("modelName", "");
            boolean modelEnabled = Boolean.TRUE.equals(m.get("enabled"));
            int contextSize = parseInt(m.get("contextSize"), 0);
            int maxOutputTokens = parseInt(m.get("maxOutputTokens"), 128000);
            boolean capsTools = Boolean.TRUE.equals(m.get("capsTools"));
            boolean capsVision = Boolean.TRUE.equals(m.get("capsVision"));
            String reasoningEffort = (String) m.getOrDefault("reasoningEffort", "Medium");
            jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name, enabled, context_size, max_output_tokens, caps_tools, caps_vision, reasoning_effort, sort_order) " + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    providerId, modelName, modelEnabled ? 1 : 0, contextSize, maxOutputTokens, capsTools ? 1 : 0, capsVision ? 1 : 0, reasoningEffort, i);
        }
    }

    // ==================== 运行时读取（供 OllamaService 使用） ====================

    /**
     * 查询所有已启用的服务商配置（含已启用的模型列表）。
     * 只返回 enabled=1 的服务商，且只返回每个服务商下 enabled=1 的模型。
     * 如果没有已启用的模型，则不包含该服务商。
     */
    public List<ProviderConfigRow> findAllActiveProvidersWithEnabledModels() {
        return loadProvidersWithModels(null, true, true);
    }

    /**
     * 根据 provider_key 查询单个服务商配置（运行时使用）。
     * 如果服务商不存在或未启用，返回 null。
     */
    public ProviderConfigRow findActiveProviderByKey(String providerKey) {
        List<ProviderConfigRow> providers = loadProvidersWithModels(providerKey, true, true);
        return providers.isEmpty() ? null : providers.get(0);
    }

    // ==================== 工具 ====================

    /**
     * 更新自定义供应商的 custom_transforms 字段。
     */
    public void updateCustomTransforms(String providerKey, String customTransforms) {
        jdbcTemplate.update(
                "UPDATE provider_config SET custom_transforms = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE provider_key = ?",
                customTransforms, providerKey);
    }

    /**
     * 更新供应商的 provider_key、custom_transforms 和 base_url（用于编辑自定义供应商）。
     * 直接修改 provider_key，保留关联的模型配置不变。
     */
    public void updateProviderKeyAndTransforms(String oldProviderKey, String newProviderKey, String customTransforms, String baseUrl) {
        jdbcTemplate.update(
                "UPDATE provider_config SET provider_key = ?, custom_transforms = ?, base_url = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE provider_key = ?",
                newProviderKey, customTransforms, baseUrl, oldProviderKey);
    }

    /**
     * 根据 provider_key 删除服务商配置及其关联的模型。
        * 当前连接已启用 SQLite 外键约束和级联删除；仍先手动删除模型，
        * 以兼容从旧版本升级且外键状态未知的数据库连接。
     */
    @Transactional
    public void deleteByKey(String providerKey) {
        Integer providerId = jdbcTemplate.query("SELECT id FROM provider_config WHERE provider_key = ?", rs -> {
            return rs.next() ? rs.getInt("id") : null;
        }, providerKey);
        if (providerId != null) {
            jdbcTemplate.update("DELETE FROM provider_model WHERE provider_id = ?", providerId);
        }
        jdbcTemplate.update("DELETE FROM provider_config WHERE provider_key = ?", providerKey);
    }

    private List<ProviderConfigRow> loadProvidersWithModels(String providerKey, boolean activeOnly, boolean enabledModelsOnly) {
        StringBuilder sql = new StringBuilder("SELECT pc.id, pc.provider_key, pc.enabled, pc.base_url, pc.api_format, pc.custom_transforms, pc.updated_at,")
                .append(" pm.id AS model_id, pm.provider_id AS model_provider_id, pm.model_name, pm.enabled AS model_enabled,")
                .append(" pm.context_size, pm.max_output_tokens, pm.caps_tools, pm.caps_vision, pm.reasoning_effort, pm.sort_order")
                .append(" FROM provider_config pc")
                .append(" LEFT JOIN provider_model pm ON pm.provider_id = pc.id")
                .append(activeOnly ? " WHERE pc.enabled = 1" : "");
        if (providerKey != null) {
            if (activeOnly) {
                sql.append(" AND pc.provider_key = ?");
            } else {
                sql.append(" WHERE pc.provider_key = ?");
            }
        }
        sql.append(" ORDER BY pc.id, pm.sort_order, pm.id");

        List<Map<String, Object>> rows;
        if (providerKey != null) {
            rows = jdbcTemplate.queryForList(sql.toString(), providerKey);
        } else {
            rows = jdbcTemplate.queryForList(sql.toString());
        }

        Map<Integer, MutableProviderConfig> providers = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            int providerId = ((Number) row.get("id")).intValue();
            MutableProviderConfig provider = providers.computeIfAbsent(providerId, id -> new MutableProviderConfig(
                    id,
                    (String) row.get("provider_key"),
                    ((Number) row.get("enabled")).intValue() == 1,
                    (String) row.get("base_url"),
                    (String) row.get("api_format"),
                    (String) row.get("custom_transforms"),
                    (String) row.get("updated_at")
            ));

            Object modelId = row.get("model_id");
            if (modelId == null) {
                continue;
            }
            boolean modelEnabled = ((Number) row.get("model_enabled")).intValue() == 1;
            if (enabledModelsOnly && !modelEnabled) {
                continue;
            }
            provider.models.add(new ProviderModelRow(
                    ((Number) row.get("model_id")).intValue(),
                    ((Number) row.get("model_provider_id")).intValue(),
                    (String) row.get("model_name"),
                    modelEnabled,
                    ((Number) row.get("context_size")).intValue(),
                    ((Number) row.get("max_output_tokens")).intValue(),
                    ((Number) row.get("caps_tools")).intValue() == 1,
                    ((Number) row.get("caps_vision")).intValue() == 1,
                    (String) row.get("reasoning_effort"),
                    ((Number) row.get("sort_order")).intValue()
            ));
        }

        List<ProviderConfigRow> result = new ArrayList<>();
        for (MutableProviderConfig provider : providers.values()) {
            if (enabledModelsOnly && provider.models.isEmpty()) {
                continue;
            }
            result.add(new ProviderConfigRow(
                    provider.id,
                    provider.providerKey,
                    provider.enabled,
                    provider.baseUrl,
                    provider.apiFormat,
                    provider.customTransforms,
                    provider.updatedAt,
                    provider.models
            ));
        }
        return result;
    }

    private static int parseInt(Object value, int defaultValue) {
        if (value == null)
            return defaultValue;
        if (value instanceof Number)
            return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static class MutableProviderConfig {
        private final int id;
        private final String providerKey;
        private final boolean enabled;
        private final String baseUrl;
        private final String apiFormat;
        private final String customTransforms;
        private final String updatedAt;
        private final List<ProviderModelRow> models = new ArrayList<>();

        private MutableProviderConfig(int id, String providerKey, boolean enabled, String baseUrl,
                                      String apiFormat, String customTransforms, String updatedAt) {
            this.id = id;
            this.providerKey = providerKey;
            this.enabled = enabled;
            this.baseUrl = baseUrl;
            this.apiFormat = apiFormat;
            this.customTransforms = customTransforms;
            this.updatedAt = updatedAt;
        }
    }
}

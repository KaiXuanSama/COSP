package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 服务商配置数据访问层 — 操作 provider_config 与 provider_model 表。
 */
@Repository
public class ProviderConfigRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProviderConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 查询 ====================

    /**
     * 查询所有服务商配置（含模型列表）。
     * 返回 Map 列表，每项包含 provider 的所有字段 + models 子列表。
     */
    public List<Map<String, Object>> findAllWithModels() {
        List<Map<String, Object>> providers = jdbcTemplate
                .query("SELECT id, provider_key, enabled, base_url, api_key, api_format, updated_at "
                        + "FROM provider_config ORDER BY id", (rs, rowNum) -> {
                            Map<String, Object> row = new java.util.LinkedHashMap<>();
                            row.put("id", rs.getInt("id"));
                            row.put("providerKey", rs.getString("provider_key"));
                            row.put("enabled", rs.getInt("enabled") == 1);
                            row.put("baseUrl", rs.getString("base_url"));
                            row.put("apiKey", rs.getString("api_key"));
                            row.put("apiFormat", rs.getString("api_format"));
                            row.put("updatedAt", rs.getString("updated_at"));
                            row.put("models", findModelsByProviderId(rs.getInt("id")));
                            return row;
                        });
        return providers;
    }

    /**
     * 根据 provider_key 查询单个服务商配置。
     */
    public Map<String, Object> findByKey(String providerKey) {
        return jdbcTemplate.query("SELECT id, provider_key, enabled, base_url, api_key, api_format, updated_at "
                + "FROM provider_config WHERE provider_key = ?", rs -> {
                    if (rs.next()) {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("id", rs.getInt("id"));
                        row.put("providerKey", rs.getString("provider_key"));
                        row.put("enabled", rs.getInt("enabled") == 1);
                        row.put("baseUrl", rs.getString("base_url"));
                        row.put("apiKey", rs.getString("api_key"));
                        row.put("apiFormat", rs.getString("api_format"));
                        row.put("updatedAt", rs.getString("updated_at"));
                        row.put("models", findModelsByProviderId(rs.getInt("id")));
                        return row;
                    }
                    return null;
                }, providerKey);
    }

    /**
     * 查询某个服务商的模型列表。
     */
    public List<Map<String, Object>> findModelsByProviderId(int providerId) {
        return jdbcTemplate
                .query("SELECT id, provider_id, model_name, context_size, caps_tools, caps_vision, sort_order "
                        + "FROM provider_model WHERE provider_id = ? ORDER BY sort_order, id", (rs, rowNum) -> {
                            Map<String, Object> row = new java.util.LinkedHashMap<>();
                            row.put("id", rs.getInt("id"));
                            row.put("providerId", rs.getInt("provider_id"));
                            row.put("modelName", rs.getString("model_name"));
                            row.put("contextSize", rs.getInt("context_size"));
                            row.put("capsTools", rs.getInt("caps_tools") == 1);
                            row.put("capsVision", rs.getInt("caps_vision") == 1);
                            row.put("sortOrder", rs.getInt("sort_order"));
                            return row;
                        }, providerId);
    }

    // ==================== 写入 ====================

    /**
     * 保存服务商配置（UPSERT）。
     * 如果 provider_key 已存在则更新，否则插入。
     *
     * @return 对应的 provider_config.id
     */
    public int saveProvider(String providerKey, boolean enabled, String baseUrl, String apiKey, String apiFormat) {
        // 尝试更新已有记录
        int updated = jdbcTemplate.update(
                "UPDATE provider_config SET enabled = ?, base_url = ?, api_key = ?, api_format = ?, "
                        + "updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') " + "WHERE provider_key = ?",
                enabled ? 1 : 0, baseUrl, apiKey, apiFormat, providerKey);
        if (updated > 0) {
            // 返回已有记录的 id
            return jdbcTemplate.queryForObject("SELECT id FROM provider_config WHERE provider_key = ?", Integer.class,
                    providerKey);
        }
        // 插入新记录
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, enabled, base_url, api_key, api_format) "
                + "VALUES (?, ?, ?, ?, ?)", providerKey, enabled ? 1 : 0, baseUrl, apiKey, apiFormat);
        return jdbcTemplate.queryForObject("SELECT id FROM provider_config WHERE provider_key = ?", Integer.class,
                providerKey);
    }

    /**
     * 保存模型列表（先删除旧列表，再批量插入）。
     */
    public void saveModels(int providerId, List<Map<String, Object>> models) {
        // 删除该服务商下所有旧模型
        jdbcTemplate.update("DELETE FROM provider_model WHERE provider_id = ?", providerId);
        // 批量插入新模型
        if (models == null || models.isEmpty())
            return;
        for (int i = 0; i < models.size(); i++) {
            Map<String, Object> m = models.get(i);
            String modelName = (String) m.getOrDefault("modelName", "");
            int contextSize = parseInt(m.get("contextSize"), 0);
            boolean capsTools = Boolean.TRUE.equals(m.get("capsTools"));
            boolean capsVision = Boolean.TRUE.equals(m.get("capsVision"));
            jdbcTemplate.update(
                    "INSERT INTO provider_model (provider_id, model_name, context_size, caps_tools, caps_vision, sort_order) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    providerId, modelName, contextSize, capsTools ? 1 : 0, capsVision ? 1 : 0, i);
        }
    }

    // ==================== 应用运行配置（app_config 键值对） ====================

    /**
     * 读取单个配置项。
     */
    public String findConfigValue(String key) {
        return jdbcTemplate.query("SELECT config_value FROM app_config WHERE config_key = ?", rs -> {
            if (rs.next()) {
                return rs.getString("config_value");
            }
            return null;
        }, key);
    }

    /**
     * 保存配置项（UPSERT）。
     */
    public void saveConfig(String key, String value) {
        int updated = jdbcTemplate.update(
                "UPDATE app_config SET config_value = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') "
                        + "WHERE config_key = ?",
                value, key);
        if (updated == 0) {
            jdbcTemplate.update("INSERT INTO app_config (config_key, config_value) VALUES (?, ?)", key, value);
        }
    }

    // ==================== 工具 ====================

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
}

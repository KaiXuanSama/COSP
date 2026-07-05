package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 应用运行配置数据访问层 — 基于 JdbcTemplate 操作 SQLite app_config 表。
 *
 * 从 ProviderConfigRepository 中拆分出来，遵循单一职责原则：
 * 本类只负责 app_config 键值对的读写，不涉及供应商或模型配置。
 */
@Repository
public class AppConfigRepository {

    private final JdbcTemplate jdbcTemplate;

    public AppConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取单个配置项。
     *
     * @param key 配置键
     * @return 配置值，不存在时返回 null
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
     *
     * @param key   配置键
     * @param value 配置值
     */
    public void saveConfig(String key, String value) {
        int updated = jdbcTemplate.update(
                "UPDATE app_config SET config_value = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE config_key = ?",
                value, key);
        if (updated == 0) {
            jdbcTemplate.update("INSERT INTO app_config (config_key, config_value) VALUES (?, ?)", key, value);
        }
    }
}

package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据库 Schema 迁移 — 在应用启动时自动执行增量迁移。
 * <p>
 * SQLite 的 ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS，
 * 因此通过 PRAGMA table_info 检查列是否存在后再执行。
 */
@Component
public class SchemaMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        addColumnIfNotExists("provider_model", "reasoning_effort", "TEXT NOT NULL DEFAULT 'Medium'");
        addColumnIfNotExists("api_call_log", "response_headers", "TEXT");
        addColumnIfNotExists("api_call_log", "status_code", "INTEGER");
        addColumnIfNotExists("provider_config", "custom_transforms", "TEXT NOT NULL DEFAULT '{}'");
        // 2026-07: 支持用户配置模型最大输出 token 数
        addColumnIfNotExists("provider_model", "max_output_tokens", "INTEGER NOT NULL DEFAULT 128000");
        // 2026-07: 多 API Key 管理 — 添加激活索引列 + 迁移旧格式数据
        addColumnIfNotExists("provider_config", "active_api_key_index", "INTEGER NOT NULL DEFAULT 0");
        migrateApiKeyToJsonArray();
    }

    private void addColumnIfNotExists(String table, String column, String definition) {
        boolean exists = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(col -> column.equals(col.get("name")));
        if (!exists) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("[SchemaMigration] 已添加列 {}.{}", table, column);
        }
    }

    /**
     * 将旧格式的 api_key（纯字符串）迁移为 JSON 数组格式。
     * 检测所有 api_key 不以 '[' 开头的记录，将其包装为 [{"name":"Default","api_key":"原值"}]。
     * 空字符串保持为 '[]'。
     */
    private void migrateApiKeyToJsonArray() {
        // 查找所有非 JSON 数组格式的记录
        var rows = jdbcTemplate.queryForList(
                "SELECT id, api_key FROM provider_config WHERE api_key NOT LIKE '[%'");
        for (var row : rows) {
            int id = ((Number) row.get("id")).intValue();
            String oldKey = (String) row.get("api_key");
            String newValue;
            if (oldKey == null || oldKey.isBlank()) {
                newValue = "[]";
            } else {
                // 转义 JSON 特殊字符
                String escaped = oldKey.replace("\\", "\\\\").replace("\"", "\\\"");
                newValue = "[{\"name\":\"Default\",\"api_key\":\"" + escaped + "\"}]";
            }
            jdbcTemplate.update("UPDATE provider_config SET api_key = ? WHERE id = ?", newValue, id);
            log.info("[SchemaMigration] 已迁移 provider_config.id={} 的 api_key 为 JSON 数组格式", id);
        }
    }
}

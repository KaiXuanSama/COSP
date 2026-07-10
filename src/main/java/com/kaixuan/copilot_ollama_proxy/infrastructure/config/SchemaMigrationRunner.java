package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库 Schema 版本迁移器。
 *
 * 每个迁移在独立事务中执行，成功后写入 schema_version。新数据库由 schema.sql
 * 直接创建最终结构，旧数据库则通过这里补齐列、转换数据并增加约束与索引。
 */
@Component
public class SchemaMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationRunner.class);
    private static final TypeReference<List<Map<String, String>>> API_KEY_LIST_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建 Schema 迁移器。
     *
     * @param jdbcTemplate JDBC 操作模板
     * @param transactionTemplate 迁移事务模板
     * @param objectMapper JSON 序列化器
     */
    public SchemaMigrationRunner(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                                 ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 按版本顺序执行尚未应用的迁移。
     *
     * @param args 应用启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                + "version INTEGER PRIMARY KEY, description TEXT NOT NULL, "
                + "applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");

        migrate(1, "补齐历史增量字段", this::migrateLegacyColumns);
        migrate(2, "API Key 转换为 JSON 数组", this::migrateApiKeyToJsonArray);
        migrate(3, "增加业务约束与查询索引", this::migrateConstraintsAndIndexes);
    }

    private void migrate(int version, String description, Runnable action) {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_version WHERE version = ?", Integer.class, version);
        if (applied != null && applied > 0) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            action.run();
            jdbcTemplate.update("INSERT INTO schema_version (version, description) VALUES (?, ?)",
                    version, description);
        });
        log.info("[SchemaMigration] 已应用 V{}: {}", version, description);
    }

    private void migrateLegacyColumns() {
        addColumnIfNotExists("provider_model", "reasoning_effort", "TEXT NOT NULL DEFAULT 'Medium'");
        addColumnIfNotExists("api_call_log", "response_headers", "TEXT");
        addColumnIfNotExists("api_call_log", "status_code", "INTEGER");
        addColumnIfNotExists("provider_config", "custom_transforms", "TEXT NOT NULL DEFAULT '{}'");
        addColumnIfNotExists("provider_model", "max_output_tokens", "INTEGER NOT NULL DEFAULT 128000");
        addColumnIfNotExists("provider_config", "active_api_key_index", "INTEGER NOT NULL DEFAULT 0");
    }

    private void addColumnIfNotExists(String table, String column, String definition) {
        boolean exists = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(col -> column.equals(col.get("name")));
        if (!exists) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("[SchemaMigration] 已添加列 {}.{}", table, column);
        }
    }

    private void migrateApiKeyToJsonArray() {
        var rows = jdbcTemplate.queryForList("SELECT id, api_key FROM provider_config");
        for (var row : rows) {
            int id = ((Number) row.get("id")).intValue();
            String oldValue = (String) row.get("api_key");
            if (isJsonArray(oldValue)) {
                continue;
            }
            try {
                List<Map<String, String>> keys;
                if (oldValue == null || oldValue.isBlank()) {
                    keys = List.of();
                } else {
                    Map<String, String> keyEntry = new LinkedHashMap<>();
                    keyEntry.put("name", "Default");
                    keyEntry.put("api_key", oldValue);
                    keys = List.of(keyEntry);
                }
                jdbcTemplate.update("UPDATE provider_config SET api_key = ? WHERE id = ?",
                        objectMapper.writeValueAsString(keys), id);
            } catch (Exception e) {
                throw new IllegalStateException("迁移 provider_config.id=" + id + " 的 API Key 失败", e);
            }
        }
    }

    private boolean isJsonArray(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            objectMapper.readValue(value, API_KEY_LIST_TYPE);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void migrateConstraintsAndIndexes() {
        normalizeLegacyValues();
        removeDuplicateModels();

        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_provider_model_provider_id");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_provider_model_provider_name "
                + "ON provider_model(provider_id, model_name)");
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_api_call_log_created_at");
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_api_call_log_provider");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_api_call_log_created_id "
                + "ON api_call_log(created_at DESC, id DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_api_call_log_provider_created_id "
                + "ON api_call_log(provider_key, created_at DESC, id DESC)");

        createValidationTriggers();
    }

    private void normalizeLegacyValues() {
        jdbcTemplate.update("UPDATE users SET enabled = CASE WHEN enabled = 0 THEN 0 ELSE 1 END");
        jdbcTemplate.update("UPDATE provider_config SET enabled = CASE WHEN enabled = 0 THEN 0 ELSE 1 END, "
                + "active_api_key_index = MAX(active_api_key_index, 0)");
        jdbcTemplate.update("UPDATE provider_model SET enabled = CASE WHEN enabled = 0 THEN 0 ELSE 1 END, "
                + "caps_tools = CASE WHEN caps_tools = 0 THEN 0 ELSE 1 END, "
                + "caps_vision = CASE WHEN caps_vision = 0 THEN 0 ELSE 1 END, "
                + "context_size = MAX(context_size, 0), max_output_tokens = MAX(max_output_tokens, 0), "
                + "sort_order = MAX(sort_order, 0)");
        jdbcTemplate.update("UPDATE api_usage_daily SET call_count = MAX(call_count, 0), "
                + "input_tokens = MAX(input_tokens, 0), output_tokens = MAX(output_tokens, 0)");
        jdbcTemplate.update("UPDATE api_call_log SET is_stream = CASE WHEN is_stream = 0 THEN 0 ELSE 1 END, "
                + "duration_ms = CASE WHEN duration_ms IS NULL THEN NULL ELSE MAX(duration_ms, 0) END");
    }

    private void removeDuplicateModels() {
        int deleted = jdbcTemplate.update("DELETE FROM provider_model WHERE id NOT IN ("
                + "SELECT MIN(id) FROM provider_model GROUP BY provider_id, model_name)");
        if (deleted > 0) {
            log.warn("[SchemaMigration] 已删除 {} 条重复的供应商模型记录", deleted);
        }
    }

    private void createValidationTriggers() {
        createTrigger("trg_users_validate_insert", "users", "INSERT", "NEW.enabled NOT IN (0, 1)");
        createTrigger("trg_users_validate_update", "users", "UPDATE", "NEW.enabled NOT IN (0, 1)");
        createTrigger("trg_provider_config_validate_insert", "provider_config", "INSERT",
                "NEW.enabled NOT IN (0, 1) OR NEW.active_api_key_index < 0");
        createTrigger("trg_provider_config_validate_update", "provider_config", "UPDATE",
                "NEW.enabled NOT IN (0, 1) OR NEW.active_api_key_index < 0");
        String modelInvalid = "NEW.enabled NOT IN (0, 1) OR NEW.caps_tools NOT IN (0, 1) "
                + "OR NEW.caps_vision NOT IN (0, 1) OR NEW.context_size < 0 "
                + "OR NEW.max_output_tokens < 0 OR NEW.sort_order < 0";
        createTrigger("trg_provider_model_validate_insert", "provider_model", "INSERT", modelInvalid);
        createTrigger("trg_provider_model_validate_update", "provider_model", "UPDATE", modelInvalid);
        String usageInvalid = "NEW.call_count < 0 OR NEW.input_tokens < 0 OR NEW.output_tokens < 0";
        createTrigger("trg_api_usage_validate_insert", "api_usage_daily", "INSERT", usageInvalid);
        createTrigger("trg_api_usage_validate_update", "api_usage_daily", "UPDATE", usageInvalid);
        String logInvalid = "NEW.is_stream NOT IN (0, 1) OR (NEW.duration_ms IS NOT NULL AND NEW.duration_ms < 0)";
        createTrigger("trg_api_call_log_validate_insert", "api_call_log", "INSERT", logInvalid);
        createTrigger("trg_api_call_log_validate_update", "api_call_log", "UPDATE", logInvalid);
    }

    private void createTrigger(String name, String table, String operation, String invalidCondition) {
        jdbcTemplate.execute("CREATE TRIGGER IF NOT EXISTS " + name + " BEFORE " + operation + " ON " + table
                + " WHEN " + invalidCondition + " BEGIN SELECT RAISE(ABORT, '数据约束校验失败: "
                + table + "'); END");
    }
}

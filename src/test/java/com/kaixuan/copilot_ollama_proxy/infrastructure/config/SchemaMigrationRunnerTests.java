package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaMigrationRunnerTests {

    @TempDir
    Path tempDir;

    @Test
    void migratesLegacySchemaRecordsVersionsAndAppliesConstraintsIdempotently() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createLegacySchema(jdbcTemplate);
        seedLegacyData(jdbcTemplate);

        SchemaMigrationRunner runner = new SchemaMigrationRunner(
                jdbcTemplate,
                new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource())),
                new ObjectMapper());

        runner.run(null);
        runner.run(null);

        Integer versionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class);
        assertThat(versionCount).isEqualTo(3);
        assertThat(columnNames(jdbcTemplate, "provider_model"))
                .contains("reasoning_effort", "max_output_tokens");
        assertThat(columnNames(jdbcTemplate, "provider_config"))
                .contains("custom_transforms", "active_api_key_index");

        String apiKeys = jdbcTemplate.queryForObject(
                "SELECT api_key FROM provider_config WHERE provider_key = 'legacy'", String.class);
        assertThat(apiKeys).isEqualTo("[{\"name\":\"Default\",\"api_key\":\"sk-legacy\"}]");

        Integer modelCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_model WHERE provider_id = 1 AND model_name = 'duplicate'",
                Integer.class);
        assertThat(modelCount).isEqualTo(1);

        List<String> indexNames = jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'index'", String.class);
        assertThat(indexNames).contains(
                "ux_provider_model_provider_name",
                "idx_api_call_log_created_id",
                "idx_api_call_log_provider_created_id");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_model "
                        + "(provider_id, model_name, enabled, context_size, max_output_tokens, caps_tools, caps_vision, reasoning_effort, sort_order) "
                        + "VALUES (1, 'invalid', 1, -1, 128000, 1, 0, 'Medium', 0)"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_model "
                        + "(provider_id, model_name, enabled, context_size, max_output_tokens, caps_tools, caps_vision, reasoning_effort, sort_order) "
                        + "VALUES (1, 'duplicate', 1, 1, 1, 1, 0, 'Medium', 1)"))
                .isInstanceOf(DataAccessException.class);
    }

    private JdbcTemplate createJdbcTemplate() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("migration.db"));
        return new JdbcTemplate(dataSource);
    }

    private void createLegacySchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("CREATE TABLE users (username TEXT PRIMARY KEY, password TEXT NOT NULL, enabled INTEGER NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE provider_config (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT NOT NULL UNIQUE, enabled INTEGER NOT NULL DEFAULT 0, base_url TEXT NOT NULL DEFAULT '', api_key TEXT NOT NULL DEFAULT '', api_format TEXT NOT NULL DEFAULT 'openai', updated_at TEXT)");
        jdbcTemplate.execute("CREATE TABLE provider_model (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id INTEGER NOT NULL, model_name TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, context_size INTEGER NOT NULL DEFAULT 0, caps_tools INTEGER NOT NULL DEFAULT 0, caps_vision INTEGER NOT NULL DEFAULT 0, sort_order INTEGER NOT NULL DEFAULT 0)");
        jdbcTemplate.execute("CREATE TABLE api_usage_daily (usage_date TEXT PRIMARY KEY, call_count INTEGER NOT NULL DEFAULT 0, input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0, updated_at TEXT)");
        jdbcTemplate.execute("CREATE TABLE api_call_log (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT, model_name TEXT, is_stream INTEGER NOT NULL DEFAULT 0, request_headers TEXT, request_body TEXT, response_body TEXT, chunks TEXT, duration_ms INTEGER, created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')))");
    }

    private void seedLegacyData(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("INSERT INTO users VALUES ('root', 'password', 2)");
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, enabled, base_url, api_key, api_format) VALUES ('legacy', 2, '', 'sk-legacy', 'openai')");
        jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name, enabled, context_size, caps_tools, caps_vision, sort_order) VALUES (1, 'duplicate', 2, -1, 2, 0, -1)");
        jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name, enabled, context_size, caps_tools, caps_vision, sort_order) VALUES (1, 'duplicate', 1, 100, 1, 0, 1)");
        jdbcTemplate.update("INSERT INTO api_usage_daily VALUES ('2026-07-10', -1, -2, -3, NULL)");
        jdbcTemplate.update("INSERT INTO api_call_log (provider_key, is_stream, duration_ms) VALUES ('legacy', 2, -10)");
    }

    private List<String> columnNames(JdbcTemplate jdbcTemplate, String tableName) {
        return jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")").stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
    }
}

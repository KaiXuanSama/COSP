package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.AppConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.security.ApiKeyCryptoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
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
        void migratesLegacySchemaRecordsVersionsAndAppliesConstraintsIdempotently() throws Exception {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createLegacySchema(jdbcTemplate);
        seedLegacyData(jdbcTemplate);

        ApiKeyCryptoService cryptoService = new ApiKeyCryptoService("test-master-key");
        ReflectionTestUtils.invokeMethod(cryptoService, "initialize");
        SchemaMigrationRunner runner = new SchemaMigrationRunner(
                jdbcTemplate,
                new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource())),
                new ObjectMapper(),
                cryptoService,
                new AppConfigRepository(jdbcTemplate));

        runner.run(null);
        runner.run(null);

        Integer versionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class);
        assertThat(versionCount).isEqualTo(6);
        assertThat(columnNames(jdbcTemplate, "provider_model"))
                .contains("reasoning_effort", "max_output_tokens");
        assertThat(columnNames(jdbcTemplate, "provider_config"))
                .contains("custom_transforms", "active_api_key_index");

        // V4：API Key 已拆到 provider_api_key 并加密，旧明文列被清空
        String clearedApiKeys = jdbcTemplate.queryForObject(
                "SELECT api_key FROM provider_config WHERE provider_key = 'legacy'", String.class);
        assertThat(clearedApiKeys).isEqualTo("[]");

        String encrypted = jdbcTemplate.queryForObject(
                "SELECT encrypted_api_key FROM provider_api_key WHERE provider_id = 1 AND is_active = 1", String.class);
        String nonce = jdbcTemplate.queryForObject(
                "SELECT nonce FROM provider_api_key WHERE provider_id = 1 AND is_active = 1", String.class);
        assertThat(encrypted).isNotBlank();
        assertThat(cryptoService.decrypt(nonce, encrypted)).isEqualTo("sk-legacy");

        // V5：复制旧请求头配置并初始化新规则编辑器状态；V6 清空全部旧转换配置
        String headerRules = jdbcTemplate.queryForObject(
                "SELECT header_rules_json FROM provider_request_transform WHERE provider_id = 1", String.class);
        assertThat(new ObjectMapper().readTree(headerRules)).isEqualTo(new ObjectMapper().readTree(
                "[{\"key\":\"api-key\",\"value\":\"{apiKey}\"}]"));
        String templateKeys = jdbcTemplate.queryForObject(
                "SELECT body_template_keys_json FROM provider_request_transform WHERE provider_id = 1", String.class);
        assertThat(templateKeys).isEqualTo("[\"base\"]");
        String bodyPreview = jdbcTemplate.queryForObject(
                "SELECT body_preview_json FROM provider_request_transform WHERE provider_id = 1", String.class);
        assertThat(new ObjectMapper().readTree(bodyPreview)).isEqualTo(new ObjectMapper().readTree(
                "{\"model\":\"<string>\",\"temperature\":0.1,\"top_p\":1.0,\"stream\":true,"
                        + "\"n\":1,\"stream_options\":{\"include_usage\":true},"
                        + "\"reasoning_effort\":\"medium\"}"));
        String bodyRules = jdbcTemplate.queryForObject(
                "SELECT body_rules_json FROM provider_request_transform WHERE provider_id = 1", String.class);
        assertThat(bodyRules).isEqualTo("{\"version\":1,\"rules\":[]}");
        String legacyTransforms = jdbcTemplate.queryForObject(
                "SELECT custom_transforms FROM provider_config WHERE id = 1", String.class);
        assertThat(legacyTransforms).isEqualTo("{}");

        String fingerprint = jdbcTemplate.queryForObject(
                "SELECT config_value FROM app_config WHERE config_key = 'encryption_key_fingerprint'", String.class);
        assertThat(fingerprint).isEqualTo(cryptoService.fingerprint());

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

    @Test
    void rerunningV5DoesNotOverwriteExistingRequestTransformConfiguration() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createLegacySchema(jdbcTemplate);
        seedLegacyData(jdbcTemplate);

        ApiKeyCryptoService cryptoService = new ApiKeyCryptoService("test-master-key");
        ReflectionTestUtils.invokeMethod(cryptoService, "initialize");
        SchemaMigrationRunner runner = new SchemaMigrationRunner(jdbcTemplate,
                new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource())),
                new ObjectMapper(), cryptoService, new AppConfigRepository(jdbcTemplate));
        runner.run(null);

        String customRules = "{\"version\":1,\"rules\":[{\"id\":\"saved\"}]}";
        jdbcTemplate.update("UPDATE provider_request_transform SET body_template_keys_json = ?, "
                        + "body_preview_json = ?, body_rules_json = ? WHERE provider_id = 1",
                "[\"custom\"]", "{\"saved\":true}", customRules);
        jdbcTemplate.update("DELETE FROM schema_version WHERE version = 5");

        runner.run(null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_template_keys_json FROM provider_request_transform WHERE provider_id = 1",
                String.class)).isEqualTo("[\"custom\"]");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_preview_json FROM provider_request_transform WHERE provider_id = 1",
                String.class)).isEqualTo("{\"saved\":true}");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_rules_json FROM provider_request_transform WHERE provider_id = 1",
                String.class)).isEqualTo(customRules);
    }

    @Test
    void rollsBackV5WhenLegacyCustomTransformsIsInvalid() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createLegacySchema(jdbcTemplate);
        seedLegacyData(jdbcTemplate);
        jdbcTemplate.update("UPDATE provider_config SET custom_transforms = 'not-json' WHERE id = 1");

        ApiKeyCryptoService cryptoService = new ApiKeyCryptoService("test-master-key");
        ReflectionTestUtils.invokeMethod(cryptoService, "initialize");
        SchemaMigrationRunner runner = new SchemaMigrationRunner(jdbcTemplate,
                new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource())),
                new ObjectMapper(), cryptoService, new AppConfigRepository(jdbcTemplate));

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("请求头规则失败");
        Integer v5Count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_version WHERE version = 5", Integer.class);
        assertThat(v5Count).isZero();
        Integer transformTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' "
                        + "AND name = 'provider_request_transform'", Integer.class);
        assertThat(transformTableCount).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT custom_transforms FROM provider_config WHERE id = 1", String.class))
                .isEqualTo("not-json");
    }

    @Test
    void rejectsStartupWhenMasterKeyDoesNotMatchStoredFingerprint() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createLegacySchema(jdbcTemplate);
        seedLegacyData(jdbcTemplate);

        ApiKeyCryptoService original = new ApiKeyCryptoService("original-key");
        ReflectionTestUtils.invokeMethod(original, "initialize");
        SchemaMigrationRunner firstRun = new SchemaMigrationRunner(jdbcTemplate,
                new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource())),
                new ObjectMapper(), original, new AppConfigRepository(jdbcTemplate));
        firstRun.run(null);

        // 用不同主密钥重跑 V4：删除 V4 版本记录以触发重新执行
        jdbcTemplate.update("DELETE FROM schema_version WHERE version = 4");
        ApiKeyCryptoService wrong = new ApiKeyCryptoService("different-key");
        ReflectionTestUtils.invokeMethod(wrong, "initialize");
        SchemaMigrationRunner secondRun = new SchemaMigrationRunner(jdbcTemplate,
                new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource())),
                new ObjectMapper(), wrong, new AppConfigRepository(jdbcTemplate));

        assertThatThrownBy(() -> secondRun.run(null))
                .isInstanceOf(IllegalStateException.class);
    }

    private JdbcTemplate createJdbcTemplate() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("migration.db"));
        return new JdbcTemplate(dataSource);
    }

    private void createLegacySchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("CREATE TABLE users (username TEXT PRIMARY KEY, password TEXT NOT NULL, enabled INTEGER NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE provider_config (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT NOT NULL UNIQUE, enabled INTEGER NOT NULL DEFAULT 0, base_url TEXT NOT NULL DEFAULT '', api_key TEXT NOT NULL DEFAULT '', api_format TEXT NOT NULL DEFAULT 'openai', custom_transforms TEXT NOT NULL DEFAULT '{}', updated_at TEXT)");
        jdbcTemplate.execute("CREATE TABLE provider_model (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id INTEGER NOT NULL, model_name TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, context_size INTEGER NOT NULL DEFAULT 0, caps_tools INTEGER NOT NULL DEFAULT 0, caps_vision INTEGER NOT NULL DEFAULT 0, sort_order INTEGER NOT NULL DEFAULT 0)");
        jdbcTemplate.execute("CREATE TABLE app_config (config_key TEXT PRIMARY KEY, config_value TEXT NOT NULL DEFAULT '', updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')))");
        jdbcTemplate.execute("CREATE TABLE api_usage_daily (usage_date TEXT PRIMARY KEY, call_count INTEGER NOT NULL DEFAULT 0, input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0, updated_at TEXT)");
        jdbcTemplate.execute("CREATE TABLE api_call_log (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT, model_name TEXT, is_stream INTEGER NOT NULL DEFAULT 0, request_headers TEXT, request_body TEXT, response_body TEXT, chunks TEXT, duration_ms INTEGER, created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')))");
    }

    private void seedLegacyData(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("INSERT INTO users VALUES ('root', 'password', 2)");
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, enabled, base_url, api_key, api_format, custom_transforms) "
                + "VALUES ('legacy', 2, '', 'sk-legacy', 'openai', "
                + "'{\"custom_headers\":[{\"key\":\"api-key\",\"value\":\"{apiKey}\"}],"
                + "\"body_transforms\":[{\"key\":\"temperature\",\"value\":\"0.7\"}]}')");
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

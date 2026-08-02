package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.AppConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.security.ApiKeyCryptoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
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
        void migratesLegacySchemaToV82AndPhysicallyRemovesObsoleteColumns() throws Exception {
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
        assertThat(versionCount).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(columnNames(jdbcTemplate, "provider_model"))
                .contains("reasoning_effort", "max_output_tokens");
        assertThat(columnNames(jdbcTemplate, "provider_config"))
                .contains("display_name")
                .doesNotContain("api_key", "active_api_key_index", "custom_transforms", "api_format");
        assertThat(tableExists(jdbcTemplate, "reasoning_cache")).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM provider_config WHERE id = 1", String.class)).isEqualTo("Legacy");

        String encrypted = jdbcTemplate.queryForObject(
                "SELECT encrypted_api_key FROM provider_api_key WHERE provider_id = 1 AND is_active = 1", String.class);
        String nonce = jdbcTemplate.queryForObject(
                "SELECT nonce FROM provider_api_key WHERE provider_id = 1 AND is_active = 1", String.class);
        assertThat(encrypted).isNotBlank();
        assertThat(cryptoService.decrypt(nonce, encrypted)).isEqualTo("sk-legacy");

        // V5：复制旧请求头配置并初始化新规则编辑器状态；V7 保留新表数据。
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
        void emptyDatabaseInitializesSchemaSqlAndEstablishesCurrentBaseline() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
                new ResourceDatabasePopulator(new ClassPathResource("schema.sql"))
                                .execute(jdbcTemplate.getDataSource());

        ApiKeyCryptoService cryptoService = new ApiKeyCryptoService("test-master-key");
        ReflectionTestUtils.invokeMethod(cryptoService, "initialize");
        SchemaMigrationRunner runner = new SchemaMigrationRunner(jdbcTemplate,
                new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource())),
                new ObjectMapper(), cryptoService, new AppConfigRepository(jdbcTemplate));

        runner.run(null);
        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(columnNames(jdbcTemplate, "provider_config"))
                .doesNotContain("api_key", "active_api_key_index", "custom_transforms", "api_format");
        assertThat(tableExists(jdbcTemplate, "reasoning_cache")).isFalse();
        assertThat(tableExists(jdbcTemplate, "api_call_usage")).isTrue();
        assertThat(indexExists(jdbcTemplate, "idx_api_call_usage_created")).isTrue();
    }

    @Test
        void v82DatabaseAddsApiCallUsageTableDuringV83Migration() {
                JdbcTemplate jdbcTemplate = createJdbcTemplate();
                createCurrentSchema(jdbcTemplate);
                jdbcTemplate.execute("CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), "
                                + "version REAL NOT NULL, description TEXT NOT NULL, applied_at TEXT)");
                jdbcTemplate.update("INSERT INTO schema_version (id, version, description) VALUES (1, 8.2, 'V8.2')");

                SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
                runner.run(null);
                runner.run(null);

                assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
                assertThat(tableExists(jdbcTemplate, "api_call_usage")).isTrue();
                assertThat(indexExists(jdbcTemplate, "idx_api_call_usage_provider_created")).isTrue();
                assertThat(indexExists(jdbcTemplate, "idx_api_call_usage_log")).isTrue();
                assertThat(columnNames(jdbcTemplate, "api_call_usage")).contains(
                                "id", "log_id", "provider_key", "model_name", "is_stream", "usage_raw",
                                "prompt_tokens", "completion_tokens", "cached_tokens", "ttfb_ms", "created_at");
                // 可空 token 列：null 与 0 均可写入且各自保留。
                jdbcTemplate.update("INSERT INTO api_call_usage (log_id, provider_key, model_name, is_stream, prompt_tokens, completion_tokens, cached_tokens, ttfb_ms) "
                                + "VALUES (NULL, 'p', 'm', 1, NULL, 0, NULL, NULL)");
                assertThat(jdbcTemplate.queryForObject(
                                "SELECT cached_tokens FROM api_call_usage WHERE completion_tokens = 0", Integer.class)).isNull();
        }

    @Test
        void legacyDatabaseRecordedAtV2ContinuesThroughV82AndCompactsHistory() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createLegacySchema(jdbcTemplate);
        seedLegacyData(jdbcTemplate);
                jdbcTemplate.execute("ALTER TABLE provider_model ADD COLUMN reasoning_effort TEXT NOT NULL DEFAULT 'Medium'");
                jdbcTemplate.execute("ALTER TABLE provider_model ADD COLUMN max_output_tokens INTEGER NOT NULL DEFAULT 128000");
                jdbcTemplate.execute("ALTER TABLE api_call_log ADD COLUMN response_headers TEXT");
                jdbcTemplate.execute("ALTER TABLE api_call_log ADD COLUMN status_code INTEGER");
                jdbcTemplate.update("UPDATE provider_config SET api_key = ? WHERE id = 1",
                        "[{\"name\":\"Default\",\"api_key\":\"sk-legacy\"}]");
        jdbcTemplate.execute("CREATE TABLE schema_version (version INTEGER PRIMARY KEY, description TEXT NOT NULL)");
        jdbcTemplate.update("INSERT INTO schema_version (version, description) VALUES (1, 'V1'), (2, 'V2')");

        ApiKeyCryptoService cryptoService = new ApiKeyCryptoService("test-master-key");
        ReflectionTestUtils.invokeMethod(cryptoService, "initialize");
        SchemaMigrationRunner runner = new SchemaMigrationRunner(jdbcTemplate,
                new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource())),
                new ObjectMapper(), cryptoService, new AppConfigRepository(jdbcTemplate));

        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(columnNames(jdbcTemplate, "provider_config"))
                .contains("display_name")
                .doesNotContain("api_key", "active_api_key_index", "custom_transforms", "api_format");
        assertThat(tableExists(jdbcTemplate, "reasoning_cache")).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_request_transform WHERE provider_id = 1", Integer.class)).isEqualTo(1);
    }

    @Test
        void v7BaselineDatabaseIncrementallyAddsDisplayNameAndRemovesCustomPrefix() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
                createCurrentProviderAssociations(jdbcTemplate);
                jdbcTemplate.update("INSERT INTO provider_config (provider_key) VALUES ('custom-stepfun')");
        jdbcTemplate.execute("CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), "
                + "version INTEGER NOT NULL, description TEXT NOT NULL, applied_at TEXT)");
        jdbcTemplate.update("INSERT INTO schema_version (id, version, description) VALUES (1, 7, 'V7 baseline')");

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM provider_config WHERE provider_key = 'stepfun'", String.class))
                .isEqualTo("Stepfun");
    }

    @Test
    void v8MigrationLetsCustomConfigurationReplaceConflictingProviderAndKeepsItsAssociations() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createCurrentProviderAssociations(jdbcTemplate);
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, display_name) VALUES ('mimo', '旧 MiMo')");
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, display_name) VALUES ('custom-mimo', '新 MiMo')");
        jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name) VALUES (1, 'old-model')");
        jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name) VALUES (2, 'new-model')");
        jdbcTemplate.update("INSERT INTO provider_api_key (provider_id) VALUES (1)");
        jdbcTemplate.update("INSERT INTO provider_api_key (provider_id) VALUES (2)");
        jdbcTemplate.update("INSERT INTO provider_request_transform (provider_id) VALUES (1)");
        jdbcTemplate.update("INSERT INTO provider_request_transform (provider_id) VALUES (2)");
        jdbcTemplate.execute("CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), "
                + "version REAL NOT NULL, description TEXT NOT NULL, applied_at TEXT)");
        jdbcTemplate.update("INSERT INTO schema_version (id, version, description) VALUES (1, 7.1, 'V7.1')");

        newMigrationRunner(jdbcTemplate).run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM provider_config", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM provider_config WHERE provider_key = 'mimo'", String.class))
                .isEqualTo("新 MiMo");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM provider_model WHERE model_name = 'new-model'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM provider_model WHERE model_name = 'old-model'", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM provider_api_key", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM provider_request_transform", Integer.class)).isEqualTo(1);
    }

        @Test
        void v81DatabaseDropsReasoningCacheAndItsIndexDuringV82Migration() {
                JdbcTemplate jdbcTemplate = createJdbcTemplate();
                createCurrentSchema(jdbcTemplate);
                jdbcTemplate.execute("CREATE TABLE reasoning_cache (tool_call_id TEXT PRIMARY KEY, reasoning_content TEXT NOT NULL)");
                jdbcTemplate.execute("CREATE INDEX idx_reasoning_cache_created_at ON reasoning_cache(reasoning_content)");
                jdbcTemplate.execute("CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), "
                                + "version REAL NOT NULL, description TEXT NOT NULL, applied_at TEXT)");
                jdbcTemplate.update("INSERT INTO schema_version (id, version, description) VALUES (1, 8.1, 'V8.1')");

                SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
                runner.run(null);
                runner.run(null);

                assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
                assertThat(tableExists(jdbcTemplate, "reasoning_cache")).isFalse();
                assertThat(indexExists(jdbcTemplate, "idx_reasoning_cache_created_at")).isFalse();
        }

    /**
     * V8.3 库升到 V8.4 时补出 created_at 前导索引，且不动表结构与数据。
     *
     * V8.3 已有的两个索引都无法服务纯时间范围扫描（provider_created 的前导列是
     * provider_key，log 索引与时间无关），故这里同时断言三个索引并存 ——
     * 新索引是补充而非替换，按供应商取最近记录仍要走原索引。
     */
    @Test
    void v83DatabaseAddsUsageCreatedAtIndexDuringV84Migration() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV83UsageTable(jdbcTemplate);
        jdbcTemplate.execute("CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), "
                + "version REAL NOT NULL, description TEXT NOT NULL, applied_at TEXT)");
        jdbcTemplate.update("INSERT INTO schema_version (id, version, description) VALUES (1, 8.3, 'V8.3')");
        jdbcTemplate.update("INSERT INTO api_call_usage (provider_key, model_name, is_stream, "
                + "prompt_tokens, completion_tokens, created_at) "
                + "VALUES ('deepseek', 'chat', 1, 120, 45, '2026-07-30T14:23:07')");

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(indexExists(jdbcTemplate, "idx_api_call_usage_created")).isTrue();
        // 新索引是补充而非替换：按供应商取最近记录仍需原索引
        assertThat(indexExists(jdbcTemplate, "idx_api_call_usage_provider_created")).isTrue();
        assertThat(indexExists(jdbcTemplate, "idx_api_call_usage_log")).isTrue();
        // 纯加索引迁移：既有行与列一个不动
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM api_call_usage", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_at FROM api_call_usage WHERE provider_key = 'deepseek'", String.class))
                .isEqualTo("2026-07-30T14:23:07");
        assertThat(columnNames(jdbcTemplate, "api_call_usage")).contains(
                "id", "log_id", "provider_key", "model_name", "is_stream", "usage_raw",
                "prompt_tokens", "completion_tokens", "cached_tokens", "ttfb_ms", "created_at");
    }

    /**
     * 索引建在 created_at 上，故按时间范围过滤的查询能用上它。
     *
     * 直接断言执行计划：索引存在但查询用不上是这次迁移要解决的原问题
     * （V8.3 的 provider_created 索引就是那种情况），只断言索引存在测不出区别。
     */
    @Test
    void usageCreatedAtIndexServesTimeRangeScan() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"))
                .execute(jdbcTemplate.getDataSource());

        // EXPLAIN QUERY PLAN 返回 (id, parent, notused, detail) 四列，计划描述在 detail
        String plan = jdbcTemplate.queryForList(
                "EXPLAIN QUERY PLAN SELECT COUNT(*) FROM api_call_usage "
                        + "WHERE created_at >= ? AND created_at < ?",
                "2026-07-25T00:00:00", "2026-08-01T00:00:00").stream()
                .map(row -> String.valueOf(row.get("detail")))
                .reduce("", (left, right) -> left + " | " + right);

        assertThat(plan).contains("idx_api_call_usage_created");
    }

    @Test
    void historicalV5MigrationDoesNotOverwriteExistingRequestTransformConfiguration() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createLegacySchema(jdbcTemplate);
        seedLegacyData(jdbcTemplate);

        ApiKeyCryptoService cryptoService = new ApiKeyCryptoService("test-master-key");
        ReflectionTestUtils.invokeMethod(cryptoService, "initialize");
        SchemaMigrationRunner runner = new SchemaMigrationRunner(jdbcTemplate,
                new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource())),
                new ObjectMapper(), cryptoService, new AppConfigRepository(jdbcTemplate));
        String customRules = "{\"version\":1,\"rules\":[{\"id\":\"saved\"}]}";
        jdbcTemplate.execute("CREATE TABLE provider_request_transform ("
                + "provider_id INTEGER PRIMARY KEY, header_rules_version INTEGER NOT NULL, "
                + "header_rules_json TEXT NOT NULL, body_template_keys_json TEXT NOT NULL, "
                + "body_preview_json TEXT NOT NULL, body_rules_version INTEGER NOT NULL, "
                + "body_rules_json TEXT NOT NULL)");
        jdbcTemplate.update("INSERT INTO provider_request_transform "
                        + "(provider_id, header_rules_version, header_rules_json, body_template_keys_json, "
                        + "body_preview_json, body_rules_version, body_rules_json) VALUES (1, 1, '[]', ?, ?, 1, ?)",
                "[\"custom\"]", "{\"saved\":true}", customRules);
        jdbcTemplate.execute("CREATE TABLE schema_version (version INTEGER PRIMARY KEY, description TEXT NOT NULL)");
        jdbcTemplate.update("INSERT INTO schema_version (version, description) VALUES (1, 'V1'), (2, 'V2'), (3, 'V3'), (4, 'V4')");

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
        void rejectsStartupWhenMasterKeyDoesNotMatchStoredFingerprintDuringHistoricalV4Migration() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createLegacySchema(jdbcTemplate);
        seedLegacyData(jdbcTemplate);

        ApiKeyCryptoService original = new ApiKeyCryptoService("original-key");
        ReflectionTestUtils.invokeMethod(original, "initialize");
        new AppConfigRepository(jdbcTemplate).saveConfig("encryption_key_fingerprint", original.fingerprint());
        jdbcTemplate.execute("CREATE TABLE schema_version (version INTEGER PRIMARY KEY, description TEXT NOT NULL)");
        jdbcTemplate.update("INSERT INTO schema_version (version, description) VALUES (1, 'V1'), (2, 'V2'), (3, 'V3')");
        ApiKeyCryptoService wrong = new ApiKeyCryptoService("different-key");
        ReflectionTestUtils.invokeMethod(wrong, "initialize");
        SchemaMigrationRunner secondRun = new SchemaMigrationRunner(jdbcTemplate,
                new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource())),
                new ObjectMapper(), wrong, new AppConfigRepository(jdbcTemplate));

        assertThatThrownBy(() -> secondRun.run(null))
                .isInstanceOf(IllegalStateException.class);
    }

        @Test
        void recursivelyUpgradesEveryRegisteredMigrationCheckpoint() {
                JdbcTemplate jdbcTemplate = createJdbcTemplate();
                createLegacySchema(jdbcTemplate);
                seedLegacyData(jdbcTemplate);
                SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);

                for (double version : runner.registeredMigrationVersions()) {
                        runner.migrateThrough(version);
                        assertCheckpoint(jdbcTemplate, version);
                }

                runner.run(null);
                assertCheckpoint(jdbcTemplate, SchemaMigrationRunner.currentSchemaVersion());
                assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM provider_config WHERE id = 1", String.class))
                                .isEqualTo("Legacy");
                assertThat(tableExists(jdbcTemplate, "reasoning_cache")).isFalse();
        }

    private JdbcTemplate createJdbcTemplate() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("migration.db"));
        return new JdbcTemplate(dataSource);
    }

        private SchemaMigrationRunner newMigrationRunner(JdbcTemplate jdbcTemplate) {
                ApiKeyCryptoService cryptoService = new ApiKeyCryptoService("test-master-key");
                ReflectionTestUtils.invokeMethod(cryptoService, "initialize");
                return new SchemaMigrationRunner(jdbcTemplate,
                                new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource())),
                                new ObjectMapper(), cryptoService, new AppConfigRepository(jdbcTemplate));
        }

    private void createLegacySchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("CREATE TABLE users (username TEXT PRIMARY KEY, password TEXT NOT NULL, enabled INTEGER NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE provider_config (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT NOT NULL UNIQUE, enabled INTEGER NOT NULL DEFAULT 0, base_url TEXT NOT NULL DEFAULT '', api_key TEXT NOT NULL DEFAULT '', active_api_key_index INTEGER NOT NULL DEFAULT 0, api_format TEXT NOT NULL DEFAULT 'openai', custom_transforms TEXT NOT NULL DEFAULT '{}', updated_at TEXT)");
        jdbcTemplate.execute("CREATE TABLE provider_model (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id INTEGER NOT NULL, model_name TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, context_size INTEGER NOT NULL DEFAULT 0, caps_tools INTEGER NOT NULL DEFAULT 0, caps_vision INTEGER NOT NULL DEFAULT 0, sort_order INTEGER NOT NULL DEFAULT 0)");
        jdbcTemplate.execute("CREATE TABLE app_config (config_key TEXT PRIMARY KEY, config_value TEXT NOT NULL DEFAULT '', updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')))");
        jdbcTemplate.execute("CREATE TABLE api_usage_daily (usage_date TEXT PRIMARY KEY, call_count INTEGER NOT NULL DEFAULT 0, input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0, updated_at TEXT)");
        jdbcTemplate.execute("CREATE TABLE api_call_log (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT, model_name TEXT, is_stream INTEGER NOT NULL DEFAULT 0, request_headers TEXT, request_body TEXT, response_body TEXT, chunks TEXT, duration_ms INTEGER, created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')))");
    }

        private void createCurrentSchema(JdbcTemplate jdbcTemplate) {
                jdbcTemplate.execute("CREATE TABLE provider_config (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                                                                + "provider_key TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 0, "
                                + "base_url TEXT NOT NULL DEFAULT '', updated_at TEXT)");
        }

        /**
         * 建出 V8.3 时的 api_call_usage —— 表与两个索引，但<strong>没有</strong>
         * created_at 前导索引，那正是 V8.4 要补的。
         */
        private void createV83UsageTable(JdbcTemplate jdbcTemplate) {
                jdbcTemplate.execute("CREATE TABLE api_call_usage ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT, log_id INTEGER, provider_key VARCHAR(30), "
                                + "model_name VARCHAR(100), is_stream INTEGER NOT NULL DEFAULT 0 CHECK (is_stream IN (0, 1)), "
                                + "usage_raw TEXT, "
                                + "prompt_tokens INTEGER CHECK (prompt_tokens IS NULL OR prompt_tokens >= 0), "
                                + "completion_tokens INTEGER CHECK (completion_tokens IS NULL OR completion_tokens >= 0), "
                                + "cached_tokens INTEGER CHECK (cached_tokens IS NULL OR cached_tokens >= 0), "
                                + "ttfb_ms INTEGER CHECK (ttfb_ms IS NULL OR ttfb_ms >= 0), "
                                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')))");
                jdbcTemplate.execute("CREATE INDEX idx_api_call_usage_provider_created "
                                + "ON api_call_usage(provider_key, created_at DESC)");
                jdbcTemplate.execute("CREATE INDEX idx_api_call_usage_log ON api_call_usage(log_id)");
        }

        private void createCurrentProviderAssociations(JdbcTemplate jdbcTemplate) {
                jdbcTemplate.execute("CREATE TABLE provider_model (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id INTEGER NOT NULL, model_name TEXT NOT NULL)");
                jdbcTemplate.execute("CREATE TABLE provider_api_key (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id INTEGER NOT NULL)");
                jdbcTemplate.execute("CREATE TABLE provider_request_transform (provider_id INTEGER PRIMARY KEY)");
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

        private boolean tableExists(JdbcTemplate jdbcTemplate, String tableName) {
                Integer count = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", Integer.class, tableName);
                return count != null && count > 0;
        }

        private boolean indexExists(JdbcTemplate jdbcTemplate, String indexName) {
                Integer count = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?", Integer.class, indexName);
                return count != null && count > 0;
        }

        private void assertCheckpoint(JdbcTemplate jdbcTemplate, double version) {
                if (version < 7) {
                        List<Double> applied = jdbcTemplate.queryForList(
                                        "SELECT version FROM schema_version ORDER BY version", Double.class);
                        assertThat(applied).contains(version);
                        return;
                }
                assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class)).isEqualTo(1);
                assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                                .isEqualTo(version);
                if (version >= 7.1) {
                        assertThat(columnNames(jdbcTemplate, "provider_config")).contains("display_name");
                }
                if (version >= 8.1) {
                        assertThat(columnNames(jdbcTemplate, "provider_config")).doesNotContain("api_format");
                }
                if (version >= 8.3) {
                        assertThat(tableExists(jdbcTemplate, "reasoning_cache")).isFalse();
                        assertThat(tableExists(jdbcTemplate, "api_call_usage")).isTrue();
                }
                if (version >= SchemaMigrationRunner.currentSchemaVersion()) {
                        assertThat(indexExists(jdbcTemplate, "idx_api_call_usage_created")).isTrue();
                }
        }
}

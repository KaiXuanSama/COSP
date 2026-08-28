package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Map;

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
        // V8.7：V5 写入的 V1 空规则集被升为 V2 单组，legacy 两列的内容搬进该组但列本身不变。
        JsonNode bodyRules = new ObjectMapper().readTree(jdbcTemplate.queryForObject(
                "SELECT body_rules_json FROM provider_request_transform WHERE provider_id = 1", String.class));
        assertThat(bodyRules.path("version").asInt()).isEqualTo(2);
        assertThat(bodyRules.path("groups")).hasSize(1);
        JsonNode legacyGroup = bodyRules.path("groups").get(0);
        assertThat(legacyGroup.path("protocols").toString()).isEqualTo("[\"OPENAI\"]");
        assertThat(legacyGroup.path("rules")).isEmpty();
        assertThat(legacyGroup.path("templateKeys").toString()).isEqualTo("[\"base\"]");
        assertThat(legacyGroup.path("previewBody")).isEqualTo(new ObjectMapper().readTree(bodyPreview));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_rules_schema FROM provider_request_transform WHERE provider_id = 1", Integer.class))
                .isEqualTo(2);
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
        assertThat(columnNames(jdbcTemplate, "api_call_log")).contains("payload_trimmed");
        assertThat(columnNames(jdbcTemplate, "provider_config"))
                .contains("supported_protocols", "anthropic_base_url");
        assertThat(columnNames(jdbcTemplate, "provider_model")).contains("reasoning_effort_schema");
        // 新库的默认思考深度须与迁移后的规范形态一致，否则「全库同形态」只在升级库成立。
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, display_name, enabled, base_url) "
                + "VALUES ('p', 'P', 1, 'https://p.example/v1')");
        jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name) VALUES (1, 'm')");
        assertThat(reasoningEffortOf(jdbcTemplate, "m"))
                .isEqualTo("{\"reasoning_effort\":\"medium\",\"overwrite_mode\":\"fallback\"}");
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

    /**
     * V8.4 库升到 V8.5 时补出 payload_trimmed 列，且既有行一律视为「载荷完整」。
     *
     * 存量行默认 0 是正确的：V8.5 之前的保留任务只做整行删除、从不清空字段，
     * 因此活着的行必然携带完整载荷。若默认成 1，详情页会把所有历史日志误报为已清理。
     */
    @Test
    void v84DatabaseAddsPayloadTrimmedFlagDuringV85Migration() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV84CallLogTable(jdbcTemplate);
        jdbcTemplate.execute("CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), "
                + "version REAL NOT NULL, description TEXT NOT NULL, applied_at TEXT)");
        jdbcTemplate.update("INSERT INTO schema_version (id, version, description) VALUES (1, 8.4, 'V8.4')");
        jdbcTemplate.update("INSERT INTO api_call_log (provider_key, model_name, is_stream, status_code, "
                + "request_body, chunks, duration_ms, created_at) "
                + "VALUES ('deepseek', 'chat', 1, 200, '{\"m\":1}', '[\"c\"]', 1500, '2026-07-30T14:23:07')");

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(columnNames(jdbcTemplate, "api_call_log")).contains("payload_trimmed");
        // 纯加列迁移：既有行与载荷一个不动，且被视为载荷完整
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM api_call_log", Integer.class)).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM api_call_log WHERE id = 1");
        assertThat(((Number) row.get("payload_trimmed")).intValue()).isZero();
        assertThat(row.get("request_body")).isEqualTo("{\"m\":1}");
        assertThat(row.get("chunks")).isEqualTo("[\"c\"]");
        assertThat(row.get("created_at")).isEqualTo("2026-07-30T14:23:07");
    }

    /** 迁移补出的列须与 schema.sql 同样带 0/1 约束，避免新库与升级库的约束分叉。 */
    @Test
    void migratedPayloadTrimmedColumnRejectsValuesOutsideZeroAndOne() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV84CallLogTable(jdbcTemplate);
        jdbcTemplate.execute("CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), "
                + "version REAL NOT NULL, description TEXT NOT NULL, applied_at TEXT)");
        jdbcTemplate.update("INSERT INTO schema_version (id, version, description) VALUES (1, 8.4, 'V8.4')");

        newMigrationRunner(jdbcTemplate).run(null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO api_call_log (provider_key, payload_trimmed) VALUES ('p', 2)"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void v85DatabaseAddsProtocolColumnsAndClassifiesChunkHistoryDuringV86Migration() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV85CallLogTable(jdbcTemplate);
        jdbcTemplate.execute("CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), "
                + "version REAL NOT NULL, description TEXT NOT NULL, applied_at TEXT)");
        jdbcTemplate.update("INSERT INTO schema_version (id, version, description) VALUES (1, 8.5, 'V8.5')");
        jdbcTemplate.update("INSERT INTO api_call_log (provider_key, model_name, is_stream, chunks) "
                + "VALUES ('openai-stream', 'm', 1, '[\"chunk\", \"[DONE]\"]')");
        jdbcTemplate.update("INSERT INTO api_call_log (provider_key, model_name, is_stream, chunks) "
                + "VALUES ('anthropic-stream', 'm', 1, '[\"message_start\", \"message_stop\"]')");
        jdbcTemplate.update("INSERT INTO api_call_log (provider_key, model_name, is_stream, chunks, payload_trimmed) "
                + "VALUES ('trimmed', 'm', 1, NULL, 1)");
        jdbcTemplate.update("INSERT INTO api_call_log (provider_key, model_name, is_stream, chunks) "
                + "VALUES ('non-stream', 'm', 0, NULL)");

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(columnNames(jdbcTemplate, "api_call_log"))
                .contains("downstream_protocol", "upstream_protocol");
        assertProtocols(jdbcTemplate, "openai-stream", "OPENAI");
        assertProtocols(jdbcTemplate, "anthropic-stream", "ANTHROPIC");
        // chunks 仅属于流式；NULL 也覆盖已瘦身的历史行，按既定规则统一归 OpenAI。
        assertProtocols(jdbcTemplate, "trimmed", "OPENAI");
        assertProtocols(jdbcTemplate, "non-stream", "OPENAI");
    }

    /** 迁移补出的协议列须限制在当前两种线路协议内，避免脏值进入未来翻译判断。 */
    @Test
    void migratedProtocolColumnsRejectUnknownValues() {        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV85CallLogTable(jdbcTemplate);
        jdbcTemplate.execute("CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), "
                + "version REAL NOT NULL, description TEXT NOT NULL, applied_at TEXT)");
        jdbcTemplate.update("INSERT INTO schema_version (id, version, description) VALUES (1, 8.5, 'V8.5')");

        newMigrationRunner(jdbcTemplate).run(null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO api_call_log (provider_key, downstream_protocol, upstream_protocol) "
                        + "VALUES ('p', 'UNKNOWN', 'OPENAI')"))
                .isInstanceOf(DataAccessException.class);
    }

    // ==================== V8.7：请求体规则分组 ====================

    /**
     * V8.6 库升到 V8.7：V1 规则集升为单个「仅 OPENAI」规则组，legacy 两列的内容搬进该组。
     *
     * <p>协议只给 OPENAI 而非两条都给：这些规则的字段路径是照 OpenAI 请求体写的，
     * 作用在 Anthropic 请求体上多数匹配不到 —— 静默失效比不执行更难排查。
     */
    @Test
    void v86DatabaseWrapsLegacyRuleSetIntoOpenAiRuleGroupDuringV87Migration() throws Exception {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV86RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.6);
        jdbcTemplate.update("INSERT INTO provider_request_transform (provider_id, header_rules_version, "
                        + "header_rules_json, body_template_keys_json, body_preview_json, "
                        + "body_rules_version, body_rules_json) VALUES (1, 1, '[]', ?, ?, 1, ?)",
                "[\"message-tool-image\"]", "{\"model\":\"m\"}",
                "{\"version\":1,\"rules\":[{\"id\":\"legacy-rule\",\"order\":0,\"field\":\"temperature\"}]}");

        newMigrationRunner(jdbcTemplate).run(null);

        JsonNode ruleSet = readRuleSet(jdbcTemplate, 1);
        assertThat(ruleSet.path("version").asInt()).isEqualTo(2);
        assertThat(ruleSet.path("groups")).hasSize(1);
        JsonNode group = ruleSet.path("groups").get(0);
        assertThat(group.path("protocols").toString()).isEqualTo("[\"OPENAI\"]");
        assertThat(group.path("enabled").asBoolean()).isTrue();
        assertThat(group.path("order").asInt()).isZero();
        assertThat(group.path("templateKeys").toString()).isEqualTo("[\"message-tool-image\"]");
        assertThat(group.path("previewBody").toString()).isEqualTo("{\"model\":\"m\"}");
        assertThat(group.path("rules").get(0).path("id").asText()).isEqualTo("legacy-rule");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_rules_schema FROM provider_request_transform WHERE provider_id = 1", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_rules_version FROM provider_request_transform WHERE provider_id = 1", Integer.class))
                .isEqualTo(2);
    }

    /**
     * legacy 两列在迁移后原封不动。
     *
     * <p>它们是废弃保留而非物理删除：SQLite 删列要重建表（连带外键与触发器），
     * 而保留的成本只是两个不再读取的字段，还能让旧版本回滚时读到一份有意义的样本。
     */
    @Test
    void v87MigrationLeavesLegacyEditorColumnsUntouched() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV86RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.6);
        jdbcTemplate.update("INSERT INTO provider_request_transform (provider_id, header_rules_version, "
                        + "header_rules_json, body_template_keys_json, body_preview_json, "
                        + "body_rules_version, body_rules_json) VALUES (1, 1, '[]', ?, ?, 1, ?)",
                "[\"custom\"]", "{\"kept\":true}", "{\"version\":1,\"rules\":[]}");

        newMigrationRunner(jdbcTemplate).run(null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_template_keys_json FROM provider_request_transform WHERE provider_id = 1",
                String.class)).isEqualTo("[\"custom\"]");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_preview_json FROM provider_request_transform WHERE provider_id = 1",
                String.class)).isEqualTo("{\"kept\":true}");
    }

    /**
     * 已是 V2 的行不被重写。
     *
     * <p>迁移必须幂等，且重写会打乱用户已有的组顺序与组 ID —— 组 ID 是前端列表 key，
     * 变了会让界面状态错位。
     */
    @Test
    void v87MigrationDoesNotRewriteRuleSetsThatAreAlreadyV2() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV86RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.6);
        String existing = "{\"version\":2,\"groups\":[{\"id\":\"user-group\",\"name\":\"Anthropic\","
                + "\"order\":0,\"enabled\":true,\"protocols\":[\"ANTHROPIC\"],"
                + "\"templateKeys\":[\"custom\"],\"previewBody\":{},\"rules\":[]}]}";
        jdbcTemplate.update("INSERT INTO provider_request_transform (provider_id, header_rules_version, "
                        + "header_rules_json, body_template_keys_json, body_preview_json, "
                        + "body_rules_version, body_rules_json) VALUES (1, 1, '[]', '[\"base\"]', '{}', 2, ?)",
                existing);

        newMigrationRunner(jdbcTemplate).run(null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_rules_json FROM provider_request_transform WHERE provider_id = 1",
                String.class)).isEqualTo(existing);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_rules_schema FROM provider_request_transform WHERE provider_id = 1", Integer.class))
                .isEqualTo(2);
    }

    /**
     * 无法识别的规则集被替换为空 V2 规则集而非阻断迁移。
     *
     * <p>让一行坏数据挡住整库升级是最差的选择；留着一份读不懂的 JSON 也不行 ——
     * 那会让「过一遍 V8.7 后全库同格式」这个不变量名存实亡，而下游读取点正是靠它
     * 才能把 V1 兼容当作纯粹的向后兜底。
     */
    @Test
    void v87MigrationReplacesUnparsableRuleSetWithEmptyV2() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV86RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.6);
        jdbcTemplate.update("INSERT INTO provider_request_transform (provider_id, header_rules_version, "
                + "header_rules_json, body_template_keys_json, body_preview_json, "
                + "body_rules_version, body_rules_json) VALUES (1, 1, '[]', '[\"base\"]', '{}', 1, '[]')");

        newMigrationRunner(jdbcTemplate).run(null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_rules_json FROM provider_request_transform WHERE provider_id = 1",
                String.class)).isEqualTo("{\"version\":2,\"groups\":[]}");
    }

    /**
     * {@code body_rules_schema} 是 V8.7 的结构性判定依据，因此空表也必须能判定已迁移。
     *
     * <p>若只靠「随便挑一行看它是不是 V2」，空库永远判不出已迁移，迁移每次启动都重跑。
     * 这里断言重复执行后版本号稳定在当前值，即基线判定成立。
     */
    @Test
    void v87MigrationIsIdempotentOnDatabaseWithoutAnyProvider() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV86RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.6);

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(columnNames(jdbcTemplate, "provider_request_transform")).contains("body_rules_schema");
    }

    // ==================== V8.8：供应商协议支持与 Anthropic 端点 ====================

    /**
     * V8.7 库升到 V8.8：两列补齐，存量供应商回填「两种协议都支持」且 Anthropic 端点照抄 base_url。
     *
     * <p>回填口径是本次迁移的核心判断：升级不得改变任何供应商的运行时行为。V8.8 之前
     * {@code ProviderProtocolSupport} 对所有供应商返回全集、Anthropic 端点由 base_url 拼出，
     * 所以「全集 + 照抄 base_url」正是把那份隐式行为显式化。若这里收窄成只支持 OpenAI，
     * 正在使用 Anthropic 线路的供应商会在升级瞬间全部失效。
     */
    @Test
    void v87DatabaseBackfillsBothProtocolsAndCopiesBaseUrlDuringV88Migration() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.7);
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, display_name, enabled, base_url) "
                + "VALUES ('relay', 'Relay', 1, 'https://relay.example/v1')");
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, display_name, enabled, base_url) "
                + "VALUES ('blank', 'Blank', 1, '')");

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(columnNames(jdbcTemplate, "provider_config"))
                .contains("supported_protocols", "anthropic_base_url");
        Map<String, Object> relay = jdbcTemplate.queryForMap(
                "SELECT supported_protocols, anthropic_base_url FROM provider_config WHERE provider_key = 'relay'");
        assertThat(relay.get("supported_protocols")).isEqualTo("[\"OPENAI\",\"ANTHROPIC\"]");
        assertThat(relay.get("anthropic_base_url")).isEqualTo("https://relay.example/v1");
        // base_url 本就为空的供应商没有可照抄的地址，保持空串即「回退到 base_url」，语义一致。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT anthropic_base_url FROM provider_config WHERE provider_key = 'blank'", String.class))
                .isEmpty();
    }

    /**
     * 重跑迁移不覆盖用户此后改过的协议配置。
     *
     * <p>回填条件写成「仅 NULL 或空串」而不是无条件 UPDATE，就是为了这个：{@code ADD COLUMN}
     * 已把存量行填成默认值，若回填再无条件跑一遍，任何一次重启都会把用户的选择冲掉。
     */
    @Test
    void v88BackfillDoesNotOverwriteProtocolsConfiguredAfterMigration() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.7);
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, display_name, enabled, base_url) "
                + "VALUES ('relay', 'Relay', 1, 'https://relay.example/v1')");

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        jdbcTemplate.update("UPDATE provider_config SET supported_protocols = '[\"OPENAI\"]', "
                + "anthropic_base_url = 'https://anthropic.example' WHERE provider_key = 'relay'");
        runner.run(null);

        Map<String, Object> relay = jdbcTemplate.queryForMap(
                "SELECT supported_protocols, anthropic_base_url FROM provider_config WHERE provider_key = 'relay'");
        assertThat(relay.get("supported_protocols")).isEqualTo("[\"OPENAI\"]");
        assertThat(relay.get("anthropic_base_url")).isEqualTo("https://anthropic.example");
    }

    /** 迁移补出的列须与 schema.sql 同样带 json_valid 约束，避免新库与升级库的约束分叉。 */
    @Test
    void migratedSupportedProtocolsColumnRejectsInvalidJson() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.7);

        newMigrationRunner(jdbcTemplate).run(null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_config (provider_key, display_name, enabled, base_url, supported_protocols) "
                        + "VALUES ('bad', 'Bad', 1, '', 'not-json')"))
                .isInstanceOf(DataAccessException.class);
    }

    /** 空库同样要判定成已迁移：新增列是结构性证据，与表里有没有行无关。 */
    @Test
    void v88MigrationIsIdempotentOnDatabaseWithoutAnyProvider() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.7);

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(columnNames(jdbcTemplate, "provider_config"))
                .contains("supported_protocols", "anthropic_base_url");
    }

    // ==================== V8.9：思考深度升级为档位与注入模式 ====================

    /**
     * V8.8 库升到 V8.9：四种历史形态各自收敛为规范 V2 JSON。
     *
     * <p>转换口径是本次迁移唯一值得推敲的地方，而 {@code None} 那一行是全部理由所在：
     * 旧值 {@code None} 表达的是「不向上游发送」，只有映射到 {@code delete} 才保住这个语义。
     * 若把它当作认不出的档位回退成默认（{@code medium} + {@code fallback}），这些模型会在
     * 升级后突然开始向上游发送思考深度 —— 用户没做任何操作，行为却变了，且从界面上看不出成因。
     *
     * <p>裸档位一律落 {@code fallback} 而非 {@code override}：兜底（只在下游未携带时注入）
     * 正是 V2 之前的唯一行为，因此升级对这些行是纯粹的表示形式变更。
     */
    @Test
    void v88DatabaseConvertsEveryLegacyReasoningEffortFormDuringV89Migration() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV88ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.8);
        seedLegacyReasoningEffort(jdbcTemplate, "plain", "Medium");
        seedLegacyReasoningEffort(jdbcTemplate, "multi", "High,Max");
        seedLegacyReasoningEffort(jdbcTemplate, "none", "None");
        seedLegacyReasoningEffort(jdbcTemplate, "garbage", "{\"reasoning_effort\":");

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(columnNames(jdbcTemplate, "provider_model")).contains("reasoning_effort_schema");
        assertThat(reasoningEffortOf(jdbcTemplate, "plain"))
                .isEqualTo("{\"reasoning_effort\":\"medium\",\"overwrite_mode\":\"fallback\"}");
        assertThat(reasoningEffortOf(jdbcTemplate, "multi"))
                .isEqualTo("{\"reasoning_effort\":\"high\",\"overwrite_mode\":\"fallback\"}");
        // None 的语义由 delete 承担，档位退回默认只是因为 None 本身不是档位。
        assertThat(reasoningEffortOf(jdbcTemplate, "none"))
                .isEqualTo("{\"reasoning_effort\":\"medium\",\"overwrite_mode\":\"delete\"}");
        // 一行读不懂的数据不该阻断整库升级，退默认即可。
        assertThat(reasoningEffortOf(jdbcTemplate, "garbage"))
                .isEqualTo("{\"reasoning_effort\":\"medium\",\"overwrite_mode\":\"fallback\"}");
    }

    /**
     * 已是规范 V2 的行原样不动，重跑迁移也不改写。
     *
     * <p>幂等在这里不是「跑两遍不报错」而是「跑两遍值不变」：转换是读改写而非一条 UPDATE，
     * 若解析与序列化不是互逆的，重跑就会让同一行在两种写法之间来回摆动，
     * 而这种漂移在单次运行里完全看不出来。
     */
    @Test
    void v89MigrationLeavesCanonicalValuesUntouchedAcrossReruns() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV88ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.8);
        String canonical = "{\"reasoning_effort\":\"max\",\"overwrite_mode\":\"override\"}";
        seedLegacyReasoningEffort(jdbcTemplate, "canonical", canonical);

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        assertThat(reasoningEffortOf(jdbcTemplate, "canonical")).isEqualTo(canonical);
        runner.run(null);

        assertThat(reasoningEffortOf(jdbcTemplate, "canonical")).isEqualTo(canonical);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
    }

    /**
     * 空库同样要判定成已迁移。
     *
     * <p>{@code isCurrentBaseline()} 的谓词全是结构性的（列是否存在），因为那与
     * 「数据里恰好有什么」无关、空库同样成立。若把判定改成「随便挑一行看它是不是 V2」，
     * 空表根本没有行、判定永远为假，迁移会在每次启动时重跑。
     */
    @Test
    void v89MigrationIsIdempotentOnDatabaseWithoutAnyModel() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV88ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.8);

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(columnNames(jdbcTemplate, "provider_model")).contains("reasoning_effort_schema");
    }

    /** 迁移补出的列须与 schema.sql 同样带下界约束，避免新库与升级库的约束分叉。 */
    @Test
    void migratedReasoningEffortSchemaColumnRejectsVersionBelowOne() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV88ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.8);

        newMigrationRunner(jdbcTemplate).run(null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_model (provider_id, model_name, reasoning_effort, reasoning_effort_schema) "
                        + "VALUES (1, 'bad', '{}', 0)"))
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * V5 不覆盖已存在的请求转换配置；V8.7 只把它升格而不丢内容。
     *
     * <p>两件事必须同时成立才算「用户配置被尊重」：V5 不能用默认值盖掉已有行（这是原本的断言），
     * 而 V8.7 的升格也不能把规则内容洗掉 —— 它只是把同一批规则装进一个规则组。
     * 因此这里既断言 legacy 两列原封不动，也断言规则本体在新结构里仍然找得到。
     */
    @Test
    void historicalV5MigrationDoesNotOverwriteExistingRequestTransformConfiguration() throws Exception {
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
        // V8.7 只重新包装而不改写规则本体：那条 id=saved 的规则仍在新结构的唯一一个组里。
        JsonNode upgraded = new ObjectMapper().readTree(jdbcTemplate.queryForObject(
                "SELECT body_rules_json FROM provider_request_transform WHERE provider_id = 1", String.class));
        assertThat(upgraded.path("version").asInt()).isEqualTo(2);
        assertThat(upgraded.path("groups").get(0).path("rules").get(0).path("id").asText())
                .isEqualTo("saved");
        assertThat(upgraded.path("groups").get(0).path("templateKeys").toString())
                .isEqualTo("[\"custom\"]");
        assertThat(upgraded.path("groups").get(0).path("previewBody").toString())
                .isEqualTo("{\"saved\":true}");
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

        /**
         * 建出 V8.7 时的 provider_config：既无 supported_protocols 也无 anthropic_base_url，
         * 那正是 V8.8 要补的两列。
         *
         * <p>方法名仍叫 createCurrentSchema 而不重命名成 createV87Schema：它被十几个历史版本
         * 的用例引用，而对那些用例而言这张表的形态只需「比它们新」即可，具体新到哪一版无关。
         */
        private void createCurrentSchema(JdbcTemplate jdbcTemplate) {
                jdbcTemplate.execute("CREATE TABLE provider_config (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                                                                + "provider_key TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 0, "
                                + "base_url TEXT NOT NULL DEFAULT '', updated_at TEXT)");
        }

        /** 建出 V8.8 时的 provider_config：两列协议字段已就位，是 V8.9 用例的起点。 */
        private void createV88ProviderConfigTable(JdbcTemplate jdbcTemplate) {
                createCurrentSchema(jdbcTemplate);
                jdbcTemplate.execute("ALTER TABLE provider_config ADD COLUMN supported_protocols TEXT NOT NULL "
                                + "DEFAULT '[\"OPENAI\",\"ANTHROPIC\"]' CHECK (json_valid(supported_protocols))");
                jdbcTemplate.execute("ALTER TABLE provider_config ADD COLUMN anthropic_base_url TEXT NOT NULL DEFAULT ''");
        }

        /**
         * 建出 V8.8 时的 provider_model：{@code reasoning_effort} 存的是裸档位字符串，
         * <strong>没有</strong> reasoning_effort_schema，那正是 V8.9 要补的结构性标记。
         */
        private void createV88ProviderModelTable(JdbcTemplate jdbcTemplate) {
                jdbcTemplate.execute("CREATE TABLE provider_model (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                                + "provider_id INTEGER NOT NULL, model_name TEXT NOT NULL, "
                                + "enabled INTEGER NOT NULL DEFAULT 1, context_size INTEGER NOT NULL DEFAULT 8192, "
                                + "max_output_tokens INTEGER NOT NULL DEFAULT 128000, "
                                + "caps_tools INTEGER NOT NULL DEFAULT 0, caps_vision INTEGER NOT NULL DEFAULT 0, "
                                + "reasoning_effort TEXT NOT NULL DEFAULT 'Medium', "
                                + "sort_order INTEGER NOT NULL DEFAULT 0)");
        }

        /** 插一行带指定历史形态 reasoning_effort 的模型，模型名即用例里的标签。 */
        private void seedLegacyReasoningEffort(JdbcTemplate jdbcTemplate, String modelName, String rawEffort) {
                jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name, reasoning_effort) "
                                + "VALUES (1, ?, ?)", modelName, rawEffort);
        }

        private String reasoningEffortOf(JdbcTemplate jdbcTemplate, String modelName) {
                return jdbcTemplate.queryForObject(
                                "SELECT reasoning_effort FROM provider_model WHERE model_name = ?", String.class, modelName);
        }

        /** 建出 V8.7 时的 provider_request_transform（已带 body_rules_schema）。 */
        private void createV87RequestTransformTable(JdbcTemplate jdbcTemplate) {
                createV86RequestTransformTable(jdbcTemplate);
                jdbcTemplate.execute("ALTER TABLE provider_request_transform ADD COLUMN body_rules_schema "
                                + "INTEGER NOT NULL DEFAULT 2 CHECK (body_rules_schema >= 1)");
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

        /**
         * 建出 V8.4 时的 api_call_log —— 载荷五列齐备，但<strong>没有</strong>
         * payload_trimmed 标记列，那正是 V8.5 要补的。
         */
        private void createV84CallLogTable(JdbcTemplate jdbcTemplate) {
                jdbcTemplate.execute("CREATE TABLE api_call_log ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key VARCHAR(30), model_name VARCHAR(100), "
                                + "is_stream INTEGER NOT NULL DEFAULT 0 CHECK (is_stream IN (0, 1)), status_code INTEGER, "
                                + "request_headers TEXT, request_body TEXT, response_headers TEXT, response_body TEXT, chunks TEXT, "
                                + "duration_ms INTEGER CHECK (duration_ms IS NULL OR duration_ms >= 0), "
                                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')))");
        }

        /** 建出 V8.5 时的 api_call_log：已有载荷瘦身标记，但尚无两侧协议字段。 */
        private void createV85CallLogTable(JdbcTemplate jdbcTemplate) {                jdbcTemplate.execute("CREATE TABLE api_call_log ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key VARCHAR(30), model_name VARCHAR(100), "
                                + "is_stream INTEGER NOT NULL DEFAULT 0 CHECK (is_stream IN (0, 1)), status_code INTEGER, "
                                + "request_headers TEXT, request_body TEXT, response_headers TEXT, response_body TEXT, chunks TEXT, "
                                + "duration_ms INTEGER CHECK (duration_ms IS NULL OR duration_ms >= 0), "
                                + "payload_trimmed INTEGER NOT NULL DEFAULT 0 CHECK (payload_trimmed IN (0, 1)), "
                                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')))");
        }

        private void createCurrentProviderAssociations(JdbcTemplate jdbcTemplate) {
                jdbcTemplate.execute("CREATE TABLE provider_model (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id INTEGER NOT NULL, model_name TEXT NOT NULL)");
                jdbcTemplate.execute("CREATE TABLE provider_api_key (id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id INTEGER NOT NULL)");
                jdbcTemplate.execute("CREATE TABLE provider_request_transform (provider_id INTEGER PRIMARY KEY)");
        }

        /**
         * 建出 V8.6 时的 provider_request_transform：七列齐备，但<strong>没有</strong>
         * body_rules_schema，那正是 V8.7 要补的结构性标记。
         */
        private void createV86RequestTransformTable(JdbcTemplate jdbcTemplate) {
                jdbcTemplate.execute("CREATE TABLE provider_request_transform ("
                                + "provider_id INTEGER PRIMARY KEY, "
                                + "header_rules_version INTEGER NOT NULL DEFAULT 1 CHECK (header_rules_version >= 1), "
                                + "header_rules_json TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(header_rules_json)), "
                                + "body_template_keys_json TEXT NOT NULL DEFAULT '[\"custom\"]' "
                                + "CHECK (json_valid(body_template_keys_json)), "
                                + "body_preview_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(body_preview_json)), "
                                + "body_rules_version INTEGER NOT NULL DEFAULT 1 CHECK (body_rules_version >= 1), "
                                + "body_rules_json TEXT NOT NULL DEFAULT '{\"version\":1,\"rules\":[]}' "
                                + "CHECK (json_valid(body_rules_json)), "
                                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')), "
                                + "updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')))");
        }

        /** 写入单行基线版本记录，模拟一个已升到指定版本的库。 */
        private void seedV86SchemaVersion(JdbcTemplate jdbcTemplate, double version) {
                jdbcTemplate.execute("CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), "
                                + "version REAL NOT NULL, description TEXT NOT NULL, applied_at TEXT)");
                jdbcTemplate.update("INSERT INTO schema_version (id, version, description) VALUES (1, ?, ?)",
                                version, "V" + version);
        }

        private JsonNode readRuleSet(JdbcTemplate jdbcTemplate, int providerId) throws Exception {
                return new ObjectMapper().readTree(jdbcTemplate.queryForObject(
                                "SELECT body_rules_json FROM provider_request_transform WHERE provider_id = ?",
                                String.class, providerId));
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

    private void assertProtocols(JdbcTemplate jdbcTemplate, String providerKey, String expectedProtocol) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT downstream_protocol, upstream_protocol FROM api_call_log WHERE provider_key = ?", providerKey);
        assertThat(row.get("downstream_protocol")).isEqualTo(expectedProtocol);
        assertThat(row.get("upstream_protocol")).isEqualTo(expectedProtocol);
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
        // 8.4 建索引、8.5 加列、8.6 补协议字段，两者是各自版本的持久不变量，故用字面版本号而非
                // currentSchemaVersion()：后者会随下次迁移前移，导致旧检查点漏断言。
                if (version >= 8.4) {
                        assertThat(indexExists(jdbcTemplate, "idx_api_call_usage_created")).isTrue();
                }
                if (version >= 8.5) {
                        assertThat(columnNames(jdbcTemplate, "api_call_log")).contains("payload_trimmed");
                }
                if (version >= 8.6) {
                        assertThat(columnNames(jdbcTemplate, "api_call_log"))
                                .contains("downstream_protocol", "upstream_protocol");
                }
                if (version >= 8.7) {
                        assertThat(columnNames(jdbcTemplate, "provider_request_transform"))
                                .contains("body_rules_schema");
                }
                if (version >= 8.8) {
                        assertThat(columnNames(jdbcTemplate, "provider_config"))
                                .contains("supported_protocols", "anthropic_base_url");
                }
                if (version >= 8.9) {
                        assertThat(columnNames(jdbcTemplate, "provider_model"))
                                .contains("reasoning_effort_schema");
                }
        }
}

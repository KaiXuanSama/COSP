package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.AppConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.security.ApiKeyCryptoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

@ExtendWith(OutputCaptureExtension.class)
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
                .contains("reasoning_effort", "max_output_tokens", "thinking_mode", "thinking_budget_tokens");
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
        // V8.7 当时写入的是旧协议名，V12 又把它重命名为 ["CHAT"]。本用例跑完整迁移链，
        // 故断言的是终态而非 V8.7 当时的形态。
        assertThat(legacyGroup.path("protocols").toString()).isEqualTo("[\"CHAT\"]");
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

        // V9 起 max_output_tokens 是 JSON，因此这两条测的分别是「context_size 不得为负」
        // （V3 触发器经 V9 重建后仍在）与「同供应商不得重名」（唯一索引随表重建）。
        String validMaxOutput = "{\"max_output_tokens\":4000,\"overwrite_mode\":\"fallback\"}";
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_model "
                        + "(provider_id, model_name, enabled, context_size, max_output_tokens, caps_tools, caps_vision, reasoning_effort, sort_order) "
                        + "VALUES (1, 'invalid', 1, -1, ?, 1, 0, 'Medium', 0)", validMaxOutput))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_model "
                        + "(provider_id, model_name, enabled, context_size, max_output_tokens, caps_tools, caps_vision, reasoning_effort, sort_order) "
                        + "VALUES (1, 'duplicate', 1, 1, ?, 1, 0, 'Medium', 1)", validMaxOutput))
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
                .contains("supported_protocols", "anthropic_base_url", "responses_base_url", "use_proxy",
                        "auth_header");
        assertThat(columnNames(jdbcTemplate, "provider_model")).contains("reasoning_effort_schema");
        // 新库的三个默认 JSON 须与迁移后的规范形态逐字一致，
        // 否则「全库同形态」只在升级库成立而新库不成立。
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, display_name, enabled, base_url) "
                + "VALUES ('p', 'P', 1, 'https://p.example/v1')");
        jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name) VALUES (1, 'm')");
        assertThat(reasoningEffortOf(jdbcTemplate, "m"))
                .isEqualTo("{\"reasoning_effort\":\"medium\",\"overwrite_mode\":\"fallback\"}");
        assertThat(maxOutputOf(jdbcTemplate, "m"))
                .isEqualTo("{\"max_output_tokens\":64000,\"overwrite_mode\":\"fallback\"}");
        assertThat(authHeaderOf(jdbcTemplate, "p"))
                .isEqualTo("{\"mode\":\"DOWNSTREAM\",\"header\":\"AUTHORIZATION\"}");
        // 与升级库同口径：新库建出来的供应商也必须默认直连。
        assertThat(useProxyOf(jdbcTemplate, "p")).isZero();
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
        // 断言的是跑完<strong>全部</strong>迁移后的终态，而非 V8.6 当时写入的值：
        // V8.6 填的是 OPENAI / ANTHROPIC，V12 又把它们重命名成 CHAT / MESSAGES。
        // 这不是「固定历史 fixture」（那种才该保留旧字面量），因此必须跟着 V12 走。
        assertProtocols(jdbcTemplate, "openai-stream", "CHAT");
        assertProtocols(jdbcTemplate, "anthropic-stream", "MESSAGES");
        // chunks 仅属于流式；NULL 也覆盖已瘦身的历史行，按既定规则统一归 Chat Completions。
        assertProtocols(jdbcTemplate, "trimmed", "CHAT");
        assertProtocols(jdbcTemplate, "non-stream", "CHAT");
    }

    /** 迁移补出的协议列须限制在已知线路协议内，避免脏值进入翻译判断。 */
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
                        + "VALUES ('p', 'UNKNOWN', 'CHAT')"))
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * V12 之后<strong>旧协议名本身</strong>也成了非法值。
     *
     * <p>与上一个用例互补：那个证明「随便一个词进不来」，这个证明「重命名是彻底的」。
     * 若新表的 CHECK 图省事写成五值白名单（新旧并存），迁移漏掉的行不会报错，
     * 而是安静地留在库里，直到某个读取处按新名匹配失败才浮现。
     */
    @Test
    void v12ProtocolColumnsRejectPreV12ProtocolNames() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV85CallLogTable(jdbcTemplate);
        jdbcTemplate.execute("CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), "
                + "version REAL NOT NULL, description TEXT NOT NULL, applied_at TEXT)");
        jdbcTemplate.update("INSERT INTO schema_version (id, version, description) VALUES (1, 8.5, 'V8.5')");

        newMigrationRunner(jdbcTemplate).run(null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO api_call_log (provider_key, downstream_protocol, upstream_protocol) "
                        + "VALUES ('p', 'OPENAI', 'CHAT')"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO api_call_log (provider_key, downstream_protocol, upstream_protocol) "
                        + "VALUES ('p', 'CHAT', 'ANTHROPIC')"))
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * 白名单已为 {@code RESPONSES} 留位，第二步接入时无需再重建这张日志表。
     *
     * <p>钉住这一点是因为「一次写三个值」是个容易在评审时被当作超前设计而删掉的决定：
     * 删掉它的代价不是少写一个字符串，而是将来要对一张只增不减的日志表再做一次整表重建。
     */
    @Test
    void v12ProtocolColumnsAlreadyAcceptResponsesForStepTwo() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createCurrentSchema(jdbcTemplate);
        createV85CallLogTable(jdbcTemplate);
        jdbcTemplate.execute("CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), "
                + "version REAL NOT NULL, description TEXT NOT NULL, applied_at TEXT)");
        jdbcTemplate.update("INSERT INTO schema_version (id, version, description) VALUES (1, 8.5, 'V8.5')");

        newMigrationRunner(jdbcTemplate).run(null);

        jdbcTemplate.update("INSERT INTO api_call_log "
                + "(provider_key, downstream_protocol, upstream_protocol) "
                + "VALUES ('future', 'CHAT', 'RESPONSES')");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT downstream_protocol, upstream_protocol FROM api_call_log "
                        + "WHERE provider_key = 'future'");
        assertThat(row.get("downstream_protocol")).isEqualTo("CHAT");
        assertThat(row.get("upstream_protocol")).isEqualTo("RESPONSES");
    }

    /**
     * V11 库升到 V12：协议字面量在<strong>三处</strong>同时改名，且重复执行结果不变。
     *
     * <p>这是 V12 的主用例。三处必须一起断言，因为它们的失败形态各不相同、都不响：
     * <ul>
     *   <li>日志列漏改 → 旧值撞新 CHECK，升级直接回滚（这个反而最容易发现）；</li>
     *   <li>{@code supported_protocols} 漏改 → {@code ProviderProtocolSupport} 忽略未知名
     *       后回退全集，功能表面正常但用户的协议配置已失效；</li>
     *   <li>{@code body_rules_json} 漏改 → 规则引擎跳过认不出协议的规则组，
     *       用户配的请求体改写<strong>静默不执行</strong>。</li>
     * </ul>
     *
     * <p>顺带钉住重建的两个副作用：索引必须还在（{@code DROP TABLE} 会带走它们），
     * 行数据必须完整搬迁（含 NULL 与已瘦身的行）。
     */
    @Test
    void v11DatabaseRenamesProtocolLiteralsEverywhereDuringV12Migration() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        // 显式钉住 V11：本用例要写入 V12 之前的旧协议名，而那些字面量在 V12 之后
        // 就是非法值了（新表的 CHECK 白名单只收新名）。用自动前移的 fixture 会让它在
        // V13 加入后直接报 CHECK 失败 —— 对“需要某个特定版本形态”的用例，版本必须写死。
        seedDatabaseAtVersion(jdbcTemplate, 11);
        // 三处存量数据，全部使用 V12 之前的协议名。
        jdbcTemplate.update("INSERT INTO api_call_log "
                + "(provider_key, model_name, is_stream, downstream_protocol, upstream_protocol, "
                + "status_code, duration_ms, payload_trimmed) "
                + "VALUES ('direct', 'gpt-4o', 1, 'OPENAI', 'OPENAI', 200, 1200, 0)");
        jdbcTemplate.update("INSERT INTO api_call_log "
                + "(provider_key, model_name, is_stream, downstream_protocol, upstream_protocol, "
                + "status_code, duration_ms, payload_trimmed) "
                + "VALUES ('translated', 'claude', 1, 'OPENAI', 'ANTHROPIC', 200, 3400, 1)");
        jdbcTemplate.update("INSERT INTO provider_config "
                + "(provider_key, display_name, base_url, supported_protocols) "
                + "VALUES ('relay', '中转站', 'https://relay.example.com/v1', ?)",
                "[\"CHAT\",\"MESSAGES\"]");
        jdbcTemplate.update("INSERT INTO provider_config "
                + "(provider_key, display_name, base_url, supported_protocols) "
                + "VALUES ('claude-only', '仅 Anthropic', 'https://c.example.com', ?)",
                "[\"MESSAGES\"]");
        int providerId = jdbcTemplate.queryForObject(
                "SELECT id FROM provider_config WHERE provider_key = 'relay'", Integer.class);
        jdbcTemplate.update("INSERT INTO provider_request_transform (provider_id, body_rules_json) "
                + "VALUES (?, ?)", providerId,
                "{\"version\":2,\"groups\":[{\"id\":\"g1\",\"protocols\":[\"CHAT\"],\"rules\":[]},"
                        + "{\"id\":\"g2\",\"protocols\":[\"MESSAGES\"],\"rules\":[]}]}");

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());

        // ① 日志列：直连与跨协议两种组合都要正确重映射。
        assertProtocols(jdbcTemplate, "direct", "CHAT");
        assertProtocols(jdbcTemplate, "translated", "CHAT", "MESSAGES");
        // 重建搬迁不得丢列值：payload_trimmed 与 duration_ms 是逐列显式列出的证据 ——
        // 若写成 SELECT *，列错位后仍满足每条约束（都是 INTEGER），不会报错。
        Map<String, Object> trimmedRow = jdbcTemplate.queryForMap(
                "SELECT duration_ms, payload_trimmed, status_code FROM api_call_log "
                        + "WHERE provider_key = 'translated'");
        assertThat(((Number) trimmedRow.get("duration_ms")).intValue()).isEqualTo(3400);
        assertThat(((Number) trimmedRow.get("payload_trimmed")).intValue()).isEqualTo(1);
        assertThat(((Number) trimmedRow.get("status_code")).intValue()).isEqualTo(200);

        // ② 供应商协议集合：全集与单协议都要改，且元素顺序不变。
        // 尾部的 RESPONSES 是紧随其后的 V13 追加的 —— 本用例从 V11 一路跑到当前版本，
        // 断言的是终态。V12 的职责（重命名）体现在前面那些元素上：只要 OPENAI / ANTHROPIC
        // 一个不剩，重命名就是彻底的。
        assertThat(protocolsOf(jdbcTemplate, "relay"))
                .isEqualTo("[\"CHAT\",\"MESSAGES\",\"RESPONSES\"]");
        assertThat(protocolsOf(jdbcTemplate, "claude-only"))
                .isEqualTo("[\"MESSAGES\",\"RESPONSES\"]");

        // ③ 规则组的适用协议：藏在 JSON 里，是最容易漏的一处。
        // 既断言新名到位，也断言旧名一个不剩 —— 只查前者的话，「只改了第一个组」这种
        // 漏改会照样通过。
        String rules = jdbcTemplate.queryForObject(
                "SELECT body_rules_json FROM provider_request_transform WHERE provider_id = ?",
                String.class, providerId);
        assertThat(rules).contains("[\"CHAT\"]").contains("[\"MESSAGES\"]")
                .doesNotContain("OPENAI").doesNotContain("ANTHROPIC");

        // 重建会带走索引，必须显式重建 —— 少了它们日志分页会退化成全表扫描。
        assertThat(indexExists(jdbcTemplate, "idx_api_call_log_created_id")).isTrue();
        assertThat(indexExists(jdbcTemplate, "idx_api_call_log_provider_created_id")).isTrue();
    }

    // ==================== V13：Responses 端点与协议支持 ====================

    /**
     * V12 库升到 V13：新列到位、回填 {@code base_url}，且协议集合追加 {@code RESPONSES}。
     *
     * <p>这是 V13 的主用例，覆盖 §3.3 划定的三类值 —— 它们的失败形态各不相同：
     * <ul>
     *   <li>非空数组漏追加 → Responses 线路默认不可用，用户要自己想到去勾（本次要避免的正是这个）；</li>
     *   <li><strong>空数组被追加</strong> → 一个被用户主动禁用的供应商变成部分可用，
     *       而用户不知道配置被改了；</li>
     *   <li>脏数据被改 → 迁移在猜一个读不懂的配置想表达什么。</li>
     * </ul>
     *
     * <p>同时断言重复执行结果不变：{@code NOT EXISTS} 子句是幂等性的唯一保障，
     * 少了它每次重启都会追加一个新的 RESPONSES 元素。
     */
    @Test
    void v12DatabaseAddsResponsesEndpointAndProtocolDuringV13Migration() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        // 显式钉住 V12（V13 的前一版）：本用例测的是 V12 → V13 这一步。若用会自动前移的
        // seedDatabaseAtPreviousVersion，V14 一加入它就指向 V13，追加逻辑便不再执行 ——
        // 对「需要某个特定版本形态」的用例，版本必须写死（V12 的主用例当年也是这么钉到 11 的）。
        seedDatabaseAtVersion(jdbcTemplate, 12);
        jdbcTemplate.update("INSERT INTO provider_config "
                + "(provider_key, display_name, base_url, supported_protocols) "
                + "VALUES ('relay', '中转站', 'https://relay.example.com/v1', ?)",
                "[\"CHAT\",\"MESSAGES\"]");
        jdbcTemplate.update("INSERT INTO provider_config "
                + "(provider_key, display_name, base_url, supported_protocols) "
                + "VALUES ('chat-only', '仅 Chat', 'https://c.example.com', ?)",
                "[\"CHAT\"]");
        jdbcTemplate.update("INSERT INTO provider_config "
                + "(provider_key, display_name, base_url, supported_protocols) "
                + "VALUES ('disabled-all', '全部禁用', 'https://d.example.com', ?)",
                "[]");
        jdbcTemplate.update("INSERT INTO provider_config "
                + "(provider_key, display_name, base_url, supported_protocols) "
                + "VALUES ('blank-url', '无地址', '', ?)",
                "[\"CHAT\"]");

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(columnNames(jdbcTemplate, "provider_config")).contains("responses_base_url");

        // ① 非空数组：追加到末尾。恰好保持字母序（R 排在 C、M 之后）。
        assertThat(protocolsOf(jdbcTemplate, "relay")).isEqualTo("[\"CHAT\",\"MESSAGES\",\"RESPONSES\"]");
        assertThat(protocolsOf(jdbcTemplate, "chat-only")).isEqualTo("[\"CHAT\",\"RESPONSES\"]");

        // ② 显式空数组：一个字符都不许变。用 isEqualTo 而非 doesNotContain("RESPONSES") ——
        // 后者在 [] 被改成 ["CHAT"] 这种错误下仍然通过，钉不住「不改用户意图」。
        assertThat(protocolsOf(jdbcTemplate, "disabled-all")).isEqualTo("[]");

        // ③ 端点回填：照抄 base_url 原值；base_url 为空时保持空串（空串即回退到 base_url）。
        assertThat(responsesBaseUrlOf(jdbcTemplate, "relay")).isEqualTo("https://relay.example.com/v1");
        assertThat(responsesBaseUrlOf(jdbcTemplate, "blank-url")).isEmpty();
    }

    /**
     * 脏协议配置不被 V13 触碰。
     *
     * <p>运行时对读不懂的配置本就回退全集（届时已含 RESPONSES），因此迁移没有必要去猜；
     * 而一旦去猜，一个畸形值会被悄悄改成某个看起来正常的形态，掩盖掉「这里的配置是坏的」
     * 这个真正需要被发现的事实。
     *
     * <p>非数组 JSON 单独造一行：{@code json_valid} 对 {@code "CHAT"} 这种裸字符串为真，
     * 只判 {@code json_valid} 会让 {@code json_insert} 把它变成一个语义全新的对象。
     * 这正是实现里要额外判 {@code json_type = 'array'} 的原因。
     */
    @Test
    void v13LeavesMalformedProtocolConfigurationUntouched() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        // 钉住 V12：理由见 v12DatabaseAddsResponsesEndpointAndProtocolDuringV13Migration。
        // 不写死的话 V14 加入后 fixture 前移到 V13，本用例就不再覆盖 V13 的行为。
        seedDatabaseAtVersion(jdbcTemplate, 12);
        // 合法 JSON 但不是数组：json_valid 为真，唯有 json_type 能把它挡住。
        jdbcTemplate.update("INSERT INTO provider_config "
                + "(provider_key, display_name, base_url, supported_protocols) "
                + "VALUES ('not-array', '非数组', 'https://n.example.com', ?)",
                "\"CHAT\"");
        jdbcTemplate.update("INSERT INTO provider_config "
                + "(provider_key, display_name, base_url, supported_protocols) "
                + "VALUES ('json-object', '对象形态', 'https://o.example.com', ?)",
                "{\"protocols\":[\"CHAT\"]}");

        newMigrationRunner(jdbcTemplate).run(null);

        assertThat(protocolsOf(jdbcTemplate, "not-array")).isEqualTo("\"CHAT\"");
        assertThat(protocolsOf(jdbcTemplate, "json-object")).isEqualTo("{\"protocols\":[\"CHAT\"]}");
    }

    /**
     * 已含 {@code RESPONSES} 的行不重复追加，且用户此后改过的配置不被冲掉。
     *
     * <p>两件事一起测是因为它们由同一个 {@code NOT EXISTS} 子句保证：
     * 少了它，重启一次就多一个 RESPONSES 元素，而那个数组会无限增长。
     */
    @Test
    void v13DoesNotDuplicateProtocolOrOverwriteLaterEdits() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        // 钉住 V12：理由见 v12DatabaseAddsResponsesEndpointAndProtocolDuringV13Migration。
        seedDatabaseAtVersion(jdbcTemplate, 12);
        jdbcTemplate.update("INSERT INTO provider_config "
                + "(provider_key, display_name, base_url, supported_protocols) "
                + "VALUES ('relay', '中转站', 'https://relay.example.com/v1', ?)",
                "[\"CHAT\"]");

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        // 用户升级后自己取消了 Responses 并改了端点 —— 重跑迁移不得把这两处改回去。
        jdbcTemplate.update("UPDATE provider_config SET supported_protocols = '[\"CHAT\"]', "
                + "responses_base_url = 'https://responses.example' WHERE provider_key = 'relay'");
        runner.run(null);

        assertThat(protocolsOf(jdbcTemplate, "relay")).isEqualTo("[\"CHAT\"]");
        assertThat(responsesBaseUrlOf(jdbcTemplate, "relay")).isEqualTo("https://responses.example");
    }

    /**
     * V13 的 UPDATE 不被 {@code provider_config} 的行级校验触发器挡住。
     *
     * <p>那两个触发器校验 {@code NEW} 整行而非只校验被 SET 的列，因此库里留着
     * {@code enabled = 2} 这类 V3 之前的脏值时，本次只改协议集合的 UPDATE 也会被 ABORT。
     * V7.1、V9 与 V12 都踩过同一个坑，这条用例把 V13 也钉住。
     *
     * <p>顺带断言脏值本身没被「顺手修正」：那超出本次迁移的职责，
     * 且会静默改变某个供应商的启用状态。
     */
    @Test
    void v13SurvivesRowLevelValidationTriggerWithLegacyDirtyEnabledValue() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        // 钉住 V12：理由见 v12DatabaseAddsResponsesEndpointAndProtocolDuringV13Migration。
        // 这条尤其不能靠自动前移 —— 它断言的正是 V13 的追加结果，前移后会直接报断言失败。
        seedDatabaseAtVersion(jdbcTemplate, 12);
        jdbcTemplate.update("INSERT INTO provider_config "
                + "(provider_key, display_name, base_url, supported_protocols) "
                + "VALUES ('dirty', '脏值', 'https://dirty.example.com', ?)",
                "[\"CHAT\"]");
        // 绕过触发器写入脏值，模拟某些升级路径上 V3 数据清洗被跳过的库。
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_provider_config_validate_update");
        jdbcTemplate.update("UPDATE provider_config SET enabled = 2 WHERE provider_key = 'dirty'");
        jdbcTemplate.execute("CREATE TRIGGER trg_provider_config_validate_update "
                + "BEFORE UPDATE ON provider_config WHEN NEW.enabled NOT IN (0, 1) "
                + "BEGIN SELECT RAISE(ABORT, '数据约束校验失败: provider_config'); END");

        newMigrationRunner(jdbcTemplate).run(null);

        assertThat(protocolsOf(jdbcTemplate, "dirty")).isEqualTo("[\"CHAT\",\"RESPONSES\"]");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT enabled FROM provider_config WHERE provider_key = 'dirty'", Integer.class))
                .isEqualTo(2);
        // 触发器必须装回去，否则其余字段的校验会静默失效。
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE provider_config SET enabled = 3 WHERE provider_key = 'dirty'"))
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * 新库的协议默认值与端点列须与升级库逐字一致，否则「全库同形态」只在一侧成立。
     *
     * <p>方法名不写版本号：它比较的是「新库」与「升级库」这两种<strong>形态</strong>，
     * 每加一版都会继续成立，带版本号的名字只会一次次过时。
     */
    @Test
    void freshSchemaDefaultsMatchUpgradedShape() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"))
                .execute(jdbcTemplate.getDataSource());

        newMigrationRunner(jdbcTemplate).run(null);

        jdbcTemplate.update("INSERT INTO provider_config (provider_key, display_name, enabled, base_url) "
                + "VALUES ('fresh', 'Fresh', 1, 'https://fresh.example/v1')");
        assertThat(protocolsOf(jdbcTemplate, "fresh")).isEqualTo("[\"CHAT\",\"MESSAGES\",\"RESPONSES\"]");
        // 新库不回填：DDL 默认空串，语义即「回退到 base_url」，与升级库留空的那些行一致。
        assertThat(responsesBaseUrlOf(jdbcTemplate, "fresh")).isEmpty();
        // 鉴权头方式的默认值同样必须两边逐字相同 —— 这里是 schema.sql 的 DEFAULT
        // 与 V14 迁移里那个 DEFAULT_AUTH_HEADER_JSON 唯一的对照点。
        assertThat(authHeaderOf(jdbcTemplate, "fresh"))
                .isEqualTo("{\"mode\":\"DOWNSTREAM\",\"header\":\"AUTHORIZATION\"}");
    }

    // ==================== V14：出站鉴权头装配方式 ====================

    /**
     * V13 库升到 V14：新列到位，且<strong>存量行由 DDL 默认值自动填充</strong>。
     *
     * <p>本迁移没有回填 UPDATE —— {@code NOT NULL DEFAULT} 让 SQLite 自己填。这条用例断言的
     * 正是那个假设：默认值若缺失或写成空串，存量供应商的鉴权头配置会落进一个既非「取下游」
     * 也非「取设置」的形态，而运行时只能靠兜底猜。
     *
     * <p>同时断言重复执行结果不变：值既不该被重跑改写，也不该被追加成两份。
     */
    @Test
    void v13DatabaseAddsAuthHeaderColumnDefaultingToDownstreamAuthorization() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        seedDatabaseAtPreviousVersion(jdbcTemplate);

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(columnNames(jdbcTemplate, "provider_config")).contains("auth_header");
        // legacy 是 fixture 里迁移之前就存在的行，它被填上默认值即证明 ADD COLUMN 的回填生效。
        assertThat(authHeaderOf(jdbcTemplate, "legacy"))
                .isEqualTo("{\"mode\":\"DOWNSTREAM\",\"header\":\"AUTHORIZATION\"}");
    }

    /**
     * 用户改过的鉴权头配置在重跑时不被冲掉。
     *
     * <p>与 V11 的 {@code use_proxy} 同一条不变量：迁移只在列<strong>不存在</strong>时动手，
     * 值一旦在了，每次重启都不该再看它一眼。
     */
    @Test
    void v14RerunPreservesUserConfiguredAuthHeader() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        seedDatabaseAtPreviousVersion(jdbcTemplate);
        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);

        jdbcTemplate.update("UPDATE provider_config SET auth_header = ? WHERE provider_key = 'legacy'",
                "{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}");
        runner.run(null);

        assertThat(authHeaderOf(jdbcTemplate, "legacy"))
                .isEqualTo("{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}");
    }

    /**
     * 新列的 {@code json_valid} 约束真的生效。
     *
     * <p>与 V11 的 {@code use_proxy IN (0, 1)} 同一目的：列加上了不等于约束加上了。
     * {@code ADD COLUMN} 里把定义写错（比如漏掉 CHECK）不会有任何报错，
     * 直到某个非法值真的落库。
     *
     * <p>探针行用干净的 {@code enabled = 1} 新建，而不是复用 legacy —— 后者可能带着
     * {@code enabled = 2} 这类历史脏值，那样 UPDATE 会被<strong>行级校验触发器</strong>拦下，
     * 于是「拒绝」发生了却与 {@code json_valid} 无关，用例通过而什么都没验到。
     */
    @Test
    void v14AuthHeaderColumnRejectsInvalidJson() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        seedDatabaseAtPreviousVersion(jdbcTemplate);
        newMigrationRunner(jdbcTemplate).run(null);
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, display_name, enabled, base_url) "
                + "VALUES ('probe', 'Probe', 1, 'https://probe.example.com')");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE provider_config SET auth_header = 'not-json' WHERE provider_key = 'probe'"))
                .isInstanceOf(DataAccessException.class);

        // 合法值放行，证明上面那条拒绝来自 json_valid 而非别的约束。
        jdbcTemplate.update("UPDATE provider_config SET auth_header = ? WHERE provider_key = 'probe'",
                "{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}");
        assertThat(authHeaderOf(jdbcTemplate, "probe"))
                .isEqualTo("{\"mode\":\"CONFIGURED\",\"header\":\"X_API_KEY\"}");
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
        // V8.7 写入的是旧协议名，V12 重命名为 ["CHAT"]；本用例跑完整链，断言终态。
        assertThat(group.path("protocols").toString()).isEqualTo("[\"CHAT\"]");
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
                + "\"order\":0,\"enabled\":true,\"protocols\":[\"MESSAGES\"],"
                + "\"templateKeys\":[\"custom\"],\"previewBody\":{},\"rules\":[]}]}";
        jdbcTemplate.update("INSERT INTO provider_request_transform (provider_id, header_rules_version, "
                        + "header_rules_json, body_template_keys_json, body_preview_json, "
                        + "body_rules_version, body_rules_json) VALUES (1, 1, '[]', '[\"base\"]', '{}', 2, ?)",
                existing);

        newMigrationRunner(jdbcTemplate).run(null);

        // V12 会把组里的协议字面量重命名（ANTHROPIC → MESSAGES），那是它的职责。
        // 本用例要证明的是 V8.7 不重写结构：组 ID、名称、顺序、templateKeys 全都原封不动，
        // 因此拿「只替换协议名」的期望串做整串比较 —— 若 V8.7 重新包装过，
        // 组 ID 会变成 group-legacy-openai，这个断言就会失败。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_rules_json FROM provider_request_transform WHERE provider_id = 1",
                String.class)).isEqualTo(existing.replace("MESSAGES", "MESSAGES"));
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
        // V8.8 回填的是当时的全集，V12 重命名了两个元素，V13 又追加了 RESPONSES。
        // 本用例跑完整迁移链，故断言的是终态；「回填全集」这个语义本身没变 ——
        // 变的只是全集包含哪几个元素、怎么写。
        assertThat(relay.get("supported_protocols")).isEqualTo("[\"CHAT\",\"MESSAGES\",\"RESPONSES\"]");
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
        jdbcTemplate.update("UPDATE provider_config SET supported_protocols = '[\"CHAT\"]', "
                + "anthropic_base_url = 'https://anthropic.example' WHERE provider_key = 'relay'");
        runner.run(null);

        Map<String, Object> relay = jdbcTemplate.queryForMap(
                "SELECT supported_protocols, anthropic_base_url FROM provider_config WHERE provider_key = 'relay'");
        assertThat(relay.get("supported_protocols")).isEqualTo("[\"CHAT\"]");
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
     * 一张模型都没有的库也要能升到当前版本并保持幂等。
     *
     * <p>V8.9 逐行重写 {@code reasoning_effort}，没有行可写时不能因此半途而废 ——
     * 版本号该照常前移，结构标记列该照常补出。第二次 {@code run} 验证不会重复动作。
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

    // ==================== V9：最大输出升级为上限与注入模式 ====================

    /**
     * V8.9 库升到 V9：裸整数转为 V9 JSON，档位按新预设重映射。
     *
     * <p>重映射口径是本次迁移唯一需要推敲的地方，而 128K 那一行是全部理由所在：
     * 它<strong>既是</strong>「降为 4K」的源，<strong>又是</strong>「256K/512K 降过来」的靶。
     * 若写成两条顺序 UPDATE，原本选 512K 的模型会先变 128000 再变 4000 —— 一路滴到 4K，
     * 而用户以为自己只是从超大档退到了标准档。同时映射让两条规则各自独立。
     *
     * <p>4096 与 8192 是二进制值，不在十进制预设表里，属于「非标值」：原样保留。
     * 这一条钉住的是「迁移不擅自改用户手填的数」——
     * 只有恰好落在旧预设上的值才被视为「选过某个档位」，其余都是明确的自定义。
     */
    @Test
    void v89DatabaseConvertsMaxOutputAndRemapsPresetsDuringV9Migration() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV89ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.9);
        seedMaxOutput(jdbcTemplate, "was-128k", 128000);
        seedMaxOutput(jdbcTemplate, "was-256k", 256000);
        seedMaxOutput(jdbcTemplate, "was-512k", 512000);
        seedMaxOutput(jdbcTemplate, "was-64k", 64000);
        seedMaxOutput(jdbcTemplate, "binary-4k", 4096);
        seedMaxOutput(jdbcTemplate, "binary-8k", 8192);
        seedMaxOutput(jdbcTemplate, "odd", 12345);

        newMigrationRunner(jdbcTemplate).run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        // 两条重映射规则各自成立，互不影响。
        assertThat(maxOutputTokensOf(jdbcTemplate, "was-128k")).isEqualTo(4000);
        assertThat(maxOutputTokensOf(jdbcTemplate, "was-256k")).isEqualTo(128000);
        assertThat(maxOutputTokensOf(jdbcTemplate, "was-512k")).isEqualTo(128000);
        // 64K 仍是标准预设，不在重映射表里。
        assertThat(maxOutputTokensOf(jdbcTemplate, "was-64k")).isEqualTo(64000);
        // 非标值原样保留。
        assertThat(maxOutputTokensOf(jdbcTemplate, "binary-4k")).isEqualTo(4096);
        assertThat(maxOutputTokensOf(jdbcTemplate, "binary-8k")).isEqualTo(8192);
        assertThat(maxOutputTokensOf(jdbcTemplate, "odd")).isEqualTo(12345);
        // 全部行都落到兜底模式：V9 之前这个值压根没进过请求体，兜底最接近「什么都没变」。
        assertThat(maxOutputOf(jdbcTemplate, "was-64k"))
                .isEqualTo("{\"max_output_tokens\":64000,\"overwrite_mode\":\"fallback\"}");
    }

    /**
     * 重跑 V9 不会把已经降过档的值再降一次。
     *
     * <p>这是本迁移最容易写错的地方：重映射表把 128000 映到 4000，而首次迁移的结果里
     * 恰好会出现 {@code max_output_tokens: 128000}（原 256K/512K 降过来的）。
     * 若转换逻辑对已是 JSON 的行也套用重映射，第二次运行就会把它们再降成 4000 ——
     * 一个只在「跑两遍」时才显形的缺陷，单次运行的断言完全看不到。
     */
    @Test
    void v9MigrationDoesNotRemapAlreadyConvertedValuesOnRerun() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV89ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.9);
        seedMaxOutput(jdbcTemplate, "was-512k", 512000);
        seedMaxOutput(jdbcTemplate, "was-128k", 128000);

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        assertThat(maxOutputTokensOf(jdbcTemplate, "was-512k")).isEqualTo(128000);
        assertThat(maxOutputTokensOf(jdbcTemplate, "was-128k")).isEqualTo(4000);

        runner.run(null);

        // 第二遍必须一字不变 —— 128000 不再被当作「选了 128K 档」而降到 4000。
        assertThat(maxOutputTokensOf(jdbcTemplate, "was-512k")).isEqualTo(128000);
        assertThat(maxOutputTokensOf(jdbcTemplate, "was-128k")).isEqualTo(4000);
    }

    /**
     * V9 重建表后列约束换成 json_valid，且其余字段的校验触发器仍然有效。
     *
     * <p>后半句是重建表的主要风险：V3 建的两个校验触发器绑在表名上，
     * {@code DROP TABLE} 会连带删掉它们且<strong>不报错</strong>。
     * 若重建流程忘了把它们建回来，`enabled`、`caps_tools` 等字段的校验会静默失效 ——
     * 没有任何现象，直到某天一个非法值进了库。
     */
    @Test
    void v9RebuiltTableEnforcesJsonAndKeepsOtherValidations() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV89ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.9);

        newMigrationRunner(jdbcTemplate).run(null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_model (provider_id, model_name, max_output_tokens) "
                        + "VALUES (1, 'bad-json', 'not-json')"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_model (provider_id, model_name, caps_tools) "
                        + "VALUES (1, 'bad-caps', 7)"))
                .isInstanceOf(DataAccessException.class);
        // 唯一索引也必须随表重建，否则同名模型会重复入库。
        jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name) VALUES (1, 'dup')");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_model (provider_id, model_name) VALUES (1, 'dup')"))
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * 重建表必须保住其余列的数据与主键。
     *
     * <p>{@code INSERT ... SELECT} 逐列显式列出而非 {@code SELECT *}：这张表历经多次
     * {@code ADD COLUMN}，物理列顺序不可假定，而 {@code SELECT *} 按顺序对位。
     * 一旦顺序与新表不一致，数据会串列 —— 而串列后的值往往仍能通过各自的约束。
     */
    @Test
    void v9RebuildPreservesEveryOtherColumn() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV89ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.9);
        jdbcTemplate.update("INSERT INTO provider_model (id, provider_id, model_name, enabled, context_size, "
                + "max_output_tokens, caps_tools, caps_vision, reasoning_effort, sort_order) "
                + "VALUES (77, 5, 'keep-me', 0, 32768, 64000, 1, 1, ?, 3)",
                "{\"reasoning_effort\":\"high\",\"overwrite_mode\":\"override\"}");

        newMigrationRunner(jdbcTemplate).run(null);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM provider_model WHERE model_name = 'keep-me'");
        assertThat(((Number) row.get("id")).intValue()).isEqualTo(77);
        assertThat(((Number) row.get("provider_id")).intValue()).isEqualTo(5);
        assertThat(((Number) row.get("enabled")).intValue()).isZero();
        assertThat(((Number) row.get("context_size")).intValue()).isEqualTo(32768);
        assertThat(((Number) row.get("caps_tools")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("caps_vision")).intValue()).isEqualTo(1);
        assertThat(row.get("reasoning_effort"))
                .isEqualTo("{\"reasoning_effort\":\"high\",\"overwrite_mode\":\"override\"}");
        assertThat(((Number) row.get("sort_order")).intValue()).isEqualTo(3);
        assertThat(maxOutputTokensOf(jdbcTemplate, "keep-me")).isEqualTo(64000);
    }

    /** 空库同样要判定成已迁移：重建表是结构变更，与表里有没有行无关。 */
    @Test
    void v9MigrationIsIdempotentOnDatabaseWithoutAnyModel() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV89ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 8.9);

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        runner.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
    }

    // ==================== V10：Anthropic 思考方式与预算 ====================

    /**
     * V9 库升级到 V10 后新增两列，且存量行拿到的默认值<strong>就是</strong>
     * V10 之前那段硬编码的行为。
     *
     * <p>这一条是本迁移的核心断言：默认值必须是 {@code adaptive + fallback}，
     * 因为升级前 {@code GenericAnthropicChatService} 无条件「下游没带 thinking 就补 adaptive」，
     * 那正是兜底档的定义。若默认写成覆写或透传，存量供应商的出站请求体会在升级瞬间改变 ——
     * 迁移不得改变运行时行为。
     */
    @Test
    void v9DatabaseGainsThinkingColumnsWithBehaviourPreservingDefaults() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV9ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 9);
        jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name) VALUES (1, 'existing')");

        newMigrationRunner(jdbcTemplate).run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(columnNames(jdbcTemplate, "provider_model"))
                .contains("thinking_mode", "thinking_budget_tokens");
        // ADD COLUMN ... NOT NULL DEFAULT 会把默认值应用到所有已有行，无需回填 UPDATE。
        assertThat(thinkingModeOf(jdbcTemplate, "existing"))
                .isEqualTo("{\"thinking_type\":\"adaptive\",\"overwrite_mode\":\"fallback\"}");
        assertThat(thinkingBudgetOf(jdbcTemplate, "existing")).isEqualTo(-1);
    }

    /**
     * 新增列带 CHECK 约束：{@code thinking_mode} 必须是合法 JSON，
     * 预算只放行正数与哨兵 -1。
     *
     * <p>预算那条约束刻意不写成 {@code >= -1}：只有 -1 一个负值有约定含义
     * （未设置），0 与 -2 之类都是脏数据，不该被放进来再让读取侧去猜。
     */
    @Test
    void v10ColumnsRejectInvalidJsonAndOutOfContractBudgets() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV9ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 9);

        newMigrationRunner(jdbcTemplate).run(null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_model (provider_id, model_name, thinking_mode) "
                        + "VALUES (1, 'bad-thinking-json', 'not-json')"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_model (provider_id, model_name, thinking_budget_tokens) "
                        + "VALUES (1, 'zero-budget', 0)"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_model (provider_id, model_name, thinking_budget_tokens) "
                        + "VALUES (1, 'negative-budget', -2)"))
                .isInstanceOf(DataAccessException.class);
        // 正数与哨兵都必须放行。
        jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name, thinking_budget_tokens) "
                + "VALUES (1, 'real-budget', 8192)");
        jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name, thinking_budget_tokens) "
                + "VALUES (1, 'unset-budget', -1)");
        assertThat(thinkingBudgetOf(jdbcTemplate, "real-budget")).isEqualTo(8192);
        assertThat(thinkingBudgetOf(jdbcTemplate, "unset-budget")).isEqualTo(-1);
    }

    /**
     * 重跑 V10 不改动用户已经配好的值。
     *
     * <p>{@code addColumnIfNotExists} 让 DDL 幂等，但真正要钉的是「没有回填 UPDATE」——
     * 若迁移体里写了一条无条件的 {@code UPDATE ... SET thinking_mode = 默认值}，
     * 第二次运行会把用户配的覆写档打回兜底档，而这个缺陷只在跑两遍时显形。
     */
    @Test
    void v10MigrationRerunPreservesUserConfiguredThinking() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV9ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 9);
        jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name) VALUES (1, 'configured')");

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        // 模拟用户在界面上改成「覆写 + 手动预算」。
        jdbcTemplate.update("UPDATE provider_model SET thinking_mode = ?, thinking_budget_tokens = ? "
                + "WHERE model_name = 'configured'",
                "{\"thinking_type\":\"enabled\",\"overwrite_mode\":\"override\"}", 16384);

        runner.run(null);

        assertThat(thinkingModeOf(jdbcTemplate, "configured"))
                .isEqualTo("{\"thinking_type\":\"enabled\",\"overwrite_mode\":\"override\"}");
        assertThat(thinkingBudgetOf(jdbcTemplate, "configured")).isEqualTo(16384);
    }

    /**
     * 新增列不影响 V9 重建时建起来的校验触发器与唯一索引。
     *
     * <p>V10 用 {@code ADD COLUMN} 而非重建表，因此本来就不该碰到它们 ——
     * 但这一条把「没碰到」变成断言，避免将来有人把 V10 改成重建流程时
     * 悄悄丢掉触发器（那正是 V9 踩过的坑，且 {@code DROP TABLE} 删触发器不报错）。
     */
    @Test
    void v10DoesNotDisturbExistingValidationsWhenAddingColumns() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV9ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 9);

        newMigrationRunner(jdbcTemplate).run(null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_model (provider_id, model_name, caps_tools) VALUES (1, 'bad-caps', 7)"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_model (provider_id, model_name, max_output_tokens) "
                        + "VALUES (1, 'bad-json', 'not-json')"))
                .isInstanceOf(DataAccessException.class);
        jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name) VALUES (1, 'dup')");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_model (provider_id, model_name) VALUES (1, 'dup')"))
                .isInstanceOf(DataAccessException.class);
    }

    // ==================== V11：供应商出站代理开关 ====================

    /**
     * V10 库升级到 V11 后新增 {@code use_proxy}，且存量供应商默认<strong>不</strong>走代理。
     *
     * <p>默认 0 是本迁移的核心断言：代理开关是需要用户显式选择的能力，默认开启会让升级瞬间
     * 所有出站流量改道 —— 迁移不得改变运行时行为。
     */
    @Test
    void v10DatabaseGainsUseProxyColumnDefaultingToDirect() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV9ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 10);
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, base_url) VALUES ('existing', 'https://a.test')");

        newMigrationRunner(jdbcTemplate).run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        assertThat(columnNames(jdbcTemplate, "provider_config")).contains("use_proxy");
        // ADD COLUMN ... NOT NULL DEFAULT 会把默认值应用到所有已有行，无需回填 UPDATE。
        assertThat(useProxyOf(jdbcTemplate, "existing")).isZero();
    }

    /**
     * {@code use_proxy} 只接受 0 与 1。
     *
     * <p>与 {@code enabled} 同一口径：布尔列在 SQLite 里没有原生类型，不加 CHECK 就能塞进
     * 任意整数，读取侧只能靠「非 0 即真」去猜，而那会让 {@code 2} 这类脏数据静默生效。
     */
    @Test
    void v11UseProxyColumnRejectsValuesOutsideZeroAndOne() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV9ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 10);

        newMigrationRunner(jdbcTemplate).run(null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_config (provider_key, base_url, use_proxy) "
                        + "VALUES ('bad-proxy-flag', 'https://a.test', 2)"))
                .isInstanceOf(DataAccessException.class);
        // 两个合法值都必须放行。
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, base_url, use_proxy) "
                + "VALUES ('proxied', 'https://a.test', 1)");
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, base_url, use_proxy) "
                + "VALUES ('direct', 'https://b.test', 0)");
        assertThat(useProxyOf(jdbcTemplate, "proxied")).isEqualTo(1);
        assertThat(useProxyOf(jdbcTemplate, "direct")).isZero();
    }

    /**
     * 重跑 V11 不会把用户打开的代理开关关回去。
     *
     * <p>{@code addColumnIfNotExists} 让 DDL 幂等，但真正要钉的是「没有回填 UPDATE」——
     * 若迁移体里写了一条无条件的 {@code UPDATE ... SET use_proxy = 0}，第二次运行会把用户
     * 打开的开关静默关掉，而这个缺陷只在跑两遍时显形。
     */
    @Test
    void v11MigrationRerunPreservesUserEnabledProxy() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        createV88ProviderConfigTable(jdbcTemplate);
        createV9ProviderModelTable(jdbcTemplate);
        createV87RequestTransformTable(jdbcTemplate);
        seedV86SchemaVersion(jdbcTemplate, 10);
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, base_url) VALUES ('configured', 'https://a.test')");

        SchemaMigrationRunner runner = newMigrationRunner(jdbcTemplate);
        runner.run(null);
        // 模拟用户在界面上打开代理开关。
        jdbcTemplate.update("UPDATE provider_config SET use_proxy = 1 WHERE provider_key = 'configured'");

        runner.run(null);

        assertThat(useProxyOf(jdbcTemplate, "configured")).isEqualTo(1);
    }

    // ==================== 跨版本升级只执行缺失的迁移 ====================
    //
    // 以下两个用例锁定同一个不变量：库版本落后于代码版本时，只能执行区间内缺失的迁移，
    // 不得重跑库里已经应用过的那些。两者分别从「改写字段」与「删除整行」两个方向验证。
    //
    // 这曾是一个真实缺陷，成因是两层过滤同时缺下界：
    //   1. baselineMigrations() 固定按 version > 7.0 过滤，没有「> 库当前版本」的下界；
    //   2. migrate() 的已应用判定是 COUNT(version = ?)，那是为 V1-V6 多行表设计的。
    //      V7 起 schema_version 只有一行，于是唯一被跳过的是「恰好等于库当前版本」的那个迁移。
    //
    // 稳定状态下两处都不会暴露，因为 migrateThrough() 开头的 isCurrentBaseline() 直接短路了，
    // 所以缺陷一直隐形；只有升级 jar 后的那一次启动会让它变成实际执行路径。
    //
    // 不要把这两个用例当成对 V8 / V8.6 迁移体的测试 —— 它们测的是迁移调度，
    // 那两个迁移只是「重放后果最容易观察」的取样。
    //
    // 第三个用例 upgradeFromPreviousVersionRunsOnlyTheMissingMigration 从日志层面覆盖全部
    // 更早版本，不依赖任何具体迁移的副作用；前两个则贴近真实危害。三者都不含版本号字面量。

    /**
     * 落后一个版本的库升级时不得重放 V8.6 的协议回填，应用层已写入的日志行保持原样。
     *
     * <p>V8.6 的回填语句没有任何「仅存量」限定：
     * {@code UPDATE api_call_log SET ... = 'ANTHROPIC' WHERE chunks IS NOT NULL AND chunks NOT LIKE '%[DONE]%'}。
     * 它的前提是「存量日志必为直连，且流式 OpenAI 一定含 [DONE]」，这在 V8.6 那一刻成立。
     * 但 V8.6 之后的行由应用层直接填协议列，其中因截断、上游异常终止或客户端断连而
     * 没写到 {@code [DONE]} 的流式 Chat 记录，一旦重放就会被误判成 Anthropic 那一侧。
     *
     * <p>选这个场景作样本是因为它不靠构造：AGENTS.md 把截断与空响应列为常见失败模式，
     * 而恰好是这些失败的记录最需要在日志里保持协议正确。
     *
     * <p><strong>V12 让这个断言更锋利了。</strong>此前缺失的迁移（V11 加代理列）根本不碰协议列，
     * 断言「协议没变」只能证明「没有任何东西动过它」。现在缺失的迁移就是重命名本身，于是
     * 两种结局可以被区分开：只跑 V12 得到 {@code CHAT}；若 V8.6 也被重放，那一行会先被判成
     * {@code ANTHROPIC} 再由 V12 变成 {@code MESSAGES}。断言 {@code CHAT} 因此同时钉住
     * 「该跑的跑了」与「不该跑的没跑」。
     */
    @Test
    void crossVersionUpgradeKeepsProtocolOfTruncatedOpenAiLogWrittenAfterV86() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        // 同样显式钉住 V11：下面要插入旧协议名。而且这个用例的锰利度本身也依赖
        // “缺失的迁移恰好是 V12 重命名”（见方法注释），fixture 前移会让那一层证明失效。
        seedDatabaseAtVersion(jdbcTemplate, 11);
        // 一条 V8.6 之后写入的流式 Chat 调用：上游中途截断，因此 chunks 里没有 [DONE]。
        // 两个协议列由应用层填写，值是正确的（此时库还在 V12 之前，用的是旧协议名）。
        jdbcTemplate.update("INSERT INTO api_call_log "
                + "(provider_key, model_name, is_stream, chunks, downstream_protocol, upstream_protocol) "
                + "VALUES ('gateway', 'gpt-4o', 1, ?, 'OPENAI', 'OPENAI')",
                "[\"{\\\"choices\\\":[{\\\"delta\\\":{\\\"content\\\":\\\"hi\\\"}}]}\"]");

        newMigrationRunner(jdbcTemplate).run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT version FROM schema_version WHERE id = 1", Double.class))
                .isEqualTo(SchemaMigrationRunner.currentSchemaVersion());
        // V12 把这一行重命名为 CHAT。若 V8.6 被重放，它会是 MESSAGES —— 见方法注释。
        assertProtocols(jdbcTemplate, "gateway", "CHAT");
    }

    /**
     * 落后一个版本的库升级时不得重放 V8，名字以 {@code custom-} 开头的合法供应商不受影响。
     *
     * <p>V8 的语义是「把 custom-x 收敛为 x，若 x 已存在则删掉 x 由 custom-x 接管」。
     * 那次迁移之后 {@code custom-} 前缀不再有任何特殊含义，所以用户完全可以合法地建一个
     * 叫 {@code custom-gateway} 的供应商并与 {@code gateway} 共存。一旦 V8 被重放，
     * 它会照旧把 {@code custom-gateway} 当成待收敛的历史键，连带 Key、模型、请求转换
     * 一起删掉 {@code gateway} 再改名接管。
     *
     * <p>用这个场景做第二个样本，是因为它是重放后果里唯一不可恢复的一类：
     * 其余迁移重放至多改写字段，而这里是整行删除，且删掉的是加密后无法从别处重建的 API Key。
     */
    @Test
    void crossVersionUpgradeKeepsProviderWhoseNameStartsWithCustomPrefix() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        seedDatabaseAtPreviousVersion(jdbcTemplate);
        // 两个都是 V8 之后建的合法供应商：此时 custom- 只是名字的一部分，没有历史含义。
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, display_name, base_url) "
                + "VALUES ('custom-gateway', '自建网关', 'https://custom.example.com/v1')");
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, display_name, base_url) "
                + "VALUES ('gateway', '公司网关', 'https://gateway.example.com/v1')");
        int shadowedProviderId = jdbcTemplate.queryForObject(
                "SELECT id FROM provider_config WHERE provider_key = 'gateway'", Integer.class);
        // 字段给全：fixture 走的是真实迁移路径，这张表带着 V4 的完整非空约束，
        // 而 Key 的密文与 nonce 正是重放后无法从别处重建的那部分。
        jdbcTemplate.update("INSERT INTO provider_api_key "
                + "(key_uuid, provider_id, key_name, encrypted_api_key, nonce, encryption_version, is_active, sort_order) "
                + "VALUES ('uuid-gateway', ?, 'Default', 'cipher', 'nonce', 1, 1, 0)",
                shadowedProviderId);
        jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name, reasoning_effort) "
                + "VALUES (?, 'gateway-model', ?)",
                shadowedProviderId, "{\"reasoning_effort\":\"medium\",\"overwrite_mode\":\"fallback\"}");

        newMigrationRunner(jdbcTemplate).run(null);

        // 两行供应商及其关联数据全部原封不动，键名也不被改写。
        // 用 contains 而非 containsExactly：fixture 走真实迁移路径，库里还有历史数据带来的
        // 其它供应商，而本用例只关心这两个键有没有被 V8 的收敛逻辑动过。
        List<String> providerKeys = jdbcTemplate.queryForList(
                "SELECT provider_key FROM provider_config ORDER BY id", String.class);
        assertThat(providerKeys).contains("custom-gateway", "gateway");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM provider_config WHERE provider_key = 'gateway'", String.class))
                .isEqualTo("公司网关");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM provider_config WHERE provider_key = 'custom-gateway'", String.class))
                .isEqualTo("自建网关");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_api_key WHERE key_uuid = 'uuid-gateway'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_model WHERE model_name = 'gateway-model'", Integer.class))
                .isEqualTo(1);
    }

    /**
     * 落后一个版本的库只执行那一个缺失的迁移，日志里不出现任何更早版本。
     *
     * <p>前两个用例通过「某个迁移的副作用有没有发生」间接证明它没跑，好处是贴近真实危害，
     * 代价是一旦那两个迁移体的实现变了，断言就跟着失去意义。这里直接断言执行了哪些版本，
     * 不依赖任何具体迁移的行为。
     *
     * <h2>不写死版本号</h2>
     * 这个不变量与版本号无关，所以断言里不出现任何版本号字面量：待执行的那一版取自
     * {@code currentSchemaVersion()}，不该执行的那些由注册表筛出。新增迁移时本用例无需修改。
     *
     * <p>日志按「本次运行输出了什么」截取：fixture 推进阶段自己也会打迁移日志，
     * 若对全量日志断言，那些行会让「更早版本没跑」永远不成立。记下推进后的日志长度再截尾，
     * 于是两个阶段天然分开。
     *
     * <p>版本号后必须带冒号：{@code 已应用 V8} 是 {@code 已应用 V8.9} 的前缀，
     * 而 {@code 已应用 V1} 是 {@code 已应用 V10} 的前缀，缺了冒号断言永远失败。
     */
    @Test
    void upgradeFromPreviousVersionRunsOnlyTheMissingMigration(CapturedOutput output) {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        seedDatabaseAtPreviousVersion(jdbcTemplate);
        int logLengthBeforeUpgrade = output.getAll().length();

        newMigrationRunner(jdbcTemplate).run(null);

        String upgradeLog = output.getAll().substring(logLengthBeforeUpgrade);
        double current = SchemaMigrationRunner.currentSchemaVersion();
        assertThat(upgradeLog).contains(appliedMarker(current));
        assertThat(upgradeLog).doesNotContain(
                newMigrationRunner(jdbcTemplate).registeredMigrationVersions().stream()
                        .filter(version -> version < current)
                        .map(this::appliedMarker)
                        .toArray(String[]::new));
    }

    /**
     * 拼出迁移日志里「已应用某版本」的那段前缀，格式与 {@code formatVersion()} 一致。
     *
     * <p>整数版本渲染成 {@code V9} 而非 {@code V9.0}，历史的 {@code a.b} 原样保留。
     * 末尾的冒号是断言正确性的前提，见调用方注释。
     */
    private String appliedMarker(double version) {
        String rendered = version == Math.rint(version)
                ? "V" + (long) version
                : "V" + version;
        return "已应用 " + rendered + ":";
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
                                + "DEFAULT '[\"CHAT\",\"MESSAGES\"]' CHECK (json_valid(supported_protocols))");
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

        /**
         * 造出一个恰好落后当前版本一步的库：最新那个迁移是唯一应当执行的。
         *
         * <p>从历史库出发、用 {@code migrateThrough} 推到<strong>倒数第二个</strong>已注册版本，
         * 而不手写那一版的建表语句。两个好处：走的是生产的执行路径，因而必然与真实升级后的
         * 结构一致；且新增迁移时本方法无需修改 —— 旧写法是每加一版就要新增一个
         * {@code seedV<前一版>Database} 并把上一个改名或删掉。
         *
         * <p>取倒数第二项而非反推「当前版本减一」：版本号间隔不规则（历史上是 0.1，
         * V9 起是 1），只有注册表的顺序才是「前一版」的权威定义。
         */
        private void seedDatabaseAtPreviousVersion(JdbcTemplate jdbcTemplate) {
                List<Double> versions = newMigrationRunner(jdbcTemplate).registeredMigrationVersions();
                seedDatabaseAtVersion(jdbcTemplate, versions.get(versions.size() - 2));
        }

        /**
         * 造出一个停在<strong>指定</strong>已注册版本的库。
         *
         * <p>{@link #seedDatabaseAtPreviousVersion} 会随新版本加入自动前移，那正是它的价值 ——
         * 「最新那个迁移是唯一该跑的」这个不变量与版本号无关。但有些用例需要的恰恰是某个
         * <strong>特定</strong>版本的形态，此时自动前移会让它们悄悄失去意义甚至直接失败：
         * 比如要构造 V12 之前的旧协议名字面量，一旦 fixture 前移到 V12，那些字面量就撞上
         * V12 已经建好的 CHECK 白名单了。这类用例应当显式钉住版本并写清为什么。
         *
         * @param version 目标版本，必须是注册表里的某一项
         */
        private void seedDatabaseAtVersion(JdbcTemplate jdbcTemplate, double version) {
                createLegacySchema(jdbcTemplate);
                seedLegacyData(jdbcTemplate);
                newMigrationRunner(jdbcTemplate).migrateThrough(version);
                assertThat(jdbcTemplate.queryForObject(
                                "SELECT version FROM schema_version WHERE id = 1", Double.class))
                                .isEqualTo(version);
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

        /**
         * 建出 V8.9 时的 provider_model：{@code max_output_tokens} 仍是 INTEGER，
         * 那正是 V9 要换成带 {@code json_valid} 约束的 TEXT 的那一列。
         */
        private void createV89ProviderModelTable(JdbcTemplate jdbcTemplate) {
                createV88ProviderModelTable(jdbcTemplate);
                jdbcTemplate.execute("ALTER TABLE provider_model ADD COLUMN reasoning_effort_schema "
                                + "INTEGER NOT NULL DEFAULT 2 CHECK (reasoning_effort_schema >= 1)");
        }

        /**
         * 建出 V9 重建后的 provider_model：{@code max_output_tokens} 已是带
         * {@code json_valid} 的 TEXT，且触发器与唯一索引都在位。
         *
         * <p>直接复用 V9 迁移之后的真实形态，而不是「V8.9 表 + 手动改列」——
         * V10 的前置条件是那张重建后的表，夹具必须与之一致，否则测的是一个
         * 现实中不存在的中间态。
         *
         * <p>触发器与索引都建上，是为了让
         * {@code v10DoesNotDisturbExistingValidationsWhenAddingColumns} 有东西可断言：
         * 若夹具不建，那条用例会因为「本来就没有」而假通过。
         */
        private void createV9ProviderModelTable(JdbcTemplate jdbcTemplate) {
                jdbcTemplate.execute("CREATE TABLE provider_model ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                                + "provider_id INTEGER NOT NULL, "
                                + "model_name VARCHAR(100) NOT NULL, "
                                + "enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)), "
                                + "context_size INTEGER NOT NULL DEFAULT 0 CHECK (context_size >= 0), "
                                + "max_output_tokens TEXT NOT NULL DEFAULT "
                                + "'{\"max_output_tokens\":4000,\"overwrite_mode\":\"fallback\"}' "
                                + "CHECK (json_valid(max_output_tokens)), "
                                + "caps_tools INTEGER NOT NULL DEFAULT 0 CHECK (caps_tools IN (0, 1)), "
                                + "caps_vision INTEGER NOT NULL DEFAULT 0 CHECK (caps_vision IN (0, 1)), "
                                + "reasoning_effort TEXT NOT NULL DEFAULT "
                                + "'{\"reasoning_effort\":\"medium\",\"overwrite_mode\":\"fallback\"}' "
                                + "CHECK (json_valid(reasoning_effort)), "
                                + "reasoning_effort_schema INTEGER NOT NULL DEFAULT 2 "
                                + "CHECK (reasoning_effort_schema >= 1), "
                                + "sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0), "
                                + "FOREIGN KEY (provider_id) REFERENCES provider_config(id) ON DELETE CASCADE)");
                jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_provider_model_provider_name "
                                + "ON provider_model(provider_id, model_name)");
                String modelInvalid = "NEW.enabled NOT IN (0, 1) OR NEW.caps_tools NOT IN (0, 1) "
                                + "OR NEW.caps_vision NOT IN (0, 1) OR NEW.context_size < 0";
                jdbcTemplate.execute("CREATE TRIGGER IF NOT EXISTS trg_provider_model_validate_insert "
                                + "BEFORE INSERT ON provider_model FOR EACH ROW WHEN " + modelInvalid
                                + " BEGIN SELECT RAISE(ABORT, 'invalid provider_model row'); END");
                jdbcTemplate.execute("CREATE TRIGGER IF NOT EXISTS trg_provider_model_validate_update "
                                + "BEFORE UPDATE ON provider_model FOR EACH ROW WHEN " + modelInvalid
                                + " BEGIN SELECT RAISE(ABORT, 'invalid provider_model row'); END");
        }

        private String thinkingModeOf(JdbcTemplate jdbcTemplate, String modelName) {
                return jdbcTemplate.queryForObject(
                                "SELECT thinking_mode FROM provider_model WHERE model_name = ?",
                                String.class, modelName);
        }

        private int thinkingBudgetOf(JdbcTemplate jdbcTemplate, String modelName) {
                return jdbcTemplate.queryForObject(
                                "SELECT thinking_budget_tokens FROM provider_model WHERE model_name = ?",
                                Integer.class, modelName);
        }

        /** 读某个供应商的代理开关原始整数值，便于断言 0/1 而非布尔转换后的结果。 */
        private int useProxyOf(JdbcTemplate jdbcTemplate, String providerKey) {
                return jdbcTemplate.queryForObject(
                                "SELECT use_proxy FROM provider_config WHERE provider_key = ?",
                                Integer.class, providerKey);
        }

        /**
         * 读某个供应商的协议集合<strong>原文</strong>。
         *
         * <p>刻意返回字符串而不解析成集合：元素顺序与空数组形态都是要断言的内容，
         * 解析成 {@code Set} 会把这两点抹掉。
         */
        private String protocolsOf(JdbcTemplate jdbcTemplate, String providerKey) {
                return jdbcTemplate.queryForObject(
                                "SELECT supported_protocols FROM provider_config WHERE provider_key = ?",
                                String.class, providerKey);
        }

        /** 读某个供应商的 Responses 端点；空串表示回退到 {@code base_url}。 */
        private String responsesBaseUrlOf(JdbcTemplate jdbcTemplate, String providerKey) {
                return jdbcTemplate.queryForObject(
                                "SELECT responses_base_url FROM provider_config WHERE provider_key = ?",
                                String.class, providerKey);
        }

        /**
         * 读某个供应商的鉴权头装配方式<strong>原文</strong>。
         *
         * <p>与 {@link #protocolsOf} 同一理由返回字符串而不解析：本列要断言的正是它的序列化形态
         * （键序、大小写），解析成对象会把「新库与升级库写出来的字节是否一致」这件事抹掉，
         * 而那正是最需要钉住的一点 —— 两处 DEFAULT 分叉时它们语义相同但字面不同。
         */
        private String authHeaderOf(JdbcTemplate jdbcTemplate, String providerKey) {
                return jdbcTemplate.queryForObject(
                                "SELECT auth_header FROM provider_config WHERE provider_key = ?",
                                String.class, providerKey);
        }

        /** 插一行带指定最大输出值的模型，模型名即用例里的标签。 */
        private void seedMaxOutput(JdbcTemplate jdbcTemplate, String modelName, int rawMaxOutput) {
                jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name, max_output_tokens) "
                                + "VALUES (1, ?, ?)", modelName, rawMaxOutput);
        }

        private String maxOutputOf(JdbcTemplate jdbcTemplate, String modelName) {
                return jdbcTemplate.queryForObject(
                                "SELECT max_output_tokens FROM provider_model WHERE model_name = ?",
                                String.class, modelName);
        }

        /** 取出 V9 JSON 里的 token 上限，便于断言档位重映射结果。 */
        private int maxOutputTokensOf(JdbcTemplate jdbcTemplate, String modelName) {
                return jdbcTemplate.queryForObject(
                                "SELECT json_extract(max_output_tokens, '$.max_output_tokens') "
                                                + "FROM provider_model WHERE model_name = ?",
                                Integer.class, modelName);
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

    /** 断言某行两侧协议同名（直连）。 */
    private void assertProtocols(JdbcTemplate jdbcTemplate, String providerKey, String expectedProtocol) {
        assertProtocols(jdbcTemplate, providerKey, expectedProtocol, expectedProtocol);
    }

    /**
     * 断言某行的两侧协议分别取值（跨协议翻译行）。
     *
     * <p>需要这个重载是因为直连与跨协议在重映射上不等价：单值版本无法区分「两列都对」
     * 与「两列都错成同一个值」，而 V12 的 {@code CASE} 是逐列生成的，恰好可能只对一列生效。
     */
    private void assertProtocols(JdbcTemplate jdbcTemplate, String providerKey,
                                 String expectedDownstream, String expectedUpstream) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT downstream_protocol, upstream_protocol FROM api_call_log WHERE provider_key = ?", providerKey);
        assertThat(row.get("downstream_protocol")).isEqualTo(expectedDownstream);
        assertThat(row.get("upstream_protocol")).isEqualTo(expectedUpstream);
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
                if (version >= 9) {
                        // 重建表后该列是 JSON：断言约束而非列名 —— 列名 V9 之前就存在，
                        // 只有「非 JSON 被拒」才能证明重建真的发生过。
                        assertThatThrownBy(() -> jdbcTemplate.update(
                                "INSERT INTO provider_model (provider_id, model_name, max_output_tokens) "
                                        + "VALUES (999, 'v9-checkpoint-probe', 'not-json')"))
                                .isInstanceOf(DataAccessException.class);
                }
                if (version >= 10) {
                        // V10 是纯新增列，断言列名即可 —— 与 V9 不同，这里没有「同名列换了形态」
                        // 的歧义，列存在就说明迁移执行过。
                        assertThat(columnNames(jdbcTemplate, "provider_model"))
                                .contains("thinking_mode", "thinking_budget_tokens");
                }
                if (version >= 11) {
                        // 同为纯新增列，断言列名即可。
                        assertThat(columnNames(jdbcTemplate, "provider_config")).contains("use_proxy");
                }
                // V12 的持久不变量是协议字面量重命名（由它自己的用例覆盖，且没有「列存在」可断言）；
                // V13 的 responses_base_url 暂未在此登记，需要时补。
                if (version >= 14) {
                        // 纯新增列，断言列名即可（与 V10、V11 同）。
                        assertThat(columnNames(jdbcTemplate, "provider_config")).contains("auth_header");
                }
        }
}

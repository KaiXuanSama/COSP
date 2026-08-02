package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.AppConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.security.ApiKeyCryptoService;
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
import java.util.UUID;

/**
 * 数据库 Schema 版本迁移器。
 *
 * 每个迁移在独立事务中执行，成功后写入 schema_version。新数据库由 schema.sql
 * 直接创建最终结构，旧数据库则通过这里补齐列、转换数据并增加约束与索引。
 */
@Component
public class SchemaMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationRunner.class);
    private static final double V7_BASELINE_VERSION = 7.0;
    private static final double V7_1_VERSION = 7.1;
    private static final double V8_VERSION = 8.0;
    private static final double V8_1_VERSION = 8.1;
    private static final double V8_2_VERSION = 8.2;
    private static final double V8_3_VERSION = 8.3;
    private static final double CURRENT_SCHEMA_VERSION = 8.4;
    private static final TypeReference<List<Map<String, String>>> API_KEY_LIST_TYPE = new TypeReference<>() {};
    private static final String DEFAULT_BODY_TEMPLATE_KEYS_JSON = "[\"base\"]";
    private static final String DEFAULT_BODY_PREVIEW_JSON = "{"
            + "\"model\":\"<string>\","
            + "\"temperature\":0.1,"
            + "\"top_p\":1.0,"
            + "\"stream\":true,"
            + "\"n\":1,"
            + "\"stream_options\":{\"include_usage\":true},"
            + "\"reasoning_effort\":\"medium\"}";
    private static final String EMPTY_BODY_RULES_JSON = "{\"version\":1,\"rules\":[]}";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final ApiKeyCryptoService cryptoService;
    private final AppConfigRepository appConfigRepository;

    /**
     * 创建 Schema 迁移器。
     *
     * @param jdbcTemplate JDBC 操作模板
     * @param transactionTemplate 迁移事务模板
     * @param objectMapper JSON 序列化器
     * @param cryptoService API Key 加密服务（V4 迁移依赖）
     * @param appConfigRepository 应用配置仓储（写入密钥指纹）
     */
    public SchemaMigrationRunner(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                                 ObjectMapper objectMapper, ApiKeyCryptoService cryptoService,
                                 AppConfigRepository appConfigRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.appConfigRepository = appConfigRepository;
    }

    /**
     * 按版本顺序执行尚未应用的迁移。
     *
     * @param args 应用启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        migrateThrough(CURRENT_SCHEMA_VERSION);
    }

    /**
     * 执行迁移直至目标版本。
     *
     * 生产启动始终传入当前版本；同包测试可逐个传入已注册版本，验证每个迁移 checkpoint
     * 使用的仍是与生产完全相同的执行路径。
     */
    void migrateThrough(double targetVersion) {
        if (!registeredMigrationVersions().contains(targetVersion)) {
            throw new IllegalArgumentException("未注册的 Schema 迁移版本: " + targetVersion);
        }
        if (isCurrentBaseline()) {
            return;
        }

        if (!hasLegacyProviderConfigColumns()) {
            if (hasBaselineVersionRecord()) {
                applyMigrationsThrough(baselineMigrations(), targetVersion);
                return;
            }
            if (targetVersion != CURRENT_SCHEMA_VERSION) {
                throw new IllegalStateException("当前 Schema 无版本记录时只能建立最新基线");
            }
            establishCurrentBaseline();
            return;
        }

        prepareHistoricalVersionTracking();
        applyMigrationsThrough(historicalMigrations(), targetVersion);
    }

    static double currentSchemaVersion() {
        return CURRENT_SCHEMA_VERSION;
    }

    List<Double> registeredMigrationVersions() {
        return historicalMigrations().stream().map(MigrationStep::version).toList();
    }

    private List<MigrationStep> historicalMigrations() {
        return List.of(
                new MigrationStep(1, "补齐历史增量字段", this::migrateLegacyColumns),
                new MigrationStep(2, "API Key 转换为 JSON 数组", this::migrateApiKeyToJsonArray),
                new MigrationStep(3, "增加业务约束与查询索引", this::migrateConstraintsAndIndexes),
                new MigrationStep(4, "API Key 拆表与加密", this::migrateApiKeysToEncryptedTable),
                new MigrationStep(5, "新增供应商请求转换配置表", this::migrateProviderRequestTransforms),
                new MigrationStep(6, "清理遗留请求转换配置", this::clearLegacyRequestTransforms),
                new MigrationStep(V7_BASELINE_VERSION, "移除废弃字段并压缩迁移历史", this::migrateToV7Baseline),
                new MigrationStep(V7_1_VERSION, "新增供应商完整显示名", this::migrateDisplayNameToV71),
                new MigrationStep(V8_VERSION, "统一供应商实现并移除 custom- 前缀", this::migrateToV8UnifiedProviders),
                new MigrationStep(V8_1_VERSION, "移除固定的 API 格式字段", this::migrateToV81RemoveApiFormat),
                new MigrationStep(V8_2_VERSION, "移除思考链缓存", this::migrateToV82RemoveReasoningCache),
                new MigrationStep(V8_3_VERSION, "新增 token 用量表", this::migrateToV83AddUsageTable),
                new MigrationStep(CURRENT_SCHEMA_VERSION, "新增用量时间范围查询索引",
                        this::migrateToV84AddUsageCreatedAtIndex));
    }

    private List<MigrationStep> baselineMigrations() {
        return historicalMigrations().stream()
                .filter(step -> step.version() > V7_BASELINE_VERSION)
                .toList();
    }

    private void applyMigrationsThrough(List<MigrationStep> migrations, double targetVersion) {
        for (MigrationStep migration : migrations) {
            if (migration.version() <= targetVersion) {
                migrate(migration.version(), migration.description(), migration.action());
            }
        }
    }

    /**
     * 判断数据库是否已处于当前单行基线。
     */
    private boolean isCurrentBaseline() {
        if (!tableExists("schema_version") || !columnExists("schema_version", "id")) {
            return false;
        }
        Double version = jdbcTemplate.query(
                "SELECT version FROM schema_version WHERE id = 1",
            resultSet -> resultSet.next() ? resultSet.getDouble("version") : null);
        return version != null && version >= CURRENT_SCHEMA_VERSION
            && columnExists("provider_config", "display_name") && !columnExists("provider_config", "api_format")
            && !hasLegacyProviderConfigColumns() && !tableExists("reasoning_cache")
            && tableExists("api_call_usage") && indexExists("idx_api_call_usage_created");
    }

    /**
     * 为由当前 schema.sql 创建的新数据库写入基线，不回放历史迁移。
     */
    private void establishCurrentBaseline() {
        ensureBaselineVersionTable();
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
                "INSERT INTO schema_version (id, version, description) VALUES (1, ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET version = excluded.version, "
                        + "description = excluded.description, "
                        + "applied_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')",
                    CURRENT_SCHEMA_VERSION, "V8.4 架构基线：统一供应商实现、token 用量表与时间范围查询索引"));
        log.info("[SchemaMigration] 已建立 V{} 架构基线", CURRENT_SCHEMA_VERSION);
    }

    /**
     * 准备旧数据库的多版本迁移记录表。
     *
     * schema.sql 可能已为无历史记录的旧库创建单行基线表；在确认仍存在旧列后，
     * 该空表会被替换为历史记录表，供 V1 至 V7 逐步升级使用。
     */
    private void prepareHistoricalVersionTracking() {
        if (!tableExists("schema_version")) {
            createHistoricalVersionTable();
            return;
        }
        if (columnExists("schema_version", "id")) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class);
            if (count != null && count > 0) {
                throw new IllegalStateException("检测到旧供应商结构与 V7 基线记录同时存在，无法安全判断迁移状态");
            }
            jdbcTemplate.execute("DROP TABLE schema_version");
            createHistoricalVersionTable();
        }
    }

    private void createHistoricalVersionTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                + "version INTEGER PRIMARY KEY, description TEXT NOT NULL, "
                + "applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");
    }

    private void ensureBaselineVersionTable() {
        if (tableExists("schema_version") && !columnExists("schema_version", "id")) {
            jdbcTemplate.execute("DROP TABLE schema_version");
        }
        if (!tableExists("schema_version")) {
            jdbcTemplate.execute("CREATE TABLE schema_version ("
                    + "id INTEGER PRIMARY KEY CHECK (id = 1), version INTEGER NOT NULL, "
                    + "description TEXT NOT NULL, applied_at TEXT NOT NULL DEFAULT "
                    + "(strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");
        }
    }

    private boolean hasBaselineVersionRecord() {
        return tableExists("schema_version") && columnExists("schema_version", "id")
                && jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_version WHERE id = 1", Integer.class) == 1;
    }

    private void migrate(double version, String description, Runnable action) {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_version WHERE version = ?", Integer.class, version);
        if (applied != null && applied > 0) {
            return;
        }
        if (version >= V7_BASELINE_VERSION) {
            transactionTemplate.executeWithoutResult(status -> action.run());
            log.info("[SchemaMigration] 已应用 V{}: {}", version, description);
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            action.run();
            jdbcTemplate.update("INSERT INTO schema_version (version, description) VALUES (?, ?)",
                    version, description);
        });
        log.info("[SchemaMigration] 已应用 V{}: {}", version, description);
    }

    private record MigrationStep(double version, String description, Runnable action) {
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
        if (!columnExists(table, column)) {
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

    /**
     * V4：将 provider_config.api_key 中的明文 JSON 数组拆分到 provider_api_key，
     * 并对每条 Key 用 AES-256-GCM 加密。迁移全程在事务内，任意失败都会回滚，保留旧明文。
     *
     * 步骤：建表 → 校验/写入主密钥指纹 → 逐条加密插入 → 立即解密校验 → 清空旧明文列。
     */
    private void migrateApiKeysToEncryptedTable() {
        createProviderApiKeyTable();
        verifyOrRecordKeyFingerprint();

        var providers = jdbcTemplate.queryForList(
                "SELECT id, api_key, active_api_key_index FROM provider_config");
        for (var provider : providers) {
            int providerId = ((Number) provider.get("id")).intValue();
            String apiKeyJson = (String) provider.get("api_key");
            int activeIndex = provider.get("active_api_key_index") == null
                    ? 0 : ((Number) provider.get("active_api_key_index")).intValue();

            List<Map<String, String>> keys = parseApiKeyArray(apiKeyJson);
            java.util.Set<String> usedNames = new java.util.HashSet<>();
            for (int i = 0; i < keys.size(); i++) {
                Map<String, String> entry = keys.get(i);
                String plaintext = entry.get("api_key");
                if (plaintext == null) {
                    plaintext = "";
                }
                String keyName = deduplicateName(entry.getOrDefault("name", ""), i, usedNames);
                boolean active = (i == activeIndex);
                insertEncryptedKey(providerId, keyName, plaintext, active, i);
            }
        }

        // 清空旧明文列（废弃保留策略）
        jdbcTemplate.update("UPDATE provider_config SET api_key = '[]', active_api_key_index = 0");
    }

    /**
     * V5：创建供应商请求转换配置表，并从旧 custom_transforms 复制请求头配置。
     *
     * V5 只引入新存储，不改变生产请求执行路径。旧请求体调整不做自动语义迁移，
     * 新请求体规则初始化为空；编辑器预览初始化为当前前端的 base 模板。
     */
    private void migrateProviderRequestTransforms() {
        createProviderRequestTransformTable();

        var providers = jdbcTemplate.queryForList("SELECT id, custom_transforms FROM provider_config ORDER BY id");
        for (var provider : providers) {
            int providerId = ((Number) provider.get("id")).intValue();
            String customTransforms = (String) provider.get("custom_transforms");
            String headerRulesJson = extractHeaderRulesJson(providerId, customTransforms);
            jdbcTemplate.update(
                    "INSERT INTO provider_request_transform "
                            + "(provider_id, header_rules_version, header_rules_json, "
                            + "body_template_keys_json, body_preview_json, body_rules_version, body_rules_json) "
                            + "VALUES (?, 1, ?, ?, ?, 1, ?) ON CONFLICT(provider_id) DO NOTHING",
                    providerId, headerRulesJson, DEFAULT_BODY_TEMPLATE_KEYS_JSON,
                    DEFAULT_BODY_PREVIEW_JSON, EMPTY_BODY_RULES_JSON);
        }

        verifyProviderRequestTransforms(providers.size());
    }

    /**
     * V6：清空 custom_transforms 中的所有历史请求转换配置。
     *
     * 请求头和请求体规则均已由 provider_request_transform 管理，旧列不再是配置来源。
     * 本次仅清空旧列内容，暂不进行 SQLite 表重建删列。
     */
    private void clearLegacyRequestTransforms() {
        jdbcTemplate.update("UPDATE provider_config SET custom_transforms = '{}'");
    }

    /**
     * V7：物理删除已被替代的 API Key 和请求转换列，并将多行迁移历史压缩为唯一基线。
     */
    private void migrateToV7Baseline() {
        dropTrigger("trg_provider_config_validate_insert");
        dropTrigger("trg_provider_config_validate_update");
        jdbcTemplate.execute("ALTER TABLE provider_config DROP COLUMN api_key");
        jdbcTemplate.execute("ALTER TABLE provider_config DROP COLUMN active_api_key_index");
        jdbcTemplate.execute("ALTER TABLE provider_config DROP COLUMN custom_transforms");
        createProviderConfigValidationTriggers();

        jdbcTemplate.execute("DROP TABLE schema_version");
        jdbcTemplate.execute("CREATE TABLE schema_version ("
                + "id INTEGER PRIMARY KEY CHECK (id = 1), version INTEGER NOT NULL, "
                + "description TEXT NOT NULL, applied_at TEXT NOT NULL DEFAULT "
                + "(strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");
        jdbcTemplate.update("INSERT INTO schema_version (id, version, description) VALUES (1, ?, ?)",
            V7_BASELINE_VERSION, "V7 架构基线：移除废弃字段并压缩迁移历史");
    }

    /**
     * V7.1：新增供应商完整显示名列，并为已有数据回填可读名称。
     *
     * 此版本是 V7 基线上的增量迁移。版本记录由统一迁移框架驱动，
     * 完成后仍只保留单条最新版本状态。
     */
    private void migrateDisplayNameToV71() {
        dropTrigger("trg_provider_config_validate_update");
        addColumnIfNotExists("provider_config", "display_name", "TEXT NOT NULL DEFAULT ''");
        var providers = jdbcTemplate.queryForList(
            "SELECT id, provider_key, display_name FROM provider_config WHERE trim(display_name) = ''");
        for (var provider : providers) {
            int providerId = ((Number) provider.get("id")).intValue();
            String providerKey = (String) provider.get("provider_key");
            jdbcTemplate.update("UPDATE provider_config SET display_name = ? WHERE id = ?",
                deriveDisplayName(providerKey), providerId);
        }
        createProviderConfigValidationTriggers();
        jdbcTemplate.update("UPDATE schema_version SET version = ?, description = ?, "
                + "applied_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE id = 1",
                V7_1_VERSION, "V7.1 增量迁移：新增供应商完整显示名");
    }

            /**
             * V8：将 custom-* 服务商键收敛为普通键，并移除运行时的服务商类别语义。
             *
             * 当 custom-x 与 x 同时存在时，custom-x 被视为用户已配置的通用供应商，
             * 会删除旧 x 及其关联配置后接管 x。调用日志保留历史 provider_key 原值。
             */
            private void migrateToV8UnifiedProviders() {
            var customProviders = jdbcTemplate.queryForList(
                "SELECT id, provider_key FROM provider_config WHERE provider_key LIKE 'custom-%' ORDER BY id");
            for (var provider : customProviders) {
                int providerId = ((Number) provider.get("id")).intValue();
                String oldKey = (String) provider.get("provider_key");
                String targetKey = oldKey.substring("custom-".length());
                if (targetKey.isBlank()) {
                throw new IllegalStateException("V8 迁移发现无效供应商键: " + oldKey);
                }
                Integer conflictingProviderId = jdbcTemplate.query(
                    "SELECT id FROM provider_config WHERE provider_key = ?", resultSet ->
                        resultSet.next() ? resultSet.getInt("id") : null, targetKey);
                if (conflictingProviderId != null && conflictingProviderId != providerId) {
                deleteProviderConfiguration(conflictingProviderId);
                log.warn("[SchemaMigration] V8 删除被 [{}] 覆盖的旧供应商配置 [{}]", oldKey, targetKey);
                }
                jdbcTemplate.update("UPDATE provider_config SET provider_key = ? WHERE id = ?",
                    "__v8_tmp_" + providerId, providerId);
            }
            for (var provider : customProviders) {
                int providerId = ((Number) provider.get("id")).intValue();
                String oldKey = (String) provider.get("provider_key");
                jdbcTemplate.update("UPDATE provider_config SET provider_key = ? WHERE id = ?",
                    oldKey.substring("custom-".length()), providerId);
                String legacyDisplayName = deriveDisplayName(oldKey);
                String normalizedDisplayName = deriveDisplayName(oldKey.substring("custom-".length()));
                jdbcTemplate.update("UPDATE provider_config SET display_name = ? WHERE id = ? AND display_name = ?",
                    normalizedDisplayName, providerId, legacyDisplayName);
            }
            jdbcTemplate.update("UPDATE schema_version SET version = ?, description = ?, "
                    + "applied_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE id = 1",
                V8_VERSION, "V8 增量迁移：统一供应商实现并移除 custom- 前缀");
            }

    /**
     * V8.1：移除始终固定为 openai、不会影响运行时行为的 API 格式字段。
     */
    private void migrateToV81RemoveApiFormat() {
        if (columnExists("provider_config", "api_format")) {
            jdbcTemplate.execute("ALTER TABLE provider_config DROP COLUMN api_format");
        }
        jdbcTemplate.update("UPDATE schema_version SET version = ?, description = ?, "
                + "applied_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE id = 1",
            V8_1_VERSION, "V8.1 增量迁移：移除固定的 API 格式字段");
    }

    /**
     * V8.2：移除不再由当前 GitHub Copilot 客户端需要的跨请求思考链缓存。
     */
    private void migrateToV82RemoveReasoningCache() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS reasoning_cache");
        jdbcTemplate.update("UPDATE schema_version SET version = ?, description = ?, "
                + "applied_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE id = 1",
            V8_2_VERSION, "V8.2 增量迁移：移除思考链缓存");
    }

    /**
     * V8.3：新增独立的 token 用量表 api_call_usage。
     *
     * 纯加表迁移（不改任何现有表），最低风险 DDL。token 记录独立于会被裁剪的 api_call_log
     * 存活，作为长期统计与概览可视化的稳定数据源；log_id 为软链接（无 FK 约束）。
     */
    private void migrateToV83AddUsageTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS api_call_usage ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "log_id INTEGER, "
                + "provider_key VARCHAR(30), "
                + "model_name VARCHAR(100), "
                + "is_stream INTEGER NOT NULL DEFAULT 0 CHECK (is_stream IN (0, 1)), "
                + "usage_raw TEXT, "
                + "prompt_tokens INTEGER CHECK (prompt_tokens IS NULL OR prompt_tokens >= 0), "
                + "completion_tokens INTEGER CHECK (completion_tokens IS NULL OR completion_tokens >= 0), "
                + "cached_tokens INTEGER CHECK (cached_tokens IS NULL OR cached_tokens >= 0), "
                + "ttfb_ms INTEGER CHECK (ttfb_ms IS NULL OR ttfb_ms >= 0), "
                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')))");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_api_call_usage_provider_created "
                + "ON api_call_usage(provider_key, created_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_api_call_usage_log "
                + "ON api_call_usage(log_id)");
        jdbcTemplate.update("UPDATE schema_version SET version = ?, description = ?, "
                + "applied_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE id = 1",
            V8_3_VERSION, "V8.3 增量迁移：新增 token 用量表");
    }

    /**
     * V8.4：为 api_call_usage 补 created_at 前导索引。
     *
     * 纯加索引迁移，不改表结构、不动数据，因此不影响任何查询结果 —— 只影响执行计划。
     *
     * 概览的两条聚合查询都按时间范围过滤 created_at，而 V8.3 建的两个索引
     * （provider_key + created_at、log_id）都无法服务纯时间范围扫描：前者的前导列是
     * provider_key，查询没有该等值条件时用不上。api_call_usage 永久保留、只增不减，
     * 全表扫描的代价与表总量成正比而与查询窗口无关，故行数增长后必然成为瓶颈。
     *
     * 索引按 created_at 升序：两条查询都是 {@code >= start}（其一还有 {@code < end}）的
     * 范围扫描，升序与之同向；DESC 只对「取最近 N 条」有利，那是 provider_created 索引的场景。
     */
    private void migrateToV84AddUsageCreatedAtIndex() {
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_api_call_usage_created "
                + "ON api_call_usage(created_at)");
        jdbcTemplate.update("UPDATE schema_version SET version = ?, description = ?, "
                + "applied_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE id = 1",
            CURRENT_SCHEMA_VERSION, "V8.4 增量迁移：新增用量时间范围查询索引");
    }

            private void deleteProviderConfiguration(int providerId) {
            jdbcTemplate.update("DELETE FROM provider_api_key WHERE provider_id = ?", providerId);
            jdbcTemplate.update("DELETE FROM provider_model WHERE provider_id = ?", providerId);
            jdbcTemplate.update("DELETE FROM provider_request_transform WHERE provider_id = ?", providerId);
            jdbcTemplate.update("DELETE FROM provider_config WHERE id = ?", providerId);
            }

    private String deriveDisplayName(String providerKey) {
        if (providerKey == null || providerKey.isBlank()) {
            return "";
        }
        StringBuilder displayName = new StringBuilder();
        for (String part : providerKey.split("[-_\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!displayName.isEmpty()) {
                displayName.append(' ');
            }
            displayName.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return displayName.isEmpty() ? providerKey : displayName.toString();
    }

    private boolean hasLegacyProviderConfigColumns() {
        return columnExists("provider_config", "api_key")
                || columnExists("provider_config", "active_api_key_index")
                || columnExists("provider_config", "custom_transforms");
    }

    private boolean tableExists(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", Integer.class, table);
        return count != null && count > 0;
    }

    private boolean columnExists(String table, String column) {
        if (!tableExists(table)) {
            return false;
        }
        return jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(row -> column.equals(row.get("name")));
    }

    private boolean indexExists(String index) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?", Integer.class, index);
        return count != null && count > 0;
    }

    private void createProviderRequestTransformTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS provider_request_transform ("
                + "provider_id INTEGER PRIMARY KEY, "
                + "header_rules_version INTEGER NOT NULL DEFAULT 1 CHECK (header_rules_version >= 1), "
                + "header_rules_json TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(header_rules_json)), "
                + "body_template_keys_json TEXT NOT NULL DEFAULT '[\"custom\"]' "
                + "CHECK (json_valid(body_template_keys_json)), "
                + "body_preview_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(body_preview_json)), "
                + "body_rules_version INTEGER NOT NULL DEFAULT 1 CHECK (body_rules_version >= 1), "
                + "body_rules_json TEXT NOT NULL DEFAULT '{\"version\":1,\"rules\":[]}' "
                + "CHECK (json_valid(body_rules_json)), "
                + "created_at TEXT NOT NULL DEFAULT "
                + "(strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')), "
                + "updated_at TEXT NOT NULL DEFAULT "
                + "(strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')), "
                + "FOREIGN KEY (provider_id) REFERENCES provider_config(id) ON DELETE CASCADE)");
    }

    private String extractHeaderRulesJson(int providerId, String customTransforms) {
        String source = customTransforms == null || customTransforms.isBlank() ? "{}" : customTransforms;
        try {
            JsonNode root = objectMapper.readTree(source);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("custom_transforms 必须是 JSON 对象");
            }
            JsonNode headers = root.get("custom_headers");
            if (headers == null || headers.isNull()) {
                return "[]";
            }
            if (!headers.isArray()) {
                throw new IllegalStateException("custom_headers 必须是 JSON 数组");
            }
            return objectMapper.writeValueAsString(headers);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "迁移 provider_config.id=" + providerId + " 的请求头规则失败: " + e.getMessage(), e);
        }
    }

    private void verifyProviderRequestTransforms(int expectedProviderCount) {
        Integer actualCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_request_transform", Integer.class);
        if (actualCount == null || actualCount != expectedProviderCount) {
            throw new IllegalStateException("供应商请求转换配置数量校验失败，预期 "
                    + expectedProviderCount + "，实际 " + actualCount);
        }

        var rows = jdbcTemplate.queryForList(
                "SELECT provider_id, header_rules_json, body_template_keys_json, "
                        + "body_preview_json, body_rules_json FROM provider_request_transform");
        for (var row : rows) {
            int providerId = ((Number) row.get("provider_id")).intValue();
            try {
                JsonNode headers = objectMapper.readTree((String) row.get("header_rules_json"));
                JsonNode templateKeys = objectMapper.readTree((String) row.get("body_template_keys_json"));
                JsonNode preview = objectMapper.readTree((String) row.get("body_preview_json"));
                JsonNode rules = objectMapper.readTree((String) row.get("body_rules_json"));
                if (!headers.isArray() || !templateKeys.isArray() || !preview.isObject()
                        || !rules.isObject() || rules.path("version").asInt() != 1
                        || !rules.path("rules").isArray()) {
                    throw new IllegalStateException("JSON 结构不符合 V5 协议");
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        "校验 provider_id=" + providerId + " 的请求转换配置失败: " + e.getMessage(), e);
            }
        }
    }

    private void createProviderApiKeyTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS provider_api_key ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "key_uuid TEXT NOT NULL UNIQUE, "
                + "provider_id INTEGER NOT NULL, "
                + "key_name TEXT NOT NULL DEFAULT '', "
                + "encrypted_api_key TEXT NOT NULL, "
                + "nonce TEXT NOT NULL, "
                + "encryption_version INTEGER NOT NULL DEFAULT 1 CHECK (encryption_version >= 1), "
                + "is_active INTEGER NOT NULL DEFAULT 0 CHECK (is_active IN (0, 1)), "
                + "sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0), "
                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')), "
                + "updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')), "
                + "UNIQUE (provider_id, key_name), "
                + "FOREIGN KEY (provider_id) REFERENCES provider_config(id) ON DELETE CASCADE)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_provider_api_key_provider_id "
                + "ON provider_api_key(provider_id)");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_provider_api_key_active "
                + "ON provider_api_key(provider_id) WHERE is_active = 1");
    }

    /**
     * 校验或首次写入主密钥指纹。
     *
     * 数据库已有指纹且与当前主密钥不一致时抛异常，阻止用错误密钥启动并破坏已有密文。
     */
    private void verifyOrRecordKeyFingerprint() {
        String stored = appConfigRepository.findConfigValue("encryption_key_fingerprint");
        String current = cryptoService.fingerprint();
        if (stored == null || stored.isBlank()) {
            appConfigRepository.saveConfig("encryption_key_fingerprint", current);
        } else if (!stored.equals(current)) {
            throw new IllegalStateException(
                    "COSP_MASTER_KEY 与当前数据库不匹配，无法解密已加密的供应商 API Key。"
                            + "请恢复原主密钥，切勿用新密钥覆盖，否则已有 API Key 将永久无法恢复。");
        }
    }

    private List<Map<String, String>> parseApiKeyArray(String apiKeyJson) {
        if (apiKeyJson == null || apiKeyJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(apiKeyJson, API_KEY_LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("解析 provider_config.api_key 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 为迁移生成唯一 key_name，避免同一供应商下重名违反 UNIQUE(provider_id, key_name)。
     * 空名或重名时用 "Key N" 兜底。
     */
    private String deduplicateName(String rawName, int index, java.util.Set<String> usedNames) {
        String name = (rawName == null || rawName.isBlank()) ? ("Key " + (index + 1)) : rawName;
        String candidate = name;
        int suffix = 2;
        while (usedNames.contains(candidate)) {
            candidate = name + " (" + suffix + ")";
            suffix++;
        }
        usedNames.add(candidate);
        return candidate;
    }

    private void insertEncryptedKey(int providerId, String keyName, String plaintext,
                                    boolean active, int sortOrder) {
        ApiKeyCryptoService.EncryptedValue encrypted = cryptoService.encrypt(plaintext);
        // 立即解密校验，确保加密可逆且与原文一致
        String roundTrip = cryptoService.decrypt(encrypted.nonceBase64(), encrypted.ciphertextBase64());
        if (!plaintext.equals(roundTrip)) {
            throw new IllegalStateException("provider_id=" + providerId + " 的 API Key 加密自校验失败");
        }
        jdbcTemplate.update(
                "INSERT INTO provider_api_key "
                        + "(key_uuid, provider_id, key_name, encrypted_api_key, nonce, encryption_version, is_active, sort_order) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), providerId, keyName, encrypted.ciphertextBase64(),
                encrypted.nonceBase64(), ApiKeyCryptoService.ENCRYPTION_VERSION, active ? 1 : 0, sortOrder);
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
        createProviderConfigValidationTriggers();
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

    private void createProviderConfigValidationTriggers() {
        createTrigger("trg_provider_config_validate_insert", "provider_config", "INSERT",
                "NEW.enabled NOT IN (0, 1)");
        createTrigger("trg_provider_config_validate_update", "provider_config", "UPDATE",
                "NEW.enabled NOT IN (0, 1)");
    }

    private void dropTrigger(String name) {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + name);
    }
}

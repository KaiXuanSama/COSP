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
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                + "version INTEGER PRIMARY KEY, description TEXT NOT NULL, "
                + "applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");

        migrate(1, "补齐历史增量字段", this::migrateLegacyColumns);
        migrate(2, "API Key 转换为 JSON 数组", this::migrateApiKeyToJsonArray);
        migrate(3, "增加业务约束与查询索引", this::migrateConstraintsAndIndexes);
        migrate(4, "API Key 拆表与加密", this::migrateApiKeysToEncryptedTable);
        migrate(5, "新增供应商请求转换配置表", this::migrateProviderRequestTransforms);
        migrate(6, "清理遗留请求转换配置", this::clearLegacyRequestTransforms);
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

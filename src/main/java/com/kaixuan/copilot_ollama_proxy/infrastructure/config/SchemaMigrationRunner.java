package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.MaxOutputTokensSetting;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ReasoningEffortSetting;
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
    private static final double V8_4_VERSION = 8.4;
    private static final double V8_5_VERSION = 8.5;
    private static final double V8_6_VERSION = 8.6;
    private static final double V8_7_VERSION = 8.7;
    private static final double V8_8_VERSION = 8.8;
    private static final double V8_9_VERSION = 8.9;
    /** V9 起版本号为整数；{@code a.b} 作为 double 会让 V8.10 碎成 V8.1。 */
    private static final double CURRENT_SCHEMA_VERSION = 9;
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
    /**
     * V5 时代的空规则集字面量。
     *
     * <p><strong>不要升到 V2。</strong>V5 迁移写入的行会被同一次迁移里的
     * {@code verifyProviderRequestTransforms} 校验，而那段历史校验要求 {@code version == 1}。
     * 库里的规则由 V8.7 统一升格 —— 历史迁移只负责把数据带到它那个年代的形态。
     */
    private static final String EMPTY_BODY_RULES_JSON = "{\"version\":1,\"rules\":[]}";
    /** 当前的空规则集字面量（V2 规则组）。 */
    private static final String EMPTY_BODY_RULES_JSON_V2 = "{\"version\":2,\"groups\":[]}";
    /** 当前请求体规则集的协议版本，与 {@code body_rules_version} 列取值一致。 */
    private static final int CURRENT_BODY_RULES_VERSION = 2;
    /**
     * V8.8 为存量供应商回填的协议支持集合。
     *
     * <p>回填成「两种都支持」而非「只支持 OpenAI」：迁移不得改变存量运行时行为。
     * V8.8 之前 {@code ProviderProtocolSupport} 对所有供应商一律返回全集，
     * 若这里收窄成 OpenAI，正在使用 Anthropic 线路的供应商会在升级瞬间全部失效。
     * 收窄是用户的决定，不是迁移的决定 —— 前端新建表单可以只默认勾 OpenAI。
     */
    private static final String DEFAULT_SUPPORTED_PROTOCOLS_JSON = "[\"OPENAI\",\"ANTHROPIC\"]";
    /** 当前思考深度配置的结构版本，与 {@code reasoning_effort_schema} 列取值一致。 */
    private static final int CURRENT_REASONING_EFFORT_VERSION = 2;
    /**
     * V9 重建 {@code provider_model} 后该表的完整 DDL，与 {@code schema.sql} 逐字对应。
     *
     * <p>必须重建而非 {@code ALTER}：{@code max_output_tokens} 原本是
     * {@code INTEGER ... CHECK (max_output_tokens >= 0)}，SQLite 不允许修改已有列的
     * 类型与约束。旧的数值约束对 JSON 文本永远为真（SQLite 把非数字文本当 0 比），
     * 留着就是一条永不生效的约束，而 {@code json_valid} 才是新形态真正需要的校验。
     */
    private static final String PROVIDER_MODEL_DDL_V9 = "CREATE TABLE provider_model ("
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
            + "reasoning_effort_schema INTEGER NOT NULL DEFAULT 2 CHECK (reasoning_effort_schema >= 1), "
            + "sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0), "
            + "FOREIGN KEY (provider_id) REFERENCES provider_config(id) ON DELETE CASCADE)";
    /**
     * V9 对存量最大输出值的一次性重映射：旧值 → 新值。
     *
     * <p>必须是一次性的同时映射，不能写成两条顺序 UPDATE：128000 既是「降为 4000」
     * 的源，又是「256000/512000 降过来」的靶。先改后者再改前者，原本选 512K 的模型会
     * 一路滴到 4K；反之则原 128K 的行会先变 4000 再不变。同时映射让两条规则各自成立。
     *
     * <p>未列举的值（包括 4096、8192 这类二进制值、以及用户手填的任意数）原值保留。
     */
    private static final Map<Integer, Integer> V9_MAX_OUTPUT_REMAP = Map.of(
            128_000, 4_000,
            256_000, 128_000,
            512_000, 128_000);
    /**
     * 版本比较的容差。
     *
     * <p>V8.1 至 V8.9 这批 {@code a.b} 版本号作为 double 都是不可精确表示的近似值，
     * 而它们必须永久留在注册表里供旧库升级。同一个字面量与 SQLite REAL 往返后通常逐位相同，
     * 因此 {@code >=} 已经够用；容差只是防止某个库里的版本值来自别处（例如手工修过、
     * 或经由文本转换）而比字面量低了一个尾数位，从而让一个其实已应用的迁移被重放。
     *
     * <p>取 1e-6 是因为相邻版本号的最小间隔是 0.1，两者相差五个数量级，
     * 不存在把两个不同版本判成同一个的风险。V9 起版本号为整数，本容差届时只服务历史版本。
     */
    private static final double VERSION_COMPARISON_EPSILON = 1e-6;

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
        if (!isRegisteredVersion(targetVersion)) {
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
            if (!isSameVersion(targetVersion, CURRENT_SCHEMA_VERSION)) {
                throw new IllegalStateException("当前 Schema 无版本记录时只能建立最新基线");
            }
            establishCurrentBaseline();
            return;
        }

        prepareHistoricalVersionTracking();
        applyMigrationsThrough(historicalMigrations(), targetVersion);
    }

    private boolean isRegisteredVersion(double version) {
        return registeredMigrationVersions().stream().anyMatch(registered -> isSameVersion(registered, version));
    }

    /**
     * 按容差比较两个版本号是否同一个。
     *
     * <p>历史的 {@code a.b} 版本号作为 double 是近似值，{@code ==} 只在两侧来自同一字面量时可靠。
     * 相邻版本相差 0.1，与容差差五个数量级，不存在误判为同一版本的可能。
     */
    private static boolean isSameVersion(double left, double right) {
        return Math.abs(left - right) < VERSION_COMPARISON_EPSILON;
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
                new MigrationStep(V8_4_VERSION, "新增用量时间范围查询索引",
                        this::migrateToV84AddUsageCreatedAtIndex),
                new MigrationStep(V8_5_VERSION, "日志保留改为载荷瘦身",
                        this::migrateToV85AddPayloadTrimmedFlag),
                new MigrationStep(V8_6_VERSION, "调用日志记录上下游线路协议",
                        this::migrateToV86AddCallLogProtocols),
                new MigrationStep(V8_7_VERSION, "请求体规则升级为按线路分组",
                        this::migrateToV87RuleGroups),
                new MigrationStep(V8_8_VERSION, "供应商声明支持的线路协议与 Anthropic 端点",
                        this::migrateToV88ProviderProtocols),
                new MigrationStep(V8_9_VERSION, "思考深度升级为档位与注入模式",
                        this::migrateToV89ReasoningEffortModes),
                new MigrationStep(CURRENT_SCHEMA_VERSION, "最大输出升级为上限与注入模式",
                        this::migrateToV9MaxOutputModes));
    }

    /**
     * 已处于 V7 之后的库需要执行的迁移：严格晚于库当前版本的那些。
     *
     * <p>下界取自库里记录的版本而非 {@link #V7_BASELINE_VERSION}：后者会把 V7.1 起的全部迁移
     * 都交给 {@link #migrate} 去逐个判定，一旦那层判定失效就是全量重放。两层各自独立成立，
     * 是因为「哪些迁移需要跑」本就该由版本区间回答，而不该依赖执行时的兜底。
     */
    private List<MigrationStep> baselineMigrations() {
        Double recorded = readBaselineVersion();
        double lowerBound = recorded != null ? recorded : V7_BASELINE_VERSION;
        return historicalMigrations().stream()
                .filter(step -> step.version() > lowerBound + VERSION_COMPARISON_EPSILON)
                .toList();
    }

    private void applyMigrationsThrough(List<MigrationStep> migrations, double targetVersion) {
        for (MigrationStep migration : migrations) {
            if (migration.version() <= targetVersion + VERSION_COMPARISON_EPSILON) {
                migrate(migration.version(), migration.description(), migration.action());
            }
        }
    }

    /**
     * 判断数据库是否已处于当前单行基线。
     *
     * <h2>为何只看版本号</h2>
     * 这里曾额外校验十来个表/列/索引是否存在，用意是「版本号说到位了，再要一份结构证据佐证」。
     * 那层校验在迁移调度按行等值判定的年代确有作用：判定失效时不短路就等于全量重放，
     * 于是结构谓词顺带承担了「发现结构缺失并重新补齐」的角色。
     *
     * <p>调度改为版本区间过滤后，这个角色消失了。假设某个库记录 8.9 但缺了一列：
     * 结构谓词让本方法返回 false、不短路，接着 {@link #baselineMigrations()} 以 8.9 为下界
     * 过滤出空列表，一个迁移都不会执行 —— 与短路的结局完全相同，只是白跑十几次 PRAGMA。
     * 也就是说那些谓词已经无法改变任何结果，只剩启动开销。
     *
     * <p>更根本的一点：结构证据本就无法覆盖所有迁移。V8.7 与 V8.9 只改列里的 JSON 形态，
     * 为了让它们「有结构可查」才额外加了两个 schema 版本列。既然判定退回纯版本比较，
     * 未来的纯数据迁移不再需要为了被判定而虚构一个结构标记。
     *
     * <p>版本号与结构不一致的库（人为改库、迁移中途崩溃）确实不再被本方法发现。
     * 但那种库原先也只是「不短路」而已，并不会被修复，所以这不是能力上的退步。
     */
    private boolean isCurrentBaseline() {
        Double version = readBaselineVersion();
        return version != null && version >= CURRENT_SCHEMA_VERSION - VERSION_COMPARISON_EPSILON;
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
                    CURRENT_SCHEMA_VERSION,
                    formatVersion(CURRENT_SCHEMA_VERSION)
                            + " 架构基线：统一供应商实现、token 用量表、日志载荷瘦身与线路协议、"
                            + "请求体规则分组、供应商协议支持与 Anthropic 端点、思考深度注入模式"));
        log.info("[SchemaMigration] 已建立 {} 架构基线", formatVersion(CURRENT_SCHEMA_VERSION));
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

    /**
     * 执行单个迁移，已应用过的直接跳过。
     *
     * <p>「是否已应用」在 V7 前后是两种不同的问题，因为版本记录表本身是两种形态：
     * <ul>
     *   <li>V1-V6 的多行表逐版本 INSERT，于是「表里有没有这一行」精确等价于「跑过没有」；</li>
     *   <li>V7 起的单行表只保存当前版本，判定必须是<strong>版本比较</strong> ——
     *       库停在 8.9 时，8.5 早已应用，尽管表里找不到 {@code version = 8.5} 这一行。</li>
     * </ul>
     *
     * <p>这里的行等值判定曾被无差别用于两种形态，导致跨版本升级时 V7 之后的每个迁移
     * 都被判成未应用而重放，且唯一被跳过的是恰好等于库当前版本的那一个。重放本应由各迁移体
     * 的幂等性兜住，但幂等只覆盖 DDL：V8.6 的协议回填会改写应用层已正确写入的日志，
     * V8 的键收敛会删掉名字以 {@code custom-} 开头的合法供应商及其 API Key。
     */
    private void migrate(double version, String description, Runnable action) {
        if (version >= V7_BASELINE_VERSION) {
            if (isAppliedOnBaseline(version)) {
                return;
            }
            transactionTemplate.executeWithoutResult(status -> action.run());
            log.info("[SchemaMigration] 已应用 {}: {}", formatVersion(version), description);
            return;
        }
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
        log.info("[SchemaMigration] 已应用 {}: {}", formatVersion(version), description);
    }

    /**
     * 判断单行基线表记录的版本是否已覆盖给定迁移。
     *
     * <p>没有记录时返回 false：此时库正沿历史路径升级，V7 迁移体尚未建出单行表，
     * 该版本自然还没应用过。
     */
    private boolean isAppliedOnBaseline(double version) {
        Double recorded = readBaselineVersion();
        return recorded != null && recorded >= version - VERSION_COMPARISON_EPSILON;
    }

    /** 读取单行基线表的当前版本；表或行不存在时返回 null。 */
    private Double readBaselineVersion() {
        if (!tableExists("schema_version") || !columnExists("schema_version", "id")) {
            return null;
        }
        return jdbcTemplate.query("SELECT version FROM schema_version WHERE id = 1",
                resultSet -> resultSet.next() ? resultSet.getDouble("version") : null);
    }

    /**
     * 把版本号格式化为日志与描述里的显示形式。
     *
     * <p>V9 起版本号是整数值的 double，直接打印会得到 {@code V9.0} —— 那正是本次要摆脱的
     * {@code a.b} 形态。可无损转为整数时去掉小数部分，历史的 {@code a.b} 版本原样保留。
     */
    private static String formatVersion(double version) {
        return version == Math.rint(version)
                ? "V" + (long) version
                : "V" + version;
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
            V8_4_VERSION, "V8.4 增量迁移：新增用量时间范围查询索引");
    }

    /**
     * V8.5：为 api_call_log 补 payload_trimmed 标记，日志保留策略由整行删除改为载荷瘦身。
     *
     * 纯加列迁移，不动既有数据、不改任何约束。新列默认 0，故所有存量行都被视为「载荷完整」——
     * 这与事实一致：此前的保留任务只做整行删除，从不清空字段，活着的行必然携带完整载荷。
     *
     * 1. 为何需要显式标记而非判 request_body IS NULL
     *    NULL 无法区分「上游/本次调用本就没有该字段」与「已被保留任务清除」。前者应展示为
     *    无内容，后者应展示为已清理，两者在详情页是不同的语义。多数写入路径确实会留下
     *    部分 NULL 列（如流式调用的 response_body），仅凭 NULL 判定必然误报。
     *
     * 2. 为何这是使 api_call_log 成为 api_call_usage 超集的前提
     *    此前日志按条数整行删除，而用量表永不裁剪，于是老数据段存在大量「孤儿用量行」
     *    （log_id 悬空），日志表在时间维度上反而是用量表的子集。改为瘦身后行永久保留，
     *    调用元信息（状态码、耗时）不再随载荷一同消失，两表在新数据段上恢复为
     *    「日志 ⊇ 用量」的稳定包含关系，明细视图因此可以单表分页而无需跨表 UNION。
     *
     * 3. 为何不回填历史孤儿
     *    被删除的行已物理消失，状态码与耗时无从恢复；凭用量行反向插入只会产出一批
     *    关键字段为空的伪日志。存量孤儿的数据价值已由概览页的聚合视图承载，
     *    此处不做补偿。
     *
     * 4. ADD COLUMN 携带 CHECK 的可行性
     *    SQLite 对 ALTER TABLE ADD COLUMN 的限制是不得为 PRIMARY KEY / UNIQUE、
     *    NOT NULL 必须带非 NULL 默认值，CHECK 不在禁止之列。因此这里与 schema.sql
     *    的列定义保持完全一致，避免「新库有约束、升级库没有」的分叉。
     *
     * 5. 为何要判表存在
     *    columnExists 在表缺失时同样返回 false，仅凭它会对不存在的表执行 ALTER 而报错。
     *    真实数据库由 schema.sql 保证该表存在，但迁移不应依赖这一前提 ——
     *    表缺失时跳过即可，后续 schema.sql 会以最终结构建出带该列的表。
     */
    private void migrateToV85AddPayloadTrimmedFlag() {
        if (tableExists("api_call_log") && !columnExists("api_call_log", "payload_trimmed")) {
            jdbcTemplate.execute("ALTER TABLE api_call_log ADD COLUMN payload_trimmed "
                    + "INTEGER NOT NULL DEFAULT 0 CHECK (payload_trimmed IN (0, 1))");
        }
        jdbcTemplate.update("UPDATE schema_version SET version = ?, description = ?, "
                + "applied_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE id = 1",
                V8_5_VERSION, "V8.5 增量迁移：日志保留改为载荷瘦身");
    }

    /**
     * V8.6：记录一次调用两侧实际采用的线路协议。
     *
     * <p>翻译层尚未实现，故存量日志必为直连：下游与上游协议相同。迁移依据历史
     * chunk：流式 OpenAI 的终止块是 {@code [DONE]}，而 Anthropic 使用 {@code message_stop}
     * 并不会出现该块。因 {@code chunks} 仅存流式载荷，NULL 统一回填 OPENAI：其中既包括
     * 所有非流式记录，也包括载荷已瘦身的旧记录；后者发生在 Anthropic 接口出现之前，
     * 因而该保守归类与历史事实一致。
     */
    private void migrateToV86AddCallLogProtocols() {
        if (tableExists("api_call_log")) {
            addColumnIfNotExists("api_call_log", "downstream_protocol",
                    "TEXT NOT NULL DEFAULT 'OPENAI' CHECK (downstream_protocol IN ('OPENAI', 'ANTHROPIC'))");
            addColumnIfNotExists("api_call_log", "upstream_protocol",
                    "TEXT NOT NULL DEFAULT 'OPENAI' CHECK (upstream_protocol IN ('OPENAI', 'ANTHROPIC'))");
            jdbcTemplate.update("UPDATE api_call_log SET downstream_protocol = 'ANTHROPIC', "
                    + "upstream_protocol = 'ANTHROPIC' WHERE chunks IS NOT NULL AND chunks NOT LIKE '%[DONE]%'");
        }
        jdbcTemplate.update("UPDATE schema_version SET version = ?, description = ?, "
                + "applied_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE id = 1",
                V8_6_VERSION, "V8.6 增量迁移：调用日志记录上下游线路协议");
    }

    /**
     * V8.7：把请求体规则集从 V1 扁平列表升为 V2 规则组，并记录规则集结构版本。
     *
     * <p>V2 的形态是 {@code {version:2, groups:[...]}}，每个规则组自带 {@code protocols}
     * （适用线路）、{@code templateKeys} 与 {@code previewBody}（该组专属的调试样本）。
     *
     * <h2>为何这不是一次「伪迁移」</h2>
     * 表结构本身确实只多一列，真正的变化在 {@code body_rules_json} 的内容形态。但它必须走
     * 迁移体系而非留给应用层惰性升级：只要库里可能残留 V1，所有读取点就都得同时理解两种格式，
     * 而「两种格式并存」正是要消除的状态。过一遍 V8.7 后全库同格式，读取端的 V1 兼容
     * 就退化为纯粹的向后兜底而非常态路径。
     *
     * <h2>为何当时加了 {@code body_rules_schema} 列</h2>
     * 当时 {@link #isCurrentBaseline()} 除版本号外还要求一排结构性谓词，而迁移调度又是行等值判定：
     * 不短路就等于全量重放。本次只改列里的 JSON 形态、没有结构变化，唯一可查的证据就是
     * 「挑一行看它是不是 V2」—— 而空库根本没有行，于是额外加了这一列来凑出结构证据。
     *
     * <p><strong>这个理由已不再成立。</strong>调度改为版本区间过滤后，
     * {@code isCurrentBaseline()} 只看版本号，纯数据迁移不需要为了被判定而虚构结构标记。
     * 本列保留只为两件事：已入库无法回收，以及直接查库排障时能一眼看出该列按哪个版本解读。
     * 下一次纯数据迁移不要照搬这个做法。
     *
     * <h2>旧的两个编辑器列</h2>
     * {@code body_template_keys_json} 与 {@code body_preview_json} 保留为 legacy：
     * 它们的内容被搬进第一个规则组，此后不再是配置来源。不物理删列是因为 SQLite 删列
     * 需要重建表（连带重建外键与触发器），而它们的存在成本只是两个不再读取的字段；
     * 保存路径仍写入首组的值，让旧版本回滚时还能读到一份有意义的样本而非空对象。
     *
     * <h2>迁移后的协议归属</h2>
     * 存量规则一律归入 {@code protocols:["OPENAI"]} 单组，而非「两条线路都执行」。
     * 那些规则的字段路径是照 OpenAI 请求体写的（{@code messages} 里含 system、
     * 无 {@code max_tokens}），作用在 Anthropic 请求体上多数匹配不到 ——
     * 静默失效比不执行更难排查。这与前端 {@code migrateRuleSet} 的判断一致。
     */
    private void migrateToV87RuleGroups() {
        if (tableExists("provider_request_transform")) {
            addColumnIfNotExists("provider_request_transform", "body_rules_schema",
                    "INTEGER NOT NULL DEFAULT " + CURRENT_BODY_RULES_VERSION
                            + " CHECK (body_rules_schema >= 1)");
            if (columnExists("provider_request_transform", "body_rules_json")) {
                upgradeRuleSetsToV2();
            }
        }
        jdbcTemplate.update("UPDATE schema_version SET version = ?, description = ?, "
                + "applied_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE id = 1",
                V8_7_VERSION, "V8.7 增量迁移：请求体规则升级为按线路分组");
    }

    /**
     * V8.8：供应商显式声明支持的线路协议，并可为 Anthropic 配置独立端点。
     *
     * <h2>为何要落库，而不是继续乐观假设</h2>
     * V8.8 之前 {@code ProviderProtocolSupport} 对所有供应商返回「两种都支持」，代价是
     * 下游打 {@code /v1/messages}、而供应商实际只有 OpenAI 端点时，失败发生在上游侧 ——
     * 日志里是一个上游 404，看起来像上游故障。落库后这类请求被提前拦下，原因明确。
     *
     * <h2>为何 Anthropic 端点要独立成列</h2>
     * 原先由 {@code base_url} 拼 {@code /messages} 得到端点，这对「Base URL 恰好带 /v1」
     * 的供应商成立，但中转站把 Anthropic 端点摆在哪里是不可预测的。继续在代码里猜，
     * 结果是「配了却调不通」且无从排查；给出一列让用户显式声明，猜错的可能性归零。
     *
     * <h2>两个回填口径不同，都是刻意的</h2>
     * <ul>
     *   <li>协议集合回填<strong>全集</strong>（{@link #DEFAULT_SUPPORTED_PROTOCOLS_JSON}）：
     *       与升级前的乐观假设完全一致，因此升级不改变任何供应商的可用性。</li>
     *   <li>Anthropic 端点回填 {@code base_url} 的<strong>原值</strong>：升级前的行为正是
     *       「用 base_url 拼 /messages」，照抄原值才能让这一行为在新的读取路径下保持不变。
     *       留空同样会回退到 base_url，但显式写入让用户在界面上能直接看到当前生效的地址，
     *       而不是一个空输入框加一句「留空则复用」—— 后者要理解回退规则才能读懂。</li>
     * </ul>
     *
     * <p>回填只针对 NULL 与空串：{@code ALTER TABLE ADD COLUMN} 会把已有行填成默认值，
     * 而重跑迁移时不该覆盖用户此后改过的配置。
     */
    private void migrateToV88ProviderProtocols() {
        if (tableExists("provider_config")) {
            addColumnIfNotExists("provider_config", "supported_protocols",
                    "TEXT NOT NULL DEFAULT '" + DEFAULT_SUPPORTED_PROTOCOLS_JSON + "' "
                            + "CHECK (json_valid(supported_protocols))");
            addColumnIfNotExists("provider_config", "anthropic_base_url", "TEXT NOT NULL DEFAULT ''");
            jdbcTemplate.update("UPDATE provider_config SET supported_protocols = ? "
                    + "WHERE supported_protocols IS NULL OR trim(supported_protocols) = ''",
                    DEFAULT_SUPPORTED_PROTOCOLS_JSON);
            if (columnExists("provider_config", "base_url")) {
                jdbcTemplate.update("UPDATE provider_config SET anthropic_base_url = base_url "
                        + "WHERE (anthropic_base_url IS NULL OR trim(anthropic_base_url) = '') "
                        + "AND base_url IS NOT NULL AND trim(base_url) <> ''");
            }
        }
        jdbcTemplate.update("UPDATE schema_version SET version = ?, description = ?, "
                + "applied_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE id = 1",
                V8_8_VERSION, "V8.8 增量迁移：供应商声明支持的线路协议与 Anthropic 端点");
    }

    /**
     * V8.9：把思考深度从纯档位字符串升为「档位 + 注入模式」的 JSON。
     *
     * <p>V2 形态是 {@code {"reasoning_effort":"medium","overwrite_mode":"fallback"}}，
     * 四种模式的语义见 {@link ReasoningEffortSetting.Mode}。
     *
     * <h2>为何走迁移而不是继续运行时收敛</h2>
     * 读取侧本就兼容旧形态，功能上不迁移也能跑。但「库里长期混着两种形态」有两处实际代价：
     * 一是每次读取都要判断形态，而那段兼容代码没有任何办法被证明可以删除；
     * 二是直接查库排查问题时，同一列出现两种写法，无法一眼看出某个模型到底配了什么。
     * 过一遍 V8.9 后全库同形态，读取端的旧格式兼容就退化为纯粹的向后兜底而非常态路径。
     *
     * <h2>为何当时加了 {@code reasoning_effort_schema} 列</h2>
     * 与 V8.7 同一个理由：当时的基线判定除版本号外还要求结构性证据，
     * 而迁移调度的行等值判定使得「不短路」等于全量重放，于是纯数据变更也得凑出一列。
     *
     * <p><strong>这个理由已不再成立。</strong>详见 {@link #isCurrentBaseline()}：
     * 基线判定现在只看版本号，本列保留只为已入库无法回收与查库排障时的可读性。
     *
     * <h2>转换口径</h2>
     * 全部委托 {@link ReasoningEffortSetting#parse} 与
     * {@link ReasoningEffortSetting#serialize}，不在这里重写一份字符串处理 ——
     * 迁移与运行时读取必须对「旧的 None 是什么意思」给出同一个答案，
     * 而保证这一点最可靠的办法是共用同一个实现。其中：
     * <ul>
     *   <li>{@code "Medium"} → {@code medium} + FALLBACK（等同升级前的行为）</li>
     *   <li>{@code "Medium,High"} → 取首项 + FALLBACK</li>
     *   <li>{@code "None"} → {@code medium} + <strong>DELETE</strong>，
     *       因为旧的 None 表达的正是「不向上游发送」；若回退成 FALLBACK，
     *       这些模型会在升级后突然开始向上游发送 medium</li>
     *   <li>已是 JSON 的行照常过一遍解析再序列化，从而收敛掉字段顺序与大小写差异</li>
     * </ul>
     *
     * <p>逐行读改而非一条 UPDATE：转换逻辑在 Java 里，SQL 无法表达
     * 「按四种历史形态分别解析」。模型行数量级是几十到几百，一次性读进内存无压力。
     */
    private void migrateToV89ReasoningEffortModes() {
        if (tableExists("provider_model")) {
            addColumnIfNotExists("provider_model", "reasoning_effort_schema",
                    "INTEGER NOT NULL DEFAULT " + CURRENT_REASONING_EFFORT_VERSION
                            + " CHECK (reasoning_effort_schema >= 1)");
            if (columnExists("provider_model", "reasoning_effort")) {
                upgradeReasoningEffortsToV2();
            }
        }
        jdbcTemplate.update("UPDATE schema_version SET version = ?, description = ?, "
                + "applied_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE id = 1",
                V8_9_VERSION, "V8.9 增量迁移：思考深度升级为档位与注入模式");
    }

    /**
     * V9：最大输出升级为「上限 + 注入模式」的 JSON，并对存量档位做一次性重映射。
     *
     * <h2>为何必须重建表</h2>
     * {@code max_output_tokens} 原本是 {@code INTEGER NOT NULL DEFAULT 128000
     * CHECK (max_output_tokens >= 0)}。SQLite 的 {@code ALTER TABLE} 改不了已有列的类型、
     * 默认值或 CHECK 约束，只能整表重建。留着旧约束不是「无害」：SQLite 比较非数字文本时
     * 当 0 处理，于是 {@code >= 0} 对任何 JSON 串都为真 —— 一条永不生效的约束，
     * 而新形态真正需要的是 {@code json_valid}。
     *
     * <h2>重建顺序</h2>
     * 先把值转换好再换表，而不是先换表再转换：转换要读旧列的整数语义，
     * 换表之后那一列已经是 TEXT，还得再判断「这是数字还是 JSON」。
     *
     * <p>重建期间必须先摘掉 V3 建的两个校验触发器 —— 它们的条件里有
     * {@code NEW.max_output_tokens < 0}，对 JSON 文本恒为假因而不会误报，
     * 但触发器绑在表名上，{@code DROP TABLE} 会连带删掉它们而不报错，
     * 于是重建后必须显式重建，否则其余几个字段的校验会静默失效。
     *
     * <h2>档位重映射</h2>
     * 见 {@link #V9_MAX_OUTPUT_REMAP}。未列举的值原样保留 —— 用户手填的 4096、8192
     * 或任何非标值都不该被一次升级悄悄改掉。
     */
    private void migrateToV9MaxOutputModes() {
        if (tableExists("provider_model") && columnExists("provider_model", "max_output_tokens")) {
            Map<Integer, String> converted = convertMaxOutputTokensToV9();
            rebuildProviderModelForV9(converted);
        }
        jdbcTemplate.update("UPDATE schema_version SET version = ?, description = ?, "
                + "applied_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE id = 1",
                CURRENT_SCHEMA_VERSION, "V9 增量迁移：最大输出升级为上限与注入模式");
    }

    /**
     * 读出每一行的最大输出并算出它的 V9 JSON 形态，返回 {@code 模型行 id -> JSON}。
     *
     * <p>只计算不写入：写入要等表重建完成，否则旧列的 INTEGER 亲和性会把 JSON 串
     * 存成 0（SQLite 对 INTEGER 列做类型转换，转不动才保留原文，行为依存储类而异）。
     *
     * <p>已是 JSON 的行照原样过一遍 parse + serialize，从而在重跑时收敛到自身 ——
     * 这是本迁移幂等的根据。重映射<strong>不</strong>作用于已是 JSON 的行：
     * 那些行的档位调整在首次运行时已经做过，再做一次会把 128000 又降成 4000。
     */
    private Map<Integer, String> convertMaxOutputTokensToV9() {
        var rows = jdbcTemplate.queryForList(
                "SELECT id, max_output_tokens FROM provider_model ORDER BY id");
        Map<Integer, String> converted = new LinkedHashMap<>();
        int remapped = 0;
        for (var row : rows) {
            int modelId = ((Number) row.get("id")).intValue();
            Object raw = row.get("max_output_tokens");
            String rawText = raw == null ? null : String.valueOf(raw);
            boolean alreadyJson = rawText != null && rawText.trim().startsWith("{");
            MaxOutputTokensSetting setting = MaxOutputTokensSetting.parse(rawText, objectMapper);
            if (!alreadyJson) {
                Integer target = V9_MAX_OUTPUT_REMAP.get(setting.maxOutputTokens());
                if (target != null) {
                    setting = new MaxOutputTokensSetting(target, setting.mode());
                    remapped++;
                }
            }
            converted.put(modelId, setting.serialize());
        }
        if (remapped > 0) {
            log.info("[SchemaMigration] V9 已按新档位调整 {} 个模型的最大输出", remapped);
        }
        return converted;
    }

    /**
     * 重建 {@code provider_model}，把最大输出列换成带 {@code json_valid} 约束的 TEXT。
     *
     * <p>沿用 SQLite 官方推荐的重建流程：建新表 → 搬数据 → 删旧表 → 改名 → 重建索引与触发器。
     * 不用 {@code PRAGMA foreign_keys=OFF}：迁移跑在事务里而 SQLite 不允许在事务中改这个
     * pragma，而这里也不需要 —— 子表只有 {@code provider_model} 自己引用 {@code provider_config}，
     * 重建的是引用方，被引用方的行没有动过。
     *
     * @param converted 模型行 id 到新 JSON 值的映射，来自 {@link #convertMaxOutputTokensToV9()}
     */
    private void rebuildProviderModelForV9(Map<Integer, String> converted) {
        dropTrigger("trg_provider_model_validate_insert");
        dropTrigger("trg_provider_model_validate_update");

        jdbcTemplate.execute("DROP TABLE IF EXISTS provider_model_v9_new");
        jdbcTemplate.execute(PROVIDER_MODEL_DDL_V9.replace(
                "CREATE TABLE provider_model", "CREATE TABLE provider_model_v9_new"));
        // 逐列显式列出：SELECT * 依赖列顺序，而这张表历经多次 ADD COLUMN，顺序不可假定。
        //
        // reasoning_effort 也要过一道 json_valid 兜底：新表对它有约束，而旧表没有。
        // 正常路径上 V8.9 已把该列收敛成 JSON，但若某一行因手工修改或历史遗留仍是裸档位
        // （如 'Medium'），整条 INSERT ... SELECT 会因 CHECK 失败而让 V9 整体回滚 ——
        // 一列与本次迁移无关的脏数据阻断另一列的升级。这里就地兜成默认值，
        // 而不是让升级失败：那一行的思考深度本来也读不出有效配置。
        jdbcTemplate.execute("INSERT INTO provider_model_v9_new "
                + "(id, provider_id, model_name, enabled, context_size, max_output_tokens, "
                + "caps_tools, caps_vision, reasoning_effort, reasoning_effort_schema, sort_order) "
                + "SELECT id, provider_id, model_name, enabled, context_size, "
                + "'{\"max_output_tokens\":4000,\"overwrite_mode\":\"fallback\"}', "
                + "caps_tools, caps_vision, "
                + "CASE WHEN json_valid(reasoning_effort) THEN reasoning_effort "
                + "ELSE '{\"reasoning_effort\":\"medium\",\"overwrite_mode\":\"fallback\"}' END, "
                + "reasoning_effort_schema, sort_order "
                + "FROM provider_model");
        jdbcTemplate.execute("DROP TABLE provider_model");
        jdbcTemplate.execute("ALTER TABLE provider_model_v9_new RENAME TO provider_model");

        // 搬迁时先填占位默认值，再逐行写入真实值：INSERT ... SELECT 无法表达 Java 侧算出的映射。
        for (var entry : converted.entrySet()) {
            jdbcTemplate.update("UPDATE provider_model SET max_output_tokens = ? WHERE id = ?",
                    entry.getValue(), entry.getKey());
        }

        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_provider_model_provider_name "
                + "ON provider_model(provider_id, model_name)");
        // 重建 V3 的校验触发器，但去掉已由 json_valid 接管的 max_output_tokens 数值条件。
        String modelInvalid = "NEW.enabled NOT IN (0, 1) OR NEW.caps_tools NOT IN (0, 1) "
                + "OR NEW.caps_vision NOT IN (0, 1) OR NEW.context_size < 0 "
                + "OR NEW.sort_order < 0";
        createTrigger("trg_provider_model_validate_insert", "provider_model", "INSERT", modelInvalid);
        createTrigger("trg_provider_model_validate_update", "provider_model", "UPDATE", modelInvalid);
    }

    /**
     * 逐行把 {@code reasoning_effort} 收敛为规范的 V2 JSON。
     *
     * <p>无条件重写每一行而非只挑旧形态：已是 JSON 的行经过一次 parse + serialize 后
     * 字段顺序、档位大小写、模式名都被归一化，于是迁移后全库<strong>逐字节同形态</strong>。
     * 只挑旧形态会留下「都是 JSON 但写法各异」的状态，而那与迁移的目的相违。
     *
     * <p>本方法幂等：规范形态再过一遍 parse + serialize 得到自身。
     */
    private void upgradeReasoningEffortsToV2() {
        var rows = jdbcTemplate.queryForList(
                "SELECT id, reasoning_effort FROM provider_model ORDER BY id");
        int converted = 0;
        for (var row : rows) {
            int modelId = ((Number) row.get("id")).intValue();
            String raw = (String) row.get("reasoning_effort");
            String canonical = ReasoningEffortSetting.parse(raw, objectMapper).serialize();
            if (canonical.equals(raw)) {
                continue;
            }
            jdbcTemplate.update("UPDATE provider_model SET reasoning_effort = ?, "
                    + "reasoning_effort_schema = ? WHERE id = ?",
                    canonical, CURRENT_REASONING_EFFORT_VERSION, modelId);
            converted++;
        }
        if (converted > 0) {
            log.info("[SchemaMigration] V8.9 已将 {} 个模型的思考深度升级为档位与注入模式", converted);
        }
    }

    /**
     * 逐行把 {@code body_rules_json} 升为 V2。
     *
     * <p>已是 V2 的行只更新 {@code body_rules_schema}，不重写 JSON —— 迁移必须幂等，
     * 且重写会打乱用户已有的组顺序与 ID。无法解析的行替换为空 V2 规则集并留下警告：
     * 让一行坏数据阻断整库升级是最差的选择，而留着一份读不懂的 JSON 只会让
     * 「全库同格式」这个不变量名存实亡。
     *
     * <p>两个 legacy 列可能在极旧的库里不存在，故取值前逐列判存 —— 缺失时退到各自默认值。
     */
    private void upgradeRuleSetsToV2() {
        boolean hasTemplateKeys = columnExists("provider_request_transform", "body_template_keys_json");
        boolean hasPreview = columnExists("provider_request_transform", "body_preview_json");
        String columns = "provider_id, body_rules_json"
                + (hasTemplateKeys ? ", body_template_keys_json" : "")
                + (hasPreview ? ", body_preview_json" : "");
        var rows = jdbcTemplate.queryForList(
                "SELECT " + columns + " FROM provider_request_transform ORDER BY provider_id");
        for (var row : rows) {
            int providerId = ((Number) row.get("provider_id")).intValue();
            String upgraded = upgradeRuleSetJson(providerId, (String) row.get("body_rules_json"),
                    hasTemplateKeys ? (String) row.get("body_template_keys_json") : null,
                    hasPreview ? (String) row.get("body_preview_json") : null);
            if (upgraded == null) {
                jdbcTemplate.update("UPDATE provider_request_transform SET body_rules_schema = ? "
                        + "WHERE provider_id = ?", CURRENT_BODY_RULES_VERSION, providerId);
                continue;
            }
            jdbcTemplate.update("UPDATE provider_request_transform SET body_rules_json = ?, "
                            + "body_rules_version = ?, body_rules_schema = ? WHERE provider_id = ?",
                    upgraded, CURRENT_BODY_RULES_VERSION, CURRENT_BODY_RULES_VERSION, providerId);
        }
    }

    /**
     * 把单个 V1 规则集包成 V2 单组；已是 V2 或无法解析时返回 null 表示不重写 JSON。
     */
    private String upgradeRuleSetJson(int providerId, String rulesJson,
                                      String templateKeysJson, String previewJson) {
        try {
            JsonNode ruleSet = rulesJson == null || rulesJson.isBlank()
                    ? null : objectMapper.readTree(rulesJson);
            if (ruleSet == null || !ruleSet.isObject()) {
                return EMPTY_BODY_RULES_JSON_V2;
            }
            int version = ruleSet.path("version").asInt(-1);
            if (version == CURRENT_BODY_RULES_VERSION && ruleSet.path("groups").isArray()) {
                return null;
            }
            if (version != 1 || !ruleSet.path("rules").isArray()) {
                log.warn("[SchemaMigration] V8.7 无法识别 provider_id={} 的请求体规则集，已替换为空 V2 规则集",
                        providerId);
                return EMPTY_BODY_RULES_JSON_V2;
            }
            return objectMapper.writeValueAsString(
                    buildLegacyRuleGroupSet(ruleSet.path("rules"), templateKeysJson, previewJson));
        } catch (Exception exception) {
            log.warn("[SchemaMigration] V8.7 解析 provider_id={} 的请求体规则集失败，已替换为空 V2 规则集: {}",
                    providerId, exception.getMessage());
            return EMPTY_BODY_RULES_JSON_V2;
        }
    }

    /**
     * 构造由 V1 升格而来的单个「OpenAI 规则组」。
     *
     * <p>组 ID 用固定字面量而非随机值：迁移必须可重放且结果可预期，随机 ID 会让
     * 「同一份输入升两次得到不同结果」，也让测试只能做模糊断言。
     */
    private Map<String, Object> buildLegacyRuleGroupSet(JsonNode rules, String templateKeysJson,
                                                        String previewJson) throws Exception {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", "group-legacy-openai");
        group.put("name", "OpenAI 规则组");
        group.put("order", 0);
        group.put("enabled", true);
        group.put("protocols", List.of("OPENAI"));
        group.put("templateKeys", parseArrayOrDefault(templateKeysJson, DEFAULT_BODY_TEMPLATE_KEYS_JSON));
        group.put("previewBody", parseObjectOrEmpty(previewJson));
        group.put("rules", rules);

        Map<String, Object> ruleSet = new LinkedHashMap<>();
        ruleSet.put("version", CURRENT_BODY_RULES_VERSION);
        ruleSet.put("groups", List.of(group));
        return ruleSet;
    }

    private JsonNode parseArrayOrDefault(String json, String fallbackJson) throws Exception {
        if (json != null && !json.isBlank()) {
            JsonNode parsed = objectMapper.readTree(json);
            if (parsed.isArray() && !parsed.isEmpty()) {
                return parsed;
            }
        }
        return objectMapper.readTree(fallbackJson);
    }

    private JsonNode parseObjectOrEmpty(String json) throws Exception {
        if (json != null && !json.isBlank()) {
            JsonNode parsed = objectMapper.readTree(json);
            if (parsed.isObject()) {
                return parsed;
            }
        }
        return objectMapper.createObjectNode();
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
    // 上面的 DEFAULT 是 V5 时代的 V1 字面量，与 EMPTY_BODY_RULES_JSON 同理不可升到 V2：
    // 这张表由 V5 迁移创建，同一次迁移里的 verifyProviderRequestTransforms 要求 version == 1。
    // 新建库走 schema.sql（那里是 V2 默认值），旧库走 V5 建表再由 V8.7 升格。

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
        // 历史条件，含 max_output_tokens 的数值判定 —— V3 时那一列还是 INTEGER。
        // V9 重建该表后会用不带这一条件的版本覆盖，本处不能跟改：
        // 历史迁移体必须堆出它当时的结构，否则 V3 检查点的断言与实际不符。
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

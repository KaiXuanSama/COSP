package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.kaixuan.copilot_ollama_proxy.application.runtime.MaxOutputTokensSetting;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务商配置数据访问层 — 操作 provider_config 与 provider_model 表。
 */
@Repository
public class ProviderConfigRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ProviderApiKeyRepository providerApiKeyRepository;

    public ProviderConfigRepository(JdbcTemplate jdbcTemplate, ProviderApiKeyRepository providerApiKeyRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.providerApiKeyRepository = providerApiKeyRepository;
    }

    // ==================== 查询 ====================

    /**
     * 查询所有服务商配置（含模型列表）。
     */
    public List<ProviderConfigRow> findAllWithModels() {
        return loadProvidersWithModels(null, false, false);
    }

    /**
     * 根据 provider_key 查询单个服务商配置。
     */
    public ProviderConfigRow findByKey(String providerKey) {
        List<ProviderConfigRow> providers = loadProvidersWithModels(providerKey, false, false);
        return providers.isEmpty() ? null : providers.get(0);
    }

    // ==================== 写入 ====================

    /**
     * 保存服务商配置（UPSERT），不涉及 API Key（已迁移到 provider_api_key 表）。
     * 如果 provider_key 已存在则更新，否则插入。
     *
     * @return 对应的 provider_config.id
     */
    public int saveProvider(String providerKey, String displayName, boolean enabled, String baseUrl) {
        jdbcTemplate.update(
            "INSERT INTO provider_config (provider_key, display_name, enabled, base_url) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT(provider_key) DO UPDATE SET "
                + "display_name = excluded.display_name, enabled = excluded.enabled, base_url = excluded.base_url, "
                + "updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')",
            providerKey, resolveDisplayName(providerKey, displayName), enabled ? 1 : 0, baseUrl);
        return jdbcTemplate.queryForObject("SELECT id FROM provider_config WHERE provider_key = ?", Integer.class, providerKey);
    }

    /**
     * 保存服务商配置，显示名缺省时从 provider_key 推导。
     */
    public int saveProvider(String providerKey, boolean enabled, String baseUrl) {
        return saveProvider(providerKey, null, enabled, baseUrl);
    }

    /**
     * 更新供应商的线路协议配置（支持的协议集合与 Anthropic 独立端点）。
     *
     * <p><strong>两个参数都按「null 表示不改」处理</strong>，而非「null 表示清空」。
     * 协议配置目前只由后端接口写入，管理后台表单尚未提交这两个字段；若按「未提供即清空」
     * 处理，任何一次普通的供应商编辑都会把用户配好的协议支持抹平，而这个字段一旦被清成
     * 空数组，该供应商的所有调用都会被调度器拒绝 —— 一次无关的保存造成全面不可用，
     * 是最难联想到成因的那类故障。
     *
     * @param providerKey             供应商标识
     * @param supportedProtocolsJson  协议集合 JSON 字符串数组；null 表示保留原值
     * @param anthropicBaseUrl        Anthropic 独立端点；null 表示保留原值，空串表示回退到 base_url
     */
    public void updateProviderProtocols(String providerKey, String supportedProtocolsJson,
                                        String anthropicBaseUrl) {
        if (supportedProtocolsJson == null && anthropicBaseUrl == null) {
            return;
        }
        List<Object> arguments = new ArrayList<>();
        StringBuilder sql = new StringBuilder("UPDATE provider_config SET ");
        if (supportedProtocolsJson != null) {
            sql.append("supported_protocols = ?, ");
            arguments.add(supportedProtocolsJson);
        }
        if (anthropicBaseUrl != null) {
            sql.append("anthropic_base_url = ?, ");
            arguments.add(anthropicBaseUrl);
        }
        sql.append("updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE provider_key = ?");
        arguments.add(providerKey);
        jdbcTemplate.update(sql.toString(), arguments.toArray());
    }

    /**
     * 仅更新服务商的 base_url，不修改 enabled 状态，也不涉及 API Key。
     * 如果指定的 providerKey 不存在，则自动插入一条新记录（enabled = 0）。
     * @return 对应的 provider_config.id
     */
    public int updateProviderConfig(String providerKey, String baseUrl) {
        jdbcTemplate.update(
            "INSERT INTO provider_config (provider_key, display_name, enabled, base_url) "
                + "VALUES (?, ?, 0, ?) ON CONFLICT(provider_key) DO UPDATE SET "
                + "display_name = CASE WHEN trim(provider_config.display_name) = '' "
                + "THEN excluded.display_name ELSE provider_config.display_name END, "
                + "base_url = excluded.base_url, "
                + "updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')",
            providerKey, deriveDisplayNameFromKey(providerKey), baseUrl);
        return jdbcTemplate.queryForObject("SELECT id FROM provider_config WHERE provider_key = ?", Integer.class, providerKey);
    }

    /**
     * 在同一事务内保存供应商配置、API Key 列表和完整模型列表。
     *
     * 任意一步失败时，配置更新、API Key 变更、旧模型删除和新模型插入会整体回滚，
     * 避免只保存了一部分。API Key 通过 {@link ProviderApiKeyRepository} 加密后按 UUID 合并。
     *
     * @param providerKey 服务商标识
     * @param baseUrl API 基础地址
     * @param apiKeys API Key 输入列表（明文按需加密，未修改项沿用原密文）
     * @param models 完整模型列表
     * @return 对应的 provider_config.id
     */
    @Transactional
    public int saveProviderConfigWithModels(String providerKey, String baseUrl,
                                                          List<ProviderApiKeyRepository.ApiKeyInput> apiKeys,
                                            List<Map<String, Object>> models) {
          int providerId = updateProviderConfig(providerKey, baseUrl);
        providerApiKeyRepository.saveKeys(providerId, apiKeys);
        saveModels(providerId, models);
        return providerId;
    }

    /**
     * 保存模型列表（先删除旧列表，再批量插入）。
     */
    @Transactional
    public void saveModels(int providerId, List<Map<String, Object>> models) {
        // 删除该服务商下所有旧模型
        jdbcTemplate.update("DELETE FROM provider_model WHERE provider_id = ?", providerId);
        // 批量插入新模型
        if (models == null || models.isEmpty())
            return;
        for (int i = 0; i < models.size(); i++) {
            Map<String, Object> m = models.get(i);
            String modelName = (String) m.getOrDefault("modelName", "");
            boolean modelEnabled = Boolean.TRUE.equals(m.get("enabled"));
            int contextSize = parseInt(m.get("contextSize"), 0);
            // 表单侧已由 ProviderAdminService 收敛成 V9 JSON，原样存下即可。
            // 但直接调用仓储的路径（测试夹具、将来的导入）可能传裸整数，那时得补成 JSON —— 否则
            // 违反列上的 json_valid 约束。已是 JSON 的值不解析，避免在这里重复一遍收敛逻辑。
            String maxOutputTokens = normalizeMaxOutputTokens(m.get("maxOutputTokens"));
            boolean capsTools = Boolean.TRUE.equals(m.get("capsTools"));
            boolean capsVision = Boolean.TRUE.equals(m.get("capsVision"));
            String reasoningEffort = (String) m.getOrDefault("reasoningEffort", "Medium");
            jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name, enabled, context_size, max_output_tokens, caps_tools, caps_vision, reasoning_effort, sort_order) " + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    providerId, modelName, modelEnabled ? 1 : 0, contextSize, maxOutputTokens, capsTools ? 1 : 0, capsVision ? 1 : 0, reasoningEffort, i);
        }
    }

    // ==================== 运行时读取（供 ProviderRouteResolver 使用） ====================

    /**
     * 查询所有已启用的服务商配置（含已启用的模型列表）。
     * 只返回 enabled=1 的服务商，且只返回每个服务商下 enabled=1 的模型。
     * 如果没有已启用的模型，则不包含该服务商。
     */
    public List<ProviderConfigRow> findAllActiveProvidersWithEnabledModels() {
        return loadProvidersWithModels(null, true, true);
    }

    // ==================== 工具 ====================

    /**
    * 更新供应商标识和 API 地址。
     *
     * @param oldProviderKey 原供应商标识
     * @param newProviderKey 新供应商标识
    * @param displayName 前端完整显示名
     * @param baseUrl API 基础地址
     */
    public void updateProviderKeyAndBaseUrl(String oldProviderKey, String newProviderKey,
                                            String displayName, String baseUrl) {
            jdbcTemplate.update(
                "UPDATE provider_config SET provider_key = ?, display_name = ?, base_url = ?, "
                    + "updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime') WHERE provider_key = ?",
                newProviderKey, resolveDisplayName(newProviderKey, displayName), baseUrl, oldProviderKey);
    }

    /**
     * 根据 provider_key 删除服务商配置及其关联的模型。
        * 当前连接已启用 SQLite 外键约束和级联删除；仍先手动删除模型，
        * 以兼容从旧版本升级且外键状态未知的数据库连接。
     */
    @Transactional
    public void deleteByKey(String providerKey) {
        Integer providerId = jdbcTemplate.query("SELECT id FROM provider_config WHERE provider_key = ?", rs -> {
            return rs.next() ? rs.getInt("id") : null;
        }, providerKey);
        if (providerId != null) {
            jdbcTemplate.update("DELETE FROM provider_model WHERE provider_id = ?", providerId);
        }
        jdbcTemplate.update("DELETE FROM provider_config WHERE provider_key = ?", providerKey);
    }

    private List<ProviderConfigRow> loadProvidersWithModels(String providerKey, boolean activeOnly, boolean enabledModelsOnly) {
        StringBuilder sql = new StringBuilder("SELECT pc.id, pc.provider_key, pc.display_name, pc.enabled, pc.base_url,")
                .append(" pc.supported_protocols, pc.anthropic_base_url, pc.updated_at,")
                .append(" pm.id AS model_id, pm.provider_id AS model_provider_id, pm.model_name, pm.enabled AS model_enabled,")
                .append(" pm.context_size, pm.max_output_tokens, pm.caps_tools, pm.caps_vision, pm.reasoning_effort, pm.sort_order")
                .append(" FROM provider_config pc")
                .append(" LEFT JOIN provider_model pm ON pm.provider_id = pc.id")
                .append(activeOnly ? " WHERE pc.enabled = 1" : "");
        if (providerKey != null) {
            if (activeOnly) {
                sql.append(" AND pc.provider_key = ?");
            } else {
                sql.append(" WHERE pc.provider_key = ?");
            }
        }
        sql.append(" ORDER BY pc.id, pm.sort_order, pm.id");

        List<Map<String, Object>> rows;
        if (providerKey != null) {
            rows = jdbcTemplate.queryForList(sql.toString(), providerKey);
        } else {
            rows = jdbcTemplate.queryForList(sql.toString());
        }

        Map<Integer, MutableProviderConfig> providers = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            int providerId = ((Number) row.get("id")).intValue();
                String providerKeyValue = (String) row.get("provider_key");
                MutableProviderConfig provider = providers.computeIfAbsent(providerId, id -> new MutableProviderConfig(
                    id,
                    providerKeyValue,
                    resolveDisplayName(providerKeyValue, (String) row.get("display_name")),
                    ((Number) row.get("enabled")).intValue() == 1,
                    (String) row.get("base_url"),
                    (String) row.get("supported_protocols"),
                    (String) row.get("anthropic_base_url"),
                    (String) row.get("updated_at")
            ));

            Object modelId = row.get("model_id");
            if (modelId == null) {
                continue;
            }
            boolean modelEnabled = ((Number) row.get("model_enabled")).intValue() == 1;
            if (enabledModelsOnly && !modelEnabled) {
                continue;
            }
            provider.models.add(new ProviderModelRow(
                    ((Number) row.get("model_id")).intValue(),
                    ((Number) row.get("model_provider_id")).intValue(),
                    (String) row.get("model_name"),
                    modelEnabled,
                    ((Number) row.get("context_size")).intValue(),
                    // V9 起是 TEXT（JSON），但未迁移的库里仍可能是 INTEGER，
                    // 所以统一转字符串而不强转具体类型。
                    row.get("max_output_tokens") == null
                            ? null : String.valueOf(row.get("max_output_tokens")),
                    ((Number) row.get("caps_tools")).intValue() == 1,
                    ((Number) row.get("caps_vision")).intValue() == 1,
                    (String) row.get("reasoning_effort"),
                    ((Number) row.get("sort_order")).intValue()
            ));
        }

        List<ProviderConfigRow> result = new ArrayList<>();
        for (MutableProviderConfig provider : providers.values()) {
            if (enabledModelsOnly && provider.models.isEmpty()) {
                continue;
            }
            result.add(new ProviderConfigRow(
                    provider.id,
                    provider.providerKey,
                    provider.displayName,
                    provider.enabled,
                    provider.baseUrl,
                    provider.supportedProtocolsJson,
                    provider.anthropicBaseUrl,
                    provider.updatedAt,
                    provider.models
            ));
        }
        return result;
    }

    private static int parseInt(Object value, int defaultValue) {
        if (value == null)
            return defaultValue;
        if (value instanceof Number)
            return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 把最大输出的入参收敛为列上 {@code json_valid} 接受的 V9 JSON。
     *
     * <p>已是 JSON 的值原样返回而不解析：本层没有 {@code ObjectMapper}，
     * 而收敛的职责在 {@code ProviderAdminService.parseModels} —— 表单路径进来的值已经规范。
     * 这里只负责让裸整数（测试夹具、将来的配置导入）也能满足约束。
     */
    private static String normalizeMaxOutputTokens(Object value) {
        String raw = value == null ? "" : String.valueOf(value).trim();
        if (raw.startsWith("{")) {
            return raw;
        }
        return new MaxOutputTokensSetting(parseInt(raw, 0), MaxOutputTokensSetting.Mode.FALLBACK)
                .serialize();
    }

    /**
     * 优先使用已保存的显示名；空值时为旧数据提供由 key 推导的回退名称。
     */
    public static String resolveDisplayName(String providerKey, String displayName) {
        return displayName != null && !displayName.isBlank()
                ? displayName.trim() : deriveDisplayNameFromKey(providerKey);
    }

    /**
     * 从路由用 provider_key 推导可读名称，仅用于旧数据迁移和空值回退。
     */
    public static String deriveDisplayNameFromKey(String providerKey) {
        if (providerKey == null || providerKey.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (String part : providerKey.split("[-_\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.isEmpty() ? providerKey : result.toString();
    }

    private static class MutableProviderConfig {
        private final int id;
        private final String providerKey;
        private final String displayName;
        private final boolean enabled;
        private final String baseUrl;
        private final String supportedProtocolsJson;
        private final String anthropicBaseUrl;
        private final String updatedAt;
        private final List<ProviderModelRow> models = new ArrayList<>();

        private MutableProviderConfig(int id, String providerKey, String displayName, boolean enabled, String baseUrl,
                                      String supportedProtocolsJson, String anthropicBaseUrl,
                                      String updatedAt) {
            this.id = id;
            this.providerKey = providerKey;
            this.displayName = displayName;
            this.enabled = enabled;
            this.baseUrl = baseUrl;
            this.supportedProtocolsJson = supportedProtocolsJson;
            this.anthropicBaseUrl = anthropicBaseUrl;
            this.updatedAt = updatedAt;
        }
    }
}

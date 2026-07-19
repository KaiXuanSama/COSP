package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 供应商请求转换配置仓储。
 */
@Repository
public class ProviderRequestTransformRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建供应商请求转换配置仓储。
     *
     * @param jdbcTemplate JDBC 操作模板
     */
    public ProviderRequestTransformRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 根据供应商主键查询请求转换配置。
     *
     * @param providerId 供应商主键
     * @return 配置；不存在时返回 null
     */
    public ProviderRequestTransformRow findByProviderId(int providerId) {
        List<ProviderRequestTransformRow> rows = jdbcTemplate.query(
                selectColumns() + " WHERE provider_id = ?",
                (rs, rowNum) -> mapRow(rs), providerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 批量查询多个供应商的请求转换配置，避免管理列表 N+1 查询。
     *
     * @param providerIds 供应商主键集合
     * @return 以 providerId 为键的配置映射
     */
    public Map<Integer, ProviderRequestTransformRow> findByProviderIds(Collection<Integer> providerIds) {
        if (providerIds == null || providerIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = providerIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<ProviderRequestTransformRow> rows = jdbcTemplate.query(
                selectColumns() + " WHERE provider_id IN (" + placeholders + ") ORDER BY provider_id",
                (rs, rowNum) -> mapRow(rs), providerIds.toArray());
        Map<Integer, ProviderRequestTransformRow> result = new LinkedHashMap<>();
        for (ProviderRequestTransformRow row : rows) {
            result.put(row.providerId(), row);
        }
        return result;
    }

    /**
     * 新增或完整更新供应商请求转换配置。
     *
     * @param providerId 供应商主键
     * @param headerRulesVersion 请求头规则版本
     * @param headerRulesJson 请求头规则 JSON
     * @param bodyTemplateKeysJson 模板键 JSON
     * @param bodyPreviewJson 预览请求体 JSON
     * @param bodyRulesVersion 请求体规则版本
     * @param bodyRulesJson 请求体规则集 JSON
     */
    public void upsert(int providerId, int headerRulesVersion, String headerRulesJson,
                       String bodyTemplateKeysJson, String bodyPreviewJson,
                       int bodyRulesVersion, String bodyRulesJson) {
        jdbcTemplate.update(
                "INSERT INTO provider_request_transform "
                        + "(provider_id, header_rules_version, header_rules_json, body_template_keys_json, "
                        + "body_preview_json, body_rules_version, body_rules_json) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(provider_id) DO UPDATE SET "
                        + "header_rules_version = excluded.header_rules_version, "
                        + "header_rules_json = excluded.header_rules_json, "
                        + "body_template_keys_json = excluded.body_template_keys_json, "
                        + "body_preview_json = excluded.body_preview_json, "
                        + "body_rules_version = excluded.body_rules_version, "
                        + "body_rules_json = excluded.body_rules_json, "
                        + "updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')",
                providerId, headerRulesVersion, headerRulesJson, bodyTemplateKeysJson,
                bodyPreviewJson, bodyRulesVersion, bodyRulesJson);
    }

    private String selectColumns() {
        return "SELECT provider_id, header_rules_version, header_rules_json, "
                + "body_template_keys_json, body_preview_json, body_rules_version, body_rules_json, "
                + "created_at, updated_at FROM provider_request_transform";
    }

    private ProviderRequestTransformRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProviderRequestTransformRow(
                rs.getInt("provider_id"),
                rs.getInt("header_rules_version"),
                rs.getString("header_rules_json"),
                rs.getString("body_template_keys_json"),
                rs.getString("body_preview_json"),
                rs.getInt("body_rules_version"),
                rs.getString("body_rules_json"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}

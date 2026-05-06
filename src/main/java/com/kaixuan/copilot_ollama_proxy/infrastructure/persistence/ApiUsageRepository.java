package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * API 调用统计数据访问层 — 基于 JdbcTemplate 操作 SQLite api_usage_daily 汇总表。
 */
@Repository
public class ApiUsageRepository {

    private final JdbcTemplate jdbcTemplate;

    public ApiUsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记录一次 API 调用，并同步更新按天汇总表。
     *
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     */
    public void insert(int inputTokens, int outputTokens) {
        jdbcTemplate.update(
                "INSERT INTO api_usage_daily (usage_date, call_count, input_tokens, output_tokens, updated_at) "
                        + "VALUES (date('now', 'localtime'), 1, ?, ?, strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')) "
                        + "ON CONFLICT(usage_date) DO UPDATE SET " + "call_count = call_count + 1, "
                        + "input_tokens = input_tokens + ?, " + "output_tokens = output_tokens + ?, "
                        + "updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')",
                inputTokens, outputTokens, inputTokens, outputTokens);
    }

    /**
     * 查询 API 调用总次数（从汇总表聚合，无记录时返回 0）。
     */
    public int countTotal() {
        return jdbcTemplate.query("SELECT COALESCE(SUM(call_count), 0) AS cnt FROM api_usage_daily", rs -> {
            if (rs.next()) {
                return rs.getInt("cnt");
            }
            return 0;
        });
    }

    /**
     * 查询今日 API 调用次数（从汇总表读取，无记录时返回 0）。
     */
    public int countToday() {
        return jdbcTemplate.query(
                "SELECT COALESCE(call_count, 0) AS cnt FROM api_usage_daily WHERE usage_date = date('now', 'localtime')",
                rs -> {
                    if (rs.next()) {
                        return rs.getInt("cnt");
                    }
                    return 0;
                });
    }

    /**
     * 查询今日 Token 消耗量（从汇总表读取，无记录时返回 [0, 0]）。
     *
     * @return int[0]=inputTokens, int[1]=outputTokens
     */
    public int[] sumTokensToday() {
        return jdbcTemplate.query("SELECT COALESCE(input_tokens, 0) AS it, COALESCE(output_tokens, 0) AS ot "
                + "FROM api_usage_daily WHERE usage_date = date('now', 'localtime')", rs -> {
                    if (rs.next()) {
                        return new int[] { rs.getInt("it"), rs.getInt("ot") };
                    }
                    return new int[] { 0, 0 };
                });
    }

    /**
     * 查询最近 N 天的每日统计数据（用于图表等）。
     *
     * @param days 天数
     * @return Map 列表，每项包含 usage_date / call_count / input_tokens / output_tokens
     */
    public java.util.List<java.util.Map<String, Object>> listRecentDays(int days) {
        return jdbcTemplate.query("SELECT usage_date, call_count, input_tokens, output_tokens "
                + "FROM api_usage_daily ORDER BY usage_date DESC LIMIT ?", (rs, rowNum) -> {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("usageDate", rs.getString("usage_date"));
                    row.put("callCount", rs.getInt("call_count"));
                    row.put("inputTokens", rs.getInt("input_tokens"));
                    row.put("outputTokens", rs.getInt("output_tokens"));
                    return row;
                }, days);
    }
}

package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * API 调用统计数据访问层 — 基于 JdbcTemplate 操作 SQLite api_usage 表。
 */
@Repository
public class ApiUsageRepository {

    private final JdbcTemplate jdbcTemplate;

    public ApiUsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记录一次 API 调用。
     *
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     */
    public void insert(int inputTokens, int outputTokens) {
        jdbcTemplate.update("INSERT INTO api_usage (input_tokens, output_tokens) VALUES (?, ?)", inputTokens,
                outputTokens);
    }

    /**
     * 查询 API 调用总次数。
     */
    public int countTotal() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM api_usage", Integer.class);
        return count != null ? count : 0;
    }

    /**
     * 查询今日 API 调用次数（本地时区）。
     */
    public int countToday() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM api_usage WHERE date(created_at) = date('now', 'localtime')", Integer.class);
        return count != null ? count : 0;
    }

    /**
     * 查询今日 Token 消耗量。
     *
     * @return int[0]=inputTokens, int[1]=outputTokens
     */
    public int[] sumTokensToday() {
        return jdbcTemplate.query("SELECT COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0) "
                + "FROM api_usage WHERE date(created_at) = date('now', 'localtime')", rs -> {
                    if (rs.next()) {
                        return new int[] { rs.getInt(1), rs.getInt(2) };
                    }
                    return new int[] { 0, 0 };
                });
    }
}

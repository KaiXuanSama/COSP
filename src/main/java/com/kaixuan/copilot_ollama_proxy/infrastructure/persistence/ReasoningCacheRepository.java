package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

/**
 * 工具调用思考链缓存数据访问层 — 基于 JdbcTemplate 操作 SQLite reasoning_cache 表。
 * <p>
 * 用于存储 DeepSeek 等模型在工具调用时的 reasoning_content，
 * 以便在下一轮请求中回填，满足上游对工具调用历史的校验要求。
 * <p>
 * 缓存记录默认保留 180 天，由 {@link #cleanupExpired()} 定时清理。
 */
@Repository
public class ReasoningCacheRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReasoningCacheRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存一条工具调用的思考链缓存。
     *
     * @param toolCallId       工具调用 ID
     * @param reasoningContent 思考链文本
     */
    public void save(String toolCallId, String reasoningContent) {
        jdbcTemplate.update("INSERT OR REPLACE INTO reasoning_cache (tool_call_id, reasoning_content, created_at) " + "VALUES (?, ?, strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))", toolCallId,
                reasoningContent);
    }

    /**
     * 根据工具调用 ID 查询思考链内容。
     *
     * @param toolCallId 工具调用 ID
     * @return 思考链文本，未找到时返回 null
     */
    public String findByToolCallId(String toolCallId) {
        return jdbcTemplate.query("SELECT reasoning_content FROM reasoning_cache WHERE tool_call_id = ?", rs -> {
            if (rs.next()) {
                return rs.getString("reasoning_content");
            }
            return null;
        }, toolCallId);
    }

    /**
     * 删除创建时间早于指定时间的缓存记录。
     *
     * @param beforeDateTime 时间边界（ISO 格式，如 2026-05-10T00:00:00）
     */
    public void deleteOlderThan(String beforeDateTime) {
        jdbcTemplate.update("DELETE FROM reasoning_cache WHERE created_at < ?", beforeDateTime);
    }

    /**
     * 定时清理超过 180 天的缓存记录，每周一零点执行。
     */
    @Scheduled(cron = "0 0 0 * * 1")
    public void cleanupExpired() {
        int deleted = jdbcTemplate.update("DELETE FROM reasoning_cache WHERE created_at < datetime('now', '-180 day', 'localtime')");
        if (deleted > 0) {
            // 使用 System.out 避免引入 Logger 依赖
            System.out.println("[ReasoningCache] 已清理 " + deleted + " 条过期思考链缓存");
        }
    }
}
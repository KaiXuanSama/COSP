package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * API 调用 token 用量数据访问层 — 基于 JdbcTemplate 操作 SQLite api_call_usage 表。
 *
 * <p>实现 application 层的 {@link ApiCallUsageService} 写入接口。写入独立于 api_call_log，
 * 记录每次成功调用的 token 用量与首字响应时长，作为长期统计的稳定数据源。
 *
 * <p>可空 token 用 {@link java.lang.Integer} 包装类透传，NULL 直接落库，绝不写成 0，
 * 以保留 null（上游未提供）vs 0（真实零值）的语义区分。
 */
@Repository
public class ApiCallUsageRepository implements ApiCallUsageService {

    private static final Logger log = LoggerFactory.getLogger(ApiCallUsageRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public ApiCallUsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存一条 token 用量记录。
     *
     * <p>沿用日志写入的"只 warn 不抛"容错策略：写入失败仅记录警告，绝不影响主调用链。
     */
    @Override
    public void save(Long logId, String providerKey, String modelName, boolean stream,
                     String usageRaw, UsageTokens tokens, Integer ttfbMs) {
        try {
            UsageTokens safe = tokens == null ? UsageTokens.EMPTY : tokens;
            jdbcTemplate.update(
                    "INSERT INTO api_call_usage (log_id, provider_key, model_name, is_stream, usage_raw, "
                            + "prompt_tokens, completion_tokens, cached_tokens, ttfb_ms) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    logId, providerKey, modelName, stream ? 1 : 0, usageRaw,
                    safe.promptTokens(), safe.completionTokens(), safe.cachedTokens(), ttfbMs);
        } catch (Exception e) {
            log.warn("保存 API 调用 token 用量失败: {}", e.getMessage());
        }
    }

    /**
     * 根据 log_id 反查关联的 token 用量记录，供日志详情页展示。
     *
     * @param logId 关联的 api_call_log.id
     * @return 用量记录（含所有列）；不存在时返回 null
     */
    public Map<String, Object> findByLogId(long logId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM api_call_usage WHERE log_id = ? ORDER BY id DESC LIMIT 1", logId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}

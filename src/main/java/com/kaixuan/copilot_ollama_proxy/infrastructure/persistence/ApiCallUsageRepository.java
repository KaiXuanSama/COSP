package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownRow;
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

    /**
     * 按 日期 × 供应商 × 模型 聚合最近若干天的调用次数。
     *
     * <p>这是概览下钻柱状图的唯一数据来源：只在最细粒度聚合一次，
     * 三级视图与 hover 明细都由前端从同一份结果 pivot 得出。
     *
     * <p>{@code created_at} 存储格式为 {@code %Y-%m-%dT%H:%M:%S}（本地时区），
     * 故取前 10 位即日期部分；按天窗口用 {@code date('now','localtime')} 起算，
     * 与写入侧的本地时区口径一致。
     *
     * <p>结果按 日期升序、次数降序 返回：日期升序便于前端直接按时间轴渲染，
     * 次数降序让"更忙的组合"先出现（前端仍会按各自维度重新排序，此处仅为稳定输出）。
     *
     * @param days 回看天数（含今天），调用方应先做范围钳制
     * @return 明细行；无数据时返回空列表
     */
    public List<UsageBreakdownRow> aggregateBreakdown(int days) {
        return jdbcTemplate.query(
                "SELECT substr(created_at, 1, 10) AS usage_date, provider_key, model_name, "
                        + "COUNT(*) AS call_count "
                        + "FROM api_call_usage "
                        + "WHERE substr(created_at, 1, 10) >= date('now', 'localtime', ?) "
                        + "GROUP BY usage_date, provider_key, model_name "
                        + "ORDER BY usage_date ASC, call_count DESC",
                (rs, rowNum) -> new UsageBreakdownRow(
                        rs.getString("usage_date"),
                        rs.getString("provider_key"),
                        rs.getString("model_name"),
                        rs.getLong("call_count")),
                "-" + (days - 1) + " days");
    }
}

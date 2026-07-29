package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownRow;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTimelinePoint;
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

    /**
     * 按天聚合最近若干天的 token 用量，供折线图的「近 7 日」范围使用。
     *
     * <p>与 {@link #aggregateBreakdown(int)} 同源同窗口，因此折线与柱状图的口径一致，
     * 两图可以互相印证。这里不带供应商 / 模型维度 —— 折线表达的是总量趋势，
     * 构成分解由柱状图负责。
     *
     * <p>{@code COALESCE} 是必需的：三个 token 列都允许 NULL（上游未提供 usage 时），
     * 若不兜底，全为 NULL 的分组会让 {@code SUM} 返回 NULL，序列化后前端拿到 null
     * 并在算术中变成 NaN。语义上 NULL ≠ 0，但在「求和」这一步按 0 处理是正确的。
     *
     * <p>只返回<strong>有数据的日期</strong>；缺失日期的补零由应用层完成，
     * 因为「时间轴该有多长」是展示口径，不属于数据访问层的职责。
     *
     * @param days 回看天数（含今天），调用方应先做范围钳制
     * @return 按日期升序的用量点；无数据时返回空列表
     */
    public List<UsageTimelinePoint> aggregateDailyTokens(int days) {
        return jdbcTemplate.query(
                "SELECT substr(created_at, 1, 10) AS bucket, "
                        + "SUM(COALESCE(prompt_tokens, 0)) AS input_tokens, "
                        + "SUM(COALESCE(completion_tokens, 0)) AS output_tokens "
                        + "FROM api_call_usage "
                        + "WHERE substr(created_at, 1, 10) >= date('now', 'localtime', ?) "
                        + "GROUP BY bucket "
                        + "ORDER BY bucket ASC",
                (rs, rowNum) -> new UsageTimelinePoint(
                        rs.getString("bucket"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens")),
                "-" + (days - 1) + " days");
    }

    /**
     * 按小时聚合指定时间窗内的 token 用量，供折线图的「今日时段」范围使用。
     *
     * <h2>为什么用字面量比较而非 substr</h2>
     * {@code created_at} 是 {@code %Y-%m-%dT%H:%M:%S} 定长本地时间字符串，
     * 其<strong>字典序与时间序一致</strong>，故可直接用 {@code >=} / {@code <} 比较。
     * 这一点很重要：把列包在 {@code substr(...)} 里会使条件失去 sargable 性质，
     * 无法利用索引而退化为全表扫描；本方法查询的时间窗很窄，更不该付这个代价。
     *
     * <h2>为什么按半小时而非整小时分组</h2>
     * 展示侧的时间点以<strong>整点为中心</strong>聚合（{@code 07:00} 覆盖 06:30–07:30），
     * 这样横轴首尾各占半格、两端都落在 05:00 上，读起来前后对称。
     * 半小时是这种居中聚合所需的最小单元 —— 按整小时分组就无法再拆成两半。
     *
     * <p>分组键在 SELECT 侧计算，不影响 WHERE 的索引可用性。返回的是<strong>半小时槽</strong>
     * 而非最终时间点：槽到点的映射依赖「以整点为中心」这一展示口径
     * （见 {@code UsageQueryService}），不属于数据访问层。
     *
     * @param startInclusive 窗口起点，格式 {@code yyyy-MM-ddTHH:mm:ss}，含
     * @param endExclusive   窗口终点，同格式，不含
     * @return 按时刻升序的用量槽，{@code bucket} 为 {@code HH:00} 或 {@code HH:30}；无数据时返回空列表
     */
    public List<UsageTimelinePoint> aggregateHalfHourTokens(String startInclusive, String endExclusive) {
        return jdbcTemplate.query(
                "SELECT substr(created_at, 12, 2) || ':' "
                        + "|| CASE WHEN CAST(substr(created_at, 15, 2) AS INTEGER) >= 30 "
                        + "THEN '30' ELSE '00' END AS bucket, "
                        + "SUM(COALESCE(prompt_tokens, 0)) AS input_tokens, "
                        + "SUM(COALESCE(completion_tokens, 0)) AS output_tokens "
                        + "FROM api_call_usage "
                        + "WHERE created_at >= ? AND created_at < ? "
                        + "GROUP BY bucket "
                        + "ORDER BY bucket ASC",
                (rs, rowNum) -> new UsageTimelinePoint(
                        rs.getString("bucket"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens")),
                startInclusive, endExclusive);
    }
}

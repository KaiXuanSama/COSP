package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageDateBounds;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownRow;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageDailyPoint;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageHourlyPoint;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageRecordDelta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    /** 与 {@code api_call_usage.created_at} 的列默认值完全一致的格式。 */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final UsageEventPublisher usageEventPublisher;

    public ApiCallUsageRepository(JdbcTemplate jdbcTemplate, UsageEventPublisher usageEventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.usageEventPublisher = usageEventPublisher;
    }

    /**
     * 保存一条 token 用量记录，并把这一行作为增量帧广播出去。
     *
     * <p>沿用日志写入的"只 warn 不抛"容错策略：写入失败仅记录警告，绝不影响主调用链。
     * 增量帧在 {@code update} 返回后才发出，故失败时不会推出并未落库的数据。
     *
     * <h2>created_at 为何由应用赋值</h2>
     * 该列有 {@code strftime(...,'localtime')} 默认值，本可交给 SQLite 生成。
     * 但增量帧必须带上时刻（前端据日期匹配柱子，将来折线图还要据完整时刻定位分桶），
     * 若由 DB 生成，应用只能事后再取一次 {@code now()} —— 两个值来自不同时钟，
     * 跨午夜的瞬间会出现「帧说今天、库里记昨天」的偏差。显式写入让两者同源。
     * 列默认值保留不动，仍为其他写入路径与历史数据兜底。
     */
    @Override
    public void save(Long logId, String providerKey, String modelName, boolean stream,
                     String usageRaw, UsageTokens tokens, Integer ttfbMs) {
        UsageTokens safe = tokens == null ? UsageTokens.EMPTY : tokens;
        String createdAt = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        try {
            jdbcTemplate.update(
                    "INSERT INTO api_call_usage (log_id, provider_key, model_name, is_stream, usage_raw, "
                            + "prompt_tokens, completion_tokens, cached_tokens, ttfb_ms, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    logId, providerKey, modelName, stream ? 1 : 0, usageRaw,
                    safe.promptTokens(), safe.completionTokens(), safe.cachedTokens(), ttfbMs, createdAt);
        } catch (Exception e) {
            log.warn("保存 API 调用 token 用量失败: {}", e.getMessage());
            return;
        }
        publishDelta(createdAt, providerKey, modelName, safe);
    }

    /**
     * 广播一条用量记录增量帧，供概览的柱状图与两个折线视图同步更新。
     *
     * <p>帧原样携带落库那一行，不做任何聚合 —— 三个视图的窗口口径互不相同
     * （柱状图与近 7 日按日历日，今日时段按 05:00 分界），归桶与丢弃都由消费侧决定。
     * {@code createdAt} 与落库值同源，故前端算出的日期必然等于
     * {@code aggregateBreakdown} 的 {@code substr(created_at, 1, 10)}。
     *
     * <p>token 用 {@code longValue} 而非可空包装：本方法只在落库成功后被调用，
     * 而上游未返回 usage 时根本不会走到写入，故 null 不出现在这条路径上。
     * {@link UsageTokens} 的可空字段在此按 0 处理仅为防御，不代表业务上允许 null。
     *
     * <p>推送失败同样只 warn：图表少涨一格，下次刷新即自愈，不值得影响主调用链。
     */
    private void publishDelta(String createdAt, String providerKey, String modelName, UsageTokens tokens) {
        try {
            usageEventPublisher.publishRecordDelta(new UsageRecordDelta(
                    createdAt,
                    providerKey,
                    modelName,
                    tokens.promptTokens() == null ? 0L : tokens.promptTokens(),
                    tokens.completionTokens() == null ? 0L : tokens.completionTokens()));
        } catch (Exception e) {
            log.warn("发布用量记录增量帧失败: {}", e.getMessage());
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
     * 按 日期 × 供应商 × 模型 聚合最近若干天的调用次数与 token 用量。
     *
     * <p>这是图表流首帧的第一部分，同时支撑两个视图：堆叠柱状图取调用次数并按
     * 供应商 / 模型 pivot 出三级下钻，「近 7 日」折线取 token 并按日期求和。
     * 折线因此不需要单独的日级聚合 —— 日期与 token 都已在同一份明细里。
     *
     * <p>{@code created_at} 存储格式为 {@code %Y-%m-%dT%H:%M:%S}（本地时区），
     * 故取前 10 位即日期部分；按天窗口用 {@code date('now','localtime')} 起算，
     * 与写入侧的本地时区口径一致。
     *
     * <p>{@code COALESCE} 是必需的：token 列允许 NULL（上游未提供），
     * 若不兜底，全为 NULL 的分组会让 {@code SUM} 返回 NULL，序列化后前端拿到 null
     * 并在算术中变成 NaN。语义上 NULL ≠ 0，但在「求和」这一步按 0 处理是正确的。
     *
     * <p>结果按 日期升序、次数降序 返回：日期升序便于前端直接按时间轴渲染，
     * 次数降序让"更忙的组合"先出现（前端仍会按各自维度重新排序，此处仅为稳定输出）。
     *
     * <h2>WHERE 为何用字面量比较而非 substr</h2>
     * 把列包在 {@code substr(...)} 里会使条件失去 sargable 性质，无法利用索引而退化为
     * 全表扫描。改用 {@code created_at >= date(...) || 'T00:00:00'} 后，条件直接作用于列，
     * 可命中 {@code idx_api_call_usage_created}（V8.4 迁移新增）。
     *
     * <p>拼上 {@code T00:00:00} 而非直接与日期串比较：{@code created_at} 是
     * {@code yyyy-MM-ddTHH:mm:ss}，而 {@code '2026-07-25' < '2026-07-25T00:00:00'}
     * 在字典序下成立，故只写日期串同样能取到当天全部行 —— 但补全成同格式的边界值
     * 使意图明确，也避免将来格式变化时出现难以察觉的偏差。
     *
     * <p>分组键仍在 SELECT 侧用 {@code substr} 计算，那不影响 WHERE 的索引可用性。
     * 需要注意索引只能省掉「扫描无关历史行」这一步：{@code GROUP BY} 仍要遍历命中的行，
     * 且分组键是表达式而非索引列，SQLite 会建临时 B-tree 排序。窗口放开到数十天时，
     * 聚合本身会成为新的瓶颈，那时才需要服务端 top-N。
     *
     * @param days 回看天数（含今天），调用方应先做范围钳制
     * @return 明细行；无数据时返回空列表
     */
    public List<UsageBreakdownRow> aggregateBreakdown(int days) {
        return jdbcTemplate.query(
                "SELECT substr(created_at, 1, 10) AS usage_date, provider_key, model_name, "
                        + "COUNT(*) AS call_count, "
                        + "SUM(COALESCE(prompt_tokens, 0)) AS input_tokens, "
                        + "SUM(COALESCE(completion_tokens, 0)) AS output_tokens "
                        + "FROM api_call_usage "
                        + "WHERE created_at >= date('now', 'localtime', ?) || 'T00:00:00' "
                        + "GROUP BY usage_date, provider_key, model_name "
                        + "ORDER BY usage_date ASC, call_count DESC",
                (rs, rowNum) -> new UsageBreakdownRow(
                        rs.getString("usage_date"),
                        rs.getString("provider_key"),
                        rs.getString("model_name"),
                        rs.getLong("call_count"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens")),
                "-" + (days - 1) + " days");
    }

    /**
     * 按 日期 × 供应商 × 模型 聚合<strong>任意闭区间</strong>内的调用次数与 token 用量。
     *
     * <p>与 {@link #aggregateBreakdown(int)} 的唯一差别是窗口的表达方式：那个方法只能说
     * 「最近 N 天」，右端永远钉在今天；本方法接受显式起止日期，因此支持向过去滑动的窗口
     * （{@code 1-7}、{@code 2-8} 这类区间）。聚合口径、排序、{@code COALESCE} 兜底
     * 与 sargable 写法全部一致，理由见那个方法的说明。
     *
     * <p>参数是<strong>日期</strong>而非完整时刻：本视图按日历日分组，窗口边界天然落在
     * 午夜。内部拼成 {@code T00:00:00} 与列格式对齐，并把结束日的次日作为右开边界 ——
     * 即 {@code [start 00:00:00, endExclusive 00:00:00)}。
     *
     * <p>由调用方而非本层计算日期：起止边界依赖「今天是哪天」与钳制规则，
     * 那属于用例决策；数据访问层只负责按给定区间取数。
     *
     * @param startInclusive 起始日期（含），格式 {@code yyyy-MM-dd}
     * @param endExclusive   结束边界（不含），格式 {@code yyyy-MM-dd}；
     *                       欲包含 {@code 07-30} 当天则传 {@code 07-31}
     * @return 明细行，日期升序、同日内次数降序；无数据时返回空列表
     */
    public List<UsageBreakdownRow> aggregateBreakdownBetween(String startInclusive, String endExclusive) {
        return jdbcTemplate.query(
                "SELECT substr(created_at, 1, 10) AS usage_date, provider_key, model_name, "
                        + "COUNT(*) AS call_count, "
                        + "SUM(COALESCE(prompt_tokens, 0)) AS input_tokens, "
                        + "SUM(COALESCE(completion_tokens, 0)) AS output_tokens "
                        + "FROM api_call_usage "
                        + "WHERE created_at >= ? AND created_at < ? "
                        + "GROUP BY usage_date, provider_key, model_name "
                        + "ORDER BY usage_date ASC, call_count DESC",
                (rs, rowNum) -> new UsageBreakdownRow(
                        rs.getString("usage_date"),
                        rs.getString("provider_key"),
                        rs.getString("model_name"),
                        rs.getLong("call_count"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens")),
                startInclusive + "T00:00:00", endExclusive + "T00:00:00");
    }

    /**
     * 按<strong>日期</strong>聚合任意闭区间内的调用次数与 token 用量，供「近 N 日」折线使用。
     *
     * <p>与 {@link #aggregateBreakdownBetween} 同窗口、同数据源，只是分组维度更粗 ——
     * 这里不保留供应商与模型。折线的横轴就是日期，拿到更细的维度也只会被再求和一次，
     * 而行数上界会从「天数」膨胀成「天数 × 供应商 × 模型」。
     *
     * <p>只返回<strong>有数据的日期</strong>，补零由应用层按窗口边界完成 ——
     * 那需要知道窗口有多长，而数据访问层只看得到命中的行。
     *
     * <p>WHERE 的 sargable 写法、{@code COALESCE} 的必要性与右开边界的约定
     * 均同 {@link #aggregateBreakdownBetween}，理由见那里。
     *
     * @param startInclusive 起始日期（含），格式 {@code yyyy-MM-dd}
     * @param endExclusive   结束边界（不含），格式 {@code yyyy-MM-dd}
     * @return 按日期升序的用量；无数据时返回空列表
     */
    public List<UsageDailyPoint> aggregateDailyTokens(String startInclusive, String endExclusive) {
        return jdbcTemplate.query(
                "SELECT substr(created_at, 1, 10) AS usage_date, "
                        + "COUNT(*) AS call_count, "
                        + "SUM(COALESCE(prompt_tokens, 0)) AS input_tokens, "
                        + "SUM(COALESCE(completion_tokens, 0)) AS output_tokens "
                        + "FROM api_call_usage "
                        + "WHERE created_at >= ? AND created_at < ? "
                        + "GROUP BY usage_date "
                        + "ORDER BY usage_date ASC",
                (rs, rowNum) -> new UsageDailyPoint(
                        rs.getString("usage_date"),
                        rs.getLong("call_count"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens")),
                startInclusive + "T00:00:00", endExclusive + "T00:00:00");
    }

    /**
     * 取用量记录在时间轴上的两个端点 —— 概览日期选择器可达区间的数据来源。
     *
     * <h2>一条 SQL 取两端而非两条</h2>
     * {@code MIN} / {@code MAX} 写在同一个 {@code SELECT} 里只扫一遍，且两端来自
     * <strong>同一个快照</strong>。分成两次查询时，中间若插入了新记录，
     * 会得到一对来自不同时刻的边界 —— 这在正常运行下几乎必然发生（写入是持续的）。
     *
     * <h2>为何是 substr 而不是 date()</h2>
     * 这里要的正是「存储字符串的前 10 位」，与其他聚合查询的分组键口径逐字符一致。
     * 用 {@code date(created_at)} 会引入 SQLite 的日期解析，格式若有偏差它会静默返回 null，
     * 而边界为 null 会被当成「表为空」—— 选择器随即退化为只有今天可选，且没有任何报错。
     *
     * <p>{@code MIN(created_at)} 与 {@code MIN(substr(created_at, 1, 10))} 在这里等价，
     * 因为存储格式定长且字典序与时间序一致；取 substr 后再求 MIN 只是省掉一次外层截断。
     *
     * <h2>没有 WHERE，也不需要索引</h2>
     * 本查询刻意不加时间条件：它要回答的就是「全部数据的边界在哪」，加了条件就变成了
     * 「某区间内的边界」，那个值恒等于区间本身，毫无信息量。
     *
     * <p>无条件的 {@code MIN}/{@code MAX} 在 SQLite 上不会退化为全表扫描 ——
     * {@code idx_api_call_usage_created}（V8.4）是 {@code created_at} 前导索引，
     * 优化器可直接取索引的首尾项。
     *
     * <h2>空表返回 EMPTY 而非抛异常</h2>
     * 聚合查询在空表上返回<strong>一行两个 NULL</strong>，不是零行，故 {@code queryForObject}
     * 不会抛 {@code EmptyResultDataAccessException}，而是把 null 交给映射函数。
     * 全新部署首次打开概览就是这个情况，属于正常状态。
     *
     * @return 记录边界；表为空时返回 {@link UsageDateBounds#EMPTY}（两端均为 null）
     */
    public UsageDateBounds findDateBounds() {
        UsageDateBounds bounds = jdbcTemplate.queryForObject(
                "SELECT MIN(substr(created_at, 1, 10)) AS earliest, "
                        + "MAX(substr(created_at, 1, 10)) AS latest "
                        + "FROM api_call_usage",
                (rs, rowNum) -> {
                    String earliest = rs.getString("earliest");
                    String latest = rs.getString("latest");
                    // 两端要么都有要么都没有；只有一端非空说明 SQL 被改坏了，
                    // 与其让半个区间流到上层，不如统一按空表处理。
                    return earliest == null || latest == null
                            ? UsageDateBounds.EMPTY
                            : new UsageDateBounds(earliest, latest);
                });
        return bounds == null ? UsageDateBounds.EMPTY : bounds;
    }

    /**
     * 按<strong>整点</strong>聚合指定时间窗内的 token 用量，供折线图「今日时段」视图使用。
     *
     * <h2>整点以中心方式聚合</h2>
     * {@code 14:00} 这个点覆盖 {@code [13:30, 14:30)}，等价于「把时刻四舍五入到最近的整点」，
     * 实现上即 {@code created_at + 30 分钟} 后截断到整点：
     * {@code 13:45 + 30min = 14:15 → 14:00}、{@code 14:30 + 30min = 15:00 → 15:00}。
     * 窗口首尾因此各只覆盖半小时，两者相加恰好一小时，总量不重不漏 ——
     * 这是窗口边界与居中聚合共同作用的自然结果，无需特例。
     *
     * <p>之所以在 SQL 里完成这步而不是下发更细的槽：整点是展示所需的最终粒度，
     * 一个 24 小时窗口最多 25 行，上界固定。若下发半小时槽或逐条记录，
     * 消费侧仍要再聚合一次，而行数上界翻倍甚至无界。
     *
     * <h2>为什么用字面量比较而非 substr</h2>
     * {@code created_at} 是 {@code %Y-%m-%dT%H:%M:%S} 定长本地时间字符串，
     * 其<strong>字典序与时间序一致</strong>，故可直接用 {@code >=} / {@code <} 比较。
     * 把列包在 {@code substr(...)} 里会使条件失去 sargable 性质，退化为全表扫描。
     *
     * <p>该条件可命中 {@code idx_api_call_usage_created}（V8.4 迁移新增的 {@code created_at}
     * 前导索引）。既有的 {@code (provider_key, created_at DESC)} 在此用不上 ——
     * 本查询没有 {@code provider_key} 等值条件，复合索引的前导列不匹配。
     *
     * <p>分组键在 SELECT 侧计算，不影响 WHERE 的索引可用性。只返回<strong>有数据的整点</strong>，
     * 补零由应用层完成 —— 「时间轴该有多长」是展示口径，不属于数据访问层。
     *
     * @param startInclusive 窗口起点，格式 {@code yyyy-MM-ddTHH:mm:ss}，含
     * @param endExclusive   窗口终点，同格式，不含
     * @return 按时刻升序的整点用量；{@code bucket} 为完整时间戳；无数据时返回空列表
     */
    public List<UsageHourlyPoint> aggregateHourlyTokens(String startInclusive, String endExclusive) {
        return jdbcTemplate.query(
                "SELECT strftime('%Y-%m-%dT%H:00:00', created_at, '+30 minutes') AS bucket, "
                        + "SUM(COALESCE(prompt_tokens, 0)) AS input_tokens, "
                        + "SUM(COALESCE(completion_tokens, 0)) AS output_tokens "
                        + "FROM api_call_usage "
                        + "WHERE created_at >= ? AND created_at < ? "
                        + "GROUP BY bucket "
                        + "ORDER BY bucket ASC",
                (rs, rowNum) -> new UsageHourlyPoint(
                        rs.getString("bucket"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens")),
                startInclusive, endExclusive);
    }
}

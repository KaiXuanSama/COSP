package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageHourlyPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证与锁定：今日时段折线的<strong>整点居中聚合</strong>查询。
 *
 * <p>这份聚合取代了原先「后端按半小时分组 → 应用层再把两个槽合成一个整点」的两步做法。
 * 居中口径现在完全由 SQL 表达（{@code created_at + 30 分钟} 后截断到整点），
 * 而「窗口多长、哪些点尚未到来」留给展示侧。
 *
 * <p>本测试的重点是<strong>边界</strong>：居中聚合意味着每个整点吸收它前后各半小时，
 * 判断一条记录归属哪个点的分界线落在 {@code HH:30}，而非 {@code HH:00}。
 * 这条分界线错位不会报错，只会让数据静静地落到相邻的点上。
 */
class ApiCallUsageRepositoryHourlyTests {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private ApiCallUsageRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("usage-hourly.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE api_call_usage ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, log_id INTEGER, provider_key TEXT, model_name TEXT, "
                + "is_stream INTEGER NOT NULL DEFAULT 0, usage_raw TEXT, prompt_tokens INTEGER, "
                + "completion_tokens INTEGER, cached_tokens INTEGER, ttfb_ms INTEGER, "
                + "created_at TEXT NOT NULL)");
        repository = new ApiCallUsageRepository(jdbcTemplate, new UsageEventPublisher());
    }

    /** 在指定时刻插入一条带 token 的记录。 */
    private void insertAt(String createdAt, Integer promptTokens, Integer completionTokens) {
        jdbcTemplate.update(
                "INSERT INTO api_call_usage (provider_key, model_name, is_stream, "
                        + "prompt_tokens, completion_tokens, created_at) VALUES ('p', 'm', 1, ?, ?, ?)",
                promptTokens, completionTokens, createdAt);
    }

    /** 查询 2026-07-27 05:00 起的 24 小时窗口。 */
    private List<UsageHourlyPoint> queryWindow() {
        return repository.aggregateHourlyTokens("2026-07-27T05:00:00", "2026-07-28T05:00:00");
    }

    /**
     * 整点前半小时的记录归入<strong>下一个</strong>整点。
     *
     * <p>13:45 属于 14:00 那个点，因为 14:00 覆盖 [13:30, 14:30)。
     * 这正是居中聚合与「整点起始」聚合的分野 —— 后者会把它归到 13:00。
     */
    @Test
    void recordBeforeHourBelongsToNextHour() {
        insertAt("2026-07-27T13:45:00", 100, 20);

        List<UsageHourlyPoint> points = queryWindow();

        assertThat(points).hasSize(1);
        assertThat(points.get(0).bucket()).isEqualTo("2026-07-27T14:00:00");
    }

    /** 整点后半小时的记录归入该整点本身：14:15 属于 14:00。 */
    @Test
    void recordAfterHourBelongsToSameHour() {
        insertAt("2026-07-27T14:15:00", 100, 20);

        List<UsageHourlyPoint> points = queryWindow();

        assertThat(points).hasSize(1);
        assertThat(points.get(0).bucket()).isEqualTo("2026-07-27T14:00:00");
    }

    /**
     * 分界线恰好在 {@code HH:30}：该时刻归入下一个整点（区间右开左闭）。
     *
     * <p>14:30 属于 15:00 而非 14:00 —— 14:00 覆盖 [13:30, 14:30)，右端不含。
     */
    @Test
    void halfPastHourIsBoundaryAndGoesToNextHour() {
        insertAt("2026-07-27T14:29:59", 1, 0);
        insertAt("2026-07-27T14:30:00", 2, 0);

        List<UsageHourlyPoint> points = queryWindow();

        assertThat(points).hasSize(2);
        assertThat(points.get(0).bucket()).isEqualTo("2026-07-27T14:00:00");
        assertThat(points.get(0).inputTokens()).isEqualTo(1L);
        assertThat(points.get(1).bucket()).isEqualTo("2026-07-27T15:00:00");
        assertThat(points.get(1).inputTokens()).isEqualTo(2L);
    }

    /**
     * 同一整点覆盖区间内的多条记录求和 —— 跨越整点线的两条也应合并。
     *
     * <p>13:35 与 14:20 都属于 14:00，尽管一条在整点前、一条在整点后。
     */
    @Test
    void sumsRecordsAcrossTheHourLineIntoOnePoint() {
        insertAt("2026-07-27T13:35:00", 100, 10);
        insertAt("2026-07-27T14:20:00", 250, 30);

        List<UsageHourlyPoint> points = queryWindow();

        assertThat(points).hasSize(1);
        assertThat(points.get(0).bucket()).isEqualTo("2026-07-27T14:00:00");
        assertThat(points.get(0).inputTokens()).isEqualTo(350L);
        assertThat(points.get(0).outputTokens()).isEqualTo(40L);
    }

    /**
     * 窗口首点只覆盖后半小时，末点只覆盖前半小时，两者相加恰好一小时。
     *
     * <p>05:10 落在首点 05:00（其 [04:30, 05:00) 部分在窗口外），
     * 次日 04:40 落在末点 05:00。两个 05:00 分属不同日期，故 bucket 带日期才能区分 ——
     * 这正是 bucket 用完整时间戳而非 {@code HH:mm} 的原因。
     */
    @Test
    void windowEdgesEachCoverHalfHourAndAreDistinguishedByDate() {
        insertAt("2026-07-27T05:10:00", 100, 10);
        insertAt("2026-07-28T04:40:00", 200, 20);

        List<UsageHourlyPoint> points = queryWindow();

        assertThat(points).hasSize(2);
        assertThat(points.get(0).bucket()).isEqualTo("2026-07-27T05:00:00");
        assertThat(points.get(1).bucket()).isEqualTo("2026-07-28T05:00:00");
    }

    /** 跨午夜的记录带上正确的次日日期，不需要任何序号补偿。 */
    @Test
    void afterMidnightRecordCarriesNextDayDate() {
        insertAt("2026-07-28T01:15:00", 100, 10);

        List<UsageHourlyPoint> points = queryWindow();

        assertThat(points).hasSize(1);
        assertThat(points.get(0).bucket()).isEqualTo("2026-07-28T01:00:00");
    }

    /** 窗口外的记录被排除：起点前一刻与终点当刻都不计入。 */
    @Test
    void excludesRecordsOutsideWindow() {
        insertAt("2026-07-27T04:59:59", 1, 0);
        insertAt("2026-07-28T05:00:00", 2, 0);
        insertAt("2026-07-27T12:00:00", 3, 0);

        List<UsageHourlyPoint> points = queryWindow();

        assertThat(points).hasSize(1);
        assertThat(points.get(0).inputTokens()).isEqualTo(3L);
    }

    /** NULL token 按 0 聚合，不让 SUM 返回 null。 */
    @Test
    void nullTokensAggregateToZero() {
        insertAt("2026-07-27T12:00:00", null, null);

        List<UsageHourlyPoint> points = queryWindow();

        assertThat(points).hasSize(1);
        assertThat(points.get(0).inputTokens()).isZero();
        assertThat(points.get(0).outputTokens()).isZero();
    }

    /** 结果按时刻升序，且只含有数据的整点（不补零，补零是展示决策）。 */
    @Test
    void returnsOnlyNonEmptyPointsInAscendingOrder() {
        insertAt("2026-07-27T20:00:00", 3, 0);
        insertAt("2026-07-27T08:00:00", 1, 0);
        insertAt("2026-07-27T14:00:00", 2, 0);

        List<UsageHourlyPoint> points = queryWindow();

        assertThat(points).hasSize(3);
        assertThat(points.stream().map(UsageHourlyPoint::bucket).toList()).isSorted();
    }

    @Test
    void returnsEmptyListWhenNoData() {
        assertThat(queryWindow()).isNotNull().isEmpty();
    }
}

package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTimelinePoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 折线图时间线聚合的验证与锁定。
 *
 * <p>这两个查询与下钻聚合同源（{@code api_call_usage}），差别在于聚合的是 token 而非次数，
 * 因此测试重点落在 token 特有的两个风险上：
 * <ul>
 *   <li><strong>NULL 语义</strong> —— 三个 token 列都允许 NULL（上游未返回 usage），
 *       求和时必须按 0 处理，否则全 NULL 分组会让 {@code SUM} 返回 NULL 而非 0；</li>
 *   <li><strong>时间窗口边界</strong> —— 半小时查询用字面量比较而非 {@code substr}，
 *       故须验证「含起点、不含终点」确实成立。</li>
 * </ul>
 */
class ApiCallUsageRepositoryTimelineTests {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private ApiCallUsageRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("usage-timeline.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE api_call_usage ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, log_id INTEGER, provider_key TEXT, model_name TEXT, "
                + "is_stream INTEGER NOT NULL DEFAULT 0, usage_raw TEXT, prompt_tokens INTEGER, "
                + "completion_tokens INTEGER, cached_tokens INTEGER, ttfb_ms INTEGER, "
                + "created_at TEXT NOT NULL)");
        repository = new ApiCallUsageRepository(jdbcTemplate);
    }

    /**
     * 按「今天减 daysAgo 天」插入一行，时间部分固定在中午，只关心日期分组。
     *
     * @param promptTokens     可传 null 以模拟上游未返回 usage
     * @param completionTokens 同上
     */
    private void insertDaysAgo(int daysAgo, Integer promptTokens, Integer completionTokens) {
        jdbcTemplate.update(
                "INSERT INTO api_call_usage (provider_key, model_name, is_stream, "
                        + "prompt_tokens, completion_tokens, created_at) "
                        + "VALUES ('p', 'm', 1, ?, ?, date('now', 'localtime', ?) || 'T12:30:00')",
                promptTokens, completionTokens, "-" + daysAgo + " days");
    }

    /** 插入一条精确到指定时刻的记录，时间戳给字面量以便验证窗口边界。 */
    private void insertAt(String timestamp, Integer promptTokens, Integer completionTokens) {
        jdbcTemplate.update(
                "INSERT INTO api_call_usage (provider_key, model_name, is_stream, "
                        + "prompt_tokens, completion_tokens, created_at) VALUES ('p', 'm', 1, ?, ?, ?)",
                promptTokens, completionTokens, timestamp);
    }

    @Nested
    class DailyAggregation {

        @Test
        void sumsTokensWithinSameDay() {
            insertDaysAgo(0, 100, 20);
            insertDaysAgo(0, 300, 40);

            List<UsageTimelinePoint> points = repository.aggregateDailyTokens(7);

            assertThat(points).hasSize(1);
            assertThat(points.get(0).inputTokens()).isEqualTo(400);
            assertThat(points.get(0).outputTokens()).isEqualTo(60);
        }

        @Test
        void returnsOnePointPerDayInAscendingOrder() {
            insertDaysAgo(2, 10, 1);
            insertDaysAgo(0, 30, 3);
            insertDaysAgo(1, 20, 2);

            List<UsageTimelinePoint> points = repository.aggregateDailyTokens(7);

            assertThat(points).hasSize(3);
            assertThat(points).extracting(UsageTimelinePoint::bucket).isSorted();
            // 升序意味着最早的一天在最前，前端可直接按顺序沿时间轴绘制
            assertThat(points.get(0).inputTokens()).isEqualTo(10);
            assertThat(points.get(2).inputTokens()).isEqualTo(30);
        }

        @Test
        void treatsNullTokensAsZeroInsteadOfNullGroup() {
            // 上游未返回 usage 时 token 列为 NULL。若不 COALESCE，全 NULL 分组的 SUM 会是 NULL，
            // 序列化后前端拿到 null 并在算术中变成 NaN —— 这条断言锁住该行为。
            insertDaysAgo(0, null, null);

            List<UsageTimelinePoint> points = repository.aggregateDailyTokens(7);

            assertThat(points).hasSize(1);
            assertThat(points.get(0).inputTokens()).isZero();
            assertThat(points.get(0).outputTokens()).isZero();
        }

        @Test
        void sumsOnlyPresentValuesWhenGroupMixesNulls() {
            insertDaysAgo(0, 100, 20);
            insertDaysAgo(0, null, null);

            List<UsageTimelinePoint> points = repository.aggregateDailyTokens(7);

            assertThat(points.get(0).inputTokens()).isEqualTo(100);
            assertThat(points.get(0).outputTokens()).isEqualTo(20);
        }

        @Test
        void excludesRowsOutsideWindow() {
            // days=7 表示含今天共 7 天，故第 6 天前入选、第 7 天前落选
            insertDaysAgo(6, 10, 1);
            insertDaysAgo(7, 999, 999);

            List<UsageTimelinePoint> points = repository.aggregateDailyTokens(7);

            assertThat(points).hasSize(1);
            assertThat(points.get(0).inputTokens()).isEqualTo(10);
        }

        @Test
        void returnsEmptyListWhenNoData() {
            assertThat(repository.aggregateDailyTokens(7)).isNotNull().isEmpty();
        }
    }

    @Nested
    class HalfHourAggregation {

        @Test
        void sumsTokensWithinSameHalfHour() {
            insertAt("2026-07-28T05:10:00", 100, 10);
            insertAt("2026-07-28T05:25:00", 200, 20);

            List<UsageTimelinePoint> points = repository.aggregateHalfHourTokens(
                    "2026-07-28T05:00:00", "2026-07-29T05:00:00");

            assertThat(points).hasSize(1);
            assertThat(points.get(0).bucket()).isEqualTo("05:00");
            assertThat(points.get(0).inputTokens()).isEqualTo(300);
            assertThat(points.get(0).outputTokens()).isEqualTo(30);
        }

        @Test
        void splitsHourIntoTwoHalves() {
            // 半小时是「以整点为中心聚合」所需的最小单元：每个整点吸收它前后各一个半小时槽，
            // 按整小时分组就无法再拆
            insertAt("2026-07-28T05:10:00", 100, 10);
            insertAt("2026-07-28T05:40:00", 200, 20);

            List<UsageTimelinePoint> points = repository.aggregateHalfHourTokens(
                    "2026-07-28T05:00:00", "2026-07-29T05:00:00");

            assertThat(points).hasSize(2);
            assertThat(points).extracting(UsageTimelinePoint::bucket).containsExactly("05:00", "05:30");
            assertThat(points.get(0).inputTokens()).isEqualTo(100);
            assertThat(points.get(1).inputTokens()).isEqualTo(200);
        }

        @Test
        void putsMinuteThirtyIntoSecondHalf() {
            // 边界分钟：30 属后半小时，29 属前半小时
            insertAt("2026-07-28T05:29:59", 1, 1);
            insertAt("2026-07-28T05:30:00", 2, 2);

            List<UsageTimelinePoint> points = repository.aggregateHalfHourTokens(
                    "2026-07-28T05:00:00", "2026-07-29T05:00:00");

            assertThat(points).extracting(UsageTimelinePoint::bucket).containsExactly("05:00", "05:30");
            assertThat(points.get(0).inputTokens()).isEqualTo(1);
            assertThat(points.get(1).inputTokens()).isEqualTo(2);
        }

        @Test
        void keepsRecordsAcrossMidnightInSameWindow() {
            // 5 点起算的窗口横跨两个自然日，这正是它存在的意义：跨夜编码不该被切成两段
            insertAt("2026-07-28T23:30:00", 100, 10);
            insertAt("2026-07-29T01:30:00", 200, 20);

            List<UsageTimelinePoint> points = repository.aggregateHalfHourTokens(
                    "2026-07-28T05:00:00", "2026-07-29T05:00:00");

            assertThat(points).hasSize(2);
            assertThat(points).extracting(UsageTimelinePoint::bucket).containsExactly("01:30", "23:30");
        }

        @Test
        void includesWindowStartButExcludesWindowEnd() {
            insertAt("2026-07-28T05:00:00", 1, 1);
            insertAt("2026-07-29T05:00:00", 999, 999);

            List<UsageTimelinePoint> points = repository.aggregateHalfHourTokens(
                    "2026-07-28T05:00:00", "2026-07-29T05:00:00");

            assertThat(points).hasSize(1);
            assertThat(points.get(0).inputTokens()).isEqualTo(1);
        }

        @Test
        void excludesRecordsBeforeWindowStart() {
            insertAt("2026-07-28T04:59:59", 999, 999);

            List<UsageTimelinePoint> points = repository.aggregateHalfHourTokens(
                    "2026-07-28T05:00:00", "2026-07-29T05:00:00");

            assertThat(points).isEmpty();
        }

        @Test
        void treatsNullTokensAsZero() {
            insertAt("2026-07-28T10:00:00", null, null);

            List<UsageTimelinePoint> points = repository.aggregateHalfHourTokens(
                    "2026-07-28T05:00:00", "2026-07-29T05:00:00");

            assertThat(points).hasSize(1);
            assertThat(points.get(0).inputTokens()).isZero();
            assertThat(points.get(0).outputTokens()).isZero();
        }

        @Test
        void returnsEmptyListWhenNoData() {
            assertThat(repository.aggregateHalfHourTokens(
                    "2026-07-28T05:00:00", "2026-07-29T05:00:00")).isNotNull().isEmpty();
        }
    }
}

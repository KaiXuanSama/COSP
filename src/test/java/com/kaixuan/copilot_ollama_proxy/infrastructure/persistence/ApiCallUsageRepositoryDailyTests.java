package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageDailyPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证与锁定：按日聚合的 token 用量查询（「近 N 日」折线的数据来源）。
 *
 * <p>与 {@code aggregateBreakdownBetween} 共享窗口与边界约定，差别只在分组维度更粗。
 * 因此本测试重点在<strong>跨维度合并</strong>：同一天的不同供应商 / 模型必须并成一行，
 * 若误留维度会让折线在同一天出现多个点。边界项仍保留 —— 那是字典序比较最脆弱的地方。
 */
class ApiCallUsageRepositoryDailyTests {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private ApiCallUsageRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("usage-daily.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE api_call_usage ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, log_id INTEGER, provider_key TEXT, model_name TEXT, "
                + "is_stream INTEGER NOT NULL DEFAULT 0, usage_raw TEXT, prompt_tokens INTEGER, "
                + "completion_tokens INTEGER, cached_tokens INTEGER, ttfb_ms INTEGER, "
                + "created_at TEXT NOT NULL)");
        repository = new ApiCallUsageRepository(jdbcTemplate, new UsageEventPublisher());
    }

    private void insertAt(String createdAt, String providerKey, String modelName,
                          Integer promptTokens, Integer completionTokens) {
        jdbcTemplate.update(
                "INSERT INTO api_call_usage (provider_key, model_name, is_stream, "
                        + "prompt_tokens, completion_tokens, created_at) VALUES (?, ?, 1, ?, ?, ?)",
                providerKey, modelName, promptTokens, completionTokens, createdAt);
    }

    /** 查询 [07-25, 08-01) 即含 07-25 至 07-31 共 7 天。 */
    private List<UsageDailyPoint> queryWeek() {
        return repository.aggregateDailyTokens("2026-07-25", "2026-08-01");
    }

    /**
     * 同一天的不同供应商与模型合并成<strong>一行</strong>。
     *
     * <p>这是本查询与明细聚合的关键差异：若 {@code GROUP BY} 里误留了维度，
     * 折线在同一天就会出现多个点，图仍能画出来 —— 只是同一日期被重复绘制。
     */
    @Test
    void mergesAllProvidersAndModelsIntoOneRowPerDay() {
        insertAt("2026-07-26T09:00:00", "deepseek", "chat", 100, 20);
        insertAt("2026-07-26T10:00:00", "deepseek", "coder", 200, 30);
        insertAt("2026-07-26T11:00:00", "zhipu", "glm", 50, 10);

        List<UsageDailyPoint> points = queryWeek();

        assertThat(points).hasSize(1);
        UsageDailyPoint day = points.get(0);
        assertThat(day.date()).isEqualTo("2026-07-26");
        assertThat(day.callCount()).isEqualTo(3L);
        assertThat(day.inputTokens()).isEqualTo(350L);
        assertThat(day.outputTokens()).isEqualTo(60L);
    }

    /** 不同日期各成一行，按日期升序。 */
    @Test
    void ordersByDateAscending() {
        insertAt("2026-07-29T10:00:00", "p", "m", 1, 1);
        insertAt("2026-07-26T10:00:00", "p", "m", 1, 1);
        insertAt("2026-07-31T10:00:00", "p", "m", 1, 1);

        assertThat(queryWeek()).extracting(UsageDailyPoint::date)
                .containsExactly("2026-07-26", "2026-07-29", "2026-07-31");
    }

    /** 只返回有数据的日期 —— 补零是应用层的事，那里才知道窗口有多长。 */
    @Test
    void returnsOnlyDaysWithData() {
        insertAt("2026-07-26T10:00:00", "p", "m", 1, 1);

        assertThat(queryWeek()).hasSize(1);
    }

    /** 窗口首日 {@code 00:00:00} 含。 */
    @Test
    void firstInstantOfStartDateIsIncluded() {
        insertAt("2026-07-25T00:00:00", "p", "m", 10, 5);

        assertThat(queryWeek()).hasSize(1);
    }

    /** 起始日前一秒排除。 */
    @Test
    void lastInstantBeforeStartDateIsExcluded() {
        insertAt("2026-07-24T23:59:59", "p", "m", 10, 5);

        assertThat(queryWeek()).isEmpty();
    }

    /**
     * 结束日最后一秒含 —— 依赖调用方传入「结束日的次日」作为右边界。
     *
     * <p>少加一天会静默丢掉最后一天，而折线照样能画 —— 只是最右一个点永远是零。
     */
    @Test
    void lastInstantOfEndDateIsIncluded() {
        insertAt("2026-07-31T23:59:59", "p", "m", 10, 5);

        assertThat(queryWeek()).hasSize(1);
    }

    /** 右边界当天排除（区间右开）。 */
    @Test
    void exclusiveEndBoundaryDayIsExcluded() {
        insertAt("2026-08-01T00:00:00", "p", "m", 10, 5);

        assertThat(queryWeek()).isEmpty();
    }

    /**
     * 全为 NULL 的 token 求和后为 0 而非 null。
     *
     * <p>{@code SUM} 遇全 NULL 返回 NULL，序列化后前端拿到 null 并在算术中变成 NaN。
     */
    @Test
    void nullTokensAggregateToZeroNotNull() {
        insertAt("2026-07-26T10:00:00", "p", "m", null, null);

        List<UsageDailyPoint> points = queryWeek();

        assertThat(points).hasSize(1);
        assertThat(points.get(0).callCount()).isEqualTo(1L);
        assertThat(points.get(0).inputTokens()).isZero();
        assertThat(points.get(0).outputTokens()).isZero();
    }

    /** 部分 NULL 时只跳过 NULL 那条的贡献，次数仍全计。 */
    @Test
    void partialNullTokensStillCountCalls() {
        insertAt("2026-07-26T10:00:00", "p", "m", 100, 20);
        insertAt("2026-07-26T11:00:00", "p", "m", null, null);

        List<UsageDailyPoint> points = queryWeek();

        assertThat(points.get(0).callCount()).isEqualTo(2L);
        assertThat(points.get(0).inputTokens()).isEqualTo(100L);
    }

    /** 窗口内无数据时返回空列表，不返回 null。 */
    @Test
    void emptyWindowReturnsEmptyList() {
        insertAt("2026-06-01T10:00:00", "p", "m", 10, 5);

        assertThat(queryWeek()).isEmpty();
    }

    /** 跨月窗口按定长字符串的字典序比较，无需解析成日期类型。 */
    @Test
    void windowSpanningMonthBoundaryWorks() {
        insertAt("2026-07-30T10:00:00", "p", "m", 10, 5);
        insertAt("2026-07-31T10:00:00", "p", "m", 10, 5);
        insertAt("2026-08-01T10:00:00", "p", "m", 10, 5);
        insertAt("2026-08-02T10:00:00", "p", "m", 10, 5);

        assertThat(repository.aggregateDailyTokens("2026-07-30", "2026-08-02"))
                .extracting(UsageDailyPoint::date)
                .containsExactly("2026-07-30", "2026-07-31", "2026-08-01");
    }

    /**
     * 与明细聚合在同一窗口下的<strong>总量一致</strong>。
     *
     * <p>两个查询喂同一页面的两张图，若口径漂移（比如一边漏了某种 NULL 处理），
     * 用户会看到柱状图与折线的总量对不上，而两张图各自都「看起来正常」。
     */
    @Test
    void totalsAgreeWithBreakdownAggregation() {
        insertAt("2026-07-26T09:00:00", "deepseek", "chat", 100, 20);
        insertAt("2026-07-26T10:00:00", "deepseek", "coder", 200, 30);
        insertAt("2026-07-27T11:00:00", "zhipu", "glm", 50, null);

        long dailyInput = queryWeek().stream().mapToLong(UsageDailyPoint::inputTokens).sum();
        long dailyCalls = queryWeek().stream().mapToLong(UsageDailyPoint::callCount).sum();

        var rows = repository.aggregateBreakdownBetween("2026-07-25", "2026-08-01");
        long breakdownInput = rows.stream().mapToLong(r -> r.inputTokens()).sum();
        long breakdownCalls = rows.stream().mapToLong(r -> r.callCount()).sum();

        assertThat(dailyInput).isEqualTo(breakdownInput);
        assertThat(dailyCalls).isEqualTo(breakdownCalls);
    }
}

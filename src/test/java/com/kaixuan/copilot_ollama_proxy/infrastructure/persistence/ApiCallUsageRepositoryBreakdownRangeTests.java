package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证与锁定：任意闭区间的用量明细聚合（滑动窗口分页的数据来源）。
 *
 * <p>与 {@code aggregateBreakdown(int days)} 的差别只在窗口表达方式，但这个差别引入了
 * 一处新的失败模式：<strong>边界串必须与 {@code created_at} 的格式逐字符对齐</strong>。
 * 字典序比较一旦格式错位不会报错，只会静默漏掉边界那一天的数据。因此本测试用真实 SQLite，
 * 把记录精确插在窗口的首末时刻与前后一秒处。
 */
class ApiCallUsageRepositoryBreakdownRangeTests {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private ApiCallUsageRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("usage-range.db"));
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
    private List<UsageBreakdownRow> queryWeek() {
        return repository.aggregateBreakdownBetween("2026-07-25", "2026-08-01");
    }

    /**
     * 窗口首日的 {@code 00:00:00} 必须被包含。
     *
     * <p>左边界是闭区间，而 {@code created_at >= '2026-07-25T00:00:00'} 恰好取到该时刻。
     */
    @Test
    void firstInstantOfStartDateIsIncluded() {
        insertAt("2026-07-25T00:00:00", "p", "m", 10, 5);

        assertThat(queryWeek()).hasSize(1);
    }

    /** 起始日前一秒被排除 —— 这一秒属于上一页窗口。 */
    @Test
    void lastInstantBeforeStartDateIsExcluded() {
        insertAt("2026-07-24T23:59:59", "p", "m", 10, 5);

        assertThat(queryWeek()).isEmpty();
    }

    /**
     * 结束日的最后一秒必须被包含。
     *
     * <p>这依赖调用方传入的右边界是「结束日的次日」；若传结束日本身，
     * 07-31 一整天的数据都会消失，而图表照样能画出来 —— 只是最右一根柱子永远是空的。
     */
    @Test
    void lastInstantOfEndDateIsIncluded() {
        insertAt("2026-07-31T23:59:59", "p", "m", 10, 5);

        assertThat(queryWeek()).hasSize(1);
    }

    /** 右边界当天（08-01）被排除，因为区间右开。 */
    @Test
    void exclusiveEndBoundaryDayIsExcluded() {
        insertAt("2026-08-01T00:00:00", "p", "m", 10, 5);

        assertThat(queryWeek()).isEmpty();
    }

    /** 分组键为 日期 × 供应商 × 模型：同组合累加，不同组合各成一行。 */
    @Test
    void groupsByDateProviderAndModel() {
        insertAt("2026-07-26T10:00:00", "deepseek", "chat", 100, 20);
        insertAt("2026-07-26T14:00:00", "deepseek", "chat", 200, 30);
        insertAt("2026-07-26T15:00:00", "deepseek", "coder", 50, 10);
        insertAt("2026-07-27T09:00:00", "deepseek", "chat", 70, 7);

        List<UsageBreakdownRow> rows = queryWeek();

        assertThat(rows).hasSize(3);
        UsageBreakdownRow merged = rows.stream()
                .filter(r -> r.date().equals("2026-07-26") && r.modelName().equals("chat"))
                .findFirst().orElseThrow();
        assertThat(merged.callCount()).isEqualTo(2L);
        assertThat(merged.inputTokens()).isEqualTo(300L);
        assertThat(merged.outputTokens()).isEqualTo(50L);
    }

    /** 排序契约：日期升序，同日内调用次数降序。 */
    @Test
    void ordersByDateAscendingThenCallCountDescending() {
        insertAt("2026-07-27T10:00:00", "a", "m", 1, 1);
        insertAt("2026-07-26T10:00:00", "b", "m", 1, 1);
        insertAt("2026-07-26T11:00:00", "b", "m", 1, 1);
        insertAt("2026-07-26T12:00:00", "c", "m", 1, 1);

        List<UsageBreakdownRow> rows = queryWeek();

        assertThat(rows).extracting(UsageBreakdownRow::date)
                .containsExactly("2026-07-26", "2026-07-26", "2026-07-27");
        // 同为 07-26，b 有 2 次排在 c 的 1 次之前
        assertThat(rows.get(0).providerKey()).isEqualTo("b");
        assertThat(rows.get(0).callCount()).isEqualTo(2L);
        assertThat(rows.get(1).providerKey()).isEqualTo("c");
    }

    /**
     * 全为 NULL 的 token 分组求和后为 0，而非 null。
     *
     * <p>{@code SUM} 遇全 NULL 会返回 NULL，序列化后前端拿到 null 并在算术中变成 NaN，
     * 故 SQL 里用 {@code COALESCE} 兜底。这只是求和这一步的处理，不改变落库侧保留 null 的语义。
     */
    @Test
    void nullTokensAggregateToZeroNotNull() {
        insertAt("2026-07-26T10:00:00", "p", "m", null, null);

        List<UsageBreakdownRow> rows = queryWeek();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).callCount()).isEqualTo(1L);
        assertThat(rows.get(0).inputTokens()).isZero();
        assertThat(rows.get(0).outputTokens()).isZero();
    }

    /** 窗口内无数据时返回空列表，不返回 null。 */
    @Test
    void emptyWindowReturnsEmptyList() {
        insertAt("2026-06-01T10:00:00", "p", "m", 10, 5);

        assertThat(queryWeek()).isEmpty();
    }

    /**
     * 窗口跨月时按字符串比较依然正确。
     *
     * <p>{@code created_at} 是定长的 {@code yyyy-MM-ddTHH:mm:ss}，其字典序与时间序一致，
     * 故 {@code '2026-07-31T...' < '2026-08-01T00:00:00'} 成立，无需按日期类型解析。
     */
    @Test
    void windowSpanningMonthBoundaryWorks() {
        insertAt("2026-07-30T10:00:00", "p", "m", 10, 5);
        insertAt("2026-07-31T10:00:00", "p", "m", 10, 5);
        insertAt("2026-08-01T10:00:00", "p", "m", 10, 5);
        insertAt("2026-08-02T10:00:00", "p", "m", 10, 5);

        List<UsageBreakdownRow> rows = repository.aggregateBreakdownBetween("2026-07-30", "2026-08-02");

        assertThat(rows).extracting(UsageBreakdownRow::date)
                .containsExactly("2026-07-30", "2026-07-31", "2026-08-01");
    }
}

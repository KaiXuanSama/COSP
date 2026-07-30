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
 * 阶段一验证与锁定：下钻柱状图的聚合查询。
 *
 * <p>覆盖：
 * <ul>
 *   <li>按 日期 × 供应商 × 模型 正确分组计数；</li>
 *   <li>日期窗口按本地时区起算，窗口外的旧数据被排除；</li>
 *   <li>同一天同一供应商的多个模型各自成行（hover 明细的数据基础）；</li>
 *   <li>失败调用不写用量行，故聚合天然只统计有用量的成功调用；</li>
 *   <li>无数据时返回空列表而非 null。</li>
 * </ul>
 */
class ApiCallUsageRepositoryBreakdownTests {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private ApiCallUsageRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("usage-breakdown.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE api_call_usage ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, log_id INTEGER, provider_key TEXT, model_name TEXT, "
                + "is_stream INTEGER NOT NULL DEFAULT 0, usage_raw TEXT, prompt_tokens INTEGER, "
                + "completion_tokens INTEGER, cached_tokens INTEGER, ttfb_ms INTEGER, "
                + "created_at TEXT NOT NULL)");
        repository = new ApiCallUsageRepository(jdbcTemplate, new UsageEventPublisher());
    }

    /** 按"今天减 offset 天"插入一行，时间部分固定，只关心日期分组。 */
    private void insertAt(int daysAgo, String providerKey, String modelName) {
        jdbcTemplate.update(
                "INSERT INTO api_call_usage (provider_key, model_name, is_stream, created_at) "
                        + "VALUES (?, ?, 1, date('now', 'localtime', ?) || 'T12:30:00')",
                providerKey, modelName, "-" + daysAgo + " days");
    }

    @Test
    void groupsByDateProviderAndModel() {
        insertAt(0, "deepseek", "deepseek-chat");
        insertAt(0, "deepseek", "deepseek-chat");
        insertAt(0, "deepseek", "deepseek-reasoner");
        insertAt(0, "zhipu", "glm-4");

        List<UsageBreakdownRow> rows = repository.aggregateBreakdown(7);

        assertThat(rows).hasSize(3);
        // 同供应商的两个模型各自成行，次数各自累计
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.providerKey()).isEqualTo("deepseek");
            assertThat(row.modelName()).isEqualTo("deepseek-chat");
            assertThat(row.callCount()).isEqualTo(2);
        });
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.providerKey()).isEqualTo("deepseek");
            assertThat(row.modelName()).isEqualTo("deepseek-reasoner");
            assertThat(row.callCount()).isEqualTo(1);
        });
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.providerKey()).isEqualTo("zhipu");
            assertThat(row.callCount()).isEqualTo(1);
        });
    }

    @Test
    void separatesRowsAcrossDifferentDays() {
        insertAt(0, "deepseek", "deepseek-chat");
        insertAt(1, "deepseek", "deepseek-chat");
        insertAt(2, "deepseek", "deepseek-chat");

        List<UsageBreakdownRow> rows = repository.aggregateBreakdown(7);

        // 同供应商同模型但跨三天 → 三行，各自计数
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> assertThat(row.callCount()).isEqualTo(1));
        assertThat(rows.stream().map(UsageBreakdownRow::date).distinct().toList()).hasSize(3);
    }

    @Test
    void excludesRowsOutsideRequestedWindow() {
        insertAt(0, "in-window", "m");
        insertAt(6, "in-window-edge", "m");
        // days=7 表示"含今天共 7 天"，即今天减 6 天为最早边界；第 7 天前的应被排除
        insertAt(7, "out-of-window", "m");
        insertAt(30, "way-out", "m");

        List<UsageBreakdownRow> rows = repository.aggregateBreakdown(7);

        assertThat(rows.stream().map(UsageBreakdownRow::providerKey).toList())
                .containsExactlyInAnyOrder("in-window", "in-window-edge");
    }

    @Test
    void returnsRowsOrderedByDateAscending() {
        insertAt(2, "a", "m");
        insertAt(0, "b", "m");
        insertAt(1, "c", "m");

        List<UsageBreakdownRow> rows = repository.aggregateBreakdown(7);

        List<String> dates = rows.stream().map(UsageBreakdownRow::date).toList();
        assertThat(dates).isSorted();
    }

    @Test
    void ordersByCallCountDescendingWithinSameDate() {
        insertAt(0, "busy", "m");
        insertAt(0, "busy", "m");
        insertAt(0, "busy", "m");
        insertAt(0, "quiet", "m");

        List<UsageBreakdownRow> rows = repository.aggregateBreakdown(7);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).providerKey()).isEqualTo("busy");
        assertThat(rows.get(0).callCount()).isEqualTo(3);
        assertThat(rows.get(1).providerKey()).isEqualTo("quiet");
    }

    @Test
    void returnsEmptyListWhenNoData() {
        assertThat(repository.aggregateBreakdown(7)).isNotNull().isEmpty();
    }

    @Test
    void singleDayWindowOnlyCoversToday() {
        insertAt(0, "today", "m");
        insertAt(1, "yesterday", "m");

        List<UsageBreakdownRow> rows = repository.aggregateBreakdown(1);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).providerKey()).isEqualTo("today");
    }
}

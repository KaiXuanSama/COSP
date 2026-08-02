package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.kaixuan.copilot_ollama_proxy.application.usage.UsageDateBounds;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证与锁定：用量记录日期上下限查询（概览日期选择器可达区间的数据来源）。
 *
 * <p>重点覆盖三处容易静默出错的地方：
 * <ul>
 *   <li><strong>空表</strong>返回 EMPTY 而非抛异常 —— 聚合查询在空表上返回一行两个 NULL，
 *       不是零行，若按「查不到就抛」写就会在全新部署首次打开概览时 500。</li>
 *   <li><strong>只取日期部分</strong> —— 边界串必须是 {@code yyyy-MM-dd}，
 *       带上时刻会让前端的日期比较逐一失配。</li>
 *   <li><strong>中间断档被忽略</strong> —— 这是刻意的设计而非缺陷，必须锁住，
 *       否则将来「顺手改成返回实际有数据的日期集合」不会有任何编译错误。</li>
 * </ul>
 */
class ApiCallUsageRepositoryDateBoundsTests {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private ApiCallUsageRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("usage-bounds.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE api_call_usage ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, log_id INTEGER, provider_key TEXT, model_name TEXT, "
                + "is_stream INTEGER NOT NULL DEFAULT 0, usage_raw TEXT, prompt_tokens INTEGER, "
                + "completion_tokens INTEGER, cached_tokens INTEGER, ttfb_ms INTEGER, "
                + "created_at TEXT NOT NULL)");
        repository = new ApiCallUsageRepository(jdbcTemplate, new UsageEventPublisher());
    }

    private void insertAt(String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO api_call_usage (provider_key, model_name, is_stream, "
                        + "prompt_tokens, completion_tokens, created_at) VALUES ('p', 'm', 1, 1, 1, ?)",
                createdAt);
    }

    /**
     * 空表返回 EMPTY，两端均为 null。
     *
     * <p>这是全新部署首次打开概览的状态，属于正常情况而非异常。
     */
    @Test
    void emptyTableReturnsEmptyBounds() {
        UsageDateBounds bounds = repository.findDateBounds();

        assertThat(bounds).isEqualTo(UsageDateBounds.EMPTY);
        assertThat(bounds.hasData()).isFalse();
        assertThat(bounds.earliest()).isNull();
        assertThat(bounds.latest()).isNull();
    }

    /** 单条记录时两端相同。 */
    @Test
    void singleRecordYieldsSameBoundsOnBothEnds() {
        insertAt("2026-07-28T14:30:00");

        UsageDateBounds bounds = repository.findDateBounds();

        assertThat(bounds.hasData()).isTrue();
        assertThat(bounds.earliest()).isEqualTo("2026-07-28");
        assertThat(bounds.latest()).isEqualTo("2026-07-28");
    }

    /**
     * 只返回日期部分，不带时刻。
     *
     * <p>带上 {@code T14:30:00} 时前端的日期比较会逐一失配，而页面照样能渲染 ——
     * 只是可达区间为空，选择器整体变成不可操作。
     */
    @Test
    void boundsContainDateOnly() {
        insertAt("2026-07-26T00:00:01");
        insertAt("2026-07-31T23:59:59");

        UsageDateBounds bounds = repository.findDateBounds();

        assertThat(bounds.earliest()).isEqualTo("2026-07-26");
        assertThat(bounds.latest()).isEqualTo("2026-07-31");
    }

    /**
     * 中间断档被<strong>忽略</strong> —— 07-29 无数据，范围仍为 07-28 到 07-30。
     *
     * <p>这是刻意的设计：空日是确定的零，画成零柱即可。若改成返回实际有数据的日期集合，
     * 选择器的离散点会变成不连续的一串，横轴不再是等距时间轴。
     */
    @Test
    void ignoresGapsInsideTheRange() {
        insertAt("2026-07-28T10:00:00");
        insertAt("2026-07-30T10:00:00");

        UsageDateBounds bounds = repository.findDateBounds();

        assertThat(bounds.earliest()).isEqualTo("2026-07-28");
        assertThat(bounds.latest()).isEqualTo("2026-07-30");
    }

    /**
     * 插入顺序不影响结果 —— 边界由值决定，不是「第一条 / 最后一条」。
     *
     * <p>若误用 {@code ORDER BY id LIMIT 1} 之类的写法，乱序写入（补录历史数据）
     * 就会给出错误的边界。
     */
    @Test
    void boundsIndependentOfInsertionOrder() {
        insertAt("2026-07-30T10:00:00");
        insertAt("2026-07-26T10:00:00");
        insertAt("2026-07-28T10:00:00");

        UsageDateBounds bounds = repository.findDateBounds();

        assertThat(bounds.earliest()).isEqualTo("2026-07-26");
        assertThat(bounds.latest()).isEqualTo("2026-07-30");
    }

    /** 跨年边界按字典序仍正确 —— 定长格式下字典序与时间序一致。 */
    @Test
    void handlesYearBoundary() {
        insertAt("2025-12-31T23:00:00");
        insertAt("2026-01-01T01:00:00");

        UsageDateBounds bounds = repository.findDateBounds();

        assertThat(bounds.earliest()).isEqualTo("2025-12-31");
        assertThat(bounds.latest()).isEqualTo("2026-01-01");
    }
}

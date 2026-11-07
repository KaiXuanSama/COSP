package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.logging.CallLogFilter;
import com.kaixuan.copilot_ollama_proxy.observability.publisher.LogEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 调用日志列表的检索条件。语义见 {@link CallLogFilter} 与 ADR-0020。 */
class ApiCallLogRepositorySearchTests {

    @TempDir
    Path tempDir;

    private ApiCallLogRepository repository;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("api-log-search.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE api_call_log ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT, model_name TEXT, is_stream INTEGER NOT NULL DEFAULT 0, "
                + "downstream_protocol TEXT NOT NULL DEFAULT 'CHAT', upstream_protocol TEXT NOT NULL DEFAULT 'CHAT', "
                + "status_code INTEGER, request_headers TEXT, request_body TEXT, response_headers TEXT, response_body TEXT, "
                + "chunks TEXT, duration_ms INTEGER, payload_trimmed INTEGER NOT NULL DEFAULT 0, "
                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");
        jdbcTemplate.execute("CREATE TABLE api_call_usage ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, log_id INTEGER, provider_key TEXT, model_name TEXT, "
                + "is_stream INTEGER NOT NULL DEFAULT 0, usage_raw TEXT, prompt_tokens INTEGER, "
                + "completion_tokens INTEGER, cached_tokens INTEGER, ttfb_ms INTEGER, "
                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");
        // id 与 created_at 同序，与生产数据一致。
        insertLog("stepfun", "step-3", 200, "2026-11-01T08:00:00");       // 1
        insertLog("stepfun", "step-3-mini", 429, "2026-11-01T08:00:01");  // 2
        insertLog("deepseek", "deepseek-v4", 503, "2026-11-01T09:00:00"); // 3
        insertLog("DeepSeek", "DeepSeek-V4", 400, "2026-11-01T10:00:00"); // 4
        insertLog("mimo", "gpt_5", -1, "2026-11-02T00:00:00");            // 5
        insertLog("mimo", "gptx5", 200, "2026-11-02T00:00:01");           // 6
        insertLog("zhipu", "glm%5", 502, "2026-11-03T12:00:00");          // 7
        insertLog("zhipu", "glm-5", 200, "2026-11-03T12:30:00");          // 8
        repository = new ApiCallLogRepository(jdbcTemplate, new ObjectMapper(), new LogEventPublisher());
    }

    private void insertLog(String providerKey, String modelName, int statusCode, String createdAt) {
        jdbcTemplate.update("INSERT INTO api_call_log (provider_key, model_name, status_code, duration_ms, created_at) "
                + "VALUES (?, ?, ?, ?, ?)", providerKey, modelName, statusCode, 100, createdAt);
    }

    private static CallLogFilter filter(String from, String to, List<String> status,
                                        String provider, String providerMatch, String model, String modelMatch) {
        return CallLogFilter.parse(from, to, status, provider, providerMatch, model, modelMatch);
    }

    @SuppressWarnings("unchecked")
    private List<Long> ids(CallLogFilter filter) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) repository.findLogs(filter, null, 100).get("items");
        return items.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
    }

    /** NONE 与无条件重载结果一致，保证不传检索参数时行为不变。 */
    @Test
    void noneEqualsLegacyQuery() {
        assertThat(repository.findLogs(CallLogFilter.NONE, null, 3)).isEqualTo(repository.findLogs(null, 3));
        assertThat(ids(CallLogFilter.NONE)).containsExactly(8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);
    }

    /** 时间是闭区间：恰好落在 from、to 上的行都要命中。 */
    @Test
    void timeRangeIsInclusive() {
        assertThat(ids(filter("2026-11-01T08:00:01", "2026-11-02T00:00:00", null, null, null, null, null)))
                .containsExactly(5L, 4L, 3L, 2L);
    }

    @Test
    void openEndedTimeRange() {
        assertThat(ids(filter("2026-11-03T00:00:00", null, null, null, null, null, null))).containsExactly(8L, 7L);
        assertThat(ids(filter(null, "2026-11-01T08:00:00", null, null, null, null, null))).containsExactly(1L);
    }

    /** 状态类与精确码之间取 OR；-1 只能用精确码选中，不属于任何状态类。 */
    @Test
    void statusClassesAndCodesAreOred() {
        assertThat(ids(filter(null, null, List.of("5xx", "429"), null, null, null, null)))
                .containsExactly(7L, 3L, 2L);
        assertThat(ids(filter(null, null, List.of("-1"), null, null, null, null))).containsExactly(5L);
        assertThat(ids(filter(null, null, List.of("2xx", "4xx", "5xx"), null, null, null, null)))
                .doesNotContain(5L);
    }

    @Test
    void exactMatchIsCaseSensitive() {
        assertThat(ids(filter(null, null, null, "deepseek", null, null, null))).containsExactly(3L);
        assertThat(ids(filter(null, null, null, null, null, "step-3", "exact"))).containsExactly(1L);
    }

    @Test
    void containsMatchIgnoresAsciiCase() {
        assertThat(ids(filter(null, null, null, "SEEK", "contains", null, null))).containsExactly(4L, 3L);
        assertThat(ids(filter(null, null, null, null, null, "step-3", "contains"))).containsExactly(2L, 1L);
    }

    /** 用户输入的 _ 与 % 是字面量，不是通配符。 */
    @Test
    void containsMatchEscapesLikeMetacharacters() {
        assertThat(ids(filter(null, null, null, null, null, "t_5", "contains"))).containsExactly(5L);
        assertThat(ids(filter(null, null, null, null, null, "m%5", "contains"))).containsExactly(7L);
        assertThat(ids(filter(null, null, null, null, null, "\\", "contains"))).isEmpty();
    }

    @Test
    void escapeLikeHandlesBackslashFirst() {
        assertThat(ApiCallLogRepository.escapeLike("a\\%_b")).isEqualTo("a\\\\\\%\\_b");
    }

    /** 不同字段之间取 AND。 */
    @Test
    void conditionsAreAnded() {
        assertThat(ids(filter("2026-11-01T00:00:00", "2026-11-01T23:59:59", List.of("4xx"),
                "step", "contains", null, null))).containsExactly(2L);
        assertThat(ids(filter(null, null, List.of("2xx"), "zhipu", null, "glm", "contains"))).containsExactly(8L);
    }

    /** 带条件翻页：沿用 id 游标，逐页拼起来与一次取全相同，不重复不遗漏。 */
    @Test
    @SuppressWarnings("unchecked")
    void paginationWithFilterIsContiguous() {
        CallLogFilter filter = filter(null, null, List.of("2xx", "5xx"), null, null, null, null);
        List<Long> collected = new ArrayList<>();
        Long cursor = null;
        int pages = 0;
        do {
            Map<String, Object> page = repository.findLogs(filter, cursor, 2);
            ((List<Map<String, Object>>) page.get("items"))
                    .forEach(row -> collected.add(((Number) row.get("id")).longValue()));
            cursor = page.get("nextCursor") == null ? null : ((Number) page.get("nextCursor")).longValue();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(collected).containsExactly(8L, 7L, 6L, 3L, 1L);
        assertThat(collected).isEqualTo(ids(filter));
    }
}

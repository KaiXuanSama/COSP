package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.LogEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段一验证与锁定：三个 save 方法通过 KeyHolder 返回新行自增 id。
 *
 * 覆盖点：
 * - 返回值为正、且等于数据库中实际写入行的 id；
 * - 连续写入返回递增 id；
 * - 三个方法（非流式 / 流式 / 流式含错误）均正确回传 id；
 * - 现有列照常写入（回归，SQL 未变）；
 * - 写入失败返回 null 且不抛异常（容错策略保留）。
 */
class ApiCallLogRepositoryGeneratedKeyTests {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private ApiCallLogRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("api-log-genkey.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE api_call_log ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT, model_name TEXT, is_stream INTEGER NOT NULL DEFAULT 0, "
                + "status_code INTEGER NOT NULL DEFAULT 0, request_headers TEXT, request_body TEXT, response_headers TEXT, response_body TEXT, "
                + "chunks TEXT, duration_ms INTEGER, created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");
        repository = new ApiCallLogRepository(jdbcTemplate, new ObjectMapper(), new LogEventPublisher());
    }

    @Test
    void saveNonStreamReturnsGeneratedIdMatchingRow() {
        Long id = repository.saveNonStream("deepseek", "gpt",
                Map.of("h", "1"), Map.of("model", "gpt"),
                Map.of("r", "2"), 200, "{\"ok\":true}", 123L);

        assertThat(id).isNotNull().isPositive();
        Long actual = jdbcTemplate.queryForObject(
                "SELECT id FROM api_call_log WHERE id = ?", Long.class, id);
        assertThat(actual).isEqualTo(id);
    }

    @Test
    void saveStreamReturnsGeneratedIdMatchingRow() {
        Long id = repository.saveStream("mimo", "mimo",
                Map.of("h", "1"), Map.of("model", "mimo"),
                Map.of("r", "2"), 200, List.of("chunk-a", "chunk-b"), 456L);

        assertThat(id).isNotNull().isPositive();
        Map<String, Object> row = repository.findLogById(id);
        assertThat(row).isNotNull();
        assertThat(((Number) row.get("id")).longValue()).isEqualTo(id);
        assertThat(row.get("is_stream")).isEqualTo(1);
    }

    @Test
    void saveStreamWithErrorReturnsGeneratedIdMatchingRow() {
        Long id = repository.saveStreamWithError("custom", "custom",
                Map.of("h", "1"), Map.of("model", "custom"),
                Map.of("r", "2"), 200, List.of("partial"),
                Map.of("e", "3"), 500, "{\"error\":\"boom\"}", 789L);

        assertThat(id).isNotNull().isPositive();
        Map<String, Object> row = repository.findLogById(id);
        assertThat(row).isNotNull();
        assertThat(((Number) row.get("status_code")).intValue()).isEqualTo(500);
    }

    @Test
    void consecutiveSavesReturnIncreasingIds() {
        Long first = repository.saveNonStream("p", "m",
                Map.of(), Map.of(), Map.of(), 200, "a", 1L);
        Long second = repository.saveStream("p", "m",
                Map.of(), Map.of(), Map.of(), 200, List.of("c"), 2L);
        Long third = repository.saveStreamWithError("p", "m",
                Map.of(), Map.of(), Map.of(), 200, List.of("c"),
                Map.of(), 500, "err", 3L);

        assertThat(first).isNotNull();
        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
    }

    @Test
    void existingColumnsPersistedUnchanged() {
        Long id = repository.saveNonStream("deepseek", "gpt-4",
                Map.of("Authorization", "***"), Map.of("model", "gpt-4", "stream", false),
                Map.of("Content-Type", "application/json"), 200, "{\"choices\":[]}", 321L);

        Map<String, Object> row = repository.findLogById(id);
        assertThat(row).isNotNull();
        assertThat(row.get("provider_key")).isEqualTo("deepseek");
        assertThat(row.get("model_name")).isEqualTo("gpt-4");
        assertThat(row.get("is_stream")).isEqualTo(0);
        assertThat(((Number) row.get("status_code")).intValue()).isEqualTo(200);
        assertThat(row.get("response_body")).isEqualTo("{\"choices\":[]}");
        assertThat(((Number) row.get("duration_ms")).longValue()).isEqualTo(321L);
        assertThat(row.get("request_headers")).asString().contains("Authorization");
    }

    @Test
    void writeFailureReturnsNullWithoutThrowing() {
        // 指向一个不存在表的库，触发 INSERT 失败；断言不抛异常且返回 null。
        SQLiteDataSource brokenDs = new SQLiteDataSource();
        brokenDs.setUrl("jdbc:sqlite:" + tempDir.resolve("broken.db"));
        JdbcTemplate brokenTemplate = new JdbcTemplate(brokenDs);
        // 故意不建表
        ApiCallLogRepository brokenRepo =
                new ApiCallLogRepository(brokenTemplate, new ObjectMapper(), new LogEventPublisher());

        Long id = brokenRepo.saveNonStream("p", "m",
                Map.of(), Map.of(), Map.of(), 200, "body", 1L);

        assertThat(id).isNull();
    }
}

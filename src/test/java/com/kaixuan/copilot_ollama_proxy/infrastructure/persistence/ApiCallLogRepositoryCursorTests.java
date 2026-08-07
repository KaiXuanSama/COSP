package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.LogEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiCallLogRepositoryCursorTests {

    @TempDir
    Path tempDir;

    private ApiCallLogRepository repository;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("api-log-cursor.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE api_call_log ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT, model_name TEXT, is_stream INTEGER NOT NULL DEFAULT 0, "
                + "status_code INTEGER NOT NULL DEFAULT 0, request_headers TEXT, request_body TEXT, response_headers TEXT, response_body TEXT, "
                + "chunks TEXT, duration_ms INTEGER, payload_trimmed INTEGER NOT NULL DEFAULT 0, "
                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");
        insertLog("deepseek", "gpt", 200, "2026-07-11T10:00:00");
        insertLog("mimo", "mimo", 200, "2026-07-11T09:00:00");
        insertLog("custom", "custom", 500, "2026-07-11T08:00:00");
        repository = new ApiCallLogRepository(jdbcTemplate, new ObjectMapper(), new LogEventPublisher());
    }

    /** 插入一条载荷五列齐备的日志，以便断言瘦身确实清空了它们。 */
    private void insertLog(String providerKey, String modelName, int statusCode, String createdAt) {
        jdbcTemplate.update("INSERT INTO api_call_log (provider_key, model_name, status_code, "
                        + "request_headers, request_body, response_headers, response_body, chunks, "
                        + "duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                providerKey, modelName, statusCode,
                "{\"h\":1}", "{\"keep\":true}", "{\"h\":2}", "{\"body\":1}", "[\"chunk\"]",
                1200, createdAt);
    }

    @Test
    void cursorPaginationReturnsNextCursorAndStopsWhenExhausted() {
        Map<String, Object> firstPage = repository.findLogs(null, 2);
        assertThat(firstPage).containsKeys("items", "nextCursor", "hasMore");
        assertThat(((java.util.List<?>) firstPage.get("items"))).hasSize(2);
        assertThat(firstPage.get("hasMore")).isEqualTo(true);
        assertThat(firstPage.get("nextCursor")).isEqualTo(2L);

        Map<String, Object> secondPage = repository.findLogs(((Number) firstPage.get("nextCursor")).longValue(), 2);
        assertThat(((java.util.List<?>) secondPage.get("items"))).hasSize(1);
        assertThat(secondPage.get("hasMore")).isEqualTo(false);
        assertThat(secondPage.get("nextCursor")).isNull();
    }

    /**
     * 瘦身只清空载荷列并置标记，元信息与行本身必须完好无损。
     *
     * 这是「日志表成为用量表超集」的核心保证：状态码与耗时若随载荷一同消失，
     * 消费者视角就仍需跨表取数。
     */
    @Test
    void payloadTrimClearsBodiesButKeepsRowAndMetadata() {
        int trimmed = repository.trimPayloadToLatest(1);

        assertThat(trimmed).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM api_call_log", Integer.class))
                .isEqualTo(3);

        Map<String, Object> oldest = jdbcTemplate.queryForMap("SELECT * FROM api_call_log WHERE id = 1");
        assertThat(oldest.get("request_headers")).isNull();
        assertThat(oldest.get("request_body")).isNull();
        assertThat(oldest.get("response_headers")).isNull();
        assertThat(oldest.get("response_body")).isNull();
        assertThat(oldest.get("chunks")).isNull();
        assertThat(((Number) oldest.get("payload_trimmed")).intValue()).isEqualTo(1);
        // 元信息一律保留，这是瘦身区别于删除的全部意义。
        assertThat(oldest.get("provider_key")).isEqualTo("deepseek");
        assertThat(oldest.get("model_name")).isEqualTo("gpt");
        assertThat(((Number) oldest.get("status_code")).intValue()).isEqualTo(200);
        assertThat(oldest.get("created_at")).isEqualTo("2026-07-11T10:00:00");

        // 保留窗口按 id 倒序取最新 1 条，即 id=3；其载荷未被触碰。
        Map<String, Object> newest = jdbcTemplate.queryForMap("SELECT * FROM api_call_log WHERE id = 3");
        assertThat(((Number) newest.get("payload_trimmed")).intValue()).isZero();
        assertThat(newest.get("request_body")).isEqualTo("{\"keep\":true}");
    }

    /** 重复执行只处理新落入区间的行，已瘦身的行不再被重写。 */
    @Test
    void payloadTrimIsIdempotent() {
        assertThat(repository.trimPayloadToLatest(1)).isEqualTo(2);
        assertThat(repository.trimPayloadToLatest(1)).isZero();
    }
}

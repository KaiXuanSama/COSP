package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("api-log-cursor.db"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE api_call_log ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT, model_name TEXT, is_stream INTEGER NOT NULL DEFAULT 0, "
                + "status_code INTEGER NOT NULL DEFAULT 0, request_headers TEXT, request_body TEXT, response_headers TEXT, response_body TEXT, "
                + "chunks TEXT, duration_ms INTEGER, created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");
        jdbcTemplate.update("INSERT INTO api_call_log (provider_key, model_name, status_code, created_at) VALUES (?, ?, ?, ?)", "deepseek", "gpt", 200, "2026-07-11T10:00:00");
        jdbcTemplate.update("INSERT INTO api_call_log (provider_key, model_name, status_code, created_at) VALUES (?, ?, ?, ?)", "mimo", "mimo", 200, "2026-07-11T09:00:00");
        jdbcTemplate.update("INSERT INTO api_call_log (provider_key, model_name, status_code, created_at) VALUES (?, ?, ?, ?)", "custom", "custom", 500, "2026-07-11T08:00:00");
        repository = new ApiCallLogRepository(jdbcTemplate, new ObjectMapper());
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
}

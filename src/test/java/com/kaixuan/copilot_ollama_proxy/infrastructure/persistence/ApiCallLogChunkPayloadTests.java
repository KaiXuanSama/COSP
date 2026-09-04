package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.LogEventPublisher;
import com.kaixuan.copilot_ollama_proxy.provider.ChunkLogPayload;
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
 * {@code api_call_log.chunks} 的「一列两形」落库。
 *
 * <p>直连存裸数组（与历史数据完全一致），跨协议翻译存
 * {@code {translated: [...], upstream: [...]}} 对象。同一列两种形状使这个能力
 * 无需 schema 迁移，保留任务也不必改（仍是清空 {@code chunks} 一列）。
 */
class ApiCallLogChunkPayloadTests {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JdbcTemplate jdbcTemplate;
    private ApiCallLogRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("api-log-chunk-payload.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE api_call_log ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT, model_name TEXT, "
                + "is_stream INTEGER NOT NULL DEFAULT 0, "
                + "downstream_protocol TEXT NOT NULL DEFAULT 'OPENAI', "
                + "upstream_protocol TEXT NOT NULL DEFAULT 'OPENAI', "
                + "status_code INTEGER NOT NULL DEFAULT 0, request_headers TEXT, request_body TEXT, "
                + "response_headers TEXT, response_body TEXT, chunks TEXT, duration_ms INTEGER, "
                + "payload_trimmed INTEGER NOT NULL DEFAULT 0, "
                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')))");
        repository = new ApiCallLogRepository(jdbcTemplate, objectMapper, new LogEventPublisher());
    }

    /** 直连：落成裸数组，与历史数据同形，前端现有解析路径不受影响。 */
    @Test
    void directCallStoresBareArray() throws Exception {
        Long id = repository.saveStream("deepseek", "model-x", "ANTHROPIC", "ANTHROPIC",
                Map.of(), Map.of(), Map.of(), 200,
                ChunkLogPayload.direct(List.of("{\"a\":1}", "[DONE]")), 100L);

        JsonNode stored = readChunks(id);

        assertThat(stored.isArray()).isTrue();
        assertThat(stored).hasSize(2);
        assertThat(stored.get(1).asText()).isEqualTo("[DONE]");
    }

    /** 跨协议：两份都留，供日志页做上下游对照。 */
    @Test
    void translatedCallStoresBothViews() throws Exception {
        List<String> upstream = List.of(
                "{\"type\":\"message_start\"}",
                "{\"type\":\"content_block_delta\"}");
        List<String> translated = List.of("{\"object\":\"chat.completion.chunk\"}", "[DONE]");

        Long id = repository.saveStream("deepseek", "model-x", "OPENAI", "ANTHROPIC",
                Map.of(), Map.of(), Map.of(), 200,
                ChunkLogPayload.translated(translated, upstream), 100L);

        JsonNode stored = readChunks(id);

        assertThat(stored.isObject()).isTrue();
        assertThat(stored.get(ChunkLogPayload.KEY_TRANSLATED)).hasSize(2);
        assertThat(stored.get(ChunkLogPayload.KEY_UPSTREAM)).hasSize(2);
        // 帧数不对等是翻译路线的常态，两份各自独立，不做长度校验。
        assertThat(stored.get(ChunkLogPayload.KEY_UPSTREAM).get(0).asText())
                .contains("message_start");
        assertThat(stored.get(ChunkLogPayload.KEY_TRANSLATED).get(0).asText())
                .contains("chat.completion.chunk");
    }

    /** 错误路径同样支持两形，否则失败调用会丢掉上游证据。 */
    @Test
    void errorPathAlsoStoresBothViews() throws Exception {
        Long id = repository.saveStreamWithError("deepseek", "model-x", "OPENAI", "ANTHROPIC",
                Map.of(), Map.of(), Map.of(), 200,
                ChunkLogPayload.translated(List.of("{\"x\":1}"), List.of("{\"type\":\"ping\"}")),
                Map.of(), 500, "boom", 100L);

        JsonNode stored = readChunks(id);

        assertThat(stored.isObject()).isTrue();
        assertThat(stored.get(ChunkLogPayload.KEY_UPSTREAM).get(0).asText()).contains("ping");
    }

    /**
     * 保留任务仍只清空 {@code chunks} 一列 —— 两形共用一列的直接收益。
     */
    @Test
    void trimClearsBothViewsBecauseTheyShareOneColumn() {
        repository.saveStream("deepseek", "model-x", "OPENAI", "ANTHROPIC",
                Map.of(), Map.of(), Map.of(), 200,
                ChunkLogPayload.translated(List.of("{\"x\":1}"), List.of("{\"y\":2}")), 100L);
        repository.saveStream("deepseek", "model-x", "OPENAI", "ANTHROPIC",
                Map.of(), Map.of(), Map.of(), 200,
                ChunkLogPayload.translated(List.of("{\"x\":2}"), List.of("{\"y\":3}")), 100L);

        int trimmed = repository.trimPayloadToLatest(1);

        assertThat(trimmed).isEqualTo(1);
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM api_call_log WHERE chunks IS NOT NULL", Integer.class);
        assertThat(remaining).isEqualTo(1);
    }

    private JsonNode readChunks(Long id) throws Exception {
        String raw = jdbcTemplate.queryForObject(
                "SELECT chunks FROM api_call_log WHERE id = ?", String.class, id);
        return objectMapper.readTree(raw);
    }
}

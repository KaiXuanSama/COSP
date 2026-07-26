package com.kaixuan.copilot_ollama_proxy.application.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallLogRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.LogEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段四（链路部分）验证与锁定：日志详情端点回传结构中附带 usage 字段。
 *
 * <p>覆盖：
 * <ul>
 *   <li>有用量记录 → {@code usage} 为 api_call_usage 完整行（含 usage_raw 原始 JSON）；</li>
 *   <li>无用量记录 → {@code usage} 为 null，语义为"未查询到"
 *       （V8.3 前的旧日志 / 失败调用 / 上游未返回 usage）；</li>
 *   <li>日志本身不存在 → 返回 empty（404 由 controller 处理）；</li>
 *   <li>null vs 0 语义透传：可空 token 保持 null，不被读成 0。</li>
 * </ul>
 */
class CallLogQueryServiceUsageDetailTests {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private CallLogQueryService service;
    private ApiCallUsageRepository usageRepository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("log-usage-detail.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE api_call_log ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT, model_name TEXT, "
                + "is_stream INTEGER NOT NULL DEFAULT 0, status_code INTEGER NOT NULL DEFAULT 0, "
                + "request_headers TEXT, request_body TEXT, response_headers TEXT, response_body TEXT, "
                + "chunks TEXT, duration_ms INTEGER, "
                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')))");
        jdbcTemplate.execute("CREATE TABLE api_call_usage ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, log_id INTEGER, provider_key TEXT, model_name TEXT, "
                + "is_stream INTEGER NOT NULL DEFAULT 0, usage_raw TEXT, prompt_tokens INTEGER, "
                + "completion_tokens INTEGER, cached_tokens INTEGER, ttfb_ms INTEGER, "
                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')))");

        ApiCallLogRepository logRepository =
                new ApiCallLogRepository(jdbcTemplate, new ObjectMapper(), new LogEventPublisher());
        usageRepository = new ApiCallUsageRepository(jdbcTemplate);
        service = new CallLogQueryService(logRepository, usageRepository, new LogEventPublisher());
    }

    @Test
    void detailCarriesUsageRowWhenUsageExists() {
        jdbcTemplate.update("INSERT INTO api_call_log (provider_key, model_name, is_stream, status_code) "
                + "VALUES ('deepseek', 'chat', 1, 200)");
        long logId = jdbcTemplate.queryForObject("SELECT id FROM api_call_log", Long.class);
        String usageRaw = "{\"prompt_tokens\":26349,\"completion_tokens\":348,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":25856}}";
        usageRepository.save(logId, "deepseek", "chat", true, usageRaw,
                new UsageTokens(26349, 348, 25856), 812);

        Optional<Map<String, Object>> detail = service.findLog(logId).block();

        assertThat(detail).isPresent();
        Map<String, Object> row = detail.orElseThrow();
        // 日志本体字段照常返回。
        assertThat(row.get("provider_key")).isEqualTo("deepseek");
        assertThat(row.get("model_name")).isEqualTo("chat");
        // usage 字段为完整用量行。
        assertThat(row).containsKey("usage");
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) row.get("usage");
        assertThat(usage).isNotNull();
        assertThat(((Number) usage.get("log_id")).longValue()).isEqualTo(logId);
        assertThat(((Number) usage.get("prompt_tokens")).intValue()).isEqualTo(26349);
        assertThat(((Number) usage.get("completion_tokens")).intValue()).isEqualTo(348);
        assertThat(((Number) usage.get("cached_tokens")).intValue()).isEqualTo(25856);
        assertThat(((Number) usage.get("ttfb_ms")).intValue()).isEqualTo(812);
        // 原始 usage 对象零损失保留，供前端按需展示未提列的字段。
        assertThat(usage.get("usage_raw")).asString().contains("prompt_tokens_details");
    }

    @Test
    void detailCarriesNullUsageWhenNoUsageRecord() {
        // 模拟迁移期旧日志：只有 chunk 日志、没有对应用量行。
        jdbcTemplate.update("INSERT INTO api_call_log (provider_key, model_name, is_stream, status_code) "
                + "VALUES ('legacy', 'old-model', 0, 200)");
        long logId = jdbcTemplate.queryForObject("SELECT id FROM api_call_log", Long.class);

        Optional<Map<String, Object>> detail = service.findLog(logId).block();

        assertThat(detail).isPresent();
        Map<String, Object> row = detail.orElseThrow();
        assertThat(row.get("provider_key")).isEqualTo("legacy");
        // usage 键存在但值为 null —— 语义为"未查询到"，前端据此显示"—"。
        assertThat(row).containsKey("usage");
        assertThat(row.get("usage")).isNull();
    }

    @Test
    void detailPreservesNullTokensWithoutCoercingToZero() {
        jdbcTemplate.update("INSERT INTO api_call_log (provider_key, model_name, is_stream, status_code) "
                + "VALUES ('p', 'm', 0, 200)");
        long logId = jdbcTemplate.queryForObject("SELECT id FROM api_call_log", Long.class);
        // 上游只报了 prompt/completion，无缓存信息；非流式无首字时长。
        usageRepository.save(logId, "p", "m", false, "{\"prompt_tokens\":10,\"completion_tokens\":0}",
                new UsageTokens(10, 0, null), null);

        Map<String, Object> row = service.findLog(logId).block().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) row.get("usage");

        assertThat(((Number) usage.get("prompt_tokens")).intValue()).isEqualTo(10);
        // 0 是上游报告的真实零值，必须保留为 0。
        assertThat(((Number) usage.get("completion_tokens")).intValue()).isEqualTo(0);
        // null 是"上游未提供"，绝不能读成 0。
        assertThat(usage.get("cached_tokens")).isNull();
        assertThat(usage.get("ttfb_ms")).isNull();
    }

    @Test
    void missingLogReturnsEmpty() {
        assertThat(service.findLog(9999L).block()).isEmpty();
    }
}

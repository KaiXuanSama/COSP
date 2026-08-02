package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageRecordDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段三验证与锁定：api_call_usage 落库层。
 *
 * <p>核心断言：
 * <ul>
 *   <li>可空 token 直接落库并读回：null 保持 null（不被读成 0），0 保持 0；</li>
 *   <li>log_id 软链接可空（写孤儿行）；</li>
 *   <li>usage_raw / is_stream / ttfb_ms 正确写入；</li>
 *   <li>findByLogId 按 log_id 反查；</li>
 *   <li>写入成功后广播增量帧，字段与落库值同源；</li>
 *   <li>写入失败（无表）只 warn 不抛，且<strong>不发</strong>增量帧。</li>
 * </ul>
 */
class ApiCallUsageRepositoryTests {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private UsageEventPublisher publisher;
    private ApiCallUsageRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("api-usage.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        publisher = new UsageEventPublisher();
        jdbcTemplate.execute("CREATE TABLE api_call_usage ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, log_id INTEGER, provider_key VARCHAR(30), "
                + "model_name VARCHAR(100), is_stream INTEGER NOT NULL DEFAULT 0 CHECK (is_stream IN (0, 1)), "
                + "usage_raw TEXT, "
                + "prompt_tokens INTEGER CHECK (prompt_tokens IS NULL OR prompt_tokens >= 0), "
                + "completion_tokens INTEGER CHECK (completion_tokens IS NULL OR completion_tokens >= 0), "
                + "cached_tokens INTEGER CHECK (cached_tokens IS NULL OR cached_tokens >= 0), "
                + "ttfb_ms INTEGER CHECK (ttfb_ms IS NULL OR ttfb_ms >= 0), "
                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')))");
        repository = new ApiCallUsageRepository(jdbcTemplate, publisher);
    }

    @Test
    void savesFullRowAndReadsBackByLogId() {
        repository.save(42L, "deepseek", "gpt", true, "{\"prompt_tokens\":100}",
                new UsageTokens(100, 20, 80), 350);

        Map<String, Object> row = repository.findByLogId(42L);
        assertThat(row).isNotNull();
        assertThat(((Number) row.get("log_id")).longValue()).isEqualTo(42L);
        assertThat(row.get("provider_key")).isEqualTo("deepseek");
        assertThat(row.get("model_name")).isEqualTo("gpt");
        assertThat(((Number) row.get("is_stream")).intValue()).isEqualTo(1);
        assertThat(row.get("usage_raw")).isEqualTo("{\"prompt_tokens\":100}");
        assertThat(((Number) row.get("prompt_tokens")).intValue()).isEqualTo(100);
        assertThat(((Number) row.get("completion_tokens")).intValue()).isEqualTo(20);
        assertThat(((Number) row.get("cached_tokens")).intValue()).isEqualTo(80);
        assertThat(((Number) row.get("ttfb_ms")).intValue()).isEqualTo(350);
    }

    @Test
    void nullCachedTokensStaysNull() {
        // cached=null（上游未提供）落库后必须读回 null，不能被读成 0。
        repository.save(1L, "p", "m", false, "{}",
                new UsageTokens(500, 30, null), null);

        Map<String, Object> row = repository.findByLogId(1L);
        assertThat(row).isNotNull();
        assertThat(row.get("cached_tokens")).isNull();
        assertThat(row.get("ttfb_ms")).isNull();
        assertThat(((Number) row.get("prompt_tokens")).intValue()).isEqualTo(500);
    }

    @Test
    void zeroCachedTokensStaysZero() {
        // cached=0（真实 0% 命中）落库后必须读回 0，与 null 区分。
        repository.save(2L, "p", "m", true, "{}",
                new UsageTokens(500, 30, 0), 100);

        Map<String, Object> row = repository.findByLogId(2L);
        assertThat(row).isNotNull();
        assertThat(row.get("cached_tokens")).isNotNull();
        assertThat(((Number) row.get("cached_tokens")).intValue()).isEqualTo(0);
    }

    @Test
    void nullLogIdWritesOrphanRow() {
        // 软链接：拿不到日志 id 时写 log_id=null 的孤儿行，仍合法落库。
        repository.save(null, "p", "m", true, "{\"prompt_tokens\":1}",
                new UsageTokens(1, 1, null), 50);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM api_call_usage WHERE log_id IS NULL", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void nullTokensObjectTreatedAsEmpty() {
        // tokens 传 null（防御边界）：三列全 null 落库，不抛。
        repository.save(3L, "p", "m", false, null, null, null);

        Map<String, Object> row = repository.findByLogId(3L);
        assertThat(row).isNotNull();
        assertThat(row.get("prompt_tokens")).isNull();
        assertThat(row.get("completion_tokens")).isNull();
        assertThat(row.get("cached_tokens")).isNull();
        assertThat(row.get("usage_raw")).isNull();
    }

    @Test
    void writeFailureOnMissingTableDoesNotThrow() {
        SQLiteDataSource brokenDs = new SQLiteDataSource();
        brokenDs.setUrl("jdbc:sqlite:" + tempDir.resolve("broken-usage.db"));
        UsageEventPublisher publisher = new UsageEventPublisher();
        ApiCallUsageRepository brokenRepo =
                new ApiCallUsageRepository(new JdbcTemplate(brokenDs), publisher);

        AtomicBoolean published = new AtomicBoolean(false);
        publisher.deltas().subscribe(delta -> published.set(true));

        // 无表 → INSERT 失败；断言只 warn 不抛。
        brokenRepo.save(1L, "p", "m", true, "{}", new UsageTokens(1, 1, 1), 10);

        // 且不发增量帧：帧一旦发出前端就会累加，而这次调用并未落库。
        assertThat(published).isFalse();
    }

    /**
     * 写入成功后广播一帧增量，{@code createdAt} 与落库值<strong>同源</strong>。
     *
     * <p>同源是硬要求：前端要用它算出日期去匹配柱子，而柱状图的日期来自
     * {@code aggregateBreakdown} 的 {@code substr(created_at, 1, 10)}。
     * 若帧里的时刻由应用二次取 {@code now()} 生成，跨午夜的瞬间两者会来自不同时钟，
     * 出现「帧说今天、库里记昨天」的偏差，增量就落到错误的日期上。
     *
     * <p>顺带锁定「帧不带派生日期字段」这一设计：日期是 createdAt 的前 10 位，
     * 由消费侧现算，不存在两个字段不一致的可能。
     */
    @Test
    void successfulSavePublishesDeltaMatchingStoredRow() {
        AtomicReference<UsageRecordDelta> captured = new AtomicReference<>();
        publisher.deltas().subscribe(captured::set);

        repository.save(7L, "deepseek", "chat", true, "{}", new UsageTokens(120, 45, null), 80);

        UsageRecordDelta delta = captured.get();
        assertThat(delta).isNotNull();
        assertThat(delta.providerKey()).isEqualTo("deepseek");
        assertThat(delta.modelName()).isEqualTo("chat");
        assertThat(delta.inputTokens()).isEqualTo(120L);
        assertThat(delta.outputTokens()).isEqualTo(45L);

        Map<String, Object> row = repository.findByLogId(7L);
        assertThat(row).isNotNull();
        String storedCreatedAt = (String) row.get("created_at");
        assertThat(delta.createdAt()).isEqualTo(storedCreatedAt);

        // 与柱状图聚合同一口径：消费侧取前 10 位即得到可匹配的日期。
        assertThat(delta.createdAt().substring(0, 10)).isEqualTo(storedCreatedAt.substring(0, 10));
    }

    /**
     * token 为 null 的防御路径：帧里按 0 下发，而<strong>库里仍是 null</strong>。
     *
     * <p>两侧口径不同是刻意的。库保留 null（上游未提供）与 0（真实零值）的区分，
     * 供日志详情页与将来的口径审计使用；而帧只在落库成功后发出，
     * 上游未返回 usage 时根本走不到写入 —— null 在这条路径上不该出现。
     * 真正的兜底是「落库成功才推帧」这条规则，不是在传输层保留一个不会出现的状态。
     */
    @Test
    void deltaFallsBackToZeroWhileRowKeepsNull() {
        AtomicReference<UsageRecordDelta> captured = new AtomicReference<>();
        publisher.deltas().subscribe(captured::set);

        repository.save(8L, "p", "m", false, null, null, null);

        UsageRecordDelta delta = captured.get();
        assertThat(delta).isNotNull();
        assertThat(delta.inputTokens()).isZero();
        assertThat(delta.outputTokens()).isZero();

        Map<String, Object> row = repository.findByLogId(8L);
        assertThat(row).isNotNull();
        assertThat(row.get("prompt_tokens")).as("库里保留 null 语义").isNull();
        assertThat(row.get("completion_tokens")).isNull();
    }
}

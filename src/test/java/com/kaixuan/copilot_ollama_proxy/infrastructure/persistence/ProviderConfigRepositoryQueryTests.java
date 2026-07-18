package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.kaixuan.copilot_ollama_proxy.infrastructure.security.ApiKeyCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderConfigRepositoryQueryTests {

    @TempDir
    Path tempDir;

    private CountingJdbcTemplate jdbcTemplate;
    private ProviderConfigRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("provider-query.db"));
        jdbcTemplate = new CountingJdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE provider_config ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 0, "
                + "base_url TEXT NOT NULL DEFAULT '', api_format TEXT NOT NULL DEFAULT 'openai', "
                + "updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");
        jdbcTemplate.execute("CREATE TABLE provider_model ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id INTEGER NOT NULL, model_name TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, "
                + "context_size INTEGER NOT NULL DEFAULT 0, max_output_tokens INTEGER NOT NULL DEFAULT 128000, caps_tools INTEGER NOT NULL DEFAULT 0, "
                + "caps_vision INTEGER NOT NULL DEFAULT 0, reasoning_effort TEXT NOT NULL DEFAULT 'Medium', sort_order INTEGER NOT NULL DEFAULT 0)");
        jdbcTemplate.execute("CREATE TABLE provider_api_key ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, key_uuid TEXT NOT NULL UNIQUE, provider_id INTEGER NOT NULL, key_name TEXT NOT NULL DEFAULT '', "
                + "encrypted_api_key TEXT NOT NULL, nonce TEXT NOT NULL, encryption_version INTEGER NOT NULL DEFAULT 1, is_active INTEGER NOT NULL DEFAULT 0, "
                + "sort_order INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')), "
                + "updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')), UNIQUE (provider_id, key_name))");

        jdbcTemplate.update("INSERT INTO provider_config (provider_key, enabled, base_url, api_format) VALUES (?, ?, ?, ?)", "deepseek", 1, "https://deepseek", "openai");
        jdbcTemplate.update("INSERT INTO provider_config (provider_key, enabled, base_url, api_format) VALUES (?, ?, ?, ?)", "mimo", 1, "https://mimo", "openai");
        jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name, enabled, context_size, caps_tools, caps_vision, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)", 1, "deepseek-v3", 1, 16384, 1, 0, 0);
        jdbcTemplate.update("INSERT INTO provider_model (provider_id, model_name, enabled, context_size, caps_tools, caps_vision, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)", 2, "mimo-v2", 1, 8192, 0, 1, 0);

        ApiKeyCryptoService cryptoService = new ApiKeyCryptoService("test-master-key");
        ReflectionTestUtils.invokeMethod(cryptoService, "initialize");
        ProviderApiKeyRepository providerApiKeyRepository = new ProviderApiKeyRepository(jdbcTemplate, cryptoService);
        repository = new ProviderConfigRepository(jdbcTemplate, providerApiKeyRepository);
    }

    @Test
    void findAllWithModelsLoadsProvidersAndModelsInOneQueryBatch() {
        List<ProviderConfigRow> providers = repository.findAllWithModels();

        assertThat(providers).hasSize(2);
        assertThat(jdbcTemplate.getQueryCount()).isEqualTo(1);
        assertThat(providers.get(0).models()).hasSize(1);
        assertThat(providers.get(1).models()).hasSize(1);
    }

    private static class CountingJdbcTemplate extends JdbcTemplate {
        private final AtomicInteger queryCount = new AtomicInteger();

        private CountingJdbcTemplate(SQLiteDataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            queryCount.incrementAndGet();
            return super.query(sql, rowMapper);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            queryCount.incrementAndGet();
            return super.query(sql, rowMapper, args);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            queryCount.incrementAndGet();
            return super.queryForList(sql, args);
        }

        private int getQueryCount() {
            return queryCount.get();
        }
    }
}

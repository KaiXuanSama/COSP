package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRequestTransformRepositoryTests {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private ProviderRequestTransformRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("request-transform.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE provider_config ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT NOT NULL UNIQUE)");
        jdbcTemplate.execute("CREATE TABLE provider_request_transform ("
                + "provider_id INTEGER PRIMARY KEY, "
                + "header_rules_version INTEGER NOT NULL CHECK (header_rules_version >= 1), "
                + "header_rules_json TEXT NOT NULL CHECK (json_valid(header_rules_json)), "
                + "body_template_keys_json TEXT NOT NULL CHECK (json_valid(body_template_keys_json)), "
                + "body_preview_json TEXT NOT NULL CHECK (json_valid(body_preview_json)), "
                + "body_rules_version INTEGER NOT NULL CHECK (body_rules_version >= 1), "
                + "body_rules_json TEXT NOT NULL CHECK (json_valid(body_rules_json)), "
                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')), "
                + "updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')), "
                + "FOREIGN KEY (provider_id) REFERENCES provider_config(id) ON DELETE CASCADE)");
        repository = new ProviderRequestTransformRepository(jdbcTemplate);
    }

    @Test
    void findByProviderIdReturnsNullWhenConfigurationDoesNotExist() {
        assertThat(repository.findByProviderId(404)).isNull();
        assertThat(repository.findByProviderIds(null)).isEmpty();
        assertThat(repository.findByProviderIds(java.util.List.of())).isEmpty();
    }

    @Test
    void upsertInsertsAndReturnsEveryPersistedField() {
        int providerId = insertProvider("alpha");

        repository.upsert(
                providerId, 1, "[{\"key\":\"api-key\",\"value\":\"{apiKey}\"}]",
                "[\"base\",\"tools\"]", "{\"model\":\"<string>\",\"stream\":true}",
                1, "{\"version\":1,\"rules\":[]}");

        ProviderRequestTransformRow row = repository.findByProviderId(providerId);
        assertThat(row).isNotNull();
        assertThat(row.providerId()).isEqualTo(providerId);
        assertThat(row.headerRulesVersion()).isEqualTo(1);
        assertThat(row.headerRulesJson()).contains("api-key");
        assertThat(row.bodyTemplateKeysJson()).isEqualTo("[\"base\",\"tools\"]");
        assertThat(row.bodyPreviewJson()).contains("\"stream\":true");
        assertThat(row.bodyRulesVersion()).isEqualTo(1);
        assertThat(row.bodyRulesJson()).isEqualTo("{\"version\":1,\"rules\":[]}");
        assertThat(row.createdAt()).isNotBlank();
        assertThat(row.updatedAt()).isNotBlank();
    }

    @Test
    void upsertUpdatesExistingRowWithoutCreatingDuplicate() {
        int providerId = insertProvider("alpha");
        repository.upsert(providerId, 1, "[]", "[\"base\"]", "{}", 1,
                "{\"version\":1,\"rules\":[]}");

        repository.upsert(providerId, 2, "[{\"key\":\"x-token\",\"value\":\"v\"}]",
                "[\"custom\"]", "{\"custom\":true}", 2,
                "{\"version\":1,\"rules\":[]}");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_request_transform WHERE provider_id = ?",
                Integer.class, providerId);
        ProviderRequestTransformRow row = repository.findByProviderId(providerId);
        assertThat(count).isEqualTo(1);
        assertThat(row.headerRulesVersion()).isEqualTo(2);
        assertThat(row.headerRulesJson()).contains("x-token");
        assertThat(row.bodyTemplateKeysJson()).isEqualTo("[\"custom\"]");
        assertThat(row.bodyPreviewJson()).isEqualTo("{\"custom\":true}");
        assertThat(row.bodyRulesVersion()).isEqualTo(2);
    }

    @Test
    void findByProviderIdsReturnsOnlyRequestedRowsKeyedByProviderId() {
        int firstId = insertProvider("alpha");
        int secondId = insertProvider("beta");
        int thirdId = insertProvider("gamma");
        repository.upsert(firstId, 1, "[]", "[\"base\"]", "{\"id\":1}", 1,
                "{\"version\":1,\"rules\":[]}");
        repository.upsert(secondId, 1, "[]", "[\"base\"]", "{\"id\":2}", 1,
                "{\"version\":1,\"rules\":[]}");
        repository.upsert(thirdId, 1, "[]", "[\"base\"]", "{\"id\":3}", 1,
                "{\"version\":1,\"rules\":[]}");

        Map<Integer, ProviderRequestTransformRow> result = repository.findByProviderIds(
                java.util.List.of(thirdId, firstId));

        assertThat(result).containsOnlyKeys(firstId, thirdId);
        assertThat(result.values()).extracting(ProviderRequestTransformRow::providerId)
                .containsExactly(firstId, thirdId);
    }

    private int insertProvider(String providerKey) {
        jdbcTemplate.update("INSERT INTO provider_config (provider_key) VALUES (?)", providerKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM provider_config WHERE provider_key = ?", Integer.class, providerKey);
    }
}

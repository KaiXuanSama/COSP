package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryUpsertTests {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private ProviderConfigRepository providerConfigRepository;
    private AppConfigRepository appConfigRepository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("repository.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE provider_config ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT NOT NULL UNIQUE, "
                + "enabled INTEGER NOT NULL DEFAULT 0, base_url TEXT NOT NULL DEFAULT '', "
                + "api_key TEXT NOT NULL DEFAULT '[]', active_api_key_index INTEGER NOT NULL DEFAULT 0, "
                + "api_format TEXT NOT NULL DEFAULT 'openai', custom_transforms TEXT NOT NULL DEFAULT '{}', "
                + "updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");
        jdbcTemplate.execute("CREATE TABLE provider_model ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id INTEGER NOT NULL, model_name TEXT NOT NULL, "
                + "enabled INTEGER NOT NULL DEFAULT 1, context_size INTEGER NOT NULL DEFAULT 0, "
                + "max_output_tokens INTEGER NOT NULL DEFAULT 128000, caps_tools INTEGER NOT NULL DEFAULT 0, "
                + "caps_vision INTEGER NOT NULL DEFAULT 0, reasoning_effort TEXT NOT NULL DEFAULT 'Medium', "
                + "sort_order INTEGER NOT NULL DEFAULT 0)");
        jdbcTemplate.execute("CREATE TABLE app_config ("
                + "config_key TEXT PRIMARY KEY, config_value TEXT NOT NULL DEFAULT '', "
                + "updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");
        providerConfigRepository = new ProviderConfigRepository(jdbcTemplate);
        appConfigRepository = new AppConfigRepository(jdbcTemplate);
    }

    @Test
    void providerUpsertUpdatesExistingRowWithoutChangingItsId() {
        int firstId = providerConfigRepository.saveProvider(
                "mimo", false, "https://old.example", "[]", "openai", "{}");
        int secondId = providerConfigRepository.saveProvider(
                "mimo", true, "https://new.example", "[{\"name\":\"New\",\"api_key\":\"sk-new\"}]",
                "openai", "{\"requestBody\":{}}");

        assertThat(secondId).isEqualTo(firstId);
        assertThat(providerConfigRepository.findAllWithModels()).hasSize(1);
        ProviderConfigRow row = providerConfigRepository.findByKey("mimo");
        assertThat(row.enabled()).isTrue();
        assertThat(row.baseUrl()).isEqualTo("https://new.example");
        assertThat(row.customTransforms()).isEqualTo("{\"requestBody\":{}}");
    }

    @Test
    void partialProviderConfigUpsertPreservesEnabledStateAndCustomTransforms() {
        providerConfigRepository.saveProvider(
                "mimo", true, "https://old.example", "[]", "openai", "{\"keep\":true}");

        int providerId = providerConfigRepository.updateProviderConfig(
                "mimo", "https://new.example", "[{\"name\":\"A\",\"api_key\":\"sk-a\"}]", 0, "openai");

        ProviderConfigRow row = providerConfigRepository.findByKey("mimo");
        assertThat(row.id()).isEqualTo(providerId);
        assertThat(row.enabled()).isTrue();
        assertThat(row.customTransforms()).isEqualTo("{\"keep\":true}");
        assertThat(row.baseUrl()).isEqualTo("https://new.example");
    }

    @Test
    void appConfigUpsertMaintainsSingleRowAndUpdatesValue() {
        appConfigRepository.saveConfig("fake_version", "0.6.4");
        appConfigRepository.saveConfig("fake_version", "0.7.0");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_config WHERE config_key = 'fake_version'", Integer.class);
        assertThat(count).isEqualTo(1);
        assertThat(appConfigRepository.findConfigValue("fake_version")).isEqualTo("0.7.0");
    }
}

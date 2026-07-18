package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ProviderRequestTransformServiceTests {

    private static final String CUSTOM_TRANSFORMS = """
            {
              "custom_headers": [{"key":"api-key","value":"{apiKey}"}],
              "body_transforms": [{"path":"temperature","action":"remove"}]
            }
            """;
    private static final String TEMPLATE_KEYS = "[\"base\",\"tools\"]";
    private static final String PREVIEW = "{\"model\":\"<string>\",\"stream\":true}";
    private static final String RULES = "{\"version\":1,\"rules\":[]}";

    @TempDir
    Path tempDir;

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbcTemplate;
    private ProviderRequestTransformRepository transformRepository;
    private ProviderRequestTransformService service;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("service.db"));

        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
        context.registerBean(PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(ProviderApiKeyRepository.class,
                () -> mock(ProviderApiKeyRepository.class));
        context.registerBean(ProviderConfigRepository.class,
                () -> new ProviderConfigRepository(
                        context.getBean(JdbcTemplate.class), context.getBean(ProviderApiKeyRepository.class)));
        context.registerBean(ProviderRequestTransformRepository.class,
                () -> new ProviderRequestTransformRepository(context.getBean(JdbcTemplate.class)));
        context.registerBean(ProviderRequestTransformService.class,
                () -> new ProviderRequestTransformService(
                        context.getBean(ProviderConfigRepository.class),
                        context.getBean(ProviderRequestTransformRepository.class),
                        context.getBean(ObjectMapper.class)));
        context.refresh();

        jdbcTemplate = context.getBean(JdbcTemplate.class);
        transformRepository = context.getBean(ProviderRequestTransformRepository.class);
        service = context.getBean(ProviderRequestTransformService.class);
        createProviderTables();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void createCustomProviderAtomicallySavesLegacyConfigurationAndNewEditorState() {
        int providerId = service.createCustomProvider(
                "custom-alpha", "https://alpha.example/v1", CUSTOM_TRANSFORMS,
                TEMPLATE_KEYS, PREVIEW, RULES);

        String legacyJson = jdbcTemplate.queryForObject(
                "SELECT custom_transforms FROM provider_config WHERE id = ?", String.class, providerId);
        ProviderRequestTransformRow transform = transformRepository.findByProviderId(providerId);
        assertThat(legacyJson).contains("custom_headers", "body_transforms", "temperature");
        assertThat(transform).isNotNull();
        assertThat(transform.headerRulesJson())
                .isEqualTo("[{\"key\":\"api-key\",\"value\":\"{apiKey}\"}]");
        assertThat(transform.bodyTemplateKeysJson()).isEqualTo(TEMPLATE_KEYS);
        assertThat(transform.bodyPreviewJson()).isEqualTo(PREVIEW);
        assertThat(transform.bodyRulesJson()).isEqualTo(RULES);
    }

    @Test
    void updateCustomProviderKeepsStableIdAndUpdatesBothStorageLocations() {
        int providerId = service.createCustomProvider(
                "custom-alpha", "https://old.example/v1", CUSTOM_TRANSFORMS,
                TEMPLATE_KEYS, PREVIEW, RULES);
        String updatedTransforms = "{\"custom_headers\":[{\"key\":\"x-token\",\"value\":\"new\"}],"
                + "\"body_transforms\":[]}";
        String updatedRules = "{\"version\":1,\"rules\":[{\"id\":\"r1\",\"order\":0,"
                + "\"field\":\"temperature\",\"array\":false,\"conditional\":false,"
                + "\"conditionMode\":\"all\",\"conditions\":[],"
                + "\"operations\":[{\"type\":\"delete\"}]}]}";

        service.updateCustomProvider(
                providerId, "custom-alpha", "custom-renamed", "https://new.example/v1",
                updatedTransforms, "[\"custom\"]", "{\"temperature\":0.2}", updatedRules);

        Integer persistedId = jdbcTemplate.queryForObject(
                "SELECT id FROM provider_config WHERE provider_key = 'custom-renamed'", Integer.class);
        String legacyJson = jdbcTemplate.queryForObject(
                "SELECT custom_transforms FROM provider_config WHERE id = ?", String.class, providerId);
        ProviderRequestTransformRow transform = transformRepository.findByProviderId(providerId);
        assertThat(persistedId).isEqualTo(providerId);
        assertThat(legacyJson).contains("x-token");
        assertThat(transform.headerRulesJson()).contains("x-token");
        assertThat(transform.bodyTemplateKeysJson()).isEqualTo("[\"custom\"]");
        assertThat(transform.bodyPreviewJson()).isEqualTo("{\"temperature\":0.2}");
        assertThat(transform.bodyRulesJson()).isEqualTo(updatedRules);
    }

    @Test
    void createCustomProviderRollsBackLegacyInsertWhenNewStorageWriteFails() {
        jdbcTemplate.execute("DROP TABLE provider_request_transform");

        assertThatThrownBy(() -> service.createCustomProvider(
                "custom-rollback", "https://rollback.example/v1", CUSTOM_TRANSFORMS,
                TEMPLATE_KEYS, PREVIEW, RULES))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_config WHERE provider_key = 'custom-rollback'", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void updateCustomProviderRollsBackLegacyChangesWhenNewStorageWriteFails() {
        int providerId = service.createCustomProvider(
                "custom-alpha", "https://old.example/v1", CUSTOM_TRANSFORMS,
                TEMPLATE_KEYS, PREVIEW, RULES);
        jdbcTemplate.execute("DROP TABLE provider_request_transform");

        assertThatThrownBy(() -> service.updateCustomProvider(
                providerId, "custom-alpha", "custom-renamed", "https://new.example/v1",
                "{\"custom_headers\":[]}", TEMPLATE_KEYS, PREVIEW, RULES))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        Integer oldCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_config WHERE provider_key = 'custom-alpha'", Integer.class);
        Integer renamedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_config WHERE provider_key = 'custom-renamed'", Integer.class);
        String baseUrl = jdbcTemplate.queryForObject(
                "SELECT base_url FROM provider_config WHERE id = ?", String.class, providerId);
        String legacyJson = jdbcTemplate.queryForObject(
                "SELECT custom_transforms FROM provider_config WHERE id = ?", String.class, providerId);
        assertThat(oldCount).isEqualTo(1);
        assertThat(renamedCount).isZero();
        assertThat(baseUrl).isEqualTo("https://old.example/v1");
        assertThat(legacyJson).contains("body_transforms", "temperature");
    }

    @Test
    void invalidEditorConfigurationIsRejectedBeforeAnyDatabaseWrite() {
        assertThatThrownBy(() -> service.createCustomProvider(
                "custom-invalid", "https://invalid.example/v1", CUSTOM_TRANSFORMS,
                "[\"custom\",\"base\"]", "[]", "{\"version\":2,\"rules\":[]}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("custom 模板不能与其他模板同时选择");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_config WHERE provider_key = 'custom-invalid'", Integer.class);
        assertThat(count).isZero();
    }

    private void createProviderTables() {
        jdbcTemplate.execute("CREATE TABLE provider_config ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT NOT NULL UNIQUE, "
                + "enabled INTEGER NOT NULL DEFAULT 0, base_url TEXT NOT NULL DEFAULT '', "
                + "api_key TEXT NOT NULL DEFAULT '[]', active_api_key_index INTEGER NOT NULL DEFAULT 0, "
                + "api_format TEXT NOT NULL DEFAULT 'openai', custom_transforms TEXT NOT NULL DEFAULT '{}', "
                + "updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");
        jdbcTemplate.execute("CREATE TABLE provider_request_transform ("
                + "provider_id INTEGER PRIMARY KEY, header_rules_version INTEGER NOT NULL, "
                + "header_rules_json TEXT NOT NULL CHECK (json_valid(header_rules_json)), "
                + "body_template_keys_json TEXT NOT NULL CHECK (json_valid(body_template_keys_json)), "
                + "body_preview_json TEXT NOT NULL CHECK (json_valid(body_preview_json)), "
                + "body_rules_version INTEGER NOT NULL, "
                + "body_rules_json TEXT NOT NULL CHECK (json_valid(body_rules_json)), "
                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')), "
                + "updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }
}

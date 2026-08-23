package com.kaixuan.copilot_ollama_proxy.infrastructure.persistence;

import com.kaixuan.copilot_ollama_proxy.infrastructure.security.ApiKeyCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryUpsertTests {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private ProviderConfigRepository providerConfigRepository;
        private ProviderApiKeyRepository providerApiKeyRepository;
    private AppConfigRepository appConfigRepository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("repository.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE provider_config ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_key TEXT NOT NULL UNIQUE, "
                + "display_name TEXT NOT NULL DEFAULT '', "
                + "enabled INTEGER NOT NULL DEFAULT 0, base_url TEXT NOT NULL DEFAULT '', "
                + "supported_protocols TEXT NOT NULL DEFAULT '[\"OPENAI\",\"ANTHROPIC\"]' "
                + "CHECK (json_valid(supported_protocols)), "
                + "anthropic_base_url TEXT NOT NULL DEFAULT '', "
                + "updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");
        jdbcTemplate.execute("CREATE TABLE provider_model ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, provider_id INTEGER NOT NULL, model_name TEXT NOT NULL, "
                + "enabled INTEGER NOT NULL DEFAULT 1, context_size INTEGER NOT NULL DEFAULT 0, "
                + "max_output_tokens INTEGER NOT NULL DEFAULT 128000, caps_tools INTEGER NOT NULL DEFAULT 0, "
                + "caps_vision INTEGER NOT NULL DEFAULT 0, reasoning_effort TEXT NOT NULL DEFAULT 'Medium', "
                + "sort_order INTEGER NOT NULL DEFAULT 0)");
        jdbcTemplate.execute("CREATE TABLE provider_api_key ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, key_uuid TEXT NOT NULL UNIQUE, provider_id INTEGER NOT NULL, "
                + "key_name TEXT NOT NULL DEFAULT '', encrypted_api_key TEXT NOT NULL, nonce TEXT NOT NULL, "
                + "encryption_version INTEGER NOT NULL DEFAULT 1, is_active INTEGER NOT NULL DEFAULT 0, "
                + "sort_order INTEGER NOT NULL DEFAULT 0, "
                + "created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')), "
                + "updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')), "
                + "UNIQUE (provider_id, key_name))");
        jdbcTemplate.execute("CREATE UNIQUE INDEX ux_provider_api_key_active "
                + "ON provider_api_key(provider_id) WHERE is_active = 1");
        jdbcTemplate.execute("CREATE TABLE app_config ("
                + "config_key TEXT PRIMARY KEY, config_value TEXT NOT NULL DEFAULT '', "
                + "updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))) ");
        ApiKeyCryptoService cryptoService = new ApiKeyCryptoService("test-master-key");
        ReflectionTestUtils.invokeMethod(cryptoService, "initialize");
        providerApiKeyRepository = new ProviderApiKeyRepository(jdbcTemplate, cryptoService);
        providerConfigRepository = new ProviderConfigRepository(jdbcTemplate, providerApiKeyRepository);
        appConfigRepository = new AppConfigRepository(jdbcTemplate);
    }

    @Test
    void providerUpsertUpdatesExistingRowWithoutChangingItsId() {
        int firstId = providerConfigRepository.saveProvider(
                "mimo", false, "https://old.example");
        int secondId = providerConfigRepository.saveProvider(
                "mimo", true, "https://new.example");

        assertThat(secondId).isEqualTo(firstId);
        assertThat(providerConfigRepository.findAllWithModels()).hasSize(1);
        ProviderConfigRow row = providerConfigRepository.findByKey("mimo");
        assertThat(row.enabled()).isTrue();
        assertThat(row.baseUrl()).isEqualTo("https://new.example");
        assertThat(row.displayName()).isEqualTo("Mimo");
    }

        @Test
        void providerUpsertPersistsExactDisplayNameAndNormalConfigUpdateKeepsIt() {
                providerConfigRepository.saveProvider("stepfun", "StepFun", true, "https://old.example");
                providerConfigRepository.updateProviderConfig("stepfun", "https://new.example");

                ProviderConfigRow row = providerConfigRepository.findByKey("stepfun");
                assertThat(row.displayName()).isEqualTo("StepFun");
                assertThat(row.baseUrl()).isEqualTo("https://new.example");
        }

    @Test
        void partialProviderConfigUpsertPreservesEnabledState() {
        providerConfigRepository.saveProvider(
                                "mimo", true, "https://old.example");

        int providerId = providerConfigRepository.updateProviderConfig(
                "mimo", "https://new.example");

        ProviderConfigRow row = providerConfigRepository.findByKey("mimo");
        assertThat(row.id()).isEqualTo(providerId);
        assertThat(row.enabled()).isTrue();
        assertThat(row.baseUrl()).isEqualTo("https://new.example");
    }

    /** 协议配置写入按字段独立生效，未提供的那个保持原值。 */
    @Test
    void protocolUpdateWritesOnlyTheFieldsThatWereProvided() {
        providerConfigRepository.saveProvider("mimo", true, "https://api.example");
        providerConfigRepository.updateProviderProtocols("mimo", "[\"OPENAI\"]", "https://ant.example");

        providerConfigRepository.updateProviderProtocols("mimo", null, "https://ant2.example");
        ProviderConfigRow afterUrlOnly = providerConfigRepository.findByKey("mimo");
        assertThat(afterUrlOnly.supportedProtocolsJson()).isEqualTo("[\"OPENAI\"]");
        assertThat(afterUrlOnly.anthropicBaseUrl()).isEqualTo("https://ant2.example");

        providerConfigRepository.updateProviderProtocols("mimo", "[\"OPENAI\",\"ANTHROPIC\"]", null);
        ProviderConfigRow afterProtocolsOnly = providerConfigRepository.findByKey("mimo");
        assertThat(afterProtocolsOnly.supportedProtocolsJson()).isEqualTo("[\"OPENAI\",\"ANTHROPIC\"]");
        assertThat(afterProtocolsOnly.anthropicBaseUrl()).isEqualTo("https://ant2.example");
    }

    /**
     * 普通的供应商配置保存不得清空协议配置。
     *
     * <p>管理后台前端目前不提交这两个字段，若保存路径把「未提供」当成「清空」，
     * 一次无关的编辑就会把协议支持抹成空集，而空集会让该供应商的全部调用被拒 ——
     * 故障现象与操作动作之间毫无联系，最难排查。
     */
    @Test
    void normalConfigSaveDoesNotResetProtocolConfiguration() {
        providerConfigRepository.saveProvider("mimo", true, "https://api.example");
        providerConfigRepository.updateProviderProtocols("mimo", "[\"ANTHROPIC\"]", "https://ant.example");

        providerConfigRepository.updateProviderConfig("mimo", "https://new.example");
        providerConfigRepository.updateProviderProtocols("mimo", null, null);

        ProviderConfigRow row = providerConfigRepository.findByKey("mimo");
        assertThat(row.baseUrl()).isEqualTo("https://new.example");
        assertThat(row.supportedProtocolsJson()).isEqualTo("[\"ANTHROPIC\"]");
        assertThat(row.anthropicBaseUrl()).isEqualTo("https://ant.example");
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

    @Test
    void savingKeysCanSwitchActiveKeyWithoutViolatingPartialUniqueIndex() {
        int providerId = providerConfigRepository.saveProvider("mimo", true, "https://api.example");
        providerApiKeyRepository.saveKeys(providerId, List.of(
                new ProviderApiKeyRepository.ApiKeyInput(null, "first", "sk-first", true),
                new ProviderApiKeyRepository.ApiKeyInput(null, "second", "sk-second", false)));

        List<ProviderApiKeyRow> initial = providerApiKeyRepository.findByProviderId(providerId);
        ProviderApiKeyRow first = initial.stream()
                .filter(key -> "first".equals(key.keyName()))
                .findFirst()
                .orElseThrow();
        ProviderApiKeyRow second = initial.stream()
                .filter(key -> "second".equals(key.keyName()))
                .findFirst()
                .orElseThrow();

        providerApiKeyRepository.saveKeys(providerId, List.of(
                new ProviderApiKeyRepository.ApiKeyInput(first.keyUuid(), "first", null, false),
                new ProviderApiKeyRepository.ApiKeyInput(second.keyUuid(), "second", null, true)));

        List<ProviderApiKeyRow> saved = providerApiKeyRepository.findByProviderId(providerId);
        assertThat(saved).filteredOn(ProviderApiKeyRow::active)
                .singleElement()
                .extracting(ProviderApiKeyRow::keyUuid)
                .isEqualTo(second.keyUuid());
    }
}

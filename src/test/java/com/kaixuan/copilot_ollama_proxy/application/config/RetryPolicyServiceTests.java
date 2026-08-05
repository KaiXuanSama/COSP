package com.kaixuan.copilot_ollama_proxy.application.config;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.AppConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 重试次数配置的读写与边界。
 *
 * <p>覆盖三档语义（正数 / 0 / -1）、非法值的容错回退，以及 Reactor 值翻译。
 * 用 {@code @TempDir} 建临时 SQLite，绝不碰根目录 admin.db。
 */
class RetryPolicyServiceTests {

    @TempDir
    Path tempDir;

    private RetryPolicyService service;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("retry-policy.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE app_config ("
                + "config_key TEXT PRIMARY KEY, config_value TEXT, updated_at TEXT)");
        service = new RetryPolicyService(new AppConfigRepository(jdbcTemplate));
    }

    /** 从未配置过：返回默认 5，保证升级后行为与引入本功能前一致。 */
    @Test
    void missingConfigFallsBackToDefault() {
        assertThat(service.getMaxAttempts()).isEqualTo(RetryPolicyService.DEFAULT_MAX_ATTEMPTS);
    }

    /** 正数：按配置值生效。 */
    @Test
    void positiveValueIsPersistedAndRead() {
        service.saveMaxAttempts(3);
        assertThat(service.getMaxAttempts()).isEqualTo(3);
    }

    /** 0：合法值，表示完全不重试 —— 不能被当成「空值」而回退默认。 */
    @Test
    void zeroMeansNoRetryAndIsNotTreatedAsUnset() {
        service.saveMaxAttempts(0);
        assertThat(service.getMaxAttempts()).isZero();
    }

    /** -1：无限重试，翻译到 Reactor 是 Long.MAX_VALUE。 */
    @Test
    void minusOneMeansUnlimited() {
        service.saveMaxAttempts(RetryPolicyService.UNLIMITED_MAX_ATTEMPTS);

        assertThat(service.getMaxAttempts()).isEqualTo(RetryPolicyService.UNLIMITED_MAX_ATTEMPTS);
        assertThat(RetryPolicyService.toReactorMaxAttempts(RetryPolicyService.UNLIMITED_MAX_ATTEMPTS))
                .isEqualTo(Long.MAX_VALUE);
    }

    /** 有限值原样翻译，不做偏移 —— Reactor 的 maxAttempts 与本配置口径一致（都不含首次）。 */
    @Test
    void finiteValueTranslatesUnchanged() {
        assertThat(RetryPolicyService.toReactorMaxAttempts(0)).isZero();
        assertThat(RetryPolicyService.toReactorMaxAttempts(7)).isEqualTo(7L);
    }

    /** 越界写入被拒绝，且不落库。 */
    @Test
    void outOfRangeValuesAreRejected() {
        assertThatThrownBy(() -> service.saveMaxAttempts(-2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveMaxAttempts(RetryPolicyService.MAX_CONFIGURABLE_ATTEMPTS + 1))
                .isInstanceOf(IllegalArgumentException.class);

        // 两次拒绝都没写库，读回来仍是默认值。
        assertThat(service.getMaxAttempts()).isEqualTo(RetryPolicyService.DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * 库里被人手改成非数字：回退默认值而不是抛异常。
     *
     * <p>配置读取发生在每次上游调用的组装期，抛异常会直接打断用户的聊天请求 ——
     * 一个脏配置值不该有这种杀伤力。
     */
    @Test
    void malformedStoredValueFallsBackToDefault() {
        jdbcTemplate.update("INSERT INTO app_config (config_key, config_value) VALUES (?, ?)",
                RetryPolicyService.RETRY_MAX_ATTEMPTS_KEY, "abc");

        assertThat(service.getMaxAttempts()).isEqualTo(RetryPolicyService.DEFAULT_MAX_ATTEMPTS);
    }

    /** 库里存了越界数字（绕过写接口直接改库）：同样夹回默认值。 */
    @Test
    void outOfRangeStoredValueFallsBackToDefault() {
        jdbcTemplate.update("INSERT INTO app_config (config_key, config_value) VALUES (?, ?)",
                RetryPolicyService.RETRY_MAX_ATTEMPTS_KEY, "999999");

        assertThat(service.getMaxAttempts()).isEqualTo(RetryPolicyService.DEFAULT_MAX_ATTEMPTS);
    }

    /** 重复保存走 UPSERT，不会因主键冲突失败。 */
    @Test
    void repeatedSaveOverwritesPreviousValue() {
        service.saveMaxAttempts(2);
        service.saveMaxAttempts(8);

        assertThat(service.getMaxAttempts()).isEqualTo(8);
    }
}

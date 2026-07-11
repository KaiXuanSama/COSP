package com.kaixuan.copilot_ollama_proxy.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyCryptoServiceTests {

    private ApiKeyCryptoService newInitialized(String masterKey) {
        ApiKeyCryptoService service = new ApiKeyCryptoService(masterKey);
        ReflectionTestUtils.invokeMethod(service, "initialize");
        return service;
    }

    @Test
    void encryptThenDecryptReturnsOriginalPlaintext() {
        ApiKeyCryptoService service = newInitialized("my-secret");
        ApiKeyCryptoService.EncryptedValue encrypted = service.encrypt("sk-abcdef123456");

        assertThat(encrypted.nonceBase64()).isNotBlank();
        assertThat(encrypted.ciphertextBase64()).isNotBlank();
        assertThat(service.decrypt(encrypted.nonceBase64(), encrypted.ciphertextBase64()))
                .isEqualTo("sk-abcdef123456");
    }

    @Test
    void eachEncryptionUsesDifferentNonceForSamePlaintext() {
        ApiKeyCryptoService service = newInitialized("my-secret");
        ApiKeyCryptoService.EncryptedValue first = service.encrypt("same-key");
        ApiKeyCryptoService.EncryptedValue second = service.encrypt("same-key");

        assertThat(first.nonceBase64()).isNotEqualTo(second.nonceBase64());
        assertThat(first.ciphertextBase64()).isNotEqualTo(second.ciphertextBase64());
    }

    @Test
    void fingerprintIsStableForSameKeyAndDiffersForDifferentKeys() {
        ApiKeyCryptoService a1 = newInitialized("key-a");
        ApiKeyCryptoService a2 = newInitialized("key-a");
        ApiKeyCryptoService b = newInitialized("key-b");

        assertThat(a1.fingerprint()).isEqualTo(a2.fingerprint());
        assertThat(a1.fingerprint()).isNotEqualTo(b.fingerprint());
    }

    @Test
    void decryptWithDifferentKeyFails() {
        ApiKeyCryptoService encryptService = newInitialized("key-a");
        ApiKeyCryptoService.EncryptedValue encrypted = encryptService.encrypt("secret");
        ApiKeyCryptoService decryptService = newInitialized("key-b");

        assertThatThrownBy(() -> decryptService.decrypt(encrypted.nonceBase64(), encrypted.ciphertextBase64()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankMasterKeyRejectsStartup() {
        ApiKeyCryptoService service = new ApiKeyCryptoService("   ");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "initialize"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tooLongMasterKeyRejectsStartup() {
        String longKey = "x".repeat(65);
        ApiKeyCryptoService service = new ApiKeyCryptoService(longKey);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "initialize"))
                .isInstanceOf(IllegalStateException.class);
    }
}

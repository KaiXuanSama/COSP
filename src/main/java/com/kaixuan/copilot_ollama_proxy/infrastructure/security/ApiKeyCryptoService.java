package com.kaixuan.copilot_ollama_proxy.infrastructure.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * API Key 对称加密服务 — 使用 AES-256-GCM 加密供应商 API Key。
 *
 * 主密钥来自环境变量 COSP_MASTER_KEY，允许任意 1 到 64 个字符的字符串。
 * 通过 SHA-256 派生为固定 32 字节的 AES-256 密钥，因此对输入长度不敏感。
 * 未配置主密钥时构造即失败，从而阻止服务启动，确保加密不会被静默绕过。
 *
 * 每次加密生成独立的 12 字节随机 nonce，使用 128 位认证标签。
 * 密文与 nonce 均以 Base64 存储，密文被篡改时解密会直接失败。
 *
 * 该服务是加解密的唯一入口，便于未来实现密钥轮换。
 */
@Service
public class ApiKeyCryptoService {

    private static final int MAX_MASTER_KEY_LENGTH = 64;
    private static final int NONCE_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** 当前加密格式版本，写入 provider_api_key.encryption_version。 */
    public static final int ENCRYPTION_VERSION = 1;

    private final String masterKeyRaw;
    private final SecureRandom secureRandom = new SecureRandom();

    private SecretKeySpec secretKey;
    private String fingerprint;

    /**
     * 构造加密服务。
     *
     * @param masterKeyRaw 环境变量 COSP_MASTER_KEY 提供的主密钥
     */
    public ApiKeyCryptoService(@Value("${COSP_MASTER_KEY:}") String masterKeyRaw) {
        this.masterKeyRaw = masterKeyRaw;
    }

    /**
     * 校验主密钥并派生 AES 密钥与指纹。
     *
     * 主密钥为空、全空白或超过 64 个字符时抛出异常，阻止服务启动。
     */
    @PostConstruct
    void initialize() {
        if (masterKeyRaw == null || masterKeyRaw.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 COSP_MASTER_KEY。该变量用于加密供应商 API Key，"
                            + "服务无法在未配置主密钥时启动。请设置一个 1 到 64 个字符的密钥并妥善保管，"
                            + "丢失后已加密的 API Key 将无法恢复。");
        }
        String trimmed = masterKeyRaw.trim();
        if (trimmed.length() > MAX_MASTER_KEY_LENGTH) {
            throw new IllegalStateException(
                    "环境变量 COSP_MASTER_KEY 过长，最多允许 " + MAX_MASTER_KEY_LENGTH + " 个字符。");
        }
        byte[] derived = sha256(trimmed.getBytes(StandardCharsets.UTF_8));
        this.secretKey = new SecretKeySpec(derived, "AES");
        this.fingerprint = HexFormat.of().formatHex(derived);
    }

    /**
     * 加密明文 API Key。
     *
     * @param plaintext 明文 API Key
     * @return 加密结果，包含 Base64 nonce 与 Base64 密文
     */
    public EncryptedValue encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getEncoder();
            return new EncryptedValue(encoder.encodeToString(nonce), encoder.encodeToString(ciphertext));
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    /**
     * 解密密文 API Key。
     *
     * @param nonceBase64 Base64 编码的 nonce
     * @param ciphertextBase64 Base64 编码的密文
     * @return 明文 API Key
     */
    public String decrypt(String nonceBase64, String ciphertextBase64) {
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] nonce = decoder.decode(nonceBase64);
            byte[] ciphertext = decoder.decode(ciphertextBase64);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 解密失败，可能是主密钥不匹配或密文已损坏", e);
        }
    }

    /**
     * 返回主密钥的十六进制指纹，用于校验数据库与当前密钥是否一致。
     *
     * 指纹是主密钥的 SHA-256，不可逆，不会暴露主密钥本身。
     *
     * @return 主密钥指纹（64 位十六进制字符串）
     */
    public String fingerprint() {
        return fingerprint;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 加密结果 — 包含 Base64 编码的 nonce 与密文。
     *
     * @param nonceBase64 Base64 编码的随机 nonce
     * @param ciphertextBase64 Base64 编码的密文
     */
    public record EncryptedValue(String nonceBase64, String ciphertextBase64) {
    }
}

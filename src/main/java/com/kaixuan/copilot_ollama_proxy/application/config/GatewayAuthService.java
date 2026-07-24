package com.kaixuan.copilot_ollama_proxy.application.config;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.AppConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.security.ApiKeyCryptoService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.security.ApiKeyCryptoService.EncryptedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 下游鉴权（网关 API Key）管理用例。
 *
 * <p>面向「部署到服务器供自己使用」场景的防滥用设计：为对外的聊天接口设置一把
 * 网关级 API Key。本服务只负责 Key 与开关的持久化管理，<strong>不负责</strong>
 * 实际的请求拦截鉴权（那是独立的 WebFilter，后续实现）。
 *
 * <p>存储复用现有 {@code app_config} 表，不新增表、不涉及 schema 版本增长：
 * <ul>
 *   <li>{@code gateway_auth_enabled} —— {@code "true"}/{@code "false"}，功能开关；</li>
 *   <li>{@code gateway_api_key} —— {@code nonce:ciphertext}，AES-256-GCM 加密后的 Key。</li>
 * </ul>
 *
 * <p>加密值由 {@link ApiKeyCryptoService} 产出的 nonce 与密文两段用冒号拼接存入单列
 * （Base64 不含冒号，可安全还原）。明文永不落库，也不随列表接口返回；仅在管理员
 * 显式调用 reveal / regenerate 时解密回传，且这些接口本身受管理后台 JWT 保护。
 */
@Service
public class GatewayAuthService {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthService.class);

    /** 功能开关配置键。 */
    private static final String ENABLED_KEY = "gateway_auth_enabled";
    /** 加密 API Key 配置键，值格式为 {@code nonce:ciphertext}。 */
    private static final String API_KEY_KEY = "gateway_api_key";
    /** 生成的 Key 前缀，便于识别来源。 */
    private static final String KEY_PREFIX = "cosp-";
    /** 随机字节长度，决定 Key 熵值（24 字节 → 32 个 Base64URL 字符）。 */
    private static final int RANDOM_BYTES = 24;
    /** Authorization 头的 Bearer 前缀。 */
    private static final String BEARER_PREFIX = "Bearer ";

    private final AppConfigRepository appConfigRepository;
    private final ApiKeyCryptoService cryptoService;
    private final SecureRandom secureRandom = new SecureRandom();

    public GatewayAuthService(AppConfigRepository appConfigRepository, ApiKeyCryptoService cryptoService) {
        this.appConfigRepository = appConfigRepository;
        this.cryptoService = cryptoService;
    }

    /**
     * 读取当前下游鉴权状态。
     *
     * @return 开关状态、脱敏 Key 与是否已配置 Key
     */
    public Mono<GatewayAuthStatus> getStatus() {
        return Mono.fromCallable(() -> {
            boolean enabled = "true".equals(appConfigRepository.findConfigValue(ENABLED_KEY));
            String plaintext = decryptStoredKey();
            String masked = plaintext == null ? "" : maskKey(plaintext);
            return new GatewayAuthStatus(enabled, masked, plaintext != null);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 切换功能开关。
     *
     * @param enabled 是否开启
     * @return 完成信号
     */
    public Mono<Void> setEnabled(boolean enabled) {
        return Mono.fromRunnable(() ->
                        appConfigRepository.saveConfig(ENABLED_KEY, Boolean.toString(enabled)))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 读取解密后的明文 Key（供复制到剪贴板）。
     *
     * @return 明文 Key；未配置时为 {@code null}
     */
    public Mono<String> revealKey() {
        return Mono.fromCallable(this::decryptStoredKey)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 对下游聊天请求做鉴权决策。
     *
     * <p>决策规则（对应方案 1A：空 Key 放行，避免自锁死）：
     * <ul>
     *   <li>功能未开启 → {@link AuthDecision#PASS}（仅一次廉价查询，不解密）；</li>
     *   <li>已开启但未配置 Key → {@code PASS}，并打 warning 日志提示配置不完整；</li>
     *   <li>已开启且已配置 Key → 用<strong>常量时间</strong>比对请求头中的 Bearer token，
     *       匹配则 {@code PASS}，否则 {@link AuthDecision#UNAUTHORIZED}。</li>
     * </ul>
     *
     * <p>每次请求实时读库，因此刷新 Key 立即生效，无需缓存失效逻辑；读库为阻塞 JDBC，
     * 调度到 {@code boundedElastic} 执行，不阻塞 event-loop。
     *
     * @param authorizationHeader 请求头 {@code Authorization} 的原始值（可能为 {@code null}）
     * @return 鉴权决策
     */
    public Mono<AuthDecision> authorize(String authorizationHeader) {
        return Mono.fromCallable(() -> {
            boolean enabled = "true".equals(appConfigRepository.findConfigValue(ENABLED_KEY));
            if (!enabled) {
                return AuthDecision.PASS;
            }
            String expected = decryptStoredKey();
            if (expected == null) {
                log.warn("下游鉴权已开启但未配置 API Key，本次请求按放行处理。请在管理后台生成 Key 或关闭开关。");
                return AuthDecision.PASS;
            }
            String presented = extractBearerToken(authorizationHeader);
            if (presented == null) {
                return AuthDecision.UNAUTHORIZED;
            }
            boolean match = MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    presented.getBytes(StandardCharsets.UTF_8));
            return match ? AuthDecision.PASS : AuthDecision.UNAUTHORIZED;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 从 {@code Authorization} 头提取 Bearer token。
     *
     * @param header 原始头值
     * @return token；缺失或格式不符时为 {@code null}
     */
    private String extractBearerToken(String header) {
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.length() <= BEARER_PREFIX.length()
                || !trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = trimmed.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 生成一把新的随机 Key，加密落库并返回明文与脱敏值（一次性显示 + 复制）。
     *
     * <p>只覆盖 Key 本身，不改动开关状态。
     *
     * @return 新的明文 Key 与其脱敏形式
     */
    public Mono<GeneratedKey> regenerate() {
        return Mono.fromCallable(() -> {
            String plaintext = generateKey();
            EncryptedValue encrypted = cryptoService.encrypt(plaintext);
            appConfigRepository.saveConfig(API_KEY_KEY,
                    encrypted.nonceBase64() + ":" + encrypted.ciphertextBase64());
            return new GeneratedKey(plaintext, maskKey(plaintext));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 解密已存储的 Key。
     *
     * @return 明文 Key；未配置或格式异常时为 {@code null}
     */
    private String decryptStoredKey() {
        String stored = appConfigRepository.findConfigValue(API_KEY_KEY);
        if (stored == null || stored.isBlank()) {
            return null;
        }
        int sep = stored.indexOf(':');
        if (sep < 0) {
            return null;
        }
        String nonce = stored.substring(0, sep);
        String ciphertext = stored.substring(sep + 1);
        return cryptoService.decrypt(nonce, ciphertext);
    }

    /** 生成带前缀的随机 Key。 */
    private String generateKey() {
        byte[] raw = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(raw);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * 脱敏展示：保留前 6 位与后 4 位，中间以星号替代。
     *
     * <p>与 {@code ProviderAdminService.maskApiKey} 规则保持一致。
     */
    private String maskKey(String key) {
        String trimmed = key.trim();
        return trimmed.length() <= 10
                ? "****"
                : trimmed.substring(0, 6) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    /**
     * 下游鉴权状态视图。
     *
     * @param enabled    功能是否开启
     * @param maskedKey  脱敏后的 Key（未配置时为空串）
     * @param configured 是否已配置 Key
     */
    public record GatewayAuthStatus(boolean enabled, String maskedKey, boolean configured) {
    }

    /**
     * 新生成的 Key 视图。
     *
     * @param apiKey    明文 Key（一次性返回）
     * @param maskedKey 脱敏后的 Key
     */
    public record GeneratedKey(String apiKey, String maskedKey) {
    }

    /** 下游鉴权决策结果。 */
    public enum AuthDecision {
        /** 放行，交由后续处理链继续。 */
        PASS,
        /** 拒绝，返回 401。 */
        UNAUTHORIZED
    }
}

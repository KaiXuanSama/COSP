package com.kaixuan.copilot_ollama_proxy.application.config;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.AppConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 上游重试次数配置 —— 从 {@code app_config} 读取用户设定的自动重试预算。
 *
 * <h2>取值语义</h2>
 * <ul>
 *   <li><strong>正数 N</strong> —— 首次请求之外再试 N 次（与 Reactor {@code maxAttempts} 口径一致）；</li>
 *   <li><strong>0</strong> —— 完全不重试，首次失败即透传给下游；</li>
 *   <li><strong>-1</strong> —— 无限重试。落到 Reactor 上是 {@code Long.MAX_VALUE}，
 *       实践中等同无限（按最短 2 秒退避也要跑上亿年）。</li>
 * </ul>
 * 缺省值为 {@link #DEFAULT_MAX_ATTEMPTS}，即历史行为，未配置过的部署升级后行为不变。
 *
 * <h2>为何每次现读而不缓存</h2>
 * 读的是一行 SQLite 主键查询，成本远低于一次上游 HTTP 往返；换来的是<strong>改配置立即生效</strong>，
 * 不必重启服务，也省掉一套缓存失效逻辑。调用点在
 * {@code AbstractUpstreamChatService#buildRetrySpec}，每次上游调用只读一次。
 *
 * <h2>无限重试的风险</h2>
 * {@code -1} 只对<strong>可重试</strong>的失败无限重试 —— 判定白名单
 * （{@code isRetryableFailure}）依旧生效，401/403 这类确定性错误仍然立即失败。
 * 但 429 / 5xx / 空响应若上游长期不恢复，该请求会一直挂着直到下游断连。
 * 这是用户显式选择的取舍，前端需给出相应提示。
 */
@Service
public class RetryPolicyService {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicyService.class);

    /** 重试次数配置在 {@code app_config} 中的键名。 */
    public static final String RETRY_MAX_ATTEMPTS_KEY = "retry_max_attempts";

    /** 默认重试次数：首次之外再试 5 次。与本功能引入前的硬编码值一致。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    /** 表示无限重试的配置值。 */
    public static final int UNLIMITED_MAX_ATTEMPTS = -1;

    /**
     * 允许配置的上限。纯粹是防误输入的护栏（比如手滑输成 100000），
     * 真要无限请用 {@link #UNLIMITED_MAX_ATTEMPTS}，语义比一个巨大的有限值清晰。
     */
    public static final int MAX_CONFIGURABLE_ATTEMPTS = 100;

    private final AppConfigRepository appConfigRepository;

    public RetryPolicyService(AppConfigRepository appConfigRepository) {
        this.appConfigRepository = appConfigRepository;
    }

    /**
     * 读取当前配置的重试次数（原始语义值，{@code -1} 表示无限）。
     *
     * <p>本方法会阻塞（JDBC），调用方若在响应式链中须自行切到 {@code boundedElastic}。
     * 目前唯一调用点是 {@code buildRetrySpec}，它运行在组装期而非订阅期，故直接调用。
     *
     * @return 配置值；未配置、格式非法或超出范围时返回 {@link #DEFAULT_MAX_ATTEMPTS}
     */
    public int getMaxAttempts() {
        String raw;
        try {
            raw = appConfigRepository.findConfigValue(RETRY_MAX_ATTEMPTS_KEY);
        } catch (Exception exception) {
            // 读配置失败绝不能拖垮正在进行的调用：退回默认值，让请求照常走。
            log.warn("读取重试次数配置失败，回退默认值 {}: {}", DEFAULT_MAX_ATTEMPTS, exception.getMessage());
            return DEFAULT_MAX_ATTEMPTS;
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_ATTEMPTS;
        }
        try {
            return normalize(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException exception) {
            log.warn("重试次数配置值非法（{}），回退默认值 {}", raw, DEFAULT_MAX_ATTEMPTS);
            return DEFAULT_MAX_ATTEMPTS;
        }
    }

    /**
     * 保存重试次数配置。
     *
     * @param maxAttempts 目标值；{@code -1} 无限，{@code 0} 不重试，正数为具体次数
     * @throws IllegalArgumentException 值不在 {@code [-1, MAX_CONFIGURABLE_ATTEMPTS]} 内
     */
    public void saveMaxAttempts(int maxAttempts) {
        if (maxAttempts < UNLIMITED_MAX_ATTEMPTS || maxAttempts > MAX_CONFIGURABLE_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "重试次数必须在 " + UNLIMITED_MAX_ATTEMPTS + " 到 " + MAX_CONFIGURABLE_ATTEMPTS
                            + " 之间（-1 表示无限重试，0 表示不重试）");
        }
        appConfigRepository.saveConfig(RETRY_MAX_ATTEMPTS_KEY, String.valueOf(maxAttempts));
        log.info("重试次数配置已更新为 {}", maxAttempts == UNLIMITED_MAX_ATTEMPTS ? "无限" : maxAttempts);
    }

    /**
     * 把配置的语义值翻译成 Reactor {@code Retry.backoff} 需要的 {@code maxAttempts}。
     *
     * <p>{@code -1} → {@code Long.MAX_VALUE}：Reactor 没有「无限 + 指数退避」的直接表达
     * （{@code Retry.indefinitely()} 返回的 {@code RetrySpec} 挂不上 {@code maxBackoff}），
     * 用 {@code Long.MAX_VALUE} 是唯一可行且行为已验证的路径。
     *
     * @param configured 配置的语义值
     * @return Reactor 可直接使用的重试上限
     */
    public static long toReactorMaxAttempts(int configured) {
        return configured == UNLIMITED_MAX_ATTEMPTS ? Long.MAX_VALUE : configured;
    }

    /** 把越界值夹回合法区间；非法配置不应让调用直接失败，一律退回默认值。 */
    private static int normalize(int value) {
        if (value == UNLIMITED_MAX_ATTEMPTS) {
            return UNLIMITED_MAX_ATTEMPTS;
        }
        if (value < 0 || value > MAX_CONFIGURABLE_ATTEMPTS) {
            log.warn("重试次数配置值 {} 超出允许范围，回退默认值 {}", value, DEFAULT_MAX_ATTEMPTS);
            return DEFAULT_MAX_ATTEMPTS;
        }
        return value;
    }
}

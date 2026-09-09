package com.kaixuan.copilot_ollama_proxy.application.config;

import com.kaixuan.copilot_ollama_proxy.application.config.GatewayAuthService.GatewayAuthStatus;
import com.kaixuan.copilot_ollama_proxy.infrastructure.config.OutboundProxyDecider;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.AppConfigRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 管理后台可编辑的运行时配置用例。
 *
 * <p>提供一个聚合读接口 {@link #getRuntimeConfig()}，把散落在 {@code app_config} 中
 * 需要在设置页显示的各项配置整理成结构化 DTO 一次性返回，未来新增配置只需在 DTO 里加字段，
 * 无需再为每项单独写 GET。
 *
 * <p><strong>安全要点</strong>：聚合接口不透传全表，每个字段显式声明并按需加工。
 * 敏感值（如网关 API Key 密文）永不进入本接口——只输出脱敏形式与是否已配置的布尔位，
 * 明文仍仅通过 {@link GatewayAuthService} 的 reveal / regenerate 专项接口按需解密回传。
 * 写接口（saveFakeVersion / toggle / regenerate）保持各自独立，本服务只负责聚合「读」。
 */
@Service
public class RuntimeConfigService {

    private static final String FAKE_VERSION_KEY = "fake_version";

    /** 出站代理地址（{@code host:port}）在 app_config 里的键。空值表示没有代理。 */
    static final String UPSTREAM_PROXY_ADDRESS_KEY = "upstream_proxy_address";

    private final AppConfigRepository appConfigRepository;
    private final GatewayAuthService gatewayAuthService;
    private final RetryPolicyService retryPolicyService;
    private final OutboundProxyDecider proxyDecider;

    public RuntimeConfigService(AppConfigRepository appConfigRepository,
                                GatewayAuthService gatewayAuthService,
                                RetryPolicyService retryPolicyService,
                                OutboundProxyDecider proxyDecider) {
        this.appConfigRepository = appConfigRepository;
        this.gatewayAuthService = gatewayAuthService;
        this.retryPolicyService = retryPolicyService;
        this.proxyDecider = proxyDecider;
    }

    /**
     * 聚合读取设置页所需的全部运行时配置。
     *
     * @return 结构化配置 DTO（伪造版本号 + 下游鉴权状态 + 重试策略）
     */
    public Mono<RuntimeConfigView> getRuntimeConfig() {
        Mono<String> fakeVersionMono = Mono.fromCallable(() -> {
            String value = appConfigRepository.findConfigValue(FAKE_VERSION_KEY);
            return value == null ? "" : value;
        }).subscribeOn(Schedulers.boundedElastic());

        Mono<Integer> retryMaxAttemptsMono = Mono.fromCallable(retryPolicyService::getMaxAttempts)
                .subscribeOn(Schedulers.boundedElastic());

        // 代理地址读内存里的当前值，不回查 DB：decider 是它的运行时真源（启动时已从 app_config 灌入），
        // 保存路径也会同步更新它。回查 DB 只会多一次 IO，还可能与内存值短暂不一致。
        Mono<String> proxyAddressMono = Mono.fromCallable(proxyDecider::currentProxyAddress)
                .subscribeOn(Schedulers.boundedElastic());

        return Mono.zip(fakeVersionMono, gatewayAuthService.getStatus(), retryMaxAttemptsMono, proxyAddressMono)
                .map(tuple -> new RuntimeConfigView(tuple.getT1(), tuple.getT2(),
                        new RetryPolicyView(tuple.getT3(),
                                RetryPolicyService.DEFAULT_MAX_ATTEMPTS,
                                RetryPolicyService.MAX_CONFIGURABLE_ATTEMPTS),
                        tuple.getT4()));
    }

    public Mono<Void> saveFakeVersion(String version) {
        String normalizedVersion = version == null ? "" : version.trim();
        return Mono.fromRunnable(() -> appConfigRepository.saveConfig(FAKE_VERSION_KEY, normalizedVersion))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 保存上游重试次数。
     *
     * @param maxAttempts {@code -1} 无限重试，{@code 0} 不重试，正数为具体次数
     * @return 完成信号；值非法时以 {@link IllegalArgumentException} 终止
     */
    public Mono<Void> saveRetryMaxAttempts(int maxAttempts) {
        return Mono.<Void>fromRunnable(() -> retryPolicyService.saveMaxAttempts(maxAttempts))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 保存出站代理地址，并同步更新决策中心的内存值。
     *
     * <p>先落库再更内存：万一落库失败，内存不会先于持久化改动，重启后不会出现
     * 「界面显示旧值但实际用的是没存下的新值」。归一化只做 trim，不校验 {@code host:port}
     * 形态 —— 这是面向个人使用的服务，地址填错时表现为该目标连不上，用户自己就能发现，
     * 不值得为此加一层可能误拒合法写法（IPv6、带认证的代理 URL 等）的格式校验。
     *
     * @param address 形如 {@code host:port} 的地址；空表示清空 = 没有代理
     * @return 完成信号
     */
    public Mono<Void> saveProxyAddress(String address) {
        String normalized = address == null ? "" : address.trim();
        return Mono.<Void>fromRunnable(() -> {
            appConfigRepository.saveConfig(UPSTREAM_PROXY_ADDRESS_KEY, normalized);
            proxyDecider.updateProxyAddress(normalized);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 从 app_config 读出代理地址并灌入决策中心（启动时调用一次）。
     *
     * <p>由 {@code ProxyConfigBootstrap} 在启动阶段调用，把持久化的地址带进内存。
     * 与设置页保存共用 {@code decider} 的同一个内存槽，因此启动后二者一致。
     */
    public void loadProxyAddressIntoDecider() {
        String stored = appConfigRepository.findConfigValue(UPSTREAM_PROXY_ADDRESS_KEY);
        proxyDecider.updateProxyAddress(stored == null ? "" : stored);
    }

    /**
     * 运行时配置聚合视图。
     *
     * @param fakeVersion  伪造版本号（未配置时为空串）
     * @param gatewayAuth  下游鉴权状态（含脱敏 Key，绝不含明文）
     * @param retryPolicy  上游重试策略
     * @param upstreamProxyAddress 出站代理地址（{@code host:port}，空串表示没有代理）
     */
    public record RuntimeConfigView(String fakeVersion, GatewayAuthStatus gatewayAuth,
                                    RetryPolicyView retryPolicy, String upstreamProxyAddress) {
    }

    /**
     * 重试策略视图。
     *
     * <p>默认值与上限一并下发，是为了让前端不必自己维护一份常量 ——
     * 同一约束写两处时，改一侧不会报错，只会让用户在 UI 上选到一个被后端静默拒绝的值。
     *
     * @param maxAttempts 当前生效的重试次数；{@code -1} 表示无限
     * @param defaultValue 默认值，供前端做「恢复默认」
     * @param maxConfigurable 允许配置的上限，供前端做输入框约束
     */
    public record RetryPolicyView(int maxAttempts, int defaultValue, int maxConfigurable) {
    }
}
package com.kaixuan.copilot_ollama_proxy.application.config;

import com.kaixuan.copilot_ollama_proxy.application.config.GatewayAuthService.GatewayAuthStatus;
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

    private final AppConfigRepository appConfigRepository;
    private final GatewayAuthService gatewayAuthService;

    public RuntimeConfigService(AppConfigRepository appConfigRepository,
                                GatewayAuthService gatewayAuthService) {
        this.appConfigRepository = appConfigRepository;
        this.gatewayAuthService = gatewayAuthService;
    }

    /**
     * 聚合读取设置页所需的全部运行时配置。
     *
     * @return 结构化配置 DTO（伪造版本号 + 下游鉴权状态）
     */
    public Mono<RuntimeConfigView> getRuntimeConfig() {
        Mono<String> fakeVersionMono = Mono.fromCallable(() -> {
            String value = appConfigRepository.findConfigValue(FAKE_VERSION_KEY);
            return value == null ? "" : value;
        }).subscribeOn(Schedulers.boundedElastic());

        return Mono.zip(fakeVersionMono, gatewayAuthService.getStatus())
                .map(tuple -> new RuntimeConfigView(tuple.getT1(), tuple.getT2()));
    }

    public Mono<Void> saveFakeVersion(String version) {
        String normalizedVersion = version == null ? "" : version.trim();
        return Mono.fromRunnable(() -> appConfigRepository.saveConfig(FAKE_VERSION_KEY, normalizedVersion))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 运行时配置聚合视图。
     *
     * @param fakeVersion  伪造版本号（未配置时为空串）
     * @param gatewayAuth  下游鉴权状态（含脱敏 Key，绝不含明文）
     */
    public record RuntimeConfigView(String fakeVersion, GatewayAuthStatus gatewayAuth) {
    }
}
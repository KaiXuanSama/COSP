package com.kaixuan.copilot_ollama_proxy.application.config;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.AppConfigRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 管理后台可编辑的运行时配置用例。
 */
@Service
public class RuntimeConfigService {

    private static final String FAKE_VERSION_KEY = "fake_version";

    private final AppConfigRepository appConfigRepository;

    public RuntimeConfigService(AppConfigRepository appConfigRepository) {
        this.appConfigRepository = appConfigRepository;
    }

    public Mono<String> getFakeVersion() {
        return Mono.fromCallable(() -> {
            String value = appConfigRepository.findConfigValue(FAKE_VERSION_KEY);
            return value == null ? "" : value;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> saveFakeVersion(String version) {
        String normalizedVersion = version == null ? "" : version.trim();
        return Mono.fromRunnable(() -> appConfigRepository.saveConfig(FAKE_VERSION_KEY, normalizedVersion))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
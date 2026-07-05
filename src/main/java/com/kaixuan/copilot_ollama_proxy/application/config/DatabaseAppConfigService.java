package com.kaixuan.copilot_ollama_proxy.application.config;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.AppConfigRepository;
import org.springframework.stereotype.Service;

/**
 * {@link AppConfigService} 的默认实现 —— 委托给 {@link AppConfigRepository} 操作 app_config 表。
 */
@Service
public class DatabaseAppConfigService implements AppConfigService {

    private final AppConfigRepository appConfigRepository;

    public DatabaseAppConfigService(AppConfigRepository appConfigRepository) {
        this.appConfigRepository = appConfigRepository;
    }

    @Override
    public String findValue(String key) {
        return appConfigRepository.findConfigValue(key);
    }

    @Override
    public void saveValue(String key, String value) {
        appConfigRepository.saveConfig(key, value);
    }
}

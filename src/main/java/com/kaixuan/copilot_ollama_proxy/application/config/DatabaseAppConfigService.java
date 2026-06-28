package com.kaixuan.copilot_ollama_proxy.application.config;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import org.springframework.stereotype.Service;

/**
 * {@link AppConfigService} 的默认实现 —— 委托给 {@link ProviderConfigRepository} 操作 app_config 表。
 */
@Service
public class DatabaseAppConfigService implements AppConfigService {

    private final ProviderConfigRepository providerConfigRepository;

    public DatabaseAppConfigService(ProviderConfigRepository providerConfigRepository) {
        this.providerConfigRepository = providerConfigRepository;
    }

    @Override
    public String findValue(String key) {
        return providerConfigRepository.findConfigValue(key);
    }

    @Override
    public void saveValue(String key, String value) {
        providerConfigRepository.saveConfig(key, value);
    }
}

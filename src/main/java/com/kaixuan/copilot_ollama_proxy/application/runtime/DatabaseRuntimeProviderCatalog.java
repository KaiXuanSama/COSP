package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderModelRow;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 基于数据库的运行时 Provider 目录实现。
 */
@Service
public class DatabaseRuntimeProviderCatalog implements RuntimeProviderCatalog {

    private final ProviderConfigRepository providerConfigRepository;

    public DatabaseRuntimeProviderCatalog(ProviderConfigRepository providerConfigRepository) {
        this.providerConfigRepository = providerConfigRepository;
    }

    @Override
    public List<ProviderRuntimeConfiguration> getActiveProviders() {
        return providerConfigRepository.findAllActiveProvidersWithEnabledModels().stream().map(this::toConfiguration).toList();
    }

    private ProviderRuntimeConfiguration toConfiguration(ProviderConfigRow source) {
        return new ProviderRuntimeConfiguration(
                source.providerKey() != null ? source.providerKey() : "",
                source.baseUrl() != null ? source.baseUrl() : "",
                source.apiKey() != null ? source.apiKey() : "",
                source.apiFormat() != null ? source.apiFormat() : "openai",
                source.models().stream().map(this::toModel).toList(),
                source.customTransforms() != null ? source.customTransforms() : "{}"
        );
    }

    private ProviderRuntimeModel toModel(ProviderModelRow source) {
        return new ProviderRuntimeModel(
                source.modelName() != null ? source.modelName() : "",
                source.contextSize(),
                source.capsTools(),
                source.capsVision(),
                source.reasoningEffort() != null ? source.reasoningEffort() : "Medium"
        );
    }
}
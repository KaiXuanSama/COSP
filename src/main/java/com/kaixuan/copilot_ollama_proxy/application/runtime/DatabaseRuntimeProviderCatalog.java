package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderModelRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRow;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 基于数据库的运行时 Provider 目录实现。
 */
@Service
public class DatabaseRuntimeProviderCatalog implements RuntimeProviderCatalog {

    private final ProviderConfigRepository providerConfigRepository;
    private final ProviderApiKeyRepository providerApiKeyRepository;
    private final ProviderRequestTransformRepository providerRequestTransformRepository;

    public DatabaseRuntimeProviderCatalog(ProviderConfigRepository providerConfigRepository,
                                          ProviderApiKeyRepository providerApiKeyRepository,
                                          ProviderRequestTransformRepository providerRequestTransformRepository) {
        this.providerConfigRepository = providerConfigRepository;
        this.providerApiKeyRepository = providerApiKeyRepository;
        this.providerRequestTransformRepository = providerRequestTransformRepository;
    }

    @Override
    public List<ProviderRuntimeConfiguration> getActiveProviders() {
        List<ProviderConfigRow> providers = providerConfigRepository.findAllActiveProvidersWithEnabledModels();
        Map<Integer, ProviderRequestTransformRow> transforms = providerRequestTransformRepository
                .findByProviderIds(providers.stream().map(ProviderConfigRow::id).toList());
        return providers.stream().map(provider -> toConfiguration(provider, transforms.get(provider.id()))).toList();
    }

    private ProviderRuntimeConfiguration toConfiguration(ProviderConfigRow source, ProviderRequestTransformRow transform) {
        return new ProviderRuntimeConfiguration(
                source.providerKey() != null ? source.providerKey() : "",
                source.baseUrl() != null ? source.baseUrl() : "",
                providerApiKeyRepository.resolveActiveApiKey(source.id()),
                source.apiFormat() != null ? source.apiFormat() : "openai",
                source.models().stream().map(this::toModel).toList(),
                source.customTransforms() != null ? source.customTransforms() : "{}",
                transform != null ? transform.headerRulesJson() : "[]"
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
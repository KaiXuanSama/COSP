package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestTransformService;
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
                source.models().stream().map(this::toModel).toList(),
                transform != null ? transform.headerRulesJson() : "[]",
                transform != null ? transform.bodyRulesJson()
                        : ProviderRequestTransformService.EMPTY_BODY_RULES_JSON,
                source.supportedProtocolsJson(),
                source.anthropicBaseUrl() != null ? source.anthropicBaseUrl() : ""
        );
    }

    /**
     * 数据库行到运行时快照。
     *
     * <p>{@code maxOutputTokens} 原样搬运、{@code null} 也原样传下去：这一列在 V9 之前可能
     * 为空，而「空」的含义由消费侧的 {@link MaxOutputTokensSetting#parse} 决定（落到默认值）。
     * 这里不做字面量兜底 —— 与上一行 {@code reasoningEffort} 的 {@code "Medium"} 不同，
     * 那个字面量是历史遗留，在此不必照抄。
     */
    private ProviderRuntimeModel toModel(ProviderModelRow source) {
        return new ProviderRuntimeModel(
                source.modelName() != null ? source.modelName() : "",
                source.contextSize(),
                source.capsTools(),
                source.capsVision(),
                source.reasoningEffort() != null ? source.reasoningEffort() : "Medium",
                source.maxOutputTokens()
        );
    }
}
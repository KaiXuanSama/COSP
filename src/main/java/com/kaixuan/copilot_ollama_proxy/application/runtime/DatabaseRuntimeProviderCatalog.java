package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

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

    @SuppressWarnings("unchecked")
    private ProviderRuntimeConfiguration toConfiguration(Map<String, Object> source) {
        List<Map<String, Object>> models = (List<Map<String, Object>>) source.getOrDefault("models", List.of());
        return new ProviderRuntimeConfiguration(asString(source.get("providerKey")), asString(source.get("baseUrl")), asString(source.get("apiKey")),
                asString(source.getOrDefault("apiFormat", "openai")), models.stream().map(this::toModel).toList());
    }

    private ProviderRuntimeModel toModel(Map<String, Object> source) {
        return new ProviderRuntimeModel(asString(source.get("modelName")), asInt(source.get("contextSize")), asBoolean(source.get("capsTools")), asBoolean(source.get("capsVision")));
    }

    private String asString(Object value) {
        return value == null ? "" : value.toString();
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }
}
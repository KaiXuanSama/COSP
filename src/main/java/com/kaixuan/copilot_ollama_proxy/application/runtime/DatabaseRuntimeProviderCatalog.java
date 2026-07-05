package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderModelRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 基于数据库的运行时 Provider 目录实现。
 */
@Service
public class DatabaseRuntimeProviderCatalog implements RuntimeProviderCatalog {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRuntimeProviderCatalog.class);
    private static final TypeReference<List<Map<String, String>>> API_KEY_LIST_TYPE = new TypeReference<>() {};

    private final ProviderConfigRepository providerConfigRepository;
    private final ObjectMapper objectMapper;

    public DatabaseRuntimeProviderCatalog(ProviderConfigRepository providerConfigRepository, ObjectMapper objectMapper) {
        this.providerConfigRepository = providerConfigRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ProviderRuntimeConfiguration> getActiveProviders() {
        return providerConfigRepository.findAllActiveProvidersWithEnabledModels().stream().map(this::toConfiguration).toList();
    }

    private ProviderRuntimeConfiguration toConfiguration(ProviderConfigRow source) {
        return new ProviderRuntimeConfiguration(
                source.providerKey() != null ? source.providerKey() : "",
                source.baseUrl() != null ? source.baseUrl() : "",
                resolveActiveApiKey(source.apiKey(), source.activeApiKeyIndex()),
                source.apiFormat() != null ? source.apiFormat() : "openai",
                source.models().stream().map(this::toModel).toList(),
                source.customTransforms() != null ? source.customTransforms() : "{}"
        );
    }

    /**
     * 从 JSON 数组格式的 api_key 字段中提取当前激活的 API Key 字符串。
     * 如果 JSON 解析失败（例如旧格式纯字符串），则原样返回作为兜底。
     *
     * @param apiKeyJson JSON 数组字符串，如 [{"name":"Default","api_key":"sk-xxx"}]
     * @param activeIndex 当前激活的索引
     * @return 解析出的单个 API Key 字符串
     */
    private String resolveActiveApiKey(String apiKeyJson, int activeIndex) {
        if (apiKeyJson == null || apiKeyJson.isBlank()) {
            return "";
        }
        // 尝试解析为 JSON 数组
        if (apiKeyJson.startsWith("[")) {
            try {
                List<Map<String, String>> keys = objectMapper.readValue(apiKeyJson, API_KEY_LIST_TYPE);
                if (keys.isEmpty()) {
                    return "";
                }
                int index = Math.max(0, Math.min(activeIndex, keys.size() - 1));
                String key = keys.get(index).get("api_key");
                return key != null ? key : "";
            } catch (Exception e) {
                log.warn("解析 api_key JSON 数组失败，原样使用: {}", e.getMessage());
            }
        }
        // 兜底：旧格式纯字符串，直接返回
        return apiKeyJson;
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
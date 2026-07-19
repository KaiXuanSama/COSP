package com.kaixuan.copilot_ollama_proxy.application.ollama;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil;
import com.kaixuan.copilot_ollama_proxy.provider.generic.discovery.GenericDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Ollama 服务解析器。
 * 根据运行时配置中的 providerKey 解析唯一的 OllamaService 实现。
 * 支持带供应商前缀的模型名称格式：[ProviderKey]modelName
 */
@Service
public class OllamaServiceResolver {

    private static final Logger log = LoggerFactory.getLogger(OllamaServiceResolver.class);

    private final RuntimeProviderCatalog runtimeProviderCatalog;
    private final GenericDiscoveryService genericService;

    public OllamaServiceResolver(RuntimeProviderCatalog runtimeProviderCatalog, GenericDiscoveryService genericService) {
        this.runtimeProviderCatalog = runtimeProviderCatalog;
        this.genericService = genericService;
        log.info("OllamaServiceResolver 初始化，统一发现实现: {}", genericService.getProviderKey());
    }

    public OllamaService resolve(String modelName) {
        // 解析模型名称，检查是否带供应商前缀
        ModelNameUtil.ParseResult parsed = ModelNameUtil.parse(modelName);

        // 如果带供应商前缀，直接路由到指定供应商
        if (parsed.hasProviderPrefix()) {
            String providerKey = parsed.providerKey().toLowerCase();
            String actualModelName = parsed.modelName();

            ProviderRuntimeConfiguration config = runtimeProviderCatalog.getActiveProvider(providerKey);
            if (config != null && config.supportsModel(actualModelName)) {
                log.debug("模型 [{}] 通过前缀路由到统一服务商配置 [{}]", modelName, providerKey);
                return genericService;
            }
            log.warn("模型 [{}] 前缀指定服务商 [{}]，但该服务商不支持模型 [{}]", modelName, providerKey, actualModelName);
        }

        // 无前缀或前缀路由失败时，使用原有匹配逻辑
        String actualModelName = parsed.modelName();
        List<ProviderRuntimeConfiguration> matchedProviders = runtimeProviderCatalog.getActiveProviders().stream()
                .filter(provider -> provider.supportsModel(actualModelName))
                .toList();

        if (!matchedProviders.isEmpty()) {
            if (matchedProviders.size() > 1) {
                log.warn("模型 [{}] 命中多个 Provider: {}，将使用第一个匹配项。建议使用带前缀的模型名如 [{}]{} 来精确指定",
                        modelName,
                        matchedProviders.stream().map(ProviderRuntimeConfiguration::providerKey).collect(Collectors.joining(", ")),
                        matchedProviders.get(0).providerKey(),
                        actualModelName);
            }

            log.debug("模型 [{}] 路由到统一服务商配置 [{}]", modelName, matchedProviders.get(0).providerKey());
            return genericService;
        }
        log.warn("模型 [{}] 未找到匹配的启用服务商配置", modelName);
        return null;
    }
}
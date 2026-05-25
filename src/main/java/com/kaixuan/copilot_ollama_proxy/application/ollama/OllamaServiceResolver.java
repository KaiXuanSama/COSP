package com.kaixuan.copilot_ollama_proxy.application.ollama;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
    private final List<OllamaService> ollamaServices;
    private final Map<String, OllamaService> servicesByProviderKey;

    public OllamaServiceResolver(RuntimeProviderCatalog runtimeProviderCatalog, List<OllamaService> ollamaServices) {
        this.runtimeProviderCatalog = runtimeProviderCatalog;
        this.ollamaServices = List.copyOf(ollamaServices);
        this.servicesByProviderKey = ollamaServices.stream().collect(Collectors.toMap(OllamaService::getProviderKey, Function.identity(), (existing, replacement) -> replacement, LinkedHashMap::new));
        log.info("OllamaServiceResolver 初始化，已注册 {} 个服务商实现: {}", ollamaServices.size(), ollamaServices.stream().map(OllamaService::getProviderKey).collect(Collectors.joining(", ")));
    }

    public OllamaService resolve(String modelName) {
        // 解析模型名称，检查是否带供应商前缀
        ModelNameUtil.ParseResult parsed = ModelNameUtil.parse(modelName);

        // 如果带供应商前缀，直接路由到指定供应商
        if (parsed.hasProviderPrefix()) {
            String providerKey = parsed.providerKey();
            String actualModelName = parsed.modelName();

            OllamaService service = servicesByProviderKey.get(providerKey.toLowerCase());
            if (service != null) {
                // 验证该供应商是否支持此模型
                ProviderRuntimeConfiguration config = runtimeProviderCatalog.getActiveProvider(providerKey.toLowerCase());
                if (config != null && config.supportsModel(actualModelName)) {
                    log.debug("模型 [{}] 通过前缀精确路由到服务商 [{}]", modelName, providerKey);
                    return service;
                }
                log.warn("模型 [{}] 前缀指定服务商 [{}]，但该服务商不支持模型 [{}]", modelName, providerKey, actualModelName);
            } else {
                log.warn("模型 [{}] 前缀指定的服务商 [{}] 未注册", modelName, providerKey);
            }
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

            ProviderRuntimeConfiguration matchedProvider = matchedProviders.get(0);
            OllamaService service = servicesByProviderKey.get(matchedProvider.providerKey());
            if (service != null) {
                log.debug("模型 [{}] 路由到服务商 [{}]", modelName, matchedProvider.providerKey());
                return service;
            }
            log.warn("模型 [{}] 命中 Provider [{}]，但未找到对应的 OllamaService 实现", modelName, matchedProvider.providerKey());
        }

        if (!ollamaServices.isEmpty()) {
            OllamaService fallback = ollamaServices.get(0);
            log.warn("模型 [{}] 未找到匹配的服务商，使用默认 [{}]", modelName, fallback.getProviderKey());
            return fallback;
        }
        return null;
    }
}
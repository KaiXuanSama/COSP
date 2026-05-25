package com.kaixuan.copilot_ollama_proxy.application.openai;

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
 * OpenAI 上游服务解析器。
 * 根据运行时配置中的 providerKey 选择唯一的上游实现。
 * 支持带供应商前缀的模型名称格式：[ProviderKey]modelName
 */
@Service
public class UpstreamChatServiceResolver {

        private static final Logger log = LoggerFactory.getLogger(UpstreamChatServiceResolver.class);

        private final RuntimeProviderCatalog runtimeProviderCatalog;
        private final List<UpstreamChatService> upstreamServices;
        private final Map<String, UpstreamChatService> servicesByProviderKey;

        public UpstreamChatServiceResolver(RuntimeProviderCatalog runtimeProviderCatalog, List<UpstreamChatService> upstreamServices) {
                this.runtimeProviderCatalog = runtimeProviderCatalog;
                this.upstreamServices = List.copyOf(upstreamServices);
                this.servicesByProviderKey = upstreamServices.stream()
                                .collect(Collectors.toMap(UpstreamChatService::getProviderKey, Function.identity(), (existing, replacement) -> replacement, LinkedHashMap::new));

                log.info("UpstreamChatServiceResolver 初始化，已注册 {} 个上游实现: {}", upstreamServices.size(),
                                upstreamServices.stream().map(UpstreamChatService::getProviderKey).collect(Collectors.joining(", ")));
        }

        public UpstreamChatService resolve(String modelName) {
                // 解析模型名称，检查是否带供应商前缀
                ModelNameUtil.ParseResult parsed = ModelNameUtil.parse(modelName);

                // 如果带供应商前缀，直接路由到指定供应商
                if (parsed.hasProviderPrefix()) {
                        String providerKey = parsed.providerKey();
                        String actualModelName = parsed.modelName();

                        UpstreamChatService service = servicesByProviderKey.get(providerKey.toLowerCase());
                        if (service != null) {
                                // 验证该供应商是否支持此模型
                                ProviderRuntimeConfiguration config = runtimeProviderCatalog.getActiveProvider(providerKey.toLowerCase());
                                if (config != null && config.supportsModel(actualModelName)) {
                                        log.debug("上游模型 [{}] 通过前缀精确路由到 [{}]", modelName, providerKey);
                                        return service;
                                }
                                log.warn("上游模型 [{}] 前缀指定服务商 [{}]，但该服务商不支持模型 [{}]", modelName, providerKey, actualModelName);
                        } else {
                                log.warn("上游模型 [{}] 前缀指定的服务商 [{}] 未注册", modelName, providerKey);
                        }
                }

                // 无前缀或前缀路由失败时，使用原有匹配逻辑
                String actualModelName = parsed.modelName();
                List<ProviderRuntimeConfiguration> matchedProviders = runtimeProviderCatalog.getActiveProviders().stream()
                                .filter(provider -> provider.supportsModel(actualModelName)).toList();

                if (!matchedProviders.isEmpty()) {
                        if (matchedProviders.size() > 1) {
                                log.warn("上游模型 [{}] 命中多个 Provider: {}，将使用第一个匹配项。建议使用带前缀的模型名如 [{}]{} 来精确指定",
                                                modelName,
                                                matchedProviders.stream().map(ProviderRuntimeConfiguration::providerKey).collect(Collectors.joining(", ")),
                                                matchedProviders.get(0).providerKey(),
                                                actualModelName);
                        }

                        ProviderRuntimeConfiguration matchedProvider = matchedProviders.get(0);
                        UpstreamChatService service = servicesByProviderKey.get(matchedProvider.providerKey());
                        if (service != null) {
                                log.debug("上游模型 [{}] 路由到 [{}]", modelName, matchedProvider.providerKey());
                                return service;
                        }
                        log.warn("上游模型 [{}] 命中 Provider [{}]，但未找到对应的上游服务实现", modelName, matchedProvider.providerKey());
                }

                if (!upstreamServices.isEmpty()) {
                        UpstreamChatService fallback = upstreamServices.get(0);
                        log.warn("上游模型 [{}] 未找到匹配，使用默认 [{}]", modelName, fallback.getProviderKey());
                        return fallback;
                }
                return null;
        }
}
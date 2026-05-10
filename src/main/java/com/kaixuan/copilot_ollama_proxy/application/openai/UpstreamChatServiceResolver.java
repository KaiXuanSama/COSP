package com.kaixuan.copilot_ollama_proxy.application.openai;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
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
                List<ProviderRuntimeConfiguration> matchedProviders = runtimeProviderCatalog.getActiveProviders().stream().filter(provider -> provider.supportsModel(modelName)).toList();

                if (!matchedProviders.isEmpty()) {
                        if (matchedProviders.size() > 1) {
                                log.warn("上游模型 [{}] 命中多个 Provider: {}，将使用第一个匹配项", modelName,
                                                matchedProviders.stream().map(ProviderRuntimeConfiguration::providerKey).collect(Collectors.joining(", ")));
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
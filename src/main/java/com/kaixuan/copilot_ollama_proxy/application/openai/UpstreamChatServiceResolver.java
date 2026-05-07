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
 * 统一根据运行时配置中的 providerKey + apiFormat 选择唯一的上游实现。
 */
@Service
public class UpstreamChatServiceResolver {

        private static final Logger log = LoggerFactory.getLogger(UpstreamChatServiceResolver.class);

        private final RuntimeProviderCatalog runtimeProviderCatalog;
        private final List<UpstreamChatService> upstreamServices;
        private final Map<UpstreamServiceKey, UpstreamChatService> servicesByKey;

        /**
         * 构造函数，注入运行时 Provider 目录和所有可用的上游服务实现，并根据 providerKey + apiFormat 构建一个 Map 以便快速查找。
         * @param runtimeProviderCatalog 运行时 Provider 目录，用于获取当前可用的 Provider 配置
         * @param upstreamServices 所有可用的上游服务实现列表，通常由 Spring 自动注入
         */
        public UpstreamChatServiceResolver(RuntimeProviderCatalog runtimeProviderCatalog, List<UpstreamChatService> upstreamServices) {
                this.runtimeProviderCatalog = runtimeProviderCatalog;
                this.upstreamServices = List.copyOf(upstreamServices);
                this.servicesByKey = upstreamServices //
                                .stream() // 将上游服务列表转换为 Map，键为 providerKey + apiFormat 的组合，值为对应的服务实现。
                                .collect(Collectors.toMap( // 使用 Collectors.toMap 来构建 Map。
                                                service -> new UpstreamServiceKey( // 创建一个复合键，包含 providerKey 和 apiFormat，用于唯一标识一个上游服务实现。
                                                                service.getProviderKey(), // 提供者键
                                                                service.getUpstreamApiFormat() // 上游 API 格式
                                                ), // 键为服务实例的 providerKey 和 apiFormat 组合
                                                Function.identity(), // 值为服务实例本身
                                                (existing, replacement) -> replacement, // 如果有重复的键，则使用后者覆盖前者
                                                LinkedHashMap::new) // 使用 LinkedHashMap 保持插入顺序
                                );

                log.info("UpstreamChatServiceResolver 初始化，已注册 {} 个上游实现: {}", //
                                upstreamServices.size(), //
                                upstreamServices.stream().map(service -> service.getProviderKey() + "/" + service.getUpstreamApiFormat()).collect(Collectors.joining(", ")) //
                );
        }

        /**
         * 根据模型名称找到对应的上游服务实现。
         * @param modelName 模型名称
         * @return 对应的 UpstreamChatService 实现，如果没有找到则返回 null
         */
        public UpstreamChatService resolve(String modelName) {
                // 根据模型名称在运行时配置中找到所有支持该模型的 Provider 配置项，理论上应该只有一个匹配项，如果有多个则记录警告并使用第一个。
                List<ProviderRuntimeConfiguration> matchedProviders = runtimeProviderCatalog.getActiveProviders().stream().filter(provider -> provider.supportsModel(modelName)).toList();

                // 如果找到了匹配的 Provider 配置项，则使用 providerKey 和 apiFormat 从已注册的上游服务中找到对应的实现。
                if (!matchedProviders.isEmpty()) {
                        if (matchedProviders.size() > 1) {
                                log.warn("上游模型 [{}] 命中多个 Provider: {}，将使用第一个匹配项", //
                                                modelName, //
                                                matchedProviders.stream().map(provider -> provider.providerKey() + "/" + provider.apiFormat()).collect(Collectors.joining(", ")) //
                                );
                        }

                        // 使用第一个匹配的 Provider 配置项，结合 providerKey 和 apiFormat 从已注册的上游服务中找到对应的实现。
                        ProviderRuntimeConfiguration matchedProvider = matchedProviders.get(0);
                        UpstreamChatService service = servicesByKey.get(new UpstreamServiceKey(matchedProvider.providerKey(), matchedProvider.apiFormat()));
                        if (service != null) {
                                log.debug("上游模型 [{}] 路由到 [{} / {}]", modelName, matchedProvider.providerKey(), matchedProvider.apiFormat());
                                return service;
                        }
                        log.warn("上游模型 [{}] 命中 [{} / {}]，但未找到对应的上游服务实现", modelName, matchedProvider.providerKey(), matchedProvider.apiFormat());
                }

                // 如果没有找到匹配的 Provider 配置项，或者找到了配置项但没有对应的上游服务实现，则使用默认的上游服务实现（如果有注册的话）。
                if (!upstreamServices.isEmpty()) {
                        UpstreamChatService fallback = upstreamServices.get(0);
                        log.warn("上游模型 [{}] 未找到匹配，使用默认 [{} / {}]", modelName, fallback.getProviderKey(), fallback.getUpstreamApiFormat());
                        return fallback;
                }
                return null;
        }

        /**
         * 上游服务的唯一标识键，由 providerKey 和 apiFormat 组成，用于在 Map 中快速查找对应的服务实现。
         * @param providerKey 服务提供商的唯一标识，例如 "mimo"
         * @param apiFormat 上游服务的 API 格式，例如 "chat"
         */
        private record UpstreamServiceKey(String providerKey, String apiFormat) {
        }
}
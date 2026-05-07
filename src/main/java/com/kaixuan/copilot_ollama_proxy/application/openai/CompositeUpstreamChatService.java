package com.kaixuan.copilot_ollama_proxy.application.openai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 上游聊天服务组合器 —— 代理所有已注册的 UpstreamChatService 实现，
 * 根据模型名称路由到对应的服务商。
 *
 * 注意：此类不实现 UpstreamChatService 接口，避免 Spring 自动注入时产生循环依赖。
 */
@Service
public class CompositeUpstreamChatService {

    private static final Logger log = LoggerFactory.getLogger(CompositeUpstreamChatService.class);

    private final List<UpstreamChatService> upstreamServices;

    public CompositeUpstreamChatService(List<UpstreamChatService> upstreamServices) {
        this.upstreamServices = upstreamServices;
        log.info("CompositeUpstreamChatService 初始化，已注册 {} 个上游服务: {}", upstreamServices.size(),
                upstreamServices.stream().map(UpstreamChatService::getProviderKey).collect(Collectors.joining(", ")));
    }

    private UpstreamChatService resolveService(String modelName) {
        for (UpstreamChatService service : upstreamServices) {
            if (service.supportsModel(modelName)) {
                log.debug("上游模型 [{}] 路由到 [{}]", modelName, service.getProviderKey());
                return service;
            }
        }
        if (!upstreamServices.isEmpty()) {
            log.warn("上游模型 [{}] 未找到匹配，使用默认 [{}]", modelName, upstreamServices.get(0).getProviderKey());
            return upstreamServices.get(0);
        }
        return null;
    }

    public Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model) {
        UpstreamChatService service = resolveService(model);
        if (service == null) {
            return Mono.error(new RuntimeException("没有可用的上游服务来处理模型: " + model));
        }
        return service.chatCompletion(openAiRequest, model);
    }

    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
        UpstreamChatService service = resolveService(model);
        if (service == null) {
            return Flux.error(new RuntimeException("没有可用的上游服务来处理模型: " + model));
        }
        return service.chatCompletionStream(openAiRequest, model);
    }
}

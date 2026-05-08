package com.kaixuan.copilot_ollama_proxy.application.openai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 上游聊天服务组合器 —— 代理所有已注册的 UpstreamChatService 实现，
 * 根据模型名称路由到对应的服务商。
 *
 * 注意：此类不实现 UpstreamChatService 接口，避免 Spring 自动注入时产生循环依赖。
 */
@Service
public class CompositeUpstreamChatService {

    private static final Logger log = LoggerFactory.getLogger(CompositeUpstreamChatService.class);

    private final UpstreamChatServiceResolver upstreamChatServiceResolver;

    public CompositeUpstreamChatService(UpstreamChatServiceResolver upstreamChatServiceResolver) {
        this.upstreamChatServiceResolver = upstreamChatServiceResolver;
        log.info("CompositeUpstreamChatService 初始化完成");
    }

    /**
     * 根据模型名称找到对应的上游服务实现。
     * @param modelName 模型名称
     * @return 对应的 UpstreamChatService 实现，如果没有找到则返回 null
     */
    private UpstreamChatService resolveService(String modelName) {
        return upstreamChatServiceResolver.resolve(modelName);
    }

    public Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model) {
        UpstreamChatService service = resolveService(model);
        if (service == null) {
            return Mono.error(new RuntimeException("没有可用的上游服务来处理模型: " + model));
        }
        return service.chatCompletion(openAiRequest, model);
    }

    /**
     * 流式聊天补全，返回一个 Flux<String>，每个元素代表上游服务发送的一个 SSE 片段。
     * @param openAiRequest OpenAI 格式的请求体，已转换为 Map 形式
     * @param model 模型名称，用于路由到对应的上游服务
     * @return Flux<String>，每个元素是上游服务发送的一个 SSE 片段
     */
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
        // 根据模型名称解析出对应的上游服务实现，如果没有找到则返回错误 Mono。
        UpstreamChatService service = resolveService(model);
        if (service == null) {
            return Flux.error(new RuntimeException("没有可用的上游服务来处理模型: " + model));
        }
        
        // 调用上游服务的 chatCompletionStream 方法，返回一个 Flux<String>，每个元素是上游服务发送的一个 SSE 片段。
        return service.chatCompletionStream(openAiRequest, model);
    }
}

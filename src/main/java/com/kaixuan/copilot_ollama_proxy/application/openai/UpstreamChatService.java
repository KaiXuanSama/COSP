package com.kaixuan.copilot_ollama_proxy.application.openai;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 上游聊天服务接口 —— 定义 OpenAI 格式的请求/响应协议。
 * Spring 容器中会同时存在多个实现，由 CompositeUpstreamChatService 统一路由。
 */
public interface UpstreamChatService {

    /** 返回该服务商的唯一标识 key，如 "longcat"、"mimo" */
    String getProviderKey();

    /** 判断该上游服务是否支持指定的模型名 */
    boolean supportsModel(String modelName);

    /**
     * 非流式聊天补全。
     */
    Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model);

    /**
     * 流式聊天补全。
     */
    Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model);
}

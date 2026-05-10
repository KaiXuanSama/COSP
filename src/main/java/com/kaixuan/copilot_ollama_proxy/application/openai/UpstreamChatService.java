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

    /**
     * 返回该上游实现所对接的协议格式。
     * 当前使用与 provider_config.api_format 相同的取值，如 openai。
     */
    default String getUpstreamApiFormat() {
        return "openai";
    }

    /** 
     * 判断该服务是否支持处理指定模型的请求。
     * @param modelName 模型名称
     * @return 如果该服务支持处理指定模型的请求，则返回 true；否则返回 false
     */
    boolean supportsModel(String modelName);

    /**
     * 非流式聊天补全。
     * @param openAiRequest OpenAI 格式的请求体
     * @param model 模型名称
     * @return 包含聊天补全结果的 Mono<String>，字符串内容为上游服务返回的 JSON 格式响应
     */
    Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model);

    /**
     * 流式聊天补全。
     * @param openAiRequest OpenAI 格式的请求体
     * @param model 模型名称
     */
    Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model);
}

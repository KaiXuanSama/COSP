package com.kaixuan.copilot_ollama_proxy.application.openai;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 上游聊天服务接口 —— 定义 OpenAI 格式的请求/响应协议。
 * 当前由统一 Generic 实现执行请求。
 */
public interface UpstreamChatService {

    /** 返回该服务商的唯一标识 key，如 "longcat"、"mimo" */
    String getProviderKey();

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

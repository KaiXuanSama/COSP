package com.kaixuan.copilot_ollama_proxy.application.openai;

import org.springframework.http.HttpHeaders;
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
     * 向请求头中注入该供应商所需的认证信息。
     * 默认使用 Bearer Token 方式，子类可覆写实现非标准鉴权（如 api-key 头）。
     * 管理后台的模型拉取等场景会调用此方法，避免鉴权逻辑硬编码在 Controller 中。
     *
     * @param headers 请求头对象
     * @param apiKey API Key
     */
    default void applyAuthHeaders(HttpHeaders headers, String apiKey) {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
    }

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

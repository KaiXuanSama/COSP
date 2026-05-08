package com.kaixuan.copilot_ollama_proxy.application.ollama;

import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaTagsResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Ollama 服务接口 —— 每个服务商（longcat/mimo）各自实现一份。
 * Spring 容器中会同时存在多个实现，由 CompositeOllamaService 统一路由。
 */
public interface OllamaService {

    /** 
     * 返回该服务商的唯一标识 key，如 "longcat"、"mimo" 
     * @return 服务商唯一标识字符串，用于路由和日志记录
     */
    String getProviderKey();

    /** 
     * 判断该服务是否支持处理指定模型的请求。
     * @param modelName 模型名称
     * @return 如果支持则返回 true，否则返回 false
     */
    boolean supportsModel(String modelName);

    /** 
     * 获取该服务商支持的模型列表。
     * @return 支持的模型列表
     */
    Mono<OllamaTagsResponse> listModels();

    /** 
     * 获取指定模型的详细信息。
     * @param modelName 模型名称
     * @return 模型的详细信息
     */
    OllamaShowResponse showModel(String modelName);

    /** 
     * 处理聊天请求。
     * @param request 聊天请求对象
     * @return 聊天响应对象
     */
    Mono<OllamaChatResponse> chat(OllamaChatRequest request);

    /** 
     * 处理聊天请求的流式响应。
     * @param request 聊天请求对象
     * @return 聊天响应的流式对象
     */
    Flux<OllamaChatResponse> chatStream(OllamaChatRequest request);
}

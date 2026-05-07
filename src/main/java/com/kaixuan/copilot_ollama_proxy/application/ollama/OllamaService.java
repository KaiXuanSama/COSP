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

    /** 返回该服务商的唯一标识 key，如 "longcat"、"mimo" */
    String getProviderKey();

    /** 判断该服务商是否支持指定的模型名（模型已启用且属于该服务商） */
    boolean supportsModel(String modelName);

    Mono<OllamaTagsResponse> listModels();

    OllamaShowResponse showModel(String modelName);

    Mono<OllamaChatResponse> chat(OllamaChatRequest request);

    Flux<OllamaChatResponse> chatStream(OllamaChatRequest request);
}

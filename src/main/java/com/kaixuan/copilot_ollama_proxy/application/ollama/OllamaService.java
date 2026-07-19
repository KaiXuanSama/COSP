package com.kaixuan.copilot_ollama_proxy.application.ollama;

import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;

/**
 * Ollama 发现服务接口。
 * 统一发现实现提供模型详情查询能力。
 */
public interface OllamaService {

    /** 
     * 返回该服务商的唯一标识 key，如 "deepseek"、"mimo" 
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
     * 获取指定模型的详细信息。
     * @param modelName 模型名称
     * @return 模型的详细信息
     */
    OllamaShowResponse showModel(String modelName);
}

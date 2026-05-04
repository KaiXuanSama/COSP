package com.kaixuan.copilot_ollama_proxy.proxy;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 上游聊天服务接口 —— 定义 OpenAI 格式的请求/响应协议。
 * 
 * 输入和输出均为 OpenAI Chat Completions 格式，各实现负责内部的协议转换。
 * 这样控制器层无需关心上游是 Anthropic、OpenAI 还是其他 API。
 */
public interface UpstreamChatService {

    /**
     * 非流式聊天补全。
     *
     * @param openAiRequest OpenAI 格式的请求体（已解析为 Map）
     * @param model         模型名称
     * @return OpenAI 格式的响应 JSON 字符串
     */
    Mono<String> chatCompletion(java.util.Map<String, Object> openAiRequest, String model);

    /**
     * 流式聊天补全。
     *
     * @param openAiRequest OpenAI 格式的请求体（已解析为 Map）
     * @param model         模型名称
     * @return 已格式化的 OpenAI SSE data 行（每条是一个完整的 JSON chunk）
     */
    Flux<String> chatCompletionStream(java.util.Map<String, Object> openAiRequest, String model);
}

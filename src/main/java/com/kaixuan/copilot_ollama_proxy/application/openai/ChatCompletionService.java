package com.kaixuan.copilot_ollama_proxy.application.openai;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.GenericOpenAiChatService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 聊天补全应用服务。
 * 负责解析供应商模型路由，并委托统一 Generic 执行器完成上游调用。
 */
@Service
public class ChatCompletionService {

    private final ProviderRouteResolver providerRouteResolver;
    private final GenericOpenAiChatService genericChatService;

    /**
     * 创建聊天补全应用服务。
     *
     * @param providerRouteResolver 供应商模型路由解析器
     * @param genericChatService 统一上游执行器
     */
    public ChatCompletionService(ProviderRouteResolver providerRouteResolver,
                                 GenericOpenAiChatService genericChatService) {
        this.providerRouteResolver = providerRouteResolver;
        this.genericChatService = genericChatService;
    }

    /**
     * 执行非流式聊天补全。
     *
     * @param openAiRequest OpenAI 格式请求体
     * @param model 模型名称
     * @return 上游原始 OpenAI 响应
     */
    public Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model,
                                       HttpHeaders downstreamHeaders, String requestId) {
        ResolvedProviderRoute route = providerRouteResolver.resolve(model);
        if (route == null) {
            return Mono.error(new RuntimeException("没有可用的上游服务来处理模型: " + model));
        }
        return genericChatService.chatCompletion(openAiRequest, route, downstreamHeaders, requestId);
    }

    /**
     * 执行不含下游请求头上下文的非流式聊天补全。
     * 仅供内部兼容调用与单元测试使用；HTTP API 必须调用带 downstreamHeaders 的重载。
     */
    public Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model) {
        return chatCompletion(openAiRequest, model, HttpHeaders.EMPTY, null);
    }

    /**
     * 执行流式聊天补全。
     *
     * @param openAiRequest OpenAI 格式请求体
     * @param model 模型名称
     * @return 上游 SSE 数据块
     */
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model,
                                              HttpHeaders downstreamHeaders, String requestId) {
        ResolvedProviderRoute route = providerRouteResolver.resolve(model);
        if (route == null) {
            return Flux.error(new RuntimeException("没有可用的上游服务来处理模型: " + model));
        }
        return genericChatService.chatCompletionStream(openAiRequest, route, downstreamHeaders, requestId);
    }

    /**
     * 执行不含下游请求头上下文的流式聊天补全。
     * 仅供内部兼容调用与单元测试使用；HTTP API 必须调用带 downstreamHeaders 的重载。
     */
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
        return chatCompletionStream(openAiRequest, model, HttpHeaders.EMPTY, null);
    }
}
package com.kaixuan.copilot_ollama_proxy.application.openai;

import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchDecision;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchManager;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.GenericOpenAiChatService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 聊天补全应用服务 —— 服务下游的 OpenAI 协议端点。
 *
 * 负责解析供应商模型路由，经协议调度管理器确认走法后委托上游执行器完成调用。
 *
 * <p>本服务的下游协议恒为 {@link WireProtocol#OPENAI}（由它服务的端点决定）；
 * 上游协议由 {@link ProtocolDispatchManager} 按供应商支持情况得出。
 * 当前阶段所有供应商都被乐观地认为支持两种协议，故恒走 OpenAI 直连，
 * 行为与引入调度器之前完全一致。
 */
@Service
public class ChatCompletionService {

    /** 本服务服务的下游端点协议，固定不变。 */
    private static final WireProtocol DOWNSTREAM_PROTOCOL = WireProtocol.OPENAI;

    private final ProviderRouteResolver providerRouteResolver;
    private final ProtocolDispatchManager protocolDispatchManager;
    private final GenericOpenAiChatService genericChatService;

    /**
     * 创建聊天补全应用服务。
     *
     * @param providerRouteResolver 供应商模型路由解析器
     * @param protocolDispatchManager 协议调度管理器
     * @param genericChatService 统一上游执行器
     */
    public ChatCompletionService(ProviderRouteResolver providerRouteResolver,
                                 ProtocolDispatchManager protocolDispatchManager,
                                 GenericOpenAiChatService genericChatService) {
        this.providerRouteResolver = providerRouteResolver;
        this.protocolDispatchManager = protocolDispatchManager;
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
        ProtocolDispatchDecision decision =
                protocolDispatchManager.dispatch(DOWNSTREAM_PROTOCOL, route.provider());
        // TODO 跨协议翻译尚未实现，此处直接失败。待 ProtocolTranslator 落地后，
        //  改为把翻译器套在上游服务外侧（装饰器）—— 重试、落库、usage 提取都留在被包装那层，
        //  翻译只负责报文改写，绝不能进 retryWhen 内侧。
        //  参考实现：cc switch / sub2api / new api 已有成熟方案，不必从零推导帧映射。
        if (decision.translationNeeded()) {
            return Mono.error(new ProtocolTranslationNotSupportedException(
                    route.provider().providerKey(), decision.downstreamProtocol(), decision.upstreamProtocol()));
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
        ProtocolDispatchDecision decision =
                protocolDispatchManager.dispatch(DOWNSTREAM_PROTOCOL, route.provider());
        // TODO 同非流式：跨协议翻译尚未实现。流式翻译尤其要注意帧数不对等 ——
        //  Anthropic 的 message_start / content_block_start 不产出下游帧，
        //  而一个 message_delta 可能产出正文与 finish 两个 chunk。
        if (decision.translationNeeded()) {
            return Flux.error(new ProtocolTranslationNotSupportedException(
                    route.provider().providerKey(), decision.downstreamProtocol(), decision.upstreamProtocol()));
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
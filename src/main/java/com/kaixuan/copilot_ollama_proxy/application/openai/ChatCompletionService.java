package com.kaixuan.copilot_ollama_proxy.application.openai;

import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchDecision;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchManager;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.TranslatedRequest;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.AnthropicToOpenAiResponseTranslator;
import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.OpenAiToAnthropicRequestTranslator;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic.GenericAnthropicChatService;
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
    private final GenericOpenAiChatService genericOpenAiChatService;
    private final GenericAnthropicChatService genericAnthropicChatService;
    private final OpenAiToAnthropicRequestTranslator o2aTranslator;
    private final AnthropicToOpenAiResponseTranslator a2oTranslator;

    /**
     * 创建聊天补全应用服务。
     *
     * @param providerRouteResolver 供应商模型路由解析器
     * @param protocolDispatchManager 协议调度管理器
     * @param genericOpenAiChatService OpenAI 上游执行器
     * @param genericAnthropicChatService Anthropic 上游执行器
     * @param o2aTranslator O2A 请求翻译器（去程）
     * @param a2oTranslator A2O 响应翻译器（回程）
     */
    public ChatCompletionService(ProviderRouteResolver providerRouteResolver,
                                 ProtocolDispatchManager protocolDispatchManager,
                                 GenericOpenAiChatService genericOpenAiChatService,
                                 GenericAnthropicChatService genericAnthropicChatService,
                                 OpenAiToAnthropicRequestTranslator o2aTranslator,
                                 AnthropicToOpenAiResponseTranslator a2oTranslator) {
        this.providerRouteResolver = providerRouteResolver;
        this.protocolDispatchManager = protocolDispatchManager;
        this.genericOpenAiChatService = genericOpenAiChatService;
        this.genericAnthropicChatService = genericAnthropicChatService;
        this.o2aTranslator = o2aTranslator;
        this.a2oTranslator = a2oTranslator;
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
        
        // 同协议直连，原请求体不变。
        if (!decision.translationNeeded()) {
            return genericOpenAiChatService.chatCompletion(openAiRequest, route, downstreamHeaders, requestId);
        }
        
        // 跨协议翻译：去程改写请求体、回程改写响应体。
        //
        // 翻译套在上游服务外侧，因而天然在 retryWhen 之外 ——
        // 空响应判定（AnthropicContentDetector）与 api_call_log 落库用的都是
        // 上游原生形态，若翻译进了重试内侧，判定器会把每一轮都当成空响应。
        // 见 docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md 第 12 节。
        //
        // 模型名传下游原始的 model（含 [provider-key] 前缀）而非上游返回的裸名：
        // 本服务按前缀路由，把裸名透给下游会让它下一轮路由失败（第 7 节）。
        if (decision.upstreamProtocol() == WireProtocol.ANTHROPIC) {
            TranslatedRequest translated = o2aTranslator.translateRequest(openAiRequest);
            Mono<String> upstream = genericAnthropicChatService.messages(
                    translated.body(), route, downstreamHeaders, requestId);
            return a2oTranslator.translateResponse(upstream, model);
        }
        
        // 其它协议组合：当前只有 OPENAI 与 ANTHROPIC 两种，走不到这里。
        // 留个明确分支，将来加第三种协议时不会静默走错路。
        return Mono.error(new ProtocolTranslationNotSupportedException(
                route.provider().providerKey(), decision.downstreamProtocol(), decision.upstreamProtocol()));
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
        
        // 同协议直连，原请求体不变。
        if (!decision.translationNeeded()) {
            return genericOpenAiChatService.chatCompletionStream(openAiRequest, route, downstreamHeaders, requestId);
        }
        
        // 跨协议翻译：去程改写请求体、回程把 Anthropic 事件翻回 OpenAI chunk。
        //
        // 帧数不对等（第 2 节）：message_start 产 1 帧（唯一带 role），
        // content_block_start/stop 与 signature_delta 产 0 帧，
        // 而 [DONE] 由流结束触发而非 message_stop。
        if (decision.upstreamProtocol() == WireProtocol.ANTHROPIC) {
            TranslatedRequest translated = o2aTranslator.translateRequest(openAiRequest);
            Flux<String> upstream = genericAnthropicChatService.messagesStream(
                    translated.body(), route, downstreamHeaders, requestId);
            return a2oTranslator.translateStream(upstream, model, translated.context());
        }
        
        return Flux.error(new ProtocolTranslationNotSupportedException(
                route.provider().providerKey(), decision.downstreamProtocol(), decision.upstreamProtocol()));
    }

    /**
     * 执行不含下游请求头上下文的流式聊天补全。
     * 仅供内部兼容调用与单元测试使用；HTTP API 必须调用带 downstreamHeaders 的重载。
     */
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
        return chatCompletionStream(openAiRequest, model, HttpHeaders.EMPTY, null);
    }
}
package com.kaixuan.copilot_ollama_proxy.application.anthropic;

import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchDecision;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchManager;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic.GenericAnthropicChatService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Messages 应用服务 —— 服务下游的 Anthropic 协议端点。
 *
 * <p>与 {@code ChatCompletionService} 结构对称：解析路由 → 协议调度 → 委托上游执行器。
 * 差别只在下游协议固定为 {@link WireProtocol#ANTHROPIC}，以及委托对象是
 * {@link GenericAnthropicChatService}。
 *
 * <h2>路由规则与 OpenAI 端点完全一致</h2>
 * 同一个 {@link ProviderRouteResolver}：带前缀模型精确路由，无前缀模型要求唯一匹配。
 * <strong>协议不参与候选集筛选</strong> —— 否则同一个模型名在两个端点上可能路由到
 * 不同供应商，「路由只由模型名决定」这条可预测性就没了。协议差异在路由<em>之后</em>
 * 由调度管理器处理。
 */
@Service
public class MessagesService {

    /** 本服务服务的下游端点协议，固定不变。 */
    private static final WireProtocol DOWNSTREAM_PROTOCOL = WireProtocol.ANTHROPIC;

    private final ProviderRouteResolver providerRouteResolver;
    private final ProtocolDispatchManager protocolDispatchManager;
    private final GenericAnthropicChatService anthropicChatService;

    public MessagesService(ProviderRouteResolver providerRouteResolver,
                           ProtocolDispatchManager protocolDispatchManager,
                           GenericAnthropicChatService anthropicChatService) {
        this.providerRouteResolver = providerRouteResolver;
        this.protocolDispatchManager = protocolDispatchManager;
        this.anthropicChatService = anthropicChatService;
    }

    /**
     * 执行一次非流式 Messages 调用。
     *
     * @param request 已含所有透传字段的 Anthropic 请求体
     * @param model 模型名（可能带供应商前缀）
     * @return 上游原始响应 JSON
     */
    public Mono<String> messages(Map<String, Object> request, String model,
                                 HttpHeaders downstreamHeaders, String requestId) {
        ResolvedProviderRoute route = providerRouteResolver.resolve(model);
        if (route == null) {
            return Mono.error(new RuntimeException("没有可用的上游服务来处理模型: " + model));
        }
        ProtocolDispatchDecision decision =
                protocolDispatchManager.dispatch(DOWNSTREAM_PROTOCOL, route.provider());
        // TODO 跨协议翻译尚未实现。待 ProtocolTranslator 落地后，改为把翻译器套在
        //  上游服务外侧（装饰器）—— 重试、落库、usage 提取都留在被包装那层，
        //  翻译只负责报文改写，绝不能进 retryWhen 内侧（否则空响应判定看到的是
        //  合成出来的形状，而非上游原生响应）。
        if (decision.translationNeeded()) {
            return Mono.error(new ProtocolTranslationNotSupportedException(
                    decision.downstreamProtocol(), decision.upstreamProtocol()));
        }
        return anthropicChatService.messages(request, route, downstreamHeaders, requestId);
    }

    /**
     * 执行一次流式 Messages 调用。
     *
     * @return 上游 SSE 事件流的 data 内容（未做协议改写）
     */
    public Flux<String> messagesStream(Map<String, Object> request, String model,
                                       HttpHeaders downstreamHeaders, String requestId) {
        ResolvedProviderRoute route = providerRouteResolver.resolve(model);
        if (route == null) {
            return Flux.error(new RuntimeException("没有可用的上游服务来处理模型: " + model));
        }
        ProtocolDispatchDecision decision =
                protocolDispatchManager.dispatch(DOWNSTREAM_PROTOCOL, route.provider());
        // TODO 同非流式：流式翻译尤其要注意帧数不对等 —— OpenAI 的单一 chunk 序列
        //  转成 Anthropic 事件流需要合成 message_start / content_block_start 等
        //  源里不存在的结构，且顺序必须合法（严格客户端会校验事件序列）。
        if (decision.translationNeeded()) {
            return Flux.error(new ProtocolTranslationNotSupportedException(
                    decision.downstreamProtocol(), decision.upstreamProtocol()));
        }
        return anthropicChatService.messagesStream(request, route, downstreamHeaders, requestId);
    }
}

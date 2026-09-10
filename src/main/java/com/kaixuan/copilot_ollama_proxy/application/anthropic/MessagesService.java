package com.kaixuan.copilot_ollama_proxy.application.anthropic;

import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchDecision;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchManager;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic.GenericAnthropicChatService;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <h2>与 OpenAI 端点的真实差异：本服务只有直连一条路</h2>
 * A2O 请求翻译（下游 {@code /v1/messages} + 上游只有 OpenAI）<strong>尚未实现</strong>，
 * 因此需要翻译时抛 {@link ProtocolTranslationNotSupportedException}（控制器译为 400）。
 * 反方向（下游 OpenAI + 上游 Anthropic）已在 {@code ChatCompletionService} 落地。
 * 落地时翻译器必须套在上游服务<strong>外侧</strong>：重试、落库、usage 提取都留在
 * 被包装那层，翻译绝不能进 {@code retryWhen} 内侧（否则空响应判定看到的是合成形态）。
 * 流式还多一层难点：帧数不对等，OpenAI 的单一 chunk 序列转成 Anthropic 事件流需要
 * 合成 {@code message_start} / {@code content_block_start} 等源里不存在的结构，
 * 且顺序必须合法（严格客户端会校验事件序列）。契约见
 * {@code docs/PROTOCOL_TRANSLATION_CONTRACT.md}。
 *
 * <h2>为何两个方法体都裹在 defer 里</h2>
 * 路由与调度都是<strong>同步</strong>调用，且调度会抛
 * {@link com.kaixuan.copilot_ollama_proxy.application.protocol.NoSupportedProtocolException}。
 * 控制器那侧的 {@code Mono.firstWithSignal(messages(...), cancelSignal)} 参数是 eager 求值的：
 * 不包 defer 时异常在组装期就逃出了控制器方法，{@code onErrorResume} 不在链上，
 * 下游拿到 WebFlux 默认 500 而不是那句点名成因的 Anthropic 错误体。
 */
@Service
public class MessagesService {

    /** 本服务服务的下游端点协议，固定不变。 */
    private static final WireProtocol DOWNSTREAM_PROTOCOL = WireProtocol.ANTHROPIC;

    private final ProviderRouteResolver providerRouteResolver;
    private final ProtocolDispatchManager protocolDispatchManager;
    private final GenericAnthropicChatService anthropicChatService;

    /**
     * 调用生命周期事件通知器，由 Spring 可选注入。
     *
     * <p>用途单一：调度结论出来后把协议信息补给生命周期事件，供前端 Toast 显示路径标记。
     * 可选注入（与 provider 层同一范式）—— 单元测试直接 new 本类时不关心这条链路，
     * 缺省即不发。当前 A2O 方向尚未实现，标记会显示为「A→O」后再收 FAILED，
     * 这恰好让人一眼看出是哪条线路缺实现。
     */
    private CallLifecycleNotifier lifecycleNotifier;

    public MessagesService(ProviderRouteResolver providerRouteResolver,
                           ProtocolDispatchManager protocolDispatchManager,
                           GenericAnthropicChatService anthropicChatService) {
        this.providerRouteResolver = providerRouteResolver;
        this.protocolDispatchManager = protocolDispatchManager;
        this.anthropicChatService = anthropicChatService;
    }

    @Autowired(required = false)
    public void setLifecycleNotifier(CallLifecycleNotifier lifecycleNotifier) {
        this.lifecycleNotifier = lifecycleNotifier;
    }

    /**
     * 把调度结论补进生命周期事件，供前端 Toast 渲染路径标记（如「A→O」）。
     *
     * <p>失败静默忽略 —— 事件推送是 best-effort 的观测链路，任何异常都不能影响聊天数据流。
     */
    private void notifyProtocols(String requestId, ProtocolDispatchDecision decision) {
        if (lifecycleNotifier == null || requestId == null) {
            return;
        }
        try {
            lifecycleNotifier.recordProtocols(requestId, DOWNSTREAM_PROTOCOL.name(),
                    decision.upstreamProtocol().name());
        } catch (Exception exception) {
            // 观测链路失败不影响调用本身。
        }
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
        // defer 把路由 / 调度的同步异常转成 onError 信号，理由见类注释。
        return Mono.defer(() -> dispatchMessages(request, model, downstreamHeaders, requestId));
    }

    private Mono<String> dispatchMessages(Map<String, Object> request, String model,
                                          HttpHeaders downstreamHeaders, String requestId) {
        ResolvedProviderRoute route = providerRouteResolver.resolve(model);
        if (route == null) {
            return Mono.error(new RuntimeException("没有可用的上游服务来处理模型: " + model));
        }
        ProtocolDispatchDecision decision =
                protocolDispatchManager.dispatch(DOWNSTREAM_PROTOCOL, route.provider());
        // 调度结论出来了：补协议信息供前端 Toast 显示「A」或「A→O」路径标记。
        // 刻意放在抛未实现异常之前 —— 那样失败 Toast 上仍能看到「A→O」，
        // 一眼认出是这条跨协议线路缺实现，而不是某个笼统的上游错误。
        notifyProtocols(requestId, decision);
        // TODO(待实现) A2O 请求翻译（去程）+ O2A 响应翻译（回程）。
        //  两者是同一条链的两半，缺一半这条路就不可用，因此不拆开计划。
        //  接线约束与流式难点见类注释，契约见
        //  docs/PROTOCOL_TRANSLATION_CONTRACT.md（请求侧）与
        //  docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md 第 15.3 节（响应侧）。
        if (decision.translationNeeded()) {
            return Mono.error(new ProtocolTranslationNotSupportedException(
                    route.provider().providerKey(), decision.downstreamProtocol(), decision.upstreamProtocol()));
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
        // 同非流式：defer 让组装期异常成为 onError 信号，控制器才能发 Anthropic error 事件。
        return Flux.defer(() -> dispatchMessagesStream(request, model, downstreamHeaders, requestId));
    }

    private Flux<String> dispatchMessagesStream(Map<String, Object> request, String model,
                                                HttpHeaders downstreamHeaders, String requestId) {
        ResolvedProviderRoute route = providerRouteResolver.resolve(model);
        if (route == null) {
            return Flux.error(new RuntimeException("没有可用的上游服务来处理模型: " + model));
        }
        ProtocolDispatchDecision decision =
                protocolDispatchManager.dispatch(DOWNSTREAM_PROTOCOL, route.provider());
        // 同非流式：结论出来即补，前端 Tag 不必等到上游响应。
        notifyProtocols(requestId, decision);
        // TODO(待实现) 同非流式的 A2O 请求 + O2A 响应。流式还多一层帧数不对等：
        //  合成 message_start / content_block_start 等源里不存在的结构，且顺序必须合法。
        if (decision.translationNeeded()) {
            return Flux.error(new ProtocolTranslationNotSupportedException(
                    route.provider().providerKey(), decision.downstreamProtocol(), decision.upstreamProtocol()));
        }
        return anthropicChatService.messagesStream(request, route, downstreamHeaders, requestId);
    }
}

package com.kaixuan.copilot_ollama_proxy.application.openai;

import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchDecision;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchManager;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.GenericResponsesChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Responses 应用服务 —— 服务下游的 OpenAI Responses 协议端点。
 *
 * <p>与 {@code ChatCompletionService} / {@code MessagesService} 结构对称：
 * 解析路由 → 协议调度 → 委托上游执行器。差别只在下游协议固定为
 * {@link WireProtocol#RESPONSES}，以及委托对象是 {@link GenericResponsesChatService}。
 *
 * <h2>路由规则与另两个端点完全一致</h2>
 * 同一个 {@link ProviderRouteResolver}：带前缀模型精确路由，无前缀模型要求唯一匹配。
 * <strong>协议不参与候选集筛选</strong> —— 否则同一个模型名在三个端点上可能路由到
 * 不同供应商，「路由只由模型名决定」这条可预测性就没了。协议差异在路由<em>之后</em>
 * 由调度管理器处理。
 *
 * <h2>本服务只有直连一条路</h2>
 * C2R 请求翻译（下游 {@code /v1/responses} + 上游只有 Chat 或 Messages）
 * <strong>尚未实现</strong>，因此需要翻译时抛
 * {@link ProtocolTranslationNotSupportedException}（控制器译为 400）。
 * 当前唯一实现的跨协议方向是 C2M 去程 + M2C 回程，在 {@code ChatCompletionService} 里。
 *
 * <p>落地时翻译器必须套在上游服务<strong>外侧</strong>：重试、落库、usage 提取都留在
 * 被包装那层，翻译绝不能进 {@code retryWhen} 内侧（否则空响应判定看到的是合成形态）。
 * 流式还多一层难点：帧数不对等，且 Responses 的事件序列比 Anthropic 更长
 * （{@code response.created} → {@code output_item.added} → 多种 {@code *.delta}
 * → {@code output_item.done} → {@code response.completed}），合成时顺序必须合法。
 * 契约见 {@code docs/PROTOCOL_TRANSLATION_CONTRACT.md}（请求侧）与
 * {@code docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md}（响应侧）。
 *
 * <h2>为何两个方法体都裹在 defer 里</h2>
 * 路由与调度都是<strong>同步</strong>调用，且调度会抛
 * {@link com.kaixuan.copilot_ollama_proxy.application.protocol.NoSupportedProtocolException}。
 * 控制器那侧的 {@code Mono.firstWithSignal(responses(...), cancelSignal)} 参数是 eager 求值的：
 * 不包 defer 时异常在组装期就逃出了控制器方法，{@code onErrorResume} 不在链上，
 * 下游拿到 WebFlux 默认 500 而不是那句点名成因的错误体。
 * {@code ChatDispatchErrorSignalTests} 钉住了这条约束。
 */
@Service
public class ResponsesService {

    private static final Logger log = LoggerFactory.getLogger(ResponsesService.class);

    /** 本服务服务的下游端点协议，固定不变。 */
    private static final WireProtocol DOWNSTREAM_PROTOCOL = WireProtocol.RESPONSES;

    private final ProviderRouteResolver providerRouteResolver;
    private final ProtocolDispatchManager protocolDispatchManager;
    private final GenericResponsesChatService responsesChatService;

    /**
     * 调用生命周期事件通知器，由 Spring 可选注入。
     *
     * <p>用途单一：调度结论出来后把协议信息补给生命周期事件，供前端 Toast 显示路径标记。
     * 可选注入（与 provider 层同一范式）—— 单元测试直接 new 本类时不关心这条链路，
     * 缺省即不发。跨协议方向尚未实现时，标记会显示为「R→C」后再收 FAILED，
     * 这恰好让人一眼看出是哪条线路缺实现。
     */
    private CallLifecycleNotifier lifecycleNotifier;

    public ResponsesService(ProviderRouteResolver providerRouteResolver,
                            ProtocolDispatchManager protocolDispatchManager,
                            GenericResponsesChatService responsesChatService) {
        this.providerRouteResolver = providerRouteResolver;
        this.protocolDispatchManager = protocolDispatchManager;
        this.responsesChatService = responsesChatService;
    }

    @Autowired(required = false)
    public void setLifecycleNotifier(CallLifecycleNotifier lifecycleNotifier) {
        this.lifecycleNotifier = lifecycleNotifier;
    }

    /**
     * 把调度结论补进生命周期事件，供前端 Toast 渲染路径标记（如「R→C」）。
     *
     * <p>失败不中断调用 —— 事件推送是 best-effort 的观测链路，任何异常都不能影响聊天数据流。
     * 但<strong>要留痕迹</strong>：完全吞掉时，补写持续失败（比如 requestId 口径不一致）
     * 的唯一症状是「路径标记不显示」，无从查证 —— 观测链路自身不可观测是个反模式。
     * 用 debug 而非 warn：它不影响功能，平时不必占日志，排查时开 debug 即可看到。
     */
    private void notifyProtocols(String requestId, ProtocolDispatchDecision decision) {
        if (lifecycleNotifier == null || requestId == null) {
            return;
        }
        try {
            lifecycleNotifier.recordProtocols(requestId, DOWNSTREAM_PROTOCOL.name(),
                    decision.upstreamProtocol().name());
        } catch (Exception exception) {
            log.debug("生命周期协议信息补写失败，不影响调用本身 [{}]: {}",
                    requestId, exception.toString());
        }
    }

    /**
     * 执行一次非流式 Responses 调用。
     *
     * @param request 已含所有透传字段的 Responses 请求体
     * @param model 模型名（可能带供应商前缀）
     * @return 上游原始响应 JSON
     */
    public Mono<String> responses(Map<String, Object> request, String model,
                                  HttpHeaders downstreamHeaders, String requestId) {
        // defer 把路由 / 调度的同步异常转成 onError 信号，理由见类注释。
        return Mono.defer(() -> dispatchResponses(request, model, downstreamHeaders, requestId));
    }

    private Mono<String> dispatchResponses(Map<String, Object> request, String model,
                                           HttpHeaders downstreamHeaders, String requestId) {
        ResolvedProviderRoute route = providerRouteResolver.resolve(model);
        if (route == null) {
            return Mono.error(new RuntimeException("没有可用的上游服务来处理模型: " + model));
        }
        ProtocolDispatchDecision decision =
                protocolDispatchManager.dispatch(DOWNSTREAM_PROTOCOL, route.provider());
        // 调度结论出来了：补协议信息供前端 Toast 显示「R」或「R→C」路径标记。
        // 刻意放在抛未实现异常之前 —— 那样失败 Toast 上仍能看到跨协议标记，
        // 一眼认出是这条线路缺实现，而不是某个笼统的上游错误。
        notifyProtocols(requestId, decision);
        // TODO(待实现) R2C / R2M 请求翻译（去程）+ 对应的响应翻译（回程）。
        //  两者是同一条链的两半，缺一半这条路就不可用，因此不拆开计划。
        //  接线约束与流式难点见类注释，契约见
        //  docs/PROTOCOL_TRANSLATION_CONTRACT.md（请求侧）与
        //  docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md（响应侧）。
        //  注意本方向与「协议流转编排」相关：真实场景下用户可能希望同一个供应商的
        //  不同模型走不同上游协议，那时该由编排配置而非全局回退序决定，见
        //  ProtocolDispatchManager.TRANSLATION_FALLBACK_ORDER 的 TODO。
        if (decision.translationNeeded()) {
            return Mono.error(new ProtocolTranslationNotSupportedException(
                    route.provider().providerKey(), decision.downstreamProtocol(), decision.upstreamProtocol()));
        }
        return responsesChatService.responses(request, route, downstreamHeaders, requestId);
    }

    /**
     * 执行一次流式 Responses 调用。
     *
     * @return 上游 SSE 事件流的 data 内容（未做协议改写）
     */
    public Flux<String> responsesStream(Map<String, Object> request, String model,
                                        HttpHeaders downstreamHeaders, String requestId) {
        // 同非流式：defer 让组装期异常成为 onError 信号，控制器才能发 SSE error 事件。
        return Flux.defer(() -> dispatchResponsesStream(request, model, downstreamHeaders, requestId));
    }

    private Flux<String> dispatchResponsesStream(Map<String, Object> request, String model,
                                                 HttpHeaders downstreamHeaders, String requestId) {
        ResolvedProviderRoute route = providerRouteResolver.resolve(model);
        if (route == null) {
            return Flux.error(new RuntimeException("没有可用的上游服务来处理模型: " + model));
        }
        ProtocolDispatchDecision decision =
                protocolDispatchManager.dispatch(DOWNSTREAM_PROTOCOL, route.provider());
        // 同非流式：结论出来即补，前端 Tag 不必等到上游响应。
        notifyProtocols(requestId, decision);
        // TODO(待实现) 同非流式的去程与回程翻译。流式还多一层帧数不对等：
        //  Responses 的事件序列比 Anthropic 更长，合成时顺序必须合法。
        if (decision.translationNeeded()) {
            return Flux.error(new ProtocolTranslationNotSupportedException(
                    route.provider().providerKey(), decision.downstreamProtocol(), decision.upstreamProtocol()));
        }
        return responsesChatService.responsesStream(request, route, downstreamHeaders, requestId);
    }
}

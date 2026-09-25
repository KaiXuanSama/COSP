package com.kaixuan.copilot_ollama_proxy.application.openai;

import com.kaixuan.copilot_ollama_proxy.pipeline.RequestPipeline;
import com.kaixuan.copilot_ollama_proxy.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamEvent;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Responses 应用服务 —— 服务下游的 OpenAI Responses 协议端点。
 *
 * <h2>它已经退化成「端点声明 + 建 ctx + 交主干」（3.4c-2）</h2>
 * 与 {@code ChatCompletionService} / {@code MessagesService} <strong>三者同形</strong>：
 * 各自只声明自己的 {@link WireProtocol}，建一个只含下游侧事实的 ctx，交给
 * {@code RequestPipeline} 跑完整条主干。本类不再持有执行器、翻译器表或调度器。
 *
 * <h2>跨协议为何不再抛错</h2>
 * 主干在翻译插槽处按 {@code (下游, 上游)} 查表：<strong>去程未命中即报错</strong>
 * （{@code ProtocolTranslationNotSupportedException}，控制器译 400）。
 * R2C / R2M 请求翻译至今没有实现，因此本端点跨协议时仍会得到那个 400 ——
 * 但抛出点从「本类的 if」变成了「主干查表未命中」，语义与阈值完全一致。
 * <strong>将来补上对应翻译器（加一个 {@code @Component}），本类一行不用改。</strong>
 *
 * <p>注意本方向与「协议流转编排」相关：真实场景下用户可能希望同一个供应商的
 * 不同模型走不同上游协议，那时该由编排配置而非全局回退序决定，见
 * {@code ProtocolDispatchManager.TRANSLATION_FALLBACK_ORDER} 的 TODO。
 *
 * <h2>路由规则与另两个端点完全一致</h2>
 * 同一个 {@link ProviderRouteResolver}：带前缀模型精确路由，无前缀模型要求唯一匹配。
 * <strong>协议不参与候选集筛选</strong> —— 否则同一个模型名在三个端点上可能路由到
 * 不同供应商，「路由只由模型名决定」这条可预测性就没了。协议差异在路由<em>之后</em>
 * 由调度管理器处理。
 *
 * <h2>翻译器的位置约束仍成立</h2>
 * 翻译器套在上游执行器<strong>外侧</strong>（主干上，因而在 {@code retryWhen} 之外）：
 * 重试、落库、usage 提取都留在被包装那层，翻译绝不能进 {@code retryWhen} 内侧
 * （否则空响应判定看到的是合成形态）。
 * 流式还多一层难点：帧数不对等，且 Responses 的事件序列比 Anthropic 更长
 * （{@code response.created} → {@code output_item.added} → 多种 {@code *.delta}
 * → {@code output_item.done} → {@code response.completed}），合成时顺序必须合法。
 * 契约见 {@code docs/PROTOCOL_TRANSLATION_CONTRACT.md}（请求侧）与
 * {@code docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md}（响应侧）。
 *
 * <h2>为何两个方法体都裹在 defer 里</h2>
 * 主干的准备与调度都是<strong>同步</strong>调用，且会抛
 * {@link com.kaixuan.copilot_ollama_proxy.pipeline.protocol.NoSupportedProtocolException}。
 * 控制器那侧的 {@code Mono.firstWithSignal(responses(...), cancelSignal)} 参数是 eager 求值的：
 * 不包 defer 时异常在组装期就逃出了控制器方法，{@code onErrorResume} 不在链上，
 * 下游拿到 WebFlux 默认 500 而不是那句点名成因的错误体。
 * {@code ChatDispatchErrorSignalTests} 钉住了这条约束。
 */
@Service
public class ResponsesService {

    /** 本服务服务的下游端点协议，固定不变。 */
    private static final WireProtocol DOWNSTREAM_PROTOCOL = WireProtocol.RESPONSES;

    private final RequestPipeline requestPipeline;

    public ResponsesService(RequestPipeline requestPipeline) {
        this.requestPipeline = requestPipeline;
    }

    /**
     * 执行一次非流式 Responses 调用。
     *
     * @param request 已含所有透传字段的 Responses 请求体
     * @param model 模型名（可能带供应商前缀）
     * @return 上游原始响应 JSON
     */
    public Mono<UpstreamEvent> responses(Map<String, Object> request, String model,
                                         HttpHeaders downstreamHeaders, String requestId) {
        // defer 把主干各同步步骤的异常转成 onError 信号，理由见类注释。
        // 非流式是「恰有一个元素的流」，故在**出口**收成 Mono。
        return Mono.defer(() -> requestPipeline.execute(RequestPipelineContext.forEndpoint(
                request, model, DOWNSTREAM_PROTOCOL, downstreamHeaders, requestId, false)).single());
    }

    /**
     * 执行一次流式 Responses 调用。
     *
     * @return 上游 SSE 事件流（未做协议改写时即上游原生形态）
     */
    public Flux<UpstreamEvent> responsesStream(Map<String, Object> request, String model,
                                               HttpHeaders downstreamHeaders, String requestId) {
        // 同非流式：defer 让组装期异常成为 onError 信号，控制器才能发 SSE error 事件。
        return Flux.defer(() -> requestPipeline.execute(RequestPipelineContext.forEndpoint(
                request, model, DOWNSTREAM_PROTOCOL, downstreamHeaders, requestId, true)));
    }
}

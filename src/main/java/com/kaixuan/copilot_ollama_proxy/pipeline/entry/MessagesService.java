package com.kaixuan.copilot_ollama_proxy.pipeline.entry;

import com.kaixuan.copilot_ollama_proxy.pipeline.RequestPipeline;
import com.kaixuan.copilot_ollama_proxy.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEvent;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Messages 应用服务 —— 服务下游的 Anthropic 协议端点。
 *
 * <h2>它已经退化成「端点声明 + 建 ctx + 交主干」</h2>
 * 与 {@code ChatCompletionService} / {@code ResponsesService} <strong>三者同形</strong>：
 * 各自只声明自己的 {@link WireProtocol}，建一个只含下游侧事实的 ctx，交给
 * {@code RequestPipeline} 跑完整条主干。本类不再持有执行器、翻译器表或调度器。
 *
 * <h2>跨协议由主干查表，不在端点写分支</h2>
 * 主干在翻译插槽处按 {@code (下游, 上游)} 查表：<strong>去程未命中即报错</strong>
 * （{@code ProtocolTranslationNotSupportedException}，控制器译 400）。
 * M2C 请求译器已声明 (MESSAGES, CHAT)，容器自动接入；本类方法体无需变化。
 * C2M 回程仍未实现，该方向会原样透传 Chat 响应并 WARN，仅用于功能分支受控验证。
 * M2R 请求方向仍未实现，不因新增 M2C 而放行；完整状态见 {@code ProtocolTranslator}。
 *
 * <p>翻译器套在上游执行器<strong>外侧</strong>（主干上，因而在 {@code retryWhen} 之外）：
 * 重试、落库、usage 提取都留在被包装那层，翻译绝不能进重试内侧
 * （否则空响应判定看到的是合成形态）。契约见
 * {@code docs/reference/contracts/C2M-REQUEST-CONTRACT.md}。
 *
 * <h2>为何两个方法体都裹在 defer 里</h2>
 * 主干的准备与调度都是<strong>同步</strong>调用，且会抛
 * {@link com.kaixuan.copilot_ollama_proxy.pipeline.protocol.NoSupportedProtocolException}。
 * 控制器那侧的 {@code Mono.firstWithSignal(messages(...), cancelSignal)} 参数是 eager 求值的：
 * 不包 defer 时异常在组装期就逃出了控制器方法，{@code onErrorResume} 不在链上，
 * 下游拿到 WebFlux 默认 500 而不是那句点名成因的 Anthropic 错误体。
 */
@Service
public class MessagesService {

    /** 本服务服务的下游端点协议，固定不变。 */
    private static final WireProtocol DOWNSTREAM_PROTOCOL = WireProtocol.MESSAGES;

    private final RequestPipeline requestPipeline;

    public MessagesService(RequestPipeline requestPipeline) {
        this.requestPipeline = requestPipeline;
    }

    /**
     * 执行一次非流式 Messages 调用。
     *
     * @param request 已含所有透传字段的 Anthropic 请求体
     * @param model 模型名（可能带供应商前缀）
    * @return 响应事件；回程未接时为上游原生 JSON
     */
    public Mono<UpstreamEvent> messages(Map<String, Object> request, String model,
                                        HttpHeaders downstreamHeaders, String requestId) {
        // defer 把主干各同步步骤的异常转成 onError 信号，理由见类注释。
        // 非流式是「恰有一个元素的流」，故在**出口**收成 Mono。
        return Mono.defer(() -> requestPipeline.execute(RequestPipelineContext.forEndpoint(
                request, model, DOWNSTREAM_PROTOCOL, downstreamHeaders, requestId, false)).single());
    }

    /**
     * 执行一次流式 Messages 调用。
     *
     * @return 上游 SSE 事件流（未做协议改写时即上游原生形态）
     */
    public Flux<UpstreamEvent> messagesStream(Map<String, Object> request, String model,
                                              HttpHeaders downstreamHeaders, String requestId) {
        // 同非流式：defer 让组装期异常成为 onError 信号，控制器才能发 Anthropic error 事件。
        return Flux.defer(() -> requestPipeline.execute(RequestPipelineContext.forEndpoint(
                request, model, DOWNSTREAM_PROTOCOL, downstreamHeaders, requestId, true)));
    }
}

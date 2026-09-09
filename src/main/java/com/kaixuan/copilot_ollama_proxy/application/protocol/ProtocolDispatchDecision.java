package com.kaixuan.copilot_ollama_proxy.application.protocol;

/**
 * 一次调用的协议调度结论。
 *
 * <p>只承载「怎么走」这个决定，不持有上游服务实例 —— 服务的选择在
 * {@code ChatCompletionService} 这一层完成，那里才有各上游服务的依赖。
 * 让本 record 保持无依赖，使调度逻辑可以被纯单元测试覆盖。
 *
 * @param downstreamProtocol 下游使用的协议（由它打的端点决定）
 * @param upstreamProtocol   实际要对上游使用的协议
 * @param translationNeeded  两侧协议是否不同，即是否需要翻译组件介入
 */
public record ProtocolDispatchDecision(
        WireProtocol downstreamProtocol,
        WireProtocol upstreamProtocol,
        boolean translationNeeded) {

    /** 同协议直连：两侧一致，无需翻译。 */
    static ProtocolDispatchDecision direct(WireProtocol protocol) {
        return new ProtocolDispatchDecision(protocol, protocol, false);
    }

    /** 跨协议：需要翻译组件把请求与响应双向改写。 */
    static ProtocolDispatchDecision translated(WireProtocol downstream, WireProtocol upstream) {
        return new ProtocolDispatchDecision(downstream, upstream, true);
    }
}

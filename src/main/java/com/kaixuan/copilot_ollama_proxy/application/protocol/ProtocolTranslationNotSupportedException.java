package com.kaixuan.copilot_ollama_proxy.application.protocol;

/**
 * 下游协议与上游协议不一致、且翻译组件尚未实现时抛出的异常。
 *
 * <h2>为何要有一个专门的异常类型</h2>
 * 这不是「上游失败」而是「本代理暂不支持这条组合」，两者的处置完全不同：
 * 前者应当重试，后者重试多少次结果都一样。做成独立类型使它不会被
 * {@code isRetryableFailure} 误判为可重试，也让日志里的成因一目了然。
 *
 * <p>消息里带上具体的协议组合而非泛泛一句「不支持」：出现这个异常时，
 * 排查者第一个想知道的就是「哪一侧对哪一侧」。
 */
public class ProtocolTranslationNotSupportedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final WireProtocol downstreamProtocol;
    private final WireProtocol upstreamProtocol;

    public ProtocolTranslationNotSupportedException(WireProtocol downstreamProtocol,
                                                    WireProtocol upstreamProtocol) {
        super("暂不支持跨协议翻译：下游 " + downstreamProtocol + " → 上游 " + upstreamProtocol
                + "（翻译组件尚未实现，当前仅支持同协议直连）");
        this.downstreamProtocol = downstreamProtocol;
        this.upstreamProtocol = upstreamProtocol;
    }

    public WireProtocol downstreamProtocol() {
        return downstreamProtocol;
    }

    public WireProtocol upstreamProtocol() {
        return upstreamProtocol;
    }
}

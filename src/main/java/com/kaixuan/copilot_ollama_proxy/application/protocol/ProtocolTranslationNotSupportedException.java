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
 *
 * <h2>消息要指向可操作的成因</h2>
 * V8.8 协议支持落库后，本异常的实际触发条件几乎总是「该供应商没声明支持下游打的
 * 那个协议」，而不是某个抽象的「翻译能力缺失」。因此消息里带上供应商标识并直接
 * 点名该去改哪个配置 —— 类名留给代码读者，消息留给遇到它的人。
 */
public class ProtocolTranslationNotSupportedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final WireProtocol downstreamProtocol;
    private final WireProtocol upstreamProtocol;

    public ProtocolTranslationNotSupportedException(WireProtocol downstreamProtocol,
                                                    WireProtocol upstreamProtocol) {
        this(null, downstreamProtocol, upstreamProtocol);
    }

    /**
     * @param providerKey 目标供应商标识，空时退到不点名的通用消息
     */
    public ProtocolTranslationNotSupportedException(String providerKey, WireProtocol downstreamProtocol,
                                                    WireProtocol upstreamProtocol) {
        super(buildMessage(providerKey, downstreamProtocol, upstreamProtocol));
        this.downstreamProtocol = downstreamProtocol;
        this.upstreamProtocol = upstreamProtocol;
    }

    private static String buildMessage(String providerKey, WireProtocol downstreamProtocol,
                                       WireProtocol upstreamProtocol) {
        String provider = providerKey == null || providerKey.isBlank() ? "目标供应商" : "供应商 " + providerKey;
        return provider + " 未声明支持 " + downstreamProtocol + " 协议，而跳协议翻译（"
                + downstreamProtocol + " → " + upstreamProtocol + "）尚未实现。"
                + "请在供应商配置里勾选该协议，或改用已支持该协议的供应商";
    }

    public WireProtocol downstreamProtocol() {
        return downstreamProtocol;
    }

    public WireProtocol upstreamProtocol() {
        return upstreamProtocol;
    }
}

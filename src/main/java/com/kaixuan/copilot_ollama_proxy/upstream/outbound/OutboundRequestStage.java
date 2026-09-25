package com.kaixuan.copilot_ollama_proxy.upstream.outbound;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import org.springframework.http.HttpHeaders;

/**
 * 出站请求的<strong>协议特定装配</strong>支线 —— 每个上游协议一份实现。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>契约</strong>（出站装配） · 位置：{@code upstream/outbound/}
 * 步骤「出站请求装配」—— 发送前块在<strong>请求体装配之后</strong>调它，把「发去哪」与
 * 「协议必需的那几个头」定下来，连同协议无关的三层头装配一起写进 {@code ctx.outboundHeaders/outboundBaseUrl}。
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 *
 * <h2>它承载两件协议特有的事，其余交给主干</h2>
 * <ol>
 *   <li>{@link #resolveBaseUrl} —— <strong>发去哪</strong>。三条线路的地址列不同：Chat 用
 *       {@code base_url}，Anthropic 用 {@code anthropic_base_url}（回退 base_url），
 *       Responses 用 {@code responses_base_url}（回退 base_url）。返回<strong>原始列值</strong>，
 *       归一化由 {@link OutboundRequestAssembler} 统一做（那是协议无关的传输层正确性）。</li>
 *   <li>{@link #applyProtocolHeaders} —— <strong>协议必需的那几个头</strong>。目前只有
 *       Anthropic 需要（{@code anthropic-version}），Chat / Responses 无，故默认 no-op。</li>
 * </ol>
 *
 * <p>协议<strong>无关</strong>的三层头装配（下游头透传 / 鉴权头再分配 / 请求头规则）不在这里 ——
 * 那是 {@code ProviderRequestHeaderService.applyHeaders} 的事，由 {@link OutboundRequestAssembler}
 * 先调它、本支线的 {@link #applyProtocolHeaders} <strong>后</strong>补协议头，使协议头能被请求头规则……
 * <strong>不</strong>，顺序相反：协议头必须在规则<strong>之后</strong> set-if-absent，规则才能覆盖它。
 * 精确顺序见 {@link OutboundRequestAssembler#assemble}。
 *
 * <h2>键由实现声明，未命中是报错</h2>
 * {@link #protocol()} 返回本实现服务的上游协议。与 {@code UpstreamExecutor} / 请求体支线同一机制：
 * 加一个上游协议 = 加一个 {@code @Component}，主干一个字不动。但<strong>未命中语义是报错</strong>
 * （与执行器表一致，与请求体支线相反）：每条线路都必须能解析出地址，查不到 = 装配坏了，
 * 而不是「这种协议不需要出站装配」。理由见 {@link OutboundRequestStageRegistry}。
 */
public interface OutboundRequestStage {

    /** 本实现服务的上游协议 —— 查表键。 */
    WireProtocol protocol();

    /**
     * 解析本协议线路使用的<strong>原始</strong>基础地址（尚未归一化）。
     *
     * <p>只回答「读哪一列、怎么回退」这件协议特有的事；尾斜杠归一化由
     * {@link OutboundRequestAssembler} 统一做，因为那与协议无关。
     *
     * @param provider 供应商运行时配置
     * @return 原始基础地址（可能带尾斜杠 / 空白，交给 assembler 归一化）
     */
    String resolveBaseUrl(ProviderRuntimeConfiguration provider);

    /**
     * 补写本协议<strong>必需</strong>的请求头（如 {@code anthropic-version}）。
     *
     * <p>默认 no-op：多数协议没有这类头。它在协议无关的三层头装配<strong>之后</strong>被调，
     * 且用 set-if-absent 语义（不覆盖请求头规则已经写好的值）—— 精确顺序与理由见
     * {@link OutboundRequestAssembler#assemble}。
     *
     * @param headers 已完成三层装配的出站头，本方法在其上补协议必需头
     */
    default void applyProtocolHeaders(HttpHeaders headers) {
        // 默认无协议必需头。
    }
}

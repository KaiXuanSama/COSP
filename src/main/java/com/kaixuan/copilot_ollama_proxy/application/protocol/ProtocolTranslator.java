package com.kaixuan.copilot_ollama_proxy.application.protocol;

/**
 * 跨协议<strong>翻译组件</strong>的契约占位。
 *
 * <p>当下游协议与上游协议不一致时（如下游打 OpenAI 端点、供应商只有 Anthropic 端点），
 * 由本组件承担双向改写：请求体转成上游协议、响应/事件流转回下游协议。
 *
 * <h2>本阶段只有签名，没有实现</h2>
 * 第一阶段只建协议调度接缝与 Anthropic 直连链路，翻译留到后续阶段。
 * 现在就把接口摆出来，是为了让 {@link ProtocolDispatchManager} 的分支结构一次成形 ——
 * 若此刻不留位置，届时加翻译就不是「新增实现」而是「改造调度器」。
 *
 * <h2>为何方法签名故意留空</h2>
 * 流式翻译的真实形状取决于 Anthropic 事件流解析的实现方式（{@code message_start} 与
 * {@code content_block_start} 不产出下游帧、一个 {@code message_delta} 可能产出两个 chunk），
 * 现在凭空定方法签名极可能定错，反而会把后续实现绑到一个错误的形状上。
 * 待 Anthropic 侧解析落地、真实形状清楚之后再补方法。
 */
public interface ProtocolTranslator {

    // TODO 翻译方法尚未定义，本接口当前只有协议标识。补充时需要三个方法：
    //  请求体改写（下游协议 → 上游协议）、非流式响应改写、流式帧改写（回下游协议）。
    //  流式那个的签名要等 Anthropic 事件解析落地后再定 —— 它不是「一帧进一帧出」：
    //  message_start / content_block_start 不产出下游帧，而一个 message_delta
    //  可能同时产出正文 chunk 与 finish chunk，故返回类型至少是 List/Flux 而非单个 String。
    //  另需注意翻译必须在重试边界之外：判定与重试用的是上游原生形态，
    //  若翻译发生在 retryWhen 内侧，空响应判定看到的就是合成出来的形状。

    /** 本翻译器接受的下游协议。 */
    WireProtocol downstreamProtocol();

    /** 本翻译器对接的上游协议。 */
    WireProtocol upstreamProtocol();
}

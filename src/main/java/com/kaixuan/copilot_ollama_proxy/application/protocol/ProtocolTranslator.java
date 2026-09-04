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
    //
    //  请求侧的字段映射、丢弃清单与错误处置已定契约，见
    //  docs/PROTOCOL_TRANSLATION_CONTRACT.md。两条必须先钉测试的不变式：
    //   1. 无损搬运「下游已表态」—— 丢了 reasoning_effort 会让设置层的兜底档
    //      静默退化成覆写档（该文档第 2 节）；
    //   2. tool_use / tool_result 配对修复的 merge → pair → merge 三步顺序（第 3.5 节）。
    //  请求侧改写的返回类型不能只是 Map：响应侧需要请求期上下文（下游要流式还是非流式、
    //  思考是下游要求的还是设置层注入的），故返回 {body, translationContext}（第 7 节）。
    //
    //  响应侧（A2O）的契约已定，见 docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md。
    //  「不是一帧进一帧出」已由实测事件序列证实（该文档第 1、2 节）：
    //   零帧：content_block_start / content_block_stop / signature_delta / message_stop / ping
    //   一帧：message_start（唯一带 role）/ text_delta / thinking_delta / message_delta
    //   多帧：流结束时的 finish chunk + usage chunk + [DONE]
    //  故流式方法的返回类型必须是 List/Flux 而非单个 String。
    //  非流式方法要做 usage 的缓存换算（Anthropic input_tokens 不含缓存、
    //  OpenAI prompt_tokens 含缓存，第 9 节），并回显下游带前缀的模型名（第 7 节）。
    //
    //  两侧翻译都必须在重试边界之外：判定与重试用的是上游原生形态，
    //  若翻译发生在 retryWhen 内侧，AnthropicContentDetector 看到的就是合成出来的
    //  OpenAI chunk，而它的取值路径是照 Anthropic 的 content[] 结构写的，
    //  会把每一轮都判成空并耗尽预算（第 12 节）。

    /** 本翻译器接受的下游协议。 */
    WireProtocol downstreamProtocol();

    /** 本翻译器对接的上游协议。 */
    WireProtocol upstreamProtocol();
}

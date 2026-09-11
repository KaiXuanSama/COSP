package com.kaixuan.copilot_ollama_proxy.application.protocol;

/**
 * 对话接口的<strong>线路协议</strong>（wire protocol）。
 *
 * <p>标识一次调用在某一侧使用的报文格式，而非某个具体供应商或端点路径：
 * <ul>
 *   <li>{@link #CHAT} —— Chat Completions API（{@code /v1/chat/completions}），
 *       请求体 {@code messages} 含 system 条目，响应为单一 chunk 序列 + {@code [DONE]}；</li>
 *   <li>{@link #MESSAGES} —— Anthropic API（{@code /v1/messages}），
 *       {@code system} 为顶层字段、{@code max_tokens} 必填，响应为带 {@code event:} 类型的事件流。</li>
 * </ul>
 *
 * <h2>常量名取自 API 路径全称，不含厂商名</h2>
 * 曾经叫 {@code OPENAI} / {@code ANTHROPIC}，V12 改成现在的名字。旧名有个会随功能增长
 * 而暴露的缺陷：{@code OPENAI} 同时承载「OpenAI 这家公司」与「Chat Completions 这个接口」
 * 两种含义。两个协议时无害（OpenAI 名下只有一个接口在用），但 OpenAI 的 <b>Responses
 * API</b> 同样属于 OpenAI —— 一旦要接它，{@code OPENAI} 指代 Chat 就自相矛盾了。
 *
 * <p>故三个名字统一取自各自的 API 路径全称，都指「接口」这一个维度。展示名仍用官方叫法
 * （{@code MESSAGES} 显示为「Anthropic API」），那是给人看的，与常量名的职责不同。
 *
 * <h2>为何是「两侧各有一个」而不是全局一个</h2>
 * 下游用哪种协议由它打的端点决定，上游用哪种由供应商配置决定，两者相互独立。
 * 同名可直连，跨协议需要翻译 —— 这个判断由 {@link ProtocolDispatchManager} 完成，
 * 本枚举只负责把「哪一侧说哪种话」表达出来。
 *
 * <p><strong>声明顺序不承载语义。</strong>{@code ProtocolDispatchManager} 当前遍历
 * {@code values()} 挑翻译目标，两个协议下结果唯一（除下游协议外只剩一个候选），
 * 但那是巧合。加入第三种协议后必须改用独立声明的回退序常量，否则「挑哪个上游协议」
 * 会变成受本文件常量书写顺序摆布的隐式行为。
 *
 * <p>刻意不叫 {@code ApiFormat}：历史上 {@code provider_config.api_format} 列在 V8.1 被删除，
 * 那个字段承载的是「供应商属于哪一家」的语义（已由 {@code provider_key} 取代），
 * 与此处「报文格式」不是一回事，复用旧名会让人误以为要把那一列加回来。
 */
public enum WireProtocol {

    /** Chat Completions API 格式（{@code /v1/chat/completions}）。 */
    CHAT,

    /** Anthropic API 格式（{@code /v1/messages}）。 */
    MESSAGES
}

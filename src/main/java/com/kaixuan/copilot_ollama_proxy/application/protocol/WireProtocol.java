package com.kaixuan.copilot_ollama_proxy.application.protocol;

/**
 * 对话接口的<strong>线路协议</strong>（wire protocol）。
 *
 * <p>标识一次调用在某一侧使用的报文格式，而非某个具体供应商或端点路径：
 * <ul>
 *   <li>{@link #OPENAI} —— OpenAI Chat Completions 格式（{@code /v1/chat/completions}），
 *       请求体 {@code messages} 含 system 条目，响应为单一 chunk 序列 + {@code [DONE]}；</li>
 *   <li>{@link #ANTHROPIC} —— Anthropic Messages 格式（{@code /v1/messages}），
 *       {@code system} 为顶层字段、{@code max_tokens} 必填，响应为带 {@code event:} 类型的事件流。</li>
 * </ul>
 *
 * <h2>为何是「两侧各有一个」而不是全局一个</h2>
 * 下游用哪种协议由它打的端点决定，上游用哪种由供应商配置决定，两者相互独立。
 * 四种组合中同名两种可直连，跨协议两种需要翻译 —— 这个判断由
 * {@link ProtocolDispatchManager} 完成，本枚举只负责把「哪一侧说哪种话」表达出来。
 *
 * <p>刻意不叫 {@code ApiFormat}：历史上 {@code provider_config.api_format} 列在 V8.1 被删除，
 * 那个字段承载的是「供应商属于哪一家」的语义（已由 {@code provider_key} 取代），
 * 与此处「报文格式」不是一回事，复用旧名会让人误以为要把那一列加回来。
 */
public enum WireProtocol {

    /** OpenAI Chat Completions 格式。 */
    OPENAI,

    /** Anthropic Messages 格式。 */
    ANTHROPIC
}

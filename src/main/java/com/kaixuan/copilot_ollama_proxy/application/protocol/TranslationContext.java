package com.kaixuan.copilot_ollama_proxy.application.protocol;

/**
 * 请求翻译产生的上下文，供响应侧消费。
 *
 * <p>响应翻译可以后做，但请求翻译不能做成完全无状态的一次性变换。
 * 响应侧需要知道请求侧发生过什么，才能正确地把上游响应转回下游协议。
 *
 * <h2>当前字段</h2>
 * <ul>
 *   <li>{@code wasStream}：下游要的是流式还是非流式</li>
 * </ul>
 *
 * <h2>将来可能需要的字段（响应侧实现时再补）</h2>
 * <ul>
 *   <li>下游有没有 {@code stream_options.include_usage}</li>
 *   <li>工具是否被重命名过（若引入名称改写）</li>
 *   <li>思考是谁打开的——下游要求的还是设置层注入的</li>
 * </ul>
 *
 * @param wasStream 下游请求的是流式还是非流式
 */
public record TranslationContext(boolean wasStream) {

    /**
     * 从下游请求体读取流式标志。
     *
     * @param downstreamBody 下游请求体
     * @return 上下文
     */
    public static TranslationContext fromDownstreamBody(Object downstreamBody) {
        if (downstreamBody instanceof java.util.Map<?, ?> map) {
            Object streamValue = map.get("stream");
            boolean wasStream = streamValue instanceof Boolean bool && bool;
            return new TranslationContext(wasStream);
        }
        return new TranslationContext(false);
    }
}

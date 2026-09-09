package com.kaixuan.copilot_ollama_proxy.application.protocol;

import java.util.Map;

/**
 * 请求翻译产生的上下文，供响应侧消费。
 *
 * <p>响应翻译需要知道请求侧发生过什么，才能正确地把上游响应转回下游协议。
 * 若请求翻译只返回一个 Map，响应侧就只能靠猜或二次解析请求体。
 *
 * <h2>字段的取值时机</h2>
 * 两个字段都读<strong>下游原始请求</strong>，不是翻译后的形态：
 * <ul>
 *   <li>{@code wasStream} —— 翻译后的 {@code stream} 会被上游服务按调用入口覆盖
 *       （{@code messages} 恒 false、{@code messagesStream} 恒 true），
 *       因此只有下游原始值才反映「下游到底要什么」。</li>
 *   <li>{@code includeUsage} —— {@code stream_options} 是 OpenAI 专有字段，
 *       翻译时会被丢弃（Anthropic 的 SSE 自带 usage），所以必须在丢弃前记下来。</li>
 * </ul>
 *
 * <h2>将来可能需要的字段</h2>
 * <ul>
 *   <li>工具是否被重命名过（若引入名称改写）</li>
 *   <li>思考是谁打开的——下游要求的还是设置层注入的
 *       （注入的那部分要不要回填给下游是个产品决定）</li>
 * </ul>
 *
 * @param wasStream    下游请求的是流式还是非流式
 * @param includeUsage 下游是否要求在流式响应里附带 usage
 */
public record TranslationContext(boolean wasStream, boolean includeUsage) {

    /**
     * 从下游请求体提取上下文。
     *
     * @param downstreamBody 下游请求体；非 Map 时返回全 false 的保守默认值
     * @return 上下文
     */
    public static TranslationContext fromDownstreamBody(Object downstreamBody) {
        if (!(downstreamBody instanceof Map<?, ?> map)) {
            return new TranslationContext(false, false);
        }
        return new TranslationContext(readStream(map), readIncludeUsage(map));
    }

    private static boolean readStream(Map<?, ?> body) {
        return body.get("stream") instanceof Boolean bool && bool;
    }

    /**
     * 读 {@code stream_options.include_usage}。
     *
     * <p>缺失时返回 false 而非 true：多发一个下游没要的 usage chunk 会让严格的客户端
     * 解析失败，而少发只是拿不到统计。保守的方向是不发。
     *
     * <p>这与 OpenAI 官方默认行为一致 —— 该字段不存在时不附带 usage。
     */
    private static boolean readIncludeUsage(Map<?, ?> body) {
        if (!(body.get("stream_options") instanceof Map<?, ?> options)) {
            return false;
        }
        return options.get("include_usage") instanceof Boolean bool && bool;
    }
}

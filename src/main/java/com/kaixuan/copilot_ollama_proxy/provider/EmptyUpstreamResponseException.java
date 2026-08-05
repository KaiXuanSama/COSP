package com.kaixuan.copilot_ollama_proxy.provider;

import java.util.List;

/**
 * 上游返回<strong>空响应</strong>的信号异常。
 *
 * <p>「空响应」指一轮上游往返里，从未出现过任何带实质载荷的 delta ——
 * 既没有正文（{@code content}）、也没有思考链（{@code reasoning_content} 及其兼容字段）、
 * 也没有工具调用（{@code tool_calls}）。第三方中转站偶发此类响应：HTTP 200、
 * 甚至连收尾 {@code [DONE]} 与 usage 都没有，直接透传给下游会让 Copilot 侧看到一次空回复。
 *
 * <h2>为何是异常</h2>
 * 做成异常纯粹为了<strong>复用 {@code retryWhen} 的重试预算</strong>：
 * {@code retryWhen} 只认异常，不关心异常由谁造。把「空响应」包成异常抛出，
 * 它就与 429 / 5xx / 网络中断走完全同一条重试路径，共享同一份次数与退避配置。
 * 未来把重试次数做成可配置时只需改 {@code buildRetrySpec} 一处，两类失败同时生效。
 *
 * <h2>为何携带帧</h2>
 * 空响应轮次的原始帧在判定前被 gate 拦下缓存，未流向下游。异常带上这份缓存有两个用途：
 * <ul>
 *   <li><strong>重试耗尽后放行</strong> —— provider 层解包出本异常，把最后一轮的帧
 *       原样发给下游，行为与其他失败「耗尽后透传最后一次响应」保持一致；</li>
 *   <li><strong>落库排查</strong> —— 否则日志里只剩「200 且零 chunk」，
 *       恰恰在最需要看上游到底吐了什么的场景下什么都看不到。</li>
 * </ul>
 *
 * <p>不继承 {@code WebClientResponseException}：本异常不代表 HTTP 层失败
 * （状态码通常是 200），混进去会让 {@code findWebResponseException} 的解包链
 * 误把它当成上游错误响应透传状态码。
 */
public class EmptyUpstreamResponseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 被 gate 拦下的原始 SSE data 帧，按到达顺序；可能为空列表（上游 0 帧）。 */
    private final List<String> bufferedFrames;

    /**
     * @param bufferedFrames 本轮被拦截的原始帧，按到达顺序；空 body 场景传空列表
     */
    public EmptyUpstreamResponseException(List<String> bufferedFrames) {
        super("上游返回空响应：无正文、无思考链、无工具调用（拦截帧数 "
                + (bufferedFrames == null ? 0 : bufferedFrames.size()) + "）");
        this.bufferedFrames = bufferedFrames == null ? List.of() : List.copyOf(bufferedFrames);
    }

    /** 本轮被拦截的原始帧（不可变）。重试耗尽时用于放行给下游，以及落库排查。 */
    public List<String> bufferedFrames() {
        return bufferedFrames;
    }

    /**
     * 信号异常不需要栈轨迹：它不表示代码缺陷，只是一次上游行为的分类结果，
     * 每轮空响应都填栈会在高频重试时造成无谓开销。
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}

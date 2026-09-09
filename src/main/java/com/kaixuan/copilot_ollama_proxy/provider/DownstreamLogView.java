package com.kaixuan.copilot_ollama_proxy.provider;

import java.util.List;
import java.util.function.Function;

/**
 * 上游服务落库时使用的「下游视图」。
 *
 * <h2>解决的问题</h2>
 * 落库发生在上游服务内部，而协议翻译套在它外侧。默认情况下日志记的是<strong>上游原生形态</strong>
 * 与「上下游协议相同」的假设，这对直连成立，对翻译路线则会记出两个错误的事实：
 * <ul>
 *   <li>协议列写成 {@code ANTHROPIC → ANTHROPIC}，看不出这是一次跳协议调用</li>
 *   <li>chunk 记的是 Anthropic 事件，而下游实际收到的是 OpenAI chunk ——
 *       排查「客户端为什么解析失败」时日志里没有客户端真正看到的东西</li>
 * </ul>
 *
 * <p>让翻译器把这两件事作为一个整体注入进来，上游服务就不必知道翻译存在，
 * 也不必为「有没有翻译」写分支。
 *
 * <h2>usage 不在本类的职责内</h2>
 * 本类曾有第三个成员 {@code usageRewriter}，用于把 A2O 的 usage 换算成下游口径。
 * 它已被删除：那个换算（把 {@code cache_read} 加回输入）<strong>只依赖上游协议</strong>，
 * 与下游是谁无关，因此已上提到 {@code AnthropicUsageParser.toTokens} ——
 * Anthropic 直连与 A2O 现在拿到的是同一个口径。
 *
 * <p>放在本类的旧做法只修了翻译路线，直连路线继续落不含缓存的值，
 * 于是 {@code api_call_usage.prompt_tokens} 一列承载两种定义，而汇总查询
 * 无法按行区分协议。不要把它加回来。
 *
 * <h2>为何不把落库整体移到翻译层外面</h2>
 * 落库需要请求头、响应头、状态码、TTFB 与每一轮的起止时间，这些只有上游服务持有；
 * 而重试是在服务内部发生的，每一轮都要落一条。把落库上移意味着把这一整套观测数据
 * 也上移，代价远大于注入一个改写器。
 *
 * @param downstreamProtocol 下游协议标识，写入 {@code api_call_log.downstream_protocol}
 * @param chunkRewriter      把上游原生 chunk 列表改写成下游实际收到的形态，并给出
 *                           逐事件产帧数；{@code null} 表示不改写（直连路线与跟协议非流式）
 */
public record DownstreamLogView(
        String downstreamProtocol,
        Function<List<String>, ChunkLogPayload> chunkRewriter) {

    /**
     * 直连视图：下游协议与上游一致，chunk 不改写。
     *
     * @param protocol 两侧共用的协议标识
     */
    public static DownstreamLogView direct(String protocol) {
        return new DownstreamLogView(protocol, null);
    }

    /**
     * 只换协议标识、不改写 chunk 的视图，用于<strong>跟协议非流式</strong>。
     *
     * <p>非流式不需要 chunk 改写：响应体是单一字符串，日志里记上游原文比记翻译后的
     * 更有用 —— 后者可以由前者推导，反之不行。但协议列必须写下游的值，
     * 否则日志里看不出这是一次跳协议调用。
     *
     * @param downstreamProtocol 下游协议标识
     */
    public static DownstreamLogView protocolOnly(String downstreamProtocol) {
        return new DownstreamLogView(downstreamProtocol, null);
    }

    /**
     * 产出落库用的 chunk 载荷。
     *
     * <p>直连时只有一份（裸数组）；跟协议时两份加对齐信息都留（对象形态），
     * 因为排查「客户端为何解析失败」需要同时看上游发了什么与客户端收到了什么。
     *
     * <p>改写器抛异常时退回直连形态：日志是观测手段，不该因为改写失败而丢掉
     * 「上游到底返回了什么」这个更基础的事实。
     */
    public ChunkLogPayload viewChunks(List<String> upstreamChunks) {
        if (chunkRewriter == null || upstreamChunks == null) {
            return ChunkLogPayload.direct(upstreamChunks);
        }
        try {
            ChunkLogPayload rewritten = chunkRewriter.apply(upstreamChunks);
            return rewritten == null ? ChunkLogPayload.direct(upstreamChunks) : rewritten;
        } catch (Exception exception) {
            return ChunkLogPayload.direct(upstreamChunks);
        }
    }
}

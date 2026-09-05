package com.kaixuan.copilot_ollama_proxy.provider;

import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;

import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * 上游服务落库时使用的「下游视图」。
 *
 * <h2>解决的问题</h2>
 * 落库发生在上游服务内部，而协议翻译套在它外侧。默认情况下日志记的是<strong>上游原生形态</strong>
 * 与「上下游协议相同」的假设，这对直连成立，对翻译路线则会记出三个错误的事实：
 * <ul>
 *   <li>协议列写成 {@code ANTHROPIC → ANTHROPIC}，看不出这是一次跳协议调用</li>
 *   <li>chunk 记的是 Anthropic 事件，而下游实际收到的是 OpenAI chunk ——
 *       排查「客户端为什么解析失败」时日志里没有客户端真正看到的东西</li>
 *   <li>usage 记的是上游口径，而 {@code api_call_usage} 的三个 token 列是
 *       <strong>跨协议共用</strong>的度量列 —— 混入另一种口径会让缓存占比与
 *       概览页的求和都失去意义，见 {@link #viewUsage}</li>
 * </ul>
 *
 * <p>让翻译器把这三件事作为一个整体注入进来，上游服务就不必知道翻译存在，
 * 也不必为「有没有翻译」写分支。
 *
 * <h2>为何不把落库整体移到翻译层外面</h2>
 * 落库需要请求头、响应头、状态码、TTFB 与每一轮的起止时间，这些只有上游服务持有；
 * 而重试是在服务内部发生的，每一轮都要落一条。把落库上移意味着把这一整套观测数据
 * 也上移，代价远大于注入两个改写器。
 *
 * @param downstreamProtocol 下游协议标识，写入 {@code api_call_log.downstream_protocol}
 * @param chunkRewriter      把上游原生 chunk 列表改写成下游实际收到的形态，并给出
 *                           逐事件产帧数；{@code null} 表示不改写（直连路线）
 * @param usageRewriter      把上游口径的 token 指标换算成下游口径；
 *                           {@code null} 表示不换算（直连路线，两侧口径本就一致）
 */
public record DownstreamLogView(
        String downstreamProtocol,
        Function<List<String>, ChunkLogPayload> chunkRewriter,
        UnaryOperator<UsageTokens> usageRewriter) {

    /**
     * 直连视图：下游协议与上游一致，chunk 与 usage 都不改写。
     *
     * @param protocol 两侧共用的协议标识
     */
    public static DownstreamLogView direct(String protocol) {
        return new DownstreamLogView(protocol, null, null);
    }

    /**
     * 只换算 usage、不改写 chunk 的视图，用于<strong>跨协议非流式</strong>。
     *
     * <p>非流式不需要 chunk 改写：响应体是单一字符串，日志里记上游原文比记翻译后的
     * 更有用 —— 后者可以由前者推导，反之不行。而 usage 必须换算，因为那三列是
     * 跨协议共用的度量列，不是原始报文的副本。
     *
     * @param downstreamProtocol 下游协议标识
     * @param usageRewriter      上游口径 → 下游口径的换算
     */
    public static DownstreamLogView usageOnly(String downstreamProtocol,
                                              UnaryOperator<UsageTokens> usageRewriter) {
        return new DownstreamLogView(downstreamProtocol, null, usageRewriter);
    }

    /**
     * 产出落库用的 chunk 载荷。
     *
     * <p>直连时只有一份（裸数组）；跨协议时两份加对齐信息都留（对象形态），
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

    /**
     * 产出落库用的 token 指标。
     *
     * <h2>为何 usage 必须按下游口径落库</h2>
     * {@code api_call_usage} 的 {@code prompt_tokens / completion_tokens / cached_tokens}
     * 三列是<strong>跨协议共用</strong>的度量列，不是原始报文的副本（原始报文另存于
     * {@code usage_raw}）。但两种协议对「输入 token」的定义不同：
     *
     * <pre>
     * OpenAI    prompt_tokens  含缓存
     * Anthropic input_tokens   不含缓存（cache_read / cache_creation 另计）
     * </pre>
     *
     * 直连时上下游口径一致，落上游原样即可。跨协议时若仍落上游口径，会同时坏掉两件事：
     * <ul>
     *   <li>缓存占比 —— 前端按下游协议解读这一列（那才是用户在客户端里看到的数），
     *       A2O 落 Anthropic 口径会让分母小一到两个数量级，比率冲到几万个百分点；</li>
     *   <li>概览页求和 —— 四处 {@code SUM(prompt_tokens)} 把含缓存与不含缓存的值
     *       加在一起，结果不对应任何真实量。</li>
     * </ul>
     *
     * <p>换算器抛异常时退回上游原样：宁可记一个口径可疑的数，也不要丢掉整行用量 ——
     * 那会让这次调用在概览页彻底消失。
     *
     * @param upstreamTokens 上游口径的指标；{@code null} 视为空
     */
    public UsageTokens viewUsage(UsageTokens upstreamTokens) {
        UsageTokens safe = upstreamTokens == null ? UsageTokens.EMPTY : upstreamTokens;
        if (usageRewriter == null) {
            return safe;
        }
        try {
            UsageTokens rewritten = usageRewriter.apply(safe);
            return rewritten == null ? safe : rewritten;
        } catch (Exception exception) {
            return safe;
        }
    }
}

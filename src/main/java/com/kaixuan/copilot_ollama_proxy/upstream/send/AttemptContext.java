package com.kaixuan.copilot_ollama_proxy.upstream.send;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次上游<strong>往返尝试</strong>的流级状态 —— 主干后半段（阶段 4 刀 2）的落点。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：主干<strong>流级状态容器</strong> · 位置：{@code upstream/send/}
 * 步骤「发送」的内层 —— 每次 {@code Flux.defer} 重订阅新建一个，承载「这一轮读到哪了」
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 *
 * <h2>它为什么存在（方向文档 §2.4 的「流级状态」有了家）</h2>
 * {@link com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext} 装的是
 * <strong>请求级</strong>事实（route、两协议、headers），整条主干共用、重试<strong>不</strong>重置。
 * 而计时、chunk 收集、首字延迟、耗尽放行标记这些是<strong>流级</strong>累积量：
 * 重试一次就得清零，且只在一次往返内有意义。它们此前没有家 —— 三个执行器各自在方法体里声明
 * 一堆 {@code AtomicLong} / {@code CopyOnWriteArrayList} 局部变量，正是这些闭包把主干后半段
 * 锁死在执行器里（{@code Flux.defer} / {@code retryWhen} 必须与它们写在同一个方法体）。
 *
 * <p>给它们一个显式的家之后，主干（{@code UpstreamCallRunner}）才能在自己的方法里组装内层链，
 * 执行器退化成把协议特定闭包交出去的薄适配器。这是阶段 4 §4.2 认定的刀 2 前置。
 *
 * <h2>三条线路的字段集相同（故可通用）</h2>
 * 三个执行器的流级状态<strong>逐字段一致</strong>（都有这六项）—— 它们本就是协议无关的骨架，
 * 差异只在协议特定的累积量（Chat 的 {@code contentEmitted} / {@code reasoningBuffer} /
 * {@code chunkId}，Anthropic 的 {@code usageAccumulator} / {@code archivedUsageRaw}，
 * 各家的 {@code usageRaw}）。<strong>那些不进本类</strong>，仍由各执行器的闭包持有 ——
 * 它们随协议而异，塞进来会让本类变成大杂烩。
 *
 * <h2>它是一次往返一个实例（不是执行器的字段）</h2>
 * 每次调用在方法体内 {@code new} 一个 —— 与它替换的那些局部变量同生命周期。
 * <strong>绝不能</strong>做成执行器的成员字段：那会让并发调用共享同一份状态。
 * {@link #resetForAttempt()} 在 {@code Flux.defer} <strong>内</strong>调用（每轮重订阅重置），
 * 而实例本身在 {@code defer} <strong>外</strong>创建（请求级）—— 与
 * {@link com.kaixuan.copilot_ollama_proxy.upstream.EmptyResponseGate} 的「外部创建、defer 内 reset」
 * 同一节奏。
 *
 * <h2>为何仍用 Atomic / CopyOnWriteArrayList</h2>
 * SSE 流的算子回调可能跨线程执行，字段的写入必须对后续读取可见。这些类型此前就在三个执行器里
 * 保证了这一点；搬进本类只是<strong>把已经在用的东西换个家</strong>，线程语义逐字保留，
 * 不是新引入的并发设计。
 */
public final class AttemptContext {

    /** 本轮往返起点（毫秒）。每轮 {@code defer} 内 {@link #resetForAttempt()} 刷新，使每条日志只反映该次往返。 */
    private final AtomicLong attemptStartMs = new AtomicLong(System.currentTimeMillis());

    /** 本轮清洗后 / 原始 chunk。每轮起点清空，成功收尾落库时读取。流式专用；非流式为空。 */
    private final List<String> logChunks = new CopyOnWriteArrayList<>();

    /** 本轮上游响应头快照。{@code exchangeToFlux} 收到响应头时写入，落库时读取。 */
    private volatile Map<String, String> respHeaders = Map.of();

    /**
     * 本轮上游状态码。初值 {@code 0} 表示「尚未收到任何响应头」——
     * 网络类失败（连不上、握手失败）走不到 {@code exchangeToFlux}，此值保持 0，
     * 落库时据此转成占位值 {@code -1}。此语义靠初值成立，不要改初值。
     */
    private volatile int statusCode = 0;

    /**
     * 首字响应时长（毫秒），{@code -1} 表示尚未测得。语义是「首 <em>chunk</em>」而非「首正文」——
     * 纯思考、纯工具调用等无正文响应同样能测得。每轮起点重置。
     */
    private final AtomicLong ttfbMs = new AtomicLong(-1);

    /**
     * 空响应耗尽放行标记。耗尽后 {@code onErrorResume} 已用缓存帧落过库，
     * {@code doFinally} 据此跳过、避免同一轮记两条。一次性置位，不重置。
     */
    private volatile boolean emptyResponsePassthrough = false;

    /**
     * 每轮往返起点重置 —— 必须放在 {@code Flux.defer} 内。
     *
     * <p>只重置<strong>跨轮会污染</strong>的三项（计时、chunk 收集、首字延迟）。
     * {@link #respHeaders} / {@link #statusCode} 每轮在 {@code exchangeToFlux} 内被覆盖写，
     * {@link #emptyResponsePassthrough} 是耗尽那一轮（即最后一轮）的一次性标记 ——
     * 二者都不需要、也不应在此清零。
     */
    public void resetForAttempt() {
        attemptStartMs.set(System.currentTimeMillis());
        logChunks.clear();
        ttfbMs.set(-1);
    }

    /** 刷新往返起点。非流式没有 {@code logChunks} / ttfb 可清，故单独提供而不走 {@link #resetForAttempt()}。 */
    public void markAttemptStart() {
        attemptStartMs.set(System.currentTimeMillis());
    }

    /** 本轮往返起点（毫秒）。 */
    public long attemptStart() {
        return attemptStartMs.get();
    }

    /** 记录一条 chunk（流式，每帧到达时）。 */
    public void addChunk(String data) {
        logChunks.add(data);
    }

    /** 直接持有的 chunk 列表 —— 成功收尾落库时传给 {@code saveStreamLog}（此时流已结束，无并发写）。 */
    public List<String> chunks() {
        return logChunks;
    }

    /** chunk 的不可变快照 —— 失败往返落库用（此时流可能仍在收尾，取快照避免并发修改）。 */
    public List<String> chunksSnapshot() {
        return List.copyOf(logChunks);
    }

    /** 本轮是否一条 chunk 都没有 —— 与状态码 0 合起来判定「该转占位值 -1」。 */
    public boolean noChunks() {
        return logChunks.isEmpty();
    }

    /** 收到上游响应头时写入头快照与状态码（二者总是一起写）。 */
    public void captureResponse(Map<String, String> headers, int status) {
        this.respHeaders = headers;
        this.statusCode = status;
    }

    /** 本轮上游响应头快照。 */
    public Map<String, String> respHeaders() {
        return respHeaders;
    }

    /** 本轮上游状态码（初值 0 = 未收到响应头）。 */
    public int statusCode() {
        return statusCode;
    }

    /**
     * 记录首字延迟 —— <strong>仅首次生效</strong>（后续 chunk 不改）。
     *
     * <p>语义是「首 chunk」：只要上游吐了帧就算测得，不因该帧被空响应 gate 暂扣而延后
     * （调用点在 gate 之前）。
     */
    public void recordFirstByteIfAbsent() {
        if (ttfbMs.get() < 0) {
            ttfbMs.set(System.currentTimeMillis() - attemptStartMs.get());
        }
    }

    /** 首字延迟（毫秒），{@code -1} 表示未测得。 */
    public long ttfb() {
        return ttfbMs.get();
    }

    /** 置位空响应耗尽放行标记（onErrorResume 里，那一轮已落库）。 */
    public void markEmptyResponsePassthrough() {
        emptyResponsePassthrough = true;
    }

    /** 本轮是否为空响应耗尽放行 —— {@code doFinally} 据此跳过重复落库。 */
    public boolean isEmptyResponsePassthrough() {
        return emptyResponsePassthrough;
    }
}

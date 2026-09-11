package com.kaixuan.copilot_ollama_proxy.protocol.lifecycle;

/**
 * 单次调用生命周期事件 DTO.
 *
 * <p>由控制器在调用链的各观测点发出，经 {@code CallLifecyclePublisher} 推送到
 * SSE {@code /config/api/calls/stream}，前端据此按 {@link #requestId} 分组渲染 Toast.
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@link #requestId} —— 本次调用的唯一标识（每条 Reactor 订阅链一个），
 *       前端以此把同一次调用的多次状态流转对应到同一个 Toast；</li>
 *   <li>{@link #phase} —— 当前阶段，见 {@link CallPhase}；</li>
 *   <li>{@link #model} —— 请求的模型名（含前缀，供 Toast 展示是哪个调用）；</li>
 *   <li>{@link #stream} —— 是否流式请求，前端据此决定显示完整版还是简化版 Toast；</li>
 *   <li>{@link #chunkCount} —— 已产生的 chunk 数（CHUNK/COMPLETED 阶段有意义，其余为 0）；</li>
 *   <li>{@link #attempt} —— 重试次数（RETRYING 阶段有意义，表示即将进行的第几次重试，其余为 0）；</li>
 *   <li>{@link #downstreamProtocol} —— 下游协议名（{@code OPENAI} / {@code ANTHROPIC}），
 *       由控制器在 RECEIVED 时就已确定，见下方「协议信息为何分两步」；</li>
 *   <li>{@link #upstreamProtocol} —— 上游协议名，需等应用层完成路由与协议调度后才有值；</li>
 *   <li>{@link #timestamp} —— 事件产生时的服务端毫秒时间戳。</li>
 * </ul>
 *
 * <h2>协议信息为何分两步</h2>
 * 下游协议由「客户端打的哪个端点」决定，控制器发 RECEIVED 时即已知；上游协议却要等
 * {@code ProviderRouteResolver} 解析出供应商、{@code ProtocolDispatchManager} 判完
 * 「直连还是跨协议」才有结论 —— 那是同步含 JDBC 读的调用，不能在 event-loop 上做。
 * 故 RECEIVED 先只带下游协议（或干脆两个都不带），等应用层在 {@code Mono.defer} 内拿到
 * 调度结论后，再经 {@code CallLifecycleNotifier#recordProtocols} 补齐并由发布器改写重发。
 * 前端据此把两个字段当作<strong>从无到有的单向填充</strong>：补上后不再被后续事件改写。
 *
 * <p>类型用 {@code String} 而不是 {@code WireProtocol} 枚举：本包属于 {@code protocol} 层，
 * 不依赖 {@code application} 层。取值就是枚举的 {@code name()}，与
 * {@code api_call_log.protocol} 列存字面量同一口径。
 *
 * <p>事件<strong>不携带任何请求/响应正文</strong>，因此 SSE 端点 permitAll 不泄露数据。
 *
 * @param requestId          本次调用唯一标识
 * @param phase              生命周期阶段
 * @param model              模型名称（含前缀）
 * @param stream             是否流式请求
 * @param chunkCount         当前累计 chunk 数
 * @param attempt            重试次数（RETRYING 阶段有意义）
 * @param downstreamProtocol 下游协议名；null 表示尚未确定
 * @param upstreamProtocol   上游协议名；null 表示尚未确定（应用层补）
 * @param timestamp          服务端毫秒时间戳
 */
public record CallLifecycleEvent(
        String requestId,
        CallPhase phase,
        String model,
        boolean stream,
        int chunkCount,
        int attempt,
        String downstreamProtocol,
        String upstreamProtocol,
        long timestamp) {

    /** 构造一个不含计数的阶段事件（RECEIVED / CONNECTED / FAILED / CANCELED）。 */
    public static CallLifecycleEvent of(String requestId, CallPhase phase, String model, boolean stream) {
        return new CallLifecycleEvent(requestId, phase, model, stream, 0, 0, null, null,
                System.currentTimeMillis());
    }

    /** 构造一个带 chunk 计数的阶段事件（CHUNK / COMPLETED / CANCELED）。 */
    public static CallLifecycleEvent of(String requestId, CallPhase phase, String model, boolean stream, int chunkCount) {
        return new CallLifecycleEvent(requestId, phase, model, stream, chunkCount, 0, null, null,
                System.currentTimeMillis());
    }

    /** 构造一个 RETRYING 事件，携带即将进行的重试次数。 */
    public static CallLifecycleEvent retrying(String requestId, String model, boolean stream, int attempt) {
        return new CallLifecycleEvent(requestId, CallPhase.RETRYING, model, stream, 0, attempt, null, null,
                System.currentTimeMillis());
    }

    /**
     * 复制一个替换了模型名的事件，其余字段保持不变。
     *
     * <p>供 {@code CallLifecyclePublisher} 统一展示名：provider 层只知道<strong>剥掉供应商
     * 前缀后的上游模型名</strong>（它要用这个名字请求上游、写日志），而展示名应当是客户端
     * 原始请求名。前者不能反推出后者 —— {@code providerKey} 的大小写未必与客户端所写一致，
     * 且无前缀路由时本就不该补前缀。故展示名以调用的首个事件为准，由发布器统一改写。
     */
    public CallLifecycleEvent withModel(String model) {
        return new CallLifecycleEvent(requestId, phase, model, stream, chunkCount, attempt,
                downstreamProtocol, upstreamProtocol, timestamp);
    }

    /**
     * 复制一个补齐了协议信息的事件，其余字段（含时间戳与阶段）保持不变。
     *
     * <p>由 {@code CallLifecyclePublisher#recordProtocols} 在应用层给出调度结论后调用，
     * 再重发一次让前端补上路径标记 —— 时间戳不变是关键：前端用它作为「流存在时间」的
     * 起点，若重发时刷新时间戳，已经进行了一会儿的调用会显示成刚刚开始。
     *
     * @param downstreamProtocol 下游协议名；null 表示不改写该字段
     * @param upstreamProtocol   上游协议名；null 表示不改写该字段
     */
    public CallLifecycleEvent withProtocols(String downstreamProtocol, String upstreamProtocol) {
        return new CallLifecycleEvent(requestId, phase, model, stream, chunkCount, attempt,
                downstreamProtocol == null ? this.downstreamProtocol : downstreamProtocol,
                upstreamProtocol == null ? this.upstreamProtocol : upstreamProtocol,
                timestamp);
    }
}

package com.kaixuan.copilot_ollama_proxy.protocol.lifecycle;

/**
 * 单次调用生命周期事件 DTO。
 *
 * <p>由控制器在调用链的各观测点发出，经 {@code CallLifecyclePublisher} 推送到
 * SSE {@code /config/api/calls/stream}，前端据此按 {@link #requestId} 分组渲染 Toast。
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
 *   <li>{@link #timestamp} —— 事件产生时的服务端毫秒时间戳。</li>
 * </ul>
 *
 * <p>事件<strong>不携带任何请求/响应正文</strong>，因此 SSE 端点 permitAll 不泄露数据。
 *
 * @param requestId  本次调用唯一标识
 * @param phase      生命周期阶段
 * @param model      模型名称（含前缀）
 * @param stream     是否流式请求
 * @param chunkCount 当前累计 chunk 数
 * @param attempt    重试次数（RETRYING 阶段有意义）
 * @param timestamp  服务端毫秒时间戳
 */
public record CallLifecycleEvent(
        String requestId,
        CallPhase phase,
        String model,
        boolean stream,
        int chunkCount,
        int attempt,
        long timestamp) {

    /** 构造一个不含计数的阶段事件（RECEIVED / CONNECTED / FAILED / CANCELED）。 */
    public static CallLifecycleEvent of(String requestId, CallPhase phase, String model, boolean stream) {
        return new CallLifecycleEvent(requestId, phase, model, stream, 0, 0, System.currentTimeMillis());
    }

    /** 构造一个带 chunk 计数的阶段事件（CHUNK / COMPLETED / CANCELED）。 */
    public static CallLifecycleEvent of(String requestId, CallPhase phase, String model, boolean stream, int chunkCount) {
        return new CallLifecycleEvent(requestId, phase, model, stream, chunkCount, 0, System.currentTimeMillis());
    }

    /** 构造一个 RETRYING 事件，携带即将进行的重试次数。 */
    public static CallLifecycleEvent retrying(String requestId, String model, boolean stream, int attempt) {
        return new CallLifecycleEvent(requestId, CallPhase.RETRYING, model, stream, 0, attempt, System.currentTimeMillis());
    }
}

package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 调用统计变更事件发布器。
 *
 * <p>每当一次实际 Copilot 调用写库成功（{@link ApiUsageCollector#record}），
 * 就向内部 Sink 发出一个轻量信号；SSE 推送流订阅该信号后按需拉取最新统计快照，
 * 从而把“每 5 秒定时轮询”替换为“事件驱动推送 + 兜底刷新”。
 *
 * <p>信号本身不携带业务数据，只表示“统计发生了变化”，具体快照由订阅方重新查询，
 * 保证前端拿到的始终是当前最新状态，即使中途丢失个别信号也不会导致数据错误。
 *
 * <p>使用 {@code multicast().directBestEffort()}：
 * <ul>
 *   <li>支持多个前端同时订阅（多个概览页标签）；</li>
 *   <li>无订阅者时直接丢弃信号，不做无界缓冲，避免空转积压；</li>
 *   <li>信号丢失可被 SSE 流的定时兜底刷新覆盖，无正确性影响。</li>
 * </ul>
 */
@Component
public class UsageEventPublisher {

    /** 单一信号值，仅表示“统计已更新”，不承载数据。 */
    private static final Object SIGNAL = new Object();

    private final Sinks.Many<Object> sink = Sinks.many().multicast().directBestEffort();

    /** 发布一次统计变更信号（写库成功后调用）。 */
    public void publishUsageChanged() {
        sink.tryEmitNext(SIGNAL);
    }

    /** 订阅统计变更信号流。 */
    public Flux<Object> changes() {
        return sink.asFlux();
    }
}

package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 调用日志变更事件发布器。
 *
 * <p>每当一条调用日志写库成功（{@code ApiCallLogRepository} 的 save* 方法），
 * 就向内部 Sink 发出一个轻量信号；SSE 推送流订阅该信号后转发给前端，
 * 前端据此自动拉取最新日志列表，从而把“手动点刷新”替换为“事件驱动自动刷新”。
 *
 * <p>信号本身<strong>不携带任何日志数据</strong>，只表示“产生了新日志”。
 * 这样设计有两个好处：
 * <ul>
 *   <li>SSE 端点无需鉴权也不会泄露数据，前端仍走带 Bearer Token 的
 *       {@code /config/api/logs} 接口拉取实际内容；</li>
 *   <li>即使个别信号丢失也不会导致数据错误，前端下次拉取即可对齐。</li>
 * </ul>
 *
 * <p>使用 {@code multicast().directBestEffort()}：
 * <ul>
 *   <li>支持多个前端同时订阅（多个日志页标签）；</li>
 *   <li>无订阅者时直接丢弃信号，不做无界缓冲，避免空转积压。</li>
 * </ul>
 */
@Component
public class LogEventPublisher {

    /** 单一信号值，仅表示“有新日志”，不承载数据。 */
    private static final Object SIGNAL = new Object();

    private final Sinks.Many<Object> sink = Sinks.many().multicast().directBestEffort();

    /** 发布一次日志变更信号（写库成功后调用）。 */
    public void publishLogCreated() {
        sink.tryEmitNext(SIGNAL);
    }

    /** 订阅日志变更信号流。 */
    public Flux<Object> changes() {
        return sink.asFlux();
    }
}

package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 单次调用生命周期事件发布器。
 *
 * <p>控制器在调用链的各观测点（RECEIVED / CONNECTED / CHUNK / COMPLETED / FAILED）
 * 发出 {@link CallLifecycleEvent}，SSE 推送流订阅后转发给前端，前端按 requestId 分组渲染 Toast，
 * 用于观察每次 Copilot 调用是否卡住、上游是否有响应。
 *
 * <p>与 {@code UsageEventPublisher} / {@code LogEventPublisher} 不同，本发布器的信号
 * <strong>携带实际数据</strong>（阶段 + chunk 计数等），但仍不含任何请求/响应正文，
 * 因此 SSE 端点 permitAll 不泄露数据。
 *
 * <p>使用 {@code multicast().directBestEffort()}：
 * <ul>
 *   <li>支持多个前端同时订阅（多个管理页标签）；</li>
 *   <li>无订阅者时直接丢弃事件，不做无界缓冲，避免高频 chunk 事件积压；</li>
 *   <li>Toast 是即时观察用途，个别事件丢失无正确性影响（COMPLETED 会带最终计数对齐）。</li>
 * </ul>
 */
@Component
public class CallLifecyclePublisher {

    private final Sinks.Many<CallLifecycleEvent> sink = Sinks.many().multicast().directBestEffort();

    /** 发布一个生命周期事件。 */
    public void publish(CallLifecycleEvent event) {
        sink.tryEmitNext(event);
    }

    /** 订阅生命周期事件流。 */
    public Flux<CallLifecycleEvent> events() {
        return sink.asFlux();
    }
}

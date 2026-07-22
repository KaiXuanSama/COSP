package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <p><strong>进行中调用快照</strong>：由于 multicast sink 不重放历史事件，晚订阅的前端会错过
 * 之前已发生的阶段。为消除「前端打开前已发起、正卡在等待首字的调用永远不显示」的盲区，
 * 本类额外维护一份 {@code requestId -> 最新事件} 的 {@link #inFlight} map：非终态事件写入/更新，
 * 终态事件（COMPLETED/FAILED/CANCELED/ABORTED）移除。SSE 连接建立时先 {@link #snapshot} 补发，
 * 使新订阅者立即看到所有进行中的调用（包括卡死的，从而可被取消）。
 */
@Component
public class CallLifecyclePublisher implements CallLifecycleNotifier {

    private final Sinks.Many<CallLifecycleEvent> sink = Sinks.many().multicast().directBestEffort();

    /** 进行中调用的最新事件（按 requestId）；终态时移除，避免无界增长。 */
    private final Map<String, CallLifecycleEvent> inFlight = new ConcurrentHashMap<>();

    /** 发布一个生命周期事件，并同步维护进行中调用快照。 */
    @Override
    public void publish(CallLifecycleEvent event) {
        if (isTerminal(event.phase())) {
            inFlight.remove(event.requestId());
        } else {
            inFlight.put(event.requestId(), event);
        }
        sink.tryEmitNext(event);
    }

    /** 订阅实时生命周期事件流。 */
    public Flux<CallLifecycleEvent> events() {
        return sink.asFlux();
    }

    /**
     * 将某次进行中调用标记为「可手动取消」：取出 inFlight 中的当前事件，
     * 只翻转 {@code canCancel=true} 后重新发布，阶段与其余字段保持不变。
     *
     * <p>由后端看门狗在等待满阈值后调用（等首字超时 / 首字后停滞）。重新发布会
     * 覆盖写入 inFlight，因此此后任何新建立的 SSE 连接在快照补发时都能立即拿到
     * canCancel=true 的最新态——刷新浏览器或新开页面都能及时显示取消按钮。
     *
     * <p>若该调用已不在 inFlight（已终结或从未存在），则静默忽略：终态调用不应再放开取消。
     */
    public void markCancelable(String requestId) {
        CallLifecycleEvent current = inFlight.get(requestId);
        if (current == null || current.canCancel()) {
            return;
        }
        publish(current.asCancelable());
    }

    /**
     * 当前所有进行中调用的最新事件快照。
     *
     * <p>SSE 连接建立时先补发此快照，使新订阅者立即看到已在进行、尚未结束的调用，
     * 消除「打开前端前已发起的卡死请求永不显示」的盲区。终态调用不在其中（已移除）。
     */
    public Collection<CallLifecycleEvent> snapshot() {
        return List.copyOf(inFlight.values());
    }

    /** 终态阶段：调用已结束，应从进行中快照移除。 */
    private static boolean isTerminal(CallPhase phase) {
        return phase == CallPhase.COMPLETED || phase == CallPhase.FAILED
                || phase == CallPhase.CANCELED || phase == CallPhase.ABORTED;
    }
}

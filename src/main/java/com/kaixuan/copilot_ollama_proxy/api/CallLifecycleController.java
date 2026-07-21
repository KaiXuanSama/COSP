package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallCancellationRegistry;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/** 管理后台单次调用生命周期 SSE API，为前端 Toast 提供实时状态流。 */
@RestController
public class CallLifecycleController {

    /** SSE 心跳周期。空闲时下发注释帧保活，避免中间代理因超时断连。 */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final CallLifecyclePublisher callLifecyclePublisher;
    private final CallCancellationRegistry callCancellationRegistry;

    public CallLifecycleController(CallLifecyclePublisher callLifecyclePublisher,
                                   CallCancellationRegistry callCancellationRegistry) {
        this.callLifecyclePublisher = callLifecyclePublisher;
        this.callCancellationRegistry = callCancellationRegistry;
    }

    /**
     * 调用生命周期 SSE 流。
     *
     * <p>每次 Copilot 调用流经代理时，其 RECEIVED / CONNECTED / CHUNK / COMPLETED / FAILED
     * 各阶段事件都会通过此流推送，前端按 requestId 分组渲染 Toast。数据帧用 {@code event: call}
     * 标识；心跳用注释帧保活，前端可忽略。
     *
     * <p>连接建立时先补发一次进行中调用快照（{@link CallLifecyclePublisher#snapshot}），
     * 使晚打开前端的用户能立即看到已在进行、尚未结束的调用（含卡在等待首字的，从而可被取消），
     * 消除 multicast sink 不重放历史事件带来的观察盲区。快照用 {@code Flux.defer} 在订阅时求值，
     * 保证每个新订阅者拿到的是各自建立连接那一刻的最新状态。
     *
     * <p>事件不携带请求/响应正文，故该端点 permitAll 不泄露数据。
     */
    @GetMapping(value = "/config/api/calls/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<CallLifecycleEvent>> streamCalls() {
        // 快照在订阅时求值：先补发所有进行中调用的最新状态，再接实时流。
        Flux<ServerSentEvent<CallLifecycleEvent>> snapshot = Flux.defer(() ->
                Flux.fromIterable(callLifecyclePublisher.snapshot()))
                .map(event -> ServerSentEvent.builder(event).event("call").build());

        Flux<ServerSentEvent<CallLifecycleEvent>> live = callLifecyclePublisher.events()
                .map(event -> ServerSentEvent.builder(event).event("call").build());

        // 快照先行、实时流紧随（concat 保证顺序）；心跳单独 merge 进来保活。
        Flux<ServerSentEvent<CallLifecycleEvent>> data = Flux.concat(snapshot, live);

        Flux<ServerSentEvent<CallLifecycleEvent>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<CallLifecycleEvent>builder().comment("keep-alive").build());

        return Flux.merge(data, heartbeat);
    }

    /**
     * 主动取消一次正在进行的调用（管理后台在「上游迟迟不吐首字」时点击取消按钮）。
     *
     * <p>触发对应 requestId 的取消信号：chat 链随即中止，向下游回传 504 超时错误
     * （触发 Copilot 重试），并发出 {@code ABORTED} 生命周期事件。
     *
     * <p>与 SSE 流不同，本端点走认证（不在 permitAll），只有登录的管理员可取消调用。
     *
     * @param requestId 目标调用唯一标识
     * @return {@code canceled=true} 表示成功触发；{@code false} 表示该调用不存在（已完成或从未存在）
     */
    @PostMapping("/config/api/calls/{requestId}/cancel")
    public Mono<ResponseEntity<Map<String, Object>>> cancelCall(@PathVariable String requestId) {
        return Mono.fromSupplier(() -> {
            boolean canceled = callCancellationRegistry.cancel(requestId);
            return ResponseEntity.ok(Map.of("requestId", requestId, "canceled", canceled));
        });
    }
}

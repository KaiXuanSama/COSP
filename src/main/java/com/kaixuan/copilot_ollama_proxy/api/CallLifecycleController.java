package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

/** 管理后台单次调用生命周期 SSE API，为前端 Toast 提供实时状态流。 */
@RestController
public class CallLifecycleController {

    /** SSE 心跳周期。空闲时下发注释帧保活，避免中间代理因超时断连。 */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final CallLifecyclePublisher callLifecyclePublisher;

    public CallLifecycleController(CallLifecyclePublisher callLifecyclePublisher) {
        this.callLifecyclePublisher = callLifecyclePublisher;
    }

    /**
     * 调用生命周期 SSE 流。
     *
     * <p>每次 Copilot 调用流经代理时，其 RECEIVED / CONNECTED / CHUNK / COMPLETED / FAILED
     * 各阶段事件都会通过此流推送，前端按 requestId 分组渲染 Toast。数据帧用 {@code event: call}
     * 标识；心跳用注释帧保活，前端可忽略。
     *
     * <p>事件不携带请求/响应正文，故该端点 permitAll 不泄露数据。
     */
    @GetMapping(value = "/config/api/calls/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<CallLifecycleEvent>> streamCalls() {
        Flux<ServerSentEvent<CallLifecycleEvent>> data = callLifecyclePublisher.events()
                .map(event -> ServerSentEvent.builder(event).event("call").build());

        Flux<ServerSentEvent<CallLifecycleEvent>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<CallLifecycleEvent>builder().comment("keep-alive").build());

        return Flux.merge(data, heartbeat);
    }
}

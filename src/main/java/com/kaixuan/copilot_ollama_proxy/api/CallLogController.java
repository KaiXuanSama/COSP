package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.logging.CallLogQueryService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.SseConnectionGate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 管理后台调用日志 API。 */
@RestController
public class CallLogController {

    /** SSE 心跳周期。空闲时下发注释帧保活，避免中间代理因超时断连。 */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final CallLogQueryService callLogQueryService;
    private final SseConnectionGate sseConnectionGate;

    public CallLogController(CallLogQueryService callLogQueryService, SseConnectionGate sseConnectionGate) {
        this.callLogQueryService = callLogQueryService;
        this.sseConnectionGate = sseConnectionGate;
    }

    @GetMapping("/config/api/logs")
    public Mono<Map<String, Object>> listLogs(@RequestParam(value = "cursor", required = false) Long cursor,
                                               @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return callLogQueryService.listLogs(cursor, pageSize);
    }

    /**
     * 消费者视角：调用记录分页列表，每行附带 token 用量。
     *
     * <p>与 {@code /config/api/logs} 共用游标和分页响应格式，区别在于每行额外返回
     * {@code prompt_tokens / completion_tokens / cached_tokens / ttfb_ms}，
     * 前端无需为每行再发一次详情请求即可渲染完整的消费者视图。
     *
     * <p>主表为 api_call_log，token 用量以相关子查询附属，故失败调用、
     * 上游未返回 usage 的调用仍出现在列表中，用量列为 null（渲染为 —）。
     */
    @GetMapping("/config/api/usage-logs")
    public Mono<Map<String, Object>> listUsageLogs(@RequestParam(value = "cursor", required = false) Long cursor,
                                                    @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return callLogQueryService.listUsageLogs(cursor, pageSize);
    }

    @GetMapping("/config/api/logs/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> getLogDetail(@PathVariable long id) {
        return callLogQueryService.findLog(id)
                .map(log -> log.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build()));
    }

    /**
     * 日志变更信号 SSE 流，替代手动点刷新。
     *
     * <p>每产生一条新日志即下发一个 {@code event: log} 信号帧（body 为时间戳，仅作占位）；
     * 前端收到后走带 Bearer Token 的 {@code /config/api/logs} 自动拉取最新列表。
     * 信号不携带日志内容。心跳用注释帧保活，前端可忽略。
     *
     * <p>本端点走认证（不在 permitAll）；且受 {@link SseConnectionGate} 总连接数上限保护，
     * 超限时立即结束连接，避免长连接资源被无限占用。
     */
    @GetMapping(value = "/config/api/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Long>> streamLogs() {
        // 订阅时占用一个连接名额；超限则立即结束（不接入数据流），并保证不误释放他人名额。
        return Flux.defer(() -> {
            if (!sseConnectionGate.tryAcquire()) {
                return Flux.<ServerSentEvent<Long>>empty();
            }
            AtomicBoolean released = new AtomicBoolean(false);

            Flux<ServerSentEvent<Long>> data = callLogQueryService.streamLogEvents()
                    .map(timestamp -> ServerSentEvent.builder(timestamp).event("log").build());

            Flux<ServerSentEvent<Long>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                    .map(tick -> ServerSentEvent.<Long>builder().comment("keep-alive").build());

            return Flux.merge(data, heartbeat)
                    .doFinally(signal -> {
                        if (released.compareAndSet(false, true)) {
                            sseConnectionGate.release();
                        }
                    });
        });
    }
}
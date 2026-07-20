package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.logging.CallLogQueryService;
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

/** 管理后台调用日志 API。 */
@RestController
public class CallLogController {

    /** SSE 心跳周期。空闲时下发注释帧保活，避免中间代理因超时断连。 */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final CallLogQueryService callLogQueryService;

    public CallLogController(CallLogQueryService callLogQueryService) {
        this.callLogQueryService = callLogQueryService;
    }

    @GetMapping("/config/api/logs")
    public Mono<Map<String, Object>> listLogs(@RequestParam(value = "cursor", required = false) Long cursor,
                                               @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return callLogQueryService.listLogs(cursor, pageSize);
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
     * 信号不携带日志内容，故该端点 permitAll 不泄露数据。心跳用注释帧保活，前端可忽略。
     */
    @GetMapping(value = "/config/api/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Long>> streamLogs() {
        Flux<ServerSentEvent<Long>> data = callLogQueryService.streamLogEvents()
                .map(timestamp -> ServerSentEvent.builder(timestamp).event("log").build());

        Flux<ServerSentEvent<Long>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<Long>builder().comment("keep-alive").build());

        return Flux.merge(data, heartbeat);
    }
}
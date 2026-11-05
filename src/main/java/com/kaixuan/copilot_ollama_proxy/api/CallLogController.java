package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.logging.CallLogFilter;
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
import java.util.List;
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

    /**
     * 调用记录分页列表，每行附带 token 用量。
     *
     * <p>调用者视角与消费者视角共用本端点：前者只用元信息列，后者还用
     * {@code prompt_tokens / completion_tokens / cached_tokens / ttfb_ms}。
     * 曾经拆成两个端点，但它们的主表、游标语义、排序与分页格式完全相同，
     * 后者的列是前者的严格超集，合并后同一段逻辑只维护一份。
     *
     * <p>主表为 api_call_log，token 用量以 LEFT JOIN 附属，故失败调用、
     * 上游未返回 usage 的调用仍出现在列表中，用量列为 null（渲染为 —）。
     *
     * <p>检索参数全部可选，一个都不传时与引入检索前完全一致（语义见 {@link CallLogFilter}、ADR-0020）：
     * {@code from} / {@code to} 为 {@code yyyy-MM-ddTHH:mm:ss} 本地时间闭区间；{@code status} 可重复，
     * 取 {@code 2xx}/{@code 4xx}/{@code 5xx} 或状态码（含 {@code -1}）；{@code provider} / {@code model}
     * 配合 {@code providerMatch} / {@code modelMatch}（{@code exact} 默认或 {@code contains}）。
     * 游标语义不变，翻页时要带上与首屏相同的条件。参数非法返回 400 与 {@code { ok: false, message }}。
     */
    @GetMapping("/config/api/logs")
    public Mono<ResponseEntity<Map<String, Object>>> listLogs(
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "status", required = false) List<String> status,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "providerMatch", required = false) String providerMatch,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "modelMatch", required = false) String modelMatch) {
        CallLogFilter filter;
        try {
            filter = CallLogFilter.parse(from, to, status, provider, providerMatch, model, modelMatch);
        } catch (IllegalArgumentException e) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.<String, Object>of("ok", false, "message", e.getMessage())));
        }
        return callLogQueryService.listLogs(filter, cursor, pageSize).map(ResponseEntity::ok);
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
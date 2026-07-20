package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.usage.UsageQueryService;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.StatsSnapshot;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 管理后台调用统计 API。 */
@RestController
public class UsageQueryController {

    /** SSE 心跳周期。空闲时下发注释帧保活，避免中间代理因超时断连。 */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final UsageQueryService usageQueryService;

    public UsageQueryController(UsageQueryService usageQueryService) {
        this.usageQueryService = usageQueryService;
    }

    @GetMapping("/config/api/stats")
    public Mono<StatsSnapshot> apiStats() {
        return usageQueryService.getStats();
    }

    /**
     * 统计快照 SSE 推送流，替代前端定时轮询。
     *
     * <p>每次实际 Copilot 调用完成后即时推送最新快照，另有定时兜底覆盖跨天与丢帧。
     * 数据帧用 {@code event: stats} 标识；心跳用注释帧（{@code SSE.comment}）保活，前端可忽略。
     */
    @GetMapping(value = "/config/api/stats/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StatsSnapshot>> streamStats() {
        Flux<ServerSentEvent<StatsSnapshot>> data = usageQueryService.streamStats()
                .map(snapshot -> ServerSentEvent.builder(snapshot).event("stats").build());

        Flux<ServerSentEvent<StatsSnapshot>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<StatsSnapshot>builder().comment("keep-alive").build());

        return Flux.merge(data, heartbeat);
    }

    @GetMapping("/config/api/heatmap")
    public Mono<List<Map<String, Object>>> heatmapData(@RequestParam(defaultValue = "360") int days) {
        return usageQueryService.getHeatmap(days);
    }
}
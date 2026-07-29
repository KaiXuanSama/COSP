package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.usage.UsageQueryService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.SseConnectionGate;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.StatsSnapshot;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownRow;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTimelinePoint;
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
import java.util.concurrent.atomic.AtomicBoolean;

/** 管理后台调用统计 API。 */
@RestController
public class UsageQueryController {

    /** SSE 心跳周期。空闲时下发注释帧保活，避免中间代理因超时断连。 */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final UsageQueryService usageQueryService;
    private final SseConnectionGate sseConnectionGate;

    public UsageQueryController(UsageQueryService usageQueryService, SseConnectionGate sseConnectionGate) {
        this.usageQueryService = usageQueryService;
        this.sseConnectionGate = sseConnectionGate;
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
     *
     * <p>本端点走认证（不在 permitAll）；且受 {@link SseConnectionGate} 总连接数上限保护，
     * 超限时立即结束连接，避免长连接资源被无限占用。
     */
    @GetMapping(value = "/config/api/stats/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StatsSnapshot>> streamStats() {
        // 订阅时占用一个连接名额；超限则立即结束（不接入数据流），并保证不误释放他人名额。
        return Flux.defer(() -> {
            if (!sseConnectionGate.tryAcquire()) {
                return Flux.<ServerSentEvent<StatsSnapshot>>empty();
            }
            AtomicBoolean released = new AtomicBoolean(false);

            Flux<ServerSentEvent<StatsSnapshot>> data = usageQueryService.streamStats()
                    .map(snapshot -> ServerSentEvent.builder(snapshot).event("stats").build());

            Flux<ServerSentEvent<StatsSnapshot>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                    .map(tick -> ServerSentEvent.<StatsSnapshot>builder().comment("keep-alive").build());

            return Flux.merge(data, heartbeat)
                    .doFinally(signal -> {
                        if (released.compareAndSet(false, true)) {
                            sseConnectionGate.release();
                        }
                    });
        });
    }

    @GetMapping("/config/api/heatmap")
    public Mono<List<Map<String, Object>>> heatmapData(@RequestParam(defaultValue = "360") int days) {
        return usageQueryService.getHeatmap(days);
    }

    /**
     * 概览下钻柱状图的用量明细（日期 × 供应商 × 模型 × 调用次数）。
     *
     * <p>只提供最细粒度数据，三级视图（按天堆叠 / 单日各供应商 / 单供应商各模型）
     * 与 hover 明细都由前端从同一份结果 pivot 得出，下钻不再产生额外请求。
     *
     * @param days 回看天数，默认 7；服务层会钳制到 [1, 90]
     */
    @GetMapping("/config/api/usage-breakdown")
    public Mono<List<UsageBreakdownRow>> usageBreakdown(@RequestParam(defaultValue = "7") int days) {
        return usageQueryService.getUsageBreakdown(days);
    }

    /**
     * 下钻柱状图明细的 SSE 推送流，让柱状图与统计卡同步实时更新。
     *
     * <p>与 {@link #streamStats()} 由同一批调用事件驱动，因此不会出现「卡片已涨、柱子还旧」的错位。
     * 每帧下发完整明细列表（{@code event: breakdown}），前端整体替换即可，
     * 无需处理增量合并；帧内容未变时后端已用 {@code distinctUntilChanged} 抑制，不会空推。
     *
     * <p>本端点走认证，且同样受 {@link SseConnectionGate} 总连接数上限保护。
     *
     * @param days 回看天数，默认 7；服务层会钳制到 [1, 90]
     */
    @GetMapping(value = "/config/api/usage-breakdown/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<List<UsageBreakdownRow>>> streamUsageBreakdown(
            @RequestParam(defaultValue = "7") int days) {
        return Flux.defer(() -> {
            if (!sseConnectionGate.tryAcquire()) {
                return Flux.<ServerSentEvent<List<UsageBreakdownRow>>>empty();
            }
            AtomicBoolean released = new AtomicBoolean(false);

            Flux<ServerSentEvent<List<UsageBreakdownRow>>> data = usageQueryService.streamUsageBreakdown(days)
                    .map(rows -> ServerSentEvent.builder(rows).event("breakdown").build());

            Flux<ServerSentEvent<List<UsageBreakdownRow>>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                    .map(tick -> ServerSentEvent.<List<UsageBreakdownRow>>builder().comment("keep-alive").build());

            return Flux.merge(data, heartbeat)
                    .doFinally(signal -> {
                        if (released.compareAndSet(false, true)) {
                            sseConnectionGate.release();
                        }
                    });
        });
    }

    /**
     * 概览折线图的 token 用量时间线。
     *
     * <p>两种范围共用一个端点与一种 DTO：{@code range=7d} 一天一个点，
     * {@code range=1d} 每小时一个点（今日 5 点起算，共 25 个点使首尾都落在 05:00）。
     * 结构同构让前端切换范围时无需更换组件。
     *
     * <p>数据源与下钻柱状图相同（{@code api_call_usage}），因此折线与柱子口径一致、
     * 可以互相印证；也因此总量会略低于统计卡的全量口径。
     *
     * @param range 时间范围，{@code 1d} 为今日时段，其余按近 7 日处理
     */
    @GetMapping("/config/api/usage-timeline")
    public Mono<List<UsageTimelinePoint>> usageTimeline(
            @RequestParam(defaultValue = "7d") String range) {
        return usageQueryService.getUsageTimeline(range);
    }

    /**
     * token 用量时间线的 SSE 推送流。
     *
     * <p>与柱状图、统计卡由同一批调用事件驱动，三处视图同步刷新。今日时段范围另有一层收益：
     * 定时兜底会随时间推进「当前所在时段」，即使没有新调用，时间轴也会向前延伸。
     *
     * <p>每帧下发完整点位列表（{@code event: timeline}），前端整体替换；
     * 内容未变时后端已用 {@code distinctUntilChanged} 抑制。
     *
     * @param range 时间范围，{@code 1d} 为今日时段，其余按近 7 日处理
     */
    @GetMapping(value = "/config/api/usage-timeline/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<List<UsageTimelinePoint>>> streamUsageTimeline(
            @RequestParam(defaultValue = "7d") String range) {
        return Flux.defer(() -> {
            if (!sseConnectionGate.tryAcquire()) {
                return Flux.<ServerSentEvent<List<UsageTimelinePoint>>>empty();
            }
            AtomicBoolean released = new AtomicBoolean(false);

            Flux<ServerSentEvent<List<UsageTimelinePoint>>> data =
                    usageQueryService.streamUsageTimeline(range)
                            .map(points -> ServerSentEvent.builder(points).event("timeline").build());

            Flux<ServerSentEvent<List<UsageTimelinePoint>>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                    .map(tick -> ServerSentEvent.<List<UsageTimelinePoint>>builder().comment("keep-alive").build());

            return Flux.merge(data, heartbeat)
                    .doFinally(signal -> {
                        if (released.compareAndSet(false, true)) {
                            sseConnectionGate.release();
                        }
                    });
        });
    }
}
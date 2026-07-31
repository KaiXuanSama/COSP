package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.usage.UsageQueryService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.SseConnectionGate;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.StatsSnapshot;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownPage;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownRow;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageDailyPage;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageHourlyPoint;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageHourlySeries;
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
     * 按日期聚合的用量明细（日期 × 供应商 × 模型 × 次数 × token）。
     *
     * <p>同时服务两个视图：堆叠柱状图取次数并 pivot 出三级下钻，
     * 「近 7 日」折线取 token 并按日期求和。
     *
     * <p>本端点是图表流首帧的 HTTP 等价物，保留供将来切换到<strong>不含今日</strong>的
     * 历史区间时使用 —— 那种窗口的数据已固化，不需要实时流。
     *
     * @param days 回看天数，默认 7；服务层会钳制到 [1, 90]
     */
    @GetMapping("/config/api/usage-breakdown")
    public Mono<List<UsageBreakdownRow>> usageBreakdown(@RequestParam(defaultValue = "7") int days) {
        return usageQueryService.getUsageBreakdown(days);
    }

    /**
     * 可滑动窗口的用量明细分页 —— 概览翻看历史区间的入口。
     *
     * <p>窗口由宽度与向过去的偏移两个参数确定，故 {@code 1-7}、{@code 2-8} 这类
     * 滑动区间都能表达；{@code offset = 0} 时右端为今天。约束为
     * {@code size ∈ [7, 15]}、{@code offset ∈ [0, 15 - size]}，
     * 即窗口整体必须落在「最近 15 天」内。
     *
     * <p>越界参数<strong>钳制而非报错</strong>，因此响应体回带实际生效的窗口边界、
     * 翻页可用性与 {@code includesToday}。前端据 {@code startDate}/{@code endDate}
     * 补零横轴，据 {@code hasNewer}/{@code hasOlder} 决定翻页按钮禁用态，
     * 据 {@code includesToday} 决定是否需要接上 SSE 增量流 ——
     * 不含今天的窗口数据已固化，一次拉取即为终态。
     *
     * <p>与 {@link #usageBreakdown} 并存而非替换：那个端点仍是图表流首帧的
     * HTTP 等价物，语义是「最近 N 天」，窗口右端固定。
     *
     * @param size   窗口宽度（天），默认 7
     * @param offset 向过去偏移的天数，默认 0（右端为今天）
     */
    @GetMapping("/config/api/usage-breakdown/page")
    public Mono<UsageBreakdownPage> usageBreakdownPage(
            @RequestParam(defaultValue = "7") int size,
            @RequestParam(defaultValue = "0") int offset) {
        return usageQueryService.getUsageBreakdownPage(size, offset);
    }

    /**
     * 可滑动窗口的按日 token 用量 —— 「近 N 日」折线翻看历史区间的入口。
     *
     * <p>窗口参数与约束、越界钳制行为、响应体的窗口元信息均与
     * {@link #usageBreakdownPage} 完全一致，两者共用同一份窗口解析逻辑。
     * 因此同样的 {@code size}/{@code offset} 在两个端点上必然落到同一区间，
     * 前端可以用一份翻页状态同时驱动柱状图与折线。
     *
     * <p>与柱状图端点的差别只在数据形状：这里按日期分组（丢掉供应商与模型），
     * 且 {@code points} <strong>已按窗口补零</strong>，长度恒等于 {@code size} ——
     * 折线按点位等距绘制，跳过空日会让横轴不再是等距时间轴。
     *
     * @param size   窗口宽度（天），默认 7
     * @param offset 向过去偏移的天数，默认 0（右端为今天）
     */
    @GetMapping("/config/api/usage-daily/page")
    public Mono<UsageDailyPage> usageDailyPage(
            @RequestParam(defaultValue = "7") int size,
            @RequestParam(defaultValue = "0") int offset) {
        return usageQueryService.getUsageDailyPage(size, offset);
    }

    /**
     * 今日时段的整点 token 用量，窗口为 {@code [今日 05:00, 次日 05:00)}。
     *
     * <p>与 {@link #usageBreakdown} 同为图表流首帧的 HTTP 等价物，理由同上。
     * 只返回有数据的整点，补零与「尚未到来」的判定都由前端完成。
     */
    @GetMapping("/config/api/usage-hourly")
    public Mono<List<UsageHourlyPoint>> usageHourly() {
        return usageQueryService.getHourlyTokens();
    }

    /**
     * 指定某一天的时段 token 用量 —— 「今日时段」折线翻看某一天的入口。
     *
     * <p><strong>没有分页也没有窗口滑动</strong>：这个视图一次只看一天，请求参数就是
     * 那一天本身，没有「宽度」这个自由度。硬套 {@code size} + {@code offset}
     * 只会多出一个恒等于 1 的宽度参数。
     *
     * <h2>一天不是 0 点到 0 点</h2>
     * 窗口是 {@code [date 05:00, date+1 05:00)}。跨夜编码是常态，按自然日切分会把
     * 一次连续工作截成两段；凌晨 5 点基本落在活动最低谷，以它为界曲线才完整。
     * 因此 {@code date} 是窗口的<strong>锚定日</strong>，不等于窗口内所有点位的日期 ——
     * 次日 00:00 至 05:00 的点位属于 {@code date + 1}，这也是每个点位的
     * {@code bucket} 必须带完整日期的原因。
     *
     * <p>聚合完全在后端完成：{@code points} 恒为 25 个（首尾同为 05:00，
     * 因为点以整点为中心聚合），无数据的时段已补零。
     *
     * <h2>参数收敛</h2>
     * 不合法的日期一律收敛而非报 400，与两个分页端点的钳制策略一致：
     * 缺省 / 格式错误回落到当前窗口的锚定日（凌晨 5 点前算前一天），
     * 晚于今天收敛到今天，早于可回看范围（15 天）收敛到最早那天。
     * 故响应体回带实际生效的日期与窗口边界。
     *
     * <p>{@code isCurrentWindow} 为 true 时该窗口仍在变化，调用方需接上 SSE 增量流；
     * 为 false 时数据已固化，一次拉取即为终态。
     *
     * @param date 目标日期，格式 {@code yyyy-MM-dd}；缺省为当前窗口所属的那一天
     */
    @GetMapping("/config/api/usage-hourly/series")
    public Mono<UsageHourlySeries> usageHourlySeries(
            @RequestParam(required = false) String date) {
        return usageQueryService.getHourlySeries(date);
    }

    /**
     * 概览三个图表共用的 SSE 流 —— 两帧全量快照打底，随后只推增量。
     *
     * <p>三种数据帧用 event 名区分：
     * <ul>
     *   <li>{@code event: breakdown} —— 按日期聚合的完整明细，前端<strong>整体替换</strong>。
     *       喂堆叠柱状图与「近 7 日」折线。</li>
     *   <li>{@code event: hourly} —— 今日时段的整点用量，前端<strong>整体替换</strong>。
     *       喂「今日时段」折线。</li>
     *   <li>{@code event: usage-delta} —— 单条用量记录，前端按各视图口径<strong>累加</strong>。
     *       同一帧可能被一个视图接受、被另一个丢弃（次日凌晨的调用不属于柱状图冻结的
     *       日历日窗口，却仍落在「今日时段」的 5 点分界窗口内），这是正确行为。</li>
     * </ul>
     * 两帧快照在每次订阅（含断线重连）时各下发一次。
     *
     * <h2>为何用 concat 而非 merge</h2>
     * 增量帧必须晚于两帧快照到达，否则它会被随后到达的快照覆盖，那次调用就白算了。
     * {@code concat} 保证顺序：前一个 Publisher 完成后才订阅下一个。
     * 心跳可以并行，故仍用 {@code merge} 叠加。
     *
     * <p>这也是「首屏 HTTP + 建流」两步能合并成一步的原因 ——
     * 流自己就带来了首屏数据，前端无需额外发 GET。
     *
     * <h2>为何没有定时兜底</h2>
     * 增量流里混入全量帧会让前端反复重置基准。正确性由「订阅即发快照」保证；
     * 「时间轴随时钟前移」由前端按整点自行推进 —— 那个变化时刻可精确预知，
     * 不需要后端定时重推。
     *
     * <p>本端点走认证，且同样受 {@link SseConnectionGate} 总连接数上限保护。
     *
     * @param days 回看天数，默认 7；服务层会钳制到 [1, 90]
     */
    @GetMapping(value = "/config/api/usage/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> streamUsage(
            @RequestParam(defaultValue = "7") int days) {
        return Flux.defer(() -> {
            if (!sseConnectionGate.tryAcquire()) {
                return Flux.<ServerSentEvent<Object>>empty();
            }
            AtomicBoolean released = new AtomicBoolean(false);

            // 泛型统一为 Object：三种数据帧的载荷类型不同（两种明细列表 / 单条记录），
            // 而 Flux 不协变，Flux<SSE<List<..>>> 无法当作 Flux<SSE<?>> 使用。
            Flux<ServerSentEvent<Object>> breakdown = usageQueryService.getUsageBreakdown(days)
                    .map(rows -> ServerSentEvent.builder((Object) rows).event("breakdown").build())
                    .flux();

            Flux<ServerSentEvent<Object>> hourly = usageQueryService.getHourlyTokens()
                    .map(points -> ServerSentEvent.builder((Object) points).event("hourly").build())
                    .flux();

            Flux<ServerSentEvent<Object>> deltas = usageQueryService.streamUsageRecords()
                    .map(delta -> ServerSentEvent.builder((Object) delta).event("usage-delta").build());

            Flux<ServerSentEvent<Object>> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                    .map(tick -> ServerSentEvent.<Object>builder().comment("keep-alive").build());

            return Flux.merge(Flux.concat(breakdown, hourly, deltas), heartbeat)
                    .doFinally(signal -> {
                        if (released.compareAndSet(false, true)) {
                            sseConnectionGate.release();
                        }
                    });
        });
    }
}

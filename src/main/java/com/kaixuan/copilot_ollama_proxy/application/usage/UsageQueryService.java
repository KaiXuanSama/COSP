package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.StatsSnapshot;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownRow;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageHourlyPoint;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageRecordDelta;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台的调用统计查询用例。
 *
 * <p>提供两条读取链路：
 * <ul>
 *   <li>{@link #getStats()} / {@link #getHeatmap(int)} / {@link #getUsageBreakdown(int)}
 *       / {@link #getHourlyTokens()} —— HTTP 首屏拉取；</li>
 *   <li>{@link #streamStats()} / {@link #streamUsageRecords()} —— SSE 推送，替代前端定时轮询。</li>
 * </ul>
 *
 * <h2>两条 SSE 流的分工</h2>
 * <ul>
 *   <li><strong>统计卡流</strong>（{@link #streamStats()}）—— 由
 *       {@link UsageEventPublisher#changes()} 的纯信号唤醒，每次重查并下发完整快照。
 *       幂等，丢帧由下一帧或定时兜底收敛。数据源为 {@code api_usage_daily}，
 *       与信号发布点所写的表一致。</li>
 *   <li><strong>图表流</strong>（{@link #streamUsageRecords()}）—— 三个图表
 *       （堆叠柱状图、近 7 日折线、今日时段折线）共用一条流，
 *       由 {@link UsageEventPublisher#deltas()} 直接转发，不查库。
 *       代价是不幂等，须在订阅时先发全量快照作为基准。</li>
 * </ul>
 *
 * <h2>归桶策略不在本层</h2>
 * 「以整点为中心聚合」由 SQL 完成（{@code aggregateHourlyTokens}），
 * 「窗口该有多长」「哪些点尚未到来」「增量帧属于哪个桶」则全部由前端决定。
 * 本层只负责确定今日窗口的起止边界（{@link #resolveHourlyWindowStart}）——
 * 因为那是查询参数，而非展示决策。
 */
@Service
public class UsageQueryService {

    /**
     * SSE 兜底刷新周期。即使没有新的调用信号，也会按此周期重推一次快照，
     * 用于覆盖跨天（today 归零）、个别信号丢失等边界，保证前端最终一致。
     *
     * <p>只用于统计卡流。图表流是增量语义，混入全量帧会让前端反复重置基准，
     * 其正确性由「订阅即发快照」保证。
     */
    private static final Duration FALLBACK_INTERVAL = Duration.ofSeconds(30);

    /** 下钻柱状图的回看天数上下限。第一版固定 7 天，保留范围钳制以便后续开放切换。 */
    private static final int MIN_BREAKDOWN_DAYS = 1;
    private static final int MAX_BREAKDOWN_DAYS = 90;

    /**
     * 今日窗口的起始小时 —— 5 点，而非 0 点。
     *
     * <p>跨夜编码是常态，按自然日切分会把一次连续工作截成两段；5 点基本落在活动最低谷，
     * 以它为界一天的曲线才完整。
     *
     * <p>注意这与柱状图「今日」的口径<strong>不同</strong>：柱状图按日历日
     * （{@code substr(created_at, 1, 10)}），本窗口按 5 点分界。两者是既存差异，
     * 前端需按各自口径判断增量帧的归属。
     */
    private static final int DAY_START_HOUR = 5;

    private static final int HOURS_PER_DAY = 24;

    /** 与 {@code api_call_usage.created_at} 完全一致的格式，用于拼时间窗边界。 */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ApiUsageRepository apiUsageRepository;
    private final ApiCallUsageRepository apiCallUsageRepository;
    private final UsageEventPublisher usageEventPublisher;

    public UsageQueryService(ApiUsageRepository apiUsageRepository,
                            ApiCallUsageRepository apiCallUsageRepository,
                            UsageEventPublisher usageEventPublisher) {
        this.apiUsageRepository = apiUsageRepository;
        this.apiCallUsageRepository = apiCallUsageRepository;
        this.usageEventPublisher = usageEventPublisher;
    }

    /**
     * 查询按日期聚合的用量明细 —— 图表流首帧的第一部分。
     *
     * <p>只在最细粒度聚合一次（日期 × 供应商 × 模型），同时支撑两个视图：
     * 堆叠柱状图取调用次数并 pivot 出三级下钻，「近 7 日」折线取 token 并按日期求和。
     * 数据源为 {@code api_call_usage}（永久保留、含供应商/模型维度），
     * 与概览统计卡所用的 {@code api_usage_daily}（全量含失败调用、无维度）互补。
     *
     * <p>口径提示：{@code api_call_usage} 仅记录成功且上游返回 usage 的调用，
     * 故此处次数会略低于统计卡的全量调用数。
     *
     * @param requestedDays 请求回看天数，超出 [1, 90] 时钳制
     */
    public Mono<List<UsageBreakdownRow>> getUsageBreakdown(int requestedDays) {
        int days = clampBreakdownDays(requestedDays);
        return Mono.fromCallable(() -> apiCallUsageRepository.aggregateBreakdown(days))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 查询今日时段的整点 token 用量 —— 图表流首帧的第二部分。
     *
     * <p>窗口为 {@code [今日 05:00, 次日 05:00)}，凌晨 5 点前则回退到前一日 05:00
     * （见 {@link #resolveHourlyWindowStart}）。这个 5 点分界只作用于本视图，
     * 柱状图与「近 7 日」折线仍按日历日 —— 两者是既存的口径差异。
     *
     * <p>整点的居中聚合在 SQL 里完成，本方法不做补零：横轴该有多长、
     * 哪些点尚未到来，都是展示决策，由前端按当前时刻自行推导。
     * 因此返回的列表只含<strong>有数据的整点</strong>，长度不定，最多 25 项。
     */
    public Mono<List<UsageHourlyPoint>> getHourlyTokens() {
        return Mono.fromCallable(() -> {
            LocalDateTime windowStart = resolveHourlyWindowStart(LocalDateTime.now());
            return apiCallUsageRepository.aggregateHourlyTokens(
                    windowStart.format(TIMESTAMP_FORMAT),
                    windowStart.plusHours(HOURS_PER_DAY).format(TIMESTAMP_FORMAT));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 用量记录的增量帧流 —— 三个图表共用的唯一实时通道。
     *
     * <p>这里<strong>不查库</strong>，只转发 {@link UsageEventPublisher#deltas()} 的帧。
     * 早先的做法是「收到信号 → 重查整个窗口 → 下发全量」：一次调用只影响一个格子，
     * 却要把 7 天 × 全部供应商 × 全部模型重新聚合一遍并整份传下去。改成增量后，
     * 一次调用的开销从「一次全表聚合 + 数 KB 传输」降到「一帧几十字节」。
     *
     * <h2>一条流喂三个图表</h2>
     * 帧是「一次调用」这个事实本身，不预设分桶方式，故三个视图能各自按口径处理：
     * 柱状图按日历日累加次数，「近 7 日」按日历日累加 token，
     * 「今日时段」按 5 点分界的窗口归入所属整点。同一帧可能被一个视图接受、
     * 被另一个丢弃（如次日凌晨 2 点的调用不属于柱状图冻结的 7 个日历日，
     * 却仍落在「今日时段」窗口内），这是正确行为。
     *
     * <h2>调用方的义务</h2>
     * 增量帧不幂等，丢一帧就少算一次调用，因此本方法<strong>不能单独使用</strong> ——
     * 调用方必须先下发全量快照（{@link #getUsageBreakdown(int)} 与
     * {@link #getHourlyTokens()}）作为基准，再接上本流。
     * 断线重连时同理：重新订阅即重新走一遍「快照 + 增量」，重连间隙的遗漏由新快照补齐。
     *
     * <h2>为何不需要定时兜底</h2>
     * 旧实现有 30 秒兜底重推全量，用途是覆盖丢帧。增量流里混入全量帧会让前端反复重置基准，
     * 故已去掉；正确性改由「订阅即发快照」保证。「时间轴随时钟前移」这件事
     * 也不再需要后端参与 —— 前端按整点自行推进即可，而变化时刻是可精确预知的。
     */
    public Flux<UsageRecordDelta> streamUsageRecords() {
        return usageEventPublisher.deltas();
    }

    /** 钳制回看天数，防止超大范围查询拖垮 SQLite。 */
    private static int clampBreakdownDays(int requestedDays) {
        return Math.max(MIN_BREAKDOWN_DAYS, Math.min(MAX_BREAKDOWN_DAYS, requestedDays));
    }

    /**
     * 确定今日时段窗口的起点。
     *
     * <p>凌晨 5 点之前仍算作「昨天」那一轮，因此起点要回退一天 ——
     * 这正是 5 点起算要解决的问题：凌晨 3 点打开页面，看到的应是昨晚以来的连续曲线，
     * 而不是刚开始 3 小时的空图。
     *
     * <p>可见性放宽到包级，供测试直接验证边界。
     */
    static LocalDateTime resolveHourlyWindowStart(LocalDateTime now) {
        LocalDate anchorDate = now.getHour() < DAY_START_HOUR
                ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
        return anchorDate.atTime(DAY_START_HOUR, 0);
    }

    /** 查询当前统计快照（HTTP 首屏）。 */
    public Mono<StatsSnapshot> getStats() {
        return Mono.fromCallable(this::loadSnapshot).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 统计快照的 SSE 推送流。
     *
     * <p>三路信号合并触发重新查询：
     * <ol>
     *   <li>订阅建立时立即推一份当前快照（首帧）；</li>
     *   <li>{@link UsageEventPublisher} 的调用变更信号（事件驱动，实时）；</li>
     *   <li>{@link #FALLBACK_INTERVAL} 定时兜底（覆盖跨天与丢帧）。</li>
     * </ol>
     *
     * <p>每次触发都重新查库获取最新快照，因此信号本身不携带数据、丢失个别信号也不影响正确性。
     * 查询在 {@code boundedElastic} 上执行，避免阻塞 event-loop；{@code distinctUntilChanged}
     * 抑制内容未变时的重复推送。
     */
    public Flux<StatsSnapshot> streamStats() {
        Flux<Object> triggers = Flux.merge(
                Flux.just(new Object()),
                usageEventPublisher.changes(),
                Flux.interval(FALLBACK_INTERVAL));

        return triggers
                .concatMap(ignored -> Mono.fromCallable(this::loadSnapshot).subscribeOn(Schedulers.boundedElastic()))
                .distinctUntilChanged();
    }

    /** 从汇总表读取一份统计快照（阻塞查询，需在 boundedElastic 上调用）。 */
    private StatsSnapshot loadSnapshot() {
        int[] tokens = apiUsageRepository.sumTokensToday();
        return new StatsSnapshot(
                apiUsageRepository.countTotal(),
                apiUsageRepository.countToday(),
                tokens[0],
                tokens[1]);
    }

    public Mono<List<Map<String, Object>>> getHeatmap(int requestedDays) {
        return Mono.fromCallable(() -> buildHeatmap(requestedDays))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private List<Map<String, Object>> buildHeatmap(int requestedDays) {
        int days = Math.max(7, Math.min(365, requestedDays));
        List<Map<String, Object>> data = apiUsageRepository.listRecentDays(days);
        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (Map<String, Object> row : data) {
            byDate.put((String) row.get("usageDate"), row);
        }

        List<Map<String, Object>> full = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 364; i >= 0; i--) {
            String date = today.minusDays(i).toString();
            Map<String, Object> row = byDate.get(date);
            if (row != null) {
                full.add(row);
                continue;
            }
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("usageDate", date);
            empty.put("callCount", 0);
            empty.put("inputTokens", 0);
            empty.put("outputTokens", 0);
            full.add(empty);
        }
        return full;
    }
}
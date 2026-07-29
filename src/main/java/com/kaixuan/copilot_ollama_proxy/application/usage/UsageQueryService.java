package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.StatsSnapshot;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownRow;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTimelinePoint;
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
 *       / {@link #getUsageTimeline(String, int)} —— HTTP 首屏拉取；</li>
 *   <li>{@link #streamStats()} / {@link #streamUsageBreakdown(int)}
 *       / {@link #streamUsageTimeline(String, int)}
 *       —— SSE 增量推送，事件驱动 + 定时兜底，替代前端定时轮询。</li>
 * </ul>
 *
 * <p>各条 SSE 流共用 {@link UsageEventPublisher} 的同一批信号，因此统计卡、下钻柱状图与
 * 用量折线由同一次调用事件同时刷新，视图之间不会出现新旧错位。
 */
@Service
public class UsageQueryService {

    /**
     * SSE 兜底刷新周期。即使没有新的调用信号，也会按此周期重推一次快照，
     * 用于覆盖跨天（today 归零）、个别信号丢失等边界，保证前端最终一致。
     */
    private static final Duration FALLBACK_INTERVAL = Duration.ofSeconds(30);

    /** 下钻柱状图的回看天数上下限。第一版固定 7 天，保留范围钳制以便后续开放切换。 */
    private static final int MIN_BREAKDOWN_DAYS = 1;
    private static final int MAX_BREAKDOWN_DAYS = 90;

    /** 折线图「今日时段」范围的标识，其余取值一律按近 7 日处理。 */
    private static final String DAILY_RANGE = "1d";

    /** 折线图近 7 日范围的天数（含今天）。 */
    private static final int TIMELINE_DAYS = 7;

    /**
     * 今日窗口的起始小时 —— 5 点，而非 0 点。
     *
     * <p>跨夜编码是常态，按自然日切分会把一次连续工作截成两段；5 点基本落在活动最低谷，
     * 以它为界一天的曲线才完整。这个值同时决定了横轴「当日 / 次日」分段线的位置。
     */
    private static final int DAY_START_HOUR = 5;

    private static final int HOURS_PER_DAY = 24;

    /** 与 {@code api_call_usage.created_at} 完全一致的格式，用于拼时间窗边界。 */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final DateTimeFormatter BUCKET_LABEL_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** 无数据桶的零值，避免在补零循环里反复分配。 */
    private static final long[] EMPTY_TOKENS = {0L, 0L};

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
     * 查询概览下钻柱状图的用量明细（日期 × 供应商 × 模型）。
     *
     * <p>只在最细粒度聚合一次，三级视图与 hover 明细均由前端从这一份数据 pivot 得出，
     * 因此下钻无需额外请求。数据源为 {@code api_call_usage}（永久保留、含供应商/模型维度），
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
     * 下钻用量明细的 SSE 推送流，让概览柱状图与统计卡一样实时更新。
     *
     * <p>触发源与 {@link #streamStats()} 完全一致（首帧 + {@link UsageEventPublisher} 调用信号
     * + 定时兜底），因此两处视图由同一批事件驱动，不会出现「卡片已涨、柱子还旧」的错位。
     *
     * <p>复用 {@code UsageEventPublisher} 而非新增发布器是有意的：{@code api_call_usage}
     * 由 provider 层在响应结束时写入，早于 api 层 {@code ApiUsageCollector.record} 发出的信号，
     * 故信号到达时明细行必定已落库，不存在读到旧数据的时序问题。
     *
     * <p>反过来，部分信号对应的调用没有 usage（上游未返回），不会产生明细行；
     * 这类「空转」由 {@code distinctUntilChanged} 吸收，不会推出重复帧。
     *
     * @param requestedDays 请求回看天数，超出 [1, 90] 时钳制
     */
    public Flux<List<UsageBreakdownRow>> streamUsageBreakdown(int requestedDays) {
        int days = clampBreakdownDays(requestedDays);

        Flux<Object> triggers = Flux.merge(
                Flux.just(new Object()),
                usageEventPublisher.changes(),
                Flux.interval(FALLBACK_INTERVAL));

        return triggers
                .concatMap(ignored -> Mono.fromCallable(() -> apiCallUsageRepository.aggregateBreakdown(days))
                        .subscribeOn(Schedulers.boundedElastic()))
                .distinctUntilChanged();
    }

    /** 钳制回看天数，防止超大范围查询拖垮 SQLite。 */
    private static int clampBreakdownDays(int requestedDays) {
        return Math.max(MIN_BREAKDOWN_DAYS, Math.min(MAX_BREAKDOWN_DAYS, requestedDays));
    }

    /**
     * 查询 token 用量时间线（HTTP 首屏）。
     *
     * <p>两种范围同源于 {@code api_call_usage}，故「近 7 日中某天的值」必然等于
     * 「该天各时段之和」，切换范围时数字可以互相印证。
     *
     * @param range {@code "1d"} 为今日时段（每小时一个点），其余值按近 7 日处理
     */
    public Mono<List<UsageTimelinePoint>> getUsageTimeline(String range) {
        return Mono.fromCallable(() -> buildTimeline(range))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * token 用量时间线的 SSE 推送流。
     *
     * <p>触发源与 {@link #streamUsageBreakdown(int)} 一致，因此折线与柱状图由同一批事件驱动，
     * 两图不会出现新旧错位。今日时段范围还额外受益于定时兜底 ——
     * 时间流逝会让「当前所在时段」前移，即使没有新调用也需要重画时间轴。
     *
     * @param range {@code "1d"} 为今日时段，其余值按近 7 日处理
     */
    public Flux<List<UsageTimelinePoint>> streamUsageTimeline(String range) {
        Flux<Object> triggers = Flux.merge(
                Flux.just(new Object()),
                usageEventPublisher.changes(),
                Flux.interval(FALLBACK_INTERVAL));

        return triggers
                .concatMap(ignored -> Mono.fromCallable(() -> buildTimeline(range))
                        .subscribeOn(Schedulers.boundedElastic()))
                .distinctUntilChanged();
    }

    /** 按范围分派到两种时间线构建方式（阻塞查询，需在 boundedElastic 上调用）。 */
    private List<UsageTimelinePoint> buildTimeline(String range) {
        return DAILY_RANGE.equals(range)
                ? buildHourlyTimeline()
                : buildWeeklyTimeline();
    }

    /**
     * 近 7 日时间线：一天一个点，缺失的日期补零。
     *
     * <p>补零是必要的：折线按点位等距绘制，若跳过无数据的日期，横轴就不再是等距时间轴，
     * 「隔了几天」这个信息会丢失。这与「今日时段不补未来」并不矛盾 ——
     * 过去的空日是确定的零，未来的时段则是尚未发生。
     */
    private List<UsageTimelinePoint> buildWeeklyTimeline() {
        Map<String, UsageTimelinePoint> byDate = new LinkedHashMap<>();
        for (UsageTimelinePoint point : apiCallUsageRepository.aggregateDailyTokens(TIMELINE_DAYS)) {
            byDate.put(point.bucket(), point);
        }

        List<UsageTimelinePoint> full = new ArrayList<>(TIMELINE_DAYS);
        LocalDate today = LocalDate.now();
        for (int offset = TIMELINE_DAYS - 1; offset >= 0; offset--) {
            String date = today.minusDays(offset).toString();
            UsageTimelinePoint point = byDate.get(date);
            full.add(point != null ? point : new UsageTimelinePoint(date, 0, 0));
        }
        return full;
    }

    /**
     * 今日时段时间线：从 {@link #DAY_START_HOUR} 起算的 24 小时，每小时一个点。
     *
     * <h2>为什么从 5 点起算而非 0 点</h2>
     * 跨夜编码是常态。按自然日切分会把一次连续的工作截成两段，看起来像两个互不相关的低谷；
     * 凌晨 5 点基本落在活动的最低谷，以它为界，一天的活动曲线才是完整的一条。
     *
     * <h2>为什么点位以整点为中心而非区间起点</h2>
     * 若每点代表「该整点起的一小时」，24 个点的标签就是 05:00 到 04:00，
     * 横轴两端一个是 05:00 一个是 04:00，看不出这是完整的一圈。
     *
     * 改为<strong>以整点为中心</strong>聚合后（{@code 07:00} 覆盖 06:30–07:30），
     * 轴变成 25 个刻度、首尾都是 05:00，前后对称、一圈闭合的语义直接可见。
     * 代价是首尾两点各只覆盖半小时（05:00 取 05:00–05:30，末尾 05:00 取 04:30–05:00），
     * 但两者相加恰好是完整一小时，总量不重不漏。
     *
     * <h2>为什么返回完整一天而非截断到当前</h2>
     * 横轴始终覆盖整个 24 小时，一天之内不再随时间推移而伸缩 ——
     * 使用者能一眼看出「今天还剩多少时间」，各时段的横向位置也不会在刷新时移动。
     *
     * <p>代价是必须区分「用量为 0」与「尚未到来」：两者的 token 都是 0，
     * 若不加区分，折线会一路贴底延伸到轴末，读起来像用量已归零。故未来时段标记
     * {@code future}，由展示侧决定断开还是淡化。
     */
    private List<UsageTimelinePoint> buildHourlyTimeline() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = resolveWindowStart(now);

        /*
         * 半小时槽 → 累计量。仓储按半小时分组，是「以整点为中心」所需的最小单元：
         * 每个整点吸收它前后各一个半小时槽。
         */
        Map<Integer, long[]> bySlot = new LinkedHashMap<>();
        List<UsageTimelinePoint> halfHours = apiCallUsageRepository.aggregateHalfHourTokens(
                windowStart.format(TIMESTAMP_FORMAT),
                windowStart.plusHours(HOURS_PER_DAY).format(TIMESTAMP_FORMAT));
        for (UsageTimelinePoint point : halfHours) {
            int slot = halfHourSlotOf(point.bucket());
            long[] sum = bySlot.computeIfAbsent(slot, ignored -> new long[2]);
            sum[0] += point.inputTokens();
            sum[1] += point.outputTokens();
        }

        /** 当前时刻所在的整点序号 —— 它之后的点尚未发生。 */
        int currentPoint = (int) Duration.between(windowStart, now).toHours();

        // 25 个点：05:00 到次日 05:00，首尾同为 05:00 使一圈闭合
        List<UsageTimelinePoint> full = new ArrayList<>(HOURS_PER_DAY + 1);
        for (int index = 0; index <= HOURS_PER_DAY; index++) {
            // 第 index 个整点吸收槽 (2·index − 1) 与 (2·index)，即它前后各半小时。
            // 首点没有前半小时（槽 −1 不存在），末点没有后半小时（槽 48 超出窗口），
            // 因此两端各只覆盖半小时，相加正好补成一小时。
            long[] before = bySlot.getOrDefault(index * 2 - 1, EMPTY_TOKENS);
            long[] after = bySlot.getOrDefault(index * 2, EMPTY_TOKENS);
            full.add(new UsageTimelinePoint(
                    formatBucketLabel(windowStart.plusHours(index)),
                    before[0] + after[0],
                    before[1] + after[1],
                    index > currentPoint));
        }
        return full;
    }

    /**
     * 确定今日窗口的起点。
     *
     * <p>凌晨 5 点之前仍算作「昨天」那一轮，因此起点要回退一天 ——
     * 这正是 5 点起算要解决的问题：凌晨 3 点打开页面，看到的应是昨晚以来的连续曲线，
     * 而不是刚开始 3 小时的空图。
     */
    private static LocalDateTime resolveWindowStart(LocalDateTime now) {
        LocalDate anchorDate = now.getHour() < DAY_START_HOUR
                ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
        return anchorDate.atTime(DAY_START_HOUR, 0);
    }

    /**
     * 把 {@code HH:00} / {@code HH:30} 换算成窗口内的半小时槽序号。
     *
     * <p>槽 0 是 05:00–05:30，槽 1 是 05:30–06:00，依此类推共 48 个。
     * 跨过午夜的时刻会算出负数，加一天的槽数补回（如 01:00 属槽 40）。
     *
     * @param label 仓储返回的半小时标识
     */
    private static int halfHourSlotOf(String label) {
        int hour = Integer.parseInt(label.substring(0, 2));
        boolean secondHalf = label.endsWith(":30");
        int offset = (hour - DAY_START_HOUR) * 2 + (secondHalf ? 1 : 0);
        if (offset < 0) {
            offset += HOURS_PER_DAY * 2;
        }
        return offset;
    }

    /** 点位标签取该整点时刻，格式 {@code HH:mm}。 */
    private static String formatBucketLabel(LocalDateTime pointTime) {
        return pointTime.format(BUCKET_LABEL_FORMAT);
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
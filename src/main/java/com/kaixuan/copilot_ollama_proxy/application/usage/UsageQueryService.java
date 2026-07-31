package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.StatsSnapshot;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownPage;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownRow;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageDailyPage;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageDailyPoint;
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

    /**
     * 日期边界格式，与 {@code created_at} 的前 10 位对齐。
     *
     * <p>刻意不用 {@code LocalDate.toString()}：那个方法对公元前与超过四位数的年份
     * 会输出带符号或扩展位数的形式，与列格式不一致。虽然实际不会出现，
     * 但显式格式化让「边界串必须与列格式逐字符对齐」这个前提留在代码里。
     */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

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
     * 查询<strong>可滑动窗口</strong>内的用量明细，附带窗口元信息 —— 概览翻看历史区间的入口。
     *
     * <p>窗口由两个自由度确定：宽度 {@code size} 与向过去的偏移 {@code offset}。
     * {@code offset = 0} 时窗口右端为今天，{@code offset = 1} 整体后退一天，
     * 于是 {@code 1-7}、{@code 2-8} 这类滑动区间都能表达。
     *
     * <h2>钳制规则与耦合顺序</h2>
     * <ol>
     *   <li>宽度先钳到 {@code [7, 15]}；</li>
     *   <li>偏移再钳到 {@code [0, 15 - size]}。</li>
     * </ol>
     * 顺序不可颠倒 —— 偏移的上界依赖已确定的宽度。这条约束等价于
     * {@code size + offset <= 15}，即窗口整体必须落在「最近 15 天」这个池子里：
     * 宽度 7 时最多退 8 天（覆盖第 9~15 天），宽度取满 15 时只有一个合法位置。
     *
     * <p>钳制而非报错，理由与既有 {@code days} 参数一致：这是展示型查询，
     * 越界参数几乎只来自前端状态漂移或手工试探，静默收敛到最近的合法窗口
     * 比抛 400 更符合「图表总能画出来」的预期。代价是前端不能假设拿回的窗口
     * 等于自己请求的窗口，因此响应体必须回带实际窗口 —— 这正是
     * {@link UsageBreakdownPage} 存在的原因。
     *
     * <h2>为何不用 LIMIT/OFFSET 分页</h2>
     * 这里的「页」是<strong>时间区间</strong>，不是结果集的行区间。同一天可能有 1 行也可能有
     * 30 行（供应商 × 模型的组合数不定），按行分页会把一天的数据劈成两页，
     * 前端拼不出完整的柱子。按日期分页则每一页都是自洽的完整窗口。
     *
     * <p>「今天」取自服务端本地时钟，与写入侧 {@code created_at} 的时区口径一致。
     *
     * @param requestedSize   请求窗口宽度（天），超出 {@code [7, 15]} 时钳制
     * @param requestedOffset 请求向过去偏移的天数，超出 {@code [0, 15 - size]} 时钳制
     * @return 含实际窗口边界、翻页可用性与明细行的分页结果
     */
    public Mono<UsageBreakdownPage> getUsageBreakdownPage(int requestedSize, int requestedOffset) {
        return Mono.fromCallable(() -> buildBreakdownPage(
                        LocalDate.now(), requestedSize, requestedOffset))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 组装滑动窗口分页结果。
     *
     * <p>可见性放宽到包级并显式接受 {@code today}，使窗口边界与翻页标志能被测试
     * 在固定「今天」下断言 —— 否则这些断言会随运行日期变化而失效。
     *
     * @param today           作为窗口右端基准的当天日期
     * @param requestedSize   请求窗口宽度
     * @param requestedOffset 请求偏移
     */
    UsageBreakdownPage buildBreakdownPage(LocalDate today, int requestedSize, int requestedOffset) {
        SlidingDateWindow window = SlidingDateWindow.resolve(today, requestedSize, requestedOffset);

        List<UsageBreakdownRow> rows = apiCallUsageRepository.aggregateBreakdownBetween(
                window.startText(), window.exclusiveEndText());

        return new UsageBreakdownPage(
                window.startText(),
                window.endText(),
                window.size(),
                window.offset(),
                window.includesToday(),
                window.hasNewer(),
                window.hasOlder(),
                rows);
    }

    /**
     * 查询<strong>可滑动窗口</strong>内按日聚合的 token 用量 —— 「近 N 日」折线翻看历史区间的入口。
     *
     * <p>窗口规则与 {@link #getUsageBreakdownPage} 完全一致，且共用
     * {@link SlidingDateWindow#resolve} 这一份钳制逻辑：同样的 {@code size}/{@code offset}
     * 在两个端点上必然落到同一区间，前端可用一份翻页状态同时驱动柱状图与折线。
     * 若两处各写一遍钳制，改上限时漏掉一处不会有编译错误，只会让两张图落到不同区间，
     * 而它们看起来仍然都能正常渲染。
     *
     * <h2>为何独立于柱状图端点</h2>
     * 概览页同时展示两张图时，前端只取一份明细自行按日归约即可（现状即如此），
     * 这个端点是给「只要折线」的场景用的：分组维度更粗，行数上界从
     * {@code 天数 × 供应商 × 模型} 降到<strong>天数</strong>，且客户端不必再聚合一遍。
     *
     * <p>返回的 {@code points} <strong>已按窗口补零</strong>，长度恒等于 {@code size}。
     * 这一点与柱状图端点相反（那里的 {@code rows} 只含有数据的组合）——
     * 折线按点位等距绘制，跳过空日会让横轴不再是等距时间轴，而这个补零完全由窗口边界
     * 决定，没有展示层的自由度可言，放在后端更合适。
     *
     * @param requestedSize   请求窗口宽度（天），超出 {@code [7, 15]} 时钳制
     * @param requestedOffset 请求向过去偏移的天数，超出 {@code [0, 15 - size]} 时钳制
     */
    public Mono<UsageDailyPage> getUsageDailyPage(int requestedSize, int requestedOffset) {
        return Mono.fromCallable(() -> buildDailyPage(
                        LocalDate.now(), requestedSize, requestedOffset))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 组装按日 token 用量的滑动窗口分页结果，并把窗口内缺失的日期补零。
     *
     * <p>可见性放宽到包级并显式接受 {@code today}，理由同 {@link #buildBreakdownPage}。
     *
     * @param today           作为窗口右端基准的当天日期
     * @param requestedSize   请求窗口宽度
     * @param requestedOffset 请求偏移
     */
    UsageDailyPage buildDailyPage(LocalDate today, int requestedSize, int requestedOffset) {
        SlidingDateWindow window = SlidingDateWindow.resolve(today, requestedSize, requestedOffset);

        // 先按日期建索引再遍历窗口，而不是对每一天都去扫一遍结果列表 ——
        // 后者是 O(size × 命中行数)，虽然规模小，但也让「补零」这层逻辑更难读。
        Map<String, UsageDailyPoint> byDate = new LinkedHashMap<>();
        for (UsageDailyPoint point : apiCallUsageRepository.aggregateDailyTokens(
                window.startText(), window.exclusiveEndText())) {
            byDate.put(point.date(), point);
        }

        List<UsageDailyPoint> points = new ArrayList<>(window.size());
        for (int i = 0; i < window.size(); i++) {
            String date = window.start().plusDays(i).format(DATE_FORMAT);
            points.add(byDate.getOrDefault(date, UsageDailyPoint.empty(date)));
        }

        return new UsageDailyPage(
                window.startText(),
                window.endText(),
                window.size(),
                window.offset(),
                window.includesToday(),
                window.hasNewer(),
                window.hasOlder(),
                points);
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
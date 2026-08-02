package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.StatsSnapshot;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownPage;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownRow;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageDailyPage;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageDailyPoint;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageDateRange;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageHourlyPoint;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageHourlySeries;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageRecordDelta;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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

    /**
     * 时段视图的点位数 —— 25 而非 24。
     *
     * <p>点以整点为<strong>中心</strong>聚合，首尾都落在 05:00 上，各只覆盖半小时
     * （首点 {@code [05:00, 05:30)}、末点 {@code [04:30, 05:00)}），两者相加恰好一小时，
     * 总量不重不漏。因此 24 小时窗口装得下 25 个点位，这不是差一错误。
     *
     * <p>选整点为中心而非整点起始，是为了让横轴首尾对称、「一天是完整一圈」的语义
     * 直接可见；若按整点起始，标签会是 05:00 到 04:00，看不出这是闭合的一圈。
     */
    private static final int HOURLY_POINT_COUNT = 25;

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
     * 查询用量记录的日期上下限与可选范围 —— 概览日期选择器可达区间的唯一数据源。
     *
     * <p>在此之前选择器的下界是前端写死的常量，于是空库也会显示一整片可选日期，
     * 而每一天点进去都是空图。这个判断必须由后端给出，因为它同时取决于
     * 库里有什么与后端愿意查多久，前端两者都不知道。
     *
     * <h2>返回两组字段而非一组</h2>
     * {@code earliestDate}/{@code latestDate} 是数据事实，
     * {@code earliestSelectable}/{@code latestSelectable} 是可选范围，两者会不一致：
     * 库里存着 60 天数据，但分页端点只接受最近 {@value SlidingDateWindow#MAX_SIZE} 天内的窗口，
     * 此时下界由后者决定。只下发前者会让选择器放开到查不动的区间；
     * 只下发后者则无法区分「没有更早的数据」与「更早的数据查不了」。
     *
     * <h2>为何按上下限而非日期集合</h2>
     * 中间断档刻意被忽略 —— {@code 07-28} 与 {@code 07-30} 有数据而 {@code 07-29} 没有时，
     * 范围仍是 {@code 07-28} 到 {@code 07-30}。空日是确定的零，画成零柱即可；
     * 若把可选点限制成实际有数据的那几天，选择器的离散点会变成不连续的一串，
     * 横轴不再是等距时间轴，「隔了几天」这个信息反而丢了。
     */
    public Mono<UsageDateRange> getUsageDateRange() {
        return Mono.fromCallable(() -> buildDateRange(LocalDate.now()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 组装日期范围响应：把数据边界与后端回看深度合成可选范围。
     *
     * <h2>两端的推导规则不对称</h2>
     * <ul>
     *   <li>下界取<strong>交集</strong>：{@code max(earliest, earliestReachable(today))} ——
     *       数据下界与回看深度两个限制都要满足。</li>
     *   <li>上界<strong>恒为今天</strong>，不取 {@code latest}：今天是默认视图，且随时可能
     *       产生第一条记录。若因今天尚无调用就把它标成不可达，页面一打开就处在一个
     *       「不可选」的位置上，而下一次调用又会让它突然变得可选。</li>
     * </ul>
     *
     * <p>另有一个反直觉的情形：数据下界可能<strong>晚于</strong>今天。测试库里塞了未来日期，
     * 或系统时钟被回调时都会出现。此时 {@code max} 会把下界推到今天之后，
     * 使可选区间反向。故最后再钳一次不超过今天 —— 反向区间会让前端的
     * 「可达点」集合为空，选择器整体变成不可操作，且没有任何报错。
     *
     * <p>表为空时两端同时收敛到今天，选择器退化为只有今天一个可选点，
     * 这正确表达了「没有历史可翻」。
     *
     * <p>可见性放宽到包级并显式接受 {@code today}，使可选范围能被测试在固定「今天」下断言 ——
     * 否则这些断言会随运行日期变化而失效。
     *
     * @param today 服务端本地时钟的今天
     */
    UsageDateRange buildDateRange(LocalDate today) {
        UsageDateBounds bounds = apiCallUsageRepository.findDateBounds();
        LocalDate earliestReachable = SlidingDateWindow.earliestReachable(today);

        LocalDate earliestSelectable = earliestReachable;
        if (bounds.hasData()) {
            LocalDate earliest = parseDateOrNull(bounds.earliest());
            if (earliest != null && earliest.isAfter(earliestReachable)) {
                earliestSelectable = earliest;
            }
        } else {
            // 无数据时不放开整个回看池：没有任何一天点进去有内容，
            // 把可选点收缩到今天一个，比给出 15 个空日更诚实。
            earliestSelectable = today;
        }
        // 数据下界晚于今天（测试数据或时钟回拨）时区间会反向，钳回今天。
        if (earliestSelectable.isAfter(today)) {
            earliestSelectable = today;
        }

        return new UsageDateRange(
                bounds.earliest(),
                bounds.latest(),
                today.format(DATE_FORMAT),
                bounds.hasData(),
                earliestSelectable.format(DATE_FORMAT),
                today.format(DATE_FORMAT));
    }

    /**
     * 宽容解析日期串，失败返回 null。
     *
     * <p>边界值来自 {@code substr(created_at, 1, 10)}，正常情况必然是合法日期。
     * 但历史数据或其他写入路径可能留下格式不符的 {@code created_at}，
     * 此时宁可当作「没有可用下界」回落到回看深度，也不要让一条脏数据把整个概览打成 500。
     */
    private static LocalDate parseDateOrNull(String text) {
        try {
            return LocalDate.parse(text, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
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
     * 查询<strong>指定某一天</strong>的时段 token 用量，已补齐 25 个点位。
     *
     * <p>窗口是 {@code [date 05:00, date+1 05:00)} —— 5 点分界的理由见
     * {@link #DAY_START_HOUR}。注意 {@code date} 是窗口的<strong>锚定日</strong>，
     * 不等于窗口内所有点位的日期：次日 00:00 至 05:00 的点位属于 {@code date + 1}。
     *
     * <h2>为什么没有分页与窗口滑动</h2>
     * 这个视图一次只看一天，请求参数就是那一天本身，没有「宽度」这个自由度。
     * 与柱状图 / 「近 N 日」折线的 {@code size} + {@code offset} 是不同的问题形状。
     *
     * <h2>日期的收敛规则</h2>
     * 不合法的日期一律收敛而非抛 400，与两个分页端点的钳制策略一致：
     * <ul>
     *   <li>为空、格式错误 → 当前窗口的锚定日（凌晨 5 点前算前一天）；</li>
     *   <li>晚于今天 → 收敛到今天（未来没有数据）；</li>
     *   <li>早于可回看范围 → 收敛到 {@link SlidingDateWindow#earliestReachable}，
     *       与概览翻页能滑到的最早一天对齐。若这里能查到更早的日期，
     *       就会出现「柱状图翻不到、时段图却查得出」的不一致。</li>
     * </ul>
     * 因此响应体回带<strong>实际生效</strong>的日期与窗口边界。
     *
     * <h2>与 getHourlyTokens 的关系</h2>
     * 那个方法是图表流首帧的一部分，只查「当前那一天」且<strong>不补零</strong>
     * （补零留给前端）。本方法补零，理由同 {@link #getUsageDailyPage} ——
     * 25 个点位完全由窗口边界决定，没有展示层的自由度。
     * 两者并存：流首帧维持既有契约不动，本端点服务于「翻看某一天」。
     *
     * @param requestedDate 请求日期，格式 {@code yyyy-MM-dd}；可为 null 或空
     */
    public Mono<UsageHourlySeries> getHourlySeries(String requestedDate) {
        return Mono.fromCallable(() -> buildHourlySeries(LocalDateTime.now(), requestedDate))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 组装指定一天的时段用量序列，并补齐窗口内每个整点。
     *
     * <p>可见性放宽到包级并显式接受 {@code now}，使窗口边界、收敛结果与
     * {@code isCurrentWindow} 能被测试在固定时刻下断言 ——
     * 否则这些断言会随运行时刻变化而失效（尤其凌晨 5 点前后行为不同）。
     *
     * @param now           当前时刻，用于默认日期、上界收敛与当前窗口判定
     * @param requestedDate 请求日期串，可为 null / 空 / 格式错误
     */
    UsageHourlySeries buildHourlySeries(LocalDateTime now, String requestedDate) {
        LocalDate anchor = resolveHourlyAnchorDate(now, requestedDate);
        LocalDateTime windowStart = anchor.atTime(DAY_START_HOUR, 0);

        // 末点位与查询右开边界恰好是同一时刻（次日 05:00），这不是巧合：
        // 末点位居中聚合，覆盖 [04:30, 05:00)，而 05:00 整起的数据属于下一轮窗口的首点。
        // 因此 24 小时窗口装得下 25 个点位而总量不重不漏。
        LocalDateTime windowEnd = windowStart.plusHours(HOURS_PER_DAY);

        Map<String, UsageHourlyPoint> byBucket = new LinkedHashMap<>();
        for (UsageHourlyPoint point : apiCallUsageRepository.aggregateHourlyTokens(
                windowStart.format(TIMESTAMP_FORMAT), windowEnd.format(TIMESTAMP_FORMAT))) {
            byBucket.put(point.bucket(), point);
        }

        List<UsageHourlyPoint> points = new ArrayList<>(HOURLY_POINT_COUNT);
        for (int i = 0; i < HOURLY_POINT_COUNT; i++) {
            String bucket = windowStart.plusHours(i).format(TIMESTAMP_FORMAT);
            points.add(byBucket.getOrDefault(bucket, UsageHourlyPoint.empty(bucket)));
        }

        return new UsageHourlySeries(
                anchor.format(DATE_FORMAT),
                windowStart.format(TIMESTAMP_FORMAT),
                windowEnd.format(TIMESTAMP_FORMAT),
                anchor.equals(resolveHourlyWindowStart(now).toLocalDate()),
                points);
    }

    /**
     * 解析并收敛时段视图的锚定日。
     *
     * <p>解析失败不报错而是回落到当前窗口 —— 这是展示型查询，畸形参数几乎只来自
     * 手工试探或前端状态漂移，静默给出一份可渲染的数据比抛 400 更符合预期。
     *
     * <p>可见性放宽到包级，供测试直接验证收敛边界。
     *
     * @param now           当前时刻
     * @param requestedDate 请求日期串，可为 null / 空 / 格式错误
     */
    static LocalDate resolveHourlyAnchorDate(LocalDateTime now, String requestedDate) {
        LocalDate fallback = resolveHourlyWindowStart(now).toLocalDate();
        if (requestedDate == null || requestedDate.isBlank()) {
            return fallback;
        }
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(requestedDate.trim(), DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return fallback;
        }

        LocalDate today = now.toLocalDate();
        if (parsed.isAfter(today)) {
            return today;
        }
        LocalDate earliest = SlidingDateWindow.earliestReachable(today);
        return parsed.isBefore(earliest) ? earliest : parsed;
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
package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageHourlyPoint;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageHourlySeries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证与锁定：指定某一天的时段用量序列。
 *
 * <p>三个易错点，也是本测试的三条主线：
 * <ul>
 *   <li><strong>5 点分界</strong> —— 窗口是 {@code [date 05:00, date+1 05:00)}，
 *       不是 0 点到 0 点。且凌晨 5 点前的「当前那一天」要回退一天。</li>
 *   <li><strong>25 个点位</strong> —— 首尾同为 05:00（居中聚合各覆盖半小时），
 *       写成 24 会砍掉一端，而折线照样能画。</li>
 *   <li><strong>日期收敛</strong> —— 未来 / 过早 / 畸形三种越界各有去处，
 *       且下界要与概览翻页能滑到的最早一天对齐。</li>
 * </ul>
 *
 * <p>断言全部在固定 {@code now} 下进行（{@code buildHourlySeries} 显式接受它），
 * 否则会随运行时刻漂移 —— 凌晨 5 点前后的行为本就不同。
 */
class UsageQueryServiceHourlySeriesTests {

    /** 固定「现在」：2026-07-31 14:30，落在当天窗口内（≥ 5 点）。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 31, 14, 30);

    private ApiCallUsageRepository usageRepository;
    private UsageQueryService service;

    @BeforeEach
    void setUp() {
        usageRepository = mock(ApiCallUsageRepository.class);
        service = new UsageQueryService(
                mock(ApiUsageRepository.class), usageRepository, new UsageEventPublisher());
        when(usageRepository.aggregateHourlyTokens(anyString(), anyString())).thenReturn(List.of());
    }

    private UsageHourlySeries series(String date) {
        return service.buildHourlySeries(NOW, date);
    }

    // ---------- 窗口边界 ----------

    /**
     * 窗口从 {@code 05:00} 起算，末点位落在<strong>次日</strong> {@code 05:00}。
     *
     * <p>若按 0 点到 0 点切分，跨夜的一次连续工作会被截成两段。
     */
    @Test
    void windowRunsFromFiveAmToFiveAmNextDay() {
        UsageHourlySeries result = series("2026-07-28");

        assertThat(result.date()).isEqualTo("2026-07-28");
        assertThat(result.windowStart()).isEqualTo("2026-07-28T05:00:00");
        assertThat(result.windowEnd()).isEqualTo("2026-07-29T05:00:00");
    }

    /**
     * 25 个点位，首尾同为 05:00。
     *
     * <p>这不是差一错误：点以整点为中心聚合，首点覆盖 {@code [05:00, 05:30)}、
     * 末点覆盖 {@code [04:30, 05:00)}，两者相加恰好一小时，故 24 小时装 25 个点。
     */
    @Test
    void seriesHasTwentyFivePointsWithMatchingEnds() {
        UsageHourlySeries result = series("2026-07-28");

        assertThat(result.points()).hasSize(25);
        assertThat(result.points().get(0).bucket()).isEqualTo("2026-07-28T05:00:00");
        assertThat(result.points().get(24).bucket()).isEqualTo("2026-07-29T05:00:00");
    }

    /**
     * 点位逐小时递增，跨午夜时日期正确翻到次日。
     *
     * <p>这也是 bucket 必须带完整日期的原因：只给 {@code HH:mm} 时 {@code 01:00}
     * 分不清属于锚定日还是次日。
     */
    @Test
    void pointsAdvanceHourlyAndRollOverMidnight() {
        UsageHourlySeries result = series("2026-07-28");
        List<String> buckets = result.points().stream().map(UsageHourlyPoint::bucket).toList();

        assertThat(buckets.get(18)).isEqualTo("2026-07-28T23:00:00");
        assertThat(buckets.get(19)).isEqualTo("2026-07-29T00:00:00");
        assertThat(buckets.get(23)).isEqualTo("2026-07-29T04:00:00");
    }

    /** 跨月同样按日历推进，不对日期串做算术。 */
    @Test
    void windowCrossesMonthBoundaryByCalendar() {
        UsageHourlySeries result = service.buildHourlySeries(
                LocalDateTime.of(2026, 8, 5, 12, 0), "2026-07-31");

        assertThat(result.windowStart()).isEqualTo("2026-07-31T05:00:00");
        assertThat(result.windowEnd()).isEqualTo("2026-08-01T05:00:00");
        assertThat(result.points().get(24).bucket()).isEqualTo("2026-08-01T05:00:00");
    }

    // ---------- 默认日期与 5 点回退 ----------

    /** 缺省日期时用当前窗口的锚定日；14:30 属于当天那一轮。 */
    @Test
    void missingDateFallsBackToCurrentWindowAnchor() {
        assertThat(series(null).date()).isEqualTo("2026-07-31");
        assertThat(series("").date()).isEqualTo("2026-07-31");
        assertThat(series("   ").date()).isEqualTo("2026-07-31");
    }

    /**
     * 凌晨 5 点<strong>之前</strong>，「当前那一天」回退一天。
     *
     * <p>凌晨 3 点打开页面，看到的应是昨晚以来的连续曲线，而不是刚开始 3 小时的空图。
     * 这正是 5 点起算要解决的问题。
     */
    @Test
    void beforeFiveAmDefaultAnchorIsPreviousDay() {
        UsageHourlySeries result = service.buildHourlySeries(
                LocalDateTime.of(2026, 7, 31, 3, 0), null);

        assertThat(result.date()).isEqualTo("2026-07-30");
        assertThat(result.windowStart()).isEqualTo("2026-07-30T05:00:00");
        assertThat(result.isCurrentWindow()).isTrue();
    }

    /** 恰好 05:00 已属于新一轮，不再回退。 */
    @Test
    void atExactlyFiveAmAnchorIsToday() {
        UsageHourlySeries result = service.buildHourlySeries(
                LocalDateTime.of(2026, 7, 31, 5, 0), null);

        assertThat(result.date()).isEqualTo("2026-07-31");
    }

    /** 04:59:59 仍属上一轮 —— 与 05:00 一起夹住分界线。 */
    @Test
    void oneSecondBeforeFiveAmStillBelongsToPreviousDay() {
        UsageHourlySeries result = service.buildHourlySeries(
                LocalDateTime.of(2026, 7, 31, 4, 59, 59), null);

        assertThat(result.date()).isEqualTo("2026-07-30");
    }

    // ---------- 日期收敛 ----------

    /** 晚于今天的日期收敛到今天：未来没有数据。 */
    @Test
    void futureDateIsClampedToToday() {
        assertThat(series("2026-08-15").date()).isEqualTo("2026-07-31");
        assertThat(series("2027-01-01").date()).isEqualTo("2026-07-31");
    }

    /**
     * 早于可回看范围的日期收敛到最早那天（15 天前，即 07-17）。
     *
     * <p>下界与 {@link SlidingDateWindow#earliestReachable} 对齐 —— 若这里能查到更早的
     * 日期，就会出现「柱状图翻不到、时段图却查得出」的不一致。
     */
    @Test
    void tooOldDateIsClampedToEarliestReachable() {
        assertThat(series("2026-01-01").date()).isEqualTo("2026-07-17");
        assertThat(series("2026-07-16").date()).isEqualTo("2026-07-17");
    }

    /** 边界日 07-17 本身可查，不被误收敛。 */
    @Test
    void earliestReachableDateItselfIsAccepted() {
        assertThat(series("2026-07-17").date()).isEqualTo("2026-07-17");
    }

    /** 畸形日期回落到当前窗口，而非抛异常 —— 这是展示型查询。 */
    @Test
    void malformedDateFallsBackToCurrentWindow() {
        assertThat(series("not-a-date").date()).isEqualTo("2026-07-31");
        assertThat(series("2026-13-45").date()).isEqualTo("2026-07-31");
        assertThat(series("07/28/2026").date()).isEqualTo("2026-07-31");
        assertThat(series("2026-7-28").date()).isEqualTo("2026-07-31");
    }

    /** 首尾空白被容忍。 */
    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertThat(series("  2026-07-28  ").date()).isEqualTo("2026-07-28");
    }

    // ---------- isCurrentWindow ----------

    /**
     * 只有当前那一轮为 true；过去的窗口数据已固化，调用方不必建流。
     */
    @Test
    void isCurrentWindowOnlyForOngoingRound() {
        assertThat(series("2026-07-31").isCurrentWindow()).isTrue();
        assertThat(series("2026-07-30").isCurrentWindow()).isFalse();
        assertThat(series("2026-07-17").isCurrentWindow()).isFalse();
    }

    /**
     * 凌晨 5 点前，「今天」这个日期<strong>不是</strong>当前窗口。
     *
     * <p>03:00 时当前那一轮锚定在 07-30；显式请求 07-31 得到的是一个尚未开始的窗口，
     * 应判为非当前。若这里用「anchor == today」判断就会错，那是本项要挡住的写法。
     */
    @Test
    void beforeFiveAmTodayIsNotTheCurrentWindow() {
        LocalDateTime earlyMorning = LocalDateTime.of(2026, 7, 31, 3, 0);

        assertThat(service.buildHourlySeries(earlyMorning, "2026-07-31").isCurrentWindow()).isFalse();
        assertThat(service.buildHourlySeries(earlyMorning, "2026-07-30").isCurrentWindow()).isTrue();
    }

    // ---------- 补零 ----------

    /** 无数据时 25 个点位全为零，而非空列表 —— 空列表会让折线整条消失。 */
    @Test
    void emptyWindowIsFullyPaddedWithZeros() {
        UsageHourlySeries result = series("2026-07-28");

        assertThat(result.points()).hasSize(25);
        assertThat(result.points()).allSatisfy(point -> {
            assertThat(point.inputTokens()).isZero();
            assertThat(point.outputTokens()).isZero();
        });
    }

    /** 有数据的整点落在正确的下标上，其余补零。 */
    @Test
    void snapshotPointsLandOnCorrectIndexes() {
        when(usageRepository.aggregateHourlyTokens(anyString(), anyString())).thenReturn(List.of(
                new UsageHourlyPoint("2026-07-28T05:00:00", 10L, 1L),
                new UsageHourlyPoint("2026-07-28T14:00:00", 200L, 40L),
                new UsageHourlyPoint("2026-07-29T05:00:00", 30L, 3L)));

        UsageHourlySeries result = series("2026-07-28");

        assertThat(result.points().get(0).inputTokens()).isEqualTo(10L);
        assertThat(result.points().get(9).inputTokens()).isEqualTo(200L);   // 05 + 9 = 14 点
        assertThat(result.points().get(24).inputTokens()).isEqualTo(30L);
        assertThat(result.points().get(1).inputTokens()).isZero();
    }

    /** 跨午夜那一段的数据也能正确落位。 */
    @Test
    void pointsAfterMidnightLandCorrectly() {
        when(usageRepository.aggregateHourlyTokens(anyString(), anyString())).thenReturn(List.of(
                new UsageHourlyPoint("2026-07-29T02:00:00", 77L, 7L)));

        UsageHourlySeries result = series("2026-07-28");

        // 05:00 起第 21 个点是次日 02:00
        assertThat(result.points().get(21).bucket()).isEqualTo("2026-07-29T02:00:00");
        assertThat(result.points().get(21).inputTokens()).isEqualTo(77L);
    }

    /**
     * 仓储若返回窗口外的整点，该行被<strong>忽略</strong>而不会挤进结果。
     *
     * <p>正常不会发生（SQL 已按窗口过滤），但补零若写成「遍历仓储结果」而非
     * 「遍历窗口点位」，越界行就会让横轴悄悄变长。此项固定住遍历方向。
     */
    @Test
    void bucketsOutsideWindowAreIgnored() {
        when(usageRepository.aggregateHourlyTokens(anyString(), anyString())).thenReturn(List.of(
                new UsageHourlyPoint("2026-07-20T10:00:00", 999L, 99L),
                new UsageHourlyPoint("2026-07-28T10:00:00", 5L, 1L)));

        UsageHourlySeries result = series("2026-07-28");

        assertThat(result.points()).hasSize(25);
        assertThat(result.points()).extracting(UsageHourlyPoint::bucket)
                .doesNotContain("2026-07-20T10:00:00");
        assertThat(result.points().get(5).inputTokens()).isEqualTo(5L);
    }

    // ---------- 与仓储的契约 ----------

    /**
     * 传给仓储的区间是 {@code [date 05:00, date+1 05:00)}。
     *
     * <p>右边界恰好等于末点位的时刻，这不是差一错误：末点位居中聚合覆盖
     * {@code [04:30, 05:00)}，而 05:00 整起的数据属于下一轮窗口的首点。
     */
    @Test
    void repositoryReceivesFiveAmToFiveAmWindow() {
        series("2026-07-28");

        ArgumentCaptor<String> start = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> end = ArgumentCaptor.forClass(String.class);
        verify(usageRepository).aggregateHourlyTokens(start.capture(), end.capture());

        assertThat(start.getValue()).isEqualTo("2026-07-28T05:00:00");
        assertThat(end.getValue()).isEqualTo("2026-07-29T05:00:00");
    }

    /** 查询区间与响应体宣告的窗口一致 —— 两者若漂移，用户看到的轴与数据就不匹配。 */
    @Test
    void queryWindowMatchesDeclaredWindow() {
        UsageHourlySeries result = series("2026-07-28");

        ArgumentCaptor<String> start = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> end = ArgumentCaptor.forClass(String.class);
        verify(usageRepository).aggregateHourlyTokens(start.capture(), end.capture());

        assertThat(start.getValue()).isEqualTo(result.windowStart());
        assertThat(end.getValue()).isEqualTo(result.windowEnd());
    }

    /**
     * 响应式入口可用。
     *
     * <p>本类其余用例都把 {@code now} 显式喂给 {@code buildHourlySeries}，唯独这里不能 ——
     * 要验的正是「响应式入口自己取当前时刻」这件事，注入时刻就把被测行为绕过去了。
     *
     * <p>因此日期必须<strong>相对当前</strong>算，不能写死：写死的日期会随着真实时间推移
     * 滑出可回看窗口，届时被 {@code resolveHourlyAnchorDate} 收敛到边界日，
     * 于是断言在某天之后突然开始失败，而代码从未改动。取「昨天」是因为它恒落在
     * {@code [earliestReachable, today]} 之内（窗口有两周），必然被原样回显。
     */
    @Test
    void reactiveEntryPointReturnsSeries() {
        String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);

        UsageHourlySeries result = service.getHourlySeries(yesterday).block();

        assertThat(result).isNotNull();
        assertThat(result.date()).isEqualTo(yesterday);
        assertThat(result.points()).hasSize(25);
    }
}

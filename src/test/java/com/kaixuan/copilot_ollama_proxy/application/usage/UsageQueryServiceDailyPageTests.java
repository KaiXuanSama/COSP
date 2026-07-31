package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageDailyPage;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageDailyPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证与锁定：「近 N 日」折线的滑动窗口分页 —— 重点在<strong>补零</strong>与窗口共享。
 *
 * <p>窗口规则本身已由 {@code SlidingDateWindowTests} 穷举，这里不重复那份边界矩阵，
 * 只确认三件事：
 * <ul>
 *   <li>确实经由共享的窗口解析（同参数下与柱状图端点落到同一区间）；</li>
 *   <li>补零后 {@code points} 长度恒等于 {@code size}，且首尾日期即窗口边界；</li>
 *   <li>传给仓储的是右开边界。</li>
 * </ul>
 *
 * <p>补零是本端点与柱状图端点的唯一形状差异，也是最容易出错的地方：漏补会让折线
 * 在空日处「跳过」，横轴不再等距，而图仍然画得出来 —— 不会有任何报错。
 */
class UsageQueryServiceDailyPageTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 31);

    private ApiCallUsageRepository usageRepository;
    private UsageQueryService service;

    @BeforeEach
    void setUp() {
        usageRepository = mock(ApiCallUsageRepository.class);
        service = new UsageQueryService(
                mock(ApiUsageRepository.class), usageRepository, new UsageEventPublisher());
        when(usageRepository.aggregateDailyTokens(anyString(), anyString())).thenReturn(List.of());
    }

    private UsageDailyPage page(int size, int offset) {
        return service.buildDailyPage(TODAY, size, offset);
    }

    // ---------- 窗口（确认共享，不重复边界矩阵） ----------

    /** 默认窗口与柱状图端点一致：含今天的最近 7 天。 */
    @Test
    void defaultWindowMatchesBreakdownPage() {
        UsageDailyPage result = page(7, 0);

        assertThat(result.startDate()).isEqualTo("2026-07-25");
        assertThat(result.endDate()).isEqualTo("2026-07-31");
        assertThat(result.size()).isEqualTo(7);
        assertThat(result.offset()).isZero();
        assertThat(result.includesToday()).isTrue();
    }

    /**
     * 同参数下两个端点的窗口与翻页标志<strong>逐字段相同</strong>。
     *
     * <p>这是共用 {@link SlidingDateWindow} 的意义所在：前端可以用一份翻页状态
     * 同时驱动柱状图与折线。若两处各写一遍钳制，改上限时漏掉一处不会有编译错误，
     * 只会让两张图落到不同区间，而它们看起来仍然都能正常渲染。
     */
    @Test
    void windowMetadataAgreesWithBreakdownPageForEveryPosition() {
        when(usageRepository.aggregateBreakdownBetween(anyString(), anyString())).thenReturn(List.of());

        for (int size : new int[] { 7, 10, 15 }) {
            for (int offset : new int[] { 0, 1, 5, 8, 99, -3 }) {
                UsageDailyPage daily = service.buildDailyPage(TODAY, size, offset);
                var breakdown = service.buildBreakdownPage(TODAY, size, offset);

                assertThat(daily.startDate()).as("size=%d offset=%d start", size, offset)
                        .isEqualTo(breakdown.startDate());
                assertThat(daily.endDate()).isEqualTo(breakdown.endDate());
                assertThat(daily.size()).isEqualTo(breakdown.size());
                assertThat(daily.offset()).isEqualTo(breakdown.offset());
                assertThat(daily.includesToday()).isEqualTo(breakdown.includesToday());
                assertThat(daily.hasNewer()).isEqualTo(breakdown.hasNewer());
                assertThat(daily.hasOlder()).isEqualTo(breakdown.hasOlder());
            }
        }
    }

    /** 偏移让整个窗口后退，且不再含今天（前端据此不建流）。 */
    @Test
    void offsetWindowExcludesToday() {
        UsageDailyPage result = page(7, 1);

        assertThat(result.startDate()).isEqualTo("2026-07-24");
        assertThat(result.endDate()).isEqualTo("2026-07-30");
        assertThat(result.includesToday()).isFalse();
        assertThat(result.hasNewer()).isTrue();
    }

    // ---------- 补零 ----------

    /**
     * 完全没有数据时仍返回 {@code size} 个零点位，日期连续覆盖整个窗口。
     *
     * <p>返回空列表会让折线整条消失，而「这段时间确实没有调用」与「查询失败」
     * 在视觉上就无法区分了。
     */
    @Test
    void emptyWindowIsFullyPaddedWithZeros() {
        UsageDailyPage result = page(7, 0);

        assertThat(result.points()).hasSize(7);
        assertThat(result.points()).extracting(UsageDailyPoint::date)
                .containsExactly("2026-07-25", "2026-07-26", "2026-07-27",
                        "2026-07-28", "2026-07-29", "2026-07-30", "2026-07-31");
        assertThat(result.points()).allSatisfy(point -> {
            assertThat(point.callCount()).isZero();
            assertThat(point.inputTokens()).isZero();
            assertThat(point.outputTokens()).isZero();
        });
    }

    /**
     * 中间的空日被补零而非跳过 —— 折线按点位等距绘制，跳过会让「隔了几天」的信息丢失。
     */
    @Test
    void gapsInsideWindowArePaddedInPlace() {
        when(usageRepository.aggregateDailyTokens(anyString(), anyString())).thenReturn(List.of(
                new UsageDailyPoint("2026-07-25", 3L, 300L, 90L),
                new UsageDailyPoint("2026-07-29", 5L, 500L, 150L)));

        UsageDailyPage result = page(7, 0);

        assertThat(result.points()).hasSize(7);
        assertThat(result.points()).extracting(UsageDailyPoint::inputTokens)
                .containsExactly(300L, 0L, 0L, 0L, 500L, 0L, 0L);
        // 有数据的日期原样保留次数，不被补零覆盖
        assertThat(result.points().get(0).callCount()).isEqualTo(3L);
        assertThat(result.points().get(4).callCount()).isEqualTo(5L);
    }

    /** 首尾两端的数据不被补零挤掉，位置也不偏移。 */
    @Test
    void boundaryDatesKeepTheirData() {
        when(usageRepository.aggregateDailyTokens(anyString(), anyString())).thenReturn(List.of(
                new UsageDailyPoint("2026-07-25", 1L, 10L, 1L),
                new UsageDailyPoint("2026-07-31", 2L, 20L, 2L)));

        UsageDailyPage result = page(7, 0);

        assertThat(result.points().get(0).inputTokens()).isEqualTo(10L);
        assertThat(result.points().get(6).inputTokens()).isEqualTo(20L);
    }

    /**
     * 不变量：{@code points.size() == size}，首尾日期恰为窗口边界 —— 对每个合法宽度成立。
     */
    @Test
    void pointsLengthAlwaysEqualsWindowSize() {
        for (int size = 7; size <= 15; size++) {
            UsageDailyPage result = page(size, 0);

            assertThat(result.points()).as("size=%d", size).hasSize(size);
            assertThat(result.points().get(0).date()).isEqualTo(result.startDate());
            assertThat(result.points().get(size - 1).date()).isEqualTo(result.endDate());
        }
    }

    /** 钳制后长度跟随<strong>实际</strong>宽度，而非请求宽度。 */
    @Test
    void pointsLengthFollowsClampedSize() {
        assertThat(page(1, 0).points()).hasSize(7);
        assertThat(page(9999, 0).points()).hasSize(15);
    }

    /**
     * 仓储若返回窗口外的日期，该行被<strong>忽略</strong>而不会挤进结果。
     *
     * <p>正常不会发生（SQL 已按窗口过滤），但补零实现若写成「遍历仓储结果」而非
     * 「遍历窗口日期」，就会让越界行悄悄延长横轴。此项固定住遍历方向。
     */
    @Test
    void datesOutsideWindowAreIgnored() {
        when(usageRepository.aggregateDailyTokens(anyString(), anyString())).thenReturn(List.of(
                new UsageDailyPoint("2026-07-01", 9L, 900L, 270L),
                new UsageDailyPoint("2026-07-26", 1L, 100L, 30L)));

        UsageDailyPage result = page(7, 0);

        assertThat(result.points()).hasSize(7);
        assertThat(result.points()).extracting(UsageDailyPoint::date).doesNotContain("2026-07-01");
        assertThat(result.points()).extracting(UsageDailyPoint::inputTokens)
                .containsExactly(0L, 100L, 0L, 0L, 0L, 0L, 0L);
    }

    // ---------- 与仓储的契约 ----------

    /** 传给仓储的右边界是结束日的次日（仓储侧右开）。 */
    @Test
    void repositoryReceivesExclusiveEndBoundary() {
        page(7, 0);

        ArgumentCaptor<String> start = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> end = ArgumentCaptor.forClass(String.class);
        verify(usageRepository).aggregateDailyTokens(start.capture(), end.capture());

        assertThat(start.getValue()).isEqualTo("2026-07-25");
        assertThat(end.getValue()).isEqualTo("2026-08-01");
    }

    /** 用的是按日聚合而非明细聚合 —— 折线不需要供应商/模型维度。 */
    @Test
    void usesDailyAggregationNotBreakdown() {
        page(7, 0);

        verify(usageRepository).aggregateDailyTokens(anyString(), anyString());
        org.mockito.Mockito.verifyNoMoreInteractions(usageRepository);
    }

    /** 响应式入口可用，走 boundedElastic 后返回同一份结果。 */
    @Test
    void reactiveEntryPointReturnsPage() {
        UsageDailyPage result = service.getUsageDailyPage(7, 0).block();

        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(7);
        assertThat(result.points()).hasSize(7);
    }
}

package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownPage;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownRow;
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
 * 验证与锁定：可滑动窗口的用量明细分页。
 *
 * <p>这份分页与常见的 {@code LIMIT/OFFSET} 分页不同 —— 「页」是<strong>时间区间</strong>，
 * 由宽度与向过去的偏移两个自由度确定。因此测试重点不在行数，而在：
 * <ul>
 *   <li>两个参数的钳制及其<strong>耦合</strong>（偏移上界依赖宽度）；</li>
 *   <li>钳制后的窗口边界是否落在正确的日期上（闭区间，仓储侧右开）；</li>
 *   <li>翻页可用性与 {@code includesToday} 三个派生标志。</li>
 * </ul>
 *
 * <p>全部断言都在固定「今天」下进行（{@code buildBreakdownPage} 显式接受 today），
 * 否则边界断言会随运行日期漂移。
 */
class UsageQueryServiceBreakdownPageTests {

    /** 固定基准日，选周中的普通一天，避免任何月末/年末的巧合掩盖偏移错误。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 31);

    private ApiCallUsageRepository usageRepository;
    private UsageQueryService service;

    @BeforeEach
    void setUp() {
        usageRepository = mock(ApiCallUsageRepository.class);
        service = new UsageQueryService(
                mock(ApiUsageRepository.class), usageRepository, new UsageEventPublisher());
        when(usageRepository.aggregateBreakdownBetween(anyString(), anyString())).thenReturn(List.of());
    }

    private UsageBreakdownPage page(int size, int offset) {
        return service.buildBreakdownPage(TODAY, size, offset);
    }

    /** 捕获实际传给仓储的两个日期边界。 */
    private List<String> capturedBounds(int size, int offset) {
        page(size, offset);
        ArgumentCaptor<String> start = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> end = ArgumentCaptor.forClass(String.class);
        verify(usageRepository).aggregateBreakdownBetween(start.capture(), end.capture());
        return List.of(start.getValue(), end.getValue());
    }

    // ---------- 窗口边界 ----------

    /**
     * 默认窗口是「含今天的最近 7 天」。
     *
     * <p>7-25 到 7-31 共 7 天（含两端），这是宽度为 7 的闭区间在 {@code offset = 0} 时的位置。
     */
    @Test
    void defaultWindowIsLatestSevenDaysInclusive() {
        UsageBreakdownPage result = page(7, 0);

        assertThat(result.startDate()).isEqualTo("2026-07-25");
        assertThat(result.endDate()).isEqualTo("2026-07-31");
        assertThat(result.size()).isEqualTo(7);
        assertThat(result.offset()).isZero();
    }

    /**
     * 偏移 1 让整个窗口后退一天，宽度不变 —— 这正是「1-7 滑到 2-8」的语义。
     *
     * <p>注意两端<strong>同时</strong>移动：若只动一端，宽度就变了，那是缩放而非滑动。
     */
    @Test
    void offsetSlidesWholeWindowIntoThePast() {
        UsageBreakdownPage result = page(7, 1);

        assertThat(result.startDate()).isEqualTo("2026-07-24");
        assertThat(result.endDate()).isEqualTo("2026-07-30");
        assertThat(result.size()).isEqualTo(7);
    }

    /**
     * 宽度 7 时偏移可取到的最大值是 8，此时窗口覆盖「第 9~15 天」。
     *
     * <p>这是滑动范围的另一端：再退一天就会跌出 15 天的可回看池。
     */
    @Test
    void maximumOffsetForMinimumSizeReachesFifteenthDayBack() {
        UsageBreakdownPage result = page(7, 8);

        assertThat(result.startDate()).isEqualTo("2026-07-17");
        assertThat(result.endDate()).isEqualTo("2026-07-23");
        assertThat(result.offset()).isEqualTo(8);
    }

    /** 宽度取满 15 时窗口铺满整个池子，起点为 15 天前。 */
    @Test
    void maximumSizeCoversWholeLookbackPool() {
        UsageBreakdownPage result = page(15, 0);

        assertThat(result.startDate()).isEqualTo("2026-07-17");
        assertThat(result.endDate()).isEqualTo("2026-07-31");
        assertThat(result.size()).isEqualTo(15);
    }

    // ---------- 宽度钳制 ----------

    /** 宽度低于 7 被抬到 7：更窄的窗口在概览上看不出趋势。 */
    @Test
    void sizeBelowMinimumIsClampedToSeven() {
        assertThat(page(1, 0).size()).isEqualTo(7);
        assertThat(page(0, 0).size()).isEqualTo(7);
        assertThat(page(-5, 0).size()).isEqualTo(7);
    }

    /** 宽度高于 15 被压到 15：这同时是 SQLite 聚合成本的护栏。 */
    @Test
    void sizeAboveMaximumIsClampedToFifteen() {
        assertThat(page(16, 0).size()).isEqualTo(15);
        assertThat(page(9999, 0).size()).isEqualTo(15);
    }

    // ---------- 偏移钳制（与宽度耦合） ----------

    /** 负偏移会让窗口伸向未来，一律收敛到 0。 */
    @Test
    void negativeOffsetIsClampedToZero() {
        UsageBreakdownPage result = page(7, -3);

        assertThat(result.offset()).isZero();
        assertThat(result.endDate()).isEqualTo("2026-07-31");
    }

    /**
     * 偏移上界随宽度收紧 —— 这是本分页最容易写错的一处。
     *
     * <p>约束是 {@code size + offset <= 15}：宽度 7 时上界为 8，宽度 10 时降到 5，
     * 宽度取满 15 时只剩 0（窗口唯一合法位置）。若把上界写成与宽度无关的常量，
     * 大宽度加大偏移就会把起点推到 15 天以外，且不会有任何报错。
     */
    @Test
    void offsetUpperBoundShrinksAsSizeGrows() {
        assertThat(page(7, 99).offset()).isEqualTo(8);
        assertThat(page(10, 99).offset()).isEqualTo(5);
        assertThat(page(15, 99).offset()).isZero();
    }

    /** 钳制后的窗口起点恒为池子边界（15 天前），与宽度无关。 */
    @Test
    void clampedWindowAlwaysStartsAtPoolBoundary() {
        assertThat(page(7, 99).startDate()).isEqualTo("2026-07-17");
        assertThat(page(10, 99).startDate()).isEqualTo("2026-07-17");
        assertThat(page(15, 99).startDate()).isEqualTo("2026-07-17");
    }

    // ---------- 派生标志 ----------

    /** {@code offset = 0} 时窗口含今天，数据仍在变化，前端据此接上 SSE 增量流。 */
    @Test
    void includesTodayOnlyWhenOffsetIsZero() {
        assertThat(page(7, 0).includesToday()).isTrue();
        assertThat(page(7, 1).includesToday()).isFalse();
        assertThat(page(7, 8).includesToday()).isFalse();
    }

    /** 已在最新位置时不能再往「更近」滑。 */
    @Test
    void hasNewerIsFalseAtLatestWindow() {
        assertThat(page(7, 0).hasNewer()).isFalse();
        assertThat(page(7, 1).hasNewer()).isTrue();
    }

    /**
     * {@code hasOlder} 在窗口触及池子边界时转为 false，判定式为 {@code size + offset < 15}。
     *
     * <p>宽度取满 15 时即便偏移为 0 也已无处可退 —— 这一项能挡住「只看 offset 就判断能否往前」
     * 的写法。
     */
    @Test
    void hasOlderReflectsRemainingRoomInPool() {
        assertThat(page(7, 0).hasOlder()).isTrue();
        assertThat(page(7, 7).hasOlder()).isTrue();
        assertThat(page(7, 8).hasOlder()).isFalse();
        assertThat(page(15, 0).hasOlder()).isFalse();
    }

    // ---------- 与仓储的契约 ----------

    /**
     * 传给仓储的右边界是<strong>结束日的次日</strong>，因为仓储侧是右开区间。
     *
     * <p>少加这一天会静默丢掉窗口最后一天的全部数据，且图表照样能画出来 ——
     * 只是最右一根柱子永远是空的。
     */
    @Test
    void repositoryReceivesExclusiveEndBoundary() {
        assertThat(capturedBounds(7, 0)).containsExactly("2026-07-25", "2026-08-01");
    }

    /**
     * 窗口跨月时按日历推进，而非对日期串做算术。
     *
     * <p>以 8-03 为今天、宽度 7、偏移 0，起点应回到 7-28 —— 若用「日 - 6」这类
     * 字符串式计算，会得到不存在的 {@code 2026-08--3}。
     */
    @Test
    void windowCrossesMonthBoundaryByCalendar() {
        UsageBreakdownPage result = service.buildBreakdownPage(LocalDate.of(2026, 8, 3), 7, 0);

        assertThat(result.startDate()).isEqualTo("2026-07-28");
        assertThat(result.endDate()).isEqualTo("2026-08-03");
    }

    /** 仓储结果原样返回，用例层不做汇总或排序加工（三级 pivot 全在前端）。 */
    @Test
    void repositoryRowsAreReturnedUnmodified() {
        List<UsageBreakdownRow> rows = List.of(
                new UsageBreakdownRow("2026-07-25", "deepseek", "chat", 12L, 1200L, 340L),
                new UsageBreakdownRow("2026-07-26", "zhipu", "glm", 3L, 300L, 90L));
        when(usageRepository.aggregateBreakdownBetween(anyString(), anyString())).thenReturn(rows);

        assertThat(page(7, 0).rows()).containsExactlyElementsOf(rows);
    }

    /** 响应式包装可用：getUsageBreakdownPage 走 boundedElastic 后仍返回同一份结果。 */
    @Test
    void reactiveEntryPointReturnsPage() {
        UsageBreakdownPage result = service.getUsageBreakdownPage(7, 0).block();

        assertThat(result).isNotNull();
        assertThat(result.size()).isEqualTo(7);
        assertThat(result.offset()).isZero();
        assertThat(result.includesToday()).isTrue();
    }
}

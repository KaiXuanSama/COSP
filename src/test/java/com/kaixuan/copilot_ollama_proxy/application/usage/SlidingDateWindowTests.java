package com.kaixuan.copilot_ollama_proxy.application.usage;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证与锁定：概览图表共用的滑动日期窗口解析。
 *
 * <p>柱状图与「近 N 日」折线两个端点都经由这一份逻辑，因此这里的断言是<strong>唯一</strong>
 * 的窗口规则来源 —— 两个端点各自的测试只需确认「确实用了它」，不必再重复边界矩阵。
 *
 * <p>全部断言在固定「今天」下进行，否则会随运行日期漂移。基准日选月末的 7-31，
 * 好处是任何跨月错误（比如对日期串做算术）都会立刻暴露。
 */
class SlidingDateWindowTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 31);

    private static SlidingDateWindow window(int size, int offset) {
        return SlidingDateWindow.resolve(TODAY, size, offset);
    }

    // ---------- 窗口位置 ----------

    /** 默认位置：含今天的最近 7 天，闭区间 7-25 ~ 7-31。 */
    @Test
    void zeroOffsetEndsAtToday() {
        SlidingDateWindow w = window(7, 0);

        assertThat(w.startText()).isEqualTo("2026-07-25");
        assertThat(w.endText()).isEqualTo("2026-07-31");
    }

    /**
     * 偏移让<strong>两端同时</strong>后退，宽度不变 —— 这正是「1-7 滑到 2-8」的语义。
     *
     * <p>只动一端就是缩放而非滑动，那种错误不会报错，只会让横轴悄悄变长或变短。
     */
    @Test
    void offsetSlidesBothEndsKeepingSize() {
        SlidingDateWindow w = window(7, 1);

        assertThat(w.startText()).isEqualTo("2026-07-24");
        assertThat(w.endText()).isEqualTo("2026-07-30");
        assertThat(w.size()).isEqualTo(7);
    }

    /** 宽度 7 时偏移取到上界 8，窗口覆盖「第 9~15 天」，正好触及池子边界。 */
    @Test
    void maximumOffsetReachesPoolBoundary() {
        SlidingDateWindow w = window(7, 8);

        assertThat(w.startText()).isEqualTo("2026-07-17");
        assertThat(w.endText()).isEqualTo("2026-07-23");
    }

    /** 宽度取满 15 时窗口铺满整个池子。 */
    @Test
    void maximumSizeCoversWholePool() {
        SlidingDateWindow w = window(15, 0);

        assertThat(w.startText()).isEqualTo("2026-07-17");
        assertThat(w.endText()).isEqualTo("2026-07-31");
    }

    /**
     * 跨月按日历推进，而非对日期串做算术。
     *
     * <p>以 8-03 为今天、宽度 7，起点应回到 7-28；字符串式的「日减 6」会得到
     * 不存在的 {@code 2026-08--3}。
     */
    @Test
    void windowCrossesMonthBoundaryByCalendar() {
        SlidingDateWindow w = SlidingDateWindow.resolve(LocalDate.of(2026, 8, 3), 7, 0);

        assertThat(w.startText()).isEqualTo("2026-07-28");
        assertThat(w.endText()).isEqualTo("2026-08-03");
    }

    // ---------- 钳制 ----------

    /** 宽度低于 7 抬到 7：更窄的窗口在概览上看不出趋势。 */
    @Test
    void sizeBelowMinimumIsClamped() {
        assertThat(window(1, 0).size()).isEqualTo(7);
        assertThat(window(0, 0).size()).isEqualTo(7);
        assertThat(window(-5, 0).size()).isEqualTo(7);
    }

    /** 宽度高于 15 压到 15：这同时是 SQLite 聚合成本的护栏。 */
    @Test
    void sizeAboveMaximumIsClamped() {
        assertThat(window(16, 0).size()).isEqualTo(15);
        assertThat(window(9999, 0).size()).isEqualTo(15);
    }

    /** 负偏移会让窗口伸向未来，一律收敛到 0。 */
    @Test
    void negativeOffsetIsClampedToZero() {
        SlidingDateWindow w = window(7, -3);

        assertThat(w.offset()).isZero();
        assertThat(w.endText()).isEqualTo("2026-07-31");
    }

    /**
     * 偏移上界随宽度收紧 —— 本类型最容易写错的一处。
     *
     * <p>约束是 {@code size + offset <= 15}：宽度 7 时上界 8，宽度 10 时降到 5，
     * 宽度取满 15 时只剩 0。若把上界写成与宽度无关的常量，大宽度加大偏移
     * 就会把起点推到池子以外，且不会有任何报错。
     */
    @Test
    void offsetUpperBoundShrinksAsSizeGrows() {
        assertThat(window(7, 99).offset()).isEqualTo(8);
        assertThat(window(10, 99).offset()).isEqualTo(5);
        assertThat(window(15, 99).offset()).isZero();
    }

    /** 无论宽度多少，钳制到极限后起点恒为池子边界（15 天前）。 */
    @Test
    void clampedWindowAlwaysStartsAtPoolBoundary() {
        assertThat(window(7, 99).startText()).isEqualTo("2026-07-17");
        assertThat(window(10, 99).startText()).isEqualTo("2026-07-17");
        assertThat(window(15, 99).startText()).isEqualTo("2026-07-17");
    }

    // ---------- 派生标志 ----------

    /** 含今天当且仅当偏移为 0 —— 调用方据此决定是否接 SSE 增量流。 */
    @Test
    void includesTodayOnlyAtZeroOffset() {
        assertThat(window(7, 0).includesToday()).isTrue();
        assertThat(window(7, 1).includesToday()).isFalse();
        assertThat(window(7, 8).includesToday()).isFalse();
    }

    /** 已在最新位置时不能再往「更近」滑。 */
    @Test
    void hasNewerReflectsOffset() {
        assertThat(window(7, 0).hasNewer()).isFalse();
        assertThat(window(7, 1).hasNewer()).isTrue();
    }

    /**
     * {@code hasOlder} 看的是池子剩余空间（{@code size + offset < 15}），不是只看 offset。
     *
     * <p>宽度取满 15 时即便偏移为 0 也已无处可退 —— 这一项能挡住「只看 offset」的写法。
     */
    @Test
    void hasOlderReflectsRemainingRoom() {
        assertThat(window(7, 0).hasOlder()).isTrue();
        assertThat(window(7, 7).hasOlder()).isTrue();
        assertThat(window(7, 8).hasOlder()).isFalse();
        assertThat(window(15, 0).hasOlder()).isFalse();
    }

    // ---------- 查询边界 ----------

    /**
     * 右开边界是结束日的<strong>次日</strong>。
     *
     * <p>少加这一天会静默丢掉窗口最后一天的全部数据，而图表照样能画出来 ——
     * 只是最右一个点永远是零。
     */
    @Test
    void exclusiveEndIsDayAfterEnd() {
        assertThat(window(7, 0).exclusiveEndText()).isEqualTo("2026-08-01");
        assertThat(window(7, 1).exclusiveEndText()).isEqualTo("2026-07-31");
    }

    /** 闭区间的天数恰等于 size：{@code end - start + 1 == size}。 */
    @Test
    void inclusiveRangeSpansExactlySizeDays() {
        for (int size = 7; size <= 15; size++) {
            SlidingDateWindow w = window(size, 0);
            long span = java.time.temporal.ChronoUnit.DAYS.between(w.start(), w.end()) + 1;
            assertThat(span).as("size=%d", size).isEqualTo(size);
        }
    }
}

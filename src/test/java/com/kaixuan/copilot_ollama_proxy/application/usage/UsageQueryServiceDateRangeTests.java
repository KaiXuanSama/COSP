package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageDateRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证与锁定：用量日期范围的两组字段与不对称的推导规则。
 *
 * <p>本组测试的核心是把「数据事实」与「可选范围」的分工钉住：
 * <ul>
 *   <li>下界取 {@code max(数据下界, 回看深度下界)} —— 两个限制都要满足；</li>
 *   <li>上界<strong>恒为今天</strong>，与数据上界无关；</li>
 *   <li>表为空时两端同时收敛到今天。</li>
 * </ul>
 * 若把上界也写成取数据上界，今天尚无调用时页面一打开就处在不可选的位置上 ——
 * 而这个 bug 只在「今天还没发生调用」的时段出现，很难被偶然发现。
 *
 * <p>断言全部在固定「今天」下进行（{@code buildDateRange} 显式接受它），
 * 否则可选范围的断言会随运行日期失效。
 */
class UsageQueryServiceDateRangeTests {

    /** 固定「今天」。回看深度 15 天，故池子左端为 2026-07-17。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 31);

    /** 与 {@link SlidingDateWindow#MAX_SIZE} 导出的池子左端，写死一份以便回看深度被改动时测试失败。 */
    private static final String EARLIEST_REACHABLE = "2026-07-17";

    private ApiCallUsageRepository usageRepository;
    private UsageQueryService service;

    @BeforeEach
    void setUp() {
        usageRepository = mock(ApiCallUsageRepository.class);
        service = new UsageQueryService(
                mock(ApiUsageRepository.class), usageRepository, new UsageEventPublisher());
    }

    private UsageDateRange rangeWithBounds(String earliest, String latest) {
        when(usageRepository.findDateBounds()).thenReturn(
                earliest == null ? UsageDateBounds.EMPTY : new UsageDateBounds(earliest, latest));
        return service.buildDateRange(TODAY);
    }

    /** 确认池子左端与本测试写死的常量一致，避免回看深度改动后其余断言变得无意义。 */
    @Test
    void earliestReachableMatchesTestConstant() {
        assertThat(SlidingDateWindow.earliestReachable(TODAY))
                .isEqualTo(LocalDate.parse(EARLIEST_REACHABLE));
    }

    // ---------- 数据事实字段 ----------

    /** 数据边界原样透传，不受可选范围钳制影响。 */
    @Test
    void reportsRawBoundsUntouched() {
        UsageDateRange range = rangeWithBounds("2026-05-01", "2026-07-29");

        assertThat(range.hasData()).isTrue();
        assertThat(range.earliestDate()).isEqualTo("2026-05-01");
        assertThat(range.latestDate()).isEqualTo("2026-07-29");
    }

    /** 回带服务端的今天，前端据此换算偏移，不用浏览器时钟。 */
    @Test
    void reportsServerToday() {
        assertThat(rangeWithBounds("2026-07-20", "2026-07-30").today()).isEqualTo("2026-07-31");
    }

    // ---------- 下界：取交集 ----------

    /**
     * 数据比回看深度更久时，下界由<strong>回看深度</strong>决定。
     *
     * <p>否则选择器会放开到分页端点查不动的区间：那些窗口会被钳回最近 15 天，
     * 用户拖到 5 月却看到 7 月的数据。
     */
    @Test
    void selectableStartClampedByLookbackDepth() {
        UsageDateRange range = rangeWithBounds("2026-05-01", "2026-07-30");

        assertThat(range.earliestSelectable()).isEqualTo(EARLIEST_REACHABLE);
    }

    /**
     * 数据比回看深度更短时，下界由<strong>数据</strong>决定。
     *
     * <p>这是本端点存在的理由：新部署只跑了三天，选择器不该放开到 15 天以前 ——
     * 那些日期点进去每一张都是空图。
     */
    @Test
    void selectableStartClampedByActualData() {
        UsageDateRange range = rangeWithBounds("2026-07-28", "2026-07-30");

        assertThat(range.earliestSelectable()).isEqualTo("2026-07-28");
    }

    /** 数据下界恰好落在池子左端时，两个限制一致。 */
    @Test
    void selectableStartWhenDataStartsExactlyAtPoolEdge() {
        UsageDateRange range = rangeWithBounds(EARLIEST_REACHABLE, "2026-07-30");

        assertThat(range.earliestSelectable()).isEqualTo(EARLIEST_REACHABLE);
    }

    // ---------- 上界：恒为今天 ----------

    /**
     * 今天尚无调用时上界仍为今天，而不是数据上界。
     *
     * <p>今天是默认视图，且随时可能产生第一条记录。若因今天无数据就把它标成不可达，
     * 页面一打开就处在一个不可选的位置上，而下一次调用又会让它突然变得可选。
     */
    @Test
    void selectableEndIsTodayEvenWhenTodayHasNoData() {
        UsageDateRange range = rangeWithBounds("2026-07-20", "2026-07-25");

        assertThat(range.latestDate()).isEqualTo("2026-07-25");
        assertThat(range.latestSelectable()).isEqualTo("2026-07-31");
    }

    /** 上界恒等于 today 字段，两者不会分叉。 */
    @Test
    void selectableEndAlwaysEqualsToday() {
        UsageDateRange range = rangeWithBounds("2026-07-20", "2026-07-31");

        assertThat(range.latestSelectable()).isEqualTo(range.today());
    }

    // ---------- 空表 ----------

    /**
     * 表为空时两端同时收敛到今天，选择器退化为只有今天一个可选点。
     *
     * <p>不放开整个回看池：没有任何一天点进去有内容，给出 15 个空日只是误导。
     */
    @Test
    void emptyTableCollapsesSelectableRangeToToday() {
        UsageDateRange range = rangeWithBounds(null, null);

        assertThat(range.hasData()).isFalse();
        assertThat(range.earliestDate()).isNull();
        assertThat(range.latestDate()).isNull();
        assertThat(range.earliestSelectable()).isEqualTo("2026-07-31");
        assertThat(range.latestSelectable()).isEqualTo("2026-07-31");
    }

    // ---------- 反常输入 ----------

    /**
     * 数据下界晚于今天（测试数据或时钟回拨）时，可选区间不会反向。
     *
     * <p>反向区间会让前端的可达点集合为空，选择器整体变成不可操作，且没有任何报错。
     */
    @Test
    void futureDataDoesNotInvertSelectableRange() {
        UsageDateRange range = rangeWithBounds("2026-08-05", "2026-08-09");

        assertThat(range.earliestSelectable()).isEqualTo("2026-07-31");
        assertThat(range.latestSelectable()).isEqualTo("2026-07-31");
    }

    /**
     * 畸形的边界串回落到回看深度，而不是把整个概览打成 500。
     *
     * <p>边界来自 {@code substr(created_at, 1, 10)}，正常必然合法；但历史数据或
     * 其他写入路径可能留下格式不符的 {@code created_at}。
     */
    @Test
    void malformedBoundFallsBackToLookbackDepth() {
        UsageDateRange range = rangeWithBounds("not-a-date", "2026-07-30");

        assertThat(range.earliestSelectable()).isEqualTo(EARLIEST_REACHABLE);
        // 数据事实字段仍原样透传，便于诊断脏数据。
        assertThat(range.earliestDate()).isEqualTo("not-a-date");
    }
}

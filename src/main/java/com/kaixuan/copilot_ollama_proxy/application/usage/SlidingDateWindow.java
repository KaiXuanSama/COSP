package com.kaixuan.copilot_ollama_proxy.application.usage;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 概览图表共用的可滑动日期窗口 —— 宽度与偏移两个自由度确定一段闭区间。
 *
 * <p>抽成独立类型的唯一目的是<strong>让钳制规则只有一份</strong>。柱状图与「近 N 日」折线
 * 是两个端点，若各写一遍 {@code Math.max/min}，改上限时漏掉一处不会有任何编译错误，
 * 只会让两张图在同一次翻页后落到不同区间 —— 而它们看起来仍然都能正常渲染。
 *
 * <h2>窗口语义</h2>
 * {@code offset = 0} 时右端为今天，{@code offset = 1} 整体后退一天，宽度不变。
 * 于是 {@code 1-7}、{@code 2-8}、{@code 9-15} 这类滑动区间都能表达。
 * 两端必须<strong>同时</strong>移动，只动一端是缩放而非滑动。
 *
 * <h2>为什么宽度上限同时是回看深度</h2>
 * 一个常量承担两个职责是有意为之：窗口只能在「最近 {@value #MAX_SIZE} 天」这个池子里滑动，
 * 于是约束自然收敛为 {@code size + offset <= MAX_SIZE}，不需要第三个常量。
 * 宽度取满时窗口恰好只有一个合法位置（{@code offset = 0}）。
 *
 * @param start  窗口起始日期（含）
 * @param end    窗口结束日期（含）
 * @param size   实际宽度（天），已钳制
 * @param offset 实际向过去偏移的天数，已钳制
 */
public record SlidingDateWindow(LocalDate start, LocalDate end, int size, int offset) {

    /**
     * 窗口宽度下限 —— 7 天。
     *
     * <p>概览的横轴需要足够长才看得出趋势，短于一周只是几根孤立的柱子。
     * 这也是既有默认视图的宽度，保持一致可让「不带参数」与「带默认参数」表现相同。
     */
    public static final int MIN_SIZE = 7;

    /**
     * 窗口宽度上限，同时也是最大可回看深度 —— 15 天。
     *
     * <p>也是 SQLite 端的成本护栏：明细聚合的分组键是表达式，命中行数随窗口线性增长
     * 且要建临时 B-tree 排序。放开到数十天时聚合本身会成为瓶颈，那时才需要服务端 top-N。
     */
    public static final int MAX_SIZE = 15;

    /**
     * 日期边界格式，与 {@code api_call_usage.created_at} 的前 10 位对齐。
     *
     * <p>刻意不用 {@code LocalDate.toString()}：那个方法对公元前与超过四位数的年份
     * 会输出带符号或扩展位数的形式，与列格式不一致。虽然实际不会出现，
     * 但显式格式化让「边界串必须与列格式逐字符对齐」这个前提留在代码里 ——
     * 字典序比较一旦格式错位不会报错，只会静默漏掉边界那一天的数据。
     */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 按请求参数解析出实际窗口，越界一律钳制。
     *
     * <h2>钳制顺序不可颠倒</h2>
     * 宽度先钳到 {@code [MIN_SIZE, MAX_SIZE]}，偏移再钳到 {@code [0, MAX_SIZE - size]} ——
     * 偏移的上界依赖已确定的宽度。若把上界写成与宽度无关的常量，大宽度加大偏移
     * 就会把起点推到池子以外，且不会有任何报错。
     *
     * <h2>为何钳制而非抛 400</h2>
     * 这是展示型查询，越界参数几乎只来自前端状态漂移或手工试探，静默收敛到最近的合法窗口
     * 比报错更符合「图表总能画出来」的预期。代价是调用方不能假设拿回的窗口等于自己请求的，
     * 因此响应体必须回带实际窗口。
     *
     * @param today           作为窗口右端基准的当天日期
     * @param requestedSize   请求宽度（天）
     * @param requestedOffset 请求向过去偏移的天数；负值意味着窗口伸向未来，收敛到 0
     */
    public static SlidingDateWindow resolve(LocalDate today, int requestedSize, int requestedOffset) {
        int size = Math.max(MIN_SIZE, Math.min(MAX_SIZE, requestedSize));
        int offset = Math.max(0, Math.min(MAX_SIZE - size, requestedOffset));
        LocalDate end = today.minusDays(offset);
        return new SlidingDateWindow(end.minusDays(size - 1L), end, size, offset);
    }

    /**
     * 窗口是否包含今天。
     *
     * <p>含今天时数据仍在变化，调用方需接上 SSE 增量流；不含今天时数据已固化，
     * 一次查询即为终态，不必建流。
     */
    public boolean includesToday() {
        return offset == 0;
    }

    /** 是否还能往「更近」的方向滑。 */
    public boolean hasNewer() {
        return offset > 0;
    }

    /**
     * 是否还能往「更早」的方向滑。
     *
     * <p>判定式是 {@code size + offset < MAX_SIZE} 而非只看 offset ——
     * 宽度取满时即便偏移为 0 也已无处可退。
     */
    public boolean hasOlder() {
        return size + offset < MAX_SIZE;
    }

    /** 起始日期串，{@code yyyy-MM-dd}，用作查询的闭区间左边界。 */
    public String startText() {
        return start.format(DATE_FORMAT);
    }

    /** 结束日期串，{@code yyyy-MM-dd}，闭区间右端（供响应体展示）。 */
    public String endText() {
        return end.format(DATE_FORMAT);
    }

    /**
     * 结束日期的<strong>次日</strong>，用作查询的右开边界。
     *
     * <p>少加这一天会静默丢掉窗口最后一天的全部数据，而图表照样能画出来 ——
     * 只是最右一个点永远是零。
     */
    public String exclusiveEndText() {
        return end.plusDays(1).format(DATE_FORMAT);
    }
}

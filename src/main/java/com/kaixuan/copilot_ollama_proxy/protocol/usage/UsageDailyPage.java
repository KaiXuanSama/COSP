package com.kaixuan.copilot_ollama_proxy.protocol.usage;

import java.util.List;

/**
 * 可滑动窗口的按日 token 用量分页结果 —— 「近 N 日」折线切换历史区间的数据载体。
 *
 * <p>窗口规则与 {@link UsageBreakdownPage} 完全一致（宽度 {@code [7, 15]}、
 * 偏移 {@code [0, 15 - size]}、越界钳制），两者共用同一份窗口解析逻辑，
 * 因此同样的 {@code size}/{@code offset} 在两个端点上必然落到同一区间 ——
 * 前端可以用一份翻页状态同时驱动柱状图与折线。
 *
 * <h2>points 已补零，长度恒等于 size</h2>
 * 与 {@code UsageBreakdownPage.rows()} 不同：那里只含有数据的组合，补零留给前端；
 * 这里则由后端补齐窗口内每一天。原因是折线按点位<strong>等距</strong>绘制，
 * 跳过无数据的日期会让横轴不再是等距时间轴，「隔了几天」这个信息就丢了 ——
 * 而这个补零完全由窗口边界决定，没有任何展示层的自由度可言。
 *
 * <p>这与「今日时段折线不补未来时段」并不矛盾：过去的空日是<strong>确定的零</strong>，
 * 而未来的时段是尚未发生，后者的判定随时钟变化，帧一发出即可能过时，故留给前端。
 *
 * <p>由此得到一个可断言的不变量：{@code points.size() == size()}，且
 * {@code points} 首尾的 {@code date} 恰为 {@code startDate} 与 {@code endDate}。
 *
 * @param startDate     窗口起始日期（含），格式 {@code yyyy-MM-dd}
 * @param endDate       窗口结束日期（含），格式 {@code yyyy-MM-dd}
 * @param size          实际窗口宽度（天），已钳制
 * @param offset        实际向过去偏移的天数，已钳制；0 表示窗口右端为今天
 * @param includesToday 窗口是否包含今天，等价于 {@code offset == 0}；
 *                      为 true 时数据仍在变化，需接上 SSE 增量流
 * @param hasNewer      是否还能往「更近」的方向滑
 * @param hasOlder      是否还能往「更早」的方向滑
 * @param points        窗口内每一天的用量，日期升序、已补零，长度恒等于 {@code size}
 */
public record UsageDailyPage(
        String startDate,
        String endDate,
        int size,
        int offset,
        boolean includesToday,
        boolean hasNewer,
        boolean hasOlder,
        List<UsageDailyPoint> points) {
}

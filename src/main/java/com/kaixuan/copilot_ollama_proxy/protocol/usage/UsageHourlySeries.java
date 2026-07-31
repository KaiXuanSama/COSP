package com.kaixuan.copilot_ollama_proxy.protocol.usage;

import java.util.List;

/**
 * 指定一天的时段 token 用量序列 —— 「今日时段」折线切换到任意一天时的数据载体。
 *
 * <h2>为什么没有分页与窗口滑动</h2>
 * 这个视图一次只看一天，请求参数就是那一天本身，没有「宽度」这个自由度可言。
 * 与柱状图 / 「近 N 日」折线的 {@code size} + {@code offset} 是不同的问题形状：
 * 那两者要表达「一段连续区间」，这里只要表达「哪一天」。硬套分页只会多出
 * 一个恒等于 1 的宽度参数。
 *
 * <h2>一天不是 0 点到 0 点</h2>
 * 窗口是 {@code [date 05:00, date+1 05:00)}。跨夜编码是常态，按自然日切分会把一次
 * 连续工作截成两段，看起来像两个互不相关的低谷；凌晨 5 点基本落在活动最低谷，
 * 以它为界一天的曲线才完整。
 *
 * <p>由此得到一个必须留意的口径差异：{@link #date()} 是<strong>窗口的锚定日</strong>，
 * 不等于窗口内所有点位的日期 —— 次日 00:00 至 05:00 的点位属于 {@code date + 1}。
 * 这也是 {@link UsageHourlyPoint#bucket()} 必须带完整日期的原因：
 * 只给 {@code HH:mm} 时 {@code 01:00} 分不清属于哪天。
 *
 * <h2>points 已补零，长度恒为 25</h2>
 * 与 {@link UsageDailyPage} 同理：折线按点位等距绘制，跳过无数据的时段会让横轴
 * 不再是等距时间轴。而这个补零完全由窗口边界决定，没有展示层的自由度。
 *
 * <p>25 而非 24：点以整点为<strong>中心</strong>聚合，首尾都落在 05:00 上，
 * 各只覆盖半小时（首点 {@code [05:00, 05:30)}、末点 {@code [04:30, 05:00)}），
 * 两者相加恰好一小时，总量不重不漏。选整点为中心而非整点起始，是为了让横轴
 * 首尾对称、「一天是完整一圈」这个语义直接可见 ——
 * 若按整点起始，标签会是 05:00 到 04:00，看不出这是闭合的一圈。
 *
 * <h2>为什么不带「尚未到来」标记</h2>
 * 那是随时钟移动的展示状态，而本 record 是一份数据快照 —— 把它写进数据里，
 * 帧一发出就开始过时，还会逼出定时重推来兜底。该判定由前端按当前时刻自行推导，
 * 且变化时刻可精确预知（每个 {@code HH:30}），前端能精准命中而不必轮询。
 *
 * <p>{@link #isCurrentWindow()} 是例外：它同样依赖服务端时钟，但用途是让调用方
 * 决定「要不要接 SSE 增量流」，这个决定只在页面切换那一刻做一次。
 * 它在响应生成时求值，跨过 5 点后会失效 —— 与 {@link UsageDailyPage#includesToday()}
 * 是同一类判断，代价与收益也相同。
 *
 * @param date            窗口锚定日，格式 {@code yyyy-MM-dd}；为<strong>实际生效</strong>的日期，
 *                        请求越界或无法解析时已被收敛
 * @param windowStart     窗口起点（含），格式 {@code yyyy-MM-dd'T'HH:mm:ss}，即 {@code date 05:00}
 * @param windowEnd       窗口末点（含），即 {@code date+1 05:00}；这是最后一个<strong>点位</strong>的时刻，
 *                        不是查询的右开边界
 * @param isCurrentWindow 该窗口是否包含当前时刻；为 true 时数据仍在变化，需接上 SSE 增量流
 * @param points          窗口内每个整点的用量，时刻升序、已补零，长度恒为 25
 */
public record UsageHourlySeries(
        String date,
        String windowStart,
        String windowEnd,
        boolean isCurrentWindow,
        List<UsageHourlyPoint> points) {
}

package com.kaixuan.copilot_ollama_proxy.protocol.usage;

/**
 * 用量记录的日期上下限 —— 概览日期选择器「可达 / 不可达」的唯一数据源。
 *
 * <p>选择器需要知道往前能拖到哪一天。在此之前那个下界是前端写死的常量，
 * 于是空库也会显示一整片可选日期，而每一天点进去都是空图。本 record 把这个判断
 * 交回后端，因为它同时取决于<strong>库里有什么</strong>和<strong>后端愿意查多久</strong>，
 * 前端两者都不知道。
 *
 * <h2>为什么是「上下限」而不是「有数据的日期集合」</h2>
 * 中间断档刻意被忽略：{@code 07-28} 与 {@code 07-30} 有数据、{@code 07-29} 没有时，
 * 范围仍是 {@code 07-28} 到 {@code 07-30}。空日是<strong>确定的零</strong>，画成零柱即可；
 * 若把可选点限制成实际有数据的那几天，选择器的离散点就会变成不连续的一串，
 * 横轴不再是等距时间轴，「隔了几天」这个信息反而丢了。
 *
 * <h2>两组字段的分工</h2>
 * <ul>
 *   <li>{@link #earliestDate()} / {@link #latestDate()} —— <strong>数据事实</strong>，
 *       表为空时同时为 {@code null}。用于展示（「有记录的区间」）与诊断。</li>
 *   <li>{@link #earliestSelectable()} / {@link #latestSelectable()} —— <strong>可选范围</strong>，
 *       永不为 null，直接喂给选择器的可达区间。它是数据事实与后端回看深度的交集，
 *       两端的推导规则并不对称，见下。</li>
 * </ul>
 * 两组并存是因为前者回答「有多少数据」、后者回答「能选到哪」，二者会不一致：
 * 库里存着 60 天的数据，但分页端点只接受最近 15 天内的窗口，此时下界由后者决定。
 * 若只下发数据事实，前端会把选择器放开到查不动的区间；若只下发可选范围，
 * 前端就无法区分「没有更早的数据」与「更早的数据查不了」。
 *
 * <h2>下界取交集，上界固定为今天</h2>
 * 下界是 {@code max(earliestDate, 后端可回看的最早一天)} —— 两个限制都要满足。
 *
 * <p>上界则<strong>不取</strong> {@code latestDate}，而恒为今天：今天是默认视图，
 * 且随时可能产生第一条记录。若因今天尚无调用就把它标成不可达，页面一打开
 * 就处在一个「不可选」的位置上，而下一次调用又会让它突然变得可选。
 *
 * <p>表为空时两端同时收敛到今天 —— 选择器退化为只有今天一个可选点，
 * 这正确表达了「没有历史可翻」。
 *
 * <h2>为什么要回带 today</h2>
 * 前端把日期换算成相对今天的偏移来驱动翻页。若用浏览器时钟算「今天」，
 * 客户端与服务端时区或日期不一致时整条时间轴会整体错位一天，
 * 而图表照样能画出来 —— 只是每根柱子都贴错了日期。回带服务端的今天使两侧同源。
 *
 * @param earliestDate       最早一条用量记录的日期，格式 {@code yyyy-MM-dd}；无数据时为 {@code null}
 * @param latestDate         最晚一条用量记录的日期，同格式；无数据时为 {@code null}
 * @param today              服务端本地时钟的今天，同格式；前端据此换算日期与偏移
 * @param hasData            是否存在至少一条用量记录；为 false 时上面两个日期字段均为 {@code null}
 * @param earliestSelectable 可选的最早日期（含），永不为 null；为数据下界与后端回看深度的交集
 * @param latestSelectable   可选的最晚日期（含），永不为 null；恒等于 {@code today}
 */
public record UsageDateRange(
        String earliestDate,
        String latestDate,
        String today,
        boolean hasData,
        String earliestSelectable,
        String latestSelectable) {
}

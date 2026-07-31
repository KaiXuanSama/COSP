package com.kaixuan.copilot_ollama_proxy.protocol.usage;

/**
 * 按<strong>日期</strong>聚合的 token 用量点位 —— 「近 N 日」折线的数据单元。
 *
 * <p>与 {@link UsageBreakdownRow} 的区别是分组维度：那个 record 保留了供应商与模型，
 * 供柱状图 pivot 出三级下钻；本 record 只按日期分组，因为折线的横轴就是日期，
 * 拿到更细的维度也只会被再求和一次。
 *
 * <h2>为什么不复用 UsageBreakdownRow</h2>
 * 折线单独查一次，行数上界是<strong>窗口天数</strong>（≤ 15）；若复用明细，
 * 上界变成 {@code 天数 × 供应商 × 模型}，且客户端还要再聚合一遍。
 * 概览页同时展示两张图时前端确实可以只取一份明细自行归约（现状即如此），
 * 但作为独立端点，按日期分组是更小也更直接的契约。
 *
 * <p>{@code callCount} 一并带上：折线当前只画 token，但「这一天有多少次调用」
 * 是 tooltip 的自然内容，且在 SQL 里是免费的（同一次 {@code GROUP BY}）。
 *
 * <p>库中 token 列允许 NULL（上游未提供，区别于真实的 0），聚合时按 0 处理，
 * 故本 DTO 字段不可空。这只是求和这一步的处理方式，不改变落库侧保留 null 语义的做法。
 *
 * @param date         日期，格式 {@code yyyy-MM-dd}（本地时区）
 * @param callCount    当日调用次数
 * @param inputTokens  当日输入 token 总量
 * @param outputTokens 当日输出 token 总量
 */
public record UsageDailyPoint(
        String date,
        long callCount,
        long inputTokens,
        long outputTokens) {

    /** 构造某一天的空点位，用于窗口内无数据日期的补零。 */
    public static UsageDailyPoint empty(String date) {
        return new UsageDailyPoint(date, 0L, 0L, 0L);
    }
}

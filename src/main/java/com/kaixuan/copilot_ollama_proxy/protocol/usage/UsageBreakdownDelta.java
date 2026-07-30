package com.kaixuan.copilot_ollama_proxy.protocol.usage;

/**
 * 用量明细<strong>增量帧</strong> DTO —— 一次调用产生的那一行。
 *
 * <p>它是 {@link UsageBreakdownRow} 的「单次」形态：字段一一对应，
 * {@code callCount} 恒为 1。前端把它按 (date, providerKey, modelName) 累加进已有明细，
 * 因此不需要为增量另设一套聚合逻辑 —— 全量帧与增量帧在前端汇入同一个数组。
 *
 * <p>{@code callCount} 保留为字段而非写死 1，是为了将来能在后端合并密集帧
 * （把同一组合的 N 次调用压成一帧）而不改协议。
 *
 * <h2>为何带上 createdAt 与 token</h2>
 * 柱状图只用到 {@link #date()}，但同一条流将来要同时驱动 token 折线图
 * （折线图的分桶规则是「以整点为中心、覆盖前后半小时」，需要完整时刻才能定位桶）。
 * 这两组字段现在无人消费，放进来的成本为零，却省掉了届时的协议变更。
 *
 * <p>token 用可空 {@link Integer} 而非 int：{@code null} = 上游未提供，
 * {@code 0} = 上游报告了零值。这与 {@code api_call_usage} 的落库口径一致，
 * 不在传输层把两者抹平。
 *
 * @param date          调用日期，格式 {@code yyyy-MM-dd}（本地时区）；前端据此匹配柱子
 * @param createdAt     完整时刻，格式 {@code yyyy-MM-dd'T'HH:mm:ss}；与落库值同源
 * @param providerKey   供应商标识
 * @param modelName     模型名称
 * @param callCount     本帧代表的调用次数，当前恒为 1
 * @param inputTokens   输入 token，可为 null（折线图预留，柱状图不读）
 * @param outputTokens  输出 token，可为 null（折线图预留，柱状图不读）
 */
public record UsageBreakdownDelta(
        String date,
        String createdAt,
        String providerKey,
        String modelName,
        long callCount,
        Integer inputTokens,
        Integer outputTokens) {
}

package com.kaixuan.copilot_ollama_proxy.protocol.usage;

/**
 * 用量明细行 DTO —— 概览下钻柱状图的唯一数据源。
 *
 * <p>后端只做一次<strong>最细粒度</strong>聚合（按 日期 × 供应商 × 模型 分组），
 * 前端据此自行 pivot 出三级视图，无需为每一级各开一个端点：
 * <ul>
 *   <li>一级（堆叠柱）—— 按 {@code date} + {@code providerKey} 汇总；</li>
 *   <li>二级（某天各供应商）—— 筛定 {@code date} 后按 {@code providerKey} 汇总；</li>
 *   <li>三级（某天某供应商各模型）—— 筛定 {@code date} + {@code providerKey} 后按 {@code modelName} 汇总；</li>
 *   <li>hover 明细 —— 同样从本明细直接算出，下钻与悬浮均不产生额外请求。</li>
 * </ul>
 *
 * <p>同一份明细也支持主次维度互换（将来把模型作为堆叠主维度），故不在字段命名上
 * 预设"谁是主维度"。
 *
 * <p>数据来自 {@code api_call_usage}，该表仅记录成功且上游返回了 usage 的调用，
 * 因此这里的次数会略低于 {@code api_usage_daily} 的全量口径（后者含失败与无 usage 调用）。
 * 这是两表的定位差异，不是缺陷；展示侧需注明口径。
 *
 * @param date        调用日期，格式 {@code yyyy-MM-dd}（本地时区）
 * @param providerKey 供应商标识
 * @param modelName   模型名称
 * @param callCount   该（日期，供应商，模型）组合下的调用次数
 */
public record UsageBreakdownRow(
        String date,
        String providerKey,
        String modelName,
        long callCount) {
}

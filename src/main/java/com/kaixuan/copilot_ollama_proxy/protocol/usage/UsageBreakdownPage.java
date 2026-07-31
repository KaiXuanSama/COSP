package com.kaixuan.copilot_ollama_proxy.protocol.usage;

import java.util.List;

/**
 * 可滑动窗口的用量明细分页结果 —— 概览三个图表切换历史区间时的数据载体。
 *
 * <p>与 {@code GET /config/api/usage-breakdown?days=N} 的区别在于「窗口能往回滑」：
 * 后者只表达「最近 N 天」，窗口右端永远钉在今天；本 DTO 的窗口由
 * <strong>宽度</strong>（{@link #size()}）与<strong>向过去的偏移</strong>（{@link #offset()}）
 * 两个自由度确定，故 {@code 1-7}、{@code 2-8}、{@code 9-15} 这类区间都能表达。
 *
 * <h2>为什么带上窗口元信息而不只返回行</h2>
 * 请求参数会被服务层钳制（宽度钳到 {@code [7, 15]}，偏移钳到 {@code [0, 15 - size]}），
 * 钳制后的实际窗口可能与请求不同。若只回行，前端无法知道自己拿到的究竟是哪一段 ——
 * 尤其在「某天完全没有调用」时，行里根本不会出现那个日期，靠数据反推窗口是不可能的。
 * 因此 {@link #startDate()} / {@link #endDate()} 是横轴补零的唯一依据。
 *
 * <h2>翻页可用性由后端判定</h2>
 * {@link #hasNewer()} / {@link #hasOlder()} 让前端直接决定翻页按钮的禁用态，
 * 不必在前端重复一份边界算式 —— 那份算式一旦与后端钳制规则不一致，
 * 就会出现「按钮可点但翻不动」的静默错位。
 *
 * <h2>includesToday 的用途</h2>
 * 窗口含今天时数据仍在变化，需要接上 SSE 增量流；不含今天时数据已固化，
 * 一次 HTTP 拉取即为终态，不必建流。这个判断依赖「今天」这个服务端时刻，
 * 由后端给出比前端自行比较日期串更可靠。
 *
 * @param startDate     窗口起始日期（含），格式 {@code yyyy-MM-dd}
 * @param endDate       窗口结束日期（含），格式 {@code yyyy-MM-dd}
 * @param size          实际窗口宽度（天），已钳制
 * @param offset        实际向过去偏移的天数，已钳制；0 表示窗口右端为今天
 * @param includesToday 窗口是否包含今天，等价于 {@code offset == 0}
 * @param hasNewer      是否还能往「更近」的方向滑（{@code offset > 0}）
 * @param hasOlder      是否还能往「更早」的方向滑（未触及最大可回看深度）
 * @param rows          窗口内按 日期 × 供应商 × 模型 聚合的明细，日期升序；无数据时为空列表
 */
public record UsageBreakdownPage(
        String startDate,
        String endDate,
        int size,
        int offset,
        boolean includesToday,
        boolean hasNewer,
        boolean hasOlder,
        List<UsageBreakdownRow> rows) {
}

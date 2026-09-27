package com.kaixuan.copilot_ollama_proxy.observability.record;

/**
 * API 调用日聚合写入服务 —— 观测轴的领域接口（日聚合这一种延迟）。
 *
 * <p>与 {@link ApiCallLogService}（明细）、{@link ApiCallUsageService}（token 明细）同属
 * 「观测轴需要的写出能力」，但写入的是第三张表 {@code api_usage_daily} ——
 * <strong>按天累加</strong>的统计卡数据源，不保留任何单次调用的痕迹。
 *
 * <h2>三种延迟各有自己的表</h2>
 * <ul>
 *   <li><strong>实时</strong> —— {@code observability/publisher/} 的三个 publisher（SSE 推送）；</li>
 *   <li><strong>明细</strong> —— {@code api_call_log} / {@code api_call_usage}，每次往返一行，后台可查；</li>
 *   <li><strong>聚合</strong> —— {@code api_usage_daily}，即本接口。统计卡读它，
 *       因而「写聚合」与「通知统计卡刷新」是同一件事的两面，由同一实现承担。</li>
 * </ul>
 *
 * <h2>为何要抽这个接口</h2>
 * 实现（{@code ApiUsageCollector}）住在 {@code infrastructure/}，它直接依赖
 * JDBC 仓储 —— 那是实现细节。调用方（三个出口控制器，以及后续的观测消费点）
 * 只需要「记一次调用」这个能力，不该看见仓储。
 *
 * <p>这是依赖倒置（DIP）的体现：接口定义在观测轴，实现放在 {@code infrastructure/}。
 * 与 {@link ApiCallLogService} / {@link ApiCallUsageService} 同属一类范本。
 *
 * <p><strong>另一个动机是消除包级环</strong>：{@code UsageQueryService}（读侧）与
 * {@code CallLogQueryService} 需要观测轴的 publisher，因此 {@code application → observability}
 * 必须成立；若出口控制器反过来直接依赖 {@code infrastructure.web} 的实现类，
 * 依赖图会在观测轴与支撑层之间形成往返。
 *
 * <h2>token 参数为何是 int 而非可空 Integer</h2>
 * 本表<strong>只做累加</strong>，没有「上游未提供」这一列的容身之处 ——
 * 累加语义下缺省即 0。这<strong>不是</strong>把 null 与 0 抹平：区分的责任在
 * {@code api_call_usage}（明细表保留可空三列），聚合表刻意不承载该信息。
 * 调用方应使用 {@code UsageTokens.promptOrZero()} / {@code completionOrZero()} 转换，
 * 那对方法存在的唯一理由正是喂给本接口。
 */
public interface ApiUsageDailyService {

    /**
     * 记录一次 API 调用：调用次数 +1，并把 token 累加进当天。
     *
     * <p>实现必须是 best-effort 的：写库失败绝不能影响正在进行的聊天数据流。
     *
     * @param inputTokens  输入 token 数；上游未提供时传 0
     * @param outputTokens 输出 token 数；上游未提供时传 0
     */
    void record(int inputTokens, int outputTokens);
}

package com.kaixuan.copilot_ollama_proxy.api.shared;

import com.kaixuan.copilot_ollama_proxy.observability.record.ApiUsageDailyService;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTokens;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 出口消费 {@link UpstreamEvent#usage()} 槽并记入日聚合 —— 三条端点共用一份。
 *
 * <h2>它在分层里的位置</h2>
 * usage 的<strong>解析</strong>由生产者承担（三个执行器 / C2M 翻译器），
 * 结果挂在事件上；出口只剩「<strong>读槽 + 记账</strong>」这一件事。本类就是那件事。
 *
 * <p>它是 {@code api/shared/} 的第三个成员，与 {@link StreamLifecycle} /
 * {@link UpstreamFailureClassifier} 同一判据：<strong>三条端点共用、且不因协议而异</strong>。
 * 与之相对，各端点的错误 JSON 骨架、SSE 事件名回填策略都<strong>不</strong>在此 —— 那些由下游协议决定。
 *
 * <h2>为何要收归一处</h2>
 * 出口只做「读槽 + 记账」之后，三条出口各剩一份，三份<strong>逐字相同</strong>。
 * 重复本身不是问题，问题是它会<strong>静默分叉</strong>：某天有人把流式的「恒记」改成「有才记」，
 * 只改一条线路 —— 症状是「同一个上游故障，这条线的统计卡少算一次调用」，
 * 而三条线路的代码看起来都合理。
 *
 * <h2>两档记账语义<strong>刻意不同</strong>，不要「顺手统一」</h2>
 * <table border="1">
 *   <caption>流式与非流式的记账档位</caption>
 *   <tr><th>路径</th><th>方法</th><th>无 usage 时</th><th>为何如此</th></tr>
 *   <tr>
 *     <td>流式</td><td>{@link #recordStream}</td><td><strong>录 0,0</strong></td>
 *     <td>它同时是「本次调用发生过」的计数 —— 跳过会让统计卡的<strong>调用次数</strong>少算</td>
 *   </tr>
 *   <tr>
 *     <td>非流式</td><td>{@link #recordNonStream}</td><td><strong>不录</strong></td>
 *     <td>响应体里真的没有 usage 就没有可记的量；调用次数由 {@code api_call_log} 承载</td>
 *   </tr>
 * </table>
 *
 * <p>两者都有历史成因（流式那条靠 {@code finalize} 兜底计数，非流式那条靠响应体自证），
 * 且都会影响 {@code api_usage_daily} 的读数。<strong>统一它们是一次行为变更</strong>，
 * 不属于「消重复」的范畴。
 *
 * <h2>「取最后一份有数据的」为何与协议无关</h2>
 * 三种协议的累积差异（Chat / Responses 后到覆盖、Anthropic 跨事件<strong>只有正数才覆盖</strong>、
 * C2M 由上游侧算好）已全部被生产者吸收，因此出口只需一条规则。
 * 这条规则写在 {@link UpstreamEvent#usage()} 的说明里，本类只是它的执行者。
 *
 * @see UpstreamEvent#usage()
 */
public final class UsageAccounting {

    private UsageAccounting() {
    }

    /**
     * 把事件上的 usage 收进流内累积器 —— <strong>取最后一份有数据的</strong>。
     *
     * <p>为何不是「每份都更新」或「累加」：生产者挂的已是「到目前为止的累积值」，
     * 而后到的总是比先到的更完整（Anthropic 的 output 在 {@code message_delta} 才到、
     * Responses 的结算值在终态事件）。故直接用后来者覆盖。
     *
     * <h2>两档都要跳过，且理由不同</h2>
     * <ul>
     *   <li><strong>{@code null}</strong> —— 生产者至今未见过 usage，无可收；</li>
     *   <li><strong>{@link UsageTokens#EMPTY}</strong> —— 生产者见过一个 usage 对象，
     *       但三个字段一个都没解析出来（上游给了本服务不认识的字段名）。
     *       <strong>收下它会把先前的真实值刷成 {@code 0}</strong>。
     *       这与重构前三条线路的行为逐字一致（它们都写成 {@code if (!tokens.isEmpty())}
     *       才更新）—— 是刻意保持，不是遗漏。</li>
     * </ul>
     *
     * @param event 刚从主干收到的事件（流式）
     * @param usage 本次流的累积器；调用方按流创建并持有
     */
    public static void accumulate(UpstreamEvent event, AtomicReference<UsageTokens> usage) {
        UsageTokens tokens = event.usage();
        if (tokens != null && !tokens.isEmpty()) {
            usage.set(tokens);
        }
    }

    /**
     * 流式记账 —— <strong>恒记</strong>：无论有没有 usage 都记一次。
     *
     * <p>无 usage 时记 {@code 0,0}。它同时承担「本次调用发生过」的计数职责，
     * 跳过会让统计卡的调用次数少算 —— 详见类注释的两档对照表。
     *
     * <p>由各端点的 finalize 调用（Layer 1 收到终止标记、Layer 2 上游关闭连接），
     * 调用方已用 {@code completed} 标志 CAS 去重，本方法不再判重。
     *
     * @param collector 日聚合写入端口
     * @param usage     流内累积到收尾这一刻的 usage；未见过时为 {@code null}
     */
    public static void recordStream(ApiUsageDailyService collector, AtomicReference<UsageTokens> usage) {
        UsageTokens tokens = usage.get();
        collector.record(tokens == null ? 0 : tokens.promptOrZero(),
                tokens == null ? 0 : tokens.completionOrZero());
    }

    /**
     * 非流式记账 —— <strong>有才记</strong>：{@code null} 或空都不记。
     *
     * <p>与 {@link #recordStream} 的「恒记」刻意不同，理由见类注释。
     *
     * @param collector 日聚合写入端口
     * @param event     刚从主干收到的事件（非流式，恰有一个元素）
     */
    public static void recordNonStream(ApiUsageDailyService collector, UpstreamEvent event) {
        UsageTokens tokens = event.usage();
        if (tokens != null && !tokens.isEmpty()) {
            collector.record(tokens.promptOrZero(), tokens.completionOrZero());
        }
    }
}

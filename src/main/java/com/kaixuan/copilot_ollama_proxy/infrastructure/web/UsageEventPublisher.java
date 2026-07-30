package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownDelta;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 调用统计变更事件发布器 —— 两条独立通道。
 *
 * <table border="1">
 *   <caption>两条通道的定位</caption>
 *   <tr><th>通道</th><th>载荷</th><th>发布点</th><th>消费方</th></tr>
 *   <tr>
 *     <td>{@link #changes()}</td><td>无（纯信号）</td>
 *     <td>{@link ApiUsageCollector#record}</td><td>统计卡流、折线图流</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #deltas()}</td><td>{@link UsageBreakdownDelta}</td>
 *     <td>{@code ApiCallUsageRepository.save} 写库成功后</td><td>柱状图流</td>
 *   </tr>
 * </table>
 *
 * <h2>为何要两条</h2>
 * 两条通道对应两张表：{@code changes()} 的发布点写 {@code api_usage_daily}，
 * {@code deltas()} 的发布点写 {@code api_call_usage}。此前只有一条通道，
 * 于是柱状图（读 {@code api_call_usage}）是被<strong>另一张表</strong>的写入信号唤醒的 ——
 * 两次写入恰好发生在同一次调用里，才让它看上去正常。拆开后，每条流由自己数据源的写入驱动。
 *
 * <h2>两种载荷形态的取舍</h2>
 * <ul>
 *   <li>纯信号<strong>幂等</strong> —— 订阅方收到后重查全量，丢帧由下一帧自动收敛；</li>
 *   <li>增量帧<strong>不可丢</strong> —— 每帧是一次状态转移，丢一帧就少算一次调用。
 *       故消费方（柱状图流）必须在订阅建立时先下发一次全量快照作为基准，
 *       断线重连时同理，否则重连间隙的增量会永久丢失。</li>
 * </ul>
 *
 * <p>两条通道都用 {@code multicast().directBestEffort()}：支持多标签页同时订阅；
 * 无订阅者时直接丢弃，不做无界缓冲。注意 best-effort 只在<strong>无订阅者</strong>时丢弃，
 * 有订阅者时正常投递，故增量语义在有人看的前提下是可靠的。
 */
@Component
public class UsageEventPublisher {

    /** 单一信号值，仅表示“统计已更新”，不承载数据。 */
    private static final Object SIGNAL = new Object();

    private final Sinks.Many<Object> sink = Sinks.many().multicast().directBestEffort();

    private final Sinks.Many<UsageBreakdownDelta> deltaSink = Sinks.many().multicast().directBestEffort();

    /**
     * 发布一次统计变更信号（写库成功后调用）。
     *
     * <p>TODO 即将废弃：本信号是无载荷的「去重查一遍」触发器，目前仍被统计卡流与折线图流使用。
     * 折线图流迁移到 {@link #deltas()} 后（增量帧已预留 createdAt 与 token 字段），
     * 这里只剩统计卡流一个消费方；届时可考虑把统计卡也并入增量通道，或让它自己发信号。
     */
    public void publishUsageChanged() {
        sink.tryEmitNext(SIGNAL);
    }

    /**
     * 订阅统计变更信号流。
     *
     * <p>TODO 即将废弃，理由见 {@link #publishUsageChanged()}。
     */
    public Flux<Object> changes() {
        return sink.asFlux();
    }

    /**
     * 发布一条用量明细增量帧（{@code api_call_usage} 写库成功后调用）。
     *
     * <p>务必只在写入确实成功后调用：帧一旦发出，前端就会把它累加进图表，
     * 而没有后续的全量重查来纠正 —— 这正是增量方案与纯信号方案的取舍所在。
     */
    public void publishBreakdownDelta(UsageBreakdownDelta delta) {
        deltaSink.tryEmitNext(delta);
    }

    /**
     * 订阅用量明细增量帧流。
     *
     * <p>订阅方须自行保证基准正确：先取一次全量快照，再接增量。
     */
    public Flux<UsageBreakdownDelta> deltas() {
        return deltaSink.asFlux();
    }
}

package com.kaixuan.copilot_ollama_proxy.protocol.usage;

/**
 * 单条用量记录的增量帧 —— 一次调用落库成功后原样广播的那一行。
 *
 * <p>图表流的三个消费方都从这一种帧派生，无需各自的增量协议：
 * <ol>
 *   <li>堆叠柱状图 —— 取 {@link #createdAt()} 的日期部分，对
 *       (日期, 供应商, 模型) 的调用次数加一；</li>
 *   <li>「近 7 日」折线 —— 同样取日期部分，累加 token；</li>
 *   <li>「今日时段」折线 —— 取完整时刻，归入所属整点后累加 token。</li>
 * </ol>
 *
 * <h2>为什么不做聚合</h2>
 * 帧是「一次调用」这个事实本身，不预设任何视图的分桶方式。三个视图的窗口口径互不相同
 * （柱状图与近 7 日按日历日，今日时段按 05:00 分界），若在后端归桶就必须为每种口径
 * 各发一种帧；原样下发则让每个视图各自决定接受、归桶还是丢弃。
 *
 * <p>因此本帧<strong>不带日期字段</strong> —— 日期是 {@code createdAt} 的前 10 位，
 * 派生字段会给「两者不一致」留下可能。
 *
 * <h2>不可丢</h2>
 * 与纯信号（收到后重查全量、丢帧由下一帧收敛）不同，每帧是一次状态转移，
 * 丢一帧就永久少算一次调用。故消费方必须在订阅建立时先接收全量快照作为基准，
 * 断线重连时同理。
 *
 * <h2>token 为何不可空</h2>
 * 库中 token 列允许 NULL（上游未提供），但本帧只在落库成功后发出，
 * 且实践中上游若未给出 usage 则根本不会走到写入 —— NULL 不会出现在这条路径上。
 * 真正的兜底是「落库成功才推帧」这条规则本身，而非在传输层把 null 压成 0：
 * 后者会让「调用次数」这个语义失真。故此处用原始类型，若上游协议将来真的出现
 * null token，应在写入侧决定是否落库，而不是在这里静默补零。
 *
 * @param createdAt    调用时刻，格式 {@code yyyy-MM-dd'T'HH:mm:ss}（本地时区），与落库值同源
 * @param providerKey  供应商标识
 * @param modelName    模型名称
 * @param inputTokens  本次调用的输入 token
 * @param outputTokens 本次调用的输出 token
 */
public record UsageRecordDelta(
        String createdAt,
        String providerKey,
        String modelName,
        long inputTokens,
        long outputTokens) {
}

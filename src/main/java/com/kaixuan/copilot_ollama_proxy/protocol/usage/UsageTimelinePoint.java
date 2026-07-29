package com.kaixuan.copilot_ollama_proxy.protocol.usage;

/**
 * token 用量时间线上的一个点 —— 概览折线图的数据单元。
 *
 * <p>两种时间范围共用这一个结构，差别仅在 {@code bucket} 的含义：
 * <ul>
 *   <li>近 7 日 —— {@code bucket} 为 {@code yyyy-MM-dd}，一天一个点；</li>
 *   <li>今日时段 —— {@code bucket} 为该时段起始时刻 {@code HH:mm}，颗粒度可为 1/2/4 小时。</li>
 * </ul>
 * 结构同构使前端只需一套渲染逻辑，切换范围时连组件都不必更换。
 *
 * <h2>为什么不返回总量</h2>
 * 总量恒等于输入加输出，由前端相加即可。多传一个派生字段既增加带宽，
 * 又给"三者不一致"留下可能 —— 与热力图 {@code total} 模式的既有做法保持一致。
 *
 * <h2>口径</h2>
 * 数据来自 {@code api_call_usage}，该表只记录成功且上游返回了 usage 的调用，
 * 因此总量会略低于统计卡与热力图（它们取自 {@code api_usage_daily} 的全量口径）。
 * 选择此表是因为它带秒级 {@code created_at}，是唯一能支撑小时级分桶的来源；
 * 两种范围同源也让"某天的日总量"与"该天各时段之和"必然吻合。
 *
 * <p>token 列在库中允许为 NULL（语义上 NULL 表示上游未提供，区别于真实的 0），
 * 聚合时统一按 0 处理，故本 DTO 的字段不可空。
 *
 * <h2>为什么要标记「尚未到来」</h2>
 * 今日时段范围会返回<strong>完整的 24 小时</strong>，横轴因此始终是一整天，
 * 不会随时间推移而伸缩 —— 使用者能一眼看出「今天还剩多少时间」。
 * 但未来时段的 0 不是「没有用量」而是「还没发生」，若照常连线，折线会一路贴底
 * 延伸到轴末，看起来像用量已经归零。故用本标记区分两者，由展示侧决定
 * 未来段是断开还是淡化。
 *
 * @param bucket        时间桶标识：日期 {@code yyyy-MM-dd} 或时段起点 {@code HH:mm}
 * @param inputTokens   该桶内的输入 token 总量
 * @param outputTokens  该桶内的输出 token 总量
 * @param future        该时段是否尚未到来；近 7 日范围恒为 {@code false}
 */
public record UsageTimelinePoint(
        String bucket,
        long inputTokens,
        long outputTokens,
        boolean future) {

    /** 已发生时段的便捷构造，供仓储与近 7 日路径使用。 */
    public UsageTimelinePoint(String bucket, long inputTokens, long outputTokens) {
        this(bucket, inputTokens, outputTokens, false);
    }
}

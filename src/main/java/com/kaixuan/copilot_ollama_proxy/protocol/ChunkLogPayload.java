package com.kaixuan.copilot_ollama_proxy.protocol;

import java.util.List;
import java.util.function.Function;

/**
 * 落库到 {@code api_call_log.chunks} 的载荷形态。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：主干<strong>值类型</strong> · 位置：{@code upstream/}（层根）
 * 步骤「落库」所用的 chunk 载荷（含翻译改写）
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>为何用「一列两形」而不加新列</h2>
 * 跨协议翻译时，日志需要同时保留<strong>上游原始事件</strong>与<strong>下游实际收到的
 * chunk</strong>：前者用于确认上游到底发了什么，后者用于确认客户端看到了什么，
 * 排查「客户端为何解析失败」时两者缺一不可。
 *
 * <p>加一列 {@code upstream_chunks} 的代价是一次 schema 迁移、保留任务多清一列、
 * 查询与 DTO 多带一个字段；而收益只在跨协议这一条路上 —— 直连时两份内容完全相同，
 * 那一列永远是冗余副本。
 *
 * <p>因此改为在同一列里用<strong>两种 JSON 形状</strong>表达：
 * <pre>
 * 直连：  ["{...}", "{...}", "[DONE]"]                 裸数组，与历史数据完全一致
 * 翻译：  {"translated": [...], "upstream": [...]}      带标记的对象
 * </pre>
 * 好处是零迁移、旧数据不受影响、保留任务不用改，而前端按 JSON 形状分派即可
 * （数组走现有逻辑、对象走双栏对照）。
 *
 * <h2>为何不折叠上游的碎片事件</h2>
 * 思考流会产出大量逐字的 {@code thinking_delta}，跨协议时单行体积接近翻倍。
 * 但排查翻译问题时往往正是要看这些碎片如何被合并，折叠会把最有用的部分丢掉。
 * 保留任务按条数裁剪且行数上限可配，本地 SQLite 承受得住。
 *
 * <h2>frameCounts 是两栏对齐的唯一依据</h2>
 * 帧数不对等是常态（实测 26 个上游事件 → 20 个下游 chunk）。
 * {@code frameCounts[i]} 是第 i 个上游事件产出的下游帧数，前端据此让零帧事件
 * 右侧留占位，两栏头部因此对齐。事后从两个数组反推不出这个关系。
 *
 * @param translated  下游实际收到的 chunk
 * @param upstream    上游原始事件；{@code null} 表示直连（无翻译，两份相同）
 * @param frameCounts 逐上游事件的产帧数，长度等于 {@code upstream}；直连时为 null
 */
public record ChunkLogPayload(List<String> translated, List<String> upstream,
                              List<Integer> frameCounts) {

    /** JSON 对象形态里的键名，前端解析需与此一致。 */
    public static final String KEY_TRANSLATED = "translated";
    public static final String KEY_UPSTREAM = "upstream";
    public static final String KEY_FRAME_COUNTS = "frameCounts";

    /** 直连：只有一份 chunk，落库为裸数组。 */
    public static ChunkLogPayload direct(List<String> chunks) {
        return new ChunkLogPayload(chunks, null, null);
    }

    /** 跨协议：两份加对齐信息都留，落库为带标记的对象。 */
    public static ChunkLogPayload translated(List<String> translated, List<String> upstream,
                                             List<Integer> frameCounts) {
        return new ChunkLogPayload(translated, upstream, frameCounts);
    }

    /**
     * 用改写器把上游 chunk 列表变成落库载荷，<strong>改写失败时退回上游原文</strong>。
     *
     * <h2>为何这个「安全套用」住在本类</h2>
     * 它决定的是<strong>退回哪一种形状</strong> —— 而那正是本类唯一负责的事
     * （一列两形）。放在别处会让「出了意外该落成什么」与「两种形状是什么」分居两处。
     *
     * <h2>三条退回路径，理由同一条</h2>
     * <ul>
     *   <li>改写器为 {@code null} —— 直连路线与跟协议非流式，本就不该改写；</li>
     *   <li>改写器抛异常 —— 翻译实现有 bug；</li>
     *   <li>改写器返回 {@code null} —— 同上，只是失败方式不同。</li>
     * </ul>
     * 三者的处理都是<strong>落上游原文（裸数组形态）</strong>。理由：
     * <strong>日志是观测手段，不该因为改写失败而丢掉「上游到底返回了什么」
     * 这个更基础的事实</strong> —— 而「翻译坏了」恰恰是最需要日志的时刻。
     *
     * <p>上游 chunk 本身为 {@code null} 时也走同一路径，且<strong>不调用改写器</strong>：
     * 没有输入就没有可改写的对象。
     *
     * @param chunkRewriter  把上游 chunk 列表改写成下游形态的改写器；
     *                       {@code null} 表示不改写（直连 / 跟协议非流式）
     * @param upstreamChunks 该轮完整的上游 chunk 列表，可能为 null
     * @return 可直接落库的载荷
     */
    public static ChunkLogPayload from(Function<List<String>, ChunkLogPayload> chunkRewriter,
                                       List<String> upstreamChunks) {
        if (chunkRewriter == null || upstreamChunks == null) {
            return direct(upstreamChunks);
        }
        try {
            ChunkLogPayload rewritten = chunkRewriter.apply(upstreamChunks);
            return rewritten == null ? direct(upstreamChunks) : rewritten;
        } catch (Exception exception) {
            return direct(upstreamChunks);
        }
    }

    /** 是否需要落成对象形态。 */
    public boolean hasUpstreamView() {
        return upstream != null;
    }

    /**
     * 产出可直接序列化的值。
     *
     * <p>返回 {@code Object} 而非固定类型：两种形态一个是 List、一个是 Map，
     * 由调用方的 {@code toJson} 统一序列化，仓储层因此不必知道这里有两种形状。
     */
    public Object toSerializableValue() {
        if (!hasUpstreamView()) {
            return translated;
        }
        return java.util.Map.of(
                KEY_TRANSLATED, translated == null ? List.of() : translated,
                KEY_UPSTREAM, upstream,
                KEY_FRAME_COUNTS, frameCounts == null ? List.<Integer>of() : frameCounts);
    }
}

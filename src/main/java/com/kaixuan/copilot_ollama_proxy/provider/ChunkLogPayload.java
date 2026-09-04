package com.kaixuan.copilot_ollama_proxy.provider;

import java.util.List;

/**
 * 落库到 {@code api_call_log.chunks} 的载荷形态。
 *
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
 * @param translated 下游实际收到的 chunk
 * @param upstream   上游原始事件；{@code null} 表示直连（无翻译，两份相同）
 */
public record ChunkLogPayload(List<String> translated, List<String> upstream) {

    /** JSON 对象形态里的键名，前端解析需与此一致。 */
    public static final String KEY_TRANSLATED = "translated";
    public static final String KEY_UPSTREAM = "upstream";

    /** 直连：只有一份 chunk，落库为裸数组。 */
    public static ChunkLogPayload direct(List<String> chunks) {
        return new ChunkLogPayload(chunks, null);
    }

    /** 跨协议：两份都留，落库为带标记的对象。 */
    public static ChunkLogPayload translated(List<String> translated, List<String> upstream) {
        return new ChunkLogPayload(translated, upstream);
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
                KEY_UPSTREAM, upstream);
    }
}

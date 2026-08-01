package com.kaixuan.copilot_ollama_proxy.application.usage;

/**
 * 用量记录在时间轴上的两个端点 —— 数据访问层能回答的全部。
 *
 * <p>刻意<strong>不含</strong>「今天是哪天」「哪些日期可选」这类信息：那需要服务端时钟与
 * 窗口钳制规则，属于用例决策。数据访问层只看得见表里有什么，因此这个 record 也只有两个字段。
 * 对外的 {@link com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageDateRange}
 * 由用例层在此基础上叠加时钟与可选范围后组装。
 *
 * <h2>为什么不是区间的「有效」表示</h2>
 * 中间断档不体现在这里。{@code 07-28} 与 {@code 07-30} 有数据、{@code 07-29} 没有时，
 * 边界仍是 {@code 07-28} 到 {@code 07-30} —— 空日是确定的零，图上画成零柱即可，
 * 用「哪些日期存在数据」去驱动选择器反而会让可选点变成不连续的一串，横轴不再等距。
 *
 * @param earliest 最早一条记录的日期，格式 {@code yyyy-MM-dd}；表为空时为 {@code null}
 * @param latest   最晚一条记录的日期，同格式；表为空时为 {@code null}
 */
public record UsageDateBounds(String earliest, String latest) {

    /** 表中没有任何用量记录时的空边界。两个字段必然同时为 null，不存在只有一端的情况。 */
    public static final UsageDateBounds EMPTY = new UsageDateBounds(null, null);

    /** 是否存在至少一条记录。等价于两个字段均非 null。 */
    public boolean hasData() {
        return earliest != null && latest != null;
    }
}

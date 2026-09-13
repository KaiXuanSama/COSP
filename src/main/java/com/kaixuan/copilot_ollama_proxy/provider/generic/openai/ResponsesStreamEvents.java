package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import java.util.Map;

/**
 * OpenAI Responses 流的<strong>终态事件</strong>清单。
 *
 * <h2>为何独立成类</h2>
 * 这份清单有两个消费者，而它们对「终态」的用途完全不同：
 * <ul>
 *   <li>{@code ResponsesController} —— 收到终态事件即 finalize 生命周期（不必等 TCP 关闭），
 *       等价于 Chat 的 {@code [DONE]} 与 Anthropic 的 {@code message_stop}；</li>
 *   <li>{@link ResponsesContentDetector} —— 终态事件携带完整 {@code output[]}，
 *       是「只发终态、不发 delta」那类上游唯一的内容来源。</li>
 * </ul>
 * 两处各写一份必然漂移，而漂移的症状是「某个上游的流永远等不到 finalize」
 * 或「一个完整响应被判成空」—— 都不会报错。
 *
 * <h2>为何清单这么长</h2>
 * Chat 只有 {@code [DONE]}、Anthropic 只有 {@code message_stop}，而 Responses 的终止
 * 有多种成因，各自一个事件名。清单取自调研中 sub2api 的实测集合
 * （{@code openai_gateway_messages.go}），它覆盖了多家兼容端点的实际行为：
 * <ul>
 *   <li>{@code response.completed} —— 正常完成，官方主路径；</li>
 *   <li>{@code response.done} —— 部分兼容端点用的简写形态；</li>
 *   <li>{@code response.incomplete} —— 达到 token 上限或被截断；</li>
 *   <li>{@code response.failed} —— 上游侧执行失败；</li>
 *   <li>{@code response.cancelled} / {@code response.canceled} —— <strong>两种拼法都收</strong>，
 *       实测有上游只发其中一种。少收一种的代价是那条流永远等不到 finalize；</li>
 *   <li>{@code error} —— 无 {@code response.} 前缀的错误事件。</li>
 * </ul>
 *
 * <p><strong>宁可多收一个也不要漏</strong>：多收的代价是一个不该 finalize 的事件触发了
 * finalize（而 CAS 去重让重复 finalize 无害），漏收的代价是流式调用在前端永远显示
 * 「进行中」，直到上游关闭连接才由兜底层补上。
 *
 * <h2>「流结束了」与「结局是什么」是两个问题</h2>
 * {@link #isTerminal} 只回答前者。七个事件里有三种截然不同的结局，
 * 由 {@link #outcomeOf} 回答后者 —— 早先控制器只问了前一个问题就无条件发 COMPLETED，
 * 于是上游明确说「我失败了」（{@code response.failed}）时前端显示的是「完成」。
 *
 * <p>这个能力是 Responses <strong>独有</strong>的：Chat 只有 {@code [DONE]}、
 * Anthropic 只有 {@code message_stop}，从终止标记本身读不出结局，只能靠异常路径判定。
 * Responses 把结局编进了事件名，不利用等于主动丢弃信息。
 */
public final class ResponsesStreamEvents {

    /**
     * 一条流的结局。
     *
     * <p>与 {@code CallPhase} 刻意不是同一个类型：本类在 {@code provider} 层，
     * 而 {@code CallPhase} 是对前端的生命周期契约。中间留一层映射，
     * 让「协议怎么表达结局」与「界面怎么显示状态」各自演进。
     */
    public enum Outcome {
        /** 正常完成或被截断 —— 都产出了内容，对用户是「完成」。 */
        SUCCESS,
        /** 上游侧执行失败或报错。 */
        FAILURE,
        /** 上游侧取消。 */
        CANCELLATION,
    }

    /**
     * 终态事件类型 → 结局。
     *
     * <p>用 {@code Map} 而非 {@code switch}：清单要同时支撑「是否终态」的集合判断
     * （{@link #isTerminal} 读它的键集）与结局查询，两处共用一份数据才不会漂移 ——
     * 分成两份的症状是「新增一个终态事件只加进了其中一处」，而那不会报错。
     */
    private static final Map<String, Outcome> TERMINAL_OUTCOMES = Map.of(
            // 正常完成，官方主路径。
            "response.completed", Outcome.SUCCESS,
            // 部分兼容端点用的简写形态。
            "response.done", Outcome.SUCCESS,
            // 达到 token 上限或被截断 —— 内容不完整但确实产出了，不是失败。
            // 归入 SUCCESS 与 Chat 侧 `finish_reason: "length"` 同口径：
            // 那边也不因截断而标失败。
            "response.incomplete", Outcome.SUCCESS,
            // 上游侧执行失败。
            "response.failed", Outcome.FAILURE,
            // 无 `response.` 前缀的错误事件。
            "error", Outcome.FAILURE,
            // 两种拼法都收，实测有上游只发其中一种。
            "response.cancelled", Outcome.CANCELLATION,
            "response.canceled", Outcome.CANCELLATION);

    private ResponsesStreamEvents() {
    }

    /**
     * 该事件类型是否终结这条流。
     *
     * @param type 事件的 {@code type} 字段；null 返回 false
     */
    public static boolean isTerminal(String type) {
        return type != null && TERMINAL_OUTCOMES.containsKey(type);
    }

    /**
     * 该终态事件表示的结局。
     *
     * <p>非终态事件与 null 返回 {@link Outcome#SUCCESS}：调用方只在终态分支调用本方法，
     * 而流被上游<strong>直接关闭</strong>（一个终态事件都没发）时兜底层拿不到事件类型 ——
     * 那种情况按成功处理，与 Chat / Anthropic 的兜底层一致：连接正常关闭且已有内容，
     * 没有任何证据表明它失败了。
     *
     * @param type 事件的 {@code type} 字段
     */
    public static Outcome outcomeOf(String type) {
        if (type == null) {
            return Outcome.SUCCESS;
        }
        return TERMINAL_OUTCOMES.getOrDefault(type, Outcome.SUCCESS);
    }
}

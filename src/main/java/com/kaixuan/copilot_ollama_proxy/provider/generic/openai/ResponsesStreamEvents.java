package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import java.util.Set;

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
 */
public final class ResponsesStreamEvents {

    /**
     * 终态事件类型集合。
     *
     * <p>用 {@code Set} 而非逐个 {@code equals}：清单有七项且还可能增加，
     * 而这里要的正是「集合包含判断」。
     */
    private static final Set<String> TERMINAL_TYPES = Set.of(
            "response.completed",
            "response.done",
            "response.incomplete",
            "response.failed",
            "response.cancelled",
            "response.canceled",
            "error");

    private ResponsesStreamEvents() {
    }

    /**
     * 该事件类型是否终结这条流。
     *
     * @param type 事件的 {@code type} 字段；null 返回 false
     */
    public static boolean isTerminal(String type) {
        return type != null && TERMINAL_TYPES.contains(type);
    }
}

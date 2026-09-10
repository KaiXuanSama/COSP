package com.kaixuan.copilot_ollama_proxy.application.lifecycle;

import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;

/**
 * 单次调用生命周期事件通知接口 —— 领域接口。
 *
 * <p>provider 层通过该接口发出调用生命周期事件（如 CONNECTED、RETRYING），
 * 而不直接依赖具体的 SSE 发布实现，从而与底层推送机制解耦。
 *
 * <p>这是依赖倒置（DIP）的体现：接口定义在 application 层，实现
 * （{@code CallLifecyclePublisher}）放在 infrastructure 层，保持
 * {@code api -> application -> provider} 的依赖方向不被破坏。
 *
 * <p>与 {@code ApiCallLogService} 同属"provider 需要的写出能力"这一类领域接口，
 * 通过 {@code @Autowired(required = false)} 可选注入，测试或非 Spring 场景下可缺省。
 */
public interface CallLifecycleNotifier {

    /**
     * 发布一个生命周期事件。
     *
     * <p>实现必须是 best-effort 的：事件发布失败绝不能影响正在进行的聊天数据流。
     *
     * @param event 生命周期事件
     */
    void publish(CallLifecycleEvent event);

    /**
     * 补齐一次调用的协议信息（下游 + 上游）。
     *
     * <p>存在的理由：下游协议由客户端打的端点在控制器里立刻确定，上游协议却要等应用层
     * 完成路由与协议调度才有结论 —— 那是含 JDBC 读的同步调用，不能在控制器发 RECEIVED 时
     * 现算。故控制器先发不含上游协议的事件，应用层在 {@code defer} 内拿到调度结论后调本方法，
     * 由实现方把信息补进已发出的事件并重发，前端据此渲染「O→A」这类路径标记。
     *
     * <p>实现方应保持<strong>时间戳不变</strong>（前端用它当计时起点）且只在事件仍在进行中时
     * 改写 —— 已终态的调用补这条信息没有意义，重发反而会让一条已收尾的 Toast 重新活跃。
     *
     * <p>给出默认空实现而非抽象方法：本接口的部分使用方（provider 层）只发事件，
     * 不需要这个能力，且测试里的替身不必为它写桩。
     *
     * @param requestId          本次调用唯一标识
     * @param downstreamProtocol 下游协议名（如 {@code OPENAI}）
     * @param upstreamProtocol   上游协议名
     */
    default void recordProtocols(String requestId, String downstreamProtocol, String upstreamProtocol) {
    }
}

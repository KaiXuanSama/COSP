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
}

package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单次调用的<strong>静默重试</strong>协调器。
 *
 * <p>与 {@link CallCancellationRegistry} 同构：管理后台右键 Toast 点「静默重试」时，
 * 通过 retry 端点触发对应 requestId 的信号，让 provider 层中断当前上游请求并重新发起。
 * 区别在于语义：
 * <ul>
 *   <li><strong>取消</strong> —— 终止整条调用链，下游连接随之关闭；</li>
 *   <li><strong>静默重试</strong> —— 只中断当前上游往返，下游 SSE 连接保持打开，
 *       重新发起的请求继续沿同一条流下发。下游 Copilot 无感知。</li>
 * </ul>
 *
 * <p>provider 层每次上游尝试开始时 {@link #register} 一个信号，并挂到自身
 * （{@code takeUntilOther}）；被触发时当前请求被中止，provider 据标志位重新发起。
 * 由于每次尝试注册的都是<strong>新鲜</strong>的 sink，连续点击可连续触发多次重发。
 *
 * <p>信号只在调用仍存活时有意义：{@code doFinally} 里 provider 会 {@link #remove}，
 * 调用已终结后点击重试返回 false（前端据此提示「调用已结束」）。
 */
@Component
public class CallRetryRegistry {

    private static final Logger log = LoggerFactory.getLogger(CallRetryRegistry.class);

    /** requestId -> 静默重试信号 sink。sink 完成即代表「请求方要求重试」。 */
    private final Map<String, Sinks.Empty<Void>> pending = new ConcurrentHashMap<>();

    /**
     * 为一次调用注册静默重试信号，返回其信号流。
     *
     * <p>调用方（provider 每次上游尝试）应把返回的 Mono 挂到自身
     * （{@code takeUntilOther}），当该 Mono 正常完成时即表示外部请求了重试。
     *
     * <p>重复注册同一 requestId 会覆盖旧信号：旧尝试已被中断或结束，
     * 覆盖不会丢失任何在途请求。
     *
     * @param requestId 本次调用唯一标识
     * @return 重试信号；正常 complete 表示被触发
     */
    public Mono<Void> register(String requestId) {
        Sinks.Empty<Void> sink = Sinks.empty();
        pending.put(requestId, sink);
        return sink.asMono();
    }

    /**
     * 请求静默重试指定调用。
     *
     * @param requestId 目标调用唯一标识
     * @return 若找到并成功触发返回 true；requestId 不存在（已完成或从未存在）返回 false
     */
    public boolean retry(String requestId) {
        Sinks.Empty<Void> sink = pending.get(requestId);
        if (sink == null) {
            return false;
        }
        // tryEmitEmpty 幂等：重复触发或与自然结束竞争时，失败无副作用。
        Sinks.EmitResult result = sink.tryEmitEmpty();
        log.info("请求静默重试调用 requestId={}, 触发结果={}", requestId, result);
        return result.isSuccess();
    }

    /** 清理指定调用的重试信号（调用链结束时调用，无论成功/失败/取消）。 */
    public void remove(String requestId) {
        pending.remove(requestId);
    }
}

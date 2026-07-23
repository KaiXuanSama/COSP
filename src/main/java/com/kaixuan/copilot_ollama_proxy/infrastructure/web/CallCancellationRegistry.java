package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单次调用的取消协调器。
 *
 * <p>管理后台可在「上游迟迟不吐首字」时，通过取消端点主动中止某次正在进行的调用。
 * 由于 WebFlux 里一次请求的隔离单元是它自己的 Reactor 订阅链（不是线程），
 * 这里用 {@code requestId -> Sinks.Empty<Void>} 建立注册表：
 * <ul>
 *   <li>chat 请求订阅时 {@link #register} 一个 sink，并把 {@link #cancelSignal} 挂到自己的链上；</li>
 *   <li>取消端点调用 {@link #cancel} 触发对应 sink，chat 链随即中止；</li>
 *   <li>chat 链无论正常结束还是被取消，都在 {@code doFinally} 里 {@link #remove} 清理，避免内存泄漏。</li>
 * </ul>
 *
 * <p>取消信号仅在「CONNECTED 后等待首字」窗口内有意义：此时流式响应尚未吐出任何 chunk，
 * 中止后向下游注入错误是干净的，不会截断已发送的内容。
 */
@Component
public class CallCancellationRegistry {

    private static final Logger log = LoggerFactory.getLogger(CallCancellationRegistry.class);

    /** requestId -> 取消信号 sink。sink 完成即代表「请求方要求取消」。 */
    private final Map<String, Sinks.Empty<Void>> pending = new ConcurrentHashMap<>();

    /**
     * 为一次调用注册取消协调，返回其取消信号。
     *
     * <p>chat 链应把返回的 Mono 挂到自身（非流式 {@code firstWithSignal}、流式 {@code takeUntilOther}），
     * 当该 Mono 正常完成时即表示外部请求了取消。
     *
     * @param requestId 本次调用唯一标识
     * @return 取消信号；正常 complete 表示被取消
     */
    public Mono<Void> register(String requestId) {
        Sinks.Empty<Void> sink = Sinks.empty();
        pending.put(requestId, sink);
        return sink.asMono();
    }

    /**
     * 请求取消指定调用。
     *
     * @param requestId 目标调用唯一标识
     * @return 若找到并成功触发取消返回 true；requestId 不存在（已完成或从未存在）返回 false
     */
    public boolean cancel(String requestId) {
        Sinks.Empty<Void> sink = pending.get(requestId);
        if (sink == null) {
            return false;
        }
        // tryEmitEmpty 幂等：重复取消或与自然结束竞争时，失败无副作用。
        Sinks.EmitResult result = sink.tryEmitEmpty();
        log.info("请求取消调用 requestId={}, 触发结果={}", requestId, result);
        return result.isSuccess();
    }

    /** 清理指定调用的取消协调（chat 链结束时调用，无论成功/失败/取消）。 */
    public void remove(String requestId) {
        pending.remove(requestId);
    }
}

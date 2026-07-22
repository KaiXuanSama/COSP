package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 连接门卫 —— 限制同时存活的 SSE 长连接总数。
 *
 * <p>三条 SSE 流（stats / logs / calls）共享同一个上限。每条连接订阅时 {@link #tryAcquire}，
 * 终止（完成 / 取消 / 出错）时 {@link #release}。超过上限时拒绝新连接，避免长连接资源被无限占用。
 *
 * <p>本服务定位为少量分发使用（日志会记录敏感的请求头/请求体），不适合大面积分发，
 * 因此一个较小的总上限即可覆盖正常使用（每个前端页面约开 3 条流）。
 */
@Component
public class SseConnectionGate {

    private static final Logger log = LoggerFactory.getLogger(SseConnectionGate.class);

    /** 同时存活的 SSE 连接总数上限（三条流共享）。 */
    private final int maxConnections;

    /** 当前存活的 SSE 连接数。 */
    private final AtomicInteger active = new AtomicInteger(0);

    public SseConnectionGate(@Value("${sse.max-connections:20}") int maxConnections) {
        this.maxConnections = Math.max(1, maxConnections);
    }

    /**
     * 尝试占用一个连接名额。
     *
     * @return {@code true} 表示成功占用（调用方须在连接终止时 {@link #release}）；
     *         {@code false} 表示已达上限，调用方应拒绝该连接。
     */
    public boolean tryAcquire() {
        int current = active.incrementAndGet();
        if (current > maxConnections) {
            active.decrementAndGet();
            log.warn("SSE 连接数已达上限 {}，拒绝新连接", maxConnections);
            return false;
        }
        return true;
    }

    /** 释放一个连接名额。 */
    public void release() {
        active.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    /** 当前存活连接数（供观测 / 测试）。 */
    public int activeConnections() {
        return active.get();
    }

    /** 连接数上限。 */
    public int maxConnections() {
        return maxConnections;
    }
}

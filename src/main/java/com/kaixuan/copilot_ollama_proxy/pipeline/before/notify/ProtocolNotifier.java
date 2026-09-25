package com.kaixuan.copilot_ollama_proxy.pipeline.before.notify;

import com.kaixuan.copilot_ollama_proxy.observability.port.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.dispatch.ProtocolDispatchDecision;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import org.slf4j.Logger;

/**
 * 把调度结论<strong>补进</strong>已发出的生命周期事件，供前端 Toast 渲染路径标记。
 *
 * <h2>它解决的问题：路径标记要早于网络等待</h2>
 * 前端 Toast 的路径标记（如 {@code O→A} 表示「下游 Chat、上游 Anthropic」）取自生命周期事件，
 * 而事件是在请求<em>刚被接收时</em>就发出去的（那时还不知道要用哪个上游协议）。
 * 上游协议只有等到 {@code ProtocolDispatchManager.dispatch(...)} 之后才知道，
 * 而再往后就是网络等待 —— 若等拿到响应再补，用户会先看到一个没有标记的 Toast
 * 在那里转圈，标记姗姗来迟。
 *
 * <p>因此在调度结论出来的那一刻、真正调上游之前，补一次
 * {@link CallLifecycleNotifier#recordProtocols}。
 *
 * <h2>为何是「补写」而不是「重发事件」</h2>
 * 生命周期事件按 {@code requestId} 分组，前端据此把同一调用的多条事件拼成一条 Toast。
 * 补写只更新那一条的协议字段，不产生新事件 —— 重发会让 Toast 变成两条。
 *
 * <h2>失败绝不中断调用</h2>
 * 这是<strong>观测链路</strong>：它失败的唯一后果是「标记没显示」，而它成功也没有任何功能价值。
 * 因此异常一律吞掉，绝不让它影响聊天数据流。
 *
 * <p>但要<strong>留痕迹</strong>：完全静默时，「补写持续失败」（比如 requestId 口径不一致
 * 导致永远匹配不上）的唯一症状就是标记不显示，无从查证 ——
 * 观测链路自身不可观测是个反模式。
 *
 * <p>用 {@code debug} 而非 {@code warn}：它不影响功能，平时不必占日志，排查时开 debug 即可。
 *
 * <h2>为何 logger 与 notifier 是参数</h2>
 * 与 {@code UpstreamCallReporter} / {@code StreamLifecycle} 同一取向：本类无状态纯静态，
 * 不做 Spring Bean。{@code log} 走参数是为了保留<strong>调用方的日志归属</strong>
 * （哪个 Service 补写失败），这是「零行为变更」的一部分：
 * 三个 Service 的这条日志此前各自以本类名打印，抽取后不该改变。
 *
 * <p>{@code downstreamProtocol} 之所以传入而不由本类持有：三个 Service 各自持有
 * 一个 {@code DOWNSTREAM_PROTOCOL} 常量（{@code CHAT} / {@code RESPONSES} / {@code MESSAGES}），
 * 那是它们<strong>服务哪个端点</strong>的身份，不是本类的知识。
 */
public final class ProtocolNotifier {

    private ProtocolNotifier() {
    }

    /**
     * 补写一次调用的上下游协议。
     *
     * @param log                调用方的 logger，用于保留日志归属
     * @param notifier           可选注入的通知器；null 表示未注入（单元测试直接 new Service）
     * @param requestId          调用唯一标识；null 时直接返回（无从匹配）
     * @param downstreamProtocol 本 Service 服务的端点协议
     * @param decision           调度结论，提供上游协议
     */
    public static void notifyProtocols(Logger log, CallLifecycleNotifier notifier, String requestId,
                                       WireProtocol downstreamProtocol, ProtocolDispatchDecision decision) {
        if (notifier == null || requestId == null) {
            return;
        }
        try {
            notifier.recordProtocols(requestId, downstreamProtocol.name(),
                    decision.upstreamProtocol().name());
        } catch (Exception exception) {
            log.debug("生命周期协议信息补写失败，不影响调用本身 [{}]: {}",
                    requestId, exception.toString());
        }
    }
}

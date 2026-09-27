package com.kaixuan.copilot_ollama_proxy.observability.notify;

import com.kaixuan.copilot_ollama_proxy.observability.port.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.observability.record.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import org.slf4j.Logger;

/**
 * 两类「best-effort 通知」的<strong>唯一实现</strong>：生命周期事件与调用记录变更信号。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：主干内的薄适配器 · 位置：{@code observability/notify/}
 * 跨步骤观测—— 生命周期事件 / 调用记录信号的 best-effort 通知
 * <p>完整步骤树见 {@code pipeline/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>共同的契约：观测定不能反过来伤到主链路</h2>
 * 两者都是<strong>观测</strong>：生命周期事件驱动前端 Toast，变更信号驱动日志页回拉。
 * 它们的失败不该影响正在进行的聊天数据流 —— 一个推送不出去的事件，
 * 代价应该是「Toast 少更新一次」，而不是「这次对话失败」。
 *
 * <p>因此两者的形状完全相同，也只有三点：
 * <ol>
 *   <li>依赖未注入时（单元测试直接 new 执行器）<strong>直接返回</strong>，
 *       不报错也不占日志 —— 缺省即不发，是既定的可选注入范式；</li>
 *   <li>捕获 {@link Exception} 并<strong>绝不重抛</strong>。这里要诚实地说清：
 *       {@link ApiCallLogService#publishCallRecorded()} 与
 *       {@link CallLifecycleNotifier#publish(CallLifecycleEvent)} 都<strong>未声明</strong>受检异常，
 *       所以实际能出现的只有 {@link RuntimeException} 及其子类，
 *       写 {@code Exception} 与写 {@code RuntimeException} 在当前签名下<strong>等价</strong>。</li>
 *   <li>失败只记一条日志，级别按可诊断性区分：
 *       生命周期事件用 debug（它每轮调用都会发，warn 会淹没日志）；
 *       变更信号用 warn（它触发日志页回拉，静默失败会让「记录不实时」无从查证）。</li>
 * </ol>
 *
 * <h2>为何依赖与 logger 是参数而不是字段</h2>
 * 两个依赖（{@link CallLifecycleNotifier}、{@link ApiCallLogService}）是
 * <strong>各执行器的可选注入字段</strong>，而不是本类的。本类因此是无状态纯静态的 ——
 * 与 {@link UpstreamRetryPolicy} 同一形状，不做 Spring Bean。
 *
 * <p>不做 Bean 还避免了一处真实的复杂度：若本类持有这两个依赖，则执行器既要在自己这里
 * 保留 setter（测试用 {@code service.setApiCallLog(...)} 注入），又要转发给本类，
 * 于是同一份依赖存在两个持有者、需要保证它们同步。收益为零，风险非零。
 *
 * <p>{@code log} 之所以也走参数：让它保持<strong>子类的 logger</strong>。
 * 若本类自带 logger，这三条日志的分类会从
 * {@code c.k.c.p.u.s.m.GenericAnthropicChatService} 变成
 * {@code c.k.c.p.u.UpstreamCallReporter} —— 按执行器类名过滤日志的人会看不到它们。
 * 这是「零行为变更」的一部分，不是随手加的参数。
 *
 * <h2>为何各执行器仍保留一个两行的同名方法</h2>
 * 那是<strong>适配器</strong>：把「自己的可选字段 + 自己的 logger」绑给本类。
 * 它与被抽走的逻辑不同 —— 被抽走的是会<em>静默分叉</em>的东西
 * （判空、try/catch、三条日志文案），而适配器若与签名不符会<strong>编译失败</strong>。
 * 重复的适配器是安全的重复；重复的逻辑不是。这一区别是判断「该不该抽」的实际依据。
 *
 * <p>顺带的好处：28 个调用点一个都不用改，改动面落在 6 个方法体上，
 * 评审时 diff 全部集中在「骨架」本身。
 */
public final class UpstreamCallReporter {

    private UpstreamCallReporter() {
    }

    /**
     * best-effort 发出一个生命周期事件。
     *
     * <p>调用点：{@code CONNECTED}（上游响应真正到达时）、{@code RETRYING}（每次退避前）。
     * 前一版注释写着「notifier 未注入（如单元测试）或发布异常时静默跳过」——
     * 这里把「静默」说得更准确些：<strong>不影响主链路</strong>，但失败会留一条 debug 日志。
     * 完全不留痕迹的话，「Toast 为什么不更新」将无从查证，而观测链路自身不可观测是个反模式。
     *
     * @param log      调用方的 logger，用于保留日志归属（见类注释）
     * @param notifier 可选注入的通知器；null 表示未注入
     * @param event    要发布的事件
     */
    public static void publishLifecycle(Logger log, CallLifecycleNotifier notifier, CallLifecycleEvent event) {
        if (notifier == null) {
            return;
        }
        try {
            notifier.publish(event);
        } catch (Exception e) {
            log.debug("生命周期事件发布失败（已忽略）: {}", e.getMessage());
        }
    }

    /**
     * best-effort 发布「调用记录已就绪」信号，驱动日志页实时刷新。
     *
     * <h2>为何发布点在此层而非仓储的 INSERT 内部</h2>
     * 一次调用要写两张表：api_call_log 必写，api_call_usage 视上游是否返回 usage 而定，
     * 且日志先写。信号是<strong>同步投递</strong>的（{@code Sinks.directBestEffort} 在调用
     * 线程上直接推给订阅者），故若在日志 INSERT 后立即发信号，消费者视角收到通知去查时
     * 用量行可能尚未写入 —— 那一行的 token 会短暂显示为空，直到下次刷新。
     * 实践中该窗口只有微秒级、远窄于一次 SSE 投递加 HTTP 回拉的往返，几乎不可观测，
     * 但它依赖的是「网络比本地慢」这一隐含前提，不是设计保证。把发布点上移到编排层，
     * 「这次调用的记录整体就绪」才成为显式的时序契约。
     *
     * <h2>为何是 finally 语义而非「两张表都写了」</h2>
     * 失败调用与上游未返回 usage 的调用本就不写用量行。若按 {@code &&} 判定，
     * 这些记录永远不会实时出现在前端 —— 而错误行恰恰最需要立刻看到。
     * 因此判据是「落库流程走完」：走到用量环节并结束（无论是否真的写入），即可宣告就绪。
     *
     * <h2>一次调用发几次</h2>
     * 每个<strong>落库分支</strong>各发一次，不是每次调用固定一次 ——
     * 一次逻辑调用若经历重试，每轮往返都会各自落一条日志，因此也各自发一次信号。
     * 这与重构前的行为一致（原先每次 INSERT 发一次），前端的回拉是幂等的，
     * 多余的信号只会多一次「查到相同数据」的请求，不会造成状态错误。
     *
     * <p>只 warn 不抛：与 save* 的容错策略一致，推送失败不得影响主调用链。
     * 用 warn 而非 debug：静默失败的症状是「日志页不实时」，而这与「本来就没新记录」
     * 无法区分，是排查成本最高的那类失败。
     *
     * @param log        调用方的 logger，用于保留日志归属（见类注释）
     * @param apiCallLog 可选注入的日志服务；null 表示未注入
     */
    public static void publishCallRecorded(Logger log, ApiCallLogService apiCallLog) {
        if (apiCallLog == null) {
            return;
        }
        try {
            apiCallLog.publishCallRecorded();
        } catch (Exception e) {
            log.warn("发布调用记录变更信号失败: {}", e.getMessage());
        }
    }
}

package com.kaixuan.copilot_ollama_proxy.upstream;

import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import org.slf4j.Logger;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * 自动重试的<strong>规格构造</strong> —— 三个上游执行器共用一份实现。
 *
 * <h2>它管的是「重试几次、等多久」，不是「要不要重试」</h2>
 * 那两件事刻意分居两类，因为回答它们所需的信息不同。
 * <strong>「自动重试」就是这两类、两件事</strong>：
 *
 * <table>
 *   <caption>自动重试的两件事</caption>
 *   <tr><th>类</th><th>管的事</th><th>性质</th></tr>
 *   <tr><td>{@link UpstreamRetryPolicy}</td><td><strong>要不要</strong>重试（429 / 5xx / 网络 / 空响应）</td>
 *       <td>纯判定，无状态，不读配置</td></tr>
 *   <tr><td><strong>本类</strong></td><td><strong>几次、多久</strong>（读配置 + 组装 {@code Retry}）</td>
 *       <td>组装，读配置</td></tr>
 * </table>
 *
 * <h2>第三件曾与此二者并列，现已出列（阶段 3.7 第①批）</h2>
 * 人工触发的重新发起（原 {@code UpstreamSilentRetry}，现 {@code control/CallResendLoop}）
 * 曾与本二者同住 {@code provider/} 顶层，
 * 排出一副「重试三件套」的样子 —— 但那只是<strong>按词聚的</strong>，不是按功能聚的。
 * 它此后移入 {@link com.kaixuan.copilot_ollama_proxy.control.CallResendLoop}，
 * 因为它是「<strong>外部信号驱动的重发</strong>」：只服务流式、入口是 HTTP 端点、不消耗本类读的那个预算。
 * <strong>三者不是一件事的三面，「自动重试」只是两个类。</strong>
 *
 * <p>{@link UpstreamRetryPolicy} 的类注释已明确排除「几次、多久」：「要不要重试」是纯判定，
 * 与「重试几次、等多久」是两件事。因此本类独立存在，而不是塞进那个类。
 *
 * <h2>429 的日志特化在<strong>这里</strong>，且三条线路共用</h2>
 * 429 是 <strong>HTTP 层</strong>事实（任何供应商都会限速），不是协议差异 ——
 * 它的<strong>判定</strong>（{@link UpstreamRetryPolicy#isRetryableStatus} 认 429）
 * 早已在主干三线共用，而日志特化与那个判定同属「处置一次 429」这一个功能。
 *
 * <p>此前只有 Chat 那份带 429 / {@code Retry-After} 特化，另两条各写 inline 简版 ——
 * 那是<strong>抄漏</strong>而非设计。收归时**不做**「调用方传入日志回调」那种形状：
 * 那等于用结构宣布「429 的日志详情是各协议的自由」，会用一个共享的判定掩护抄漏的日志。
 *
 * <p>连带的一处表示统一：无限重试模式下预算显示 {@code ∞}（Chat 原本如此），
 * 而不是 {@code -1}（另两条原本如此）。{@code -1} 是<strong>配置语义值</strong>，
 * {@code ∞} 是<strong>给人看的</strong>，后者在日志里更明确。
 *
 * <h2>退避时长为什么是参数而不是本类自己读</h2>
 * {@code retryFirstBackoff()} / {@code retryMaxBackoff()} 是各执行器的
 * {@code protected} 方法，<strong>7 个测试子类覆写它们把退避压到毫秒级</strong> ——
 * 那是刻意保留的测试覆盖点（见 plan A.4）。抽到本类会让子类多一层间接，
 * 所以由调用方把自己那份时长传进来。
 *
 * <h2>为何用 record 而不是六个散参数</h2>
 * {@code requestId} 与 {@code model} 都是 {@link String} 且<strong>相邻</strong>：
 * 写成散参数时，两处对调<strong>不会编译失败</strong>，症状只是日志与前端 Toast 里
 * 模型名变成 requestId（或被截断的观感）。收进 record 后，构造点只有一处、一眼可核。
 *
 * <p>{@code log} 走参数而非本类自带，理由同 {@link UpstreamCallReporter}：
 * 保持<strong>子类的 logger</strong>，按执行器类名过滤日志的人不会漏掉它。
 */
public final class UpstreamAutoRetry {

    private UpstreamAutoRetry() {
    }

    /**
     * 一次重试所需的调用标识 —— 全部字段<strong>只用于日志与生命周期事件</strong>，
     * 不参与重试判定（判定见 {@link UpstreamRetryPolicy}）。
     *
     * @param method        调用方方法名（如 {@code chatCompletionStream}），用于日志区分重试来源
     * @param protocolLabel 上游协议名（{@code OpenAI} / {@code Anthropic} / {@code Responses}）——
     *                      三条线路原本各写各的（Chat 干脆不带），收归后统一
     * @param providerKey   供应商标识，日志里标明是哪一路上游
     * @param requestId     本次调用唯一标识，用于发出 RETRYING 生命周期事件
     * @param model         模型名（含前缀），用于 RETRYING 事件展示
     * @param stream        是否流式请求
     */
    public record CallContext(String method, String protocolLabel, String providerKey,
                              String requestId, String model, boolean stream) {
    }

    /**
     * 构造一条自动重试规格。
     *
     * <p>次数取自 {@code app_config} 的 {@code retry_max_attempts}（管理后台可改，改完即时生效）：
     * 正数为具体次数，{@code 0} 不重试，{@code -1} 无限重试。
     * 未注入策略服务时（单元测试直接 new 执行器）回退
     * {@link RetryPolicyService#DEFAULT_MAX_ATTEMPTS}。
     *
     * <p>注意 {@code 0} 与「不加 retryWhen」并不完全等价：{@code filter} 与
     * {@code doBeforeRetry} 依旧挂着，只是永远不会触发重订阅，异常照常透传。
     * 保留这条链而不做分支，是为了让重试次数始终只有这一个来源。
     *
     * @param ctx               本次调用的日志/事件标识
     * @param retryPolicyService 次数配置来源；可为 null（未注入）
     * @param lifecycleNotifier 生命周期通知器；可为 null（未注入，测试常态）
     * @param firstBackoff      首次退避时长（各执行器的 {@code retryFirstBackoff()}）
     * @param maxBackoff        退避上限（各执行器的 {@code retryMaxBackoff()}）
     * @param log               调用方的 logger，用于保留日志归属
     * @return 配置好的 {@link Retry}
     */
    public static Retry build(CallContext ctx,
                              RetryPolicyService retryPolicyService,
                              CallLifecycleNotifier lifecycleNotifier,
                              Duration firstBackoff, Duration maxBackoff,
                              Logger log) {
        int configured = retryPolicyService != null
                ? retryPolicyService.getMaxAttempts()
                : RetryPolicyService.DEFAULT_MAX_ATTEMPTS;
        long maxAttempts = RetryPolicyService.toReactorMaxAttempts(configured);
        String budget = configured == RetryPolicyService.UNLIMITED_MAX_ATTEMPTS
                ? "∞" : String.valueOf(configured);

        return Retry.backoff(maxAttempts, firstBackoff).maxBackoff(maxBackoff)
                .filter(UpstreamRetryPolicy::isRetryableFailure)
                .doBeforeRetry(signal -> {
                    int attempt = (int) (signal.totalRetries() + 1);
                    // RETRYING：让前端 Toast 从“已连接/等待中”切换到“上游异常，正在重试（第N次）”，
                    // 避免重试期间静默卡顿让用户误以为卡死。无限模式下前端拿 total=-1 以示无上限。
                    UpstreamCallReporter.publishLifecycle(log, lifecycleNotifier,
                            CallLifecycleEvent.retrying(ctx.requestId(), ctx.model(), ctx.stream(), attempt));
                    logRetryAttempt(log, ctx, signal, attempt, budget);
                });
    }

    /**
     * 记录一次重试的日志 —— 三条线路共用一套文案。
     *
     * <p>429 单独区分：限速是上游明确的节流信号，附带其 {@code Retry-After} 头
     * 便于人工判断退避是否符合预期；其余失败只记异常消息。
     */
    private static void logRetryAttempt(Logger log, CallContext ctx, Retry.RetrySignal signal,
                                        int attempt, String budget) {
        if (signal.failure() instanceof WebClientResponseException responseException
                && responseException.getStatusCode().value() == 429) {
            String retryAfter = responseException.getHeaders().getFirst("Retry-After");
            log.warn("[{}] {} {} API 限速 (429)，重试第 {}/{} 次{}",
                    ctx.method(), ctx.providerKey(), ctx.protocolLabel(), attempt, budget,
                    retryAfter != null ? "，Retry-After: " + retryAfter + "s" : "");
        } else {
            log.warn("[{}] {} {} 调用失败，重试第 {}/{} 次: {}",
                    ctx.method(), ctx.providerKey(), ctx.protocolLabel(), attempt, budget,
                    signal.failure().getMessage());
        }
    }
}

package com.kaixuan.copilot_ollama_proxy.control;

import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 人工重发（管理后台「静默重试」）的<strong>递归循环</strong> —— 三个上游执行器共用一份实现。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：控制面（<strong>不是主干/支线</strong>） · 位置：{@code control/}
 * 人工重发循环—— 外部信号驱动、只服务流式、不消耗自动重试预算
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>它为什么住在 {@code control} 包（阶段 3.7 第①批）</h2>
 * 它此前叫 {@code UpstreamSilentRetry} 且住在 {@code provider/} 顶层，与
 * {@code UpstreamRetryPolicy} / {@code UpstreamAutoRetry} 并列 —— 那个摆法把它摆成了
 * 「自动重试的第三件」，而<strong>它根本不是</strong>。三条实测依据：
 * <ul>
 *   <li>它<strong>只服务流式</strong>：只在三个 {@code *Stream} 方法里被调用，
 *       三个非流式方法里零引用（人工重发需要一条尚未断开的连接来续）；</li>
 *   <li>它的入口是一个 <strong>HTTP 端点</strong>（管理后台 {@code retry}），
 *       属入站控制，不是管道的一步；</li>
 *   <li>它的孪生兄弟是<strong>取消</strong>（{@link CallCancellationRegistry} +
 *       {@link CallCanceledException}），同样由外部信号驱动。</li>
 * </ul>
 * 因此它与那两个「自动重试」不属于同一条轴，而与取消同族 —— 都归入本包。
 * 于是 {@code upstream/} 只回答一个问题「一次请求的数据怎么流」，
 * 而「人可能在中途插手」是另一条轴、集中在这里。
 *
 * <p>改名的理由：它不再与「重试」那两个共处一类，「Retry」字样会继续误导
 * （见下「与自动重试不是一回事」的错误前提）。{@code Resend} 描述的是它真正做的事。
 *
 * <h2>它是独立功能，与自动重试不是一回事</h2>
 * 「重试」在这条链路里指两种完全不同的东西，触发者、预算与观感都不同：
 *
 * <table>
 *   <caption>两种重试的分野</caption>
 *   <tr><th></th><th>自动重试（{@code UpstreamRetryPolicy} + {@code UpstreamAutoRetry}）</th>
 *       <th>人工重发（本类）</th></tr>
 *   <tr><td><strong>触发者</strong></td><td>COSP 自己判定（429 / 5xx / 网络中断 / 空响应）</td>
 *       <td><strong>人</strong> —— 管理后台右键 Toast 点「静默重试」</td></tr>
 *   <tr><td><strong>预算</strong></td><td>消耗 {@code retry_max_attempts}</td>
 *       <td>不消耗，可连点</td></tr>
 *   <tr><td><strong>下游观感</strong></td><td>完全无感（发生在首字节之前）</td>
 *       <td>SSE 连接保持打开，新内容续在同一条流上</td></tr>
 * </table>
 *
 * <p>「不共用预算」是刻意的：那条预算属于「COSP 自己判定的失败」，
 * 而这里是管理员的显式意图，两者互相挤占会让「重试 5 次」这个配置变得难以预测。
 *
 * <h2>为什么信号必须挂在<strong>整轮尝试</strong>上</h2>
 * 挂载点是「含 {@code retryWhen} 退避等待」的那一整轮，而不是单次 HTTP 往返 ——
 * 因此请求进行中与退避等待<strong>两个阶段</strong>都能被中断。
 * 若只挂在单次往返上，用户在上游反复失败、正等着退避时点击重试会<strong>毫无反应</strong>，
 * 而那恰恰是最需要它的时刻。
 *
 * <h2>递归形状为何是 {@code AtomicReference} 自引用</h2>
 * 「被中断就再来一轮」是自我引用，而 Reactor 的链在<strong>构造期</strong>就要定型。
 * 于是先建引用、后回填：{@code loopRef} 指向 {@code loop} 自身，
 * {@code concatWith} 里取它即得到新一轮订阅。
 *
 * <p>每轮都重新 {@link CallRetryRegistry#register} 一个<strong>新鲜</strong>的信号
 * （注册发生在 {@code Flux.defer} 内，每次重订阅都重跑），因此可以连续点击；
 * 下游断连时整个链被取消，递归随之终止。
 *
 * <h2>与执行器的分工</h2>
 * 本类只负责「中断与再发起」这个<strong>机制</strong>。协议相关的部分由调用方提供：
 * <ul>
 *   <li>{@code protocolLabel} —— 日志里的协议名。三处原本只有 Chat 不带协议名
 *       （「重新发起上游请求」），另两条各带自己的；现统一为都带，
 *       信息量不变而格式一致，便于按消息文本检索；</li>
 *   <li>形态归一（{@code UpstreamEventClassifier.classify}）—— 由调用方接在
 *       <strong>返回值之外</strong>。那是协议关联的一步，本类对协议一无所知；
 *       接在外侧同样覆盖静默重发的那一轮 —— 重发的产物正是循环输出的一部分。</li>
 * </ul>
 *
 * <p>{@code log} 走参数而非本类自带，理由同 {@code UpstreamCallReporter}：
 * 保持<strong>子类的 logger</strong>，按执行器类名过滤日志的人不会漏掉它。
 */
public final class CallResendLoop {

    private CallResendLoop() {
    }

    /**
     * 把一个「单轮尝试」包装成可被静默重试信号中断、且中断后自动重发的循环。
     *
     * @param roundAttempt       单轮尝试。<strong>必须本身是个 {@code Flux.defer}</strong>
     *                           （三条线路传进来的都是），故重订阅即重跑整轮（含
     *                           {@code retryWhen} 与退避）—— 这是「每轮取一份新鲜状态」得以成立的前提
     * @param callRetryRegistry  静默重试信号注册表；未注入（单元测试直接 new 执行器）时为 null，
     *                           此时信号恒为 {@link Mono#never()}，循环退化为「只有一轮」
     * @param requestId          本次调用唯一标识，用作信号键；null 时同上
     * @param protocolLabel      日志用的上游协议名（{@code OpenAI} / {@code Anthropic} / {@code Responses}）
     * @param log                调用方的 logger，用于保留日志归属
     * @param model              模型名（含前缀），仅用于日志
     * @param <T>                帧元素类型 —— 三条线路不同（Chat 是 {@code ServerSentEvent<String>}，
     *                           另两条是裸 {@code String}），故与
     *                           {@link com.kaixuan.copilot_ollama_proxy.pipeline.after.attempt.EmptyResponseGate}
     *                           同样做成泛型
     * @return 可被中断并自动重发的循环
     */
    public static <T> Flux<T> loop(Flux<T> roundAttempt,
                                   CallRetryRegistry callRetryRegistry,
                                   String requestId, String protocolLabel,
                                   Logger log, String model) {
        AtomicBoolean retryRequested = new AtomicBoolean(false);
        AtomicReference<Flux<T>> loopRef = new AtomicReference<>();
        Flux<T> loop = Flux.defer(() -> {
                    Mono<Void> silentRetrySignal = callRetryRegistry == null || requestId == null
                            ? Mono.never()
                            : callRetryRegistry.register(requestId)
                                    .doOnSuccess(v -> retryRequested.set(true));
                    return roundAttempt.takeUntilOther(silentRetrySignal);
                })
                .concatWith(Flux.defer(() -> {
                    if (retryRequested.compareAndSet(true, false)) {
                        log.info("静默重试：重新发起 {} 上游请求 [{}] {}", protocolLabel, model, requestId);
                        return loopRef.get();
                    }
                    return Flux.empty();
                }));
        loopRef.set(loop);
        return loop;
    }
}

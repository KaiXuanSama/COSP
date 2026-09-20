package com.kaixuan.copilot_ollama_proxy.application.pipeline;

import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 一次请求在管道里的<strong>执行登记</strong> —— 走了哪条链路、哪些步骤真的执行了。
 *
 * <h2>它解决的问题</h2>
 * 管道的某些步骤只在特定协议组合下才有实现（见 {@link PipelineStep}）。
 * 上游执行层<strong>看不到</strong>这些事实：它只收到请求体、供应商与落库视图，
 * 不知道编排层做过什么决策、哪些步骤被跳过了。
 *
 * <p>空响应拦截因此失去判据 —— 它要回答「这一轮到底有没有内容」，
 * 而那取决于「这批帧是不是本服务所服务协议的形态」，
 * 后者又取决于「回程翻译有没有接上」。执行层推不出这个事实，只能被<strong>告知</strong>。
 *
 * <h2>为何不让执行层按帧形状自己猜</h2>
 * 曾考虑给检测器加第三态（不是本协议形状），由拦截层按「全是不认识的形状」判定。
 * 两个问题：
 * <ul>
 *   <li><strong>启发式有两面</strong>：形状判别会漏判也会误判。实测过一个反例 ——
 *       Responses 的检测器只做「{@code delta} 是非空对象吗」，
 *       而 Anthropic 的 {@code message_delta} 恰好带 {@code delta:{stop_reason:...}}，
 *       于是<em>纯控制帧</em>会被判成有载荷，一路真空的响应因此不再被识别；</li>
 *   <li>「回程翻译有没有接」是<strong>已经确定的事实</strong>，编排层在组装期就知道。
 *       记下来比让下游反推更准确，也更便宜。</li>
 * </ul>
 *
 * <h2>生命周期：请求级，跨重试<strong>不</strong>重置</h2>
 * 与 {@code gateOpen} / {@code heldFrames} 那类<strong>流级</strong>状态刚好相反 ——
 * 后者每轮往返在 {@code Flux.defer} 起点重置，而本类是请求级事实：
 * 重试一次不会改变「回程翻译没接」这件事。
 *
 * <p>这个区别容易被顺手写错：把本类实例构造在 {@code defer} 内部，
 * 单轮用例全部照常通过，只有「半实现态 + 重试」才会暴露 ——
 * 而那恰恰是它要服务的场景，排查时会先去怀疑检测器。
 *
 * <h2>谁写、谁读</h2>
 * <ul>
 *   <li><strong>写</strong>：编排层（{@code ChatCompletionService} 等）在组装期，
 *       拿到调度结论之后、调用上游执行器之前；</li>
 *   <li><strong>读</strong>：上游执行器里的空响应拦截。</li>
 * </ul>
 * 被跳过的步骤<strong>没有机会登记自己</strong>（它压根没执行），
 * 因此登记只能由编排层代劳 —— 这也说明它天然是请求级事实。
 *
 * <h2>与 {@code RequestPipelineContext} 的关系</h2>
 * 重构方向文档 §2.4 描述的请求级上下文（{@code route} / 两侧协议 / {@code headers} /
 * {@code translationContext}）是它的最终形态。本类现在只承载其中<strong>被消费</strong>的那部分，
 * 不提前塞入尚无人读取的字段 —— 那些等各自的读取方出现时再加入。
 */
public final class PipelineExecution {

    /** 空登记：协议未知、无已完成步骤。不可变，可安全复用。 */
    private static final PipelineExecution EMPTY =
            new PipelineExecution(null, null, EnumSet.noneOf(PipelineStep.class));

    private final WireProtocol downstreamProtocol;
    private final WireProtocol upstreamProtocol;
    private final Set<PipelineStep> completedSteps;

    private PipelineExecution(WireProtocol downstreamProtocol, WireProtocol upstreamProtocol,
                              Set<PipelineStep> completedSteps) {
        this.downstreamProtocol = downstreamProtocol;
        this.upstreamProtocol = upstreamProtocol;
        this.completedSteps = completedSteps;
    }

    /**
     * 空登记：协议未知、什么都没执行过。
     *
     * <p>既有调用方（测试辅助方法、不带上下文的兼容重载）的默认值。
     * 它<strong>不构成跳过任何步骤的理由</strong> —— 见
     * {@link #shouldApplyEmptyResponseGate()}。
     */
    public static PipelineExecution empty() {
        return EMPTY;
    }

    /**
     * 登记一次调用的两侧协议。
     *
     * <p>协议是<strong>数据</strong>：拦截据此知道「下游期待什么形状」，
     * 不必从帧反推。两侧相同即直连，无需再登记翻译步骤。
     *
     * @param downstream 下游使用的协议
     * @param upstream   实际对上游使用的协议
     */
    public static PipelineExecution of(WireProtocol downstream, WireProtocol upstream) {
        return new PipelineExecution(downstream, upstream, EnumSet.noneOf(PipelineStep.class));
    }

    /** 记录一个<strong>已执行</strong>的步骤。返回新实例，本类不可变。 */
    public PipelineExecution withCompleted(PipelineStep step) {
        EnumSet<PipelineStep> next = completedSteps.isEmpty()
                ? EnumSet.noneOf(PipelineStep.class)
                : EnumSet.copyOf(completedSteps);
        next.add(step);
        return new PipelineExecution(downstreamProtocol, upstreamProtocol, next);
    }

    /** 该步骤是否登记为已执行。 */
    public boolean hasCompleted(PipelineStep step) {
        return completedSteps.contains(step);
    }

    /** 已执行的步骤集合（只读）。 */
    public Set<PipelineStep> completedSteps() {
        return Collections.unmodifiableSet(completedSteps);
    }

    /** 下游使用的协议；未登记时为 {@code null}。 */
    public WireProtocol downstreamProtocol() {
        return downstreamProtocol;
    }

    /** 对上游使用的协议；未登记时为 {@code null}。 */
    public WireProtocol upstreamProtocol() {
        return upstreamProtocol;
    }

    /**
     * 两侧协议是否不同，即本次调用是否需要翻译组件介入。
     *
     * <p>协议未登记时返回 false —— 不猜。
     */
    public boolean translationNeeded() {
        return downstreamProtocol != null && upstreamProtocol != null
                && downstreamProtocol != upstreamProtocol;
    }

    /**
     * 空响应拦截是否应当介入。
     *
     * <h2>判据：回程翻译未执行 → 跳过</h2>
     * 「需要翻译，但回程没接」这个组合就是<strong>开发者的半轮实现态</strong>：
     * 去程已接（请求发得出去、上游能理解），回程还没接。
     *
     * <p>此时交给下游的帧是<strong>上游协议的形态</strong>。拦截要判「这一轮有没有内容」，
     * 而它的判据属于本服务所服务的那个协议 —— 对着另一个协议的帧，
     * 判定结果不承载任何信息，只会把过程拖成「扣住 → 判否 → 重试 →
     * 白等完整轮预算（生产值约 62 秒、6 次上游调用）→ 才原样放行」。
     *
     * <p>开发者要的恰恰是那批帧本身：他正在对齐新写的去程翻译，
     * 需要看上游到底发了什么。帧本来就在手里，不该被重试机制压住一分钟。
     *
     * <h2>为何是「回程未执行」而非「两侧协议不同」</h2>
     * 两侧协议不同但<strong>回程已接</strong>时（C2M 现状），拦截照常生效是正确的 ——
     * 一条真正空的跨协议响应同样应该被识别并重试。
     * 判据因此落在「这个步骤有没有实现」，而不是「协议同不同」。
     *
     * <h2>三种情形各行其道</h2>
     * <table>
     *   <caption>拦截是否介入</caption>
     *   <tr><th>情形</th><th>登记</th><th>拦截</th></tr>
     *   <tr><td>直连</td><td>两侧同协议，无翻译步骤</td><td>介入（与重构前一致）</td></tr>
     *   <tr><td>C2M（全实现）</td><td>去程 + 回程均已登记</td><td>介入</td></tr>
     *   <tr><td>半轮实现</td><td>仅去程已登记</td><td><strong>跳过</strong>：整轮放行，不判空、不重试</td></tr>
     * </table>
     *
     * <p>直连那一行依赖「两侧协议相同时也返回 true」——
     * 跳过只对「半轮实现」成立，其余一律照常，这样既有调用方
     * （未登记协议、未登记步骤）不会静默失去空响应兜底。
     *
     * @return true 表示照常拦截；false 表示跳过本步骤
     */
    public boolean shouldApplyEmptyResponseGate() {
        // 直连：帧的形状与下游期待一致，拦截照常。
        if (!translationNeeded()) {
            return true;
        }
        // 跨协议：只有回程已接，拦截才判得有意义。
        return hasCompleted(PipelineStep.RESPONSE_TRANSLATION);
    }
}

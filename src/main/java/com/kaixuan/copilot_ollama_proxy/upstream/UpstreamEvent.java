package com.kaixuan.copilot_ollama_proxy.upstream;

/**
 * 上游响应在管道里的<strong>统一形态</strong>。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：主干<strong>值类型</strong> · 位置：{@code upstream/}（层根）
 * 步骤「统一形态出口」—— 主干与执行器之间传递的唯一形态；非流式 = 恰有一个元素的流
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>它统一的是什么</h2>
 * 管道此前有两种传输形态：非流式是 {@code Mono<String>}（一个完整响应体），
 * 流式是 {@code Flux<String>}（一串帧）。两者在重试、空响应判定、落库三处
 * 各自需要一套写法，而它们的差异其实只在于<strong>元素个数</strong>。
 *
 * <p>收成一个形态之后，「非流式」就是<strong>只有一个元素的事件流</strong>，
 * 而那三处只需要面对一种输入。形态统一的边界止于<b>出口</b>（控制器）——
 * 下游协议要什么由出口适配：流式侧读 {@link #isTerminal()} 触发收尾，
 * 非流式侧取 {@link #data()} 拿报文。
 *
 * <h2>两态，没有 error</h2>
 * 失败<strong>不</strong>进事件流，仍走 Reactor 的 {@code onError} 信号。这是刻意的：
 * {@code retryWhen} 只认信号，不认流内元素 —— 把失败做成 in-band 事件会让
 * 重试判定、每轮落库、耗尽放行、控制器分类这四处全部需要改写成
 * 「在流里找错误事件」，而它们此刻都正确工作。失败继续走信号，
 * 本步才是纯粹的形态统一。
 *
 * <h2>Terminal 为什么不只是个标记</h2>
 * 它<strong>携带原文</strong>，因为三种协议都要求终止标记本身下发给客户端：
 * Chat 的 {@code [DONE]}、Anthropic 的 {@code message_stop}、
 * Responses 的终态事件，下游状态机都靠它收尾。
 * 若把它做成无载荷的哨兵，下发时就得再构造一次原文 —— 那等于把协议知识
 * 从「检测到它的人」手里拿走。
 *
 * <p>它与 {@link Body} 的真正区别在于<strong>语义</strong>：
 * {@code Body} 是「上游说了点什么」，{@code Terminal} 是「上游说完了」。
 * 此前这个区别靠三处<strong>字符串模式匹配</strong>表达
 * （{@code "[DONE]".equals(...)} / {@code message_stop} / {@code ResponsesStreamEvents.isTerminal}），
 * 现在它是一个类型。
 *
 * <h2>非流式恒为 Body，且这是<strong>被统一的</strong>而非被排除的</h2>
 * 非流式的响应体里不存在协议级终止标记（三个协议都是如此：{@code [DONE]} /
 * {@code message_stop} / Responses 终态事件都只在流式出现），
 * 因此非流式的元素恒为 {@link Body}。
 *
 * <p>但<strong>它照样走本形态</strong>：非流式在管道里就是
 * 「恰有一个元素的流」（{@code Mono<UpstreamEvent>}），
 * 于是 {@code send} 之后的所有阶段 —— 空响应拦截、重试边界、耗尽放行、清洗、落库 ——
 * 只需面对<strong>一种</strong>输入，不必为「单事件」与「多事件」各写一套。
 *
 * <p>曾经的选择是让非流式保持 {@code Mono<String>}，理由是
 * 「本类型相对 {@code String} 唯一多出的 terminal 态在非流式恒为假，故不承载信息」。
 * <strong>那个论证偷换了论题</strong>：它证明了「terminal 字段在非流式无意义」，
 * 却用来否决「这套类型在非流式无意义」—— 而统一形态要买的不是多一个字段，
 * 是「后续阶段不必区分两种形态」。代价则是主干在 {@code send} 处永久分岔成两根，
 * 正是重构要消除的东西。
 *
 * <p>与流式的唯一差异因此被压缩到<strong>元素个数</strong>上：
 * {@code Mono<T>} 与 {@code Flux<T>} 元素类型相同，{@code mono.flux()} /
 * {@code flux.single()} 是标准转换，阶段签名写成 {@code Flux<UpstreamEvent>}
 * 即可同时服务两者。
 *
 * @see Body
 * @see Terminal
 */
public sealed interface UpstreamEvent {

    /**
     * 下游可见的原始载荷。
     *
     * <p>两态都携带它：{@link Body} 是内容或控制帧，{@link Terminal} 是终止标记原文。
     * 控制器据此构造 SSE 帧，无需区分两态 —— 这是「形态统一」最直接的兑现。
     */
    String data();

    /** 是否为本轮的终止标记。控制器据此触发收尾，替代原先的字符串匹配。 */
    default boolean isTerminal() {
        return this instanceof Terminal;
    }

    /**
     * 一帧上游载荷。
     *
     * <p>可能是正文、思考链、工具调用参数，也可能是协议的控制帧
     * （{@code content_block_start} 之类）—— 本类不区分它们：
     * 「有没有内容」是判定器的事，形态层只负责搬运。
     */
    record Body(String data) implements UpstreamEvent {

        public Body {
            java.util.Objects.requireNonNull(data, "data");
        }
    }

    /**
     * 本轮的终止标记 —— 上游说完了。
     *
     * <p>只有流式会产生它：非流式的响应体里没有协议级终止标记（见接口注释）。
     */
    record Terminal(String data) implements UpstreamEvent {

        public Terminal {
            java.util.Objects.requireNonNull(data, "data");
        }
    }

    /** 构造一帧载荷。 */
    static Body body(String data) {
        return new Body(data);
    }

    /** 构造一个终止标记。 */
    static Terminal terminal(String data) {
        return new Terminal(data);
    }
}

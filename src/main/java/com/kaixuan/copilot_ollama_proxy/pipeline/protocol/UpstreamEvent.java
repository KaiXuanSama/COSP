package com.kaixuan.copilot_ollama_proxy.pipeline.protocol;

import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTokens;

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
     * 本帧携带的 token 用量 —— <strong>生产者在流内填，出口只读</strong>。
     *
     * <h2>为何挂在这个值类型上（阶段 5 步 7b-1「usage 消重复」）</h2>
     * 同一份上游字节此前被<strong>解析两遍</strong>：块 2 解析一次写 {@code api_call_usage}（明细），
     * 出口又解析一次写 {@code api_usage_daily}（聚合）。
     * 后一遍把协议语义搬到了出口 —— 而出口本该只知道「这些字节要包成什么 HTTP 形状」，
     * 不该知道「这些字节在协议上是什么意思」（本层 README §3.3）。
     *
     * <p>消重复的载体<strong>不是</strong> {@code RequestPipelineContext}：那个 ctx 由
     * {@code entry/} 的三个 Service 在 {@code defer} 内创建、从不返回给出口，
     * 而且出口的记账发生在 Layer 1（收到终止标记<strong>那一刻</strong>就 finalize，
     * 不等 TCP 关闭）。<strong>数据已经开始流动之后，唯一能承载跨层信息的就是流里的元素</strong> ——
     * 故槽开在此处，与「主干交给出口的形态」是同一个东西。
     *
     * <h2>语义：生产者挂「到目前为止的累积值」</h2>
     * 不是「这一帧新增的量」，也不是「只有尾帧才挂」—— 而是<strong>每帧都挂当前累积值</strong>。
     * 因为上游不发终止标记就直接断连时要靠出口的 Layer 2 兜底，
     * 那条路径需要从<strong>流内已见过的值</strong>记账；只在尾帧挂会让它退回 {@code 0,0}。
     *
     * <p>消费规则因此可以极简：<strong>取最后一份「有数据的」（非 null 且非空）即结算值</strong>。
     * 这条规则<strong>与协议无关</strong> —— 三种协议的累积差异（Chat / Responses 后到覆盖、
     * Anthropic 跨事件只有正数才覆盖、C2M 由上游侧算好）已被生产者吸收干净。
     * 出口一行协议分支都不剩，这正是「消重复」的判据。
     *
     * <p>「非空」那一档不省略：上游可能给出本服务不认识的 usage 字段名（解析为
     * {@link UsageTokens#EMPTY}），收下它会把先前的真实值刷成 {@code 0}。
     * 实现在 {@code api/shared/UsageAccounting.accumulate}。
     *
     * <h2>null 的含义</h2>
     * {@code null} = 生产者至今未见过 usage（或本帧不经过生产者）。
     * 它与 {@link UsageTokens#EMPTY}（上游报了但三个字段都缺）不同，两者不可归一：
     * 出口的「最后一份非 null」靠这个区分「没有」与「有但是空」。
     *
     * @return 累积到本帧为止的 token 用量；未见任何 usage 时为 {@code null}
     */
    UsageTokens usage();

    /**
     * 复制本帧并换上一个 usage（{@code data} 与两态保持不变）。
     *
     * <p>供生产者在分类之后追加 usage 用：{@code classify(...)} 的职责只是分两态，
     * 不该由它承担「累积 usage」这件事（那是各协议执行器的流级态）。
     * 用不可变复制而非可变字段：事件可能在多处被引用，改原对象会让「那一帧当时携带什么」
     * 随之后的事件漂移 —— 而出口要的恰恰是「帧到达时看到了什么」。
     */
    default UpstreamEvent withUsage(UsageTokens usage) {
        return switch (this) {
            case Body body -> new Body(body.data(), usage);
            case Terminal terminal -> new Terminal(terminal.data(), usage);
        };
    }

    /**
     * 一帧上游载荷。
     *
     * <p>可能是正文、思考链、工具调用参数，也可能是协议的控制帧
     * （{@code content_block_start} 之类）—— 本类不区分它们：
     * 「有没有内容」是判定器的事，形态层只负责搬运。
     */
    record Body(String data, UsageTokens usage) implements UpstreamEvent {

        Body(String data) {
            this(data, null);
        }

        public Body {
            java.util.Objects.requireNonNull(data, "data");
        }
    }

    /**
     * 本轮的终止标记 —— 上游说完了。
     *
     * <p>只有流式会产生它：非流式的响应体里没有协议级终止标记（见接口注释）。
     */
    record Terminal(String data, UsageTokens usage) implements UpstreamEvent {

        Terminal(String data) {
            this(data, null);
        }

        public Terminal {
            java.util.Objects.requireNonNull(data, "data");
        }
    }

    /** 构造一帧载荷（不带 usage）。 */
    static Body body(String data) {
        return new Body(data, null);
    }

    /** 构造一帧载荷并挂上 usage。 */
    static Body body(String data, UsageTokens usage) {
        return new Body(data, usage);
    }

    /** 构造一个终止标记（不带 usage）。 */
    static Terminal terminal(String data) {
        return new Terminal(data, null);
    }

    /** 构造一个终止标记并挂上 usage。 */
    static Terminal terminal(String data, UsageTokens usage) {
        return new Terminal(data, usage);
    }
}

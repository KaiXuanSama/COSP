package com.kaixuan.copilot_ollama_proxy.upstream.content;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;

/**
 * 判定上游响应是否带<strong>实质载荷</strong>的支线 —— 空响应拦截的判据来源。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：<strong>契约</strong>（支线） · 位置：{@code upstream/content/}
 * 步骤「空响应判定」—— 经 {@link ContentDetectorRegistry} 按上游协议查表取得
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>为什么它是支线</h2>
 * 按方向文档 §2.1 的判据：<strong>协议差异是代码 → 支线；是数据 → 主干</strong>。
 * 「什么算有内容」这件事三条线路的实现毫无交集（实测取值路径）：
 *
 * <table>
 *   <caption>三条线路的取值路径</caption>
 *   <tr><th>协议</th><th>整轮判（非流式）</th><th>逐帧判（流式）</th></tr>
 *   <tr><td>Chat</td><td>{@code choices[].message}</td><td>{@code choices[].delta}</td></tr>
 *   <tr><td>Anthropic</td><td>{@code content[]}</td><td>按事件类型分派</td></tr>
 *   <tr><td>Responses</td><td>{@code output[]}</td><td>按十余种事件类型分派</td></tr>
 * </table>
 *
 * <p>而<strong>「空返拦截 + 重试」这一族里，唯一的协议关联点就是这个检测器</strong> ——
 * 其余（gate 机制、重试预算、异常解包、耗尽放行）全部与协议无关，留在主干。
 * 这是阶段 3.6 的核心发现：把唯一的协议关联点做成支线，主干上就不再需要任何协议判断。
 *
 * <h2>两个判据共享一套「类别定义」，但取值路径各自独立</h2>
 * 三条线路对「什么算实质载荷」的<strong>策略</strong>必须同口径 ——
 * 正文、思考链、工具调用三类任一非空即算有内容。
 * 否则会重演「切一下协议，同一个上游故障的结论就不同」。
 * 但<strong>怎么读到这三类</strong>完全按协议各写各的（上表），故独立实现。
 *
 * <h2>与 {@link ChunkNormalizeStage} 的关系：同族但不同职责</h2>
 * 那个处理「有内容时怎么改写」，本接口回答「有没有内容」。两者都在
 * {@code ctx.upstreamProtocol()} 上查表，但<strong>未命中语义不同</strong>（见下）。
 *
 * <h2>未命中 = 报错（不是跳过）</h2>
 * 另两个阶段的未命中是「跳过」（表达「这种协议没这一步」这个领域事实）。
 * 本接口没有这种解释：<strong>每个上游协议都必须能判空</strong> ——
 * 判不了就意味着空响应兜底对那条线路失效，而那是保护用户的功能。
 * 因此查表未命中属<strong>装配错误</strong>，由 {@link ContentDetectorRegistry} 抛出。
 *
 * <h2>⚠️ 接口方法的语义，不要按实现类的方法名推断</h2>
 * 三个现有检测器是静态工具类（{@code OpenAiContentDetector} /
 * {@code AnthropicContentDetector} / {@code ResponsesContentDetector}），
 * 它们的方法名<strong>不一致且会骗人</strong> ——
 * 最典型的是 {@code OpenAiContentDetector.hasMeaningfulPayload}
 * <strong>读的是 {@code delta}，即它其实是流式用的</strong>；
 * 而另两条线路同名的那个方法读的是完整 body（非流式）。
 * 因此接线时必须按<strong>实际取值路径</strong>映射，见各实现类的注释。
 * 接错不会编译失败（签名相同），症状是「某条线路的空响应判定失效」。
 *
 * <h2>为何不叫 {@code ContentDetector}</h2>
 * 因为那会与三个静态工具类的<b>职责名</b>撞在一起（它们就是「检测器」，
 * 只是还是静态工具而非可查表的类型）。本包内五个同类接口统一用
 * {@code *Stage} 后缀（{@code ChunkNormalizeStage} / {@code ReasoningFallbackStage} /
 * {@code SystemPromptNormalizeStage} …），本接口沿用同一约定。
 */
public interface ContentDetectorStage {

    /** 本检测器服务的协议 —— 查表键。 */
    WireProtocol protocol();

    /**
     * 整轮判：一份<strong>完整的</strong>非流式响应体是否带实质载荷。
     *
     * @param fullBody 上游完整响应体；null / 空白视为空响应
     * @return 含正文 / 思考链 / 工具调用之一返回 true；<strong>解析失败也返回 true</strong>
     *         （保守放行：宁可放行没见过的格式，也不要因结构陌生把正常响应判成空并重试）
     */
    boolean hasMeaningfulPayload(String fullBody);

    /**
     * 逐帧判：这一个流式事件是否<strong>贡献了</strong>实质载荷。
     *
     * <p>语义是「贡献」而非「整体有内容」：调用方在一轮内做逻辑或。
     * 这条很关键 —— 例如 Anthropic 的 {@code message_start} 只有元信息、
     * {@code content_block_start} 只声明类型，逐事件判「有没有内容」会把
     * 正常响应的头两个事件判成空。
     *
     * @param eventData 单个上游事件的 data 内容；{@code [DONE]} 等控制帧不算实质载荷
     * @return 本事件贡献了正文 / 思考链 / 工具调用之一返回 true；解析失败也返回 true
     */
    boolean eventHasPayload(String eventData);
}

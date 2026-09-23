package com.kaixuan.copilot_ollama_proxy.upstream.chunk.fallback;

import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 只有思考链、没有正文时的<strong>兜底回退支线</strong> —— 主干上一个按协议查表的接入点。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：<strong>契约</strong>（支线） · 位置：{@code upstream/chunk/fallback/}
 * 步骤「reasoning fallback」—— 只有思考链没有正文时补一对伪 chunk
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>它做什么</h2>
 * 早期部分模型会把思考链<strong>当作正文输出</strong>（只吐 {@code reasoning_content}、
 * 正文一字没有），下游看到的是空白回复。本支线在流的终止 chunk 到达时发现这种情形，
 * 用累积的思考内容补一对伪 chunk（一个承载正文、一个标记结束）。
 *
 * <h2>它是一个 gate，因此留在逐帧处理里而非「主干末步」</h2>
 * 触发看三个东西：{@code contentEmitted}（整流是否发过正文）、{@code reasoningBuffer}
 * （攒下的思考）、以及<strong>当前 chunk 是不是终止标记</strong>。前两个是跨帧累积状态，
 * 而 stop chunk 的到达<strong>就是它的「流末信号」</strong> —— 回看「历史流过的是不是只有思考」，
 * 是则整合成正文发下去。这与空响应 gate 同构（累积 + 轮末判定），
 * 因此不必真的挪到 {@code doFinally}，随 stop chunk 就地触发即可。
 * 见方向文档 §5.1.1「步骤的生命周期位置」。
 *
 * <h2>为何是接口 / 为何只有 Chat 实现 / 查表键是上游协议</h2>
 * 与 {@link ChunkNormalizeStage} 同一套理由：接口在 3.1 成形、
 * 3.3d-2 由 {@link ChunkStageRegistry} 按 {@code ctx.upstreamProtocol()} 接线。
 * Chat 专属（读写 {@code choices[].delta.content} 与
 * {@code reasoning_content} 这些 OpenAI 形态），另两条协议查不到实现即跳过，是预期行为。
 *
 * <h2>流级状态作参数传入</h2>
 * {@code contentEmitted} / {@code reasoningBuffer} / {@code chunkId} 是流算子内部闭包状态
 * （§2.4），作方法参数穿线传递，不塞进请求级 context。
 */
public interface ReasoningFallbackStage {

    /**
     * 本支线服务的协议 —— 查表键。只有 {@link WireProtocol#CHAT} 实现存在。
     */
    WireProtocol protocol();

    /**
     * 判断当前（已归一的）chunk 是否应触发 reasoning fallback。
     *
     * <p>签名与原静态方法 {@code ReasoningFallback.shouldFallback} 逐字一致。
     * 四个触发条件（终止 chunk、{@code finish_reason=stop}、从未发过正文、有思考可回退）
     * 见实现类。
     *
     * @param normalizedChunk <strong>已归一</strong>的 chunk（判定必须看归一后的形态）
     * @param contentEmitted  整个流是否已发出过正文
     * @param reasoningBuffer 累积的思考内容
     * @return 四条同时成立时返回 true
     */
    boolean shouldFallback(String normalizedChunk, AtomicBoolean contentEmitted, StringBuilder reasoningBuffer);

    /**
     * 构造回退用的两个伪 chunk：先正文、后结束标记。
     *
     * <p>签名与原静态方法 {@code ReasoningFallback.buildFallbackFrames} 逐字一致。
     *
     * @param chunkId          当前流的 chunk ID（取自归一时记录的最近一个真实 id）
     * @param model            模型名
     * @param reasoningContent 累积的思考内容，作为正文补发
     * @return 两个元素的列表：[正文 chunk, 结束 chunk]
     */
    List<String> buildFallbackFrames(String chunkId, String model, String reasoningContent);
}

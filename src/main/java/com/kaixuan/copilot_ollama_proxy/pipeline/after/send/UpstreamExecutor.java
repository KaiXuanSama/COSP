package com.kaixuan.copilot_ollama_proxy.pipeline.after.send;

import com.kaixuan.copilot_ollama_proxy.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;
import com.kaixuan.copilot_ollama_proxy.protocol.ChunkLogPayload;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEvent;

/**
 * 上游执行器的<strong>共同入口</strong> —— 主干上 {@code send} 那个插槽的实现契约。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：<strong>契约</strong>（send 插槽） · 位置：{@code pipeline/after/send/}
 * 步骤「发送」—— 主干按 {@code ctx.upstreamProtocol()} 查表选中实现
 * <p>完整步骤树见 {@code pipeline/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>为什么需要它</h2>
 * 三个执行器（Chat / Anthropic / Responses）此前各有一套公开方法名
 * （{@code chatCompletion} / {@code messages} / {@code responses}），
 * 主干若按协议「三选一」就会在主干上留一个分叉 —— 与本重构的本意相反
 * 。接口化之后，主干只写一行查表：
 *
 * <pre>
 * executors.get(ctx.upstreamProtocol()).invoke(ctx, chunkRewriter)
 * </pre>
 *
 * <p>这是 {@code SESE Pipeline with Joining Branches} 里 <strong>send 插槽</strong>的载体：
 * 协议差异是<strong>代码</strong>（三个实现各不相同），因此它必须是支线，
 * 有进有出、汇回主干。加一个上游协议 = 加一个 {@code @Component}，主干一个字不动
 * —— 与 {@code ProtocolTranslator} / {@code ChunkStageRegistry} / {@code RequestBodyStageRegistry} 同一机制。
 *
 * <h2>键由实现<strong>声明</strong>，不靠类名猜</h2>
 * {@link #protocol()} 返回本执行器服务的上游协议。与项目其它地方的取舍一致
 * （能力由 {@code caps_tools} 声明，不按模型名猜）。同一协议两个实现 = 配置错误，
 * 由 registry 在启动期抛出，不静默择一。
 *
 * <h2>参数为什么这么少</h2>
 * 旧签名有 5–6 个参数，其中 {@code request} / {@code provider} / {@code downstreamHeaders} /
 * {@code requestId} / {@code model} <strong>全都已经是 ctx 的字段</strong>，
 * 故不再重复传递（判据同收编 {@code DownstreamLogView} 那次：同一个事实只有一个家）。
 * 剩下的 {@code chunkRewriter} 是<strong>闭包不是状态</strong> ——
 * 它由编排层按回程翻译器构造，只对 Anthropic 路线有意义，因此<strong>留在签名上</strong>、
 * 不进 ctx。
 *
 * <h2>未命中语义是「报错」，与其它插槽不同</h2>
 * 协议已被声明支持、却没有对应执行器 —— 那是<strong>装配坏了</strong>，
 * 不是「这种协议没有这一步」。因此主干查不到执行器时**必须报错**，
 * 不能像 chunk 归一那样静默跳过。通则见 {@link UpstreamExecutorRegistry} 的类注释。
 *
 * <h2>执行器只留这三个方法</h2>
 * 三个执行器旧有的公开方法（{@code chatCompletion} / {@code messages} / {@code responses}
 * 及其 Stream 版）已删除 —— 它们让主干按协议三选一，而那正是「主干上不该有协议分叉」
 * 要消除的东西。本接口的 {@code invoke} / {@code invokeStream} 是唯一的入口。
 */
public interface UpstreamExecutor {

    /** 本执行器服务的上游协议 —— 查表键。 */
    WireProtocol protocol();

    /**
     * 执行一次非流式调用。
     *
     * @param ctx           本次请求的管道上下文（含 body / model / provider / headers / requestId）
     * @param chunkRewriter 落库用的 chunk 改写器；直连传 {@code null}（不改写）。
     *                      它由编排层构造 —— 那里才知道回程翻译器是谁
     * @return 统一形态的上游响应（单个 {@link UpstreamEvent.Body}）
     */
    Mono<UpstreamEvent> invoke(RequestPipelineContext ctx,
                               Function<List<String>, ChunkLogPayload> chunkRewriter);

    /**
     * 执行一次流式调用。
     *
     * <p>上下文决定空响应拦截是否介入 —— 跨协议但回程翻译未实现时（半轮实现态）
     * 整轮放行，不判空、不重试，判据见 {@code RequestPipelineContext#shouldApplyEmptyResponseGate()}。
     *
     * @param ctx           本次请求的管道上下文
     * @param chunkRewriter 落库用的 chunk 改写器；直连传 {@code null}
     * @return 统一形态的上游事件流
     */
    Flux<UpstreamEvent> invokeStream(RequestPipelineContext ctx,
                                     Function<List<String>, ChunkLogPayload> chunkRewriter);
}

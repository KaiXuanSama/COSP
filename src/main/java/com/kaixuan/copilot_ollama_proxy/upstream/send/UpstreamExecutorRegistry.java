package com.kaixuan.copilot_ollama_proxy.upstream.send;

import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上游执行器的查表 —— 按 {@link WireProtocol} 查 {@link UpstreamExecutor} 实现。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：<strong>注册表</strong>（接入点级） · 位置：{@code upstream/send/}
 * 步骤「选执行器」—— 未命中即<strong>报错</strong>（装配坏了，不是领域事实）
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>它就是主干 {@code send} 插槽的落点</h2>
 * 主干上只有一行：
 * <pre>
 * executorRegistry.require(ctx.upstreamProtocol()).invoke(ctx, chunkRewriter)
 * </pre>
 * 主干因此<strong>不认识任何具体执行器</strong>，也不需要在三个实现里做协议分派
 * —— 那正是「主干上不该有协议分叉」这条本意的要求（方向文档 §2.2）。
 * 「加一个上游协议 = 加一个 {@code @Component}」，主干与三个应用服务一个字不动。
 *
 * <h2>键由实现<strong>声明</strong>，不靠类名猜</h2>
 * 与 {@code TranslatorRegistry} / {@code RequestBodyStageRegistry} / {@code ChunkStageRegistry}
 * 同一机制：收集容器里全部 {@link UpstreamExecutor}，按各实现的 {@link UpstreamExecutor#protocol()}
 * 建索引。同一协议两个实现 = 声明矛盾，**启动即失败**，不静默择一。
 *
 * <h2>未命中语义 = <strong>报错</strong>（与其它插槽不同）</h2>
 * 协议已被供应商声明支持、却没有对应执行器 —— 那是<strong>装配坏了</strong>，
 * 不是「这种协议没有这一步」。别的插槽（chunk 归一、system 抬升……）未命中是跳过，
 * 因为它们表达领域事实（「这种协议确实没这一步」）；本表未命中没有合法的领域解释。
 * 见 plan_ Step 3.4「未命中语义通则」。
 *
 * <p>用 {@link IllegalStateException} 而非某个业务异常：它不该被控制器译成 400，
 * 而应作为「程序接线错误」暴露出来。
 */
@Component
public class UpstreamExecutorRegistry {

    private final Map<WireProtocol, UpstreamExecutor> byProtocol;

    /**
     * 由 Spring 集合注入构造。
     *
     * @param executors 容器里全部执行器实现
     */
    public UpstreamExecutorRegistry(List<UpstreamExecutor> executors) {
        Map<WireProtocol, UpstreamExecutor> indexed = new HashMap<>();
        for (UpstreamExecutor executor : executors) {
            WireProtocol protocol = executor.protocol();
            UpstreamExecutor previous = indexed.put(protocol, executor);
            if (previous != null) {
                throw new IllegalStateException(
                        "协议 " + protocol + " 的上游执行器有两个实现："
                                + previous.getClass().getName() + " 与 " + executor.getClass().getName()
                                + "。每个协议只能有一个执行器");
            }
        }
        this.byProtocol = Map.copyOf(indexed);
    }

    /**
     * 取指定协议的执行器。
     *
     * @param protocol 本次调用的上游协议（来自调度结论）
     * @return 该协议的执行器
     * @throws IllegalStateException 没有执行器服务该协议 —— 装配错误，不是领域事实
     */
    public UpstreamExecutor require(WireProtocol protocol) {
        UpstreamExecutor executor = byProtocol.get(protocol);
        if (executor == null) {
            throw new IllegalStateException(
                    "没有执行器服务上游协议 " + protocol + " —— 协议被声明支持却没有实现，"
                            + "属装配错误。已注册的协议：" + byProtocol.keySet());
        }
        return executor;
    }
}

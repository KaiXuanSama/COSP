package com.kaixuan.copilot_ollama_proxy.pipeline.after.chunk;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.pipeline.after.chunk.fallback.ReasoningFallbackStage;
import com.kaixuan.copilot_ollama_proxy.pipeline.after.chunk.normalize.ChunkNormalizeStage;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 流式 chunk 支线的查表 —— 按 {@link WireProtocol} 查归一与 fallback 的实现。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：<strong>注册表</strong>（接入点级） · 位置：{@code upstream/chunk/}
 * 步骤「chunk 形态归一」与「reasoning fallback」的聚合查表；未命中即<strong>跳过</strong>
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>它替掉了什么</h2>
 * 这两个支线在 Stage 3.1 就已成形（接口 + {@code @Component}），但调用点当时是
 * <strong>「优先注入、回退静态工具」的双路形态</strong>，且注入时**硬编码 filter CHAT**：
 *
 * <pre>
 * // 3.1 的过渡形态（已删除）
 * this.chunkNormalizeStage = stages.stream()
 *         .filter(stage -&gt; stage.protocol() == WireProtocol.CHAT)   // ← 硬编码
 *         .findFirst().orElse(null);
 * ...
 * stage != null ? stage.normalize(...) : UpstreamChunkNormalizer.normalize(...)  // ← 两条等价路
 * </pre>
 *
 * 双路形态是「主干还没成形」时的权宜之计：那两条路按设计逐字等价
 * （实现只转调静态工具），于是<strong>装配断了行为完全不变</strong> ——
 * 只能靠结构断言兜。现在主干与 {@code ctx} 都在了，硬编码与双路都可以去掉：
 * 键变成请求级事实、未命中是「这种协议没这一步」。
 *
 * <h2>为何本类与 {@link RequestBodyStageRegistry} 分成两个</h2>
 * 两者服务不同阶段（一个是请求体准备、一个是响应帧处理），键也不同：
 * 本类按 <strong>{@code ctx.upstreamProtocol()}</strong> 查（处理的是上游原始 chunk），
 * 那个按 {@code ctx.bodyProtocol()} 查（读写的是 body 的字段形态）。
 * 合表会让「用哪个键」变成调用点的自由选择，而它们各有唯一正确答案。
 *
 * <h2>为何是 Bean 而非静态工具</h2>
 * 与 {@code TranslatorRegistry} / {@link RequestBodyStageRegistry} 同一判据：
 * 它<strong>持有</strong>集合注入建好的两张表，状态来自容器 —— 因此必须是 Bean。
 *
 * <h2>同一协议两个实现 = 配置错误，启动即失败</h2>
 * 同 {@code TranslatorRegistry}：声明矛盾早失败，比运行期随机选一个可预测得多。
 */
@Component
public class ChunkStageRegistry {

    private final Map<WireProtocol, ChunkNormalizeStage> normalizers;
    private final Map<WireProtocol, ReasoningFallbackStage> fallbacks;

    /**
     * 由 Spring 集合注入构造。
     *
     * @param normalizers 所有 chunk 归一支线实现
     * @param fallbacks   所有 reasoning fallback 支线实现
     */
    public ChunkStageRegistry(List<ChunkNormalizeStage> normalizers,
                              List<ReasoningFallbackStage> fallbacks) {
        this.normalizers = index(normalizers, ChunkNormalizeStage::protocol, "chunk 归一");
        this.fallbacks = index(fallbacks, ReasoningFallbackStage::protocol, "reasoning fallback");
    }

    private static <T> Map<WireProtocol, T> index(List<T> stages,
                                                  Function<T, WireProtocol> keyOf,
                                                  String stepName) {
        Map<WireProtocol, T> byProtocol = new HashMap<>();
        for (T stage : stages) {
            WireProtocol protocol = keyOf.apply(stage);
            T previous = byProtocol.put(protocol, stage);
            if (previous != null) {
                throw new IllegalStateException(
                        "协议 " + protocol + " 的" + stepName + "支线有两个实现："
                                + previous.getClass().getName() + " 与 " + stage.getClass().getName()
                                + "。每个步骤每个协议只能有一个实现");
            }
        }
        return Map.copyOf(byProtocol);
    }

    /**
     * 查 chunk 归一支线。
     *
     * <p><strong>键用上游协议</strong>：归一的输入是<strong>上游原始 chunk</strong>，
     * 因此「上游发来什么形态」才是判据。用 {@code bodyProtocol} 会在 C2M 下错 ——
     * 那时 body 是 Anthropic 形态，而归一处理的帧属于上游协议。
     *
     * @param upstreamProtocol 本次调用的上游协议
     * @return 命中则为该协议的实现；未命中为空（表示这种协议没有归一这一步）
     */
    public Optional<ChunkNormalizeStage> findNormalizer(WireProtocol upstreamProtocol) {
        return Optional.ofNullable(normalizers.get(upstreamProtocol));
    }

    /**
     * 查 reasoning fallback 支线。键与未命中语义同 {@link #findNormalizer}。
     *
     * @param upstreamProtocol 本次调用的上游协议
     * @return 命中则为该协议的实现；未命中为空（表示这种协议不需要该兜底）
     */
    public Optional<ReasoningFallbackStage> findFallback(WireProtocol upstreamProtocol) {
        return Optional.ofNullable(fallbacks.get(upstreamProtocol));
    }
}

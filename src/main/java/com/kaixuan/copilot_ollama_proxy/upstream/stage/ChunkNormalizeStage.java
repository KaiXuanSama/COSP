package com.kaixuan.copilot_ollama_proxy.upstream.stage;

import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 上游 chunk 的<strong>形态归一支线</strong> —— 主干上一个按协议查表的接入点。
 *
 * <h2>为何是接口而不是继续用静态工具</h2>
 * 主干化（阶段 3）要把「三个平行执行器」收敛成「一条主干 + 若干支线」，支线的载体是
 * <strong>类型</strong>而非静态方法（方向文档 §2.3：链接的是类型，不是类）。
 * 把归一做成接口，它才能像 {@code ProtocolTranslator} 那样被 Spring 集合注入按协议查表 ——
 * 「加一个协议的实现 = 加一个类」，主干一个字不动。
 *
 * <h2>查表已接线（3.3d-2）</h2>
 * 本接口在 3.1 成形、3.3d-2 完成接线，现在由
 * {@link ChunkStageRegistry} 按 {@code ctx.upstreamProtocol()} 运行时查表。
 *
 * <p><strong>键是「上游协议」而不是 {@code bodyProtocol}</strong>：本支线处理的输入是
 * <strong>上游原始 chunk</strong>，故「上游发来什么形态」才是判据。
 * 用 {@code bodyProtocol} 会在 C2M 下错（那时 body 已是 Anthropic 形态，
 * 而归一处理的帧属于上游协议）。
 *
 * <p>3.1 期间它是「直接注入并调用」的双路形态（注入或回退静态工具），
 * 3.3d-2 已收成单路 —— 那时两条路逐字等价，装配断了也不会响，
 * 因此改用「构造器注入 registry + 未命中即跳过」。
 *
 * <h2>「只有 Chat 一个实现」是预期，不是缺口</h2>
 * Chat 路径的上游 chunk 鱼龙混杂（各家字段名不一致），归一专为它做；
 * Anthropic / Responses 的事件结构由各自协议规定，不存在「同义字段名」问题，
 * 因此<strong>没有</strong>这一步。另两条协议查不到实现 →
 * <strong>未命中即跳过</strong>，这正确表达了「归一是 Chat 专属步骤」——
 * 定性为「跳过」比「不存在」更贴合它作为支线的意图。
 *
 * <h2>流级状态由调用方持有，作参数传入</h2>
 * {@code contentEmitted} / {@code reasoningBuffer} / {@code chunkId} 是<strong>跨帧累积</strong>
 * 的流级状态（重试一次就要重置），不属于本支线，也不该塞进请求级 context ——
 * 见方向文档 §2.4「两级状态要分清」。因此它们作为方法参数穿线传递。
 */
public interface ChunkNormalizeStage {

    /**
     * 本支线服务的协议 —— 查表键。
     *
     * <p>只有返回 {@link WireProtocol#CHAT} 的实现存在；其余协议查不到，主干在 3.3 接线后跳过。
     */
    WireProtocol protocol();

    /**
     * 把一帧上游 chunk 归一成 OpenAI 标准形态。
     *
     * <p>签名与原静态方法 {@code UpstreamChunkNormalizer.normalize} 逐字一致 ——
     * 本步只把「静态工具」变成「可查表的支线实现」，不改行为。
     *
     * @param chunkJson       原始 SSE data 的 JSON 字符串
     * @param contentEmitted  是否已输出过正文 content（跨帧累积，本方法会在见到正文时置位）
     * @param reasoningBuffer 累积 reasoning_content 的缓冲区（跨帧累积，本方法会追加）
     * @param chunkId         当前流的 chunk ID 引用（本方法会在见到真实 id 时更新）
     * @return 归一后的 chunk JSON 字符串；解析失败时原样返回入参
     */
    String normalize(String chunkJson, AtomicBoolean contentEmitted,
                     StringBuilder reasoningBuffer, AtomicReference<String> chunkId);
}

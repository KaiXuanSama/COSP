package com.kaixuan.copilot_ollama_proxy.pipeline.after.chunk.normalize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ChunkNormalizeStage} 的 <strong>Chat</strong> 实现 —— 唯一的实现。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>包装</strong>（CHAT） · 位置：{@code upstream/chunk/normalize/}
 * 步骤「chunk 形态归一」—— 当前唯一实现
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>为何委托给静态工具而非把逻辑搬进来</h2>
 * 归一的纯逻辑仍留在 {@link UpstreamChunkNormalizer}（含它的一整套单测），
 * 本类只承担<strong>支线的三件事</strong>：声明协议键、带 {@code @Component} 让 Spring 收集、
 * 把调用转给静态工具。这样 Stage 3.1（本类成形那一步）是纯粹的「形状改造」——
 * 逻辑一行未动、既有单测一行未改，只是让归一具备了被查表的资格。
 *
 * <p>{@code objectMapper} 由构造器注入：静态工具需要它做 JSON 解析，
 * 而本类作为 Bean 正好能从容器拿到那个全局实例。
 */
@Component
public class ChatChunkNormalizeStage implements ChunkNormalizeStage {

    private final ObjectMapper objectMapper;

    public ChatChunkNormalizeStage(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public WireProtocol protocol() {
        return WireProtocol.CHAT;
    }

    @Override
    public String normalize(String chunkJson, AtomicBoolean contentEmitted,
                            StringBuilder reasoningBuffer, AtomicReference<String> chunkId) {
        return UpstreamChunkNormalizer.normalize(objectMapper, chunkJson, contentEmitted, reasoningBuffer, chunkId);
    }
}

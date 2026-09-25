package com.kaixuan.copilot_ollama_proxy.upstream.chunk.fallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ReasoningFallbackStage} 的 <strong>Chat</strong> 实现 —— 唯一的实现。
 *
 * <p>与 {@link ChatChunkNormalizeStage} 同一形状：纯逻辑仍在静态工具
 * {@link ReasoningFallback}（含其单测），本类只声明协议键、带 {@code @Component}、转调。
 * Stage 3.1（本类成形那一步）只改形状不改行为。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>包装</strong>（CHAT） · 位置：{@code upstream/chunk/fallback/}
 * 步骤「reasoning fallback」—— 当前唯一实现
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 */
@Component
public class ChatReasoningFallbackStage implements ReasoningFallbackStage {

    private final ObjectMapper objectMapper;

    public ChatReasoningFallbackStage(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public WireProtocol protocol() {
        return WireProtocol.CHAT;
    }

    @Override
    public boolean shouldFallback(String normalizedChunk, AtomicBoolean contentEmitted,
                                  StringBuilder reasoningBuffer) {
        return ReasoningFallback.shouldFallback(objectMapper, normalizedChunk, contentEmitted, reasoningBuffer);
    }

    @Override
    public List<String> buildFallbackFrames(String chunkId, String model, String reasoningContent) {
        return ReasoningFallback.buildFallbackFrames(objectMapper, chunkId, model, reasoningContent);
    }
}

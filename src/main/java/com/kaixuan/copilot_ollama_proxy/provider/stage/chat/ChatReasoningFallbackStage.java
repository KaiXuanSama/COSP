package com.kaixuan.copilot_ollama_proxy.provider.stage.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ReasoningFallback;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ReasoningFallbackStage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ReasoningFallbackStage} 的 <strong>Chat</strong> 实现 —— 唯一的实现。
 *
 * <p>与 {@link ChatChunkNormalizeStage} 同一形状：纯逻辑仍在静态工具
 * {@link ReasoningFallback}（含其单测），本类只声明协议键、带 {@code @Component}、转调。
 * Stage 3.1 只改形状不改行为。
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

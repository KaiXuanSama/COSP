package com.kaixuan.copilot_ollama_proxy.upstream.requestbody.maxtokens;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link MaxTokensNormalizeStage} 的 <strong>MESSAGES</strong> 实现 —— 唯一的实现。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>包装</strong>（MESSAGES） · 位置：{@code upstream/requestbody/maxtokens/}
 * 步骤「max_tokens 补齐」—— 当前唯一实现
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>为何另两条协议没有实现</h2>
 * Chat 的 {@code max_tokens} 与 Responses 的 {@code max_output_tokens} 都是可选的，
 * 接上会给所有「下游没带」的调用凭空补一个上限 —— 而 Copilot 通常就是不带。
 * 因此那两条协议<strong>查不到实现 → 跳过本步</strong>，这是预期行为而非缺口。
 *
 * <p>为何委托给静态工具：见 {@link MessagesSystemPromptStage} 的同段说明。
 */
@Component
public class MessagesMaxTokensStage implements MaxTokensNormalizeStage {

    private final ObjectMapper objectMapper;

    public MessagesMaxTokensStage(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public WireProtocol protocol() {
        return WireProtocol.MESSAGES;
    }

    @Override
    public void apply(Map<String, Object> body, String resolvedModel,
                      ProviderRuntimeConfiguration provider) {
        MaxTokensNormalizer.ensureMaxTokens(body, resolvedModel, provider, objectMapper);
    }
}

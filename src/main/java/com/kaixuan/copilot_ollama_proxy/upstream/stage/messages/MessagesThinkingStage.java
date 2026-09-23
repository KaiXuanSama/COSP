package com.kaixuan.copilot_ollama_proxy.upstream.stage.messages;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.upstream.stage.AnthropicThinkingNormalizer;
import com.kaixuan.copilot_ollama_proxy.upstream.stage.ThinkingInjectStage;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link ThinkingInjectStage} 的 <strong>MESSAGES</strong> 实现 —— 当前唯一的实现。
 *
 * <h2>为何是一个支线而不是两个</h2>
 * Anthropic 侧有两个正交维度（深度 = {@code output_config.effort}、
 * 方式 = {@code thinking} 对象），但它们必须作为<strong>一个整体</strong>施加：
 * 深度先、方式后、且深度写了 {@code disabled} 时跳过方式，
 * 最后还要剥掉 {@code reasoning_effort} 兼容副本。
 * 拆成两个支线会让这套顺序约束变成跨支线的隐式契约，而它们本就是同一条协议里的同一件事。
 *
 * <h2>另两条协议为何没有实现（本步不搬）</h2>
 * Chat 与 Responses 的思考注入分别写 {@code reasoning_effort} 与 {@code reasoning.effort}，
 * 目前仍在各自执行器的 {@code applyReasoningEffort} 里。把它们也支线化是后续步骤的事 ——
 * 本步只做 Anthropic 侧，一次搬一个协议才能让「既有测试一行未改」可验证。
 *
 * <p>为何委托给静态工具：见 {@link MessagesSystemPromptStage} 的同段说明。
 */
@Component
public class MessagesThinkingStage implements ThinkingInjectStage {

    private final ObjectMapper objectMapper;

    public MessagesThinkingStage(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public WireProtocol protocol() {
        return WireProtocol.MESSAGES;
    }

    @Override
    public void apply(Map<String, Object> body, String resolvedModel,
                      ProviderRuntimeConfiguration provider) {
        AnthropicThinkingNormalizer.applyThinkingDimensions(body, resolvedModel, provider, objectMapper);
    }
}

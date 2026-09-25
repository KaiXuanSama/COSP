package com.kaixuan.copilot_ollama_proxy.upstream.requestbody.thinking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link ThinkingInjectStage} 的 <strong>MESSAGES</strong> 实现 —— 当前唯一的实现。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>包装</strong>（MESSAGES） · 位置：{@code upstream/requestbody/thinking/}
 * 步骤「思考注入」—— 当前唯一实现
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>为何是一个支线而不是两个</h2>
 * Anthropic 侧有两个正交维度（深度 = {@code output_config.effort}、
 * 方式 = {@code thinking} 对象），但它们必须作为<strong>一个整体</strong>施加：
 * 深度先、方式后、且深度写了 {@code disabled} 时跳过方式，
 * 最后还要剥掉 {@code reasoning_effort} 兼容副本。
 * 拆成两个支线会让这套顺序约束变成跨支线的隐式契约，而它们本就是同一条协议里的同一件事。
 *
 * <h2>另两条协议也有实现（阶段 4 刀 1 补齐）</h2>
 * Chat 与 Responses 的思考注入分别写 {@code reasoning_effort} 与 {@code reasoning.effort}，
 * 现各自有 {@link ChatThinkingStage} / {@code ResponsesThinkingStage}（三协议在
 * {@code RequestBodyStageRegistry} 的 thinking 表里各占一席）。
 * 它们不需要本类的「深度先方式后、off 档跳过方式」编排：那套约束只存在于
 * Anthropic 侧的两个正交维度之间（见下），单字段协议一行就够。
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

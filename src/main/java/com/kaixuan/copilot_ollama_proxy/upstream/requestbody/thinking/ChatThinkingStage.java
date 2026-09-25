package com.kaixuan.copilot_ollama_proxy.upstream.requestbody.thinking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ReasoningEffortSetting;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link ThinkingInjectStage} 的 <strong>CHAT</strong> 实现。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>包装</strong>（CHAT） · 位置：{@code upstream/requestbody/thinking/}
 * 步骤「思考注入」—— Chat 侧写 {@code reasoning_effort}（{@code off} 档另写 {@code thinking}）
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 *
 * <h2>它与 MESSAGES 侧「一个支线 vs 两维」的区别</h2>
 * Chat 侧<strong>只有一维</strong>（深度）：{@link ReasoningEffortSetting#applyTo} 已经成对操作
 * {@code reasoning_effort} 与 {@code thinking} 两个字段（{@code off} 档写
 * {@code thinking:{"type":"disabled"}}，其余档写 {@code reasoning_effort}）——
 * 那是<strong>同一维（深度）的两个出站字段</strong>，不是 Anthropic 那种「深度 + 方式」两维。
 * 因此这里不需要 {@code AnthropicThinkingNormalizer} 的「深度先方式后、off 档跳过方式」编排。
 *
 * <h2>逻辑与搬移前逐字等价</h2>
 * 本类是刀 1 从 {@code AbstractUpstreamChatService.applyReasoningEffort} 搬来的 ——
 * 那一行就是 {@code resolveReasoningEffort(...).applyTo(body)}，配置解析统一走
 * {@link ReasoningEffortSetting#forModel}（三条线路同一份）。
 */
@Component
public class ChatThinkingStage implements ThinkingInjectStage {

    private final ObjectMapper objectMapper;

    public ChatThinkingStage(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public WireProtocol protocol() {
        return WireProtocol.CHAT;
    }

    @Override
    public void apply(Map<String, Object> body, String resolvedModel,
                      ProviderRuntimeConfiguration provider) {
        ReasoningEffortSetting.forModel(resolvedModel, provider, objectMapper).applyTo(body);
    }
}

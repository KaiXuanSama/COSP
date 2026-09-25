package com.kaixuan.copilot_ollama_proxy.upstream.requestbody.thinking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ReasoningEffortSetting;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link ThinkingInjectStage} 的 <strong>RESPONSES</strong> 实现。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>包装</strong>（RESPONSES） · 位置：{@code upstream/requestbody/thinking/}
 * 步骤「思考注入」—— Responses 侧写嵌套的 {@code reasoning.effort}
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 *
 * <h2>与另两条协议的出站字段差异</h2>
 * 三种协议的思考深度出站形态各不相同，这正是它必须是支线（协议差异是代码）的理由：
 * <ul>
 *   <li>CHAT —— 顶层 {@code reasoning_effort}（{@code off} 档另写 {@code thinking}）；</li>
 *   <li>RESPONSES —— <strong>嵌套</strong> {@code reasoning.effort}（本类）；</li>
 *   <li>MESSAGES —— 顶层 {@code output_config.effort}，另有独立的 {@code thinking} 方式维度。</li>
 * </ul>
 * Responses 侧<strong>只有深度一维</strong>（无 Anthropic 那种方式维度），因此不需要
 * 「深度先方式后」的编排；由 {@link ReasoningEffortSetting#applyToResponses} 负责。
 *
 * <h2>逻辑与搬移前逐字等价</h2>
 * 本类是刀 1 从 {@code GenericResponsesChatService.applyReasoningEffort} 搬来的 ——
 * 那一行就是 {@code resolveReasoningEffort(...).applyToResponses(body)}，配置解析统一走
 * {@link ReasoningEffortSetting#forModel}（三条线路同一份）。
 */
@Component
public class ResponsesThinkingStage implements ThinkingInjectStage {

    private final ObjectMapper objectMapper;

    public ResponsesThinkingStage(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public WireProtocol protocol() {
        return WireProtocol.RESPONSES;
    }

    @Override
    public void apply(Map<String, Object> body, String resolvedModel,
                      ProviderRuntimeConfiguration provider) {
        ReasoningEffortSetting.forModel(resolvedModel, provider, objectMapper).applyToResponses(body);
    }
}

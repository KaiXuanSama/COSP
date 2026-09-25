package com.kaixuan.copilot_ollama_proxy.upstream.outbound.responses;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.upstream.outbound.OutboundRequestStage;
import org.springframework.stereotype.Component;

/**
 * RESPONSES 线路的出站装配支线实现（阶段 4 刀 3 B）。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>实现</strong>（RESPONSES） · 位置：{@code upstream/outbound/responses/}
 * 步骤「出站请求装配」—— 只回答「地址读哪一列」。
 *
 * <h2>它只做地址解析，没有协议必需头</h2>
 * Responses 线路读 {@code responses_base_url}（回退 base_url），且没有协议必需头，
 * 故 {@link #applyProtocolHeaders} 用接口默认的 no-op。
 *
 * <p>「留空回退 base_url」在这条线路上是<strong>常态而非例外</strong>：多数中转站根本没有
 * Responses 端点，那时请求打到不存在的路径得到 404 —— 而那正是让用户感知「这家不支持」的方式。
 * 与旧 {@code GenericResponsesChatService.normalizeResponsesBaseUrl} 语义一致（归一化交给 assembler）。
 */
@Component
public class ResponsesOutboundStage implements OutboundRequestStage {

    @Override
    public WireProtocol protocol() {
        return WireProtocol.RESPONSES;
    }

    @Override
    public String resolveBaseUrl(ProviderRuntimeConfiguration provider) {
        // 优先 responses_base_url，为空回退 base_url。归一化由 assembler 统一做。
        return provider.resolveResponsesBaseUrl();
    }
}

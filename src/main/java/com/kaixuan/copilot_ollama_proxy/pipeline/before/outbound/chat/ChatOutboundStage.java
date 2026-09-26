package com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.chat;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.OutboundRequestStage;
import org.springframework.stereotype.Component;

/**
 * CHAT 线路的出站装配支线实现。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>实现</strong>（CHAT） · 位置：{@code pipeline/before/outbound/chat/}
 * 步骤「出站请求装配」—— 只回答「地址读哪一列」。
 *
 * <h2>它只做地址解析，没有协议必需头</h2>
 * Chat 线路读 {@code base_url}（供应商的主地址），且没有 {@code anthropic-version} 那样的
 * 协议必需头，故 {@link #applyProtocolHeaders} 用接口默认的 no-op。
 *
 * <p>此前 OpenAI 执行器有一个 {@code defaultBaseUrl()} 抽象方法，{@code base_url} 为空时回退到它
 * （返回空串）。本实现保留同一行为：{@code base_url} 空白时返回空串，让 {@code normalizeBaseUrl}
 * 归一为空 —— 与旧 {@code buildWebClientWithHeaders} 的 {@code provider.baseUrl().isBlank() ? defaultBaseUrl() : ...}
 * 逐字节等价（OpenAI 侧 {@code defaultBaseUrl} 本就返回空串）。
 */
@Component
public class ChatOutboundStage implements OutboundRequestStage {

    @Override
    public WireProtocol protocol() {
        return WireProtocol.CHAT;
    }

    @Override
    public String resolveBaseUrl(ProviderRuntimeConfiguration provider) {
        // 与旧 OpenAI 执行器一致：base_url 为空时回退到「默认地址」，而 OpenAI 侧的
        // defaultBaseUrl() 返回空串，故这里直接给空串（normalizeBaseUrl 会归一）。
        String baseUrl = provider.baseUrl();
        return baseUrl == null || baseUrl.isBlank() ? "" : baseUrl;
    }
}

package com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.messages;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.OutboundRequestStage;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * MESSAGES（Anthropic）线路的出站装配支线实现。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>实现</strong>（MESSAGES） · 位置：{@code pipeline/before/outbound/messages/}
 * 步骤「出站请求装配」—— 回答「地址读哪一列」+「补 anthropic-version」。
 *
 * <h2>两件 Anthropic 特有的事</h2>
 * <ul>
 *   <li>{@link #resolveBaseUrl} 读 {@code anthropic_base_url}（回退 base_url）——
 *       中转站把 Anthropic 端点摆在哪里不可预测，独立成列让用户显式声明；</li>
 *   <li>{@link #applyProtocolHeaders} 补 {@code anthropic-version} 头 —— 官方 API 缺它返回 400。</li>
 * </ul>
 *
 * <p>{@code anthropic-version} 用 set-if-absent 补写：本方法在协议无关的三层头装配<strong>之后</strong>
 * 被调（见 {@code OutboundRequestAssembler.assemble}），故供应商请求头规则若已写了这个头，
 * 这里不覆盖 —— 与旧 {@code buildWebClient} 里「放在 applyHeaders 之后、containsKey 才 set」逐字节等价。
 */
@Component
public class MessagesOutboundStage implements OutboundRequestStage {

    /**
     * Anthropic API 版本头。这是<strong>必需</strong>的请求头，缺失时官方 API 返回 400。
     *
     * <p>值固定而非可配：它标识的是「本代理按哪一版协议构造请求」，属于代码事实而非用户偏好。
     * 升级协议版本必然伴随代码改动，那时一并改这里。
     *
     * <p>出站头装配上移到发送前块后，补版本头这件事归位到出站支线 ——
     * 它与地址解析同属「构造请求的固定部分」。
     */
    private static final String ANTHROPIC_VERSION_HEADER = "anthropic-version";
    private static final String ANTHROPIC_VERSION_VALUE = "2023-06-01";

    @Override
    public WireProtocol protocol() {
        return WireProtocol.MESSAGES;
    }

    @Override
    public String resolveBaseUrl(ProviderRuntimeConfiguration provider) {
        // 优先 anthropic_base_url，为空回退 base_url —— 完整保留用户配置的路径，不裁切 /v1。
        // 归一化（尾斜杠）由 assembler 统一做，故这里返回原始列值。
        return provider.resolveAnthropicBaseUrl();
    }

    @Override
    public void applyProtocolHeaders(HttpHeaders headers) {
        // set-if-absent：放在三层头装配之后，使供应商请求头规则仍可覆盖它。
        if (!headers.containsKey(ANTHROPIC_VERSION_HEADER)) {
            headers.set(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION_VALUE);
        }
    }
}

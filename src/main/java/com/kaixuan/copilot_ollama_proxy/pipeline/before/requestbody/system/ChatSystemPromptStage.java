package com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.system;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CHAT 的 system 归一化支线：承接 M2C 翻译保留的顶层 system 中间形态。
 *
 * <p>system 落点与字段翻译分开，沿用 MESSAGES/RESPONSES 的步骤边界；
 * {@code bodyProtocol=CHAT} 会让现有注册表自动命中，不在主干新增协议判断。
 * 标准 Chat 请求没有顶层 system，本步骤完整 no-op，不能改写既有历史。
 * 纯逻辑留在 {@link SystemPromptNormalizer}，本类只声明键并转调。
 *
 * <p>步骤「system 归一化」的位置见 {@code pipeline/README.md}；决策见 ADR-0012。
 */
@Component
public class ChatSystemPromptStage implements SystemPromptNormalizeStage {

    @Override
    public WireProtocol protocol() {
        return WireProtocol.CHAT;
    }

    @Override
    public void apply(Map<String, Object> body) {
        SystemPromptNormalizer.inlineSystemIntoMessages(body);
    }
}
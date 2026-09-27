package com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.system;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link SystemPromptNormalizeStage} 的 <strong>MESSAGES</strong> 实现 —— 唯一的实现。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>包装</strong>（MESSAGES） · 位置：{@code pipeline/before/requestbody/system/}
 * 步骤「system 抬升」—— 当前唯一实现
 * <p>完整步骤树见 {@code pipeline/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>为何委托给静态工具而非把逻辑搬进来</h2>
 * 与 {@code ChatChunkNormalizeStage} 同一取向：纯逻辑留在
 * {@link SystemPromptNormalizer}，本类只承担<strong>支线的三件事</strong> ——
 * 声明协议键、带 {@code @Component} 让 Spring 收集、把调用转给静态工具。
 *
 * <p>这样做的直接收益：**查表接线与执行器并行演进**，而在此之前执行器仍需工作。
 * 逻辑若只在本类里，执行器就得留一份自己的副本 —— 两份等价逻辑必然静默分叉。
 */
@Component
public class MessagesSystemPromptStage implements SystemPromptNormalizeStage {

    @Override
    public WireProtocol protocol() {
        return WireProtocol.MESSAGES;
    }

    @Override
    public void apply(Map<String, Object> body) {
        SystemPromptNormalizer.extractSystemPrompt(body);
    }
}

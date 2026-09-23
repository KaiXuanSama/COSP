package com.kaixuan.copilot_ollama_proxy.upstream.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.springframework.stereotype.Component;

/**
 * {@link ContentDetectorStage} 的 <strong>MESSAGES</strong> 实现 —— 转调 {@link AnthropicContentDetector}。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>包装</strong>（MESSAGES） · 位置：{@code upstream/content/}
 * 步骤「空响应判定」—— 转调 {@link AnthropicContentDetector}
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>方法映射（这两个名字是对得上的）</h2>
 * <table>
 *   <caption>映射关系</caption>
 *   <tr><th>本接口方法</th><th>转调</th><th>它实际读的字段</th></tr>
 *   <tr><td>{@link #hasMeaningfulPayload}（整轮 / 非流式）</td>
 *       <td>{@code hasMeaningfulPayload}</td><td>{@code content[]}</td></tr>
 *   <tr><td>{@link #eventHasPayload}（逐帧 / 流式）</td>
 *       <td>{@code eventHasPayload}</td><td>按事件类型分派</td></tr>
 * </table>
 *
 * <p>与 Chat 侧不同，本协议的两个方法名与语义一致，可以直接按名字接。
 * 那个「名字骗人」的坑只存在于 {@code OpenAiContentDetector}
 * （详见 {@code ChatContentDetector} 的类注释）。
 *
 * <p>为何委托给静态工具而非把逻辑搬进来：见 {@code ChatContentDetector} 的同段说明。
 */
@Component
public class MessagesContentDetectorStage implements ContentDetectorStage {

    private final ObjectMapper objectMapper;

    public MessagesContentDetectorStage(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public WireProtocol protocol() {
        return WireProtocol.MESSAGES;
    }

    /** 非流式：读 {@code content[]}。 */
    @Override
    public boolean hasMeaningfulPayload(String fullBody) {
        return AnthropicContentDetector.hasMeaningfulPayload(objectMapper, fullBody);
    }

    /** 流式：按事件类型分派。 */
    @Override
    public boolean eventHasPayload(String eventData) {
        return AnthropicContentDetector.eventHasPayload(objectMapper, eventData);
    }
}

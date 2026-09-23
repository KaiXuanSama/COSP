package com.kaixuan.copilot_ollama_proxy.upstream.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.springframework.stereotype.Component;

/**
 * {@link ContentDetectorStage} 的 <strong>RESPONSES</strong> 实现 —— 转调 {@link ResponsesContentDetector}。
 *
 * <h2>方法映射（这两个名字是对得上的）</h2>
 * <table>
 *   <caption>映射关系</caption>
 *   <tr><th>本接口方法</th><th>转调</th><th>它实际读的字段</th></tr>
 *   <tr><td>{@link #hasMeaningfulPayload}（整轮 / 非流式）</td>
 *       <td>{@code hasMeaningfulPayload}</td><td>{@code output[]}</td></tr>
 *   <tr><td>{@link #eventHasPayload}（逐帧 / 流式）</td>
 *       <td>{@code eventHasPayload}</td><td>按十余种事件类型分派</td></tr>
 * </table>
 *
 * <p>那个「名字骗人」的坑只存在于 {@code OpenAiContentDetector}
 * （详见 {@code ChatContentDetector} 的类注释）；本协议的方法名与语义一致。
 *
 * <p>为何委托给静态工具而非把逻辑搬进来：见 {@code ChatContentDetector} 的同段说明。
 */
@Component
public class ResponsesContentDetectorStage implements ContentDetectorStage {

    private final ObjectMapper objectMapper;

    public ResponsesContentDetectorStage(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public WireProtocol protocol() {
        return WireProtocol.RESPONSES;
    }

    /** 非流式：读 {@code output[]}。 */
    @Override
    public boolean hasMeaningfulPayload(String fullBody) {
        return ResponsesContentDetector.hasMeaningfulPayload(objectMapper, fullBody);
    }

    /** 流式：按事件类型分派。 */
    @Override
    public boolean eventHasPayload(String eventData) {
        return ResponsesContentDetector.eventHasPayload(objectMapper, eventData);
    }
}

package com.kaixuan.copilot_ollama_proxy.provider.stage.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.OpenAiContentDetector;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ContentDetectorStage;
import org.springframework.stereotype.Component;

/**
 * {@link ContentDetectorStage} 的 <strong>CHAT</strong> 实现 —— 转调 {@link OpenAiContentDetector}。
 *
 * <h2>⚠️ 这里的方法映射是「反的」，不要按名字接</h2>
 * 被转调的那个类是静态工具，它的两个方法名<strong>与语义对不上</strong>：
 *
 * <table>
 *   <caption>映射关系（按实际取值路径，不按名字）</caption>
 *   <tr><th>本接口方法</th><th>转调</th><th>它实际读的字段</th></tr>
 *   <tr><td>{@link #hasMeaningfulPayload}（整轮 / 非流式）</td>
 *       <td>{@code hasMeaningfulNonStreamPayload}</td><td>{@code choices[].message}</td></tr>
 *   <tr><td>{@link #eventHasPayload}（逐帧 / 流式）</td>
 *       <td><strong>{@code hasMeaningfulPayload}</strong></td><td>{@code choices[].delta}</td></tr>
 * </table>
 *
 * <p>也就是说：那个类里叫 {@code hasMeaningfulPayload} 的方法<strong>其实是流式用的</strong>
 * （名字没有 {@code NonStream} 后缀，但它读 {@code delta}），
 * 而另两条协议的<strong>同名方法</strong>读的是完整 body（非流式）。
 * 因此映射必须按字段而不是按名字 —— <strong>接错不会编译失败</strong>（签名相同），
 * 症状是「Chat 的空响应判定失效」或「非流式被逐帧逻辑误判」，两者都不会报错。
 *
 * <h2>为何委托给静态工具而非把逻辑搬进来</h2>
 * 与本项目其它支线同一取向（见 {@code ChatChunkNormalizeStage}）：纯逻辑留在原工具类
 * （含它的一整套既有单测），本类只承担支线的三件事 —— 声明协议键、带 {@code @Component}
 * 让 Spring 收集、把调用转过去。这样本步是纯粹的「形状改造」，逻辑一行未动。
 */
@Component
public class ChatContentDetectorStage implements ContentDetectorStage {

    private final ObjectMapper objectMapper;

    public ChatContentDetectorStage(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public WireProtocol protocol() {
        return WireProtocol.CHAT;
    }

    /** 非流式：读 {@code choices[].message}。 */
    @Override
    public boolean hasMeaningfulPayload(String fullBody) {
        return OpenAiContentDetector.hasMeaningfulNonStreamPayload(objectMapper, fullBody);
    }

    /** 流式：读 {@code choices[].delta} —— 注意被转调的方法名不含 NonStream。 */
    @Override
    public boolean eventHasPayload(String eventData) {
        return OpenAiContentDetector.hasMeaningfulPayload(objectMapper, eventData);
    }
}

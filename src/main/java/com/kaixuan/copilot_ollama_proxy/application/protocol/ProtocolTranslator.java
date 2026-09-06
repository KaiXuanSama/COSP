package com.kaixuan.copilot_ollama_proxy.application.protocol;

/**
 * 跨协议<strong>翻译组件</strong>的方向标识。
 *
 * <p>当下游协议与上游协议不一致时（如下游打 OpenAI 端点、供应商只有 Anthropic 端点），
 * 由翻译组件承担报文改写：请求体转成上游协议、响应/事件流转回下游协议。
 * 本接口只声明<strong>方向</strong>，不声明改写方法。
 *
 * <h2>为何不把改写方法收进接口</h2>
 * 四个方向的方法形状互不相同，硬统一只会得到一个谁都用不上的最小公倍数：
 * <ul>
 *   <li>请求侧返回 {@link TranslatedRequest}（{@code body} + {@link TranslationContext}），
 *       因为响应侧需要请求期上下文（下游要不要流式、思考是下游要求的还是设置层注入的）。</li>
 *   <li>非流式响应侧是 {@code Mono<String> -> Mono<String>}。</li>
 *   <li>流式响应侧<strong>不是一帧进一帧出</strong>：{@code content_block_start} 产 0 帧、
 *       {@code message_start} 产 1 帧、流结束收尾产多帧，因此必须按 {@code List} 建模，
 *       且需要一个跨事件的状态机（{@code A2OStreamState}）。</li>
 * </ul>
 * 而且一条链的去程与回程分属两个类（{@code OpenAiToAnthropicRequestTranslator} 与
 * {@code AnthropicToOpenAiResponseTranslator}），「一个翻译器 = 三个方法」这个假设
 * 本身就不成立。
 *
 * <h2>那本接口还剩什么用</h2>
 * 只有一个：让每个翻译器<strong>自证方向</strong>，使「这个类是给哪条链用的」写在类型里
 * 而不是靠类名约定。{@code ChatCompletionService} 目前按具体类型注入（去程与回程的接线
 * 方式不同，查表反而绕），因此这两个方法当前<strong>没有生产调用方</strong> ——
 * 这是已知且可接受的。一旦出现第三条链，注册表才有意义，届时再补
 * {@code supports(下游, 上游)} 的查表。
 *
 * <h2>实现现状</h2>
 * <ul>
 *   <li>O2A 请求（下游 OpenAI → 上游 Anthropic）：{@code OpenAiToAnthropicRequestTranslator}，
 *       已实测。</li>
 *   <li>A2O 响应（上游 Anthropic → 下游 OpenAI）：{@code AnthropicToOpenAiResponseTranslator}，
 *       已实测（流式 + 非流式 + 多轮工具链）。</li>
 *   <li>A2O 请求（下游 Anthropic → 上游 OpenAI）：未实现，
 *       {@code MessagesService} 抛 {@link ProtocolTranslationNotSupportedException}。</li>
 *   <li>O2A 响应（上游 OpenAI → 下游 Anthropic）：未实现，同上。</li>
 * </ul>
 *
 * <h2>两侧翻译都必须在重试边界之外</h2>
 * 空响应判定与落库用的是上游<strong>原生</strong>形态。若翻译发生在 {@code retryWhen}
 * 内侧，{@code AnthropicContentDetector} 看到的是合成出来的 OpenAI chunk，
 * 而它的取值路径是照 Anthropic 的 {@code content[]} 结构写的，会把每一轮都判成空
 * 并耗尽预算。响应侧契约第 12 节。
 *
 * @see <a href="file:../../../../../../../../docs/PROTOCOL_TRANSLATION_CONTRACT.md">
 *      协议翻译契约（请求侧）</a>
 * @see <a href="file:../../../../../../../../docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md">
 *      协议翻译契约（响应侧）</a>
 */
public interface ProtocolTranslator {

    // TODO(待实现) 剩余两个方向：A2O 请求（下游 Anthropic → 上游 OpenAI）
    //  与 O2A 响应（上游 OpenAI → 下游 Anthropic）。
    //  两者是同一条链的两半，缺一半那条路就不可用，因此不拆开计划 ——
    //  参照已落地那条链的排序原则「让已有的一半变成可用的整体」
    //  （响应侧契约第 0 节）。
    //  接入点在 MessagesService 的两处翻译分支；落地后翻译器仍要套在上游服务外侧。

    /** 本翻译器接受的下游协议。 */
    WireProtocol downstreamProtocol();

    /** 本翻译器对接的上游协议。 */
    WireProtocol upstreamProtocol();
}

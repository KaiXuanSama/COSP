package com.kaixuan.copilot_ollama_proxy.pipeline.protocol;

/**
 * 跨协议<strong>翻译组件</strong>的方向标识。
 *
 * <p>当下游协议与上游协议不一致时（如下游打 OpenAI 端点、供应商只有 Anthropic 端点），
 * 由翻译组件承担报文改写：请求体转成上游协议、响应/事件流转回下游协议。
 * 本接口只声明<strong>方向</strong>，不声明改写方法。
 *
 * <h2>为何本接口不收改写方法（改写方法在两个子接口里）</h2>
 * 四个方向的方法形状互不相同，全塞进<strong>一个</strong>接口只会得到一个谁都用不上的
 * 最小公倍数：
 * <ul>
 *   <li>请求侧返回 {@link TranslatedRequest}（{@code body} + {@link TranslationContext}），
 *       因为响应侧需要请求期上下文（下游要不要流式、思考是下游要求的还是设置层注入的）。</li>
 *   <li>非流式响应侧是 {@code Mono<String> -> Mono<String>}。</li>
 *   <li>流式响应侧<strong>不是一帧进一帧出</strong>：{@code content_block_start} 产 0 帧、
 *       {@code message_start} 产 1 帧、流结束收尾产多帧，因此必须按 {@code List} 建模，
 *       且需要一个跨事件的状态机（{@code M2CStreamState}）。</li>
 * </ul>
 * 更根本地，一条链的去程与回程<strong>分属两个类</strong>
 * （{@code ChatToMessagesRequestTranslator} 去程、{@code MessagesToChatResponseTranslator} 回程），
 * 「一个翻译器 = 三个方法」这个假设本身就不成立。
 *
 * <p>因此改写方法按<strong>方向</strong>拆进两个子接口 ——
 * {@code RequestProtocolTranslator}（去程，只有 {@code translateRequest}）与
 * {@code ResponseProtocolTranslator}（回程，非流式 / 流式 / 落库重译三个方法）。
 * 每个子接口只收<strong>一个方向</strong>的方法，形状对该方向的所有实现都一致，
 * 是「贴合一个方向的真实形状」，不是「跨方向的最小公倍数」。本接口留作两者的公共父类，
 * 只承载下面那对方向键。
 *
 * <h2>本接口承载什么：查表键</h2>
 * 两个键方法让每个翻译器<strong>自证方向</strong>，使「这个类是给哪条链用的」写在类型里
 * 而不是靠类名约定。{@code TranslatorRegistry} 靠 Spring 集合注入收集所有子接口实现，
 * 按这对键分别建<strong>去程表与回程表</strong>，{@code ChatCompletionService} 据此查表分派。
 * 「加一个方向的翻译器 = 加一个 {@code @Component}」，它自动出现在查表里。
 *
 * <p>本类早先的注释曾预言「等出现第三条链再补查表」。那个预言被一个<strong>更早的收益</strong>
 * 推翻了：查表让「回程未接」表现为<strong>回程表未命中</strong>，从而把
 * {@code PipelineExecution} 里那个「回程翻译已执行」的登记<strong>从手写状态位变成查表派生</strong> ——
 * 加一个 {@code @Component} 就自动接管，删掉就自动退回透传，不再有 bool 开关要记得维护
 * 。这个收益不依赖链条数量，因此查表在只有一条链时就值得建。
 *
 * <h2>实现现状</h2>
 * <ul>
 *   <li>C2M 请求（下游 Chat Completions → 上游 Anthropic）：{@code ChatToMessagesRequestTranslator}，
 *       已实测。</li>
 *   <li>M2C 响应（上游 Anthropic → 下游 Chat Completions）：{@code MessagesToChatResponseTranslator}，
 *       已实测（流式 + 非流式 + 多轮工具链）。</li>
 *   <li>M2C 请求（下游 Anthropic → 上游 OpenAI）：未实现，
 *       {@code MessagesService} 抛 {@link ProtocolTranslationNotSupportedException}。</li>
 *   <li>C2M 响应（上游 OpenAI → 下游 Anthropic）：未实现，同上。</li>
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

    // TODO(待实现) 剩余两个方向：M2C 请求（下游 Anthropic → 上游 OpenAI）
    //  与 C2M 响应（上游 OpenAI → 下游 Anthropic）。
    //  两者是同一条链的两半，缺一半那条路就不可用，因此不拆开计划 ——
    //  参照已落地那条链的排序原则「让已有的一半变成可用的整体」
    //  （响应侧契约第 0 节）。
    //  落地方式：新增两个类分别实现 RequestProtocolTranslator / ResponseProtocolTranslator、
    //  声明方向 (MESSAGES, CHAT)、带 @Component —— 它们会自动进 TranslatorRegistry 的查表。
    //  但 MessagesService 目前尚未改用查表（它对这两个方向仍无条件抛
    //  ProtocolTranslationNotSupportedException），要让这条链可用，需先把 MessagesService
    //  的翻译分支改成查 TranslatorRegistry（与 ChatCompletionService 同一手法）。
    //  翻译器仍要套在上游服务外侧。

    /** 本翻译器接受的下游协议。 */
    WireProtocol downstreamProtocol();

    /** 本翻译器对接的上游协议。 */
    WireProtocol upstreamProtocol();
}

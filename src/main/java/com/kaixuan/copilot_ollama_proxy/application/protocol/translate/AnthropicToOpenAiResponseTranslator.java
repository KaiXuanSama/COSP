package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslator;
import com.kaixuan.copilot_ollama_proxy.application.protocol.TranslationContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Anthropic Messages 响应 → OpenAI Chat Completions 响应的翻译器。
 *
 * <h2>职责边界</h2>
 * 本类只做<strong>报文改写</strong>。重试、落库、usage 落表、生命周期事件都留在
 * {@code GenericAnthropicChatService} 那一层，本类被套在它<strong>外侧</strong>。
 *
 * <h2>为何必须在重试边界之外</h2>
 * 空响应判定（{@code AnthropicContentDetector}）与落库用的都是<strong>上游原生形态</strong>：
 * 前者的取值路径是照 Anthropic 的 {@code content[]} 数组写的，
 * 后者的 {@code api_call_log} 记的是上游实际报文。
 * 若翻译发生在 {@code retryWhen} 内侧，判定器看到的是合成出来的 OpenAI chunk，
 * 会把每一轮都判成空并耗尽重试预算。
 *
 * <p>契约第 12 节。
 *
 * @see <a href="file:../../../../../../../../../docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md">
 *      响应侧协议翻译契约</a>
 */
@Component
public class AnthropicToOpenAiResponseTranslator implements ProtocolTranslator {

    private final AnthropicToOpenAiNonStreamTranslator nonStreamTranslator;
    private final AnthropicToOpenAiStreamTranslator streamTranslator;

    public AnthropicToOpenAiResponseTranslator(ObjectMapper objectMapper) {
        this.nonStreamTranslator = new AnthropicToOpenAiNonStreamTranslator(objectMapper);
        this.streamTranslator = new AnthropicToOpenAiStreamTranslator(objectMapper);
    }

    /**
     * 本翻译器服务的下游协议。
     *
     * <p>注意与请求侧翻译器方向相同：都是「下游说 OpenAI、上游说 Anthropic」这条链，
     * 请求侧负责去程、本类负责回程。
     */
    @Override
    public WireProtocol downstreamProtocol() {
        return WireProtocol.OPENAI;
    }

    @Override
    public WireProtocol upstreamProtocol() {
        return WireProtocol.ANTHROPIC;
    }

    /**
     * 翻译非流式响应。
     *
     * <p>模型名从上游响应里取，不需要调用方传入 ——
     * 与 OpenAI 直连路径一致（那条路径也不改写响应里的 model）。
     *
     * @param upstreamBody 上游原始响应体
     */
    public Mono<String> translateResponse(Mono<String> upstreamBody) {
        return upstreamBody.map(nonStreamTranslator::translate);
    }

    /**
     * 翻译流式响应。
     *
     * <h2>为何用 concatMapIterable 而非 map</h2>
     * 帧数不对等：一个上游事件可能产出零帧、一帧，而流结束时要产出多帧
     * （finish chunk + usage chunk + {@code [DONE]}）。
     *
     * <h2>状态在 defer 内创建</h2>
     * {@code Flux.defer} 保证每次订阅都拿到新状态。这一点对重试是必需的——
     * {@code retryWhen} 会重订阅，若状态跨轮复用，第二轮的 role 帧会缺失、
     * tool index 会从非零开始。
     *
     * @param upstreamEvents 上游 SSE data 流
     * @param upstreamModel  上游真实模型名（不含供应商前缀），作为 {@code message_start}
     *                       到达前的占位值
     * @param context        请求期上下文，提供 {@code include_usage}
     */
    public Flux<String> translateStream(Flux<String> upstreamEvents, String upstreamModel,
                                        TranslationContext context) {
        return Flux.defer(() -> {
            A2OStreamState state = new A2OStreamState(
                    placeholderId(), upstreamModel, context.includeUsage());
            return upstreamEvents
                    .concatMapIterable(event -> streamTranslator.translateEvent(event, state))
                    // 收尾必须在流正常结束后追加，而不是放在 doFinally ——
                    // 后者无法把新元素注入流中。
                    .concatWith(Flux.defer(() -> Flux.fromIterable(
                            streamTranslator.finalizeStream(state))));
        });
    }

    /**
     * 上游还没给出 message id 之前的占位 id。
     *
     * <p>用 {@code chatcmpl-} 前缀而非裸 UUID：万一上游始终不给 id，
     * 下游至少收到一个形态合法的 OpenAI 风格标识。
     * 上游给了就会被 {@link A2OStreamState#adoptUpstreamId} 替换成真实的 {@code msg_xxx}。
     */
    private static String placeholderId() {
        return "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    /**
     * 把一整轮上游事件批量翻译成下游 chunk，供<strong>落库</strong>使用。
     *
     * <h2>为何落库要单独走一遍翻译</h2>
     * 落库发生在上游服务内部（那里才有请求头、状态码、TTFB 与每轮起止时间），
     * 而翻译套在服务外侧。若不重译一遍，日志记的就是上游的 Anthropic 事件，
     * 而下游实际收到的是 OpenAI chunk —— 排查「客户端为什么解析失败」时，
     * 日志里没有客户端真正看到的东西。
     *
     * <h2>为何用独立状态而非复用出站那份</h2>
     * 出站状态在 {@code Flux.defer} 内、随订阅生命周期走；落库可能发生在
     * 出站流已经结束之后（{@code doFinally}），那时复用同一份状态会因为
     * {@code finalized} 已置位而产出不完整的帧序列。用新状态重放一遍是幂等的。
     *
     * @param upstreamEvents 该轮完整的上游事件列表
     * @param upstreamModel  上游模型名，作为 {@code message_start} 到达前的占位值
     * @param includeUsage   与出站保持一致，否则日志里的帧数与实际下发不符
     * @return 翻译结果与逐事件产帧数，后者供日志页做两栏对齐
     */
    public TranslatedChunkLog translateChunksForLog(List<String> upstreamEvents, String upstreamModel,
                                                    boolean includeUsage) {
        A2OStreamState state = new A2OStreamState(placeholderId(), upstreamModel, includeUsage);
        List<String> translated = new ArrayList<>();
        List<Integer> frameCounts = new ArrayList<>(upstreamEvents.size());
        for (String event : upstreamEvents) {
            List<String> frames = streamTranslator.translateEvent(event, state);
            // 逐事件记下产帧数。这是两栏对齐的唯一依据 ——
            // 事后从两个数组反推不出「第 5 帧来自第 9 个事件」。
            frameCounts.add(frames.size());
            translated.addAll(frames);
        }
        // 收尾帧不计入 frameCounts：它们由流结束触发，不属于任何上游事件。
        translated.addAll(streamTranslator.finalizeStream(state));
        return new TranslatedChunkLog(translated, frameCounts);
    }
}

package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.TranslationContext;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEventClassifier;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTokens;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI Responses 响应 → OpenAI Chat Completions 响应的翻译器（R2C 回程）。
 *
 * <h2>在链条中的位置</h2>
 * 与 {@code MessagesToChatResponseTranslator}（M2C）同位：套在上游执行器
 * <strong>外侧</strong>、{@code retryWhen} 之外 —— 空响应判定与落库用的都是
 * 上游原生形态，翻译在内侧会让判定器看到合成 chunk 而误判（响应侧契约第 12 节）。
 *
 * <h2>注册即接通</h2>
 * 带 {@code @Component} 后 {@code TranslatorRegistry} 的 {@code (CHAT, RESPONSES)}
 * 回程位即命中 —— C2R 线路端到端接通，{@code AfterSend.warnIfHalfRound} 的
 * 半轮告警随之消失。阶段一曾刻意不注册（当时流式未实现，注册会让下游拿到
 * 只会透传的假回程）；阶段二连同 {@code R2CStreamState} 与流式翻译器一起补上。
 *
 * <h2>流式：帧数不对等 + 状态在 defer 内</h2>
 * {@code concatMapIterable} 而非 {@code map}（一个事件可能产 0/1/多帧）；
 * {@code Flux.defer} 保证每次订阅新状态 —— {@code retryWhen} 重订阅时若状态
 * 跨轮复用，第二轮会缺 role 帧、tool index 从非零开始。
 *
 * <h2>usage 双通道（与 M2C 同构）</h2>
 * {@link UpstreamEvent#usage()} 槽位由执行器填（记账口径），本类<strong>只传递
 * 不重算</strong>（{@code carriedUsage} 模式）；出站给下游看的 usage chunk 由
 * 状态机自算（{@code R2CStreamState.lastUsageRaw} → 共享转换器）——
 * 「记账用什么」与「给下游看什么」是两件事。
 *
 * <h2>职责边界</h2>
 * 本类只做报文改写。重试、落库、usage 记账、生命周期事件都在
 * {@code GenericResponsesChatService} 那一层。
 *
 * @see <a href="file:../../../../../../../../../docs/features/protocol-translation/chat-responses/R2C-PLAN.md">
 *      R2C 回程翻译实施计划</a>
 */
@Component
class ResponsesToChatResponseTranslator implements ResponseProtocolTranslator {

    private final ObjectMapper objectMapper;
    private final ResponsesToChatNonStreamTranslator nonStreamTranslator;
    private final ResponsesToChatStreamTranslator streamTranslator;

    public ResponsesToChatResponseTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.nonStreamTranslator = new ResponsesToChatNonStreamTranslator(objectMapper);
        this.streamTranslator = new ResponsesToChatStreamTranslator(objectMapper);
    }

    /**
     * 本翻译器服务的链：下游说 Chat、上游说 Responses。
     * 与请求侧 {@code ChatToResponsesRequestTranslator}（C2R 去程）声明同一条链。
     */
    @Override
    public WireProtocol downstreamProtocol() {
        return WireProtocol.CHAT;
    }

    @Override
    public WireProtocol upstreamProtocol() {
        return WireProtocol.RESPONSES;
    }

    /**
     * 翻译非流式响应。
     *
     * <p>入出都是统一形态：收 {@link UpstreamEvent} 取 {@code data()}，
     * 翻译后包回 {@code UpstreamEvent.body}，usage 槽位原样传递（只传不重算，
     * 与 M2C 同判 —— 换算已在执行器层做过）。
     */
    @Override
    public Mono<UpstreamEvent> translateResponse(Mono<UpstreamEvent> upstreamBody) {
        return upstreamBody
                .map(upstream -> UpstreamEvent.body(
                        nonStreamTranslator.translate(upstream.data()), upstream.usage()));
    }

    /**
     * 翻译流式响应（M2C 门面的同构实现）。
     *
     * <p>收尾由 {@code concatWith(defer)} 追加而非 {@code doFinally}——
     * 后者无法把新元素注入流中（finish 兜底 / usage chunk / {@code [DONE]}）。
     * 出站帧经 {@link UpstreamEventClassifier} 按 CHAT 重新分类
     * （{@code [DONE]} 由此成为 Terminal，控制器据此收尾）——谁生产帧，谁分类。
     */
    @Override
    public Flux<UpstreamEvent> translateStream(Flux<UpstreamEvent> upstreamEvents, String upstreamModel,
                                               TranslationContext context) {
        return Flux.defer(() -> {
            R2CStreamState state = new R2CStreamState(
                    placeholderId(), upstreamModel, context.includeUsage());
            WireProtocol outputProtocol = downstreamProtocol();
            // usage 槽位只传递不重算（见类注释「usage 双通道」）。
            AtomicReference<UsageTokens> carriedUsage = new AtomicReference<>(null);
            return upstreamEvents
                    .doOnNext(event -> {
                        if (event.usage() != null) {
                            carriedUsage.set(event.usage());
                        }
                    })
                    .map(UpstreamEvent::data)
                    .concatMapIterable(event -> streamTranslator.translateEvent(event, state))
                    .concatWith(Flux.defer(() -> Flux.fromIterable(
                            streamTranslator.finalizeStream(state))))
                    .map(frame -> UpstreamEventClassifier.classify(objectMapper, outputProtocol, frame)
                            .withUsage(carriedUsage.get()));
        });
    }

    /**
     * 把一整轮上游事件批量翻译成下游 chunk，供<strong>落库</strong>使用。
     *
     * <p>与出站共用同一 {@code translateEvent}/{@code finalizeStream}、独立状态
     * 重放一遍（M2C 同构）：落库可能发生在出站流结束后（{@code doFinally}），
     * 复用出站状态会因 {@code finalized} 已置位而产出不完整帧序列。
     */
    @Override
    public TranslatedChunkLog translateChunksForLog(List<String> upstreamEvents, String upstreamModel,
                                                    boolean includeUsage) {
        R2CStreamState state = new R2CStreamState(placeholderId(), upstreamModel, includeUsage);
        List<String> translated = new ArrayList<>();
        List<Integer> frameCounts = new ArrayList<>(upstreamEvents.size());
        for (String event : upstreamEvents) {
            List<String> frames = streamTranslator.translateEvent(event, state);
            // 逐事件记下产帧数——两栏对齐的唯一依据。
            frameCounts.add(frames.size());
            translated.addAll(frames);
        }
        // 收尾帧不计入 frameCounts：它们由流结束触发，不属于任何上游事件。
        translated.addAll(streamTranslator.finalizeStream(state));
        return new TranslatedChunkLog(translated, frameCounts);
    }

    /**
     * 上游还没给出 response id 之前的占位 id。
     *
     * <p>{@code chatcmpl-} 前缀而非裸 UUID：上游始终不给 id 时，下游至少收到一个
     * 形态合法的 OpenAI 风格标识（M2C 同判）。
     */
    private static String placeholderId() {
        return "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}

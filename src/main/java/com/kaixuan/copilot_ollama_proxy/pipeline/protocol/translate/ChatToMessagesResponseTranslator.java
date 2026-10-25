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
import java.util.concurrent.atomic.AtomicReference;

/**
 * M2C 链路的 C2M 响应回程（上游 Chat → 下游 Messages）。
 *
 * <p>注册键为 (MESSAGES, CHAT)，与 MessagesToChatResponseTranslator 恰好相反。
 * 本 Bean 仅改写报文，套在上游执行器/重试之外，不接管判空、往返落库或重试预算。
 * 记账槽原样携带，Messages JSON 内的展示 usage 由独立转换器生成（ADR-0015）。
 */
@Component
public class ChatToMessagesResponseTranslator implements ResponseProtocolTranslator {

    private final ObjectMapper objectMapper;
    private final ChatToMessagesNonStreamTranslator nonStreamTranslator;
    private final ChatToMessagesStreamTranslator streamTranslator;

    public ChatToMessagesResponseTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.nonStreamTranslator = new ChatToMessagesNonStreamTranslator(objectMapper);
        this.streamTranslator = new ChatToMessagesStreamTranslator(objectMapper);
    }

    @Override
    public WireProtocol downstreamProtocol() {
        return WireProtocol.MESSAGES;
    }

    @Override
    public WireProtocol upstreamProtocol() {
        return WireProtocol.CHAT;
    }

    @Override
    public Mono<UpstreamEvent> translateResponse(Mono<UpstreamEvent> upstreamBody) {
        return upstreamBody.map(upstream -> UpstreamEvent.body(
                nonStreamTranslator.translate(upstream.data()), upstream.usage()));
    }

    @Override
    public Flux<UpstreamEvent> translateStream(Flux<UpstreamEvent> upstreamEvents, String upstreamModel,
                                               TranslationContext context) {
        return Flux.defer(() -> {
            C2MStreamState state = new C2MStreamState(ChatToMessagesNonStreamTranslator.placeholderId(), upstreamModel);
            AtomicReference<UsageTokens> carriedUsage = new AtomicReference<>();
            return upstreamEvents.concatMapIterable(upstream -> {
                        UsageTokens incoming = upstream.usage();
                        if (incoming != null && !incoming.isEmpty()) {
                            carriedUsage.set(incoming);
                        }
                        // 在本事件内取槽快照，避免 prefetch 时把后续事件的指标挂到较早的帧上。
                        UsageTokens snapshot = carriedUsage.get();
                        return streamTranslator.translateEvent(upstream.data(), state).stream()
                                .map(data -> classify(data, snapshot)).toList();
                    })
                    // 只在正常完成后补 EOF 收尾；error/cancel 不伪造成功 message_stop。
                    .concatWith(Flux.defer(() -> Flux.fromIterable(streamTranslator.finalizeStream(state))
                            .map(data -> classify(data, carriedUsage.get()))));
        });
    }

    private UpstreamEvent classify(String data, UsageTokens usage) {
        return UpstreamEventClassifier.classify(objectMapper, WireProtocol.MESSAGES, data).withUsage(usage);
    }

    @Override
    public TranslatedChunkLog translateChunksForLog(List<String> upstreamEvents, String upstreamModel,
                                                    boolean includeUsage) {
        C2MStreamState state = new C2MStreamState(ChatToMessagesNonStreamTranslator.placeholderId(), upstreamModel);
        List<String> translated = new ArrayList<>();
        List<Integer> frameCounts = new ArrayList<>(upstreamEvents.size());
        for (String event : upstreamEvents) {
            List<String> frames = streamTranslator.translateEvent(event, state);
            frameCounts.add(frames.size());
            translated.addAll(frames);
        }
        // EOF 补帧归收尾段；由 [DONE]/usage 尾帧触发的输出已计入相应事件，收尾幂等。
        translated.addAll(streamTranslator.finalizeStream(state));
        // Messages 原生带 usage；includeUsage 是下游 CHAT 选项，不控制本方向。
        return new TranslatedChunkLog(translated, frameCounts);
    }
}
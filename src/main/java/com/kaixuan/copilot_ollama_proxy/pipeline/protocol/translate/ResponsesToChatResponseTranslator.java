package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ResponseTranslationException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.TranslationContext;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * OpenAI Responses 响应 → OpenAI Chat Completions 响应的翻译器（R2C 回程）。
 *
 * <h2>在链条中的位置</h2>
 * 与 {@code MessagesToChatResponseTranslator}（M2C）同位：套在上游执行器
 * <strong>外侧</strong>、{@code retryWhen} 之外 —— 空响应判定与落库用的都是
 * 上游原生形态，翻译在内侧会让判定器看到合成 chunk 而误判（响应侧契约第 12 节）。
 *
 * <h2>阶段一状态：只实现非流式，<strong>刻意未注册</strong></h2>
 * 本类<strong>不带 {@code @Component}</strong>（R2C-PLAN §4 阶段一的既定决策）：
 * 注册即意味着 {@code TranslatorRegistry} 回程表命中 {@code (CHAT, RESPONSES)}，
 * 流式路径也会走到这里 —— 而流式翻译（阶段二）尚未实现，那会让下游拿到一个
 * 「只会透传的假回程」，比当前的半轮告警更糟（静默而非留痕）。
 * 阶段二连同 {@code R2CStreamState} + 流式翻译器一起补上注解与实现。
 *
 * <h2>职责边界</h2>
 * 本类只做报文改写。重试、落库、usage 记账、生命周期事件都在
 * {@code GenericResponsesChatService} 那一层。
 *
 * @see <a href="file:../../../../../../../../../docs/features/protocol-translation/chat-responses/R2C-PLAN.md">
 *      R2C 回程翻译实施计划</a>
 */
class ResponsesToChatResponseTranslator implements ResponseProtocolTranslator {

    private final ObjectMapper objectMapper;
    private final ResponsesToChatNonStreamTranslator nonStreamTranslator;

    ResponsesToChatResponseTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.nonStreamTranslator = new ResponsesToChatNonStreamTranslator(objectMapper);
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
     * 流式翻译 —— <strong>阶段二实现</strong>。
     *
     * <p>本方法当前不可达：本类未注册进 {@code TranslatorRegistry}，
     * 编排层不会查到它。抛出而非返回透传，是让「未注册却被调用」这种
     * 接线错误在第一时间暴露，而不是静默地把 Responses 原文当 Chat chunk 下发。
     */
    @Override
    public Flux<UpstreamEvent> translateStream(Flux<UpstreamEvent> upstreamEvents, String upstreamModel,
                                               TranslationContext context) {
        throw new UnsupportedOperationException("R2C 流式翻译属阶段二（R2C-PLAN §4），本类当前未注册、不可达");
    }

    /**
     * 落库批量重译 —— <strong>阶段二实现</strong>（与流式共用同一状态机）。
     *
     * @see #translateStream 同一不可达理由
     */
    @Override
    public TranslatedChunkLog translateChunksForLog(List<String> upstreamEvents, String upstreamModel,
                                                    boolean includeUsage) {
        throw new UnsupportedOperationException("R2C 落库重译属阶段二（R2C-PLAN §4），本类当前未注册、不可达");
    }

    /**
     * 预留给阶段二：Spring 装配用的工厂构造器。
     *
     * <p>阶段二把本类补上 {@code @Component} 时删除此方法与类注释中的
     * 「未注册」说明 —— 保留它让阶段二的 diff 恰好是一行注解的增删。
     */
    static ResponsesToChatResponseTranslator forSpring(ObjectMapper objectMapper) {
        return new ResponsesToChatResponseTranslator(objectMapper);
    }
}

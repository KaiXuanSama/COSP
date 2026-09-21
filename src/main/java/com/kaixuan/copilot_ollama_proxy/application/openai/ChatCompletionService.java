package com.kaixuan.copilot_ollama_proxy.application.openai;

import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.PipelineStep;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.NoSupportedProtocolException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchDecision;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchManager;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.TranslatedRequest;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.RequestProtocolTranslator;
import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.ResponseProtocolTranslator;
import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.TranslatorRegistry;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.application.runtime.UnresolvedModelRouteException;
import com.kaixuan.copilot_ollama_proxy.application.shared.ProtocolNotifier;
import com.kaixuan.copilot_ollama_proxy.provider.ChunkLogPayload;
import com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic.GenericAnthropicChatService;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.GenericOpenAiChatService;
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 聊天补全应用服务 —— 服务下游的 OpenAI 协议端点。
 *
 * 负责解析供应商模型路由，经协议调度管理器确认走法后委托上游执行器完成调用。
 *
 * <p>本服务的下游协议恒为 {@link WireProtocol#CHAT}（由它服务的端点决定）；
 * 上游协议由 {@link ProtocolDispatchManager} 按供应商支持情况得出。两条路都活：
 * 供应商支持 OpenAI 时直连，只支持 Anthropic 时走 C2M 去程 + M2C 回程翻译。
 *
 * <h2>为何两个方法体都裹在 defer 里</h2>
 * 路由解析、协议调度与请求翻译都是<strong>同步</strong>调用，且都会抛异常
 * （{@link NoSupportedProtocolException}、{@link RequestTranslationException}）。
 * 控制器那侧的 {@code Mono.firstWithSignal(chatCompletion(...), cancelSignal)}
 * 参数是 eager 求值的：若不包 defer，异常在 Mono <strong>组装期</strong>就抛出了
 * 控制器方法，{@code onErrorResume} 根本不在链上 —— 下游拿到的是 WebFlux 默认
 * 500 与通用错误体，那些带字段路径的消息一个字都到不了对端。
 * 流式同理，且更隐蔽：状态码还未提交，因此发出去的不是 SSE error 帧而是 500 JSON。
 */
@Service
public class ChatCompletionService {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionService.class);

    /** 本服务服务的下游端点协议，固定不变。 */
    private static final WireProtocol DOWNSTREAM_PROTOCOL = WireProtocol.CHAT;

    private final ProviderRouteResolver providerRouteResolver;
    private final ProtocolDispatchManager protocolDispatchManager;
    private final GenericOpenAiChatService genericOpenAiChatService;
    private final GenericAnthropicChatService genericAnthropicChatService;
    private final TranslatorRegistry translatorRegistry;

    /**
     * 调用生命周期事件通知器，由 Spring 可选注入。
     *
     * <p>用途单一：调度结论出来后把协议信息补给生命周期事件，供前端 Toast 显示路径标记。
     * 可选注入（与 provider 层同一范式）—— 单元测试直接 new 本类时不关心这条链路，
     * 缺省即不发。
     */
    private CallLifecycleNotifier lifecycleNotifier;

    /**
     * 创建聊天补全应用服务。
     *
     * @param providerRouteResolver 供应商模型路由解析器
     * @param protocolDispatchManager 协议调度管理器
     * @param genericOpenAiChatService OpenAI 上游执行器
     * @param genericAnthropicChatService Anthropic 上游执行器
     * @param translatorRegistry 翻译器查表（去程 / 回程各自按方向命中）
     */
    public ChatCompletionService(ProviderRouteResolver providerRouteResolver,
                                 ProtocolDispatchManager protocolDispatchManager,
                                 GenericOpenAiChatService genericOpenAiChatService,
                                 GenericAnthropicChatService genericAnthropicChatService,
                                 TranslatorRegistry translatorRegistry) {
        this.providerRouteResolver = providerRouteResolver;
        this.protocolDispatchManager = protocolDispatchManager;
        this.genericOpenAiChatService = genericOpenAiChatService;
        this.genericAnthropicChatService = genericAnthropicChatService;
        this.translatorRegistry = translatorRegistry;
    }

    @Autowired(required = false)
    public void setLifecycleNotifier(CallLifecycleNotifier lifecycleNotifier) {
        this.lifecycleNotifier = lifecycleNotifier;
    }

    /**
     * 把调度结论补进生命周期事件，供前端 Toast 渲染路径标记（如「O→A」）。
     *
     * <p>实现已收归 {@link ProtocolNotifier}（与另两个 Service 共用）——
     * 本方法只负责把本类持有的下游协议常量与 logger 绑给它。
     * 完整理由（为何必须在 dispatch 之后且早于网络等待、为何失败只记 debug
     * 从不中断调用）见那个类的注释。
     */
    private void notifyProtocols(String requestId, ProtocolDispatchDecision decision) {
        ProtocolNotifier.notifyProtocols(log, lifecycleNotifier, requestId,
                DOWNSTREAM_PROTOCOL, decision);
    }

    /**
     * 执行非流式聊天补全。
     *
     * @param openAiRequest OpenAI 格式请求体
     * @param model 模型名称
     * @return 上游原始 OpenAI 响应
     */
    public Mono<UpstreamEvent> chatCompletion(Map<String, Object> openAiRequest, String model,
                                              HttpHeaders downstreamHeaders, String requestId) {        // defer 把路由 / 调度 / 翻译的同步异常转成 onError 信号，控制器才能分类处置。
        // 理由见类注释。
        return Mono.defer(() ->
                dispatchChatCompletion(openAiRequest, model, downstreamHeaders, requestId));
    }

    private Mono<UpstreamEvent> dispatchChatCompletion(Map<String, Object> openAiRequest, String model,
                                                       HttpHeaders downstreamHeaders, String requestId) {        ResolvedProviderRoute route = providerRouteResolver.resolve(model);
        if (route == null) {
            // 类型化异常而非裸 RuntimeException：路由在本地目录就没解析出来，
            // 上游从未被连接，控制器据此回 400 而不是「无法连接到上游服务」502。
            return Mono.error(new UnresolvedModelRouteException(model));
        }
        ProtocolDispatchDecision decision =
                protocolDispatchManager.dispatch(DOWNSTREAM_PROTOCOL, route.provider());
        // 调度结论出来了：把下游/上游协议补进生命周期事件，前端 Toast 才能显示路径标记。
        notifyProtocols(requestId, decision);
        
        // 同协议直连，原请求体不变。
        if (!decision.translationNeeded()) {
            // 直连：两侧同协议。上下文在此处显式构建 —— 与翻译路线同一形状，
            // 因为执行器只有一个入口（3.3b-2 已退役不带 ctx 的旧重载）。
            RequestPipelineContext ctx = RequestPipelineContext.direct(openAiRequest, DOWNSTREAM_PROTOCOL,
                    route.provider(), downstreamHeaders, requestId, null);
            return genericOpenAiChatService.chatCompletion(openAiRequest, route, downstreamHeaders, requestId, ctx);
        }
        
        // 跨协议翻译：去程与回程各自查表，两半独立缺省（方向文档 §2.3.2）。
        //
        // 翻译套在上游服务外侧，因而天然在 retryWhen 之外 ——
        // 空响应判定（AnthropicContentDetector）与 api_call_log 落库用的都是
        // 上游原生形态，若翻译进了重试内侧，判定器会把每一轮都当成空响应。
        // 见 docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md 第 12 节。
        //
        // 模型名传下游原始的 model（含 [provider-key] 前缀）而非上游返回的裸名：
        // 本服务按前缀路由，把裸名透给下游会让它下一轮路由失败（第 7 节）。
        WireProtocol upstreamProtocol = decision.upstreamProtocol();

        // 去程未命中即「连请求翻译都没有」—— 报错。它与「回程未命中」不同：去程没有就
        // 发不出上游能理解的请求，跳过只会让上游回 400，与「配置写错」现象相同、无信息量
        // （§2.3.2）。跳过只在「去程已改写成功」时才有价值。
        RequestProtocolTranslator requestTranslator = translatorRegistry
                .findRequestTranslator(DOWNSTREAM_PROTOCOL, upstreamProtocol).orElse(null);
        if (requestTranslator == null) {
            return Mono.error(new ProtocolTranslationNotSupportedException(
                    route.provider().providerKey(), decision.downstreamProtocol(), upstreamProtocol));
        }
        TranslatedRequest translated = requestTranslator.translateRequest(openAiRequest);
        ResponseProtocolTranslator responseTranslator = translatorRegistry
                .findResponseTranslator(DOWNSTREAM_PROTOCOL, upstreamProtocol).orElse(null);

        // 组装期建上下文：此刻路由与调度结论都已得出，正是本类所需的全部事实。
        // 登记：去程恒已执行；回程仅在命中时登记。空响应拦截据此决定介入还是跳过 ——
        // 回程未命中（半轮实现态）时跳过，下游拿到的是上游原生帧，不该被重试压住。
        // 漏登记回程会让全实现的线路静默失去空响应兜底（拦截被误当成半轮态而跳过）。
        RequestPipelineContext ctx = translatedContext(translated, upstreamProtocol, route,
                downstreamHeaders, requestId);
        ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);
        if (responseTranslator != null) {
            ctx.markCompleted(PipelineStep.RESPONSE_TRANSLATION);
        }

        // 上游服务仍按协议选：服务合一是 Step 3.4 的事，本步只把翻译器改成查表。
        if (upstreamProtocol == WireProtocol.MESSAGES) {
            // 非流式不改写 chunk：响应体是单一字符串，日志里记上游原文
            // 比记翻译后的更有用 —— 后者可以由前者推导，反之不行。
            // 流式不同：帧序列的切分方式无法从上游事件反推，见下方流式分支。
            //
            // usage 不需要在这里接线：把 cache_read 加回输入只依赖上游协议，
            // 已由 AnthropicUsageParser 完成，直连与翻译两条线路拿到同一口径。
            Mono<UpstreamEvent> upstream = genericAnthropicChatService.messages(
                    translated.body(), route, downstreamHeaders, requestId, null, ctx);
            if (responseTranslator == null) {
                // 半轮实现态：回程未接，原样透传上游响应 —— 但不静默（§2.3.2）。
                warnResponseTranslationMissing(requestId, upstreamProtocol);
                return upstream;
            }
            return responseTranslator.translateResponse(upstream);
        }

        // 有去程翻译却无对应上游服务分支：当前不可达（唯一去程 C2M 的上游是 MESSAGES）。
        // 留个明确分支，将来加第三种上游协议时不会静默走错路。
        return Mono.error(new ProtocolTranslationNotSupportedException(
                route.provider().providerKey(), decision.downstreamProtocol(), upstreamProtocol));
    }

    /**
     * 为<strong>翻译路线</strong>组装一个上下文。
     *
     * <p>与直连唯一的差别：两侧协议不同（下游 {@code CHAT}、上游 MESSAGES），
     * 且带着 {@code translationContext}（响应侧要靠它决定怎么翻回来）。
     * 步骤登记由调用方按「回程是否命中」补 —— 那正是半轮实现态的判据。
     */
    private RequestPipelineContext translatedContext(TranslatedRequest translated,
                                                     WireProtocol upstreamProtocol,
                                                     ResolvedProviderRoute route,
                                                     HttpHeaders downstreamHeaders, String requestId) {
        return RequestPipelineContext.of(translated.body(), DOWNSTREAM_PROTOCOL, upstreamProtocol,
                route.provider(), downstreamHeaders, requestId, translated.context());
    }

    /**
     * 执行不含下游请求头上下文的非流式聊天补全。
     * 仅供内部兼容调用与单元测试使用；HTTP API 必须调用带 downstreamHeaders 的重载。
     */
    public Mono<UpstreamEvent> chatCompletion(Map<String, Object> openAiRequest, String model) {
        return chatCompletion(openAiRequest, model, HttpHeaders.EMPTY, null);
    }

    /**
     * 执行流式聊天补全。
     *
     * <p>返回<strong>统一形态</strong>的上游事件流：每条元素已经分好「载荷」与
     * 「终止标记」两态。控制器据此触发收尾，不必再按字符串匹配认魔数 ——
     * 那个判断在翻译路线下会拿下游协议去比对上游报文。
     *
     * @param openAiRequest OpenAI 格式请求体
     * @param model 模型名称
     * @return 统一形态的上游事件流
     */
    public Flux<UpstreamEvent> chatCompletionStream(Map<String, Object> openAiRequest, String model,
                                                     HttpHeaders downstreamHeaders, String requestId) {
        // 同非流式：defer 让组装期异常成为 onError 信号，控制器才能发出 SSE error 帧
        // 而不是让 WebFlux 兜底成 500 JSON。
        return Flux.defer(() ->
                dispatchChatCompletionStream(openAiRequest, model, downstreamHeaders, requestId));
    }

    private Flux<UpstreamEvent> dispatchChatCompletionStream(Map<String, Object> openAiRequest, String model,
                                                             HttpHeaders downstreamHeaders, String requestId) {
        ResolvedProviderRoute route = providerRouteResolver.resolve(model);
        if (route == null) {
            return Flux.error(new UnresolvedModelRouteException(model));
        }
        ProtocolDispatchDecision decision =
                protocolDispatchManager.dispatch(DOWNSTREAM_PROTOCOL, route.provider());
        // 同非流式：结论出来后立刻补协议信息，前端 Tag 不必等到上游响应。
        notifyProtocols(requestId, decision);
        
        // 同协议直连，原请求体不变。
        if (!decision.translationNeeded()) {
            // 同非流式：直连也要在组装期建上下文。
            RequestPipelineContext ctx = RequestPipelineContext.direct(openAiRequest, DOWNSTREAM_PROTOCOL,
                    route.provider(), downstreamHeaders, requestId, null);
            return genericOpenAiChatService.chatCompletionStream(openAiRequest, route, downstreamHeaders, requestId, ctx);
        }
        
        // 跨协议翻译：去程与回程各自查表，两半独立缺省（方向文档 §2.3.2）。
        //
        // 帧数不对等（第 2 节）：message_start 产 1 帧（唯一带 role），
        // content_block_start/stop 与 signature_delta 产 0 帧，
        // 而 [DONE] 由流结束触发而非 message_stop。
        WireProtocol upstreamProtocol = decision.upstreamProtocol();

        // 去程未命中即报错（同非流式：跳过只在去程已改写成功时才有价值，§2.3.2）。
        RequestProtocolTranslator requestTranslator = translatorRegistry
                .findRequestTranslator(DOWNSTREAM_PROTOCOL, upstreamProtocol).orElse(null);
        if (requestTranslator == null) {
            return Flux.error(new ProtocolTranslationNotSupportedException(
                    route.provider().providerKey(), decision.downstreamProtocol(), upstreamProtocol));
        }
        TranslatedRequest translated = requestTranslator.translateRequest(openAiRequest);
        ResponseProtocolTranslator responseTranslator = translatorRegistry
                .findResponseTranslator(DOWNSTREAM_PROTOCOL, upstreamProtocol).orElse(null);

        // 登记：去程恒已执行；回程仅在命中时登记（同非流式，判据见 RequestPipelineContext）。
        RequestPipelineContext ctx = translatedContext(translated, upstreamProtocol, route,
                downstreamHeaders, requestId);
        ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);
        if (responseTranslator != null) {
            ctx.markCompleted(PipelineStep.RESPONSE_TRANSLATION);
        }

        if (upstreamProtocol == WireProtocol.MESSAGES) {
            // 落库用的 chunk 改写器：下游协议记 CHAT，且 chunk 记翻译后的形态。
            // 流式必须重译而不能只记上游事件：帧数不对等（零帧/一帧/多帧），
            // 从上游事件反推不出下游到底收到了几帧、长什么样。
            // frameCounts 让日志页能把两栏按事件对齐 —— 零帧事件右侧留占位。
            // usage 同非流式分支：不在这里换算，解析层已给出归一口径。
            //
            // 回程未命中时传 null：没有回程就没有翻译后的 chunk 可记，
            // 落库退回上游原生事件（半轮态下开发者要看的正是上游原文）。
            //
            // 它由本层构造而非执行器自己派生 —— 后者需要执行器认识 TranslatorRegistry，
            // 会形成包级环并拆掉依赖倒置（3.3d-3 的 E 方案，见 plan_）。
            Function<List<String>, ChunkLogPayload> logChunkRewriter = responseTranslator == null
                    ? null
                    : chunks -> {
                        var log = responseTranslator.translateChunksForLog(
                                chunks, route.model(), translated.context().includeUsage());
                        return ChunkLogPayload.translated(log.translated(), chunks, log.frameCounts());
                    };
            Flux<UpstreamEvent> upstream = genericAnthropicChatService.messagesStream(
                    translated.body(), route, downstreamHeaders, requestId, logChunkRewriter, ctx);
            if (responseTranslator == null) {
                // 半轮实现态：回程未接，原样透传上游事件流 —— 但不静默（§2.3.2）。
                warnResponseTranslationMissing(requestId, upstreamProtocol);
                return upstream;
            }
            // 翻译器自己按输出协议分类，故直接用它伸出的流（与上游执行器同一原理）。
            return responseTranslator.translateStream(upstream, route.model(), translated.context());
        }

        return Flux.error(new ProtocolTranslationNotSupportedException(
                route.provider().providerKey(), decision.downstreamProtocol(), upstreamProtocol));
    }

    /**
     * 回程翻译未实现而原样透传时留痕 —— 透传本身可接受，但<strong>不能静默</strong>。
     *
     * <h2>为何是 warn 而非 debug</h2>
     * 与 {@link ProtocolNotifier} 那条「补协议信息失败」的 debug 不同：那条是观测链路
     * 自身的失败，无功能后果；而本条标记的是<strong>下游正在收到未翻译的上游响应</strong>，
     * 是一个「有人应该看见」的事实。同一个坑（流挂住、界面转圈而无报错）已踩过一次
     * （方向文档 §3.3），因此这里必须响 —— warn 平时不淹没日志、出现时一眼可见。
     *
     * <p>前端侧的可见性由已有的 {@code notifyProtocols} 承载：那条已把 {@code (下游, 上游)}
     * 写进生命周期事件，Toast 显示 {@code C→M} 这类标记。开发者看到标记 + 这条 warn，
     * 就知道自己在看的是原生上游响应，而非线上坏数据。
     *
     * @param requestId        调用标识，便于把日志与那次调用对上
     * @param upstreamProtocol 上游协议，点明是哪条回程缺实现
     */
    private void warnResponseTranslationMissing(String requestId, WireProtocol upstreamProtocol) {
        log.warn("回程翻译未实现，原样透传上游响应 [{}]：下游 {} ← 上游 {}（半轮实现态，"
                        + "仅开发中间态应出现；合并主干前须补齐回程翻译）",
                requestId, DOWNSTREAM_PROTOCOL, upstreamProtocol);
    }

    /**
     * 执行不含下游请求头上下文的流式聊天补全。
     * 仅供内部兼容调用与单元测试使用；HTTP API 必须调用带 downstreamHeaders 的重载。
     */
    public Flux<UpstreamEvent> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
        return chatCompletionStream(openAiRequest, model, HttpHeaders.EMPTY, null);
    }
}
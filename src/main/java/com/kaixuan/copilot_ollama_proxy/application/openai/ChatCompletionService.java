package com.kaixuan.copilot_ollama_proxy.application.openai;

import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.protocol.NoSupportedProtocolException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchDecision;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchManager;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.TranslatedRequest;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.AnthropicToOpenAiResponseTranslator;
import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.OpenAiToAnthropicRequestTranslator;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.provider.ChunkLogPayload;
import com.kaixuan.copilot_ollama_proxy.provider.DownstreamLogView;
import com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic.GenericAnthropicChatService;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.GenericOpenAiChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 聊天补全应用服务 —— 服务下游的 OpenAI 协议端点。
 *
 * 负责解析供应商模型路由，经协议调度管理器确认走法后委托上游执行器完成调用。
 *
 * <p>本服务的下游协议恒为 {@link WireProtocol#OPENAI}（由它服务的端点决定）；
 * 上游协议由 {@link ProtocolDispatchManager} 按供应商支持情况得出。两条路都活：
 * 供应商支持 OpenAI 时直连，只支持 Anthropic 时走 O2A 去程 + A2O 回程翻译。
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
    private static final WireProtocol DOWNSTREAM_PROTOCOL = WireProtocol.OPENAI;

    private final ProviderRouteResolver providerRouteResolver;
    private final ProtocolDispatchManager protocolDispatchManager;
    private final GenericOpenAiChatService genericOpenAiChatService;
    private final GenericAnthropicChatService genericAnthropicChatService;
    private final OpenAiToAnthropicRequestTranslator o2aTranslator;
    private final AnthropicToOpenAiResponseTranslator a2oTranslator;

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
     * @param o2aTranslator O2A 请求翻译器（去程）
     * @param a2oTranslator A2O 响应翻译器（回程）
     */
    public ChatCompletionService(ProviderRouteResolver providerRouteResolver,
                                 ProtocolDispatchManager protocolDispatchManager,
                                 GenericOpenAiChatService genericOpenAiChatService,
                                 GenericAnthropicChatService genericAnthropicChatService,
                                 OpenAiToAnthropicRequestTranslator o2aTranslator,
                                 AnthropicToOpenAiResponseTranslator a2oTranslator) {
        this.providerRouteResolver = providerRouteResolver;
        this.protocolDispatchManager = protocolDispatchManager;
        this.genericOpenAiChatService = genericOpenAiChatService;
        this.genericAnthropicChatService = genericAnthropicChatService;
        this.o2aTranslator = o2aTranslator;
        this.a2oTranslator = a2oTranslator;
    }

    @Autowired(required = false)
    public void setLifecycleNotifier(CallLifecycleNotifier lifecycleNotifier) {
        this.lifecycleNotifier = lifecycleNotifier;
    }

    /**
     * 把调度结论补进生命周期事件，供前端 Toast 渲染路径标记（如「O→A」）。
     *
     * <p>调用时机必须在 {@code dispatch} 之后、真正的上游调用之前：过了这一步才知道
     * 上游协议，而再往后就是网络等待，晚补会让前端先看到没有标记的 Toast。
     *
     * <p>失败不中断调用 —— 事件推送是 best-effort 的观测链路，任何异常都不能影响聊天数据流。
     * 但<strong>要留痕迹</strong>：完全吞掉时，补写持续失败（比如 requestId 口径不一致）
     * 的唯一症状是「路径标记不显示」，无从查证 —— 观测链路自身不可观测是个反模式。
     * 用 debug 而非 warn：它不影响功能，平时不必占日志，排查时开 debug 即可看到。
     */
    private void notifyProtocols(String requestId, ProtocolDispatchDecision decision) {
        if (lifecycleNotifier == null || requestId == null) {
            return;
        }
        try {
            lifecycleNotifier.recordProtocols(requestId, DOWNSTREAM_PROTOCOL.name(),
                    decision.upstreamProtocol().name());
        } catch (Exception exception) {
            log.debug("生命周期协议信息补写失败，不影响调用本身 [{}]: {}",
                    requestId, exception.toString());
        }
    }

    /**
     * 执行非流式聊天补全。
     *
     * @param openAiRequest OpenAI 格式请求体
     * @param model 模型名称
     * @return 上游原始 OpenAI 响应
     */
    public Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model,
                                       HttpHeaders downstreamHeaders, String requestId) {        // defer 把路由 / 调度 / 翻译的同步异常转成 onError 信号，控制器才能分类处置。
        // 理由见类注释。
        return Mono.defer(() ->
                dispatchChatCompletion(openAiRequest, model, downstreamHeaders, requestId));
    }

    private Mono<String> dispatchChatCompletion(Map<String, Object> openAiRequest, String model,
                                                HttpHeaders downstreamHeaders, String requestId) {        ResolvedProviderRoute route = providerRouteResolver.resolve(model);
        if (route == null) {
            return Mono.error(new RuntimeException("没有可用的上游服务来处理模型: " + model));
        }
        ProtocolDispatchDecision decision =
                protocolDispatchManager.dispatch(DOWNSTREAM_PROTOCOL, route.provider());
        // 调度结论出来了：把下游/上游协议补进生命周期事件，前端 Toast 才能显示路径标记。
        notifyProtocols(requestId, decision);
        
        // 同协议直连，原请求体不变。
        if (!decision.translationNeeded()) {
            return genericOpenAiChatService.chatCompletion(openAiRequest, route, downstreamHeaders, requestId);
        }
        
        // 跨协议翻译：去程改写请求体、回程改写响应体。
        //
        // 翻译套在上游服务外侧，因而天然在 retryWhen 之外 ——
        // 空响应判定（AnthropicContentDetector）与 api_call_log 落库用的都是
        // 上游原生形态，若翻译进了重试内侧，判定器会把每一轮都当成空响应。
        // 见 docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md 第 12 节。
        //
        // 模型名传下游原始的 model（含 [provider-key] 前缀）而非上游返回的裸名：
        // 本服务按前缀路由，把裸名透给下游会让它下一轮路由失败（第 7 节）。
        if (decision.upstreamProtocol() == WireProtocol.ANTHROPIC) {
            TranslatedRequest translated = o2aTranslator.translateRequest(openAiRequest);
            // 非流式不改写 chunk：响应体是单一字符串，日志里记上游原文
            // 比记翻译后的更有用 —— 后者可以由前者推导，反之不行。
            // 流式不同：帧序列的切分方式无法从上游事件反推，见下方流式分支。
            //
            // usage 不需要在这里接线：把 cache_read 加回输入只依赖上游协议，
            // 已由 AnthropicUsageParser 完成，直连与翻译两条线路拿到同一口径。
            Mono<String> upstream = genericAnthropicChatService.messages(
                    translated.body(), route, downstreamHeaders, requestId,
                    DownstreamLogView.protocolOnly(DOWNSTREAM_PROTOCOL.name()));
            return a2oTranslator.translateResponse(upstream);
        }
        
        // 其它协议组合：当前只有 OPENAI 与 ANTHROPIC 两种，走不到这里。
        // 留个明确分支，将来加第三种协议时不会静默走错路。
        return Mono.error(new ProtocolTranslationNotSupportedException(
                route.provider().providerKey(), decision.downstreamProtocol(), decision.upstreamProtocol()));
    }

    /**
     * 执行不含下游请求头上下文的非流式聊天补全。
     * 仅供内部兼容调用与单元测试使用；HTTP API 必须调用带 downstreamHeaders 的重载。
     */
    public Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model) {
        return chatCompletion(openAiRequest, model, HttpHeaders.EMPTY, null);
    }

    /**
     * 执行流式聊天补全。
     *
     * @param openAiRequest OpenAI 格式请求体
     * @param model 模型名称
     * @return 上游 SSE 数据块
     */
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model,
                                              HttpHeaders downstreamHeaders, String requestId) {
        // 同非流式：defer 让组装期异常成为 onError 信号，控制器才能发出 SSE error 帧
        // 而不是让 WebFlux 兜底成 500 JSON。
        return Flux.defer(() ->
                dispatchChatCompletionStream(openAiRequest, model, downstreamHeaders, requestId));
    }

    private Flux<String> dispatchChatCompletionStream(Map<String, Object> openAiRequest, String model,
                                                      HttpHeaders downstreamHeaders, String requestId) {
        ResolvedProviderRoute route = providerRouteResolver.resolve(model);
        if (route == null) {
            return Flux.error(new RuntimeException("没有可用的上游服务来处理模型: " + model));
        }
        ProtocolDispatchDecision decision =
                protocolDispatchManager.dispatch(DOWNSTREAM_PROTOCOL, route.provider());
        // 同非流式：结论出来后立刻补协议信息，前端 Tag 不必等到上游响应。
        notifyProtocols(requestId, decision);
        
        // 同协议直连，原请求体不变。
        if (!decision.translationNeeded()) {
            return genericOpenAiChatService.chatCompletionStream(openAiRequest, route, downstreamHeaders, requestId);
        }
        
        // 跨协议翻译：去程改写请求体、回程把 Anthropic 事件翻回 OpenAI chunk。
        //
        // 帧数不对等（第 2 节）：message_start 产 1 帧（唯一带 role），
        // content_block_start/stop 与 signature_delta 产 0 帧，
        // 而 [DONE] 由流结束触发而非 message_stop。
        if (decision.upstreamProtocol() == WireProtocol.ANTHROPIC) {
            TranslatedRequest translated = o2aTranslator.translateRequest(openAiRequest);
            // 落库视图：下游协议记 OPENAI，且 chunk 记翻译后的形态。
            // 流式必须重译而不能只记上游事件：帧数不对等（零帧/一帧/多帧），
            // 从上游事件反推不出下游到底收到了几帧、长什么样。
            // frameCounts 让日志页能把两栏按事件对齐 —— 零帧事件右侧留占位。
            // usage 同非流式分支：不在这里换算，解析层已给出归一口径。
            DownstreamLogView logView = new DownstreamLogView(
                    DOWNSTREAM_PROTOCOL.name(),
                    chunks -> {
                        var log = a2oTranslator.translateChunksForLog(
                                chunks, route.model(), translated.context().includeUsage());
                        return ChunkLogPayload.translated(log.translated(), chunks, log.frameCounts());
                    });
            Flux<String> upstream = genericAnthropicChatService.messagesStream(
                    translated.body(), route, downstreamHeaders, requestId, logView);
            return a2oTranslator.translateStream(upstream, route.model(), translated.context());
        }
        
        return Flux.error(new ProtocolTranslationNotSupportedException(
                route.provider().providerKey(), decision.downstreamProtocol(), decision.upstreamProtocol()));
    }

    /**
     * 执行不含下游请求头上下文的流式聊天补全。
     * 仅供内部兼容调用与单元测试使用；HTTP API 必须调用带 downstreamHeaders 的重载。
     */
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
        return chatCompletionStream(openAiRequest, model, HttpHeaders.EMPTY, null);
    }
}
package com.kaixuan.copilot_ollama_proxy.application.pipeline;

import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchDecision;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchManager;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslationNotSupportedException;
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
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamExecutor;
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamExecutorRegistry;
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
 * 主干 —— 三个下游端点共享的那条线的<strong>前奏段</strong>。
 *
 * <h2>它现在 own 整条线（3.4c-2 起）</h2>
 * 3.4a/b 只收下前奏（{@code resolve → dispatch → notifyProtocols}）；
 * 3.4c-2 把<strong>主干剩下的全部</strong>也收进来：
 *
 * <pre>
 * 解析路由与上游协议 → 【translate 插槽】→ 调执行器（查表）→ 【responseTranslate 插槽】
 * </pre>
 *
 * <p>其中两个【插槽】按协议查表选实现：
 * <ul>
 *   <li><strong>请求翻译</strong>：未命中 → <strong>报错</strong>
 *       （发不出上游能懂的请求，跳过只会让上游回 400）；</li>
 *   <li><strong>响应翻译</strong>：未命中 → <strong>透传 + WARN</strong>
 *       （半轮实现态是开发者的正常中间态，帧本来就在手里）。</li>
 * </ul>
 * send 也是一个插槽，且<strong>未命中即报错</strong> —— 协议被声明支持却没有执行器，
 * 那是装配坏了，不是领域事实。三者语义不同，是插槽各自的属性（见 plan_ Step 3.4）。
 *
 * <h2>为何它能 own 全流程（而 3.4a/b 不能）</h2>
 * 因为「翻译会把 body 换掉」—— 翻译插槽一进主干，夹在它和解包之间的
 * <strong>执行器调用就跟着进了主干</strong>。3.4d-1 先建好了执行器接口与键，
 * 这一步才做得成；而 ctx 由端点创建、主干逐步回填（`forEndpoint` + `applyRouting`
 * + `applyTranslation`）是同一件事的前提，故 3.4c-1 先落地。
 *
 * <h2>方法体是同步的，异常是抛出的</h2>
 * 调用方把它包在 {@code Mono.defer} / {@code Flux.defer} 里，因此同步抛出
 * 会自然变成 {@code onError} 信号 —— 与原先各 Service 里 {@code return Mono.error(...)}
 * 逐字等价。这个等价性是「零行为变更」的一部分，不是风格选择：
 * 若某个调用方不在 defer 内，同步抛出会逃出组装期，{@code onErrorResume} 就不在链上了。
 *
 * <p>本类的准备与决策部分<strong>无 I/O</strong>（只读本地目录 + 判协议），因此不需要
 * {@code subscribeOn(Schedulers.boundedElastic())} —— 那条纪律针对的是 JDBC 调用。
 *
 * <h2>日志归属的一处变化</h2>
 * {@link ProtocolNotifier} 的失败日志（debug 级，仅观测链路自身失败时打印）
 * 在搬迁前以各 Service 名打印，搬迁后以本类名打印；
 * 同样，回程缺实现的 WARN 也改由本类打印（原先以 {@code ChatCompletionService} 名打印）。
 * 这是本步<strong>唯一可观测的差异</strong>，只影响日志来源类名、不影响功能。
 */
@Service
public class RequestPipeline {

    private static final Logger log = LoggerFactory.getLogger(RequestPipeline.class);

    private final ProviderRouteResolver providerRouteResolver;
    private final ProtocolDispatchManager protocolDispatchManager;
    private final TranslatorRegistry translatorRegistry;
    private final UpstreamExecutorRegistry executorRegistry;

    /**
     * 调用生命周期事件通知器，由 Spring 可选注入。
     *
     * <p>可选注入（与 provider 层同一范式）—— 单元测试直接 new 本类时不关心这条链路，
     * 缺省即不发。它随 {@code notifyProtocols} 一起从三个 Service 上移到这里。
     */
    private CallLifecycleNotifier lifecycleNotifier;

    public RequestPipeline(ProviderRouteResolver providerRouteResolver,
                           ProtocolDispatchManager protocolDispatchManager,
                           TranslatorRegistry translatorRegistry,
                           UpstreamExecutorRegistry executorRegistry) {
        this.providerRouteResolver = providerRouteResolver;
        this.protocolDispatchManager = protocolDispatchManager;
        this.translatorRegistry = translatorRegistry;
        this.executorRegistry = executorRegistry;
    }

    @Autowired(required = false)
    public void setLifecycleNotifier(CallLifecycleNotifier lifecycleNotifier) {
        this.lifecycleNotifier = lifecycleNotifier;
    }

    /**
     * 跑一遍主干的前奏，把「本次请求的两条结论」交给调用方。
     *
     * <p>本方法同步执行、同步抛异常；把它包进 {@code defer} 是<strong>调用方</strong>的责任
     * （三个 Service 都这么做）—— 理由见类注释。
     *
     * @param model              客户端请求的模型名（可带 {@code [provider-key]} 前缀）
     * @param downstreamProtocol 本端点服务的下游协议
     * @param requestId          本次调用唯一标识，用于生命周期事件与取消注册
     * @return 路由与调度两个结论
     * @throws UnresolvedModelRouteException 路由在本地目录未解析出唯一供应商
     * @throws com.kaixuan.copilot_ollama_proxy.application.protocol.NoSupportedProtocolException
     *         供应商未声明支持任何协议
     */
    public PipelinePreamble run(String model, WireProtocol downstreamProtocol, String requestId) {
        ResolvedProviderRoute route = providerRouteResolver.resolve(model);
        if (route == null) {
            // 类型化异常而非裸 RuntimeException：路由在本地目录就没解析出来，
            // 上游从未被连接，控制器据此回 400 而不是「无法连接到上游服务」502。
            throw new UnresolvedModelRouteException(model);
        }
        ProtocolDispatchDecision decision =
                protocolDispatchManager.dispatch(downstreamProtocol, route.provider());
        // 调度结论出来了：把下游/上游协议补进生命周期事件，前端 Toast 才能显示路径标记。
        // 它刻意在抛「未实现」异常之前 —— 失败 Toast 也要能看到跨协议标记。
        ProtocolNotifier.notifyProtocols(log, lifecycleNotifier, requestId, downstreamProtocol, decision);
        return new PipelinePreamble(route, decision);
    }

    // ==================== 主干全流程 ====================

    /**
     * 跑完整条主干（非流式）—— 调用方只需交出端点建好的 ctx。
     *
     * <p>流程：前奏 → 回填路由 → 【translate 插槽】→ send（查表选执行器）→
     * 【responseTranslate 插槽】。各步骤的顺序约束与理由见类注释。
     *
     * @param ctx 端点建好的上下文（{@code forEndpoint}）；本方法会逐步填充它
     * @return 统一形态的上游响应（跨协议且回程已接时，已被翻译回下游形态）
     */
    public Mono<UpstreamEvent> execute(RequestPipelineContext ctx) {
        ResponseProtocolTranslator responseTranslator = prepareForSend(ctx);
        Mono<UpstreamEvent> upstream = executorRegistry.require(ctx.upstreamProtocol()).invoke(ctx, null);
        if (responseTranslator == null) {
            warnIfHalfRound(ctx);
            return upstream;
        }
        return responseTranslator.translateResponse(upstream);
    }

    /**
     * 跑完整条主干（流式）—— 与非流式同一条流程，两处差异见下。
     *
     * <p>差异一：落库用的 chunk 改写器只有流式才构造 ——
     * 非流式的响应体是单一字符串，日志记上游原文比记翻译后的更有用
     * （后者可由前者推导，反之不行）；而流式的<strong>帧切分方式无法从上游事件反推</strong>，
     * 故必须重译一遍才能记下「下游实际收到了几帧」。
     *
     * <p>差异二：回程翻译走 {@code translateStream}（内含帧数不对等的状态机），
     * 且需要 {@code translationContext} 里的 {@code include_usage}。
     *
     * @param ctx 端点建好的上下文（{@code forEndpoint}）；本方法会逐步填充它
     * @return 统一形态的上游事件流（跨协议且回程已接时，已被翻译回下游形态）
     */
    public Flux<UpstreamEvent> executeStream(RequestPipelineContext ctx) {
        ResponseProtocolTranslator responseTranslator = prepareForSend(ctx);
        Flux<UpstreamEvent> upstream = executorRegistry.require(ctx.upstreamProtocol())
                .invokeStream(ctx, chunkRewriterFor(ctx, responseTranslator));
        if (responseTranslator == null) {
            warnIfHalfRound(ctx);
            return upstream;
        }
        return responseTranslator.translateStream(upstream, ctx.model(), ctx.translationContext());
    }

    /**
     * 两个入口的公共前半程：前奏 → 回填路由 → translate 插槽。
     *
     * @return 回程翻译器；直连或回程未实现时为 {@code null}
     */
    private ResponseProtocolTranslator prepareForSend(RequestPipelineContext ctx) {
        PipelinePreamble preamble = run(ctx.model(), ctx.downstreamProtocol(), ctx.requestId());
        // 回填路由与调度结论 —— 此前 ctx 里只有下游侧事实（见 forEndpoint）。
        ctx.applyRouting(preamble.route().model(), preamble.route().provider(),
                preamble.decision().upstreamProtocol());
        return translateRequestIfNeeded(ctx);
    }

    /**
     * 【translate 插槽】—— 直连时不介入；跨协议时查去程与回程。
     *
     * <h2>两半的未命中语义不同（这是刻意的）</h2>
     * <ul>
     *   <li><strong>去程未命中 → 报错</strong>：没有请求翻译就发不出上游能理解的请求，
     *       跳过只会让上游回 400 —— 与「配置写错」现象相同、不承载信息（方向文档 §2.3.2）。</li>
     *   <li><strong>回程未命中 → 返回 null</strong>，由调用方透传上游原生响应并留痕。
     *       那是开发者写新方向时的<strong>正常中间态</strong>：去程已接，正要拿真实上游
     *       验证请求是否被接受，此时帧本来就在手里，不该被压住。</li>
     * </ul>
     *
     * <p>登记也在这一步：去程恒已执行；回程仅在命中时登记。
     * 空响应拦截据此决定介入还是跳过（判据见 {@link RequestPipelineContext#shouldApplyEmptyResponseGate()}）
     * —— <strong>漏登记回程会让全实现的线路静默失去空响应兜底</strong>。
     */
    private ResponseProtocolTranslator translateRequestIfNeeded(RequestPipelineContext ctx) {
        if (!ctx.translationNeeded()) {
            return null;
        }
        WireProtocol downstream = ctx.downstreamProtocol();
        WireProtocol upstream = ctx.upstreamProtocol();

        RequestProtocolTranslator requestTranslator = translatorRegistry
                .findRequestTranslator(downstream, upstream).orElse(null);
        if (requestTranslator == null) {
            throw new ProtocolTranslationNotSupportedException(
                    ctx.provider().providerKey(), downstream, upstream);
        }
        TranslatedRequest translated = requestTranslator.translateRequest(ctx.body());
        ResponseProtocolTranslator responseTranslator = translatorRegistry
                .findResponseTranslator(downstream, upstream).orElse(null);

        // body 换形态、查表键跟着换、回程所需的事实一并记下 —— 三件事同源，故一个入口。
        ctx.applyTranslation(translated.body(), upstream, translated.context());
        ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);
        if (responseTranslator != null) {
            ctx.markCompleted(PipelineStep.RESPONSE_TRANSLATION);
        }
        return responseTranslator;
    }

    /**
     * 落库用的 chunk 改写器 —— 只有「回程已接」时才构造。
     *
     * <p>回程未命中时返回 {@code null}：没有回程就没有翻译后的 chunk 可记，
     * 落库退回上游原生事件（半轮态下开发者要看的正是上游原文）。
     */
    private static Function<List<String>, ChunkLogPayload> chunkRewriterFor(
            RequestPipelineContext ctx, ResponseProtocolTranslator responseTranslator) {
        if (responseTranslator == null) {
            return null;
        }
        return chunks -> {
            var chunkLog = responseTranslator.translateChunksForLog(
                    chunks, ctx.model(), ctx.translationContext().includeUsage());
            return ChunkLogPayload.translated(chunkLog.translated(), chunks, chunkLog.frameCounts());
        };
    }

    /**
     * 半轮实现态留痕 —— 透传本身可接受，但<strong>不能静默</strong>（方向文档 §2.3.2）。
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
     * <p><strong>直连不告警</strong>：那里没有「未翻译」这回事（大家本就是同一种协议），
     * 故只在跨协议时响。
     */
    private void warnIfHalfRound(RequestPipelineContext ctx) {
        if (!ctx.translationNeeded()) {
            return;
        }
        log.warn("回程翻译未实现，原样透传上游响应 [{}]：下游 {} ← 上游 {}（半轮实现态，"
                        + "仅开发中间态应出现；合并主干前须补齐回程翻译）",
                ctx.requestId(), ctx.downstreamProtocol(), ctx.upstreamProtocol());
    }
}

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
import com.kaixuan.copilot_ollama_proxy.upstream.ChunkLogPayload;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.upstream.send.UpstreamExecutor;
import com.kaixuan.copilot_ollama_proxy.upstream.send.UpstreamExecutorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * 主干 —— 三个下游端点共享的那条线的<strong>前奏段</strong>。
 *
 * <h2>它 own 整条线，且只有一个入口（3.5a 起）</h2>
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
 * <h2>流式 / 非流式：两条入口已于 3.5a 合成一条</h2>
 * 此前是 {@code execute}（非流式，返回 {@code Mono}）与 {@code executeStream}（流式）
 * 两个入口。合并后只剩 {@link #execute}，它返回统一的 {@code Flux<UpstreamEvent>}：
 * <strong>非流式就是「恰有一个元素的流」</strong>（见 {@code UpstreamEvent} 的类注释）。
 *
 * <p><strong>本类内部</strong>只读一次 {@code ctx.stream()}，用于在 send 与回程两处
 * <strong>选机制</strong> —— 那是 3.5.2 认定的两处真本质（一次取全 vs 逐事件）。
 * 主干上的其余步骤对两态完全无感知。
 *
 * <p><strong>限定「本类内部」是因为执行器也各读一次</strong>：它们的两个方法体
 * （{@code invoke} / {@code invokeStream}）都要拿流式标志去写 body、选 {@code Accept} 头、
 * 定 ttfb 口径。那个读取不构成第二个事实源 —— 主干正是按 {@code ctx.stream()}
 * 选中那两个方法之一的，因此两边必然一致。但这意味着一条<strong>依赖调用方守规矩的
 * 不变式</strong>：直接调 {@code invokeStream} 而 ctx 里 {@code stream=false} 会走错路且不会响。
 * 该不变式在 3.5b（两态合链）后自然消失，故现在只记录、不为此加防护。
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
     * 跑完整条主干 —— <strong>唯一入口</strong>，流式与非流式共用（3.5a 起的形状）。
     *
     * <p>流程：前奏 → 回填路由 → 【translate 插槽】→ send（查表选执行器）→
     * 【responseTranslate 插槽】。各步骤的顺序约束与理由见类注释。
     *
     * <h2>两态的分歧收敛到这一处</h2>
     * 本方法内部只读一次 {@code ctx.stream()}，用于**选机制**：
     * send 阶段选 {@code invoke} / {@code invokeStream}，回程阶段选
     * {@code translateResponse} / {@code translateStream}。除此之外，
     * 主干上的每一步对两态**完全无感知** —— 这正是「把纵向复制压成一个字段的分叉」。
     *
     * <p>那两处机制之所以必须分开，是 3.5.2 认定的**真本质**：
     * 非流式一次拿到全部（无「扣住」可言），流式要逐事件处理且帧数不对等。
     * 其余 5 处「同判据不同机制」的分歧在 3.5b 收束。
     *
     * @param ctx 端点建好的上下文（{@code forEndpoint}）；本方法会逐步填充它
     * @return 统一形态的上游事件流（非流式即「恰有一个元素的流」；
     *         跨协议且回程已接时，已被翻译回下游形态）
     */
    public Flux<UpstreamEvent> execute(RequestPipelineContext ctx) {
        ResponseProtocolTranslator responseTranslator = prepareForSend(ctx);
        // 主干上唯一一次读「是不是流式」—— 下面两处选机制都由它驱动。
        boolean stream = ctx.stream();
        UpstreamExecutor executor = executorRegistry.require(ctx.upstreamProtocol());

        // send 插槽：非流式一次取全（chunk 不改写，故 rewriter 传 null），
        // 流式逐事件取，且落库需要 chunk 改写器（帧切分方式无法从上游事件反推）。
        Flux<UpstreamEvent> upstream = stream
                ? executor.invokeStream(ctx, chunkRewriterFor(ctx, responseTranslator))
                : executor.invoke(ctx, null).flux();

        if (responseTranslator == null) {
            warnIfHalfRound(ctx);
            return upstream;
        }
        // 回程插槽：非流式的回程翻译收/吐 Mono（单一响应体），故先把单元素流收成 Mono；
        // 流式要走帧数不对等的状态机（Flux→Flux）。
        return stream
                ? responseTranslator.translateStream(upstream, ctx.model(), ctx.translationContext())
                : responseTranslator.translateResponse(upstream.single()).flux();
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

package com.kaixuan.copilot_ollama_proxy.pipeline;

import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.dispatch.ProtocolDispatchDecision;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.dispatch.ProtocolDispatchManager;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.TranslatedRequest;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.RequestProtocolTranslator;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.ResponseProtocolTranslator;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.TranslatorRegistry;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.application.runtime.UnresolvedModelRouteException;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.notify.ProtocolNotifier;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.outbound.OutboundRequestAssembler;
import com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.RequestBodyAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * <strong>发送前块</strong> —— 主干「真正发出 HTTP 之前」那一段（阶段 4 刀 3 块化）。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：主干<strong>功能块</strong>（发送前） · 位置：{@code pipeline/}（阶段 5 归拢后将进 {@code pipeline/before/}）
 * <p>{@link RequestPipeline#execute} 现在只有两行：{@code beforeSend.process(ctx)} 与
 * {@code return afterSend.process(ctx)}。本类是前者。
 *
 * <h2>为什么能是「一步步改 ctx」的形态</h2>
 * 发送前的每一步都是<strong>同步</strong>的：拿到 ctx → 改 ctx → 返回。数据当场就在，
 * 没有「未来才到」的东西。因此本块把自己拆成四个语义明确的 {@code void step(ctx)}：
 * <ol>
 *   <li>{@link #routeStep} —— 找供应商 + 定协议，回填 ctx（route + dispatch + notify）；</li>
 *   <li>{@link #translateStep} —— 【支线】按协议对查翻译器，跨协议时改 {@code ctx.body}，
 *       并把翻译对记进 ctx；</li>
 *   <li>{@link #assembleStep} —— 请求体装配（复用 {@link RequestBodyAssembler}），改 {@code ctx.body}；</li>
 *   <li>{@link #assembleOutboundStep} —— 【支线】出站请求装配（复用 {@link OutboundRequestAssembler}），
 *       定下「发去哪、带什么头」，写 {@code ctx.outboundHeaders/outboundBaseUrl}。</li>
 * </ol>
 * 与<strong>发送后块</strong>（{@code AfterSend}）的分界是「真正发出 HTTP」：那之后是异步状态机，
 * 无法写成 {@code void step(ctx)}（函数在数据到达前就返回 {@code Flux}）。这条缝是同步/异步的
 * 本质分界，不是写法选择。
 *
 * <h2>方法体同步、异常抛出（defer 由端点负责）</h2>
 * 本块所有步骤同步执行、同步抛异常（{@code UnresolvedModelRouteException} /
 * {@code NoSupportedProtocolException} / {@code ProtocolTranslationNotSupportedException} /
 * {@code RequestTranslationException}）。端点服务把 {@code execute} 包在 {@code Mono/Flux.defer}
 * 里，因此同步抛出会自然变成 {@code onError} 信号 —— 与搬迁前逐字等价。
 *
 * <h2>它持有的是「无状态协作者」（工具），不是请求数据</h2>
 * 路由解析器、调度器、翻译器注册表、请求体装配器都是单例 Bean、无请求态，故本块做成
 * 单例 Bean 安全。请求数据在 ctx 里、每次请求一份。
 */
@Service
public class BeforeSend {

    private static final Logger log = LoggerFactory.getLogger(BeforeSend.class);

    private final ProviderRouteResolver providerRouteResolver;
    private final ProtocolDispatchManager protocolDispatchManager;
    private final TranslatorRegistry translatorRegistry;
    private final RequestBodyAssembler requestBodyAssembler;
    private final OutboundRequestAssembler outboundRequestAssembler;

    /**
     * 生命周期通知器，可选注入（与旧 {@code RequestPipeline} 同一范式）——
     * 单元测试直接 new 本块时不关心这条链路，缺省即不发。
     */
    private CallLifecycleNotifier lifecycleNotifier;

    public BeforeSend(ProviderRouteResolver providerRouteResolver,
                      ProtocolDispatchManager protocolDispatchManager,
                      TranslatorRegistry translatorRegistry,
                      RequestBodyAssembler requestBodyAssembler,
                      OutboundRequestAssembler outboundRequestAssembler) {
        this.providerRouteResolver = providerRouteResolver;
        this.protocolDispatchManager = protocolDispatchManager;
        this.translatorRegistry = translatorRegistry;
        this.requestBodyAssembler = requestBodyAssembler;
        this.outboundRequestAssembler = outboundRequestAssembler;
    }

    @Autowired(required = false)
    public void setLifecycleNotifier(CallLifecycleNotifier lifecycleNotifier) {
        this.lifecycleNotifier = lifecycleNotifier;
    }

    /**
     * 跑完发送前的三步 —— 每步改 ctx，全块返回 {@code void}。
     *
     * <p>返回 void 是本块的形态本质：发送前的一切都是「就地改状态」，没有需要交出去的产物
     * （产物是 ctx 自己）。这与发送后块必须返回 {@code Flux} 形成对照。
     *
     * @param ctx 端点建好的上下文；本方法逐步填充它
     */
    public void process(RequestPipelineContext ctx) {
        routeStep(ctx);
        translateStep(ctx);
        assembleStep(ctx);
        assembleOutboundStep(ctx);
    }

    /**
     * 步骤 1：找供应商 + 定协议，回填 ctx。
     *
     * <p>三件事：解析路由（剥前缀、定供应商）→ 协议调度（直连还是翻译、上游协议）→
     * 把「(下游, 上游)」写进生命周期事件（前端 Toast 显示 {@code C→M} 标记）。
     * {@code notifyProtocols} 刻意在可能抛错的步骤之前，失败 Toast 也要能看到跨协议标记。
     *
     * <p><strong>包级可见（非 private）是为了单元测试</strong>：路由解析、调度、通知这三件
     * 从旧 {@code RequestPipeline.run} 逐字搬来，{@code RequestPipelineTests} 直接调它、
     * 断言 ctx 回填与异常，而不必跑完整个 {@link #process}（后两步要真实装配器）。
     *
     * @throws UnresolvedModelRouteException 路由在本地目录未解析出唯一供应商（控制器回 400）
     * @throws com.kaixuan.copilot_ollama_proxy.pipeline.protocol.NoSupportedProtocolException
     *         供应商未声明支持任何协议
     */
    void routeStep(RequestPipelineContext ctx) {
        ResolvedProviderRoute route = providerRouteResolver.resolve(ctx.model());
        if (route == null) {
            // 类型化异常而非裸 RuntimeException：路由在本地目录就没解析出来，
            // 上游从未被连接，控制器据此回 400 而不是「无法连接到上游服务」502。
            throw new UnresolvedModelRouteException(ctx.model());
        }
        ProtocolDispatchDecision decision =
                protocolDispatchManager.dispatch(ctx.downstreamProtocol(), route.provider());
        ProtocolNotifier.notifyProtocols(log, lifecycleNotifier, ctx.requestId(),
                ctx.downstreamProtocol(), decision);
        // 回填路由与调度结论 —— 此前 ctx 里只有下游侧事实（见 forEndpoint）。
        ctx.applyRouting(route.model(), route.provider(), decision.upstreamProtocol());
    }

    /**
     * 步骤 2【支线】：请求翻译 —— 直连时不介入；跨协议时按协议对查去程与回程翻译器。
     *
     * <h2>两半的未命中语义不同（这是刻意的）</h2>
     * <ul>
     *   <li><strong>去程未命中 → 报错</strong>：没有请求翻译就发不出上游能理解的请求，
     *       跳过只会让上游回 400 —— 与「配置写错」现象相同、不承载信息（方向文档 §2.3.2）。</li>
     *   <li><strong>回程未命中 → 记 null</strong>，由发送后块透传上游原生响应并留痕。
     *       那是开发者写新方向时的<strong>正常中间态</strong>：去程已接，正要拿真实上游
     *       验证请求是否被接受，此时帧本来就在手里，不该被压住。</li>
     * </ul>
     *
     * <h2>翻译对记进 ctx（阶段 4 刀 3）</h2>
     * 去程翻译器在本步当场用掉（改 body）；回程翻译器要到<strong>发送后块</strong>才用，
     * 跨了块边界，故经 {@code ctx.applyTranslators(...)} 传递。去程也一并记入只为对称
     * （见 {@link RequestPipelineContext} 类注释「装策略」段）。
     *
     * <p>登记也在这一步：去程恒已执行；回程仅在命中时登记。空响应拦截据此决定介入还是跳过
     * （判据见 {@link RequestPipelineContext#shouldApplyEmptyResponseGate()}）——
     * <strong>漏登记回程会让全实现的线路静默失去空响应兜底</strong>。
     */
    private void translateStep(RequestPipelineContext ctx) {
        if (!ctx.translationNeeded()) {
            return;
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
        // 翻译对记进 ctx：去程当场用完，回程留给发送后块（跨块边界）。
        ctx.applyTranslators(requestTranslator, responseTranslator);
        ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);
        if (responseTranslator != null) {
            ctx.markCompleted(PipelineStep.RESPONSE_TRANSLATION);
        }
    }

    /**
     * 步骤 3：请求体装配 —— 把 ctx.body 装配成最终发往上游的形态。
     *
     * <p>协议无关的公共序列（复制 / 解析模型名 / 写 model+stream / bodyRules / 清 null）在主干，
     * 协议特定三步（system 抬升 / max_tokens / 思考注入）走 {@code RequestBodyStageRegistry} 支线。
     * 完整顺序与约束见 {@link RequestBodyAssembler}。放在翻译之后 —— body 此时已是上游形态，
     * 装配据 {@code bodyProtocol} 查表。
     */
    private void assembleStep(RequestPipelineContext ctx) {
        requestBodyAssembler.assemble(ctx);
    }

    /**
     * 步骤 4【支线】：出站请求装配 —— 定下「发去哪、带什么头」，写进 ctx。
     *
     * <p>协议无关的三层头装配（下游头透传 / 鉴权头再分配 / 请求头规则）在主干，协议特定的两件事
     * （读哪一列地址 / 补哪个协议必需头，如 {@code anthropic-version}）走
     * {@link OutboundRequestAssembler} 的出站支线（按 {@code upstreamProtocol} 查表）。
     * 完整顺序与「协议头为何在规则之后」见 {@link OutboundRequestAssembler}。
     *
     * <p>放在请求体装配<strong>之后</strong>：鉴权头装配要读 {@code ctx.stream()} 选 Accept，
     * 地址解析要读回填好的 {@code provider} —— 二者此时都已就绪。产出的
     * {@code outboundHeaders} / {@code outboundBaseUrl} 交给发送后块的 {@code buildWebClient} 直接铺用。
     */
    private void assembleOutboundStep(RequestPipelineContext ctx) {
        outboundRequestAssembler.assemble(ctx);
    }
}

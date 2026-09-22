package com.kaixuan.copilot_ollama_proxy.application.pipeline;

import com.kaixuan.copilot_ollama_proxy.application.lifecycle.CallLifecycleNotifier;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchDecision;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchManager;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.application.runtime.UnresolvedModelRouteException;
import com.kaixuan.copilot_ollama_proxy.application.shared.ProtocolNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 主干 —— 三个下游端点共享的那条线的<strong>前奏段</strong>。
 *
 * <h2>它现在只做前奏，但它的终点是「整条线」</h2>
 * 本类是主干化重构（SESE Pipeline with Joining Branches）的落点。当前（3.4a/b）
 * 它只收下三个应用服务<strong>逐字相同</strong>的那一段：
 *
 * <pre>
 * resolve(model) → route == null ? UnresolvedModelRouteException
 *               → dispatch(downstreamProtocol, provider) → notifyProtocols
 * </pre>
 *
 * <p>后面几段（翻译插槽、执行器查表、响应翻译插槽）会随 3.4c/3.4d 陆续搬进来。
 * 之所以先把前奏独立出来，是因为那是<strong>唯一现在就能零风险搬走</strong>的一段 ——
 * 它不碰执行器、不碰 ctx 的生命周期，搬完「6 份骨架 → 1 份」立刻成立。
 *
 * <h2>为何只有前奏能现在搬（见 plan_ Step 3.4「实施时发现的依赖」）</h2>
 * 三个服务的<strong>直连分支逐字相同，只差「调哪个执行器」</strong>。
 * 主干要 own 分支，就必须能选执行器 —— 那是 3.4d。同理「ctx 创建上移到端点」
 * 也要等翻译分支的 ctx 有来源（3.4c）。因此本步只 own 前奏，
 * <strong>ctx 创建与分支仍留在各自 Service</strong>。
 *
 * <h2>方法是同步的，异常是抛出的</h2>
 * 三个调用方都把它包在 {@code Mono.defer} / {@code Flux.defer} 里，因此同步抛出
 * 会自然变成 {@code onError} 信号 —— 与原先各 Service 里 {@code return Mono.error(...)}
 * 逐字等价。这个等价性是「零行为变更」的一部分，不是风格选择：
 * 若某个调用方不在 defer 内，同步抛出会逃出组装期，{@code onErrorResume} 就不在链上了。
 *
 * <p>本方法<strong>无 I/O</strong>（只读本地目录 + 纯决策），因此不需要
 * {@code subscribeOn(Schedulers.boundedElastic())} —— 那条纪律针对的是 JDBC 调用。
 *
 * <h2>日志归属的一处变化</h2>
 * {@link ProtocolNotifier} 的失败日志（debug 级，仅观测链路自身失败时打印）
 * 在搬迁前以各 Service 名打印，搬迁后以本类名打印。
 * 这是本步<strong>唯一可观测的差异</strong>，只影响日志来源类名、不影响功能。
 */
@Service
public class RequestPipeline {

    private static final Logger log = LoggerFactory.getLogger(RequestPipeline.class);

    private final ProviderRouteResolver providerRouteResolver;
    private final ProtocolDispatchManager protocolDispatchManager;

    /**
     * 调用生命周期事件通知器，由 Spring 可选注入。
     *
     * <p>可选注入（与 provider 层同一范式）—— 单元测试直接 new 本类时不关心这条链路，
     * 缺省即不发。它随 {@code notifyProtocols} 一起从三个 Service 上移到这里。
     */
    private CallLifecycleNotifier lifecycleNotifier;

    public RequestPipeline(ProviderRouteResolver providerRouteResolver,
                           ProtocolDispatchManager protocolDispatchManager) {
        this.providerRouteResolver = providerRouteResolver;
        this.protocolDispatchManager = protocolDispatchManager;
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
}

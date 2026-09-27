package com.kaixuan.copilot_ollama_proxy.pipeline;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 主干 —— 三个下游端点共享的那条处理线的<strong>唯一入口</strong>。
 *
 * <h2>它的工序只有两行</h2>
 * <pre>
 * beforeSend.process(ctx);        // 发送前块：一步步改 ctx（同步）
 * return afterSend.process(ctx);  // 发送后块：返回 Flux（异步状态机）
 * </pre>
 * 以「真正发出 HTTP」为界，主干被拆成两个<strong>功能块</strong>：
 * <ul>
 *   <li>{@link BeforeSend} —— 发送前：路由 + 协议调度 + 请求翻译（支线）+ 请求体装配。
 *       每一步都是「拿到 ctx → 改 ctx → 返回」的同步 {@code void step(ctx)}，
 *       因为数据当场就在；</li>
 *   <li>{@link AfterSend} —— 发送后：选执行器 + send 插槽 + 回程翻译插槽。
 *       它返回 {@code Flux<UpstreamEvent>}（一份「数据将来怎么来」的说明书），
 *       内部是异步状态机（{@code defer → gate → retryWhen → …}），<strong>不能</strong>
 *       写成 {@code void step(ctx)}。</li>
 * </ul>
 *
 * <h2>为什么这条缝在这里</h2>
 * 它是<strong>同步 / 异步的本质分界</strong>，不是写法选择：发送前的一切是就地改状态，
 * 发送后是「发起 I/O、数据未来才到、中途失败要重发」。哪怕从零重写，只要还要
 * 流式 + 重试 + 空响应兜底，发送后那段仍是状态机。本类把这条缝显式化 ——
 * 读主流程只需看这两行，「看不懂的高级写法」全关在 {@link AfterSend} 内部。
 *
 * <h2>本类为何还在（没被两个块取代）</h2>
 * 它是<strong>端点服务唯一认识的入口</strong>：三个 Service 只依赖 {@code RequestPipeline}，
 * 不直接碰两个块。保留这层让「主干只有一个门」这件事在类型上成立，且端点服务无需改动。
 *
 * <h2>方法体同步、异常抛出（defer 由端点负责）</h2>
 * {@code beforeSend.process} 同步抛异常（路由未解析 / 供应商无协议 / 翻译未实现）；
 * 端点服务把 {@code execute} 包在 {@code Mono/Flux.defer} 里，同步抛出因此变成
 * {@code onError} 信号。若某个调用方不在 defer 内，异常会逃出组装期、
 * {@code onErrorResume} 不在链上（{@code ChatDispatchErrorSignalTests} 钉住这一点）。
 *
 * <h2>它为什么不是「路由 + 调度 + 发送」全写在一起</h2>
 * 那些事在职责上分属两个<strong>形态不同的</strong>半段（同步改状态 / 异步状态机），
 * 放在一个类里会让「哪一半在改数据、哪一半在编排数据流」看不出来。
 * 拆开后本类退回「组合两个块」的门面；路由与调度结论<strong>就地写进 ctx</strong>
 * （{@code ctx.applyRouting(...)}），不需要一个返回值把结论交出来 ——
 * 因此也不存在旧设计里的 {@code PipelinePreamble} 那种前奏返回值类型。
 */
@Service
public class RequestPipeline {

    private final BeforeSend beforeSend;
    private final AfterSend afterSend;

    public RequestPipeline(BeforeSend beforeSend, AfterSend afterSend) {
        this.beforeSend = beforeSend;
        this.afterSend = afterSend;
    }

    /**
     * 跑完整条主干 —— 发送前块（改 ctx）→ 发送后块（返回事件流）。
     *
     * @param ctx 端点建好的上下文（{@code forEndpoint}）；发送前块会逐步填充它
     * @return 统一形态的上游事件流（非流式即「恰有一个元素的流」；
     *         跨协议且回程已接时，已被翻译回下游形态）
     */
    public Flux<UpstreamEvent> execute(RequestPipelineContext ctx) {
        beforeSend.process(ctx);
        return afterSend.process(ctx);
    }
}


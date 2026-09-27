package com.kaixuan.copilot_ollama_proxy.pipeline;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.ResponseProtocolTranslator;
import com.kaixuan.copilot_ollama_proxy.protocol.ChunkLogPayload;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.pipeline.after.send.UpstreamExecutor;
import com.kaixuan.copilot_ollama_proxy.pipeline.after.send.UpstreamExecutorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * <strong>发送后块</strong> —— 主干「真正发出 HTTP 之后」那一段。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：主干<strong>功能块</strong>（发送后） · 位置：{@code pipeline/after/}
 * <p>{@link RequestPipeline#execute} 的第二行 {@code return afterSend.process(ctx)} 就是它。
 *
 * <h2>为什么它<strong>不能</strong>是「一步步改 ctx」的形态</h2>
 * 与发送前块（{@code BeforeSend}）相反：这里做的事是「发起异步 I/O，数据未来才陆续到、
 * 中途失败要重发、流动时要逐帧判」。函数在数据到达<strong>之前</strong>就返回一个
 * {@code Flux<UpstreamEvent>} —— 那是「一份关于数据将来怎么来的说明书」，不是坐在 ctx 里的数据。
 * 因此本块<strong>返回 Flux</strong>，而非 {@code void step(ctx)}。这是同步/异步的本质分界。
 *
 * <h2>它是异步状态机的「门面」，内部不拆步</h2>
 * 真正的状态机（{@code defer → gate → retryWhen → 耗尽放行 → 静默重发 → doFinally}）
 * 由执行器 + {@code UpstreamCallRunner} 承载。本块只做三件<strong>编排</strong>：
 * 选执行器（查表）→ 调 send 插槽 → 回程翻译插槽。
 * <strong>绝不把 {@code retryWhen}/gate 拆成 {@code void step(ctx)}</strong> —— 那会破坏流式与重试。
 *
 * <h2>两态分歧只在这里读一次 {@code ctx.stream()}</h2>
 * send 阶段选 {@code invoke}（非流式，一次取全）/ {@code invokeStream}（流式，逐事件）；
 * 回程阶段选 {@code translateResponse}（收 Mono）/ {@code translateStream}（Flux→Flux 状态机）。
 * 这是两态在主干上仅剩的两处真本质。除此之外主干对两态无感。
 *
 * <h2>回程翻译器从 ctx 读</h2>
 * 它由发送前块按协议对查表选好、记进 ctx（{@code ctx.responseTranslator()}）。本块不再
 * 自己查表 —— 「选策略」是发送前块的职责，本块只「用策略」。直连或回程未实现时它为 null，
 * 本块透传上游原生响应并留痕（见 {@link #warnIfHalfRound}）。
 */
@Service
public class AfterSend {

    private static final Logger log = LoggerFactory.getLogger(AfterSend.class);

    private final UpstreamExecutorRegistry executorRegistry;

    public AfterSend(UpstreamExecutorRegistry executorRegistry) {
        this.executorRegistry = executorRegistry;
    }

    /**
     * 跑完发送后的编排 —— 返回统一形态的上游事件流。
     *
     * @param ctx 发送前块已填好的上下文（路由、body、翻译对均已就绪）
     * @return 统一形态的上游事件流（非流式即「恰有一个元素的流」；
     *         跨协议且回程已接时，已被翻译回下游形态）
     */
    public Flux<UpstreamEvent> process(RequestPipelineContext ctx) {
        ResponseProtocolTranslator responseTranslator = ctx.responseTranslator();
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
     * 半轮实现态留痕 —— 透传本身可接受，但<strong>不能静默</strong>。
     *
     * <h2>为何是 warn 而非 debug</h2>
     * 它标记的是<strong>下游正在收到未翻译的上游响应</strong>，是一个「有人应该看见」的事实。
     * 同一个坑（流挂住、界面转圈而无报错）已踩过一次，因此这里必须响 ——
     * warn 平时不淹没日志、出现时一眼可见。前端侧的可见性由 {@code notifyProtocols} 承载
     * （Toast 显示 {@code C→M} 标记）。
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

package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslator;
import com.kaixuan.copilot_ollama_proxy.application.protocol.TranslationContext;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 一条翻译链的<strong>回程</strong>（响应/事件流：上游协议 → 下游协议）。
 *
 * <h2>为何回程的方法比去程多</h2>
 * 回程要覆盖三种消费场景，形状互不相同，但都属于<strong>回程这一个方向</strong>，
 * 因此归在同一个子接口里是「贴合一个方向的真实形状」而非跨方向的最小公倍数
 * （{@link ProtocolTranslator} 反对的是后者）：
 * <ul>
 *   <li>{@link #translateResponse} —— 非流式，{@code Mono -> Mono}；</li>
 *   <li>{@link #translateStream} —— 流式，<strong>帧数不对等</strong>
 *       （一个上游事件可能产 0 / 1 / 多帧），需要一个跨事件状态机；</li>
 *   <li>{@link #translateChunksForLog} —— 落库用的批量重译，产出下游实际收到的
 *       chunk 序列 + 逐事件产帧数，供日志页两栏对齐。</li>
 * </ul>
 *
 * <h2>它买到的东西：去程/回程独立缺省</h2>
 * 编排层按 {@link TranslationRoute}「(下游, 上游)」查<strong>回程表</strong> ——
 * 与去程表各查各的。<strong>回程未命中即原样透传上游响应</strong>：这正是开发者写新方向
 * 时的常态中间态（去程已接、回程未接，用真实上游验证请求是否被接受）。
 * 「未实现」因此是「没有这个方向的 {@code @Component} 实现」，不是 bool 开关。
 * 见方向文档 §2.3.2。
 *
 * <h2>透传不可静默</h2>
 * 回程未命中而透传本身可接受，但<strong>不能静默</strong> —— 同一个坑（流挂住、
 * 界面转圈而无报错）已经踩过一次。编排层在透传前必须留痕（日志 + 已有的
 * {@code notifyProtocols} 路径标记），让开发时看得见「正在看的是原生上游响应」。
 *
 * <h2>都必须在重试边界之外</h2>
 * 空响应判定与落库用的是上游<strong>原生</strong>形态。若翻译发生在 {@code retryWhen}
 * 内侧，内容检测器看到的是合成出来的下游 chunk，会把每一轮都判成空并耗尽预算。
 *
 * @see ProtocolTranslator
 * @see RequestProtocolTranslator
 */
public interface ResponseProtocolTranslator extends ProtocolTranslator {

    /**
     * 翻译非流式响应。
     *
     * <p>签名与 {@code MessagesToChatResponseTranslator.translateResponse} 逐字一致。
     *
     * @param upstreamBody 上游原始响应体（统一形态）
     * @return 翻译回下游协议的响应体（统一形态）
     */
    Mono<UpstreamEvent> translateResponse(Mono<UpstreamEvent> upstreamBody);

    /**
     * 翻译流式响应。
     *
     * <p>签名与 {@code MessagesToChatResponseTranslator.translateStream} 逐字一致。
     *
     * @param upstreamEvents 上游事件流（统一形态）
     * @param upstreamModel  上游真实模型名（不含供应商前缀），作为占位值
     * @param context        请求期上下文，提供 {@code include_usage}
     * @return 翻译回下游协议的 chunk 流（统一形态）
     */
    Flux<UpstreamEvent> translateStream(Flux<UpstreamEvent> upstreamEvents, String upstreamModel,
                                        TranslationContext context);

    /**
     * 把一整轮上游事件批量翻译成下游 chunk，供<strong>落库</strong>使用。
     *
     * <p>签名与 {@code MessagesToChatResponseTranslator.translateChunksForLog} 逐字一致。
     *
     * @param upstreamEvents 该轮完整的上游事件列表
     * @param upstreamModel  上游模型名，作为占位值
     * @param includeUsage   与出站保持一致，否则日志里的帧数与实际下发不符
     * @return 翻译结果与逐事件产帧数
     */
    TranslatedChunkLog translateChunksForLog(List<String> upstreamEvents, String upstreamModel,
                                             boolean includeUsage);
}

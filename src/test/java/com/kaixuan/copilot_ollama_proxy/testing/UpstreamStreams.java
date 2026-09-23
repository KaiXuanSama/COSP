package com.kaixuan.copilot_ollama_proxy.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.upstream.UpstreamEventClassifier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 测试用的统一形态事件流构造器。
 *
 * <h2>它解决什么问题</h2>
 * 上游执行器的流式接口改为 {@code Flux<UpstreamEvent>} 之后，
 * 桩数据从 {@code Flux.just("{...}")} 变成 {@code Flux.just(UpstreamEvent.body("{...}"))} ——
 * 而写桩的人必须自己判断<strong>哪一帧是终止标记</strong>。
 *
 * <p>那个判断恰恰是本步要<strong>从调用方拿走</strong>的东西：它依赖协议知识，
 * 而写成字面量后，一旦某帧被错标成 {@code body}，控制器就不会触发收尾 ——
 * 症状是「流结束了但 Toast 不消失」，而测试桩本身看不出问题。
 *
 * <p>因此让写桩的人只声明<strong>协议</strong>（那是测试真正知道的事），
 * 分类交给生产代码里的同一份 {@link UpstreamEventClassifier} ——
 * 测试与生产用同一套判据，桩就不可能标错。
 *
 * <h2>为何不放在生产包</h2>
 * 它只服务测试。放在 {@code testing} 包里可以明确它不参与运行时。
 */
public final class UpstreamStreams {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UpstreamStreams() {
    }

    /** Chat 协议的流：{@code [DONE]} 会被自动识别为终止标记。 */
    public static Flux<UpstreamEvent> chat(String... frames) {
        return of(WireProtocol.CHAT, frames);
    }

    /** Anthropic 协议的流：{@code message_stop} 会被自动识别为终止标记。 */
    public static Flux<UpstreamEvent> messages(String... frames) {
        return of(WireProtocol.MESSAGES, frames);
    }

    /** Responses 协议的流：终态事件会被自动识别为终止标记。 */
    public static Flux<UpstreamEvent> responses(String... frames) {
        return of(WireProtocol.RESPONSES, frames);
    }

    /**
     * 按指定协议构造事件流。
     *
     * @param protocol 这一批帧所属的协议
     * @param frames   原始报文，顺序即到达顺序
     */
    public static Flux<UpstreamEvent> of(WireProtocol protocol, String... frames) {
        return Flux.fromArray(frames)
                .map(frame -> UpstreamEventClassifier.classify(MAPPER, protocol, frame));
    }

    /** 单帧载荷，用于只关心「有内容」的桩。 */
    public static UpstreamEvent body(String data) {
        return UpstreamEvent.body(data);
    }

    /**
     * 非流式响应体：包成统一形态的<strong>单元素</strong>流。
     *
     * <p>它与上面三个流式入口的区别<strong>只在元素个数</strong> ——
     * 那正是本步要统一的东西：非流式在本形态下就是「恰有一个元素的流」。
     *
     * <p>恒为 {@link UpstreamEvent.Body}，不走分类器：非流式的响应体里
     * 不存在协议级终止标记（三个协议都是），因此不需要、也不可能判出 Terminal。
     */
    public static Mono<UpstreamEvent> single(String body) {
        return Mono.just(UpstreamEvent.body(body));
    }

    /** 单个终止标记，用于只关心「流结束」的桩。 */
    public static UpstreamEvent terminal(String data) {
        return UpstreamEvent.terminal(data);
    }
}

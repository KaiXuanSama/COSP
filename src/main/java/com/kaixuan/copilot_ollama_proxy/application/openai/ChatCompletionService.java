package com.kaixuan.copilot_ollama_proxy.application.openai;

import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipeline;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.NoSupportedProtocolException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamEvent;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 聊天补全应用服务 —— 服务下游的 OpenAI 协议端点。
 *
 * <h2>它已经退化成「端点声明 + 建 ctx + 交主干」（3.4c-2）</h2>
 * 本类此前的全部编排（路由解析、协议调度、协议分派、翻译两插槽的接线）
 * 都已收进 {@link RequestPipeline}。现在它与 {@code MessagesService} /
 * {@code ResponsesService} <strong>同形</strong>，唯一差别是
 * {@link #DOWNSTREAM_PROTOCOL} 的值。
 *
 * <p>这正是「三线合一」的落点：主干唯一，「下游端点」是三个 Service 各自的身份。
 *
 * <h2>为何两个方法体都裹在 defer 里</h2>
 * 主干的准备与决策都是<strong>同步</strong>调用，且都会抛异常
 * （{@link NoSupportedProtocolException}、{@link RequestTranslationException}）。
 * 控制器那侧的 {@code Mono.firstWithSignal(chatCompletion(...), cancelSignal)}
 * 参数是 eager 求值的：若不包 defer，异常在 Mono <strong>组装期</strong>就抛出了
 * 控制器方法，{@code onErrorResume} 根本不在链上 —— 下游拿到的是 WebFlux 默认
 * 500 与通用错误体，那些带字段路径的消息一个字都到不了对端。
 * 流式同理，且更隐蔽：状态码还未提交，因此发出去的不是 SSE error 帧而是 500 JSON。
 */
@Service
public class ChatCompletionService {

    /** 本服务服务的下游端点协议，固定不变。 */
    private static final WireProtocol DOWNSTREAM_PROTOCOL = WireProtocol.CHAT;

    private final RequestPipeline requestPipeline;

    /**
     * 创建聊天补全应用服务。
     *
     * @param requestPipeline 主干 —— 它 own 整条线（前奏 + 翻译两插槽 + 执行器查表 + 回程）
     */
    public ChatCompletionService(RequestPipeline requestPipeline) {
        this.requestPipeline = requestPipeline;
    }

    /**
     * 执行非流式聊天补全。
     *
     * @param openAiRequest OpenAI 格式请求体
     * @param model 模型名称
     * @return 上游原始 OpenAI 响应
     */
    public Mono<UpstreamEvent> chatCompletion(Map<String, Object> openAiRequest, String model,
                                              HttpHeaders downstreamHeaders, String requestId) {
        // defer 把主干各同步步骤的异常转成 onError 信号，控制器才能分类处置。理由见类注释。
        // 主干只有一个入口（返回统一的事件流）；非流式是「恰有一个元素的流」，
        // 故在**出口**收成 Mono —— 与上游形态的适配只发生在端点边界。
        return Mono.defer(() -> requestPipeline.execute(RequestPipelineContext.forEndpoint(
                openAiRequest, model, DOWNSTREAM_PROTOCOL, downstreamHeaders, requestId, false)).single());
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
        return Flux.defer(() -> requestPipeline.execute(RequestPipelineContext.forEndpoint(
                openAiRequest, model, DOWNSTREAM_PROTOCOL, downstreamHeaders, requestId, true)));
    }

    /**
     * 执行不含下游请求头上下文的流式聊天补全。
     * 仅供内部兼容调用与单元测试使用；HTTP API 必须调用带 downstreamHeaders 的重载。
     */
    public Flux<UpstreamEvent> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
        return chatCompletionStream(openAiRequest, model, HttpHeaders.EMPTY, null);
    }
}
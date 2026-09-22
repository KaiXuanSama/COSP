package com.kaixuan.copilot_ollama_proxy.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.PipelineStep;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ChunkStageRegistry;
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamExecutor;
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamExecutorRegistry;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ContentDetectorRegistry;
import com.kaixuan.copilot_ollama_proxy.provider.stage.RequestBodyStageRegistry;
import com.kaixuan.copilot_ollama_proxy.provider.stage.chat.ChatChunkNormalizeStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.chat.ChatContentDetectorStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.chat.ChatReasoningFallbackStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.messages.MessagesContentDetectorStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.messages.MessagesMaxTokensStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.messages.MessagesSystemPromptStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.messages.MessagesThinkingStage;
import com.kaixuan.copilot_ollama_proxy.provider.stage.responses.ResponsesContentDetectorStage;
import org.springframework.http.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用的管道上下文构造器 —— 把「哪种链路」声明出来，而不是逐个登记。
 *
 * <h2>它解决什么问题</h2>
 * 上游执行器的流式/非流式入口收 {@code RequestPipelineContext} 之后，
 * 写测试的人要自己拼出上下文并登记步骤 —— 而**登错一个步骤就会让空响应拦截的判据翻转**，
 * 症状是「某个用例重试次数对不上」，排查会先怀疑重试逻辑。
 *
 * <p>三种链路是有限的、且各有名字，因此让写测试的人只声明**是哪一种**：
 * <ul>
 *   <li>{@link #direct} —— 两侧同协议，无翻译（拦截照常）；</li>
 *   <li>{@link #halfRound} —— 去程已接、回程未接（半轮实现态，拦截跳过）；</li>
 *   <li>{@link #fullyTranslated} —— 两半都已接（C2M 现状，拦截照常）。</li>
 * </ul>
 *
 * <h2>为何放在 testing 包</h2>
 * 与 {@link UpstreamStreams} 同一取向：它只服务测试，放在这里明确它不参与运行时。
 * 生产路径的上下文由**编排层**在组装期构造（那里才知道路由与调度结论），
 * 本类只是替测试把同样的形状拼出来。
 */
public final class PipelineContexts {

    private PipelineContexts() {
    }

    /**
     * 直连：两侧同协议、无翻译步骤。空响应拦截照常介入。
     *
     * @param stream 本次调用是否流式（直传进 ctx，执行器据此选机制）
     */
    public static RequestPipelineContext direct(Map<String, Object> body,
                                                ProviderRuntimeConfiguration provider,
                                                WireProtocol protocol,
                                                boolean stream) {
        return RequestPipelineContext.of(copyOf(body), modelOf(body), protocol, protocol,
                provider, HttpHeaders.EMPTY, null, null, stream);
    }

    /** 半轮实现态：跨协议且<strong>只登记了去程</strong> —— 拦截跳过。 */
    public static RequestPipelineContext halfRound(Map<String, Object> body,
                                                  ProviderRuntimeConfiguration provider,
                                                  WireProtocol downstream, WireProtocol upstream,
                                                  boolean stream) {
        RequestPipelineContext ctx = RequestPipelineContext.of(copyOf(body), modelOf(body), downstream, upstream,
                provider, HttpHeaders.EMPTY, null, null, stream);
        ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);
        return ctx;
    }

    /** 全实现翻译：跨协议且去程与回程都已登记 —— 拦截照常。 */
    public static RequestPipelineContext fullyTranslated(Map<String, Object> body,
                                                        ProviderRuntimeConfiguration provider,
                                                        WireProtocol downstream, WireProtocol upstream,
                                                        boolean stream) {
        RequestPipelineContext ctx = RequestPipelineContext.of(copyOf(body), modelOf(body), downstream, upstream,
                provider, HttpHeaders.EMPTY, null, null, stream);
        ctx.markCompleted(PipelineStep.REQUEST_TRANSLATION);
        ctx.markCompleted(PipelineStep.RESPONSE_TRANSLATION);
        return ctx;
    }

    /**
     * 从请求体里取模型名 —— 与生产路径一致（控制器把下游传来的 model 写进 body）。
     *
     * <p>测试构造的请求体未必带 {@code model}（如 Anthropic 侧只有 {@code messages}），
     * 那时回退到占位名。执行器入口用 {@code ctx.model()}，而本辅助类只服务那些
     * <strong>不关心 model</strong> 的用例（它们验的是重试/拦截/落库），故占位名足够。
     */
    private static String modelOf(Map<String, Object> body) {
        Object model = body.get("model");
        return model instanceof String value && !value.isBlank() ? value : "test-model";
    }

    /**
     * 复制请求体。
     *
     * <p>上下文会**改写** body（各阶段往里补字段），因此不能把调用方持有的 Map
     * 直接交给它 —— 那会让一次断言失败污染后续用例。与生产路径一致：
     * 编排层交给执行器的也是原始请求体的副本。
     */
    private static Map<String, Object> copyOf(Map<String, Object> body) {
        return new LinkedHashMap<>(body);
    }

    /**
     * 拼一个只含 MESSAGES 请求体支线的注册表 —— 供直接 {@code new} 执行器的测试使用。
     *
     * <h2>为何测试要自己拼，而不是从容器取</h2>
     * 7 个测试子类直接 {@code new} 执行器、不走 Spring（这样能把退避压到毫秒级、
     * 并能用 {@code ExchangeFunction} 打桩上游）。因此它们拿不到集合注入建的注册表。
     *
     * <p><strong>刻意不提供「无注入时安全跳过」的路径</strong>：那会让「装配漏了」
     * 与「该协议没这个步骤」在行为上无从区分 —— 于是漏接线时测试照样全绿，
     * 而生产上少了三步。这里让测试**显式拼出**它需要的那些支线，
     * 于是「用到了哪些支线」在测试里是看得见的。
     *
     * <p>拼的是<strong>真实实现</strong>（{@code Messages*Stage}）而非替身：
     * 替身会让这些测试验的东西与生产不同，而它们本就想验生产行为。
     *
     * @param objectMapper 传给需要它的支线实现
     */
    public static RequestBodyStageRegistry registryWithMessagesStages(ObjectMapper objectMapper) {
        return new RequestBodyStageRegistry(
                List.of(new MessagesSystemPromptStage()),
                List.of(new MessagesMaxTokensStage(objectMapper)),
                List.of(new MessagesThinkingStage(objectMapper)));
    }

    /**
     * 拼一个只含 CHAT 流式 chunk 支线的注册表 —— 供直接 {@code new} 执行器的测试使用。
     *
     * <p>理由同 {@link #registryWithMessagesStages}：不走 Spring 的测试拿不到集合注入，
     * 因此显式拼出它需要的支线。拼的是<strong>真实实现</strong>而非替身 ——
     * 替身会让测试验的东西与生产不同。
     *
     * @param objectMapper 传给需要它的支线实现
     */
    public static ChunkStageRegistry registryWithChatChunkStages(ObjectMapper objectMapper) {
        return new ChunkStageRegistry(
                List.of(new ChatChunkNormalizeStage(objectMapper)),
                List.of(new ChatReasoningFallbackStage(objectMapper)));
    }

    /**
     * 拼一个含指定执行器的注册表 —— 供直接 {@code new} 主干的测试使用。
     *
     * <p>理由同 {@link #registryWithMessagesStages}：不走 Spring 的测试拿不到集合注入。
     * 与那两个不同的是，这里收的是**调用方传进来的**执行器实例 ——
     * 主干测试常用 mock 的执行器（它们自己 stub {@code invoke}），
     * 拼真实现反而验不到被测行为。
     *
     * <p><strong>注意</strong>：传给本方法的 mock 必须已 stub {@code protocol()}，
     * 否则注册表建索引时拿到 null 键，查表恒未命中。
     *
     * @param executors 本次测试要装配的执行器
     */
    public static UpstreamExecutorRegistry executorRegistry(UpstreamExecutor... executors) {
        return new UpstreamExecutorRegistry(List.of(executors));
    }

    /**
     * 拼一个含三个协议检测器的注册表 —— 供直接 {@code new} 执行器的测试使用。
     *
     * <p>理由同 {@link #registryWithChatChunkStages}：不走 Spring 的测试拿不到集合注入。
     * 三个都要给：检测器表的<strong>未命中是报错</strong>（不是跳过），
     * 缺一个会让那条线路在构造期就抛 {@code IllegalStateException}。
     *
     * <p>拼的是真实实现而非替身 —— 替身会让这些测试验的东西与生产不同。
     *
     * @param objectMapper 传给需要它的检测器实现
     */
    public static ContentDetectorRegistry contentDetectorRegistry(ObjectMapper objectMapper) {
        return new ContentDetectorRegistry(List.of(
                new ChatContentDetectorStage(objectMapper),
                new MessagesContentDetectorStage(objectMapper),
                new ResponsesContentDetectorStage(objectMapper)));
    }
}

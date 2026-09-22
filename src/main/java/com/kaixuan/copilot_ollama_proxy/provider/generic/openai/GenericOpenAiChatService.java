package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.provider.AbstractUpstreamChatService;
import com.kaixuan.copilot_ollama_proxy.provider.ChunkLogPayload;
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamExecutor;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ChunkStageRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 通用 OpenAI 上游服务 —— 处理所有数据库供应商配置。
 * 从数据库动态读取配置，复用父类的请求准备、SSE 解析、日志和流式翻译基础设施。
 * <p>
 * 请求头和请求体规则均从 provider_request_transform 读取。
 */
@Service
public class GenericOpenAiChatService extends AbstractUpstreamChatService implements UpstreamExecutor {

    private final RequestBodyRuleEngine requestBodyRuleEngine;

    public GenericOpenAiChatService(ObjectMapper objectMapper,
                                    ProviderRequestHeaderService providerRequestHeaderService,
                                    RequestBodyRuleEngine requestBodyRuleEngine,
                                    ChunkStageRegistry chunkStageRegistry) {
        super(objectMapper, providerRequestHeaderService, chunkStageRegistry);
        this.requestBodyRuleEngine = requestBodyRuleEngine;
    }

    @Override
    protected String defaultBaseUrl() {
        return "";
    }

    // ==================== 主干 send 插槽（3.4d-1，委派给原有方法） ====================

    /** 本执行器服务的上游协议 —— <strong>查表键</strong>。 */
    @Override
    public WireProtocol protocol() {
        return WireProtocol.CHAT;
    }

    /**
     * 主干入口（非流式）—— 委派给原有方法。
     *
     * <p><strong>3.4d-1 是纯加法</strong>：本方法不改变任何行为，只把「旧签名里的 5 个参数」
     * 改从 ctx 取（它们本就是 ctx 的字段），以便 3.4d-2 切换调用点时主干只需传 ctx。
     * OpenAI 直连无落库改写，故 {@code chunkRewriter} 被忽略。
     */
    @Override
    public Mono<UpstreamEvent> invoke(RequestPipelineContext ctx,
                                      Function<List<String>, ChunkLogPayload> chunkRewriter) {
        return super.chatCompletion(ctx.body(), ctx.model(), ctx.provider(), ctx.downstreamHeaders(),
                ctx.requestId(), ctx);
    }

    /** 主干入口（流式）—— 委派给原有方法，理由同 {@link #invoke}。 */
    @Override
    public Flux<UpstreamEvent> invokeStream(RequestPipelineContext ctx,
                                            Function<List<String>, ChunkLogPayload> chunkRewriter) {
        return super.chatCompletionStream(ctx.body(), ctx.model(), ctx.provider(), ctx.downstreamHeaders(),
                ctx.requestId(), ctx);
    }

    @Override
    protected String chatCompletionsUri() {
        return "/chat/completions";
    }

    /**
     * 根据 body_rules_json 对请求体进行动态转换。
     *
     * 只执行声明适用于 {@link WireProtocol#CHAT} 的规则组；协议筛选由引擎完成。
     */
    @Override
    protected void customizeRequestBody(Map<String, Object> body, String resolvedModel,
                                        ProviderRuntimeConfiguration provider) {
        RequestBodyRuleEngine.TransformResult result = requestBodyRuleEngine.transform(
                body, provider.bodyRulesJson(), WireProtocol.CHAT);
        body.clear();
        body.putAll(result.output());
        for (RequestBodyRuleEngine.TransformWarning warning : result.warnings()) {
            log.warn("请求体规则已跳过: ruleId={}, path={}, message={}",
                    warning.ruleId(), warning.fieldPath(), warning.message());
        }
    }

    /**
     * 执行一次非流式聊天补全。
     *
     * @param openAiRequest 原始 OpenAI 请求体
     * @param route 应用层解析出的供应商模型路由
     * @param requestId 本次调用唯一标识，用于透传生命周期事件
     * @param ctx 本次请求的管道上下文，由编排层在组装期填好
     * @return 统一形态的上游响应（单个 {@link UpstreamEvent.Body}）
     */
    public Mono<UpstreamEvent> chatCompletion(Map<String, Object> openAiRequest, ResolvedProviderRoute route,
                                              HttpHeaders downstreamHeaders, String requestId,
                                              RequestPipelineContext ctx) {
        return super.chatCompletion(openAiRequest, route.model(), route.provider(), downstreamHeaders, requestId,
                ctx);
    }

    /**
     * 执行一次流式聊天补全。
     *
     * @param openAiRequest 原始 OpenAI 请求体
     * @param route 应用层解析出的供应商模型路由
     * @param requestId 本次调用唯一标识，用于透传生命周期事件
     * @param ctx 本次请求的管道上下文，由编排层在组装期填好
     * @return 统一形态的上游事件流
     */
    public Flux<UpstreamEvent> chatCompletionStream(Map<String, Object> openAiRequest, ResolvedProviderRoute route,
                                                     HttpHeaders downstreamHeaders, String requestId,
                                                     RequestPipelineContext ctx) {
        return super.chatCompletionStream(openAiRequest, route.model(), route.provider(), downstreamHeaders,
                requestId, ctx);
    }
}

package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.RequestPipelineContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.provider.AbstractUpstreamChatService;
import com.kaixuan.copilot_ollama_proxy.provider.ChunkLogPayload;
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.provider.UpstreamExecutor;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ChunkStageRegistry;
import com.kaixuan.copilot_ollama_proxy.provider.stage.ContentDetectorRegistry;
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
                                    ChunkStageRegistry chunkStageRegistry,
                                    ContentDetectorRegistry contentDetectorRegistry) {
        super(objectMapper, providerRequestHeaderService, chunkStageRegistry, contentDetectorRegistry);
        this.requestBodyRuleEngine = requestBodyRuleEngine;
    }

    @Override
    protected String defaultBaseUrl() {
        return "";
    }

    // ==================== 主干 send 插槽 ====================

    /** 本执行器服务的上游协议 —— <strong>查表键</strong>。 */
    @Override
    public WireProtocol protocol() {
        return WireProtocol.CHAT;
    }

    /**
     * 主干 send 插槽（非流式）。
     *
     * <p>参数全部来自 ctx —— 旧签名里的 {@code request} / {@code provider} / {@code headers} /
     * {@code requestId} / {@code model} 本就都是 ctx 字段，故不再重复传递。
     * OpenAI 直连无落库改写，故 {@code chunkRewriter} 被忽略。
     */
    @Override
    public Mono<UpstreamEvent> invoke(RequestPipelineContext ctx,
                                      Function<List<String>, ChunkLogPayload> chunkRewriter) {
        return super.chatCompletion(ctx.body(), ctx.model(), ctx.provider(), ctx.downstreamHeaders(),
                ctx.requestId(), ctx);
    }

    /** 主干 send 插槽（流式），理由同 {@link #invoke}。 */
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
}

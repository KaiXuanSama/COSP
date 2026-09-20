package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.pipeline.PipelineExecution;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.provider.AbstractUpstreamChatService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 通用 OpenAI 上游服务 —— 处理所有数据库供应商配置。
 * 从数据库动态读取配置，复用父类的请求准备、SSE 解析、日志和流式翻译基础设施。
 * <p>
 * 请求头和请求体规则均从 provider_request_transform 读取。
 */
@Service
public class GenericOpenAiChatService extends AbstractUpstreamChatService {

    private final RequestBodyRuleEngine requestBodyRuleEngine;

    public GenericOpenAiChatService(ObjectMapper objectMapper,
                                    ProviderRequestHeaderService providerRequestHeaderService,
                                    RequestBodyRuleEngine requestBodyRuleEngine) {
        super(objectMapper, "", providerRequestHeaderService);
        this.requestBodyRuleEngine = requestBodyRuleEngine;
    }

    @Override
    protected String defaultBaseUrl() {
        return "";
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
     * @return 上游返回的 OpenAI 响应
     */
    public Mono<String> chatCompletion(Map<String, Object> openAiRequest, ResolvedProviderRoute route,
                                       HttpHeaders downstreamHeaders, String requestId) {
        return super.chatCompletion(openAiRequest, route.model(), route.provider(), downstreamHeaders, requestId);
    }

    /**
     * 带<strong>管道执行登记</strong>的非流式补全。
     *
     * <p>登记决定空响应拦截是否介入 —— C2M 的调用方用它声明「去程与回程都已执行」，
     * 从而保留空响应兜底；若某天只接了去程，不登记回程即可跳过拦截。
     * 判据与理由见 {@link PipelineExecution#shouldApplyEmptyResponseGate()}。
     *
     * @param execution 本次请求的管道执行登记，由编排层在组装期填好
     */
    public Mono<String> chatCompletion(Map<String, Object> openAiRequest, ResolvedProviderRoute route,
                                       HttpHeaders downstreamHeaders, String requestId,
                                       PipelineExecution execution) {
        return super.chatCompletion(openAiRequest, route.model(), route.provider(), downstreamHeaders, requestId,
                execution);
    }

    /**
     * 执行不含下游请求头上下文的非流式聊天补全。
     * 仅供直接调用的兼容路径使用。
     */
    public Mono<String> chatCompletion(Map<String, Object> openAiRequest, ResolvedProviderRoute route) {
        return chatCompletion(openAiRequest, route, HttpHeaders.EMPTY, null);
    }

    /**
     * 执行一次流式聊天补全。
     *
     * @param openAiRequest 原始 OpenAI 请求体
     * @param route 应用层解析出的供应商模型路由
     * @param requestId 本次调用唯一标识，用于透传生命周期事件
     * @return 上游 SSE 数据块
     */
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, ResolvedProviderRoute route,
                                              HttpHeaders downstreamHeaders, String requestId) {
        return super.chatCompletionStream(openAiRequest, route.model(), route.provider(), downstreamHeaders, requestId);
    }

    /**
     * 带<strong>管道执行登记</strong>的流式补全。理由同非流式的那个重载。
     *
     * @param execution 本次请求的管道执行登记，由编排层在组装期填好
     */
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, ResolvedProviderRoute route,
                                              HttpHeaders downstreamHeaders, String requestId,
                                              PipelineExecution execution) {
        return super.chatCompletionStream(openAiRequest, route.model(), route.provider(), downstreamHeaders,
                requestId, execution);
    }

    /**
     * 执行不含下游请求头上下文的流式聊天补全。
     * 仅供直接调用的兼容路径使用。
     */
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, ResolvedProviderRoute route) {
        return chatCompletionStream(openAiRequest, route, HttpHeaders.EMPTY, null);
    }
}

package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.provider.AbstractUpstreamChatService;
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

    public GenericOpenAiChatService(ObjectMapper objectMapper, ProviderRequestHeaderService providerRequestHeaderService) {
        super(objectMapper, "", providerRequestHeaderService);
        this.requestBodyRuleEngine = new RequestBodyRuleEngine(objectMapper);
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
     * 根据新表 body_rules_json 对请求体进行动态转换。
     *
     * 旧 custom_transforms.body_transforms 在此路径中不再执行，也不会作为回退来源。
     */
    @Override
    protected void customizeRequestBody(Map<String, Object> body, String resolvedModel,
                                        ProviderRuntimeConfiguration provider) {
        RequestBodyRuleEngine.TransformResult result = requestBodyRuleEngine.transform(body, provider.bodyRulesJson());
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
     * @return 上游返回的 OpenAI 响应
     */
    public Mono<String> chatCompletion(Map<String, Object> openAiRequest, ResolvedProviderRoute route) {
        return super.chatCompletion(openAiRequest, route.model(), route.provider());
    }

    /**
     * 执行一次流式聊天补全。
     *
     * @param openAiRequest 原始 OpenAI 请求体
     * @param route 应用层解析出的供应商模型路由
     * @return 上游 SSE 数据块
     */
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, ResolvedProviderRoute route) {
        return super.chatCompletionStream(openAiRequest, route.model(), route.provider());
    }
}

package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil;
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

    private static final String PROVIDER_KEY = "__generic__";
    private final RuntimeProviderCatalog runtimeProviderCatalog;
    private final ObjectMapper objectMapper;
    private final RequestBodyRuleEngine requestBodyRuleEngine;

    /** 当前请求动态解析的 providerKey，由 chatCompletion/chatCompletionStream 设置 */
    private final ThreadLocal<String> currentProviderKey = new ThreadLocal<>();

    public GenericOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog, ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, objectMapper, "");
        this.runtimeProviderCatalog = runtimeProviderCatalog;
        this.objectMapper = objectMapper;
        this.requestBodyRuleEngine = new RequestBodyRuleEngine(objectMapper);
    }

    @Override
    public String getProviderKey() {
        return PROVIDER_KEY;
    }

    @Override
    protected String providerDisplayName() {
        return getLoggingProviderKey();
    }

    @Override
    protected String defaultBaseUrl() {
        return "";
    }

    @Override
    protected String normalizeBaseUrl(String rawBaseUrl) {
        return rawBaseUrl.replaceAll("/+$", "");
    }

    @Override
    protected void applyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
        // 先设置默认的 Bearer Token 鉴权（走接口默认实现）
        super.applyAuthenticationHeaders(headers, apiKey);
        // 再根据新表请求头规则覆写、新增或删除请求头。
        ProviderRuntimeConfiguration config = getActiveProviderConfiguration();
        if (config != null) {
            RequestTransformEngine.applyHeaderRules(headers, apiKey, config.headerRulesJson(), objectMapper);
        }
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
    protected void customizeRequestBody(Map<String, Object> body, String resolvedModel) {
        ProviderRuntimeConfiguration config = getActiveProviderConfiguration();
        if (config != null) {
            RequestBodyRuleEngine.TransformResult result = requestBodyRuleEngine.transform(body, config.bodyRulesJson());
            body.clear();
            body.putAll(result.output());
            for (RequestBodyRuleEngine.TransformWarning warning : result.warnings()) {
                log.warn("请求体规则已跳过: ruleId={}, path={}, message={}",
                        warning.ruleId(), warning.fieldPath(), warning.message());
            }
        }
    }

    /**
     * 重写配置获取，使用动态解析的 providerKey。
     * 父类的 buildWebClient() 会调用此方法获取配置。
     */
    @Override
    protected ProviderRuntimeConfiguration getActiveProviderConfiguration() {
        String key = currentProviderKey.get();
        if (key != null) {
            return runtimeProviderCatalog.getActiveProvider(key);
        }
        return null;
    }

    /**
    * 重写日志记录的供应商标识，返回实际的供应商键。
     */
    @Override
    protected String getLoggingProviderKey() {
        String key = currentProviderKey.get();
        if (key != null) {
            return key;
        }
        return getProviderKey();
    }

    /**
     * 重写非流式聊天，动态解析 provider 配置后委托给父类逻辑。
     */
    @Override
    public Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model) {
        String providerKey = resolveProviderKey(model);
        if (providerKey == null) {
            return Mono.error(new IllegalStateException("无法解析供应商: " + model));
        }
        return Mono.defer(() -> {
            currentProviderKey.set(providerKey);
            return super.chatCompletion(openAiRequest, model)
                    .doFinally(signal -> currentProviderKey.remove());
        });
    }

    /**
     * 重写流式聊天，动态解析 provider 配置后委托给父类逻辑。
     */
    @Override
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
        String providerKey = resolveProviderKey(model);
        if (providerKey == null) {
            return Flux.error(new IllegalStateException("无法解析供应商: " + model));
        }
        return Flux.defer(() -> {
            currentProviderKey.set(providerKey);
            return super.chatCompletionStream(openAiRequest, model)
                    .doFinally(signal -> currentProviderKey.remove());
        });
    }

    /**
    * 判断此服务是否能处理给定的 providerKey。
     */
    public boolean supports(String providerKey) {
        return providerKey != null && runtimeProviderCatalog.getActiveProvider(providerKey) != null;
    }

    private String resolveProviderKey(String model) {
        var parsed = ModelNameUtil.parse(model);
        if (parsed.hasProviderPrefix()) {
            String key = parsed.providerKey().toLowerCase();
            if (runtimeProviderCatalog.getActiveProvider(key) != null) {
                return key;
            }
        }
        for (var provider : runtimeProviderCatalog.getActiveProviders()) {
            if (provider.supportsModel(parsed.modelName())) {
                return provider.providerKey();
            }
        }
        return null;
    }
}

package com.kaixuan.copilot_ollama_proxy.provider.kimi.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.provider.ollama.AbstractRuntimeCatalogOllamaService;
import com.kaixuan.copilot_ollama_proxy.provider.ollama.OllamaProtocolConverter;
import com.kaixuan.copilot_ollama_proxy.provider.ollama.OllamaStreamTranslator;
import com.kaixuan.copilot_ollama_proxy.provider.openai.OpenAiTransportClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Kimi Ollama 协议实现 —— 将 Ollama 格式请求转换为 OpenAI 格式调用 Kimi API，
 * 再将 OpenAI 格式响应转换回 Ollama 格式。
 * <p>
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 * Kimi 使用完全兼容 OpenAI 的协议，支持 Bearer Token 认证。
 */
@Service
public class KimiOllamaService extends AbstractRuntimeCatalogOllamaService {

    private static final Logger log = LoggerFactory.getLogger(KimiOllamaService.class);

    private final OllamaProtocolConverter protocolConverter;
    private final OllamaProtocolConverter.Support protocolSupport;
    private final OllamaStreamTranslator streamTranslator;
    private final OpenAiTransportClient transportClient;

    public KimiOllamaService(RuntimeProviderCatalog runtimeProviderCatalog,
            @Value("${kimi.default-model:kimi-k2.5}") String fallbackDefaultModel,
            ObjectMapper objectMapper, WebClient.Builder webClientBuilder) {
        super(runtimeProviderCatalog, fallbackDefaultModel);
        this.transportClient = new OpenAiTransportClient(runtimeProviderCatalog, webClientBuilder,
                new OpenAiTransportClient.Config("kimi", "https://api.moonshot.cn/v1", "/chat/completions",
                        (headers, apiKey) -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey),
                        raw -> raw.replaceAll("/+$", "")));
        this.protocolConverter = new OllamaProtocolConverter(objectMapper, (body, ollamaReq) -> {
            // Kimi 特有字段：thinking 控制思考能力
            if (ollamaReq.getOptions() != null) {
                if (ollamaReq.getOptions().containsKey("thinking")) {
                    Object thinking = ollamaReq.getOptions().get("thinking");
                    if (thinking instanceof String s) {
                        body.put("thinking", Map.of("type", s));
                    }
                }
            }
        });
        this.protocolSupport = new OllamaProtocolConverter.Support(
                this::resolveRequestModel, this::resolveMaxTokens,
                this::extractStringContent, this::currentTimestamp);
        this.streamTranslator = new OllamaStreamTranslator(objectMapper,
                new OllamaStreamTranslator.Support(
                        this::createStreamingChunk, this::createStreamingCompletion));
    }

    // ========== 路由支持 ==========

    @Override
    public String getProviderKey() {
        return "kimi";
    }

    @Override
    protected String providerFormat() {
        return "kimi";
    }

    @Override
    protected String providerFamily() {
        return "Kimi";
    }

    @Override
    protected List<String> providerFamilies() {
        return List.of("Kimi");
    }

    @Override
    protected String providerParameterSize() {
        return "K2.5";
    }

    @Override
    protected String providerLicense() {
        return "Proprietary";
    }

    @Override
    public OllamaShowResponse showModel(String modelName) {
        String resolvedModel = resolveModelOrDefault(modelName);
        List<String> capabilities = buildCapabilitiesFromDb(resolvedModel);
        int contextLength = requireContextLength(resolvedModel);
        return buildShowResponse(resolvedModel, contextLength, capabilities);
    }

    private List<String> buildCapabilitiesFromDb(String resolvedModel) {
        List<String> caps = new ArrayList<>();
        caps.add("completion");
        try {
            var model = requireModelConfiguration(resolvedModel);
            if (model.capsTools()) {
                caps.add("tools");
            }
            if (model.capsVision()) {
                caps.add("vision");
            }
        } catch (Exception e) {
            log.warn("Kimi 模型 [{}] 能力读取失败，仅使用默认 completion 能力: {}", resolvedModel, e.getMessage());
        }
        return caps;
    }

    // ========== 非流式对话 ==========

    @Override
    public Mono<OllamaChatResponse> chat(OllamaChatRequest request) {
        if (request.isStream()) {
            return Mono.error(new UnsupportedOperationException("Use chatStream() for streaming"));
        }

        Map<String, Object> openAiRequest = convertOllamaToOpenAi(request);
        log.info("Kimi Ollama→OpenAI，模型: {}, 流式: false", openAiRequest.get("model"));

        return transportClient.sendChatCompletion(openAiRequest)
                .map(respJson -> convertOpenAiToOllama(respJson, request.getModel()));
    }

    // ========== 流式对话 ==========

    @Override
    public Flux<OllamaChatResponse> chatStream(OllamaChatRequest request) {
        Map<String, Object> openAiRequest = convertOllamaToOpenAi(request);
        openAiRequest.put("stream", true);
        log.info("Kimi Ollama→OpenAI，模型: {}, 流式: true", openAiRequest.get("model"));

        var session = streamTranslator.newSession();
        return transportClient.streamChatCompletion(openAiRequest)
                .concatMap(chunk -> Flux.fromIterable(
                        streamTranslator.translate(session, chunk, request.getModel())));
    }

    // ========== Ollama → OpenAI 请求转换 ==========

    /** Kimi Coding 端点标识，用于判断是否需要强制覆盖请求参数。 */
    private static final String CODING_ENDPOINT_MARKER = "api.kimi.com/coding";

    private Map<String, Object> convertOllamaToOpenAi(OllamaChatRequest ollamaReq) {
        Map<String, Object> body = protocolConverter.toOpenAiRequest(ollamaReq, protocolSupport);
        applyReasoningEffort(body, resolveModelOrDefault(ollamaReq.getModel()));
        // Kimi Coding 端点特殊处理：移除 temperature 和 top_p，避免触发上游限制
        if (isCodingEndpoint()) {
            body.remove("temperature");
            body.remove("top_p");
            log.debug("Kimi Coding 端点：已移除 temperature 和 top_p 字段");
        }
        return body;
    }

    /**
     * 判断当前配置的 Base URL 是否为 Kimi Coding 端点。
     */
    private boolean isCodingEndpoint() {
        var config = getProviderConfiguration();
        if (config != null && config.baseUrl() != null) {
            return config.baseUrl().contains(CODING_ENDPOINT_MARKER);
        }
        return false;
    }

    // ========== OpenAI → Ollama 响应转换（非流式） ==========

    private OllamaChatResponse convertOpenAiToOllama(String openAiJson, String requestModel) {
        try {
            return protocolConverter.toOllamaResponse(openAiJson, requestModel, protocolSupport);
        } catch (Exception e) {
            log.error("OpenAI → Ollama 转换失败", e);
            return createResponse(requestModel, true, "stop",
                    createMessage("assistant", "转换失败: " + e.getMessage()));
        }
    }

    private OllamaChatResponse createStreamingChunk(String modelName, String content) {
        return createAssistantChunk(modelName, content, false);
    }

    private OllamaChatResponse createStreamingCompletion(String modelName, String content,
            List<OllamaChatResponse.ToolCallResult> toolCalls) {
        return createAssistantCompletion(modelName,
                !toolCalls.isEmpty() ? "tool_calls" : "stop", content, toolCalls);
    }
}

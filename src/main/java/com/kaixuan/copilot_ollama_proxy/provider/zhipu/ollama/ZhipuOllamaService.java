package com.kaixuan.copilot_ollama_proxy.provider.zhipu.ollama;

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
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智谱AI Ollama 协议实现 —— 将 Ollama 格式请求转换为 OpenAI 格式调用智谱 API，
 * 再将 OpenAI 格式响应转换回 Ollama 格式。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 *
 * 智谱 API 完全兼容 OpenAI 协议，Base URL 为 https://open.bigmodel.cn/api/paas/v4/
 * 认证方式为 Bearer Token，支持流式响应和 thinking 模式。
 */
@Service
public class ZhipuOllamaService extends AbstractRuntimeCatalogOllamaService {

    private static final Logger log = LoggerFactory.getLogger(ZhipuOllamaService.class);

    private final OllamaProtocolConverter protocolConverter;
    private final OllamaProtocolConverter.Support protocolSupport;
    private final OllamaStreamTranslator streamTranslator;
    private final OpenAiTransportClient transportClient;

    public ZhipuOllamaService(
            RuntimeProviderCatalog runtimeProviderCatalog,
            @Value("${zhipu.default-model:glm-4.5-air}") String fallbackDefaultModel,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder) {
        super(runtimeProviderCatalog, fallbackDefaultModel);

        // 1. Create Transport Client
        // 智谱 API 兼容 OpenAI，用户只需填写根地址 https://open.bigmodel.cn
        // normalizeBaseUrl 会自动拼接 /api/paas/v4 后缀
        this.transportClient = new OpenAiTransportClient(
                runtimeProviderCatalog, webClientBuilder,
                new OpenAiTransportClient.Config(
                        "zhipu",
                        "https://open.bigmodel.cn",
                        "/chat/completions",
                        (headers, apiKey) -> headers.set(
                                org.springframework.http.HttpHeaders.AUTHORIZATION,
                                "Bearer " + apiKey),
                        raw -> {
                            String url = raw.replaceAll("/+$", "");
                            if (!url.endsWith("/api/paas/v4")) {
                                url = url + "/api/paas/v4";
                            }
                            return url;
                        }));

        // 2. Create Protocol Converter
        this.protocolConverter = new OllamaProtocolConverter(objectMapper);

        // 3. Create Support record with method references
        this.protocolSupport = new OllamaProtocolConverter.Support(
                this::resolveRequestModel,
                this::resolveMaxTokens,
                this::extractStringContent,
                this::currentTimestamp);

        // 4. Create Stream Translator
        this.streamTranslator = new OllamaStreamTranslator(
                objectMapper,
                new OllamaStreamTranslator.Support(
                        this::createStreamingChunk,
                        this::createStreamingCompletion));
    }

    @Override
    public String getProviderKey() {
        return "zhipu";
    }

    @Override
    protected String providerFormat() {
        return "zhipu";
    }

    @Override
    protected String providerFamily() {
        return "Zhipu";
    }

    @Override
    protected List<String> providerFamilies() {
        return List.of("Zhipu");
    }

    @Override
    protected String providerParameterSize() {
        return "Flash";
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

    /**
     * 从数据库读取模型能力声明，禁止硬编码。
     */
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
            log.warn("智谱 模型 [{}] 能力读取失败，仅使用默认 completion 能力: {}", resolvedModel, e.getMessage());
        }
        return caps;
    }

    @Override
    public Mono<OllamaChatResponse> chat(OllamaChatRequest request) {
        if (request.isStream()) {
            return Mono.error(new UnsupportedOperationException("Use chatStream() for streaming"));
        }

        Map<String, Object> openAiRequest = convertOllamaToOpenAi(request);
        log.info("智谱 Ollama→OpenAI，模型: {}, 流式: false", openAiRequest.get("model"));

        return transportClient.sendChatCompletion(openAiRequest)
                .map(respJson -> convertOpenAiToOllama(respJson, request.getModel()));
    }

    @Override
    public Flux<OllamaChatResponse> chatStream(OllamaChatRequest request) {
        Map<String, Object> openAiRequest = convertOllamaToOpenAi(request);
        openAiRequest.put("stream", true);
        log.info("智谱 Ollama→OpenAI，模型: {}, 流式: true", openAiRequest.get("model"));

        var session = streamTranslator.newSession();
        return transportClient.streamChatCompletion(openAiRequest)
                .concatMap(chunk -> Flux.fromIterable(
                        streamTranslator.translate(session, chunk, request.getModel())));
    }

    private Map<String, Object> convertOllamaToOpenAi(OllamaChatRequest ollamaReq) {
        Map<String, Object> body = protocolConverter.toOpenAiRequest(ollamaReq, protocolSupport);
        applyReasoningEffort(body, resolveModelOrDefault(ollamaReq.getModel()));
        return body;
    }

    private OllamaChatResponse convertOpenAiToOllama(String openAiJson, String requestModel) {
        try {
            return protocolConverter.toOllamaResponse(openAiJson, requestModel, protocolSupport);
        } catch (Exception e) {
            log.error("智谱 OpenAI → Ollama 转换失败", e);
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

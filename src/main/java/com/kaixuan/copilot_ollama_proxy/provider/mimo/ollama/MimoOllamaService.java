package com.kaixuan.copilot_ollama_proxy.provider.mimo.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.provider.mimo.openai.MimoOpenAiTransportClient;
import com.kaixuan.copilot_ollama_proxy.provider.ollama.AbstractRuntimeCatalogOllamaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * MiMo Ollama 协议实现 —— 将 Ollama 格式请求转换为 OpenAI 格式调用 MiMo API，
 * 再将 OpenAI 格式响应转换回 Ollama 格式。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class MimoOllamaService extends AbstractRuntimeCatalogOllamaService {

    private static final Logger log = LoggerFactory.getLogger(MimoOllamaService.class);

    private final MimoOpenAiTransportClient transportClient;
    private final MimoOllamaProtocolConverter protocolConverter;
    private final MimoOllamaProtocolConverter.Support protocolSupport;
    private final MimoOllamaStreamTranslator streamTranslator;

    public MimoOllamaService(RuntimeProviderCatalog runtimeProviderCatalog, @Value("${mimo.default-model:mimo-v2.5-pro}") String fallbackDefaultModel, ObjectMapper objectMapper,
            MimoOpenAiTransportClient transportClient) {
        super(runtimeProviderCatalog, fallbackDefaultModel);
        this.transportClient = transportClient;
        this.protocolConverter = new MimoOllamaProtocolConverter(objectMapper);
        this.protocolSupport = new MimoOllamaProtocolConverter.Support(this::resolveRequestModel, this::resolveMaxTokens, this::extractStringContent, this::currentTimestamp);
        this.streamTranslator = new MimoOllamaStreamTranslator(objectMapper, new MimoOllamaStreamTranslator.Support(this::createStreamingChunk, this::createStreamingCompletion));
    }

    @Override
    public String getProviderKey() {
        return "mimo";
    }

    @Override
    protected String providerFormat() {
        return "mimo";
    }

    @Override
    protected String providerFamily() {
        return "Mimo";
    }

    @Override
    protected List<String> providerFamilies() {
        return List.of("Mimo");
    }

    @Override
    protected String providerParameterSize() {
        return "42B";
    }

    @Override
    protected String providerLicense() {
        return "Apache 2.0";
    }

    private List<String> buildCapabilitiesFromDb(String resolvedModel) {
        List<String> caps = new ArrayList<>();
        caps.add("completion");
        ProviderRuntimeModel model = requireModelConfiguration(resolvedModel);
        if (model.capsTools()) {
            caps.add("tools");
        }
        if (model.capsVision()) {
            caps.add("vision");
        }
        return caps;
    }

    @Override
    public OllamaShowResponse showModel(String modelName) {
        String resolvedModel = resolveModelOrDefault(modelName);
        int contextLength = requireContextLength(resolvedModel);
        List<String> capabilities = buildCapabilitiesFromDb(resolvedModel);
        return buildShowResponse(resolvedModel, contextLength, capabilities);
    }

    @Override
    public Mono<OllamaChatResponse> chat(OllamaChatRequest request) {
        if (request.isStream()) {
            return Mono.error(new UnsupportedOperationException("Use chatStream() for streaming"));
        }

        Map<String, Object> openAiRequest = convertOllamaToOpenAi(request);
        log.info("MiMo Ollama→OpenAI，模型: {}, 流式: false", openAiRequest.get("model"));

        return transportClient.sendChatCompletion(openAiRequest).map(respJson -> convertOpenAiToOllama(respJson, request.getModel()));
    }

    @Override
    public Flux<OllamaChatResponse> chatStream(OllamaChatRequest request) {
        Map<String, Object> openAiRequest = convertOllamaToOpenAi(request);
        openAiRequest.put("stream", true);
        log.info("MiMo Ollama→OpenAI，模型: {}, 流式: true", openAiRequest.get("model"));

        return transportClient.streamChatCompletion(openAiRequest).concatMap(chunk -> Flux.fromIterable(streamTranslator.translate(chunk, request.getModel())));
    }

    private Map<String, Object> convertOllamaToOpenAi(OllamaChatRequest ollamaReq) {
        return protocolConverter.toOpenAiRequest(ollamaReq, protocolSupport);
    }

    private OllamaChatResponse convertOpenAiToOllama(String openAiJson, String requestModel) {
        try {
            return protocolConverter.toOllamaResponse(openAiJson, requestModel, protocolSupport);
        } catch (Exception e) {
            log.error("OpenAI → Ollama 转换失败", e);
            throw new RuntimeException("响应转换失败", e);
        }
    }

    private OllamaChatResponse createStreamingChunk(String modelName, String content) {
        return createAssistantChunk(modelName, content, false);
    }

    private OllamaChatResponse createStreamingCompletion(String modelName, String content, List<OllamaChatResponse.ToolCallResult> toolCalls) {
        return createAssistantCompletion(modelName, "stop", content, toolCalls);
    }
}

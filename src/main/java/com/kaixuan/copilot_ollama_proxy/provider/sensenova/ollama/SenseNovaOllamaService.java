package com.kaixuan.copilot_ollama_proxy.provider.sensenova.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.provider.ollama.AbstractRuntimeCatalogOllamaService;
import com.kaixuan.copilot_ollama_proxy.provider.sensenova.openai.SenseNovaOpenAiTransportClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * SenseNova Ollama 协议实现 —— 将 Ollama 格式请求转换为 OpenAI 格式调用 SenseNova API，
 * 再将 OpenAI 格式响应转换回 Ollama 格式。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class SenseNovaOllamaService extends AbstractRuntimeCatalogOllamaService {

    private static final Logger log = LoggerFactory.getLogger(SenseNovaOllamaService.class);

    private final SenseNovaOllamaProtocolConverter protocolConverter;
    private final SenseNovaOllamaProtocolConverter.Support protocolSupport;
    private final SenseNovaOllamaStreamTranslator streamTranslator;
    private final SenseNovaOpenAiTransportClient transportClient;

    public SenseNovaOllamaService(RuntimeProviderCatalog runtimeProviderCatalog, @Value("${sensenova.default-model:sensenova-6.7-flash-lite}") String fallbackDefaultModel, ObjectMapper objectMapper,
            SenseNovaOpenAiTransportClient transportClient) {
        super(runtimeProviderCatalog, fallbackDefaultModel);
        this.transportClient = transportClient;
        this.protocolConverter = new SenseNovaOllamaProtocolConverter(objectMapper);
        this.protocolSupport = new SenseNovaOllamaProtocolConverter.Support(this::resolveRequestModel, this::resolveMaxTokens, this::extractStringContent, this::currentTimestamp);
        this.streamTranslator = new SenseNovaOllamaStreamTranslator(objectMapper, new SenseNovaOllamaStreamTranslator.Support(this::createStreamingChunk, this::createStreamingCompletion));
    }

    // ========== 路由支持 ==========

    @Override
    public String getProviderKey() {
        return "sensenova";
    }

    @Override
    protected String providerFormat() {
        return "sensenova";
    }

    @Override
    protected String providerFamily() {
        return "SenseNova";
    }

    @Override
    protected List<String> providerFamilies() {
        return List.of("SenseNova");
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

        // 能力列表从数据库读取（caps_tools / caps_vision）
        List<String> capabilities = buildCapabilitiesFromDb(resolvedModel);

        // 上下文长度必须从数据库读取
        int contextLength = requireContextLength(resolvedModel);

        return buildShowResponse(resolvedModel, contextLength, capabilities);
    }

    /**
     * 从数据库读取模型的能力标志（caps_tools / caps_vision），构建 capabilities 列表。
     */
    private List<String> buildCapabilitiesFromDb(String resolvedModel) {
        List<String> caps = new java.util.ArrayList<>();
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
            log.warn("SenseNova 模型 [{}] 能力读取失败，仅使用默认 completion 能力: {}", resolvedModel, e.getMessage());
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
        log.info("SenseNova Ollama→OpenAI，模型: {}, 流式: false", openAiRequest.get("model"));

        return transportClient.sendChatCompletion(openAiRequest).map(respJson -> convertOpenAiToOllama(respJson, request.getModel()));
    }

    // ========== 流式对话 ==========

    @Override
    public Flux<OllamaChatResponse> chatStream(OllamaChatRequest request) {
        Map<String, Object> openAiRequest = convertOllamaToOpenAi(request);
        openAiRequest.put("stream", true);
        log.info("SenseNova Ollama→OpenAI，模型: {}, 流式: true", openAiRequest.get("model"));

        return transportClient.streamChatCompletion(openAiRequest).concatMap(chunk -> Flux.fromIterable(streamTranslator.translate(chunk, request.getModel())));
    }

    // ========== Ollama → OpenAI 请求转换 ==========

    private Map<String, Object> convertOllamaToOpenAi(OllamaChatRequest ollamaReq) {
        return protocolConverter.toOpenAiRequest(ollamaReq, protocolSupport);
    }

    // ========== OpenAI → Ollama 响应转换（非流式） ==========

    private OllamaChatResponse convertOpenAiToOllama(String openAiJson, String requestModel) {
        try {
            return protocolConverter.toOllamaResponse(openAiJson, requestModel, protocolSupport);
        } catch (Exception e) {
            log.error("SenseNova OpenAI → Ollama 转换失败", e);
            return createResponse(requestModel, true, "stop", createMessage("assistant", "转换失败: " + e.getMessage()));
        }
    }

    /**
     * 组装 SenseNova 流式输出中的普通增量 chunk。
     */
    private OllamaChatResponse createStreamingChunk(String modelName, String content) {
        return createAssistantChunk(modelName, content, false);
    }

    /**
     * 组装 SenseNova 流式输出中的最终完成 chunk。
     */
    private OllamaChatResponse createStreamingCompletion(String modelName, String content, List<OllamaChatResponse.ToolCallResult> toolCalls) {
        return createAssistantCompletion(modelName, !toolCalls.isEmpty() ? "tool_calls" : "stop", content, toolCalls);
    }
}

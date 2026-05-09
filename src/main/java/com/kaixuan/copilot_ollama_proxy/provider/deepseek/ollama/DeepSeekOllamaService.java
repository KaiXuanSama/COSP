package com.kaixuan.copilot_ollama_proxy.provider.deepseek.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.provider.deepseek.openai.DeepSeekOpenAiTransportClient;
import com.kaixuan.copilot_ollama_proxy.provider.ollama.AbstractRuntimeCatalogOllamaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek Ollama 协议实现 —— 将 Ollama 格式请求转换为 OpenAI 格式调用 DeepSeek API，
 * 再将 OpenAI 格式响应转换回 Ollama 格式。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class DeepSeekOllamaService extends AbstractRuntimeCatalogOllamaService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekOllamaService.class);

    private final DeepSeekOllamaProtocolConverter protocolConverter;
    private final DeepSeekOllamaProtocolConverter.Support protocolSupport;
    private final DeepSeekOllamaStreamTranslator streamTranslator;
    private final DeepSeekOpenAiTransportClient transportClient;

    public DeepSeekOllamaService(RuntimeProviderCatalog runtimeProviderCatalog, @Value("${deepseek.default-model:deepseek-v4-flash}") String fallbackDefaultModel, ObjectMapper objectMapper,
            DeepSeekOpenAiTransportClient transportClient) {
        super(runtimeProviderCatalog, fallbackDefaultModel);
        this.transportClient = transportClient;
        this.protocolConverter = new DeepSeekOllamaProtocolConverter(objectMapper);
        this.protocolSupport = new DeepSeekOllamaProtocolConverter.Support(this::resolveRequestModel, this::resolveMaxTokens, this::extractStringContent, this::currentTimestamp);
        this.streamTranslator = new DeepSeekOllamaStreamTranslator(objectMapper, new DeepSeekOllamaStreamTranslator.Support(this::createStreamingChunk, this::createStreamingCompletion));
    }

    // ========== 路由支持 ==========

    @Override
    public String getProviderKey() {
        return "deepseek";
    }

    @Override
    protected String providerFormat() {
        return "deepseek";
    }

    @Override
    protected String providerFamily() {
        return "DeepSeek";
    }

    @Override
    protected List<String> providerFamilies() {
        return List.of("DeepSeek");
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

        // DeepSeek 模型都支持 completion 和 tools
        List<String> capabilities = List.of("completion", "tools");

        int contextLength = requireContextLength(resolvedModel);
        return buildShowResponse(resolvedModel, contextLength, capabilities);
    }

    // ========== 非流式对话 ==========

    @Override
    public Mono<OllamaChatResponse> chat(OllamaChatRequest request) {
        if (request.isStream()) {
            return Mono.error(new UnsupportedOperationException("Use chatStream() for streaming"));
        }

        Map<String, Object> openAiRequest = convertOllamaToOpenAi(request);
        log.info("DeepSeek Ollama→OpenAI，模型: {}, 流式: false", openAiRequest.get("model"));

        return transportClient.sendChatCompletion(openAiRequest).map(respJson -> convertOpenAiToOllama(respJson, request.getModel()));
    }

    // ========== 流式对话 ==========

    @Override
    public Flux<OllamaChatResponse> chatStream(OllamaChatRequest request) {
        Map<String, Object> openAiRequest = convertOllamaToOpenAi(request);
        openAiRequest.put("stream", true);
        log.info("DeepSeek Ollama→OpenAI，模型: {}, 流式: true", openAiRequest.get("model"));

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
            log.error("OpenAI → Ollama 转换失败", e);
            return createResponse(requestModel, true, "stop", createMessage("assistant", "转换失败: " + e.getMessage()));
        }
    }

    private OllamaChatResponse createStreamingChunk(String modelName, String content) {
        return createAssistantChunk(modelName, content, false);
    }

    private OllamaChatResponse createStreamingCompletion(String modelName, String content, List<OllamaChatResponse.ToolCallResult> toolCalls) {
        return createAssistantCompletion(modelName, !toolCalls.isEmpty() ? "tool_calls" : "stop", content, toolCalls);
    }
}
package com.kaixuan.copilot_ollama_proxy.provider.mimo.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicStreamEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.provider.mimo.anthropic.MimoAnthropicClient;
import com.kaixuan.copilot_ollama_proxy.provider.ollama.AbstractRuntimeCatalogOllamaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * MiMo Ollama 协议实现 —— 将 Ollama 格式请求转换为 Anthropic 格式调用 MiMo API，
 * 再将 Anthropic 格式响应转换回 Ollama 格式。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class MimoOllamaService extends AbstractRuntimeCatalogOllamaService {

    private final MimoAnthropicClient anthropicClient;
    private final MimoOllamaProtocolConverter protocolConverter;
    private final MimoOllamaProtocolConverter.Support protocolSupport;
    private final MimoOllamaStreamTranslator streamTranslator;

    public MimoOllamaService(MimoAnthropicClient anthropicClient, RuntimeProviderCatalog runtimeProviderCatalog, @Value("${mimo.default-model:mimo-v2.5-pro}") String fallbackDefaultModel,
            ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, fallbackDefaultModel);
        this.anthropicClient = anthropicClient;
        this.protocolConverter = new MimoOllamaProtocolConverter();
        this.protocolSupport = new MimoOllamaProtocolConverter.Support(this::resolveRequestModel, this::resolveMaxTokens, this::extractStringContent, this::currentTimestamp);
        this.streamTranslator = new MimoOllamaStreamTranslator(objectMapper, new MimoOllamaStreamTranslator.Support(this::createStreamingChunk, this::createStreamingCompletion));
    }

    // ========== 路由支持 ==========

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

    /**
     * 从数据库读取模型的能力标志（caps_tools / caps_vision），构建 capabilities 列表。
     */
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

        // 上下文长度必须从数据库读取，未配置则直接报错
        int contextLength = requireContextLength(resolvedModel);

        // 能力列表从数据库读取（caps_tools / caps_vision）
        List<String> capabilities = buildCapabilitiesFromDb(resolvedModel);

        return buildShowResponse(resolvedModel, contextLength, capabilities);
    }

    // ========== 对话接口（非流式） ==========

    @Override
    public Mono<OllamaChatResponse> chat(OllamaChatRequest request) {
        AnthropicRequest anthropicReq = convertRequest(request);
        boolean stream = request.isStream();

        if (stream) {
            return Mono.error(new UnsupportedOperationException("Use chatStream() for streaming"));
        }

        return anthropicClient.sendMessage(anthropicReq).map(resp -> convertResponse(resp, request.getModel()));
    }

    // ========== 对话接口（流式） ==========

    @Override
    public Flux<OllamaChatResponse> chatStream(OllamaChatRequest request) {
        AnthropicRequest anthropicReq = convertRequest(request);
        anthropicReq.setStream(true);

        return anthropicClient.streamMessages(anthropicReq).transform(flux -> processStreamEvents(flux, request.getModel()));
    }

    private Flux<OllamaChatResponse> processStreamEvents(Flux<AnthropicStreamEvent> flux, String modelName) {
        return flux.concatMap(event -> Flux.fromIterable(streamTranslator.translate(event, modelName)));
    }

    // ========== 协议转换：Ollama → Anthropic ==========

    private AnthropicRequest convertRequest(OllamaChatRequest ollamaReq) {
        return protocolConverter.toAnthropicRequest(ollamaReq, protocolSupport);
    }

    // ========== 协议转换：Anthropic → Ollama（非流式） ==========

    private OllamaChatResponse convertResponse(AnthropicResponse anthropicResp, String requestModel) {
        return protocolConverter.toOllamaResponse(anthropicResp, requestModel, protocolSupport);
    }

    private OllamaChatResponse createStreamingChunk(String modelName, String content) {
        return createAssistantChunk(modelName, content, false);
    }

    private OllamaChatResponse createStreamingCompletion(String modelName, String content, List<OllamaChatResponse.ToolCallResult> toolCalls) {
        return createAssistantCompletion(modelName, "stop", content, toolCalls);
    }

}

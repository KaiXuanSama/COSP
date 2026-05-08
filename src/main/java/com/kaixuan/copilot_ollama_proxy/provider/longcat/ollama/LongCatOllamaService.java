package com.kaixuan.copilot_ollama_proxy.provider.longcat.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.provider.ollama.AbstractRuntimeCatalogOllamaService;
import com.kaixuan.copilot_ollama_proxy.provider.longcat.openai.LongCatOpenAiTransportClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.*;

/**
 * LongCat Ollama 协议实现 —— 将 Ollama 格式请求转换为 OpenAI 格式调用 LongCat API，
 * 再将 OpenAI 格式响应转换回 Ollama 格式。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class LongCatOllamaService extends AbstractRuntimeCatalogOllamaService {

    private static final Logger log = LoggerFactory.getLogger(LongCatOllamaService.class);

    private final LongCatOllamaProtocolConverter protocolConverter;
    private final LongCatOllamaProtocolConverter.Support protocolSupport;
    private final LongCatOllamaStreamTranslator streamTranslator;
    private final LongCatOpenAiTransportClient transportClient;

    /**
     * @param runtimeProviderCatalog 运行时 provider 配置目录
     * @param fallbackDefaultModel 当请求中未显式给出模型时的 LongCat 默认模型
     * @param objectMapper JSON 编解码器，供 converter 与 translator 共享
     * @param transportClient LongCat 上游 HTTP 传输客户端
     */
    public LongCatOllamaService(RuntimeProviderCatalog runtimeProviderCatalog,
            @Value("${longcat.default-model:LongCat-Flash-Chat}") String fallbackDefaultModel,
            ObjectMapper objectMapper, LongCatOpenAiTransportClient transportClient) {
        super(runtimeProviderCatalog, fallbackDefaultModel);
        this.transportClient = transportClient;
        this.protocolConverter = new LongCatOllamaProtocolConverter(objectMapper);
        this.protocolSupport = new LongCatOllamaProtocolConverter.Support(this::resolveRequestModel, this::resolveMaxTokens, this::extractStringContent, this::currentTimestamp);
        this.streamTranslator = new LongCatOllamaStreamTranslator(objectMapper, new LongCatOllamaStreamTranslator.Support(this::createStreamingChunk, this::createStreamingCompletion));
    }

    // ========== 路由支持 ==========

    @Override
    public String getProviderKey() {
        return "longcat";
    }

    @Override
    protected String providerFormat() {
        return "longcat";
    }

    @Override
    protected String providerFamily() {
        return "LongCat";
    }

    @Override
    protected List<String> providerFamilies() {
        return List.of("LongCat");
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

        // 先确定能力列表（硬编码，因为能力通常由模型类型决定）
        List<String> capabilities;
        if (resolvedModel.contains("Omni")) {
            capabilities = List.of("completion", "tools", "vision");
        } else {
            capabilities = List.of("completion", "tools");
        }

        // 上下文长度必须从数据库读取，未配置则直接报错
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
        log.info("LongCat Ollama→OpenAI，模型: {}, 流式: false", openAiRequest.get("model"));

        return transportClient.sendChatCompletion(openAiRequest).map(respJson -> convertOpenAiToOllama(respJson, request.getModel()));
    }

    // ========== 流式对话 ==========
    @Override
    public Flux<OllamaChatResponse> chatStream(OllamaChatRequest request) {
        Map<String, Object> openAiRequest = convertOllamaToOpenAi(request);
        openAiRequest.put("stream", true);
        log.info("LongCat Ollama→OpenAI，模型: {}, 流式: true", openAiRequest.get("model"));

        return transportClient.streamChatCompletion(openAiRequest)
            .concatMap(chunk -> Flux.fromIterable(streamTranslator.translate(chunk, request.getModel())));
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

    /**
     * 组装 LongCat 流式输出中的普通增量 chunk。
     */
    private OllamaChatResponse createStreamingChunk(String modelName, String content) {
        return createAssistantChunk(modelName, content, false);
    }

    /**
     * 组装 LongCat 流式输出中的最终完成 chunk。
     * <p>
     * LongCat 在工具调用结束时会通过 done_reason=tool_calls 收口；
     * 普通文本结束则走 done_reason=stop。
     */
    private OllamaChatResponse createStreamingCompletion(String modelName, String content, List<OllamaChatResponse.ToolCallResult> toolCalls) {
        return createAssistantCompletion(modelName, !toolCalls.isEmpty() ? "tool_calls" : "stop", content, toolCalls);
    }
}

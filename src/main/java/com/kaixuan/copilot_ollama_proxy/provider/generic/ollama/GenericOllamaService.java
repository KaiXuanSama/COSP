package com.kaixuan.copilot_ollama_proxy.provider.generic.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
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
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通用 Ollama 协议服务 —— 处理所有 custom-* 前缀的自定义供应商。
 * 从数据库动态读取配置，按标准 OpenAI 兼容协议转发请求。
 */
@Service
public class GenericOllamaService extends AbstractRuntimeCatalogOllamaService {

    private static final Logger log = LoggerFactory.getLogger(GenericOllamaService.class);
    private static final String PROVIDER_KEY = "__generic__";

    private final RuntimeProviderCatalog runtimeProviderCatalog;
    private final ObjectMapper objectMapper;

    public GenericOllamaService(RuntimeProviderCatalog runtimeProviderCatalog, ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder) {
        super(runtimeProviderCatalog, "");
        this.runtimeProviderCatalog = runtimeProviderCatalog;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderKey() {
        return PROVIDER_KEY;
    }

    @Override
    protected String providerFormat() {
        return "generic";
    }

    @Override
    protected String providerFamily() {
        return "Generic";
    }

    @Override
    protected List<String> providerFamilies() {
        return List.of("Generic");
    }

    @Override
    protected String providerParameterSize() {
        return "Unknown";
    }

    @Override
    protected String providerLicense() {
        return "Proprietary";
    }

    /**
     * 判断此服务是否能处理给定的 providerKey。
     * 支持所有 custom- 前缀的供应商。
     */
    public boolean supports(String providerKey) {
        return providerKey != null && providerKey.startsWith("custom-");
    }

    @Override
    public OllamaShowResponse showModel(String modelName) {
        String resolvedModel = resolveModelOrDefault(modelName);
        // 从模型名反推 providerKey
        String providerKey = findProviderKeyForModel(resolvedModel);
        if (providerKey == null) {
            log.warn("通用服务无法找到模型 [{}] 对应的供应商", resolvedModel);
            return buildShowResponse(resolvedModel, 4096, List.of("completion"));
        }
        ProviderRuntimeConfiguration config = runtimeProviderCatalog.getActiveProvider(providerKey);
        List<String> caps = new ArrayList<>();
        caps.add("completion");
        int contextLength = config != null ? 4096 : 4096;
        // 尝试从模型配置中读取能力
        if (config != null) {
            for (var m : config.models()) {
                if (resolvedModel.equals(m.modelName())) {
                    contextLength = m.contextSize() > 0 ? m.contextSize() : 4096;
                    if (m.capsTools()) caps.add("tools");
                    if (m.capsVision()) caps.add("vision");
                    break;
                }
            }
        }
        return buildShowResponse(resolvedModel, contextLength, caps);
    }

    @Override
    public Mono<OllamaChatResponse> chat(OllamaChatRequest request) {
        if (request.isStream()) {
            return Mono.error(new UnsupportedOperationException("Use chatStream() for streaming"));
        }
        String providerKey = resolveProviderKey(request.getModel());
        if (providerKey == null) {
            return Mono.error(new IllegalStateException("无法解析自定义供应商: " + request.getModel()));
        }
        var transportClient = buildTransportClient(providerKey);
        var protocolConverter = new OllamaProtocolConverter(objectMapper);
        var protocolSupport = new OllamaProtocolConverter.Support(
                this::resolveRequestModel, this::resolveMaxTokens, this::extractStringContent, this::currentTimestamp);

        Map<String, Object> openAiRequest = protocolConverter.toOpenAiRequest(request, protocolSupport);
        log.info("通用服务 Ollama→OpenAI，供应商: {}, 模型: {}, 流式: false", providerKey, openAiRequest.get("model"));
        return transportClient.sendChatCompletion(openAiRequest)
                .map(respJson -> {
                    try {
                        return protocolConverter.toOllamaResponse(respJson, request.getModel(), protocolSupport);
                    } catch (Exception e) {
                        log.error("通用服务 OpenAI → Ollama 转换失败", e);
                        return createResponse(request.getModel(), true, "stop",
                                createMessage("assistant", "转换失败: " + e.getMessage()));
                    }
                });
    }

    @Override
    public Flux<OllamaChatResponse> chatStream(OllamaChatRequest request) {
        String providerKey = resolveProviderKey(request.getModel());
        if (providerKey == null) {
            return Flux.error(new IllegalStateException("无法解析自定义供应商: " + request.getModel()));
        }
        var transportClient = buildTransportClient(providerKey);
        var protocolConverter = new OllamaProtocolConverter(objectMapper);
        var protocolSupport = new OllamaProtocolConverter.Support(
                this::resolveRequestModel, this::resolveMaxTokens, this::extractStringContent, this::currentTimestamp);
        var streamTranslator = new OllamaStreamTranslator(objectMapper,
                new OllamaStreamTranslator.Support(this::createStreamingChunk, this::createStreamingCompletion));

        Map<String, Object> openAiRequest = protocolConverter.toOpenAiRequest(request, protocolSupport);
        openAiRequest.put("stream", true);
        log.info("通用服务 Ollama→OpenAI，供应商: {}, 模型: {}, 流式: true", providerKey, openAiRequest.get("model"));

        var session = streamTranslator.newSession();
        return transportClient.streamChatCompletion(openAiRequest)
                .concatMap(chunk -> Flux.fromIterable(
                        streamTranslator.translate(session, chunk, request.getModel())));
    }

    private OpenAiTransportClient buildTransportClient(String providerKey) {
        return new OpenAiTransportClient(runtimeProviderCatalog, WebClient.builder(),
                new OpenAiTransportClient.Config(providerKey, "", "/chat/completions",
                        (headers, apiKey) -> headers.set(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + apiKey),
                        raw -> raw.replaceAll("/+$", "")));
    }

    private String resolveProviderKey(String modelName) {
        // 从完整模型名中提取 providerKey
        var parsed = com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil.parse(modelName);
        if (parsed.hasProviderPrefix()) {
            String key = parsed.providerKey().toLowerCase();
            // 先尝试原始 key
            if (runtimeProviderCatalog.getActiveProvider(key) != null) {
                return key;
            }
            // 再尝试 custom- 前缀
            String customKey = "custom-" + key;
            if (runtimeProviderCatalog.getActiveProvider(customKey) != null) {
                return customKey;
            }
        }
        // 无前缀时，从模型名搜索
        return findProviderKeyForModel(parsed.modelName());
    }

    private String findProviderKeyForModel(String modelName) {
        for (var provider : runtimeProviderCatalog.getActiveProviders()) {
            if (provider.providerKey().startsWith("custom-") && provider.supportsModel(modelName)) {
                return provider.providerKey();
            }
        }
        return null;
    }

    private OllamaChatResponse createStreamingChunk(String modelName, String content) {
        return createAssistantChunk(modelName, content, false);
    }

    private OllamaChatResponse createStreamingCompletion(String modelName, String content,
            List<OllamaChatResponse.ToolCallResult> toolCalls) {
        return createAssistantCompletion(modelName, !toolCalls.isEmpty() ? "tool_calls" : "stop", content, toolCalls);
    }
}

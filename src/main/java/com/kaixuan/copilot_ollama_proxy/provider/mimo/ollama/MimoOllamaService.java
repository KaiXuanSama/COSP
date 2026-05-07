package com.kaixuan.copilot_ollama_proxy.provider.mimo.ollama;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.ollama.OllamaService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicStreamEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaTagsResponse;
import com.kaixuan.copilot_ollama_proxy.provider.mimo.anthropic.MimoAnthropicClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MiMo Ollama 协议实现 —— 将 Ollama 格式请求转换为 Anthropic 格式调用 MiMo API，
 * 再将 Anthropic 格式响应转换回 Ollama 格式。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class MimoOllamaService implements OllamaService {

    private static final Logger log = LoggerFactory.getLogger(MimoOllamaService.class);

    private final MimoAnthropicClient anthropicClient;
    private final ProviderConfigRepository providerConfigRepository;
    private final ObjectMapper objectMapper;
    private final String fallbackDefaultModel;

    public MimoOllamaService(MimoAnthropicClient anthropicClient, ProviderConfigRepository providerConfigRepository,
            @Value("${mimo.default-model:mimo-v2.5-pro}") String fallbackDefaultModel, ObjectMapper objectMapper) {
        this.anthropicClient = anthropicClient;
        this.providerConfigRepository = providerConfigRepository;
        this.objectMapper = objectMapper;
        this.fallbackDefaultModel = fallbackDefaultModel;
    }

    /**
     * 从数据库读取 MiMo 运行时配置。
     */
    private Map<String, Object> getMimoConfig() {
        return providerConfigRepository.findActiveProviderByKey("mimo");
    }

    /**
     * 从数据库读取第一个已启用的模型名称作为默认模型。
     */
    private String getDefaultModel() {
        Map<String, Object> config = getMimoConfig();
        if (config != null) {
            int providerId = (Integer) config.get("id");
            List<Map<String, Object>> models = providerConfigRepository.findModelsByProviderId(providerId);
            if (models != null) {
                for (Map<String, Object> m : models) {
                    if (Boolean.TRUE.equals(m.get("enabled"))) {
                        return (String) m.getOrDefault("modelName", fallbackDefaultModel);
                    }
                }
            }
        }
        return fallbackDefaultModel;
    }

    // ========== 路由支持 ==========

    @Override
    public String getProviderKey() {
        return "mimo";
    }

    @Override
    public boolean supportsModel(String modelName) {
        Map<String, Object> config = getMimoConfig();
        if (config == null) {
            return false;
        }
        int providerId = (Integer) config.get("id");
        List<Map<String, Object>> dbModels = providerConfigRepository.findModelsByProviderId(providerId);
        if (dbModels != null) {
            for (Map<String, Object> m : dbModels) {
                if (Boolean.TRUE.equals(m.get("enabled")) && modelName.equals(m.get("modelName"))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ========== 模型管理 ==========

    @Override
    public Mono<OllamaTagsResponse> listModels() {
        var response = new OllamaTagsResponse();
        List<OllamaTagsResponse.ModelInfo> models = new ArrayList<>();

        Map<String, Object> config = getMimoConfig();
        if (config == null) {
            // MiMo 未启用，返回空列表（控制器层会处理 nano_llm 回退）
            response.setModels(models);
            return Mono.just(response);
        }

        int providerId = (Integer) config.get("id");
        List<Map<String, Object>> dbModels = providerConfigRepository.findModelsByProviderId(providerId);
        if (dbModels != null) {
            for (Map<String, Object> m : dbModels) {
                if (!Boolean.TRUE.equals(m.get("enabled"))) {
                    continue;
                }
                String modelName = (String) m.getOrDefault("modelName", "");
                if (modelName.isEmpty())
                    continue;
                boolean capsTools = Boolean.TRUE.equals(m.get("capsTools"));
                boolean capsVision = Boolean.TRUE.equals(m.get("capsVision"));
                models.add(createModelInfo(modelName, modelName, capsTools, capsVision));
            }
        }

        response.setModels(models);
        return Mono.just(response);
    }

    private OllamaTagsResponse.ModelInfo createModelInfo(String name, String model, boolean capsTools,
            boolean capsVision) {
        var info = new OllamaTagsResponse.ModelInfo();
        info.setName(name);
        info.setModel(model);
        info.setModifiedAt(Instant.now().toString());
        info.setSize(0);
        info.setDigest("sha256:" + UUID.randomUUID().toString().replace("-", ""));
        var details = new OllamaTagsResponse.ModelDetails();
        details.setFormat("mimo");
        details.setFamily("Mimo");
        details.setFamilies(List.of("Mimo"));
        details.setParameterSize("42B");
        details.setQuantizationLevel("none");
        info.setDetails(details);
        return info;
    }

    /**
     * 从数据库读取模型的上下文长度（context_size）。
     * 如果数据库中未配置或值为 0，抛出 IllegalStateException。
     */
    private int getContextLengthFromDb(String resolvedModel) {
        Map<String, Object> config = getMimoConfig();
        if (config != null) {
            int providerId = (Integer) config.get("id");
            List<Map<String, Object>> dbModels = providerConfigRepository.findModelsByProviderId(providerId);
            if (dbModels != null) {
                for (Map<String, Object> m : dbModels) {
                    if (resolvedModel.equals(m.get("modelName"))) {
                        Object ctx = m.get("contextSize");
                        if (ctx instanceof Number n && n.intValue() > 0) {
                            return n.intValue();
                        }
                        throw new IllegalStateException("模型 " + resolvedModel + " 的 context_size 未在数据库中配置或为 0，请先完成配置");
                    }
                }
            }
        }
        throw new IllegalStateException("在数据库中找不到模型 " + resolvedModel + " 的配置，请检查 provider_model 表");
    }

    /**
     * 从数据库读取模型的能力标志（caps_tools / caps_vision），构建 capabilities 列表。
     */
    private List<String> buildCapabilitiesFromDb(String resolvedModel) {
        List<String> caps = new ArrayList<>();
        caps.add("completion");
        Map<String, Object> config = getMimoConfig();
        if (config != null) {
            int providerId = (Integer) config.get("id");
            List<Map<String, Object>> dbModels = providerConfigRepository.findModelsByProviderId(providerId);
            if (dbModels != null) {
                for (Map<String, Object> m : dbModels) {
                    if (resolvedModel.equals(m.get("modelName"))) {
                        if (Boolean.TRUE.equals(m.get("capsTools"))) {
                            caps.add("tools");
                        }
                        if (Boolean.TRUE.equals(m.get("capsVision"))) {
                            caps.add("vision");
                        }
                        return caps;
                    }
                }
            }
        }
        throw new IllegalStateException("在数据库中找不到模型 " + resolvedModel + " 的配置，请检查 provider_model 表");
    }

    @Override
    public OllamaShowResponse showModel(String modelName) {
        String resolvedModel = (modelName == null || modelName.isBlank()) ? getDefaultModel() : modelName;

        // 上下文长度必须从数据库读取，未配置则直接报错
        int contextLength = getContextLengthFromDb(resolvedModel);

        // 能力列表从数据库读取（caps_tools / caps_vision）
        List<String> capabilities = buildCapabilitiesFromDb(resolvedModel);

        var response = new OllamaShowResponse();
        response.setParameters("temperature 0.7\nnum_ctx " + contextLength);
        response.setLicense("Apache 2.0");
        response.setModifiedAt(Instant.now().toString());
        response.setTemplate("{{ .System }}\n{{ .Prompt }}");
        response.setCapabilities(capabilities);

        var details = new OllamaShowResponse.ShowDetails();
        details.setParentModel("");
        details.setFormat("mimo");
        details.setFamily("Mimo");
        details.setFamilies(List.of("Mimo"));
        details.setParameterSize("42B");
        details.setQuantizationLevel("none");
        response.setDetails(details);

        String arch = "mimo";
        response.setModelInfo(Map.of("general.architecture", arch, "general.basename", resolvedModel,
                arch + ".context_length", contextLength, arch + ".embedding_length", 8192));

        return response;
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

        return anthropicClient.streamMessages(anthropicReq)
                .transform(flux -> processStreamEvents(flux, request.getModel()));
    }

    private Flux<OllamaChatResponse> processStreamEvents(Flux<AnthropicStreamEvent> flux, String modelName) {
        AtomicReference<StringBuilder> textBuffer = new AtomicReference<>(new StringBuilder());
        AtomicReference<List<OllamaChatResponse.ToolCallResult>> toolCalls = new AtomicReference<>(new ArrayList<>());
        AtomicReference<Map<String, Object>> currentToolInput = new AtomicReference<>();
        AtomicReference<String> currentToolName = new AtomicReference<>();
        AtomicReference<Integer> currentBlockIndex = new AtomicReference<>(-1);

        return flux.concatMap(event -> {
            List<OllamaChatResponse> results = new ArrayList<>();

            switch (event.getType()) {
            case "message_start" -> {
            }
            case "content_block_start" -> {
                Map<String, Object> block = event.getContentBlock();
                if (block != null) {
                    int idx = event.getIndex() != null ? event.getIndex() : 0;
                    currentBlockIndex.set(idx);
                    String blockType = (String) block.get("type");
                    if ("tool_use".equals(blockType)) {
                        currentToolName.set((String) block.get("name"));
                        currentToolInput.set(new HashMap<>());
                    }
                }
            }
            case "content_block_delta" -> {
                Map<String, Object> delta = event.getDelta();
                if (delta != null) {
                    String deltaType = (String) delta.get("type");
                    if ("text_delta".equals(deltaType)) {
                        String text = (String) delta.get("text");
                        if (text != null) {
                            textBuffer.get().append(text);
                            results.add(createStreamChunk(modelName, text, false));
                        }
                    } else if ("input_json_delta".equals(deltaType)) {
                        String json = (String) delta.get("partial_json");
                        if (json != null && currentToolInput.get() != null) {
                            try {
                                Map<String, Object> partial = objectMapper.readValue(json, new TypeReference<>() {
                                });
                                currentToolInput.get().putAll(partial);
                            } catch (Exception e) {
                                log.debug("Partial JSON parse, accumulating: {}", json);
                            }
                        }
                    }
                }
            }
            case "content_block_stop" -> {
                if (currentToolName.get() != null) {
                    var tc = new OllamaChatResponse.ToolCallResult();
                    var fn = new OllamaChatResponse.ToolCallFunction();
                    fn.setName(currentToolName.get());
                    fn.setArguments(currentToolInput.get() != null ? currentToolInput.get() : Map.of());
                    tc.setFunction(fn);
                    toolCalls.get().add(tc);
                    currentToolName.set(null);
                    currentToolInput.set(null);
                }
            }
            case "message_delta" -> {
            }
            case "message_stop" -> {
                var finalResp = new OllamaChatResponse();
                finalResp.setModel(modelName);
                finalResp.setCreatedAt(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
                finalResp.setDone(true);
                finalResp.setDoneReason("stop");

                var msg = new OllamaChatResponse.ResponseMessage();
                msg.setRole("assistant");

                if (!toolCalls.get().isEmpty()) {
                    msg.setToolCalls(toolCalls.get());
                    msg.setContent("");
                } else {
                    msg.setContent(textBuffer.get().toString());
                }
                finalResp.setMessage(msg);
                results.add(finalResp);
            }
            }
            return Flux.fromIterable(results);
        });
    }

    private OllamaChatResponse createStreamChunk(String modelName, String text, boolean done) {
        var resp = new OllamaChatResponse();
        resp.setModel(modelName);
        resp.setCreatedAt(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        resp.setDone(done);

        var msg = new OllamaChatResponse.ResponseMessage();
        msg.setRole("assistant");
        msg.setContent(text);
        resp.setMessage(msg);
        return resp;
    }

    // ========== 协议转换：Ollama → Anthropic ==========

    private AnthropicRequest convertRequest(OllamaChatRequest ollamaReq) {
        var req = new AnthropicRequest();
        req.setModel(resolveModel(ollamaReq.getModel()));
        req.setMaxTokens(resolveMaxTokens(ollamaReq.getOptions()));
        req.setStream(false);

        List<AnthropicRequest.SystemContent> systemParts = new ArrayList<>();
        List<AnthropicRequest.Message> messages = new ArrayList<>();

        for (var ollamaMsg : ollamaReq.getMessages()) {
            String role = ollamaMsg.getRole();

            if ("system".equals(role)) {
                String content = extractStringContent(ollamaMsg.getContent());
                systemParts.add(new AnthropicRequest.SystemContent(content));
            } else if ("tool".equals(role)) {
                var toolResultContent = new ArrayList<>();
                var block = new HashMap<String, Object>();
                block.put("type", "tool_result");
                block.put("tool_use_id", "toolu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                block.put("content", extractStringContent(ollamaMsg.getContent()));
                toolResultContent.add(block);
                messages.add(new AnthropicRequest.Message("user", toolResultContent));
            } else {
                List<Object> contentBlocks = new ArrayList<>();

                if (ollamaMsg.getToolCalls() != null) {
                    for (var tc : ollamaMsg.getToolCalls()) {
                        var toolUseBlock = new HashMap<String, Object>();
                        toolUseBlock.put("type", "tool_use");
                        toolUseBlock.put("id",
                                "toolu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                        toolUseBlock.put("name", tc.getFunction().getName());
                        toolUseBlock.put("input",
                                tc.getFunction().getArguments() != null ? tc.getFunction().getArguments() : Map.of());
                        contentBlocks.add(toolUseBlock);
                    }
                }

                String text = extractStringContent(ollamaMsg.getContent());
                if (text != null && !text.isEmpty()) {
                    if (contentBlocks.isEmpty()) {
                        messages.add(new AnthropicRequest.Message(role, text));
                        continue;
                    }
                    var textBlock = new HashMap<String, Object>();
                    textBlock.put("type", "text");
                    textBlock.put("text", text);
                    contentBlocks.add(0, textBlock);
                }

                if (!contentBlocks.isEmpty()) {
                    messages.add(new AnthropicRequest.Message(role, contentBlocks));
                }
            }
        }

        if (!systemParts.isEmpty()) {
            req.setSystem(systemParts);
        }
        req.setMessages(messages);

        if (ollamaReq.getTools() != null && !ollamaReq.getTools().isEmpty()) {
            List<AnthropicRequest.Tool> tools = new ArrayList<>();
            for (var ollamaTool : ollamaReq.getTools()) {
                if (ollamaTool.getFunction() != null) {
                    var tool = new AnthropicRequest.Tool();
                    tool.setName(ollamaTool.getFunction().getName());
                    tool.setDescription(ollamaTool.getFunction().getDescription());
                    tool.setInputSchema(ollamaTool.getFunction().getParameters());
                    tools.add(tool);
                }
            }
            req.setTools(tools);
        }

        return req;
    }

    // ========== 协议转换：Anthropic → Ollama（非流式） ==========

    private OllamaChatResponse convertResponse(AnthropicResponse anthropicResp, String requestModel) {
        var resp = new OllamaChatResponse();
        resp.setModel(requestModel);
        resp.setCreatedAt(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        resp.setDone(true);
        resp.setDoneReason("stop".equals(anthropicResp.getStopReason()) ? "stop" : "tool_calls");

        var msg = new OllamaChatResponse.ResponseMessage();
        msg.setRole("assistant");

        if (anthropicResp.getContent() != null) {
            StringBuilder textBuilder = new StringBuilder();
            List<OllamaChatResponse.ToolCallResult> toolCalls = new ArrayList<>();

            for (var block : anthropicResp.getContent()) {
                if ("text".equals(block.getType())) {
                    textBuilder.append(block.getText());
                } else if ("thinking".equals(block.getType())) {
                    msg.setThinking(block.getThinking());
                } else if ("tool_use".equals(block.getType())) {
                    var tc = new OllamaChatResponse.ToolCallResult();
                    var fn = new OllamaChatResponse.ToolCallFunction();
                    fn.setName(block.getName());
                    fn.setArguments(block.getInput());
                    tc.setFunction(fn);
                    toolCalls.add(tc);
                }
            }

            msg.setContent(textBuilder.toString());
            if (!toolCalls.isEmpty()) {
                msg.setToolCalls(toolCalls);
            }
        }

        resp.setMessage(msg);
        return resp;
    }

    // ========== 辅助方法 ==========

    private String resolveModel(String ollamaModel) {
        if (ollamaModel == null || ollamaModel.isBlank()) {
            return getDefaultModel();
        }
        return ollamaModel;
    }

    private int resolveMaxTokens(Map<String, Object> options) {
        if (options != null && options.containsKey("num_predict")) {
            Object val = options.get("num_predict");
            if (val instanceof Number n) {
                int v = n.intValue();
                return v > 0 ? v : 8192;
            }
        }
        return 8192;
    }

    private String extractStringContent(Object content) {
        if (content == null)
            return "";
        if (content instanceof String s)
            return s;
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object text = map.get("text");
                    if (text != null)
                        sb.append(text);
                }
            }
            return sb.toString();
        }
        return content.toString();
    }
}

package com.kaixuan.copilot_ollama_proxy.provider.longcat.ollama;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.ollama.OllamaService;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaTagsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LongCat Ollama 协议实现 —— 将 Ollama 格式请求转换为 OpenAI 格式调用 LongCat API，
 * 再将 OpenAI 格式响应转换回 Ollama 格式。
 */
@Service @ConditionalOnProperty(name = "proxy.provider", havingValue = "longcat")
public class LongCatOllamaService implements OllamaService {

    private static final Logger log = LoggerFactory.getLogger(LongCatOllamaService.class);

    private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE = new ParameterizedTypeReference<>() {
    };

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public LongCatOllamaService(@Value("${longcat.api-key}") String apiKey,
            @Value("${longcat.base-url}") String baseUrl, @Value("${longcat.default-model}") String defaultModel,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
        String normalizedUrl = baseUrl.replaceAll("/+$", "") + "/openai";
        this.webClient = WebClient.builder().baseUrl(normalizedUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
    }

    // ========== 模型管理 ==========

    @Override
    public Mono<OllamaTagsResponse> listModels() {
        var response = new OllamaTagsResponse();
        var models = List.of(createModelInfo("LongCat-Flash-Chat", "LongCat-Flash-Chat", "Flash", "LongCat"),
                createModelInfo("LongCat-Flash-Thinking", "LongCat-Flash-Thinking", "Flash", "LongCat"),
                createModelInfo("LongCat-Flash-Thinking-2601", "LongCat-Flash-Thinking-2601", "Flash", "LongCat"),
                createModelInfo("LongCat-Flash-Lite", "LongCat-Flash-Lite", "Flash", "LongCat"),
                createModelInfo("LongCat-2.0-Preview", "LongCat-2.0-Preview", "2.0", "LongCat"),
                createModelInfo("LongCat-Flash-Omni-2603", "LongCat-Flash-Omni-2603", "Flash", "LongCat"));
        response.setModels(models);
        return Mono.just(response);
    }

    private OllamaTagsResponse.ModelInfo createModelInfo(String name, String model, String paramSize, String family) {
        var info = new OllamaTagsResponse.ModelInfo();
        info.setName(name);
        info.setModel(model);
        info.setModifiedAt(Instant.now().toString());
        info.setSize(0);
        info.setDigest("sha256:" + UUID.randomUUID().toString().replace("-", ""));
        var details = new OllamaTagsResponse.ModelDetails();
        details.setFormat("longcat");
        details.setFamily(family);
        details.setFamilies(List.of(family));
        details.setParameterSize(paramSize);
        details.setQuantizationLevel("none");
        info.setDetails(details);
        return info;
    }

    @Override
    public OllamaShowResponse showModel(String modelName) {
        String resolvedModel = (modelName == null || modelName.isBlank()) ? defaultModel : modelName;

        int contextLength;
        List<String> capabilities;

        if (resolvedModel.contains("Omni")) {
            contextLength = 8192;
            capabilities = List.of("completion", "tools", "vision");
        } else if (resolvedModel.contains("2.0-Preview")) {
            contextLength = 128000;
            capabilities = List.of("completion", "tools");
        } else {
            contextLength = 262144;
            capabilities = List.of("completion", "tools");
        }

        var response = new OllamaShowResponse();
        response.setParameters("temperature 0.7\nnum_ctx " + contextLength);
        response.setLicense("Proprietary");
        response.setModifiedAt(Instant.now().toString());
        response.setTemplate("{{ .System }}\n{{ .Prompt }}");
        response.setCapabilities(capabilities);

        var details = new OllamaShowResponse.ShowDetails();
        details.setParentModel("");
        details.setFormat("longcat");
        details.setFamily("LongCat");
        details.setFamilies(List.of("LongCat"));
        details.setParameterSize("Flash");
        details.setQuantizationLevel("none");
        response.setDetails(details);

        String arch = "longcat";
        response.setModelInfo(Map.of("general.architecture", arch, "general.basename", resolvedModel,
                arch + ".context_length", contextLength, arch + ".embedding_length", 8192));

        return response;
    }

    // ========== 非流式对话 ==========

    @Override
    public Mono<OllamaChatResponse> chat(OllamaChatRequest request) {
        if (request.isStream()) {
            return Mono.error(new UnsupportedOperationException("Use chatStream() for streaming"));
        }

        Map<String, Object> openAiRequest = convertOllamaToOpenAi(request);
        log.info("LongCat Ollama→OpenAI，模型: {}, 流式: false", openAiRequest.get("model"));

        return webClient.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(openAiRequest).retrieve().bodyToMono(String.class).retryWhen(buildRetrySpec("chat"))
                .map(respJson -> convertOpenAiToOllama(respJson, request.getModel()));
    }

    // ========== 流式对话 ==========

    @SuppressWarnings("unchecked") @Override
    public Flux<OllamaChatResponse> chatStream(OllamaChatRequest request) {
        Map<String, Object> openAiRequest = convertOllamaToOpenAi(request);
        openAiRequest.put("stream", true);
        log.info("LongCat Ollama→OpenAI，模型: {}, 流式: true", openAiRequest.get("model"));

        AtomicReference<StringBuilder> textBuffer = new AtomicReference<>(new StringBuilder());
        AtomicBoolean contentEmitted = new AtomicBoolean(false);
        StringBuilder reasoningBuffer = new StringBuilder();
        AtomicReference<List<OllamaChatResponse.ToolCallResult>> toolCalls = new AtomicReference<>(new ArrayList<>());
        AtomicReference<String> currentToolName = new AtomicReference<>();
        AtomicReference<Map<String, Object>> currentToolInput = new AtomicReference<>();

        return webClient.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(openAiRequest).retrieve().bodyToFlux(STRING_SSE_TYPE)
                .retryWhen(buildRetrySpec("chatStream")).mapNotNull(ServerSentEvent::data)
                .filter(chunk -> !chunk.isBlank()).concatMap(chunk -> {
                    List<OllamaChatResponse> results = new ArrayList<>();

                    if ("[DONE]".equals(chunk)) {
                        results.add(createDoneChunk(request.getModel(), textBuffer.get().toString(), toolCalls.get()));
                        return Flux.fromIterable(results);
                    }

                    try {
                        Map<String, Object> parsed = objectMapper.readValue(chunk, Map.class);
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
                        if (choices == null || choices.isEmpty()) {
                            return Flux.fromIterable(results);
                        }

                        Map<String, Object> choice = choices.get(0);
                        Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
                        String finishReason = (String) choice.get("finish_reason");

                        if (delta != null) {
                            // 处理正文内容
                            Object contentObj = delta.get("content");
                            if (contentObj instanceof String content && !content.isEmpty()) {
                                textBuffer.get().append(content);
                                contentEmitted.set(true);
                                results.add(createStreamChunk(request.getModel(), content, false));
                            }

                            // 处理思考内容
                            Object reasoningObj = delta.get("reasoning_content");
                            if (reasoningObj instanceof String reasoning && !reasoning.isEmpty()) {
                                reasoningBuffer.append(reasoning);
                            }

                            // 处理工具调用
                            List<Map<String, Object>> deltaToolCalls = (List<Map<String, Object>>) delta
                                    .get("tool_calls");
                            if (deltaToolCalls != null) {
                                for (Map<String, Object> tc : deltaToolCalls) {
                                    Map<String, Object> func = (Map<String, Object>) tc.get("function");
                                    if (func != null) {
                                        if (func.containsKey("name") && func.get("name") != null) {
                                            currentToolName.set((String) func.get("name"));
                                            currentToolInput.set(new LinkedHashMap<>());
                                        }
                                        Object argsObj = func.get("arguments");
                                        if (argsObj instanceof String args && !args.isEmpty()
                                                && currentToolInput.get() != null) {
                                            try {
                                                Map<String, Object> partial = objectMapper.readValue(args,
                                                        new TypeReference<>() {
                                                        });
                                                currentToolInput.get().putAll(partial);
                                            } catch (Exception e) {
                                                log.debug("工具参数 JSON 片段累积: {}", args);
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // finish_reason=tool_calls 时收集工具调用
                        if ("tool_calls".equals(finishReason) && currentToolName.get() != null) {
                            var tc = new OllamaChatResponse.ToolCallResult();
                            var fn = new OllamaChatResponse.ToolCallFunction();
                            fn.setName(currentToolName.get());
                            fn.setArguments(currentToolInput.get() != null ? currentToolInput.get() : Map.of());
                            tc.setFunction(fn);
                            toolCalls.get().add(tc);
                            currentToolName.set(null);
                            currentToolInput.set(null);
                        }

                        // stop 时回退逻辑
                        if ("stop".equals(finishReason) && !contentEmitted.get() && !reasoningBuffer.isEmpty()) {
                            log.warn("模型未输出正文，回退使用思考内容作为回复 (长度: {})", reasoningBuffer.length());
                            results.add(createStreamChunk(request.getModel(), reasoningBuffer.toString(), false));
                            results.add(
                                    createDoneChunk(request.getModel(), reasoningBuffer.toString(), toolCalls.get()));
                        }
                    } catch (Exception e) {
                        log.warn("LongCat SSE 解析失败: {}", chunk, e);
                    }

                    return Flux.fromIterable(results);
                });
    }

    // ========== Ollama → OpenAI 请求转换 ==========
    private Map<String, Object> convertOllamaToOpenAi(OllamaChatRequest ollamaReq) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", resolveModel(ollamaReq.getModel()));
        body.put("max_tokens", resolveMaxTokens(ollamaReq.getOptions()));

        List<Map<String, Object>> messages = new ArrayList<>();

        for (var ollamaMsg : ollamaReq.getMessages()) {
            String role = ollamaMsg.getRole();

            if ("system".equals(role)) {
                messages.add(Map.of("role", "system", "content", extractStringContent(ollamaMsg.getContent())));
            } else if ("tool".equals(role)) {
                messages.add(Map.of("role", "tool", "content", extractStringContent(ollamaMsg.getContent())));
            } else if ("assistant".equals(role) && ollamaMsg.getToolCalls() != null) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "assistant");
                String text = extractStringContent(ollamaMsg.getContent());
                msg.put("content", text != null ? text : "");
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                for (var tc : ollamaMsg.getToolCalls()) {
                    Map<String, Object> toolCall = new LinkedHashMap<>();
                    toolCall.put("id", "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
                    toolCall.put("type", "function");
                    String argsJson;
                    try {
                        argsJson = tc.getFunction().getArguments() != null
                                ? objectMapper.writeValueAsString(tc.getFunction().getArguments())
                                : "{}";
                    } catch (Exception e) {
                        argsJson = "{}";
                    }
                    toolCall.put("function", Map.of("name", tc.getFunction().getName(), "arguments", argsJson));
                    toolCalls.add(toolCall);
                }
                msg.put("tool_calls", toolCalls);
                messages.add(msg);
            } else {
                String text = extractStringContent(ollamaMsg.getContent());
                messages.add(Map.of("role", role, "content", text != null ? text : ""));
            }
        }

        body.put("messages", messages);

        // 转换工具定义
        if (ollamaReq.getTools() != null && !ollamaReq.getTools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (var ollamaTool : ollamaReq.getTools()) {
                if (ollamaTool.getFunction() != null) {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("type", "function");
                    Map<String, Object> func = new LinkedHashMap<>();
                    func.put("name", ollamaTool.getFunction().getName());
                    if (ollamaTool.getFunction().getDescription() != null) {
                        func.put("description", ollamaTool.getFunction().getDescription());
                    }
                    if (ollamaTool.getFunction().getParameters() != null) {
                        func.put("parameters", ollamaTool.getFunction().getParameters());
                    }
                    tool.put("function", func);
                    tools.add(tool);
                }
            }
            body.put("tools", tools);
        }

        return body;
    }

    // ========== OpenAI → Ollama 响应转换（非流式） ==========

    @SuppressWarnings("unchecked")
    private OllamaChatResponse convertOpenAiToOllama(String openAiJson, String requestModel) {
        try {
            Map<String, Object> openAi = objectMapper.readValue(openAiJson, Map.class);

            var resp = new OllamaChatResponse();
            resp.setModel(requestModel);
            resp.setCreatedAt(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            resp.setDone(true);

            List<Map<String, Object>> choices = (List<Map<String, Object>>) openAi.get("choices");
            if (choices == null || choices.isEmpty()) {
                resp.setDoneReason("stop");
                resp.setMessage(createMessage("assistant", ""));
                return resp;
            }

            Map<String, Object> choice = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            String finishReason = (String) choice.get("finish_reason");

            resp.setDoneReason("tool_calls".equals(finishReason) ? "tool_calls" : "stop");

            var msg = new OllamaChatResponse.ResponseMessage();
            msg.setRole("assistant");

            if (message != null) {
                Object contentObj = message.get("content");
                msg.setContent(contentObj instanceof String s ? s : "");

                // 处理思考内容
                Object reasoningObj = message.get("reasoning_content");
                if (reasoningObj instanceof String reasoning && !reasoning.isEmpty()) {
                    msg.setThinking(reasoning);
                }

                // 处理工具调用
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    List<OllamaChatResponse.ToolCallResult> tcResults = new ArrayList<>();
                    for (var tc : toolCalls) {
                        Map<String, Object> func = (Map<String, Object>) tc.get("function");
                        if (func != null) {
                            var tcResult = new OllamaChatResponse.ToolCallResult();
                            var fn = new OllamaChatResponse.ToolCallFunction();
                            fn.setName((String) func.get("name"));
                            Object argsObj = func.get("arguments");
                            if (argsObj instanceof String argsStr) {
                                fn.setArguments(objectMapper.readValue(argsStr, new TypeReference<>() {
                                }));
                            } else if (argsObj instanceof Map) {
                                fn.setArguments((Map<String, Object>) argsObj);
                            } else {
                                fn.setArguments(Map.of());
                            }
                            tcResult.setFunction(fn);
                            tcResults.add(tcResult);
                        }
                    }
                    msg.setToolCalls(tcResults);
                }
            }

            resp.setMessage(msg);
            return resp;
        } catch (Exception e) {
            log.error("OpenAI → Ollama 转换失败", e);
            var resp = new OllamaChatResponse();
            resp.setModel(requestModel);
            resp.setCreatedAt(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            resp.setDone(true);
            resp.setDoneReason("stop");
            resp.setMessage(createMessage("assistant", "转换失败: " + e.getMessage()));
            return resp;
        }
    }

    // ========== 辅助方法 ==========

    private OllamaChatResponse createStreamChunk(String modelName, String text, boolean done) {
        var resp = new OllamaChatResponse();
        resp.setModel(modelName);
        resp.setCreatedAt(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        resp.setDone(done);
        resp.setMessage(createMessage("assistant", text));
        return resp;
    }

    private OllamaChatResponse createDoneChunk(String modelName, String fullText,
            List<OllamaChatResponse.ToolCallResult> toolCalls) {
        var resp = new OllamaChatResponse();
        resp.setModel(modelName);
        resp.setCreatedAt(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        resp.setDone(true);
        resp.setDoneReason(!toolCalls.isEmpty() ? "tool_calls" : "stop");

        var msg = new OllamaChatResponse.ResponseMessage();
        msg.setRole("assistant");
        if (!toolCalls.isEmpty()) {
            msg.setToolCalls(toolCalls);
            msg.setContent("");
        } else {
            msg.setContent(fullText);
        }
        resp.setMessage(msg);
        return resp;
    }

    private OllamaChatResponse.ResponseMessage createMessage(String role, String content) {
        var msg = new OllamaChatResponse.ResponseMessage();
        msg.setRole(role);
        msg.setContent(content);
        return msg;
    }

    private String resolveModel(String ollamaModel) {
        if (ollamaModel == null || ollamaModel.isBlank()) {
            return defaultModel;
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

    private Retry buildRetrySpec(String method) {
        return Retry.fixedDelay(5, Duration.ofSeconds(5))
                .filter(ex -> (ex instanceof WebClientResponseException
                        && (((WebClientResponseException) ex).getStatusCode().is5xxServerError()
                                || ((WebClientResponseException) ex).getStatusCode().value() == 400))
                        || ex instanceof WebClientRequestException)
                .doBeforeRetry(signal -> log.warn("[{}] LongCat API 调用失败，重试第 {} 次: {}", method,
                        signal.totalRetries() + 1, signal.failure().getMessage()));
    }
}

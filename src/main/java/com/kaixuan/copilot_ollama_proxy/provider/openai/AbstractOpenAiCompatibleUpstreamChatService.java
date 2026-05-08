package com.kaixuan.copilot_ollama_proxy.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.openai.UpstreamChatService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI 兼容上游服务的公共基类。
 *
 * 这个类封装了所有与 OpenAI Chat Completions 协议交互的公共逻辑：
 * WebClient 构建、鉴权头注入、默认 Base URL 回退、请求体准备、
 * SSE 流式解包、reasoning fallback 以及统一的重试策略。
 *
 * 子类只需实现四个模板方法即可接入一个新的 OpenAI 兼容 provider：
 * {@link #defaultBaseUrl()}、{@link #normalizeBaseUrl(String)}、
 * {@link #applyAuthenticationHeaders} 和 {@link #chatCompletionsUri()}。
 * 如果需要在请求体中添加 provider 特有字段，可以覆写 {@link #customizeRequestBody}。
 *
 * 运行时配置（API Key、Base URL、模型列表）通过 {@link RuntimeProviderCatalog} 从数据库动态加载。
 */
public abstract class AbstractOpenAiCompatibleUpstreamChatService implements UpstreamChatService {

    /** SSE 场景下，每个 data 字段的原始字符串类型引用。 */
    private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE = new ParameterizedTypeReference<>() {
    };

    /** 子类可直接使用的 logger，自动绑定到实际子类的类名。 */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** Jackson 对象映射器，用于 SSE chunk 的 JSON 解析与序列化。 */
    protected final ObjectMapper objectMapper;

    /** 当请求中未指定模型时回退使用的默认模型名称。 */
    private final String fallbackDefaultModel;

    /** 运行时 provider 配置目录，统一暴露数据库中的 provider 配置。 */
    private final RuntimeProviderCatalog runtimeProviderCatalog;

    /**
     * @param runtimeProviderCatalog 运行时 provider 配置目录
     * @param objectMapper Jackson 对象映射器
     * @param fallbackDefaultModel 当请求中未指定模型时使用的默认模型名称
     */
    protected AbstractOpenAiCompatibleUpstreamChatService(RuntimeProviderCatalog runtimeProviderCatalog, ObjectMapper objectMapper, String fallbackDefaultModel) {
        this.runtimeProviderCatalog = runtimeProviderCatalog;
        this.objectMapper = objectMapper;
        this.fallbackDefaultModel = fallbackDefaultModel;
    }

    @Override
    public boolean supportsModel(String modelName) {
        ProviderRuntimeConfiguration config = getActiveProviderConfiguration();
        return config != null && config.supportsModel(modelName);
    }

    /**
     * 发送一次非流式 Chat Completions 请求。
     *
     * 请求体会经过 {@link #prepareRequestBody} 处理，包括模型名称解析、
     * stream 标志设置和子类的自定义字段注入。
     *
     * @param openAiRequest 原始 OpenAI 格式请求体
     * @param model 请求中指定的模型名称
     * @return 上游返回的原始 OpenAI JSON 响应字符串
     */
    @Override
    public Mono<String> chatCompletion(Map<String, Object> openAiRequest, String model) {
        // 准备请求体，解析模型名称，设置流式标志，并调用 customizeRequestBody 进行特定服务的字段定制。
        Map<String, Object> requestBody = prepareRequestBody(openAiRequest, false, model);
        log.info("{} OpenAI 上游，模型: {}, 流式: false", providerDisplayName(), requestBody.get("model"));

        return buildWebClient().post().uri(chatCompletionsUri()).contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody).retrieve().bodyToMono(String.class)
                .retryWhen(buildRetrySpec("chatCompletion")).doOnNext(response -> log.debug("{} 响应: {}", providerDisplayName(), response));
    }

    /**
     * 发送一次流式 Chat Completions 请求。
     *
     * 该方法会把上游返回的 SSE data 解包为纯 JSON 字符串流，
     * 并在流式过程中处理 reasoning_content 的提取与回退：
     * 如果模型只输出了思考内容而没有正文，则在流末尾自动把思考内容作为回复输出。
     *
     * @param openAiRequest 原始 OpenAI 格式请求体
     * @param model 请求中指定的模型名称
     * @return 按顺序发出的 chunk JSON 字符串，最后一个元素为 "[DONE]"
     */
    @Override
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
        Map<String, Object> requestBody = prepareRequestBody(openAiRequest, true, model);
        log.info("{} OpenAI 上游，模型: {}, 流式: true", providerDisplayName(), requestBody.get("model"));

        AtomicBoolean reasoningEmitted = new AtomicBoolean(false);
        AtomicBoolean contentEmitted = new AtomicBoolean(false);
        StringBuilder reasoningBuffer = new StringBuilder();
        AtomicReference<String> chunkId = new AtomicReference<>("chatcmpl-unknown");
        AtomicReference<String> finishReason = new AtomicReference<>(null);

        return buildWebClient().post().uri(chatCompletionsUri()).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).bodyValue(requestBody).retrieve()
                .bodyToFlux(STRING_SSE_TYPE).retryWhen(buildRetrySpec("chatCompletionStream")).mapNotNull(ServerSentEvent::data).filter(chunk -> !chunk.isBlank())
                .doOnNext(raw -> log.debug("{} 原始: {}", providerDisplayName(), raw)).concatMap(chunk -> {
                    if (!"[DONE]".equals(chunk) && chunk.contains("\"finish_reason\":\"stop\"") && !contentEmitted.get() && !reasoningBuffer.isEmpty()) {
                        log.warn("模型未输出正文，回退使用思考内容作为回复 (长度: {})", reasoningBuffer.length());
                        String fallbackContent = buildFallbackContentChunk(chunkId.get(), model, reasoningBuffer.toString());
                        String fallbackFinish = buildFallbackFinishChunk(chunkId.get(), model);
                        return Flux.just(fallbackContent, fallbackFinish);
                    }
                    return Flux.just(translateChunk(chunk, reasoningEmitted, contentEmitted, reasoningBuffer, chunkId, finishReason));
                }).doOnNext(chunk -> log.debug("{} 翻译: {}", providerDisplayName(), chunk));
    }

    /**
     * 从运行时配置目录中获取当前激活的 Provider 配置，用于构建 WebClient 和判断支持的模型列表。
     * @return 当前激活的 Provider 配置，如果没有找到则返回 null
     */
    protected ProviderRuntimeConfiguration getActiveProviderConfiguration() {
        return runtimeProviderCatalog.getActiveProvider(getProviderKey());
    }

    /**
     * 构建 WebClient，设置 Base URL 和认证头，Base URL 和认证信息从运行时配置中动态读取。
     * @return 配置好的 WebClient 实例，用于发送请求到上游服务
     */
    protected WebClient buildWebClient() {
        ProviderRuntimeConfiguration config = getActiveProviderConfiguration();
        String apiKey = config != null ? config.apiKey() : "";
        String baseUrl = (config == null || config.baseUrl().isBlank()) ? defaultBaseUrl() : config.baseUrl();
        String normalizedUrl = normalizeBaseUrl(baseUrl);

        return WebClient.builder().baseUrl(normalizedUrl).defaultHeaders(headers -> {
            applyAuthenticationHeaders(headers, apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
        }).build();
    }

    /**
     * 准备请求体，解析模型名称，设置流式标志，并调用 customizeRequestBody 进行特定服务的字段定制。
     * @param openAiRequest 请求体的初始 Map 结构
     * @param stream 是否启用流式响应
     * @param model 模型名称
     * @return 最终准备好的请求体 Map 结构，已经解析了模型名称并设置了流式标志
     */
    protected Map<String, Object> prepareRequestBody(Map<String, Object> openAiRequest, boolean stream, String model) {
        Map<String, Object> body = new LinkedHashMap<>(openAiRequest);
        String resolvedModel = resolveModel(body.get("model"), model);
        body.put("model", resolvedModel);
        body.put("stream", stream);
        body.values().removeIf(Objects::isNull);
        customizeRequestBody(body, resolvedModel);
        return body;
    }

    /**
     * 子类可以重写此方法在请求体中添加特定的字段或格式转换，例如将模型名称转换为特定服务识别的格式。
     * @param body 请求体的 Map 结构，子类可以直接修改该 Map 来添加或修改字段
     * @param resolvedModel 已经解析出的模型名称，子类可以根据该名称来决定是否进行特定的字段添加或格式转换
     */
    protected void customizeRequestBody(Map<String, Object> body, String resolvedModel) {
    }

    /**
     * 解析请求中的模型名称，如果请求中没有指定模型或指定的模型名称无效，则使用提供的 fallbackModel 进行回退，如果 fallbackModel 也无效则使用全局默认模型。
     * @param requestModel 请求中指定的模型名称，可能为 null 或空字符串
     * @param fallbackModel 提供的回退模型名称，可能为 null 或空字符串
     * @return 最终解析出的模型名称，保证不为 null 或空字符串
     */
    protected String resolveModel(Object requestModel, String fallbackModel) {
        if (requestModel instanceof String value && !value.isBlank()) {
            return value;
        }
        if (fallbackModel != null && !fallbackModel.isBlank()) {
            return fallbackModel;
        }
        return fallbackDefaultModel;
    }

    /**
     * 构建 OpenAI 上游的统一重试策略。
     *
     * 覆盖三类可恢复场景：
     * 1. 5xx 服务端错误
     * 2. 可重试的 400 错误
     * 3. 网络层异常：包括 WebClientRequestException（连接建立失败）
     *    以及 WebClientResponseException 的 cause chain 中的 IOException
     *    （如 SocketException: Connection reset，即 HTTP 200 但 SSE 流中途断开）
     *
     * @param method 调用方方法名，用于日志区分重试来源
     * @return 配置好的 Retry 实例
     */
    protected Retry buildRetrySpec(String method) {
        return Retry.fixedDelay(5, Duration.ofSeconds(5))
                .filter(ex -> ((ex instanceof WebClientResponseException responseException)
                        && (responseException.getStatusCode().is5xxServerError() || responseException.getStatusCode().value() == 400 || hasNetworkCause(responseException)))
                        || ex instanceof WebClientRequestException)
                .doBeforeRetry(signal -> log.warn("[{}] {} API 调用失败，重试第 {} 次: {}", method, providerDisplayName(), signal.totalRetries() + 1, signal.failure().getMessage()));
    }

    /**
     * 判断异常的 cause chain 中是否包含网络层异常（IOException 及其子类，如 SocketException）。
     *
     * 这类异常通常表现为 HTTP 200 但 SSE 流中途断开，需要重试。
     */
    private static boolean hasNetworkCause(Throwable throwable) {
        Throwable cause = throwable.getCause();
        while (cause != null) {
            if (cause instanceof java.io.IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 子类可覆写此方法提供更友好的服务显示名称，默认返回 providerKey。
     *
     * @return 服务显示名称，用于日志输出
     */
    protected String providerDisplayName() {
        return getProviderKey();
    }

    /**
     * 提供默认的 Base URL，当运行时配置中未指定地址时使用。
     *
     * @return 默认 Base URL，以协议开头，不含路径后缀
     */
    protected abstract String defaultBaseUrl();

    /**
     * 规范化 Base URL，确保最终地址符合上游端点要求。
     *
     * 子类通常在这里追加 provider 特有的路径前缀（如 "/openai"），
     * 并去除多余的尾部斜杠。
     *
     * @param rawBaseUrl 原始 Base URL
     * @return 规范化后的 Base URL
     */
    protected abstract String normalizeBaseUrl(String rawBaseUrl);

    /**
     * 在请求头中添加 provider 特有的认证信息。
     *
     * @param headers 请求头对象，子类直接修改即可
     * @param apiKey 从运行时配置读取的 API Key，可能为空串
     */
    protected abstract void applyAuthenticationHeaders(HttpHeaders headers, String apiKey);

    /**
     * 提供 Chat Completions 端点的 URI 路径。
     *
     * @return 端点 URI，如 "/v1/chat/completions"
     */
    protected abstract String chatCompletionsUri();

    /**
     * 翻译单个 SSE chunk 的 JSON 内容。
     *
     * 这里有两个关键职责：
     * 1. 把 reasoning_content 从 delta 中提取出来，转成 reasoning_opaque/reasoning_text 字段，
     *    这样上层客户端可以识别并展示思考过程。
     * 2. 如果模型只输出了 reasoning_content 而没有正文 content，
     *    则在流末尾由调用方触发回退，把思考内容作为最终回复。
     *
     * @param chunkJson 原始 SSE data 的 JSON 字符串
     * @param reasoningEmitted 是否已经输出过 reasoning_opaque 标记
     * @param contentEmitted 是否已经输出过正文 content
     * @param reasoningBuffer 累积 reasoning_content 的缓冲区
     * @param chunkId 当前流的 chunk ID 引用
     * @param finishReason 当前流的 finish_reason 引用
     * @return 翻译后的 chunk JSON 字符串
     */
    @SuppressWarnings("unchecked")
    private String translateChunk(String chunkJson, AtomicBoolean reasoningEmitted, AtomicBoolean contentEmitted, StringBuilder reasoningBuffer, AtomicReference<String> chunkId,
            AtomicReference<String> finishReason) {
        try {
            Map<String, Object> chunk = objectMapper.readValue(chunkJson, Map.class);

            // 记录 chunk ID，供 reasoning fallback 构建伪 chunk 时保持一致性。
            Object id = chunk.get("id");
            if (id instanceof String idStr && !idStr.isEmpty()) {
                chunkId.set(idStr);
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
            if (choices == null || choices.isEmpty()) {
                return chunkJson;
            }

            // delta 为 null 说明是纯 finish_reason chunk，直接透传。
            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
            if (delta == null) {
                Object fr = choices.get(0).get("finish_reason");
                if (fr instanceof String reason) {
                    finishReason.set(reason);
                }
                return chunkJson;
            }

            // 标记是否已经输出过正文 content，用于判断是否需要 reasoning fallback。
            Object contentObj = delta.get("content");
            if (contentObj instanceof String content && !content.isEmpty()) {
                contentEmitted.set(true);
            }

            Object fr = choices.get(0).get("finish_reason");
            if (fr instanceof String reason) {
                finishReason.set(reason);
            }

            // reasoning_content 需要转成 reasoning_opaque/reasoning_text 字段，
            // 这样上层客户端可以识别并展示思考过程。
            Object reasoningObj = delta.get("reasoning_content");
            if (reasoningObj instanceof String reasoning && !reasoning.isEmpty()) {
                reasoningBuffer.append(reasoning);
                delta.remove("reasoning_content");
                delta.put("content", null);
                if (!reasoningEmitted.getAndSet(true)) {
                    delta.put("reasoning_opaque", "thinking");
                }
                delta.put("reasoning_text", reasoning);
                return objectMapper.writeValueAsString(chunk);
            }

            // 普通 content chunk 直接透传。
            return chunkJson;
        } catch (Exception exception) {
            return chunkJson;
        }
    }

    /**
     * 构建 reasoning fallback 的正文 chunk。
     *
     * 当模型只输出了思考内容而没有正文时，用思考内容构造一个伪 content delta，
     * 使客户端看到的回复内容就是模型的思考过程。
     *
     * @param id 当前流的 chunk ID
     * @param model 模型名称
     * @param reasoningContent 累积的思考内容
     * @return OpenAI chunk JSON 字符串
     */
    private String buildFallbackContentChunk(String id, String model, String reasoningContent) {
        try {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);

            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("role", "assistant");
            delta.put("content", reasoningContent);

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", null);

            chunk.put("choices", List.of(choice));
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception exception) {
            return "{}";
        }
    }

    /**
     * 构建 reasoning fallback 的 finish chunk。
     *
     * 紧跟在 {@link #buildFallbackContentChunk} 之后发出，标记流的结束。
     *
     * @param id 当前流的 chunk ID
     * @param model 模型名称
     * @return OpenAI chunk JSON 字符串，finish_reason 为 "stop"
     */
    private String buildFallbackFinishChunk(String id, String model) {
        try {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);

            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("role", null);
            delta.put("content", null);

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", "stop");

            chunk.put("choices", List.of(choice));
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception exception) {
            return "{}";
        }
    }
}
package com.kaixuan.copilot_ollama_proxy.provider.mimo.ollama;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.ollama.OllamaService;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 核心代理服务 —— 负责协议转换和上游 API 调用。
 * 本类是整个项目的灵魂，承担三大职责：
 *   模型信息管理：提供模型列表（{@link #listModels}）和模型详情（{@link #showModel}）
 *   请求协议转换：将 Ollama 格式请求转为 Anthropic 格式（{@link #convertRequest}）
 *   响应协议转换：将 Anthropic 格式响应转为 Ollama 格式（{@link #convertResponse} 和 {@link #processStreamEvents}）
 * 上游调用通过 Spring WebFlux 的 {@link WebClient} 实现，支持非流式（Mono）和流式（Flux）两种模式。
 * 流式模式下，Mimo 后端通过 SSE（Server-Sent Events）逐步返回内容增量，
 * 本类通过 {@link #processStreamEvents} 逐个处理这些增量，实时转换为 Ollama 的 NDJSON 格式。
 */
@Service @ConditionalOnProperty(name = "proxy.provider", havingValue = "mimo", matchIfMissing = true)
public class MimoOllamaService implements OllamaService {

    private static final Logger log = LoggerFactory.getLogger(MimoOllamaService.class);

    private final MimoAnthropicClient anthropicClient;

    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper;

    /** 默认模型名称，当请求中未指定模型时使用 */
    private final String defaultModel;

    /**
     * 构造函数 —— 初始化 WebClient 并注入配置。
     * WebClient 是 Spring WebFlux 提供的非阻塞 HTTP 客户端，
     * 这里预设了 Mimo 后端的 base URL 和认证头，后续调用时无需重复设置。
     */
    public MimoOllamaService(MimoAnthropicClient anthropicClient, @Value("${mimo.default-model}") String defaultModel,
            ObjectMapper objectMapper) {
        this.anthropicClient = anthropicClient;
        this.defaultModel = defaultModel;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回可用模型列表 —— 对应 Copilot 调用 GET /api/tags。
     * 返回的是硬编码的 Mimo 模型信息。每个模型包含名称、参数量等元数据，
     * 这些信息会展示在 Copilot 的模型选择下拉框中。
     * Mono 是 Reactor 的响应式类型，表示"将来会返回一个值"。这里用 Mono.just() 包装，
     * 因为模型列表是静态数据，不需要真正的异步调用。
     */
    @Override
    public Mono<OllamaTagsResponse> listModels() {
        var response = new OllamaTagsResponse();
        var models = List.of(createModelInfo("mimo-v2.5-pro", "mimo-v2.5-pro", "1T/42B", "Mimo"),
                createModelInfo("mimo-v2-pro", "mimo-v2-pro", "1T/42B", "Mimo"),
                createModelInfo("mimo-v2.5", "mimo-v2.5", "1T/42B", "Mimo"),
                createModelInfo("mimo-v2-omni", "mimo-v2-omni", "42B", "Mimo"),
                createModelInfo("mimo-v2-flash", "mimo-v2-flash", "42B", "Mimo"));
        response.setModels(models);
        return Mono.just(response);
    }

    /**
     * 创建单个模型的摘要信息（用于 /api/tags 响应）。
     *
     * @param name      模型显示名称
     * @param model     模型标识符
     * @param paramSize 参数量描述，如 "1T/42B"
     * @param family    模型家族，如 "Mimo"
     */
    private OllamaTagsResponse.ModelInfo createModelInfo(String name, String model, String paramSize, String family) {
        var info = new OllamaTagsResponse.ModelInfo();
        info.setName(name);
        info.setModel(model);
        info.setModifiedAt(Instant.now().toString());
        info.setSize(0);
        info.setDigest("sha256:" + UUID.randomUUID().toString().replace("-", ""));
        var details = new OllamaTagsResponse.ModelDetails();
        details.setFormat("mimo");
        details.setFamily(family);
        details.setFamilies(List.of(family));
        details.setParameterSize(paramSize);
        details.setQuantizationLevel("none");
        info.setDetails(details);
        return info;
    }

    /**
     * 返回模型详细信息 —— 对应 Copilot 调用 POST /api/show。
     * 此方法根据模型名称返回对应的上下文长度、能力、参数量等信息。
     * 其中最关键的是 model_info 中的 {@code <架构名>.context_length} 字段，
     * Copilot 用它来决定单次对话的最大 token 数。
     * Mimo 模型参数（来自官方文档 platform.xiaomimimo.com）：
     * - v2.5-pro / v2-pro / v2.5：1T 总参数 / 42B 激活参数，上下文 1M tokens
     * - v2-omni / v2-flash：42B 参数，上下文 256K tokens
     *
     * @param modelName 模型名称，为 null 或空时使用默认模型
     */
    @Override
    public OllamaShowResponse showModel(String modelName) {
        String resolvedModel = (modelName == null || modelName.isBlank()) ? defaultModel : modelName;

        String family;
        String parameterSize;
        long parameterCount;
        int contextLength;
        List<String> capabilities;

        // 根据模型名称确定参数配置
        if (resolvedModel.contains("v2.5-pro")) {
            family = "Mimo";
            parameterSize = "1T/42B";
            parameterCount = 1_000_000_000_000L;
            contextLength = 1000000; // 1M tokens 上下文
            capabilities = List.of("completion", "tools");
        } else if (resolvedModel.contains("v2.5")) {
            family = "Mimo";
            parameterSize = "1T/42B";
            parameterCount = 1_000_000_000_000L;
            contextLength = 1000000;
            capabilities = List.of("completion", "tools", "vision"); // 支持图片理解
        } else if (resolvedModel.contains("v2-pro")) {
            family = "Mimo";
            parameterSize = "1T/42B";
            parameterCount = 1_000_000_000_000L;
            contextLength = 1000000;
            capabilities = List.of("completion", "tools");
        } else if (resolvedModel.contains("v2-omni")) {
            family = "Mimo";
            parameterSize = "42B";
            parameterCount = 42_000_000_000L;
            contextLength = 256000; // 256K tokens 上下文
            capabilities = List.of("completion", "tools", "vision");
        } else {
            // mimo-v2-flash 及其他未识别模型
            family = "Mimo";
            parameterSize = "42B";
            parameterCount = 42_000_000_000L;
            contextLength = 256000;
            capabilities = List.of("completion", "tools");
        }

        var response = new OllamaShowResponse();
        // parameters 字段：Copilot 会解析 num_ctx 来确定上下文窗口大小
        response.setParameters("temperature 0.7\nnum_ctx " + contextLength);
        response.setLicense("Apache 2.0");
        response.setModifiedAt(Instant.now().toString());
        response.setTemplate("{{ .System }}\n{{ .Prompt }}");
        response.setCapabilities(capabilities);

        var details = new OllamaShowResponse.ShowDetails();
        details.setParentModel("");
        details.setFormat("mimo");
        details.setFamily(family);
        details.setFamilies(List.of(family));
        details.setParameterSize(parameterSize);
        details.setQuantizationLevel("none");
        response.setDetails(details);

        // model_info 中的 key 格式必须遵循 Ollama 规范：<架构名>.context_length
        // Copilot 根据 general.architecture 的值（"mimo"）去查找 mimo.context_length
        String arch = family.toLowerCase();
        response.setModelInfo(
                Map.of("general.architecture", arch, "general.basename", resolvedModel, "general.parameter_count",
                        parameterCount, arch + ".context_length", contextLength, arch + ".embedding_length", 8192));

        return response;
    }

    // ========== 对话接口（非流式） ==========

    /**
     * 非流式对话 —— 等待模型生成完毕后一次性返回完整回复。
     * 调用链：convertRequest() → WebClient POST → convertResponse()
     *
     * @param request Ollama 格式的对话请求
     * @return Ollama 格式的对话响应（Mono 包装）
     */
    @Override
    public Mono<OllamaChatResponse> chat(OllamaChatRequest request) {
        AnthropicRequest anthropicReq = convertRequest(request);
        boolean stream = request.isStream();

        if (stream) {
            return Mono.error(new UnsupportedOperationException("Use chatStream() for streaming"));
        }

        // 发送 POST 请求到 Mimo 后端的 /v1/messages 端点
        // .retrieve() 自动处理 HTTP 错误码
        // .bodyToMono() 将响应体反序列化为 AnthropicResponse 对象
        // .map() 将 Anthropic 格式的响应转换为 Ollama 格式
        return anthropicClient.sendMessage(anthropicReq).map(resp -> convertResponse(resp, request.getModel()));
    }

    // ========== 对话接口（流式） ==========

    /**
     * 流式对话 —— 逐步返回模型生成的增量文本。
     * 调用链：convertRequest() → WebClient POST（SSE） → processStreamEvents()
     * 流式模式的优势：用户可以立即看到模型开始输出，无需等待整个回复生成完毕。
     * Copilot 会逐个接收 NDJSON chunk 并实时渲染。
     *
     * @param request Ollama 格式的对话请求
     * @return Ollama 格式对话响应的 Flux 流（每个元素是一个 NDJSON 行）
     */
    @Override
    public Flux<OllamaChatResponse> chatStream(OllamaChatRequest request) {
        AnthropicRequest anthropicReq = convertRequest(request);
        anthropicReq.setStream(true); // 告诉上游使用 SSE 流式响应

        // bodyToFlux 将 SSE 流反序列化为 AnthropicStreamEvent 对象流
        // .filter 过滤掉 type 为 null 的无效事件
        // .transform 将 Anthropic 事件流转换为 Ollama NDJSON chunk 流
        return anthropicClient.streamMessages(anthropicReq)
                .transform(flux -> processStreamEvents(flux, request.getModel()));
    }

    /**
     * 流式事件处理器 —— 将 Anthropic SSE 事件流转换为 Ollama NDJSON chunk 流。
     * 这是流式模式的核心方法，使用有状态的方式逐个处理事件：
     *   textBuffer：累积所有文本增量，用于最终的 done chunk
     *   toolCalls：累积所有工具调用
     *   currentToolName / currentToolInput：跟踪当前正在解析的工具调用
     * 每收到一个文本增量（content_block_delta + text_delta），就立即发出一个 Ollama chunk，
     * 这样 Copilot 可以实时显示模型正在生成的文本。
     * 最终在 message_stop 事件时发出一个 done=true 的结束 chunk。
     *
     * @param flux     Anthropic SSE 事件流
     * @param modelName 模型名称，写入每个 Ollama chunk 中
     * @return Ollama 格式 chunk 的 Flux 流
     */
    private Flux<OllamaChatResponse> processStreamEvents(Flux<AnthropicStreamEvent> flux, String modelName) {
        // 累积器：用于在事件之间保持状态
        // AtomicReference 保证在 reactive 流中的线程安全
        AtomicReference<StringBuilder> textBuffer = new AtomicReference<>(new StringBuilder());
        AtomicReference<List<OllamaChatResponse.ToolCallResult>> toolCalls = new AtomicReference<>(new ArrayList<>());
        AtomicReference<Map<String, Object>> currentToolInput = new AtomicReference<>();
        AtomicReference<String> currentToolName = new AtomicReference<>();
        AtomicReference<Integer> currentBlockIndex = new AtomicReference<>(-1);

        return flux.concatMap(event -> {
            List<OllamaChatResponse> results = new ArrayList<>();

            switch (event.getType()) {
            case "message_start" -> {
                // 消息开始事件，包含模型元信息，无需输出
            }
            case "content_block_start" -> {
                // 一个新的内容块开始了（可能是 text 或 tool_use）
                Map<String, Object> block = event.getContentBlock();
                if (block != null) {
                    int idx = event.getIndex() != null ? event.getIndex() : 0;
                    currentBlockIndex.set(idx);
                    String blockType = (String) block.get("type");
                    if ("tool_use".equals(blockType)) {
                        // 开始一个新的工具调用块，记录工具名称
                        currentToolName.set((String) block.get("name"));
                        currentToolInput.set(new HashMap<>());
                    }
                }
            }
            case "content_block_delta" -> {
                // 内容增量 —— 这是最频繁的事件，包含实际的文本或工具参数片段
                Map<String, Object> delta = event.getDelta();
                if (delta != null) {
                    String deltaType = (String) delta.get("type");
                    if ("text_delta".equals(deltaType)) {
                        // 文本增量：累积到 textBuffer，同时立即发出一个 Ollama chunk
                        String text = (String) delta.get("text");
                        if (text != null) {
                            textBuffer.get().append(text);
                            results.add(createStreamChunk(modelName, text, false));
                        }
                    } else if ("input_json_delta".equals(deltaType)) {
                        // 工具参数增量：逐步解析 JSON 片段，合并到 currentToolInput
                        String json = (String) delta.get("partial_json");
                        if (json != null && currentToolInput.get() != null) {
                            try {
                                Map<String, Object> partial = objectMapper.readValue(json, new TypeReference<>() {
                                });
                                currentToolInput.get().putAll(partial);
                            } catch (Exception e) {
                                // JSON 片段可能不完整，需要继续累积
                                log.debug("Partial JSON parse, accumulating: {}", json);
                            }
                        }
                    }
                }
            }
            case "content_block_stop" -> {
                // 内容块结束：如果当前是工具调用块，将其保存到 toolCalls 列表
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
                // 消息级别更新，包含 stop_reason 和 usage 信息，无需额外处理
            }
            case "message_stop" -> {
                // 消息结束 —— 发出最终的 done=true chunk
                var finalResp = new OllamaChatResponse();
                finalResp.setModel(modelName);
                finalResp.setCreatedAt(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
                finalResp.setDone(true);
                finalResp.setDoneReason("stop");

                var msg = new OllamaChatResponse.ResponseMessage();
                msg.setRole("assistant");

                if (!toolCalls.get().isEmpty()) {
                    // 如果有工具调用，放在最后的 chunk 中
                    msg.setToolCalls(toolCalls.get());
                    msg.setContent("");
                } else {
                    // 纯文本回复，content 设为完整文本
                    msg.setContent(textBuffer.get().toString());
                }
                finalResp.setMessage(msg);
                results.add(finalResp);
            }
            }
            return Flux.fromIterable(results);
        });
    }

    /**
     * 创建一个流式增量 chunk（done=false）。
     * 每收到一小段文本增量就调用一次，Copilot 会实时拼接显示。
     */
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

    /**
     * 将 Ollama 格式的请求转换为 Anthropic Messages API 格式。
     * 这是协议转换的核心方法，处理两种 API 格式之间的差异：
     *   Ollama 的 system 消息在 messages 数组中 → Anthropic 的 system 是独立顶层字段
     *   Ollama 的 role=tool 消息 → Anthropic 的 user 消息 + tool_result 内容块
     *   Ollama 的 assistant tool_calls → Anthropic 的 tool_use 内容块
     *   Ollama 的 tools[].function.parameters → Anthropic 的 tools[].input_schema
     *
     * @param ollamaReq Ollama 格式请求
     * @return Anthropic 格式请求
     */
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
                // system 消息：提取文本内容，放入 Anthropic 的 system 顶层字段
                String content = extractStringContent(ollamaMsg.getContent());
                systemParts.add(new AnthropicRequest.SystemContent(content));

            } else if ("tool".equals(role)) {
                // 工具返回结果：Ollama 用 role=tool 表示，Anthropic 用 user 消息 + tool_result 块表示
                var toolResultContent = new ArrayList<>();
                var block = new HashMap<String, Object>();
                block.put("type", "tool_result");
                block.put("tool_use_id", "toolu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                block.put("content", extractStringContent(ollamaMsg.getContent()));
                toolResultContent.add(block);
                messages.add(new AnthropicRequest.Message("user", toolResultContent));

            } else {
                // user 或 assistant 消息
                List<Object> contentBlocks = new ArrayList<>();

                // 如果 assistant 消息包含 tool_calls，转换为 Anthropic 的 tool_use 块
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

                // 提取文本内容
                String text = extractStringContent(ollamaMsg.getContent());
                if (text != null && !text.isEmpty()) {
                    if (contentBlocks.isEmpty()) {
                        // 纯文本消息：直接作为字符串传递
                        messages.add(new AnthropicRequest.Message(role, text));
                        continue;
                    }
                    // 混合内容（文本 + 工具调用）：构建 content block 列表
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

        // 转换工具定义：Ollama 的 function.parameters → Anthropic 的 input_schema
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

    /**
     * 将 Anthropic 格式的非流式响应转换为 Ollama 格式。
     * 遍历 Anthropic 响应中的 content blocks：
     * - text 类型：提取文本，拼接到回复内容
     * - thinking 类型：保留思考过程（部分 Copilot 版本支持展示）
     * - tool_use 类型：转换为 Ollama 的 tool_calls 格式
     *
     * @param anthropicResp Anthropic 格式响应
     * @param requestModel  原始请求中的模型名称（用于回显）
     * @return Ollama 格式响应
     */
    private OllamaChatResponse convertResponse(AnthropicResponse anthropicResp, String requestModel) {
        var resp = new OllamaChatResponse();
        resp.setModel(requestModel);
        resp.setCreatedAt(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        resp.setDone(true);
        // stop_reason: "stop" 表示正常结束，"tool_use" 表示需要调用工具
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
                    // 思考过程，单独存储
                    msg.setThinking(block.getThinking());
                } else if ("tool_use".equals(block.getType())) {
                    // 工具调用请求，转换为 Ollama 格式
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

    /**
     * 解析模型名称：如果请求中未指定，使用默认模型。
     */
    private String resolveModel(String ollamaModel) {
        if (ollamaModel == null || ollamaModel.isBlank()) {
            return defaultModel;
        }
        return ollamaModel;
    }

    /**
     * 解析最大生成 token 数。
     * Ollama 通过 options.num_predict 设置，本代理默认使用 8192。
     */
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

    /**
     * 从消息内容中提取纯文本。
     * Ollama 的 content 字段可能是：
     * - String：直接返回
     * - List（Anthropic 格式的 content blocks）：提取所有 text block 的文本拼接
     * - 其他类型：调用 toString()
     */
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

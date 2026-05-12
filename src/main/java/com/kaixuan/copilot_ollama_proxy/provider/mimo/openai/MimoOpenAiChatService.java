package com.kaixuan.copilot_ollama_proxy.provider.mimo.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MiMo 上游 OpenAI 实现 —— 将请求转发到 MiMo 的 OpenAI 兼容端点。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class MimoOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    // 构造函数，注入运行时 Provider 目录、JSON 对象映射器和默认模型名称。
    public MimoOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog, @Value("${mimo.default-model:mimo-v2.5-pro}") String fallbackDefaultModel, ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
    }

    // ==================== XML 工具调用检测状态字段 ====================

    /** 进入可疑模式后缓存的原始 chunk JSON 字符串 */
    private final List<String> pendingChunks = new ArrayList<>(32);

    /** 是否已进入可疑模式（检测到 &lt;tool_call 开标签后切换） */
    private boolean suspiciousMode = false;

    /** 专用的 reasoning 缓冲区，累积 XML 格式的思考内容 */
    private final StringBuilder xmlReasoningBuffer = new StringBuilder();

    // ==================== 流式处理覆写 ====================

    @Override
    public Flux<String> chatCompletionStream(Map<String, Object> openAiRequest, String model) {
        // 每次新请求前重置 XML 检测状态
        pendingChunks.clear();
        suspiciousMode = false;
        xmlReasoningBuffer.setLength(0);
        return super.chatCompletionStream(openAiRequest, model);
    }

    protected void onRawStreamChunk(String rawChunkJson) {
        if ("[DONE]".equals(rawChunkJson)) {
            return;
        }
        if (!suspiciousMode) {
            checkXmlStart(rawChunkJson);
            return;
        }
        bufferSuspiciousChunk(rawChunkJson);
    }

    /** 在正常模式下检查 reasoning_content 是否包含 XML 工具调用开标签 */
    @SuppressWarnings("unchecked")
    private void checkXmlStart(String rawChunkJson) {
        try {
            Map<String, Object> chunk = objectMapper.readValue(rawChunkJson, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
            if (choices == null || choices.isEmpty()) {
                return;
            }
            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
            if (delta == null) {
                return;
            }
            Object reasoningObj = delta.get("reasoning_content");
            if (!(reasoningObj instanceof String reasoning) || reasoning.isEmpty()) {
                return;
            }
            if (reasoning.contains("<tool_call")) {
                suspiciousMode = true;
                log.info("MiMo XML 工具调用检测: 发现 <tool_call 开标签，可能有 XML 工具调用意图");
                xmlReasoningBuffer.append(reasoning);
                pendingChunks.add(rawChunkJson);
            }
        } catch (Exception e) {
            // 解析异常不影响主流程
        }
    }

    /** 在可疑模式下缓存 chunk 并累积 reasoning */
    @SuppressWarnings("unchecked")
    private void bufferSuspiciousChunk(String rawChunkJson) {
        try {
            Map<String, Object> chunk = objectMapper.readValue(rawChunkJson, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
            if (choices == null || choices.isEmpty()) {
                pendingChunks.add(rawChunkJson);
                return;
            }
            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
            if (delta != null) {
                Object reasoningObj = delta.get("reasoning_content");
                if (reasoningObj instanceof String reasoning && !reasoning.isEmpty()) {
                    xmlReasoningBuffer.append(reasoning);
                }
            }
            pendingChunks.add(rawChunkJson);
        } catch (Exception e) {
            // 解析异常不影响主流程
        }
    }

    // MiMo 上游服务的提供者键，用于在运行时配置中标识该服务。
    @Override
    public String getProviderKey() {
        return "mimo";
    }

    // MiMo 上游服务的显示名称，用于日志输出和错误提示等场景。
    @Override
    protected String providerDisplayName() {
        return "MiMo";
    }

    // MiMo 上游服务的默认 Base URL，如果运行时配置中没有指定则使用该值。
    @Override
    protected String defaultBaseUrl() {
        return "https://api.xiaomimimo.com";
    }

    // MiMo 上游服务的 Base URL 规范化方法，确保最终的 Base URL 以 "/v1" 结尾，并去除多余的斜杠。
    @Override
    protected String normalizeBaseUrl(String rawBaseUrl) {
        String normalized = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        if (normalized.isEmpty()) {
            return "https://api.xiaomimimo.com/v1";
        }

        // 去除末尾的斜杠，以兼容用户输入的各种 Base URL 形式。
        normalized = normalized.replaceAll("/+$", "");

        // 确保最终的 Base URL 以 "/v1" 结尾。
        if (!normalized.endsWith("/v1")) {
            normalized = normalized + "/v1";
        }
        return normalized;
    }

    // 在请求体中添加 MiMo 特定的字段或格式转换，例如将模型名称转换为 MiMo 识别的格式。
    @Override
    protected void customizeRequestBody(Map<String, Object> body, String resolvedModel) {
        // MiMo 的图片消息格式特殊，需要进行转换以兼容 MiMo 的格式要求。
        if (resolvedModel.contains("mimo")) {
            convertImageFormatForMimo(body);
        }
        // 注入 XML 工具调用格式指导，纠正 MiMo 的 XML 格式问题
        injectXmlToolCallGuidance(body);
        // 打印最终请求体，确认提示词注入生效
        try {
            log.debug("MiMo 最终请求体:\n{}", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body));
        } catch (Exception e) {
            log.debug("MiMo 请求体序列化失败: {}", e.getMessage());
        }
    }

    // 在请求头中添加 MiMo 特定的认证信息，例如 API Key。
    @Override
    protected void applyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
        headers.set("api-key", apiKey);
        headers.set("x-api-key", apiKey);
    }

    // MiMo 上游服务的 Chat Completions 端点 URI，通常为 "/chat/completions"。
    @Override
    protected String chatCompletionsUri() {
        return "/chat/completions";
    }

    // ==================== XML 工具调用检测：流结束处理 ====================

    @Override
    protected Flux<String> onStreamFinish(String chunkId, String model, StringBuilder reasoningBuffer, boolean contentEmitted) {
        if (!suspiciousMode || xmlReasoningBuffer.isEmpty()) {
            return null;
        }
        String accumulatedXml = xmlReasoningBuffer.toString();
        boolean hasToolCallEnd = accumulatedXml.contains("</tool_call>");

        if (!hasToolCallEnd) {
            // 场景 1: 只有 <tool_call 开标签，无闭标签 → 普通文本描述，放行所有缓存 chunk
            log.info("MiMo XML 工具调用检测: 虚惊一场，仅有 <tool_call 开标签无闭标签，不是 XML 工具调用");
            return releasePendingChunks();
        }

        // 场景 3: </tool_call> + stop 同时满足 → 真正的工具调用意图
        log.info("MiMo XML 工具调用检测: 确实是 XML 工具调用，开始转换为 JSON tool_calls (reasoning 长度: {})", accumulatedXml.length());
        return convertXmlToToolCallFlux(chunkId, model);
    }

    /** 场景 1/2: 放行所有缓存的 chunk */
    private Flux<String> releasePendingChunks() {
        List<String> chunks = new ArrayList<>(pendingChunks);
        pendingChunks.clear();
        return Flux.fromIterable(chunks);
    }

    /** 场景 3: 将 XML reasoning 内容转换为标准 OpenAI tool_calls JSON chunks */
    private Flux<String> convertXmlToToolCallFlux(String chunkId, String model) {
        try {
            String accumulatedXml = xmlReasoningBuffer.toString();

            // 提取 <parameter=name>value</parameter> 键值对
            // 特殊语法: <parameter=oldString>而非 <parameter name="oldString">
            Map<String, String> params = parseXmlParameters(accumulatedXml);
            if (params.isEmpty()) {
                log.warn("MiMo XML 转换失败: 未提取到参数，回退放行原始 chunk");
                return releasePendingChunks();
            }

            // 根据参数名推断函数名
            String functionName = inferFunctionName(params.keySet());

            // 构造 arguments JSON
            String argumentsJson = objectMapper.writeValueAsString(params);

            // 生成唯一的 tool_call ID
            String toolCallId = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

            // 构建 tool_calls delta chunk
            String toolCallChunk = buildXmlToolCallChunk(chunkId, model, toolCallId, functionName, argumentsJson);

            // 构建 finish_reason: "tool_calls" 的 finish chunk
            String finishChunk = buildXmlToolCallFinishChunk(chunkId, model);

            // 清理状态
            pendingChunks.clear();
            log.info("MiMo XML 转换完成: {} ({}), 参数: {}, arguments 长度: {}", functionName, toolCallId, params.keySet(), argumentsJson.length());

            return Flux.just(toolCallChunk, finishChunk);
        } catch (Exception e) {
            log.warn("MiMo XML 转换异常，回退放行原始 chunk: {}", e.getMessage());
            return releasePendingChunks();
        }
    }

    /** 解析 XML reasoning 中的 <parameter=name>value</parameter> 键值对 */
    private Map<String, String> parseXmlParameters(String xml) {
        Map<String, String> params = new LinkedHashMap<>();

        // 匹配 <parameter=name> 开标签：等号紧接标签名，非标准 XML
        // 示例: <parameter=oldString> value </parameter>
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<parameter=([^>]+)>\\s*(.*?)\\s*</parameter>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(xml);

        while (matcher.find()) {
            String paramName = matcher.group(1).trim();
            String paramValue = matcher.group(2);
            if (!paramName.isEmpty() && !params.containsKey(paramName)) {
                params.put(paramName, paramValue);
            }
        }

        return params;
    }

    /** 根据参数名集合推断函数名 */
    private String inferFunctionName(java.util.Set<String> paramNames) {
        // 多个参数时，按字母序拼接 key 用于匹配
        String key = String.join("+", paramNames.stream().sorted().toList());

        // 已知的常用函数参数模式 → 函数名映射
        if (key.contains("oldString") && key.contains("newString")) {
            return "replace_string_in_file";
        }
        if (key.contains("content") && key.contains("filePath") && !key.contains("endLine")) {
            return "create_file";
        }
        if (key.contains("command") && key.contains("explanation")) {
            return "run_in_terminal";
        }
        if (key.contains("endLine") && key.contains("filePath") && key.contains("startLine")) {
            return "read_file";
        }

        log.warn("MiMo XML: 无法根据参数推断函数名 (参数: {})，回退为 run_in_terminal", paramNames);
        return "run_in_terminal";
    }

    /** 构建标准 OpenAI tool_calls delta chunk JSON */
    private String buildXmlToolCallChunk(String chunkId, String model, String toolCallId, String functionName, String argumentsJson) {
        try {
            Map<String, Object> functionMap = new LinkedHashMap<>();
            functionMap.put("name", functionName);
            functionMap.put("arguments", argumentsJson);

            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("index", 0);
            toolCall.put("id", toolCallId);
            toolCall.put("type", "function");
            toolCall.put("function", functionMap);

            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("role", "assistant");
            delta.put("content", null);
            delta.put("tool_calls", List.of(toolCall));

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", null);

            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", chunkId);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);
            chunk.put("choices", List.of(choice));

            return objectMapper.writeValueAsString(chunk);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 构建 tool_calls 的 finish chunk */
    private String buildXmlToolCallFinishChunk(String chunkId, String model) {
        try {
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("role", null);
            delta.put("content", null);
            delta.put("tool_calls", null);

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", "tool_calls");

            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", chunkId);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);
            chunk.put("choices", List.of(choice));

            return objectMapper.writeValueAsString(chunk);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 向 system prompt 注入约束，禁止 MiMo 输出 XML 格式的工具调用，
     * 要求其使用标准 JSON tool_calls 格式。
     * <p>
     * MiMo 在需要调用工具（尤其是字符串替换场景）时，倾向于输出残缺的
     * XML 格式（如 &lt;parameter=oldString&gt;），这会导致 Copilot 解析失败。
     * 通过明确禁止 XML 并给出 JSON 示例来规范其行为。
     */
    @SuppressWarnings("unchecked")
    private void injectXmlToolCallGuidance(Map<String, Object> body) {
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> rawMessages)) {
            return;
        }
        List<Map<String, Object>> messages = (List<Map<String, Object>>) rawMessages;

        String guidance = """
                IMPORTANT: When you need to call tools, you MUST use the standard JSON tool_calls format. NEVER use XML format.

                Correct JSON format example:
                {
                  "tool_calls": [
                    {
                      "id": "call_xxx",
                      "type": "function",
                      "function": {
                        "name": "replace_string_in_file",
                        "arguments": {
                          "filePath": "/path/to/file",
                          "oldString": "old text",
                          "newString": "new text"
                        }
                      }
                    }
                  ]
                }

                Special notes:
                1. Do NOT use any XML tags such as <tool_calls>, <tool_call>, <function>, <parameter>.
                2. Do NOT output placeholder text like "...existing code...".
                3. For string replacement scenarios (oldString/newString), always use JSON format, never XML.
                4. Always use standard JSON format for all tool calls.
                """;

        // 查找已有的 system 消息
        for (Object msgObj : messages) {
            if (msgObj instanceof Map<?, ?> msg && "system".equals(msg.get("role"))) {
                String existingContent = msg.get("content") instanceof String s ? s : "";
                ((Map<String, Object>) msgObj).put("content", existingContent + "\n\n" + guidance);
                log.info("MiMo 已注入 JSON 工具调用约束到 system prompt");
                return;
            }
        }

        // 没有 system 消息则新建一个
        Map<String, Object> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", guidance);
        messages.add(0, systemMsg);
        log.info("MiMo 已新建 system prompt 并注入 JSON 工具调用约束");
    }

    /**
     * MiMo 的图片消息格式特殊，
     * 需要将 content 中的图片项从 {type: "image_url", image_url: {...}} 
     * 转换为 {type: "image_url", url: ...}，并且如果 role 是 "tool" 则转换为 "user"，
     * 以兼容 MiMo 的格式要求。
     * @param body 原始请求体，可能包含 messages 字段，其中的 content 可能包含图片项。
     */
    @SuppressWarnings("unchecked")
    private void convertImageFormatForMimo(Map<String, Object> body) {
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> messages)) {
            return;
        }
        for (Object msgObj : messages) {
            if (!(msgObj instanceof Map<?, ?> msg)) {
                continue;
            }
            Object contentObj = msg.get("content");
            if (!(contentObj instanceof List<?> contentList)) {
                continue;
            }
            boolean hasImage = false;
            for (Object itemObj : contentList) {
                if (!(itemObj instanceof Map<?, ?> item)) {
                    continue;
                }
                if (!"image_url".equals(item.get("type"))) {
                    continue;
                }
                hasImage = true;
                Object imageObj = item.get("image_url");
                if (imageObj instanceof Map<?, ?> imageMap) {
                    if (imageMap.containsKey("media_type")) {
                        ((Map<String, Object>) imageMap).remove("media_type");
                        ((Map<String, Object>) imageMap).put("type", "image_url");
                        log.debug("MiMo 图片格式转换: media_type -> type=image_url");
                    }
                }
            }
            if (hasImage && "tool".equals(msg.get("role"))) {
                ((Map<String, Object>) msg).put("role", "user");
                ((Map<String, Object>) msg).remove("tool_call_id");
                log.debug("MiMo 图片消息 role 转换: tool -> user");
            }
        }
    }
}

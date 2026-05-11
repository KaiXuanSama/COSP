package com.kaixuan.copilot_ollama_proxy.provider.longcat.openai;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LongCat 上游 OpenAI 实现 —— 将请求转发到 LongCat 的 OpenAI 兼容端点。
 * 所有运行时配置（API Key、Base URL）均从数据库读取。
 */
@Service
public class LongCatOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    // 构造函数，注入运行时 Provider 目录、JSON 对象映射器和默认模型名称。
    public LongCatOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog, @Value("${longcat.default-model:LongCat-Flash-Chat}") String fallbackDefaultModel, ObjectMapper objectMapper) {
        super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
    }

    // ==================== XML 工具调用检测状态字段 ====================

    /** 进入可疑模式后缓存的原始 chunk JSON 字符串 */
    private final List<String> pendingChunks = new ArrayList<>(32);

    /** 是否已进入可疑模式（检测到 &lt;longcat_tool_call 开标签后切换） */
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

    @Override
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
            if (reasoning.contains("<longcat_tool_call")) {
                suspiciousMode = true;
                log.info("LongCat XML 工具调用检测: 发现 <longcat_tool_call 开标签，可能有 XML 工具调用意图");
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

    // LongCat 上游服务的提供者键，用于在运行时配置中标识该服务。
    @Override
    public String getProviderKey() {
        return "longcat";
    }

    // LongCat 上游服务的显示名称，用于日志输出和错误提示等场景。
    @Override
    protected String providerDisplayName() {
        return "LongCat";
    }

    // LongCat 上游服务的默认 Base URL，如果运行时配置中没有指定则使用该值。
    @Override
    protected String defaultBaseUrl() {
        return "https://api.longcat.chat";
    }

    // LongCat 上游服务的 Base URL 规范化方法，确保最终的 Base URL 以 "/openai" 结尾，并去除多余的斜杠。
    @Override
    protected String normalizeBaseUrl(String rawBaseUrl) {
        return rawBaseUrl.replaceAll("/+$", "") + "/openai";
    }

    // 在请求体中添加 LongCat 特定的字段或格式转换，例如将模型名称转换为 LongCat 识别的格式。
    @Override
    protected void applyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
    }

    // LongCat 上游服务的 Chat Completions 端点 URI，通常为 "/v1/chat/completions"。
    @Override
    protected String chatCompletionsUri() {
        return "/v1/chat/completions";
    }

    // ==================== XML 工具调用检测：流结束处理 ====================

    @Override
    protected Flux<String> onStreamFinish(String chunkId, String model, StringBuilder reasoningBuffer, boolean contentEmitted) {
        if (!suspiciousMode || xmlReasoningBuffer.isEmpty()) {
            return null;
        }
        String accumulatedXml = xmlReasoningBuffer.toString();
        boolean hasToolCallEnd = accumulatedXml.contains("</longcat_tool_call>");

        if (!hasToolCallEnd) {
            // 场景 1: 只有 <longcat_tool_call 开标签，无闭标签 → 普通文本描述，放行所有缓存 chunk
            log.info("LongCat XML 工具调用检测: 虚惊一场，仅有 <longcat_tool_call 开标签无闭标签，不是 XML 工具调用");
            return releasePendingChunks();
        }

        // 场景 3: </longcat_tool_call> + stop 同时满足 → 真正的工具调用意图
        log.info("LongCat XML 工具调用检测: 确实是 XML 工具调用，开始转换为 JSON tool_calls (reasoning 长度: {})", accumulatedXml.length());
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

            // 提取函数名: <longcat_tool_call>functionName
            String functionName = extractFunctionName(accumulatedXml);
            if (functionName == null || functionName.isEmpty()) {
                log.warn("LongCat XML 转换失败: 未提取到函数名，回退放行原始 chunk");
                return releasePendingChunks();
            }

            // 提取 <longcat_arg_key>/<longcat_arg_value> 键值对
            Map<String, String> params = parseLongCatParameters(accumulatedXml);
            if (params.isEmpty()) {
                log.warn("LongCat XML 转换失败: 未提取到参数，回退放行原始 chunk");
                return releasePendingChunks();
            }

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
            log.info("LongCat XML 转换完成: {} ({}), 参数: {}, arguments 长度: {}", functionName, toolCallId, params.keySet(), argumentsJson.length());

            return Flux.just(toolCallChunk, finishChunk);
        } catch (Exception e) {
            log.warn("LongCat XML 转换异常，回退放行原始 chunk: {}", e.getMessage());
            return releasePendingChunks();
        }
    }

    /** 从 XML 中提取函数名: <longcat_tool_call>functionName */
    private String extractFunctionName(String xml) {
        Pattern pattern = Pattern.compile("<longcat_tool_call>\\s*([^\\s<]+)");
        Matcher matcher = pattern.matcher(xml);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /** 解析 LongCat XML 中的 <longcat_arg_key>/<longcat_arg_value> 键值对 */
    private Map<String, String> parseLongCatParameters(String xml) {
        Map<String, String> params = new LinkedHashMap<>();

        // 提取所有 <longcat_arg_key> 和 <longcat_arg_value> 对
        Pattern keyPattern = Pattern.compile("<longcat_arg_key>\\s*(.*?)\\s*</longcat_arg_key>", Pattern.DOTALL);
        Pattern valuePattern = Pattern.compile("<longcat_arg_value>\\s*(.*?)\\s*</longcat_arg_value>", Pattern.DOTALL);

        List<String> keys = new ArrayList<>();
        Matcher keyMatcher = keyPattern.matcher(xml);
        while (keyMatcher.find()) {
            keys.add(keyMatcher.group(1).trim());
        }

        List<String> values = new ArrayList<>();
        Matcher valueMatcher = valuePattern.matcher(xml);
        while (valueMatcher.find()) {
            values.add(valueMatcher.group(1));
        }

        // 按顺序配对 key-value
        int size = Math.min(keys.size(), values.size());
        for (int i = 0; i < size; i++) {
            String key = keys.get(i);
            if (!key.isEmpty() && !params.containsKey(key)) {
                params.put(key, values.get(i));
            }
        }

        return params;
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
}

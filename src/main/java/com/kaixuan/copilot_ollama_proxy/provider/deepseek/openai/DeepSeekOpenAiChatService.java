package com.kaixuan.copilot_ollama_proxy.provider.deepseek.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ReasoningCacheRepository;
import com.kaixuan.copilot_ollama_proxy.provider.openai.AbstractOpenAiCompatibleUpstreamChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 上游 OpenAI 实现 —— 将请求转发到 DeepSeek 的 OpenAI 兼容端点。
 * <p>
 * 特性：
 * <ul>
 *   <li>所有运行时配置（API Key、Base URL）均从数据库读取</li>
 *   <li>自动缓存工具调用时的 reasoning_content，并在后续请求中回填</li>
 *   <li>缓存未命中时注入空字符串作为保底方案</li>
 * </ul>
 */
@Service
public class DeepSeekOpenAiChatService extends AbstractOpenAiCompatibleUpstreamChatService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekOpenAiChatService.class);

    private final ReasoningCacheRepository reasoningCacheRepository;

    /**
     * 当前流式响应中累积的 reasoning_content（跨多个 SSE chunk）。
     * 注意：由于是实例字段，多个并发流可能相互干扰。
     * 但在本项目中，DeepSeek 请求是串行处理的，风险可控。
     */
    private final StringBuilder reasoningBuffer = new StringBuilder();

    /**
     * 当前流式响应中正在构建的 tool_call ID 列表。
     */
    private final List<String> pendingToolCallIds = new ArrayList<>();

    public DeepSeekOpenAiChatService(RuntimeProviderCatalog runtimeProviderCatalog, @Value("${deepseek.default-model:deepseek-v4-flash}") String fallbackDefaultModel, ObjectMapper objectMapper,
            ReasoningCacheRepository reasoningCacheRepository) {
        super(runtimeProviderCatalog, objectMapper, fallbackDefaultModel);
        this.reasoningCacheRepository = reasoningCacheRepository;
    }

    @Override
    public String getProviderKey() {
        return "deepseek";
    }

    @Override
    protected String providerDisplayName() {
        return "DeepSeek";
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.deepseek.com";
    }

    @Override
    protected String normalizeBaseUrl(String rawBaseUrl) {
        return rawBaseUrl.replaceAll("/+$", "");
    }

    @Override
    protected void applyAuthenticationHeaders(HttpHeaders headers, String apiKey) {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
    }

    @Override
    protected String chatCompletionsUri() {
        return "/chat/completions";
    }

    // ==================== 思考链缓存：写入 ====================

    @Override @SuppressWarnings("unchecked")
    protected void onRawStreamChunk(String rawChunkJson) {
        if ("[DONE]".equals(rawChunkJson)) {
            return;
        }
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

            // 1. 累积 reasoning_content
            Object reasoningObj = delta.get("reasoning_content");
            if (reasoningObj instanceof String reasoning && !reasoning.isEmpty()) {
                reasoningBuffer.append(reasoning);
            }

            // 2. 捕获 tool_call ID（只在第一个 tool_calls chunk 中携带 id）
            Object toolCallsObj = delta.get("tool_calls");
            if (toolCallsObj instanceof List<?> toolCalls) {
                for (Object tcObj : toolCalls) {
                    if (tcObj instanceof Map<?, ?> tc) {
                        Object idObj = tc.get("id");
                        if (idObj instanceof String id && !id.isEmpty() && !pendingToolCallIds.contains(id)) {
                            pendingToolCallIds.add(id);
                        }
                    }
                }
            }

            // 3. 流结束时判断是否需要持久化
            Object finishReason = choices.get(0).get("finish_reason");
            if ("tool_calls".equals(finishReason) && !pendingToolCallIds.isEmpty()) {
                String reasoning = reasoningBuffer.toString();
                if (!reasoning.isEmpty()) {
                    for (String toolCallId : pendingToolCallIds) {
                        reasoningCacheRepository.save(toolCallId, reasoning);
                    }
                    log.debug("已缓存 {} 条工具调用思考链 (reasoning 长度: {})", pendingToolCallIds.size(), reasoning.length());
                }
                reasoningBuffer.setLength(0);
                pendingToolCallIds.clear();
            } else if ("stop".equals(finishReason) || "length".equals(finishReason)) {
                // 非工具调用结束，清空状态
                reasoningBuffer.setLength(0);
                pendingToolCallIds.clear();
            }
        } catch (Exception e) {
            // 解析异常不影响主流程
        }
    }

    // ==================== 思考链缓存：读取与注入 ====================

    @Override @SuppressWarnings("unchecked")
    protected void customizeRequestBody(Map<String, Object> body, String resolvedModel) {
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> messages)) {
            return;
        }

        boolean modified = false;
        for (Object msgObj : messages) {
            if (!(msgObj instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> msg = (Map<String, Object>) msgObj;

            // 只处理 assistant 角色且带有 tool_calls 但没有 reasoning_content 的消息
            if (!"assistant".equals(msg.get("role")) || !msg.containsKey("tool_calls") || msg.containsKey("reasoning_content")) {
                continue;
            }

            // 从缓存中查找 reasoning_content（遍历 tool_calls，找到第一个命中的即停止）
            String cachedReasoning = null;
            Object toolCallsObj = msg.get("tool_calls");
            if (toolCallsObj instanceof List<?> toolCalls) {
                for (Object tcObj : toolCalls) {
                    if (tcObj instanceof Map<?, ?> tc) {
                        Object idObj = tc.get("id");
                        if (idObj instanceof String id) {
                            cachedReasoning = reasoningCacheRepository.findByToolCallId(id);
                            if (cachedReasoning != null) {
                                break; // 找到即停止后续查询
                            }
                        }
                    }
                }
            }

            // 注入：缓存命中用真实内容，未命中用空字符串保底
            msg.put("reasoning_content", cachedReasoning != null ? cachedReasoning : "");
            modified = true;
        }

        if (modified) {
            // try {
            //     String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
            //     log.debug("已注入 reasoning_content，修改后请求体:\n{}", json);
            // } catch (Exception e) {
            //     log.debug("已注入 reasoning_content，但序列化失败", e);
            // }
            log.debug("已向带 tool_calls 的 assistant 消息注入 reasoning_content");
        }
    }
}
package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ResponseTranslationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages <strong>非流式</strong>响应 → OpenAI Chat Completions 响应。
 *
 * <h2>为何先做非流式</h2>
 * 形态简单、无状态机，可以先把字段映射、usage 换算与模型名回显这三件事钉死；
 * 流式那侧的每一条映射都能复用这里的结论。
 *
 * <h2>不做什么</h2>
 * <ul>
 *   <li><strong>不重建 thinking 块的 signature</strong>。它由 Anthropic 自己签发，
 *       跨协议造不出来。契约第 5.1 节。</li>
 *   <li><strong>不凭空估算 reasoning_tokens</strong>。Anthropic 不上报这个数字。</li>
 *   <li><strong>不改写模型名</strong>。用下游原始请求里的带前缀名，见 {@link #translate}。</li>
 * </ul>
 *
 * @see <a href="file:../../../../../../../../../docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md">
 *      响应侧协议翻译契约</a>
 */
final class AnthropicToOpenAiNonStreamTranslator {

    private final ObjectMapper objectMapper;

    AnthropicToOpenAiNonStreamTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 翻译完整响应体。
     *
     * @param anthropicBody 上游原始响应体
     * @param downstreamModel <strong>下游原始请求</strong>里的模型名（含 {@code [provider-key]}
     *                        前缀）。不能用上游返回的裸名——本服务按前缀路由，
     *                        把裸名透给下游会让它下一轮路由失败（契约第 7 节）
     * @return OpenAI 形态的响应 JSON 字符串
     * @throws ResponseTranslationException 上游响应无法解析
     */
    String translate(String anthropicBody, String downstreamModel) {
        JsonNode root = parse(anthropicBody);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<Object> toolCalls = new ArrayList<>();

        JsonNode blocks = root.get("content");
        if (blocks != null && blocks.isArray()) {
            for (JsonNode block : blocks) {
                collectBlock(block, content, reasoning, toolCalls);
            }
        }

        message.put("content", content.toString());
        // 思考内容无条件给出，不看有没有 tool_calls。
        // 请求侧确实有个「只在带工具时回传」的闸门，但那是为了规避上游 400；
        // 响应是发给下游客户端的，全量给出才能让它渲染思考过程。契约第 5.3 节。
        if (!reasoning.isEmpty()) {
            message.put("reasoning_content", reasoning.toString());
        }
        if (!toolCalls.isEmpty()) {
            message.put("tool_calls", toolCalls);
        }

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", StopReasonMapper.toFinishReason(text(root, "stop_reason")));

        Map<String, Object> response = new LinkedHashMap<>();
        // id 原样透传上游的 msg_xxx，不套 chatcmpl- 前缀：那样做只是伪装成 OpenAI 生成的，
        // 而保留原值能让日志与上游对账。
        response.put("id", text(root, "id"));
        response.put("object", OpenAiResponseShapes.OBJECT_COMPLETION);
        // Anthropic 没有 created 字段，只能用本地时间。注意它不是上游耗时基准。
        response.put("created", System.currentTimeMillis() / 1000);
        response.put("model", downstreamModel);
        response.put("choices", List.of(choice));

        AnthropicUsageAccumulator usage = new AnthropicUsageAccumulator();
        usage.merge(root.get("usage"));
        if (usage.hasUsage()) {
            response.put("usage", usage.toOpenAiUsage());
        }

        return serialize(response);
    }

    /**
     * 归集单个 content block。
     *
     * <p>text 与 thinking 各自按出现顺序累积——<strong>累积而非赋值</strong>。
     * 参考实现里 new-api 用的是赋值，导致多个 thinking 块只剩最后一个、
     * 前面的静默丢失（契约第 5.4 节）。
     */
    private void collectBlock(JsonNode block, StringBuilder content,
                              StringBuilder reasoning, List<Object> toolCalls) {
        if (block == null || !block.isObject()) {
            return;
        }
        String type = text(block, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "text" -> append(content, text(block, "text"));
            case "thinking" -> append(reasoning, text(block, "thinking"));
            // redacted_thinking 的内容已被上游加密，没有可展示的明文。
            // 显式列出而非落到 default：这样将来读代码的人知道它是「有意跳过」
            // 而不是「忘了处理」。契约第 5.2 节要求的 warning 由流式侧承担——
            // 非流式这里没有 logger，且整体响应可见，用户能自己发现内容缺失。
            case "redacted_thinking" -> {
            }
            case "tool_use" -> toolCalls.add(toolCall(block, toolCalls.size()));
            // 其余块类型（server_tool_use、web_search_tool_result 等 hosted 工具）
            // 在 Chat Completions 里没有对等物，丢弃。
            default -> {
            }
        }
    }

    /**
     * {@code tool_use} 块 → OpenAI {@code tool_calls} 元素。
     *
     * <p>非流式的 index 就是数组下标——这里不存在流式那侧「Anthropic index 覆盖所有块类型」
     * 的问题，因为我们只把 tool_use 块放进这个数组。
     */
    private Map<String, Object> toolCall(JsonNode block, int index) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", text(block, "name"));
        function.put("arguments", stringifyInput(block.get("input")));

        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("index", index);
        toolCall.put("id", text(block, "id"));
        toolCall.put("type", "function");
        toolCall.put("function", function);
        return toolCall;
    }

    /**
     * Anthropic 的 {@code input} 是对象，OpenAI 的 {@code arguments} 是 JSON 字符串。
     *
     * <p>缺失或序列化失败都返回 {@code "{}"}：无参工具调用是合法且常见的形态，
     * 而一个空对象比一个畸形字符串更容易被下游消费。
     */
    private String stringifyInput(JsonNode input) {
        if (input == null || input.isNull()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw new ResponseTranslationException("上游返回空响应体，无法翻译");
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new ResponseTranslationException(
                    "上游响应不是合法 JSON，无法翻译: " + exception.getMessage());
        }
    }

    private String serialize(Map<String, Object> response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception exception) {
            // 这里失败意味着我们自己构造的 Map 序列化不出去，属于编程错误而非上游问题。
            throw new ResponseTranslationException(
                    "翻译结果序列化失败: " + exception.getMessage());
        }
    }

    private static void append(StringBuilder target, String value) {
        if (value != null && !value.isEmpty()) {
            target.append(value);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}

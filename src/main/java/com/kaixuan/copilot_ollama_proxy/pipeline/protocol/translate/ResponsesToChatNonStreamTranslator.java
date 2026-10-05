package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ResponseTranslationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses <strong>非流式</strong>响应 → OpenAI Chat Completions 响应（R2C 方向）。
 *
 * <h2>为何先做非流式</h2>
 * 与 M2C 同序：非流式无状态机，先把「从 {@code output[]} 提取」「usage 换算」
 * 「finish_reason 推导」三件事钉死；流式那侧的终态补发（R2C-PLAN §0.2-10）
 * 复用本类的提取逻辑 ——「只发终态、不发 delta」的上游与真正的非流式，
 * 面对的是同一份 {@code output[]}。
 *
 * <h2>不做什么</h2>
 * <ul>
 *   <li><strong>不改写模型名</strong>：原样透传上游返回的值。与 M2C / OpenAI 直连
 *       同口径（三线路必须一致，否则同一客户端因走了哪条路看到不同模型名）。
 *       契约第 7 节与此矛盾（要求回显下游带前缀名），实测三家下游 agent
 *       （Copilot / Claude CLI / Codex）对裸名均无问题 —— 矛盾处置见
 *       R2C-PLAN §6-1，不在本分支修。</li>
 *   <li><strong>不读 {@code encrypted_content} / {@code phase} / {@code annotations} /
 *       {@code logprobs} / {@code obfuscation} / {@code sequence_number}</strong>：
 *       Chat 协议无承载位置（R2C-RESEARCH §2.6，三家共识）。</li>
 *   <li><strong>不取顶层 {@code output_text} 便利字段</strong>：实测 stepfun 上游
 *       该字段为空而 {@code output[].content[]} 有值（PLAN §7.3），依赖它必然踩坑。</li>
 * </ul>
 *
 * <h2>提取规则（R2C-RESEARCH §1 的定案口径）</h2>
 * <ul>
 *   <li>{@code message} item：取全部 {@code output_text} part 的 {@code text}；
 *       多个 message item 之间用<strong>空行分隔</strong>（new-api 的
 *       {@code appendSeparatedText} 语义：只补足到两个换行，不重复）。</li>
 *   <li>{@code reasoning} item：<strong>{@code content} 优先、{@code summary} 兜底</strong>
 *       （实测三家都发明文 content、summary 为空；官方上游可能只发 summary）。</li>
 *   <li>{@code function_call} / {@code custom_tool_call} item：→ {@code tool_calls[]}；
 *       {@code id} 用 {@code call_id}（空回落 item {@code id}）；{@code custom_tool_call}
 *       按 function 翻译、{@code arguments} 取其 {@code input} 字段（R2C-PLAN §6-2）。</li>
 *   <li>{@code web_search_call} 等其余 item：吞掉 + debug 留痕（Chat 无对等物）。</li>
 * </ul>
 *
 * @see <a href="file:../../../../../../../../../docs/features/protocol-translation/chat-responses/R2C-PLAN.md">
 *      R2C 回程翻译实施计划</a>
 */
final class ResponsesToChatNonStreamTranslator {

    private final ObjectMapper objectMapper;

    ResponsesToChatNonStreamTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 翻译完整响应体。
     *
     * @param responsesBody 上游原始响应体（Responses 形态 JSON）
     * @return Chat Completions 形态的响应 JSON 字符串
     * @throws ResponseTranslationException 上游响应无法解析
     */
    String translate(String responsesBody) {
        JsonNode root = parse(responsesBody);

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<Object> toolCalls = new ArrayList<>();

        JsonNode output = root.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                collectItem(item, content, reasoning, toolCalls);
            }
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        // 空文本给 "" 而非 null：M2C 同口径（CPA 测试钉过 null 覆盖已有内容的坑），
        // 且部分客户端按 content 存在与否判断消息形态。
        message.put("content", content.toString());
        if (!reasoning.isEmpty()) {
            message.put("reasoning_content", reasoning.toString());
        }
        if (!toolCalls.isEmpty()) {
            message.put("tool_calls", toolCalls);
        }

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", resolveFinishReason(root, !toolCalls.isEmpty()));

        Map<String, Object> response = new LinkedHashMap<>();
        // id 原样透传（resp_ 前缀保留）：与 M2C「保留上游 id 让日志与上游对账」同判。
        response.put("id", text(root, "id"));
        response.put("object", OpenAiResponseShapes.OBJECT_COMPLETION);
        response.put("created", createdOf(root));
        response.put("model", text(root, "model"));
        response.put("choices", List.of(choice));

        JsonNode usage = ResponsesToChatUsageConverter.toOpenAiUsageNode(objectMapper, root.get("usage"));
        if (usage != null) {
            response.put("usage", usage);
        }

        return serialize(response);
    }

    /**
     * 决定最终的 {@code finish_reason}（R2C-RESEARCH §6-8 三家共识）。
     *
     * <table>
     *   <caption>推导规则</caption>
     *   <tr><th>上游 status</th><th>finish_reason</th></tr>
     *   <tr><td>{@code completed}（或缺失）</td><td>有工具调用 → {@code tool_calls}，否则 {@code stop}</td></tr>
     *   <tr><td>{@code incomplete} + reason {@code max_output_tokens}</td><td>{@code length}</td></tr>
     *   <tr><td>{@code incomplete} + reason {@code content_filter}</td><td>{@code content_filter}</td></tr>
     *   <tr><td>{@code incomplete} + 其它 reason</td><td>{@code stop}（有工具调用仍优先 {@code tool_calls}）</td></tr>
     * </table>
     *
     * <p>工具调用优先的语义与 M2C 的 {@code resolveFinishReason} 同源：
     * 含完整 {@code tool_calls} 的响应把 finish_reason 报成别的值，下游（Copilot）
     * 会判定「回答被截断 / 正常结束」而<strong>放弃执行已拿到的工具调用</strong>。
     */
    private static String resolveFinishReason(JsonNode root, boolean hasToolCalls) {
        String status = text(root, "status");
        String reason = null;
        if ("incomplete".equals(status)) {
            reason = text(root.get("incomplete_details"), "reason");
        }
        String mapped = switch (reason == null ? "" : reason) {
            case "max_output_tokens" -> "length";
            case "content_filter" -> "content_filter";
            default -> "stop";
        };
        return hasToolCalls ? "tool_calls" : mapped;
    }

    /**
     * 归集单个 output item。
     *
     * <p>累积而非赋值：多个 message / reasoning item 各自按出现顺序累积，
     * 参考实现里「赋值」的写法会让前面的 item 静默只剩最后一个。
     */
    private void collectItem(JsonNode item, StringBuilder content,
                             StringBuilder reasoning, List<Object> toolCalls) {
        if (item == null || !item.isObject()) {
            return;
        }
        String type = text(item, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "message" -> appendMessageText(item, content);
            case "reasoning" -> appendReasoningText(item, reasoning);
            case "function_call", "custom_tool_call" ->
                    toolCalls.add(toolCall(item, toolCalls.size(), type));
            // web_search_call 等其余 item 在 Chat Completions 里没有对等物，丢弃。
            // 显式 default 分支：与「忘了处理」区分开是有意跳过。
            default -> {
            }
        }
    }

    /**
     * message item：取全部 {@code output_text} part 的文本。
     *
     * <p>多 part / 多 item 之间用空行分隔（{@link #appendSeparated}，new-api 语义）。
     * {@code input_text} 忽略 —— 那是请求侧形态，响应的 message 里不应出现，
     * 出现了也不是给下游看的内容。
     */
    private static void appendMessageText(JsonNode item, StringBuilder content) {
        JsonNode parts = item.get("content");
        if (parts == null || !parts.isArray()) {
            return;
        }
        for (JsonNode part : parts) {
            if ("output_text".equals(text(part, "type"))) {
                appendSeparated(content, text(part, "text"));
            }
        }
    }

    /**
     * reasoning item：<strong>content 优先、summary 兜底</strong>（R2C-RESEARCH §9 定案）。
     *
     * <p>content 有任何非空文本就全取 content；全空才读 summary 的 {@code summary_text}。
     * 官方语义里 content 是详细思考、summary 是概括 —— 两者拼接会重复，
     * 优先级取一才不会把同一段思考发两遍。
     */
    private static void appendReasoningText(JsonNode item, StringBuilder reasoning) {
        StringBuilder contentText = new StringBuilder();
        JsonNode parts = item.get("content");
        if (parts != null && parts.isArray()) {
            for (JsonNode part : parts) {
                if (text(part, "text") != null && !text(part, "text").isEmpty()) {
                    appendSeparated(contentText, text(part, "text"));
                }
            }
        }
        if (contentText.length() > 0) {
            appendSeparated(reasoning, contentText.toString());
            return;
        }
        JsonNode summary = item.get("summary");
        if (summary != null && summary.isArray()) {
            for (JsonNode part : summary) {
                if ("summary_text".equals(text(part, "type"))) {
                    appendSeparated(reasoning, text(part, "text"));
                }
            }
        }
    }

    /**
     * function_call / custom_tool_call item → {@code tool_calls[]} 元素。
     *
     * <p>{@code id} 优先 {@code call_id}（配对键，下游回传 {@code tool_call_id} 用它），
     * 空则回落 item {@code id}（R2C-RESEARCH §1.4 三家共识）。
     * {@code custom_tool_call} 的载荷在 {@code input} 字段（文本形态），
     * 直接作 {@code arguments} —— 下游看到的是普通 function 调用（R2C-PLAN §6-2）。
     */
    private static Map<String, Object> toolCall(JsonNode item, int index, String type) {
        String callId = text(item, "call_id");
        if (callId == null || callId.isBlank()) {
            callId = text(item, "id");
        }
        String arguments = "custom_tool_call".equals(type)
                ? text(item, "input")
                : text(item, "arguments");

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", text(item, "name"));
        // arguments 缺失补 "{}"：无参调用是合法且常见的形态，
        // 空对象比空字符串更容易被下游解析（M2C stringifyInput 同判）。
        function.put("arguments", arguments == null || arguments.isBlank() ? "{}" : arguments);

        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("index", index);
        toolCall.put("id", callId);
        toolCall.put("type", "function");
        toolCall.put("function", function);
        return toolCall;
    }

    /**
     * usage 换算已上提到 {@link ResponsesToChatUsageConverter}（流式收尾与非流式
     * 共用同一份换算，两条路径必须同口径）。换算规则与「绝不能照抄 Anthropic
     * 三项相加」的理由见那个类的注释。
     */

    /**
     * 空行分隔的追加（new-api {@code appendSeparatedText} 语义）。
     *
     * <p>检查已有文本尾部与新文本头部的换行数，只补足到 2 个 ——
     * 两边都已带换行时不会叠加成四个。R2C-RESEARCH §1.2 的定案口径。
     */
    private static void appendSeparated(StringBuilder target, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (target.length() == 0) {
            target.append(value);
            return;
        }
        int trailingNewlines = 0;
        for (int i = target.length() - 1; i >= 0 && trailingNewlines < 2
                && target.charAt(i) == '\n'; i--) {
            trailingNewlines++;
        }
        int leadingNewlines = 0;
        for (int i = 0; i < value.length() && leadingNewlines < 2
                && value.charAt(i) == '\n'; i++) {
            leadingNewlines++;
        }
        target.append("\n".repeat(Math.max(0, 2 - trailingNewlines - leadingNewlines)));
        target.append(value);
    }

    /** {@code created_at} 原样用；缺失时本地时间兜底（非上游耗时基准）。 */
    private static long createdOf(JsonNode root) {
        JsonNode created = root.get("created_at");
        return created != null && created.canConvertToLong() ? created.asLong()
                : System.currentTimeMillis() / 1000;
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
            throw new ResponseTranslationException(
                    "翻译结果序列化失败: " + exception.getMessage());
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

package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses <strong>流式事件</strong> → OpenAI Chat Completions SSE chunk（R2C 方向）。
 *
 * <h2>不是一帧进一帧出</h2>
 * 与 {@code MessagesToChatStreamTranslator} 同一形状约束：
 * <table>
 *   <caption>帧数对照（完整规格见 R2C-PLAN §2.3）</caption>
 *   <tr><th>帧数</th><th>Responses 事件</th></tr>
 *   <tr><td>0</td><td>{@code in_progress}、message/reasoning 的 {@code output_item.added}、
 *       各 {@code part.*}、各 {@code *.done}（text/reasoning）、{@code failed}/{@code error}</td></tr>
 *   <tr><td>1</td><td>{@code created}（唯一 role 帧）、{@code output_text.delta}、
 *       {@code reasoning_text.delta}、工具的 {@code added}（首个 tool_calls 帧）、
 *       {@code arguments.delta/done}（差量）、{@code completed/incomplete}（finish chunk）</td></tr>
 *   <tr><td>多</td><td>终态事件的 output[] 补发 + 流结束收尾（finish 兜底 + usage + {@code [DONE]}）</td></tr>
 * </table>
 * 因此每个方法都返回 {@code List}，空列表是完全正常的返回值。
 *
 * <h2>usage chunk 只由 {@link #finalizeStream} 发出</h2>
 * 与 M2C 同判：跟着 finish chunk 走会在正常路径发两个 usage chunk（双倍计费记录），
 * 且以「那一刻」的累积值抢跑。收尾统一发必然是最完整的一份。
 *
 * <h2>[DONE] 不由任何事件触发</h2>
 * 由<strong>流结束</strong>触发（M2C 类注释的既定原则）：上游可能发完终态才关连接，
 * 也可能不发终态就断开。终态事件（completed 等）只产出 finish chunk，{@code [DONE]}
 * 一律留给 {@link #finalizeStream}。
 *
 * <h2>failed / error 不产帧</h2>
 * 能走到本翻译器的失败事件，说明空响应门已按原生形态判定并重试过（终态事件
 * {@code output} 为空 → 已卷入重试），这是耗尽放行的最终轮。给下游一个结构完整的
 * 流（收尾三档）+ warn 留痕，好过转 onError（SSE 头已发出后的二次分类）——
 * R2C-PLAN §0.2-9。
 *
 * @see <a href="file:../../../../../../../../../docs/features/protocol-translation/chat-responses/R2C-PLAN.md">
 *      R2C 回程翻译实施计划</a>
 */
final class ResponsesToChatStreamTranslator {

    private static final Logger log = LoggerFactory.getLogger(ResponsesToChatStreamTranslator.class);

    private final ObjectMapper objectMapper;

    ResponsesToChatStreamTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 翻译单个上游事件。
     *
     * @param eventData SSE data 字段的原始内容
     * @param state     本轮往返的状态，由调用方在重试重订阅时重建
     * @return 零到多个下游 chunk 的 JSON 字符串；解析失败返回空列表
     */
    List<String> translateEvent(String eventData, R2CStreamState state) {
        if (eventData == null || eventData.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(eventData);
        } catch (Exception exception) {
            // 单事件解析失败不中断整条流：已发出的内容对下游仍有效（M2C 同判）。
            log.warn("R2C 翻译跳过无法解析的上游事件: {}", exception.getMessage());
            return List.of();
        }

        String type = text(root, "type");
        if (type == null) {
            return List.of();
        }

        return switch (type) {
            case "response.created" -> onCreated(root, state);
            case "response.output_item.added" -> onItemAdded(root, state);
            case "response.output_text.delta" ->
                    emitText(text(root, "delta"), state);
            case "response.reasoning_text.delta", "response.reasoning_summary_text.delta" ->
                    emitReasoning(text(root, "delta"), state);
            case "response.reasoning_text.done", "response.reasoning_summary_text.done" -> {
                // 只设标记不发固定分隔：下个 delta 若自带 \n\n 开头就不补（§9-5 差额方案）。
                state.markReasoningBreak();
                yield List.of();
            }
            case "response.function_call_arguments.delta", "response.custom_tool_call_input.delta" ->
                    onArgumentsDelta(root, state, false);
            case "response.function_call_arguments.done", "response.custom_tool_call_input.done" ->
                    onArgumentsDelta(root, state, true);
            case "response.completed", "response.done", "response.incomplete" ->
                    onTerminal(root, state);
            case "response.failed", "error", "response.cancelled", "response.canceled" -> {
                // 处置见类注释：不产内容帧，warn 留痕（「内容凭空变少」在日志里要有痕迹）。
                log.warn("R2C 翻译收到失败/取消终态事件 {}，不产内容帧（finish_reason 由收尾兜底）", type);
                yield List.of();
            }
            // in_progress / part.* / output_text.done / 非工具 output_item.added/done /
            // 未知类型：块生命周期对 Chat 无意义，空 delta 帧是噪声（M2C 同判）。
            default -> {
                log.debug("R2C 翻译忽略上游事件类型: {}", type);
                yield List.of();
            }
        };
    }

    /** {@code response.created}：采纳身份 + 唯一的 role 帧（混合兜底的第一半）。 */
    private List<String> onCreated(JsonNode root, R2CStreamState state) {
        JsonNode response = root.get("response");
        if (response != null) {
            state.adoptUpstreamId(text(response, "id"));
            state.adoptUpstreamModel(text(response, "model"));
        }
        if (!state.claimRoleFrame()) {
            return List.of();
        }
        return List.of(chunk(state, OpenAiResponseShapes.roleDelta(), null));
    }

    /** {@code output_item.added}：只有工具类产帧（message/reasoning 的声明帧是噪声）。 */
    private List<String> onItemAdded(JsonNode root, R2CStreamState state) {
        JsonNode item = root.get("item");
        if (item == null) {
            return List.of();
        }
        String itemType = text(item, "type");
        if (!"function_call".equals(itemType) && !"custom_tool_call".equals(itemType)) {
            return List.of();
        }
        Integer outputIndex = intValue(root, "output_index");
        R2CStreamState.ToolRecord tool = state.registerTool(
                text(item, "id"), text(item, "call_id"), text(item, "name"), outputIndex);
        state.markToolCallSeen();
        // 首个 tool_calls 帧：index/id/name（name 只在这里发，arguments 用 "" 占位）。
        return List.of(chunk(state, OpenAiResponseShapes.toolCallStartDelta(
                tool.index, tool.callId, tool.name), null));
    }

    /**
     * {@code output_text.delta}：正文增量。
     *
     * <p>role 未发则在本帧带上（混合兜底的第二半：上游不发 created 时下游仍能拿到 role）。
     */
    private List<String> emitText(String delta, R2CStreamState state) {
        if (delta == null || delta.isEmpty()) {
            return List.of();
        }
        state.markContentSeen();
        List<String> frames = new ArrayList<>(2);
        if (state.claimRoleFrame()) {
            // 并入本帧：单独的 role-only 帧反而让消费端多处理一种形态。
            Map<String, Object> merged = OpenAiResponseShapes.contentDelta(delta);
            merged.put("role", "assistant");
            frames.add(chunk(state, merged, null));
            return frames;
        }
        frames.add(chunk(state, OpenAiResponseShapes.contentDelta(delta), null));
        return frames;
    }

    /**
     * {@code reasoning_text.delta} / {@code reasoning_summary_text.delta}：思考增量。
     *
     * <p>两事件同等对待（R2C-RESEARCH §2.1 三家共识：原始推理与概括对下游都是思考）。
     * 前置处理 {@link R2CStreamState#applyReasoningBreak} 的差额分隔。
     */
    private List<String> emitReasoning(String delta, R2CStreamState state) {
        if (delta == null || delta.isEmpty()) {
            return List.of();
        }
        state.markContentSeen();
        String effective = state.applyReasoningBreak(delta);
        if (state.claimRoleFrame()) {
            Map<String, Object> merged = OpenAiResponseShapes.reasoningDelta(effective);
            merged.put("role", "assistant");
            return List.of(chunk(state, merged, null));
        }
        return List.of(chunk(state, OpenAiResponseShapes.reasoningDelta(effective), null));
    }

    /**
     * {@code arguments.delta / .done}（含 custom_tool_call_input 同名形态）。
     *
     * <p>统一走「前缀补齐」模型：delta 逐片透传并累加；done 的完整参数只发
     * {@code substring(argsSentAt)} 的差量 —— 正常流差量为空不发，乱序流
     * （done 先到、参数在随后的 delta）delta 直接透传。<strong>不设 Done 门</strong>
     * （CPA 弱面，见 R2CStreamState 类注释）。
     */
    private List<String> onArgumentsDelta(JsonNode root, R2CStreamState state, boolean isDone) {
        String itemId = text(root, "item_id");
        Integer outputIndex = intValue(root, "output_index");
        R2CStreamState.ToolRecord tool = state.findTool(itemId, null, outputIndex);

        String value = isDone
                ? text(root, "arguments") != null ? text(root, "arguments") : text(root, "input")
                : text(root, "delta");
        if (value == null) {
            return List.of();
        }

        if (tool == null) {
            // 未注册（added 缺失或乱序先到）：delta 存留待注册拼入；done 也存
            //（done 携带完整参数，注册后由补齐路径统一对账）。
            if (!isDone && !value.isEmpty()) {
                state.stashPendingArgs(itemId, null, outputIndex, value);
            } else if (isDone && !value.isEmpty()) {
                state.stashPendingArgs(itemId, null, outputIndex, value);
            }
            return List.of();
        }

        if (isDone) {
            // done 是权威完整值：累加到 record（可能只到部分 delta 乱序后到），
            // 只发未发过的差量。
            if (value.length() > tool.arguments.length()
                    && value.startsWith(tool.arguments.toString())) {
                // 正常流：done 与已累积一致或更长，补差量。
                tool.arguments.setLength(0);
                tool.arguments.append(value);
            }
            // done 与累积不一致（乱序形态）：保守不动 record，避免倒退。
            return emitArgumentsRemainder(tool, state);
        }

        // delta：累加 + 透传（不检查与已发位置——乱序上游的 delta 可能晚于 done，
        // 仍然要发，下游按增量拼接自然正确）。
        tool.arguments.append(value);
        if (value.isEmpty()) {
            return List.of();
        }
        tool.argsSentAt += value.length();
        return List.of(chunk(state, OpenAiResponseShapes.toolCallArgumentsDelta(tool.index, value), null));
    }

    /** 发出 record 中「未发过」的参数差量（done 补齐路径共用）。 */
    private List<String> emitArgumentsRemainder(R2CStreamState.ToolRecord tool, R2CStreamState state) {
        int total = tool.arguments.length();
        if (total <= tool.argsSentAt) {
            return List.of();
        }
        String remainder = tool.arguments.substring(tool.argsSentAt);
        tool.argsSentAt = total;
        return List.of(chunk(state, OpenAiResponseShapes.toolCallArgumentsDelta(tool.index, remainder), null));
    }

    /**
     * 终态事件（{@code completed/done/incomplete}）：吸收原文，产出 finish chunk
     * 与（若流内从未以 delta 发出的）output[] 补发帧。
     *
     * <p>与 M2C 的 {@code message_delta} 对位：唯一产 finish chunk 的路径。
     * {@code [DONE]} 与 usage chunk 由 {@link #finalizeStream} 统一发。
     */
    private List<String> onTerminal(JsonNode root, R2CStreamState state) {
        JsonNode response = root.get("response");
        List<String> frames = new ArrayList<>();

        if (response != null) {
            state.adoptUpstreamId(text(response, "id"));
            state.adoptUpstreamModel(text(response, "model"));
            state.recordUsage(rawUsage(response));
            // 终态补发（R2C-PLAN §0.2-10）：「只发终态不发 delta」的上游，内容全在
            // 终态 output[] 里。与非流式共用判断口径：内容以「state 是否见过」为准。
            frames.addAll(supplementFromOutput(response, state));
        }

        if (!state.claimFinish()) {
            // 上游重复发终态：finish_reason 只发一次。
            return frames;
        }
        String finishReason = resolveFinishReason(state, mappedReason(response));
        frames.add(chunk(state, OpenAiResponseShapes.finishDelta(), finishReason));
        return frames;
    }

    /**
     * 从终态事件的 {@code output[]} 补发流内未发过的内容。
     *
     * <p>三个维度的补发（都按「state 没见过才发」判定，正常流下全部为空操作）：
     * <ul>
     *   <li>message 文本：state 未标记 sawContent 时整段发（delta 路径会标记）；</li>
     *   <li>reasoning 文本：同上；</li>
     *   <li>function_call：未登记的工具整条发（name/arguments 一次给全）。
     *       已登记的只对账差量（arguments 以 done 值补齐）。</li>
     * </ul>
     */
    private List<String> supplementFromOutput(JsonNode response, R2CStreamState state) {
        JsonNode output = response.get("output");
        if (output == null || !output.isArray()) {
            return List.of();
        }
        List<String> frames = new ArrayList<>();
        for (JsonNode item : output) {
            String type = text(item, "type");
            if (type == null) {
                continue;
            }
            switch (type) {
                case "message" -> {
                    if (!state.sawContent()) {
                        StringBuilder sb = new StringBuilder();
                        JsonNode parts = item.get("content");
                        if (parts != null && parts.isArray()) {
                            for (JsonNode part : parts) {
                                if ("output_text".equals(text(part, "type"))
                                        && text(part, "text") != null && !text(part, "text").isEmpty()) {
                                    if (!sb.isEmpty()) {
                                        sb.append("\n\n");
                                    }
                                    sb.append(text(part, "text"));
                                }
                            }
                        }
                        if (!sb.isEmpty()) {
                            state.markContentSeen();
                            frames.addAll(emitText(sb.toString(), state));
                        }
                    }
                }
                case "reasoning" -> {
                    if (!state.sawContent()) {
                        StringBuilder sb = new StringBuilder();
                        JsonNode parts = item.get("content");
                        if (parts != null && parts.isArray()) {
                            for (JsonNode part : parts) {
                                if (text(part, "text") != null && !text(part, "text").isEmpty()) {
                                    if (!sb.isEmpty()) {
                                        sb.append("\n\n");
                                    }
                                    sb.append(text(part, "text"));
                                }
                            }
                        }
                        if (!sb.isEmpty()) {
                            state.markContentSeen();
                            frames.addAll(emitReasoning(sb.toString(), state));
                        }
                    }
                }
                case "function_call", "custom_tool_call" -> {
                    R2CStreamState.ToolRecord tool = state.registerTool(
                            text(item, "id"), text(item, "call_id"), text(item, "name"),
                            null);
                    state.markToolCallSeen();
                    String full = "custom_tool_call".equals(type)
                            ? text(item, "input") : text(item, "arguments");
                    if (full != null && full.length() > tool.arguments.length()
                            && full.startsWith(tool.arguments.toString())) {
                        tool.arguments.setLength(0);
                        tool.arguments.append(full);
                    }
                    // 已发过 name/参数的只补差量；全新工具一次给全。
                    if (tool.argsSentAt == 0) {
                        if (tool.name != null) {
                            frames.add(chunk(state, OpenAiResponseShapes.toolCallStartDelta(
                                    tool.index, tool.callId, tool.name), null));
                            tool.nameSent = true;
                        }
                        frames.addAll(emitArgumentsRemainder(tool, state));
                    } else {
                        frames.addAll(emitArgumentsRemainder(tool, state));
                    }
                }
                default -> {
                }
            }
        }
        return frames;
    }

    /**
     * 流结束时的收尾（三档判定，与 M2C 同构）。
     *
     * <ol>
     *   <li>已发过 finish chunk（终态事件给过）—— 只补 usage chunk + {@code [DONE]}；</li>
     *   <li>没发过但有实质输出 —— 按截断处理（{@code length}）。不能报正常完成：
     *       下游会把被切断的回答当完整答案；</li>
     *   <li>什么都没有 —— 仍发终止帧（下游会一直等），用 {@code stop} 而非
     *       {@code length}：这种情况通常是上游立刻断开，没有「生成了一半」的语义</li>
     * </ol>
     */
    List<String> finalizeStream(R2CStreamState state) {
        List<String> frames = new ArrayList<>(3);

        if (state.claimFinish()) {
            String finishReason = state.sawContent() || state.sawToolCall()
                    ? resolveFinishReason(state, "length")
                    : "stop";
            if (!state.sentFinish()) {
                if (state.sawContent() || state.sawToolCall()) {
                    log.warn("R2C 翻译收尾：上游未发送终态事件，按截断处理（finish_reason=length）");
                } else {
                    log.warn("R2C 翻译收尾：上游未产出任何实质内容，发出空的终止帧");
                }
            }
            frames.add(chunk(state, OpenAiResponseShapes.finishDelta(), finishReason));
        }
        // usage 帧无条件在这里发（不跟着 finish chunk 走）：收尾时 lastUsageRaw
        // 必然是最完整的一份。顺序固定 finish 在前、usage 在后（客户端按
        // finish_reason 收尾会漏掉反序的 usage）。仅在下游要求且真有 usage 时发。
        frames.addAll(usageFrame(state));
        frames.add(OpenAiResponseShapes.DONE_SENTINEL);
        return frames;
    }

    /** usage chunk：独立一帧，choices 为空数组（M2C usageFrame 同形态）。 */
    private List<String> usageFrame(R2CStreamState state) {
        if (!state.includeUsage() || state.lastUsageRaw() == null) {
            return List.of();
        }
        try {
            JsonNode usage = objectMapper.readTree(state.lastUsageRaw());
            // 字段换算复用共享转换器（与非流式同一份逻辑）——两条路径必须同口径。
            Map<String, Object> converted = ResponsesToChatUsageConverter.toOpenAiUsage(usage);
            if (converted == null) {
                return List.of();
            }
            Map<String, Object> chunk = OpenAiResponseShapes.chunk(
                    state.id(), state.model(), state.created(), null);
            chunk.put("usage", converted);
            return List.of(objectMapper.writeValueAsString(chunk));
        } catch (Exception exception) {
            log.warn("R2C 翻译收尾跳过无法解析的 usage: {}", exception.getMessage());
            return List.of();
        }
    }

    /**
     * 决定最终 finish_reason：工具调用优先（OpenAI 语义要求含完整 tool_calls 的
     * 响应报 {@code tool_calls}，否则下游放弃执行工具 —— M2C resolveFinishReason
     * 同源，两条链的判定必须一致）。
     */
    private static String resolveFinishReason(R2CStreamState state, String mapped) {
        return state.sawToolCall() ? "tool_calls" : (mapped != null ? mapped : "stop");
    }

    /** 从终态 response 取 incomplete_details.reason 并映射（无则 null）。 */
    private static String mappedReason(JsonNode response) {
        if (response == null) {
            return null;
        }
        String reason = text(response.get("incomplete_details"), "reason");
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case "max_output_tokens" -> "length";
            case "content_filter" -> "content_filter";
            default -> "stop";
        };
    }

    /** 终态事件的 usage 原文（response.usage 子树序列化）。 */
    private String rawUsage(JsonNode response) {
        JsonNode usage = response.get("usage");
        if (usage == null || usage.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(usage);
        } catch (Exception exception) {
            return null;
        }
    }

    private String chunk(R2CStreamState state, Map<String, Object> delta, String finishReason) {
        Map<String, Object> chunk = OpenAiResponseShapes.chunk(
                state.id(), state.model(), state.created(),
                OpenAiResponseShapes.deltaChoice(delta, finishReason));
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception exception) {
            // 自构造 Map 序列化失败属编程错误。
            throw new IllegalStateException("R2C chunk 序列化失败: " + exception.getMessage(), exception);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static Integer intValue(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : null;
    }
}

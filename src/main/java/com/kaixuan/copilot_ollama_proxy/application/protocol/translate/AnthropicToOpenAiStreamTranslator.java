package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages <strong>流式事件</strong> → OpenAI Chat Completions SSE chunk。
 *
 * <h2>不是一帧进一帧出</h2>
 * 这是本类最重要的形状约束，也是 {@code ProtocolTranslator} 迟迟没定方法签名的原因：
 * <table>
 *   <caption>帧数对照</caption>
 *   <tr><th>帧数</th><th>Anthropic 事件</th></tr>
 *   <tr><td>0</td><td>{@code content_block_start}（非 tool）、{@code content_block_stop}、
 *       {@code signature_delta}、{@code message_stop}、{@code ping}</td></tr>
 *   <tr><td>1</td><td>{@code message_start}（唯一带 role）、{@code text_delta}、
 *       {@code thinking_delta}、{@code input_json_delta}、{@code message_delta}
 *       （只产 finish chunk）</td></tr>
 *   <tr><td>多</td><td>流结束时的收尾（可能的 finish chunk + usage chunk + {@code [DONE]}）</td></tr>
 * </table>
 * 因此每个方法都返回 {@code List}，空列表是完全正常的返回值。
 *
 * <p><strong>usage chunk 只由 {@link #finalizeStream} 发出</strong>，
 * 不跟着 finish chunk 走 —— 那样正常路径会发出两个。见 {@code usageFrame} 的说明。
 *
 * <h2>[DONE] 不由 message_stop 触发</h2>
 * 它由<strong>流结束</strong>触发。上游可能在发完 {@code message_stop} 后才真正关闭连接，
 * 也可能根本不发 {@code message_stop} 就断开——把 {@code [DONE]} 绑在某个事件上，
 * 后一种情况下游就永远等不到终止信号。见 {@link #finalizeStream}。
 *
 * @see <a href="file:../../../../../../../../../docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md">
 *      响应侧协议翻译契约</a>
 */
final class AnthropicToOpenAiStreamTranslator {

    private static final Logger log = LoggerFactory.getLogger(AnthropicToOpenAiStreamTranslator.class);

    /** Chat Completions 里没有对等物的 hosted 工具块，显式跳过而非落到 default。 */
    private static final List<String> HOSTED_BLOCK_PREFIXES = List.of(
            "server_tool_use", "web_search_tool_result", "code_execution_tool_result",
            "mcp_tool_use", "mcp_tool_result");

    private final ObjectMapper objectMapper;

    AnthropicToOpenAiStreamTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 翻译单个上游事件。
     *
     * @param eventData SSE data 字段的原始内容
     * @param state     本轮往返的状态，由调用方在重试重订阅时重建
     * @return 零到多个下游 chunk 的 JSON 字符串；解析失败返回空列表
     */
    List<String> translateEvent(String eventData, A2OStreamState state) {
        if (eventData == null || eventData.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(eventData);
        } catch (Exception exception) {
            // 单个事件解析失败不该中断整条流：上游可能夹了个我们没见过的形态，
            // 而已经发出的内容对下游仍然有效。记一条日志，跳过这个事件。
            log.warn("A2O 翻译跳过无法解析的上游事件: {}", exception.getMessage());
            return List.of();
        }

        String type = text(root, "type");
        if (type == null) {
            return List.of();
        }

        return switch (type) {
            case "message_start" -> onMessageStart(root, state);
            case "content_block_start" -> onContentBlockStart(root, state);
            case "content_block_delta" -> onContentBlockDelta(root, state);
            case "message_delta" -> onMessageDelta(root, state);
            // content_block_stop：Chat 协议没有块生命周期概念，无需通知下游。
            // message_stop：终止信号由 finalizeStream 统一发，见类注释。
            // ping：上游保活。本服务的流式链路自带 keep-alive，不重复造保活帧。
            case "content_block_stop", "message_stop", "ping" -> List.of();
            // error 事件的处置在调用方（需要区分「SSE 头是否已发出」），这里只记录。
            case "error" -> onError(root);
            default -> {
                log.debug("A2O 翻译忽略未知上游事件类型: {}", type);
                yield List.of();
            }
        };
    }

    /**
     * {@code message_start} —— 唯一带 {@code role} 的一帧。
     *
     * <p>同时从这里取上游的 message id 与首批 usage（输入 token 与缓存明细都在这个事件里）。
     */
    private List<String> onMessageStart(JsonNode root, A2OStreamState state) {
        JsonNode message = root.get("message");
        if (message != null) {
            state.adoptUpstreamId(text(message, "id"));
            state.adoptUpstreamModel(text(message, "model"));
            state.usage().merge(message.get("usage"));
        }
        if (!state.claimRoleFrame()) {
            // 上游重复发了 message_start。不再发第二个 role 帧——
            // 下游按增量累积，两个 role 帧会让它认为这是两条消息。
            return List.of();
        }
        return List.of(chunk(state, OpenAiResponseShapes.roleDelta(), null));
    }

    /**
     * {@code content_block_start} —— 只有 tool_use 产帧。
     *
     * <p>text 与 thinking 的块声明对 Chat 协议毫无意义（它没有块概念），
     * 产出空 delta 帧只是噪声。参考实现 new-api 因为 fall-through 会发出这种噪声帧。
     */
    private List<String> onContentBlockStart(JsonNode root, A2OStreamState state) {
        int blockIndex = intValue(root, "index", -1);
        JsonNode block = root.get("content_block");
        String blockType = text(block, "type");
        state.rememberBlockType(blockIndex, blockType);

        if (blockType == null) {
            return List.of();
        }
        if ("redacted_thinking".equals(blockType)) {
            // 内容已被上游加密，没有可展示的明文。记一条 warning：
            // 用户看到内容凭空变少时，日志里要有痕迹（契约第 5.2 节）。
            log.warn("A2O 翻译跳过 redacted_thinking 块（内容已加密，无明文可传递），index={}", blockIndex);
            return List.of();
        }
        if (isHostedBlock(blockType)) {
            log.debug("A2O 翻译跳过 hosted 工具块 {}（Chat 协议无对等物），index={}", blockType, blockIndex);
            return List.of();
        }
        if (!"tool_use".equals(blockType)) {
            // text / thinking：不产帧，内容在后续的 delta 里。
            return List.of();
        }

        int toolIndex = state.assignToolIndex(blockIndex);
        Map<String, Object> delta = OpenAiResponseShapes.toolCallStartDelta(
                toolIndex, text(block, "id"), text(block, "name"));
        return List.of(chunk(state, delta, null));
    }

    /**
     * {@code content_block_delta} —— 内容的主要载体。
     */
    private List<String> onContentBlockDelta(JsonNode root, A2OStreamState state) {
        JsonNode delta = root.get("delta");
        String deltaType = text(delta, "type");
        if (deltaType == null) {
            return List.of();
        }
        int blockIndex = intValue(root, "index", -1);

        return switch (deltaType) {
            case "text_delta" -> emitText(delta, state);
            case "thinking_delta" -> emitThinking(delta, state);
            case "input_json_delta" -> emitToolArguments(delta, blockIndex, state);
            // signature 由 Anthropic 自己签发，Chat 协议没有承载位置。
            // 吸收但不产帧，也不注入替代内容 —— 参考实现把它映射成一个换行符塞进
            // 思考流，那个换行会成为下游看到的真实内容，是凭空多出来的（契约第 5.1 节）。
            case "signature_delta" -> {
                log.debug("A2O 翻译丢弃 signature_delta（Chat 协议无承载位置，跨协议无法回放）");
                yield List.of();
            }
            default -> {
                log.debug("A2O 翻译忽略未知 delta 类型: {}", deltaType);
                yield List.of();
            }
        };
    }

    private List<String> emitText(JsonNode delta, A2OStreamState state) {
        String value = text(delta, "text");
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        state.markContentSeen();
        return List.of(chunk(state, OpenAiResponseShapes.contentDelta(value), null));
    }

    private List<String> emitThinking(JsonNode delta, A2OStreamState state) {
        String value = text(delta, "thinking");
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        // 思考内容无条件转发，不看有没有 tool_calls（契约第 5.3 节）。
        state.markContentSeen();
        return List.of(chunk(state, OpenAiResponseShapes.reasoningDelta(value), null));
    }

    /**
     * 工具参数增量。
     *
     * <p>必须按 Anthropic 的 block index 查已分配的 tool index，
     * <strong>不能用 block index 本身</strong>——那是另一个索引域（契约第 4 节）。
     * 查不到就丢弃并记日志，不猜一个序号：猜错会让参数拼到别的工具上。
     */
    private List<String> emitToolArguments(JsonNode delta, int blockIndex, A2OStreamState state) {
        String partialJson = text(delta, "partial_json");
        if (partialJson == null || partialJson.isEmpty()) {
            return List.of();
        }
        Integer toolIndex = state.toolIndex(blockIndex);
        if (toolIndex == null) {
            String blockType = state.blockType(blockIndex);
            if (blockType == null || !isHostedBlock(blockType)) {
                log.warn("A2O 翻译丢弃 input_json_delta：block index {} 没有对应的工具声明（blockType={}）",
                        blockIndex, blockType);
            }
            return List.of();
        }
        return List.of(chunk(state, OpenAiResponseShapes.toolCallArgumentsDelta(toolIndex, partialJson), null));
    }

    /**
     * {@code message_delta} —— 唯一带 {@code finish_reason} 的帧。
     *
     * <p>{@code stop_reason} 为 null 时仍产出一帧（不带 finish_reason）：
     * Anthropic 允许 {@code message_delta} 只携带 usage。
     */
    private List<String> onMessageDelta(JsonNode root, A2OStreamState state) {
        state.usage().merge(root.get("usage"));

        JsonNode delta = root.get("delta");
        String stopReason = text(delta, "stop_reason");
        state.recordStopReason(stopReason);

        String mapped = StopReasonMapper.toFinishReason(stopReason);
        if (mapped == null) {
            // 只带 usage 的 message_delta：没有可告知下游的状态变化，不产帧。
            return List.of();
        }
        if (!state.claimFinalize()) {
            // 上游重复发了带 stop_reason 的 message_delta。finish_reason 只能发一次。
            return List.of();
        }
        // 只发 finish chunk，不在这里带 usage —— usage 由 finalizeStream 统一发出，
        // 否则正常路径会产出两个 usage chunk（见 usageFrame 的说明）。
        return List.of(finishChunk(state, resolveFinishReason(state, mapped)));
    }

    /**
     * 决定最终的 {@code finish_reason} —— 两条收尾路径唯一的决策处。
     *
     * <h2>为什么工具调用能覆盖上游的终止原因</h2>
     * OpenAI 语义要求：响应含完整 {@code tool_calls} 时 {@code finish_reason} 必须是
     * {@code tool_calls}，优先级高于 {@code length} / {@code stop}。下游（Copilot）据此
     * 决定要不要执行工具：收到 {@code length} 会判定回答被截断，于是<strong>放弃执行
     * 已经拿到的完整工具调用</strong>并结束对话。这个故障没有任何异常，只表现为
     * 「工具齐全却没被调用」。
     *
     * <p>曾经这个判断只存在于 {@link #finalizeStream} 的兜底分支，正常路径
     * （{@code message_delta} 带了 stop_reason）直接采用 {@link StopReasonMapper} 的结果。
     * 于是出现了一个反直觉的不对称：上游<strong>不发</strong> message_delta 直接断连时结果正确，
     * 而上游规矩地发了终止原因反倒会丢工具。收敛到这里就是为了消除那半个缺失的判断。
     *
     * <p>触发它不需要非标 stop_reason：任何非 {@code tool_use} 的终止原因（标准的
     * {@code max_tokens}、{@code end_turn} 都算）配上工具调用都会复现，因此
     * <strong>不能</strong>只针对某一个 stop_reason 值打补丁。
     *
     * <p>反过来，没见过工具调用时必须原样返回映射结果：这里的职责是「工具调用优先」，
     * 而不是把所有终止原因都改写成 {@code tool_calls}。
     *
     * @param mapped {@link StopReasonMapper} 对上游 stop_reason 的映射结果，非 null
     * @return 本轮见过工具调用时返回 {@code tool_calls}，否则返回 {@code mapped}
     */
    private static String resolveFinishReason(A2OStreamState state, String mapped) {
        return state.sawToolCall() ? "tool_calls" : mapped;
    }

    private List<String> onError(JsonNode root) {
        JsonNode error = root.get("error");
        log.warn("A2O 翻译收到上游流内错误事件: type={}, message={}",
                text(error, "type"), text(error, "message"));
        // 流内错误的处置需要知道 SSE 头是否已发出，那是调用方的信息。
        // 这里只记录，不擅自产出帧或吞掉错误。
        return List.of();
    }

    /**
     * 流结束时的收尾。
     *
     * <h2>三档判定</h2>
     * <ol>
     *   <li>已发过 finish chunk（{@code message_delta} 带了 stop_reason）—— 只补
     *       {@code [DONE]}</li>
     *   <li>没发过但有实质输出 —— 按截断处理（{@code length}）。不能报成正常完成：
     *       那会让下游把一个被切断的回答当作完整答案</li>
     *   <li>什么都没有 —— 仍要发终止帧，否则下游会一直等。用 {@code stop} 而非
     *       {@code length}：这种情况通常是上游立刻断开，没有"生成了一半"的语义</li>
     * </ol>
     *
     * <p>注意「空响应」本身有独立的判定与重试预算
     * （{@code EmptyUpstreamResponseException}），走到这里说明重试已经耗尽或本轮有内容，
     * 收尾的职责只是让下游拿到一个结构完整的流。
     */
    List<String> finalizeStream(A2OStreamState state) {
        List<String> frames = new ArrayList<>(3);

        if (state.claimFinalize()) {
            String finishReason;
            if (state.sawSubstantiveOutput()) {
                // 与正常路径共用 resolveFinishReason：上游没给终止原因，本轮的默认判定是
                // 「截断」（length），工具调用优先的规则由同一个方法施加。
                finishReason = resolveFinishReason(state, "length");
                log.warn("A2O 翻译收尾：上游未发送 message_delta 终止原因，按截断处理（finish_reason={}）",
                        finishReason);
            } else {
                finishReason = "stop";
                log.warn("A2O 翻译收尾：上游未产出任何实质内容，发出空的终止帧");
            }
            frames.add(finishChunk(state, finishReason));
        }
        // usage 帧无条件在这里发（而非跟着 finish chunk 走），见 usageFrame 的说明。
        frames.addAll(usageFrame(state));
        frames.add(OpenAiResponseShapes.DONE_SENTINEL);
        return frames;
    }

    /**
     * finish chunk —— 唯一带非 null {@code finish_reason} 的帧。
     *
     * <p>它可能来自 {@code message_delta}（正常路径），也可能来自
     * {@link #finalizeStream} 的截断兜底，两处共用同一形态。
     */
    private String finishChunk(A2OStreamState state, String finishReason) {
        return chunk(state, OpenAiResponseShapes.finishDelta(), finishReason);
    }

    /**
     * usage chunk —— 独立一帧，{@code choices} 为空数组。
     *
     * <h2>唯一发出点是 finalizeStream</h2>
     * 曾经这一帧跟在 finish chunk 后面一起产出，于是正常路径会发出<strong>两个</strong>
     * usage chunk：{@code message_delta} 带 {@code stop_reason} 时发第一个，
     * 流结束时 {@link #finalizeStream} 又补第二个。宽容的客户端会拿后一个覆盖前一个
     * 因而看不出问题，但那是两条相同的计费记录。
     *
     * <p>收敛到收尾一处还顺带修正了<strong>完整性</strong>：Anthropic 允许在带
     * {@code stop_reason} 的 {@code message_delta} 之后再发只携带 usage 的
     * {@code message_delta}。跟着 finish chunk 发意味着用「那一刻」的累积值抢跑，
     * 之后到达的 usage 只能进第二帧 —— 于是两帧数字还不一样。放在收尾发出的必然是
     * 最完整的一份。
     *
     * <h2>顺序仍然固定：finish 在前、usage 在后</h2>
     * 反过来会让按 {@code finish_reason} 判断流结束的客户端提前收尾，漏掉 usage。
     * 正常路径下 finish chunk 由 {@code message_delta} 在更早的位置发出，
     * 截断路径下由上面的 {@code frames.add(finishChunk(...))} 发出，两种情况都在
     * usage 之前 —— 这个不变量由测试钉住。
     *
     * <p>只在下游明确要求（{@code stream_options.include_usage}）且真的有 usage 时发出。
     */
    private List<String> usageFrame(A2OStreamState state) {
        if (!state.includeUsage() || !state.usage().hasUsage()) {
            return List.of();
        }
        Map<String, Object> chunk = OpenAiResponseShapes.chunk(
                state.id(), state.model(), state.created(), null);
        chunk.put("usage", state.usage().toOpenAiUsage());
        return List.of(serialize(chunk));
    }

    private String chunk(A2OStreamState state, Map<String, Object> delta, String finishReason) {
        Map<String, Object> chunk = OpenAiResponseShapes.chunk(
                state.id(), state.model(), state.created(),
                OpenAiResponseShapes.deltaChoice(delta, finishReason));
        return serialize(chunk);
    }

    private String serialize(Map<String, Object> chunk) {
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception exception) {
            // 我们自己构造的 Map 序列化不出去属于编程错误。
            // 不抛异常中断整条流——返回一个语法合法的空 chunk，让流能走完。
            log.error("A2O 翻译序列化 chunk 失败: {}", exception.getMessage());
            return "{}";
        }
    }

    private static boolean isHostedBlock(String blockType) {
        return HOSTED_BLOCK_PREFIXES.stream().anyMatch(blockType::startsWith);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static int intValue(JsonNode node, String field, int defaultValue) {
        if (node == null) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : defaultValue;
    }
}

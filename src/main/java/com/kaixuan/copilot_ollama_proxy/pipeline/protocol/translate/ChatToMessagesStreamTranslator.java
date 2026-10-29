package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ResponseTranslationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat data → 0..n 个 Messages 事件 JSON（SSE 信封仍由出口包装）。
 *
 * <p>单块指一次只打开一个 content block，不指合并参数。首工具参数逐片输出；
 * 其它工具保留原分片排队，避免关掉工具 A、打开 B 后又给已关闭的 A 发 delta。
 * finish_reason 收尾内容块但延迟 message_delta；usage 尾帧、[DONE] 或正常 EOF 才结算消息。
 */
final class ChatToMessagesStreamTranslator {

    private static final Logger log = LoggerFactory.getLogger(ChatToMessagesStreamTranslator.class);

    private final ObjectMapper objectMapper;

    ChatToMessagesStreamTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<String> translateEvent(String data, C2MStreamState state) {
        if (data == null || data.isBlank() || state.finalized()) {
            return List.of();
        }
        if ("[DONE]".equals(data.trim())) {
            return finalizeStream(state);
        }
        JsonNode root = parse(data);
        if (root.hasNonNull("error")) {
            String message = AnthropicResponseShapes.text(root.get("error"), "message");
            throw new ResponseTranslationException("C2M 上游流内返回错误: "
                    + (message == null ? root.get("error").toString() : message));
        }
        JsonNode choices = root.get("choices");
        if (choices != null && !choices.isNull() && !choices.isArray()) {
            throw new ResponseTranslationException("C2M 流式响应的 choices 必须是数组");
        }
        JsonNode usage = root.get("usage");
        boolean hasUsage = ChatToMessagesUsageConverter.hasKnownCounters(usage);
        if ((choices == null || choices.isNull()) && !hasUsage) {
            log.debug("C2M 忽略无 choices/usage 的上游控制帧");
            return List.of();
        }
        state.adoptIdentity(AnthropicResponseShapes.text(root, "id"), AnthropicResponseShapes.text(root, "model"));
        state.rememberUsage(usage);
        List<Map<String, Object>> events = new ArrayList<>();
        appendMessageStart(state, events);

        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.get("delta");
            if (delta != null && !delta.isNull() && !delta.isObject()) {
                throw new ResponseTranslationException("C2M 流式响应的 choices[0].delta 必须是对象");
            }
            if (delta != null && delta.isObject()) {
                String reasoning = AnthropicResponseShapes.reasoning(delta);
                appendContent("thinking", reasoning, state, events, true);
                for (String text : AnthropicResponseShapes.contentTexts(delta.get("content"))) {
                    appendContent("text", text, state, events, true);
                }
                appendContent("text", AnthropicResponseShapes.text(delta, "refusal"), state, events, true);
                JsonNode tools = delta.get("tool_calls");
                if (tools != null && !tools.isNull()) {
                    if (!tools.isArray()) {
                        throw new ResponseTranslationException("C2M 流式响应的 tool_calls 必须是数组");
                    }
                    for (int index = 0; index < tools.size(); index++) {
                        recordTool(tools.get(index), index, state, events);
                    }
                }
                JsonNode legacy = delta.get("function_call");
                if ((tools == null || tools.isEmpty()) && legacy != null && legacy.isObject()) {
                    recordTool(objectMapper.createObjectNode().set("function", legacy), 0, state, events);
                }
                startReadyTool(state, events);
            }
            String finishReason = AnthropicResponseShapes.text(choice, "finish_reason");
            if (finishReason != null && !finishReason.isBlank()) {
                state.recordFinishReason(finishReason);
                finishContent(state, events);
            }
        } else if (hasUsage && (state.contentFinished() || state.hasTools() || state.currentBlockType() != null)) {
            // 只有真正尾部的 usage-only 快照触发结算；首个仅 usage 的占位帧不能提前结束。
            appendFinalEvents(state, events);
        }
        return serialize(events);
    }

    List<String> finalizeStream(C2MStreamState state) {
        if (state.finalized()) {
            return List.of();
        }
        List<Map<String, Object>> events = new ArrayList<>();
        appendMessageStart(state, events);
        appendFinalEvents(state, events);
        return serialize(events);
    }

    private void appendMessageStart(C2MStreamState state, List<Map<String, Object>> events) {
        if (!state.claimMessageStart()) {
            return;
        }
        Map<String, Object> usage = new LinkedHashMap<>(state.displayUsage());
        usage.put("output_tokens", 0);
        events.add(Map.of("type", "message_start", "message", AnthropicResponseShapes.message(
                state.id(), state.model(), List.of(), null, usage)));
    }

    private void appendContent(String type, String text, C2MStreamState state,
                                List<Map<String, Object>> events, boolean allowBuffer) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (allowBuffer && state.contentFinished()) {
            throw new ResponseTranslationException("C2M 上游在 finish_reason 之后继续发送内容");
        }
        if (allowBuffer && state.toolPhase()) {
            state.bufferContent(type, text);
            return;
        }
        if (!type.equals(state.currentBlockType())) {
            closeBlock(state, events);
            int index = state.openBlock(type);
            events.add(blockStart(index, AnthropicResponseShapes.textBlock(type, "")));
        }
        String field = "thinking".equals(type) ? "thinking" : "text";
        events.add(blockDelta(state.currentBlockIndex(), Map.of("type", type + "_delta", field, text)));
    }

    private void recordTool(JsonNode incoming, int arrayIndex, C2MStreamState state,
                              List<Map<String, Object>> events) {
        if (!incoming.isObject()) {
            throw new ResponseTranslationException("C2M 流式 tool_calls 元素必须是对象");
        }
        JsonNode indexNode = incoming.get("index");
        int index = arrayIndex;
        if (indexNode != null && !indexNode.isNull()) {
            if (!indexNode.isIntegralNumber() || !indexNode.canConvertToInt() || indexNode.intValue() < 0) {
                throw new ResponseTranslationException("C2M 流式 tool_calls.index 必须是非负整数");
            }
            index = indexNode.intValue();
        }
        JsonNode function = incoming.get("function");
        if (function != null && !function.isNull() && !function.isObject()) {
            throw new ResponseTranslationException("C2M 流式 tool_calls.function 必须是对象");
        }
        JsonNode argumentNode = function == null ? null : function.get("arguments");
        if (argumentNode != null && !argumentNode.isNull() && !argumentNode.isTextual()) {
            throw new ResponseTranslationException("C2M 流式 tool_calls.function.arguments 必须是字符串");
        }
        String id = AnthropicResponseShapes.text(incoming, "id");
        String name = AnthropicResponseShapes.text(function, "name");
        String arguments = AnthropicResponseShapes.text(function, "arguments");
        if ((id == null || id.isBlank()) && (name == null || name.isBlank())
                && (arguments == null || arguments.isEmpty())) {
            return;
        }
        if (state.contentFinished()) {
            throw new ResponseTranslationException("C2M 上游在 finish_reason 之后继续发送工具调用");
        }
        if (state.currentBlockType() != null && !"tool_use".equals(state.currentBlockType())) {
            closeBlock(state, events);
        }
        C2MStreamState.ToolCall tool = state.tool(index);
        // 只采纳首个有效声明；重复/空白元数据不得让已经下发的 id/name 漂移。
        if (tool.id == null && id != null && !id.isBlank()) {
            tool.id = id;
        }
        if (tool.name == null && name != null && !name.isBlank()) {
            tool.name = name;
        }
        if (arguments != null && !arguments.isEmpty()) {
            if (tool.started && !tool.closed && state.activeTool() == tool) {
                appendArguments(tool, arguments, events);
            } else {
                tool.pendingArguments.add(arguments);
            }
        }
    }

    private void startReadyTool(C2MStreamState state, List<Map<String, Object>> events) {
        if (state.activeTool() != null || state.contentFinished()) {
            return;
        }
        for (C2MStreamState.ToolCall tool : state.toolsInArrivalOrder()) {
            if (!tool.closed && !tool.started) {
                if (tool.ready()) {
                    startTool(tool, state, events);
                }
                return;
            }
        }
    }

    private void startTool(C2MStreamState.ToolCall tool, C2MStreamState state,
                            List<Map<String, Object>> events) {
        state.activateTool(tool);
        events.add(blockStart(tool.blockIndex, AnthropicResponseShapes.toolBlock(tool.id, tool.name, Map.of())));
        for (String arguments : tool.pendingArguments) {
            appendArguments(tool, arguments, events);
        }
        tool.pendingArguments.clear();
    }

    private static void appendArguments(C2MStreamState.ToolCall tool, String arguments,
                                          List<Map<String, Object>> events) {
        events.add(blockDelta(tool.blockIndex, Map.of("type", "input_json_delta", "partial_json", arguments)));
        tool.emittedArguments = true;
    }

    private void closeBlock(C2MStreamState state, List<Map<String, Object>> events) {
        if (state.currentBlockType() == null) {
            return;
        }
        C2MStreamState.ToolCall tool = state.activeTool();
        if (tool != null) {
            if (!tool.emittedArguments) {
                appendArguments(tool, "{}", events);
            }
            tool.closed = true;
        }
        events.add(Map.of("type", "content_block_stop", "index", state.currentBlockIndex()));
        state.closeBlock();
    }

    private void finishContent(C2MStreamState state, List<Map<String, Object>> events) {
        if (state.contentFinished()) {
            return;
        }
        closeBlock(state, events);
        for (C2MStreamState.ToolCall tool : state.toolsInArrivalOrder()) {
            if (tool.closed) {
                continue;
            }
            if (!tool.ready()) {
                log.warn("C2M 工具声明不完整，收尾补齐元数据，index={}", tool.upstreamIndex);
                tool.id = AnthropicResponseShapes.nonBlankOr(tool.id,
                    AnthropicResponseShapes.fallbackToolId(state.id(), tool.upstreamIndex));
                tool.name = AnthropicResponseShapes.nonBlankOr(tool.name, "tool_" + tool.upstreamIndex);
            }
            startTool(tool, state, events);
            closeBlock(state, events);
        }
        for (C2MStreamState.BufferedContent chunk : state.drainBufferedContent()) {
            appendContent(chunk.type(), chunk.text(), state, events, false);
        }
        closeBlock(state, events);
        state.finishContent();
    }

    private void appendFinalEvents(C2MStreamState state, List<Map<String, Object>> events) {
        if (state.finalized()) {
            return;
        }
        finishContent(state, events);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("stop_reason", AnthropicResponseShapes.stopReason(state.finishReason(), state.hasTools()));
        delta.put("stop_sequence", null);
        events.add(Map.of("type", "message_delta", "delta", delta, "usage", state.displayUsage()));
        events.add(Map.of("type", "message_stop"));
        state.finalizeMessage();
    }

    private static Map<String, Object> blockStart(int index, Map<String, Object> block) {
        return Map.of("type", "content_block_start", "index", index, "content_block", block);
    }

    private static Map<String, Object> blockDelta(int index, Map<String, Object> delta) {
        return Map.of("type", "content_block_delta", "index", index, "delta", delta);
    }

    private List<String> serialize(List<Map<String, Object>> events) {
        return events.stream().map(event -> AnthropicResponseShapes.serialize(objectMapper, event)).toList();
    }

    private JsonNode parse(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            if (root == null || !root.isObject()) {
                throw new ResponseTranslationException("C2M 流式响应必须是 JSON 对象");
            }
            return root;
        } catch (JsonProcessingException exception) {
            throw new ResponseTranslationException("C2M 上游事件不是有效 JSON: " + exception.getOriginalMessage());
        }
    }
}
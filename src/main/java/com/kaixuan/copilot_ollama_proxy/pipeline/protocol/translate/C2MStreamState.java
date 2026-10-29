package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat → Messages 回程的订阅级状态（ADR-0015）。
 *
 * <p>参考 new-api 的显式工具索引映射，但选用单个打开的 content block。
 * 首个工具可逐片输出，其它工具与交错正文/思考保留分片排队；不会向已 stop 的块补 delta。
 * 状态由门面的 Flux.defer 创建，出站、重订阅与日志重放各用独立实例。
 * 不持有记账指标：展示 usage 与 UpstreamEvent.usage() 是两个通道。
 */
final class C2MStreamState {

    /** 仅本方向的状态机操作这些字段，不作为跨层上下文。 */
    static final class ToolCall {
        final int upstreamIndex;
        final List<String> pendingArguments = new ArrayList<>();
        String id;
        String name;
        int blockIndex = -1;
        boolean started;
        boolean closed;
        boolean emittedArguments;

        ToolCall(int upstreamIndex) {
            this.upstreamIndex = upstreamIndex;
        }

        boolean ready() {
            return id != null && !id.isBlank() && name != null && !name.isBlank();
        }
    }

    record BufferedContent(String type, String text) {
    }

    private String id;
    private String model;
    private boolean messageStarted;
    private boolean contentFinished;
    private boolean finalized;
    private int nextBlockIndex;
    private String currentBlockType;
    private int currentBlockIndex = -1;
    private ToolCall activeTool;
    private boolean toolPhase;
    private final Map<Integer, ToolCall> tools = new LinkedHashMap<>();
    private final List<BufferedContent> bufferedContent = new ArrayList<>();
    private String finishReason;
    private JsonNode lastUsage;

    C2MStreamState(String fallbackId, String upstreamModel) {
        this.id = fallbackId;
        this.model = upstreamModel == null ? "" : upstreamModel;
    }

    void adoptIdentity(String upstreamId, String upstreamModel) {
        if (!messageStarted) {
            id = AnthropicResponseShapes.nonBlankOr(upstreamId, id);
            model = AnthropicResponseShapes.nonBlankOr(upstreamModel, model);
        }
    }

    String id() {
        return id;
    }

    String model() {
        return model;
    }

    boolean claimMessageStart() {
        if (messageStarted) {
            return false;
        }
        messageStarted = true;
        return true;
    }

    String currentBlockType() {
        return currentBlockType;
    }

    int currentBlockIndex() {
        return currentBlockIndex;
    }

    int openBlock(String type) {
        currentBlockType = type;
        currentBlockIndex = nextBlockIndex++;
        return currentBlockIndex;
    }

    void closeBlock() {
        currentBlockType = null;
        currentBlockIndex = -1;
        activeTool = null;
    }

    ToolCall activeTool() {
        return activeTool;
    }

    void activateTool(ToolCall tool) {
        tool.blockIndex = openBlock("tool_use");
        tool.started = true;
        activeTool = tool;
    }

    ToolCall tool(int upstreamIndex) {
        toolPhase = true;
        return tools.computeIfAbsent(upstreamIndex, ToolCall::new);
    }

    List<ToolCall> toolsInArrivalOrder() {
        return List.copyOf(tools.values());
    }

    boolean hasTools() {
        return !tools.isEmpty();
    }

    boolean toolPhase() {
        return toolPhase;
    }

    void bufferContent(String type, String text) {
        // 保留每片边界；不能把一段工具期间的输出悄悄合成为单片。
        bufferedContent.add(new BufferedContent(type, text));
    }

    List<BufferedContent> drainBufferedContent() {
        List<BufferedContent> result = List.copyOf(bufferedContent);
        bufferedContent.clear();
        return result;
    }

    void recordFinishReason(String reason) {
        if (finishReason == null && reason != null && !reason.isBlank()) {
            finishReason = reason;
        }
    }

    String finishReason() {
        return finishReason;
    }

    boolean contentFinished() {
        return contentFinished;
    }

    void finishContent() {
        contentFinished = true;
    }

    boolean finalized() {
        return finalized;
    }

    void finalizeMessage() {
        finalized = true;
    }

    void rememberUsage(JsonNode usage) {
        if (ChatToMessagesUsageConverter.hasKnownCounters(usage)) {
            // Chat 后到快照覆盖，允许真实 0；未知字段的空指标不抹掉已知值。
            lastUsage = usage.deepCopy();
        }
    }

    Map<String, Object> displayUsage() {
        return ChatToMessagesUsageConverter.toAnthropicUsage(lastUsage);
    }
}
package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A2O 流式翻译的跨帧状态。
 *
 * <h2>为何需要状态</h2>
 * 只有一个原因是<strong>本质性</strong>的：Anthropic 的 content block index 与
 * OpenAI 的 {@code tool_calls[].index} 是两个独立索引域，必须靠一张表桥接
 * （见 {@link #assignToolIndex}）。其余字段都是为了避免重复解析或重复发帧。
 *
 * <h2>生命周期：一次上游往返</h2>
 * <strong>必须在重试重订阅时重建</strong>。若跨重试复用，第二轮会带着第一轮的
 * {@code sentRole} 与 tool index 分配——前者让下游收不到 role 帧，
 * 后者让 index 从一个非零值开始。这与 {@code GenericAnthropicChatService} 在
 * {@code Flux.defer} 内重置 {@code sawPayload} 是同一个道理。
 *
 * <h2>刻意不持有的字段</h2>
 * 参考实现 sub2api 的状态里有 {@code sequenceNumber}、{@code outputs}、
 * {@code currentContent}、{@code textAccum}、{@code contentIndex}、{@code currentItemId}，
 * 那些全是它走 OpenAI Responses 中间层时的账本。A2O 直连一个都不需要——
 * Chat Completions 是扁平的增量流，没有块生命周期概念。
 *
 * <p>契约第 11 节。
 */
final class A2OStreamState {

    /** 每帧都要回显的身份三元组。 */
    private String id;
    /**
     * 上游返回的模型名。
     *
     * <p>不用下游带前缀的请求名：OpenAI 直连路径下代理也不改写响应里的 model，
     * 两条路必须同口径，否则同一个客户端会因为走了哪条路而看到不同的模型名。
     */
    private String model;
    private final long created;

    /** role 帧只发一次。 */
    private boolean sentRole;

    /**
     * Anthropic content block index → OpenAI tool index。
     *
     * <p>这是本类存在的核心理由。实测序列里 thinking 占 index 0、text 占 index 1，
     * 若把 Anthropic index 直接当 tool index 用，下游会收到从非零开始的稀疏数组。
     */
    private final Map<Integer, Integer> toolIndexByBlockIndex = new LinkedHashMap<>();

    /** 下一个可用的 OpenAI tool index。稠密递增，与 Anthropic index 无关。 */
    private int nextToolIndex;

    /** Anthropic content block index → block type，用于识别 hosted 块与 redacted_thinking。 */
    private final Map<Integer, String> blockTypeByIndex = new LinkedHashMap<>();

    /** 见过工具调用。收尾兜底 finish_reason 时用。 */
    private boolean sawToolCall;

    /** 见过实质内容（正文或思考）。三档收尾判定用。 */
    private boolean sawContent;

    /** 上游给的终止原因，由 message_delta 写、收尾读。 */
    private String stopReason;

    /** usage 累加器，跨 message_start 与 message_delta 合并。 */
    private final AnthropicUsageAccumulator usage = new AnthropicUsageAccumulator();

    /** 下游是否要求附带 usage，来自请求期的 TranslationContext。 */
    private final boolean includeUsage;

    /** 收尾幂等：finish chunk 已发出。 */
    private boolean finalized;

    /**
     * @param fallbackId    上游给出 message id 之前的占位值
     * @param fallbackModel 上游给出模型名之前的占位值（取上游真实模型名，
     *                      不含供应商前缀）
     */
    A2OStreamState(String fallbackId, String fallbackModel, boolean includeUsage) {
        this.id = fallbackId;
        this.model = fallbackModel;
        this.includeUsage = includeUsage;
        this.created = System.currentTimeMillis() / 1000;
    }

    String id() {
        return id;
    }

    /**
     * 用上游的 message id 替换占位 id。
     *
     * <p>只在非空时替换：上游若不给 id，保留构造时的占位值，
     * 而不是让下游收到 null。
     */
    void adoptUpstreamId(String upstreamId) {
        if (upstreamId != null && !upstreamId.isBlank()) {
            this.id = upstreamId;
        }
    }

    String model() {
        return model;
    }

    /**
     * 采纳上游 {@code message_start} 里的模型名。
     *
     * <p>只在非空时替换：上游若不给，保留构造时的占位值，
     * 而不是让下游收到 null。
     */
    void adoptUpstreamModel(String upstreamModel) {
        if (upstreamModel != null && !upstreamModel.isBlank()) {
            this.model = upstreamModel;
        }
    }

    long created() {
        return created;
    }

    boolean includeUsage() {
        return includeUsage;
    }

    /** 首次调用返回 true，之后恒为 false。 */
    boolean claimRoleFrame() {
        if (sentRole) {
            return false;
        }
        sentRole = true;
        return true;
    }

    void rememberBlockType(int blockIndex, String type) {
        if (type != null) {
            blockTypeByIndex.put(blockIndex, type);
        }
    }

    String blockType(int blockIndex) {
        return blockTypeByIndex.get(blockIndex);
    }

    /**
     * 为一个 tool_use 块分配 OpenAI tool index。
     *
     * <p>重复调用同一个 blockIndex 返回既有分配——上游理论上不会对同一个块发两次
     * {@code content_block_start}，但幂等比报错更稳。
     */
    int assignToolIndex(int blockIndex) {
        Integer existing = toolIndexByBlockIndex.get(blockIndex);
        if (existing != null) {
            return existing;
        }
        int assigned = nextToolIndex++;
        toolIndexByBlockIndex.put(blockIndex, assigned);
        sawToolCall = true;
        return assigned;
    }

    /**
     * 查询已分配的 tool index。
     *
     * @return 未分配时返回 null——调用方据此判断这是不是一个我们没见过声明的块，
     *         <strong>不要猜</strong>
     */
    Integer toolIndex(int blockIndex) {
        return toolIndexByBlockIndex.get(blockIndex);
    }

    void markContentSeen() {
        sawContent = true;
    }

    boolean sawToolCall() {
        return sawToolCall;
    }

    /**
     * 是否产出过实质输出。
     *
     * <p>判据与 {@code AnthropicContentDetector} 同口径：正文、思考、工具调用之一，
     * <strong>usage 不算</strong>。收尾三档判定用（契约第 6.2 节）。
     */
    boolean sawSubstantiveOutput() {
        return sawContent || sawToolCall;
    }

    void recordStopReason(String reason) {
        if (reason != null && !reason.isBlank()) {
            this.stopReason = reason;
        }
    }

    String stopReason() {
        return stopReason;
    }

    AnthropicUsageAccumulator usage() {
        return usage;
    }

    /** 首次调用返回 true，之后恒为 false。收尾幂等靠它。 */
    boolean claimFinalize() {
        if (finalized) {
            return false;
        }
        finalized = true;
        return true;
    }

    boolean isFinalized() {
        return finalized;
    }
}

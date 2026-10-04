package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * R2C 流式翻译的跨事件状态。
 *
 * <h2>为何需要状态</h2>
 * 本质性理由与 {@code M2CStreamState} 同源：<strong>两个独立索引域</strong>。
 * Responses 的 {@code output_index} 覆盖所有 item（reasoning 常占 0），而 Chat 的
 * {@code tool_calls[].index} 只在工具数组内编号 —— 必须靠一张表桥接
 * （见 {@link #registerTool}）。此外 R2C 多出两件 M2C 没有的事：
 * <ul>
 *   <li><strong>arguments 的已发位置追踪</strong>（{@code argsSentAt}）——「前缀补齐」
 *       要求 done 携带完整参数时只发差量，不重复（R2C-RESEARCH §2.4 A 派）；</li>
 *   <li><strong>pending 参数存留</strong>（{@code pendingArgsBy*}）—— delta 早于
 *       {@code output_item.added} 到达时先存，注册时拼入（§9-4，new-api 派）。</li>
 * </ul>
 *
 * <h2>生命周期：一次上游往返</h2>
 * <strong>必须在重试重订阅时重建</strong>（门面在 {@code Flux.defer} 内创建本对象）。
 * 若跨轮复用，第二轮会带着第一轮的 {@code sentRole} 与 tool index —— 前者让下游
 * 收不到 role 帧，后者让 index 从非零开始。
 *
 * <h2>不设「Done 硬门」（与 CPA 的刻意分歧）</h2>
 * {@code output_item.done} 之后到达的 {@code arguments.delta} 仍然接受：
 * cc-switch 实测（2026-10-03，MiniMax）存在「done 先到且参数为空、完整参数在随后的
 * delta」的乱序上游。CPA 在 done 后置 {@code state.Done} 丢弃后续 delta，会把参数
 * 整个丢掉 —— 这是它的已知弱面（R2C-RESEARCH §5.2），不抄。
 *
 * <p>契约依据见 R2C-PLAN §2.2/§2.3。
 */
final class R2CStreamState {

    // ==================== 身份三元组（每帧回显） ====================

    private String id;
    /** 上游返回的模型名（裸名透传，R2C-PLAN §6-1 定案——与 M2C/直连同口径）。 */
    private String model;
    private final long created;

    /** role 帧只发一次（created 时发；首个内容事件检查未发则带上——混合兜底 §9-3）。 */
    private boolean sentRole;

    // ==================== 工具登记（本地重编号 + 三级映射） ====================

    /** 下一个可用的 Chat 侧 tool index。稠密递增，与上游 output_index 无关。 */
    private int nextToolIndex;

    /** 工具记录，按本地 key 索引。key 由 {@link #keyOf} 生成。 */
    private final Map<String, ToolRecord> toolsByKey = new LinkedHashMap<>();

    /** 三级映射：output_index / item_id / call_id → 本地 key（先到先得，多键共指一条记录）。 */
    private final Map<String, String> keyByOutputIndex = new LinkedHashMap<>();
    private final Map<String, String> keyByItemId = new LinkedHashMap<>();
    private final Map<String, String> keyByCallId = new LinkedHashMap<>();

    /** delta 早于 added 时按 output_index / item_id 存留，注册时拼入（§9-4）。 */
    private final Map<String, StringBuilder> pendingArgs = new LinkedHashMap<>();

    // ==================== 内容与收尾 ====================

    /** 见过工具调用。finish_reason 的 tool_calls 优先判定用。 */
    private boolean sawToolCall;

    /** 见过实质内容（正文或思考）。三档收尾判定用。 */
    private boolean sawContent;

    /** reasoning 段结束标记：下个 reasoning delta 前按差额补足到 2 个换行（§9-5）。 */
    private boolean needsReasoningBreak;

    /** 上游终止原因已记录（completed/incomplete 的 finish chunk 已发）。 */
    private boolean sentFinish;

    /** 收尾幂等：finalizeStream 已跑。 */
    private boolean finalized;

    /** 最后一份非 null 的 usage 原文（Responses 的 usage 在终态事件一次给全）。 */
    private String lastUsageRaw;

    /** 下游是否要求附带 usage（来自请求期 TranslationContext）。 */
    private final boolean includeUsage;

    /**
     * 单个工具调用的跨事件记录。
     *
     * <p>{@code argsSentAt} 是「已发出的 arguments 总长度」：done 携带完整参数时
     * 只发 {@code arguments.substring(argsSentAt)} —— 正常流下 delta 已逐片发过，
     * done 的差量为空（不发）；乱序流（done 先到、参数后到）下 delta 直接透传。
     * {@code nameSent} 保证 name 只随首帧发一次 —— arguments 增量帧里带
     * {@code "name":""} 会覆盖客户端已累积的名字（sub2api 钉过的坑）。
     */
    static final class ToolRecord {
        final int index;
        String callId;
        String name;
        final StringBuilder arguments = new StringBuilder();
        int argsSentAt;
        boolean nameSent;

        ToolRecord(int index) {
            this.index = index;
        }
    }

    /**
     * @param fallbackId    上游给出 response id 之前的占位值
     * @param fallbackModel 上游给出模型名之前的占位值（取上游真实模型名，不含供应商前缀）
     * @param includeUsage  下游是否要求流式附带 usage
     */
    R2CStreamState(String fallbackId, String fallbackModel, boolean includeUsage) {
        this.id = fallbackId;
        this.model = fallbackModel;
        this.includeUsage = includeUsage;
        this.created = System.currentTimeMillis() / 1000;
    }

    // ==================== 身份 ====================

    String id() {
        return id;
    }

    String model() {
        return model;
    }

    long created() {
        return created;
    }

    boolean includeUsage() {
        return includeUsage;
    }

    /** 只在非空时替换占位值——上游不给就保留占位，不让下游收到 null。 */
    void adoptUpstreamId(String upstreamId) {
        if (upstreamId != null && !upstreamId.isBlank()) {
            this.id = upstreamId;
        }
    }

    void adoptUpstreamModel(String upstreamModel) {
        if (upstreamModel != null && !upstreamModel.isBlank()) {
            this.model = upstreamModel;
        }
    }

    // ==================== role 帧（混合兜底 §9-3） ====================

    /** 首次调用返回 true，之后恒 false。 */
    boolean claimRoleFrame() {
        if (sentRole) {
            return false;
        }
        sentRole = true;
        return true;
    }

    // ==================== 工具登记与查找 ====================

    /**
     * 生成事件的本地 key。优先级：{@code call_id} > {@code item_id} > {@code output_index}
     * —— call_id 是配对键（最稳定），output_index 是兜底（所有事件都带但可能缺失）。
     */
    static String keyOf(String itemId, String callId, Integer outputIndex) {
        if (callId != null && !callId.isBlank()) {
            return "call:" + callId;
        }
        if (itemId != null && !itemId.isBlank()) {
            return "item:" + itemId;
        }
        if (outputIndex != null) {
            return "output:" + outputIndex;
        }
        return null;
    }

    /**
     * 注册或找到工具记录（{@code output_item.added} 与终态补发共用）。
     *
     * <p>三级查找：直接 key → item_id → call_id。注册时把三种键都指到同一条记录，
     * 后续任何一种键的事件（delta/done）都能命中。命中已有记录时补充缺失字段
     * （如 added 未带 call_id 而 done 带了），并把 pending 参数拼入。
     */
    ToolRecord registerTool(String itemId, String callId, String name, Integer outputIndex) {
        String key = keyOf(itemId, callId, outputIndex);
        ToolRecord tool = key != null ? toolsByKey.get(key) : null;
        if (tool == null) {
            // 三级回落查找：同一工具可能用不同键出现在不同事件里。
            String altKey = itemId != null && !itemId.isBlank() ? keyByItemId.get(itemId) : null;
            if (altKey == null && callId != null && !callId.isBlank()) {
                altKey = keyByCallId.get(callId);
            }
            if (altKey == null && outputIndex != null) {
                altKey = keyByOutputIndex.get(String.valueOf(outputIndex));
            }
            if (altKey != null) {
                tool = toolsByKey.get(altKey);
                if (key != null && tool != null) {
                    toolsByKey.put(key, tool);
                }
            }
        }
        if (tool == null) {
            tool = new ToolRecord(nextToolIndex++);
        }
        // 注册到<strong>所有可用键</strong>下（而非只存主键）：后续事件可能带任何一种键，
        // 只存主键会让「keyByXxx 指向的键查不到记录」—— 三个键指向同一条记录，
        // 多键并存正是三级查找的前提。
        if (key != null) {
            toolsByKey.put(key, tool);
        }
        if (itemId != null && !itemId.isBlank()) {
            toolsByKey.put("item:" + itemId, tool);
        }
        if (callId != null && !callId.isBlank()) {
            toolsByKey.put("call:" + callId, tool);
        }
        if (outputIndex != null) {
            toolsByKey.put("output:" + outputIndex, tool);
        }
        // 次级索引 + pending 拼入：注册晚于 delta 时把存留的参数补上。
        if (itemId != null && !itemId.isBlank()) {
            keyByItemId.put(itemId, "item:" + itemId);
            StringBuilder pending = pendingArgs.remove("item:" + itemId);
            if (pending != null) {
                tool.arguments.append(pending);
            }
        }
        if (callId != null && !callId.isBlank()) {
            keyByCallId.put(callId, "call:" + callId);
            StringBuilder pending = pendingArgs.remove("call:" + callId);
            if (pending != null) {
                tool.arguments.append(pending);
            }
        }
        if (outputIndex != null) {
            keyByOutputIndex.put(String.valueOf(outputIndex), "output:" + outputIndex);
            StringBuilder pending = pendingArgs.remove("output:" + outputIndex);
            if (pending != null) {
                tool.arguments.append(pending);
            }
        }
        if (callId != null && !callId.isBlank() && tool.callId == null) {
            tool.callId = callId;
        }
        if (name != null && !name.isBlank() && tool.name == null) {
            tool.name = name;
        }
        return tool;
    }

    /**
     * 按事件键查工具（不注册）。delta / done 事件用；查不到返回 null
     * （delta 的存留由调用方决定，done 的兜底注册也由调用方发起）。
     */
    ToolRecord findTool(String itemId, String callId, Integer outputIndex) {
        String key = keyOf(itemId, callId, outputIndex);
        ToolRecord tool = key != null ? toolsByKey.get(key) : null;
        if (tool != null) {
            return tool;
        }
        if (itemId != null && !itemId.isBlank()) {
            String alt = keyByItemId.get(itemId);
            if (alt != null) {
                return toolsByKey.get(alt);
            }
        }
        if (callId != null && !callId.isBlank()) {
            String alt = keyByCallId.get(callId);
            if (alt != null) {
                return toolsByKey.get(alt);
            }
        }
        if (outputIndex != null) {
            String alt = keyByOutputIndex.get(String.valueOf(outputIndex));
            if (alt != null) {
                return toolsByKey.get(alt);
            }
        }
        return null;
    }

    /** delta 早于注册时存留参数（按可用键存，注册时 {@link #registerTool} 拼入）。 */
    void stashPendingArgs(String itemId, String callId, Integer outputIndex, String delta) {
        String key = keyOf(itemId, callId, outputIndex);
        if (key == null) {
            return;
        }
        pendingArgs.computeIfAbsent(key, k -> new StringBuilder()).append(delta);
    }

    /** 全部已登记工具（终态补发按登记顺序遍历）。 */
    Iterable<ToolRecord> tools() {
        return toolsByKey.values();
    }

    /** 已登记且未发过任何帧的工具（去重用：Set 身份按引用）。 */
    Set<ToolRecord> registeredTools() {
        return new LinkedHashSet<>(toolsByKey.values());
    }

    void markToolCallSeen() {
        sawToolCall = true;
    }

    boolean sawToolCall() {
        return sawToolCall;
    }

    // ==================== 内容 ====================

    void markContentSeen() {
        sawContent = true;
    }

    boolean sawContent() {
        return sawContent;
    }

    // ==================== reasoning 分隔（§9-5 标记 + 差额） ====================

    void markReasoningBreak() {
        needsReasoningBreak = true;
    }

    /**
     * 取「本段 reasoning delta 的实际前缀」：若需要分隔且 delta 不以换行开头，
     * 前置 {@code \n\n}；以单个换行开头补到两个；已以 {@code \n\n} 开头则不补。
     *
     * @return 处理后的 delta（可能加长）；分隔需求同时被消费
     */
    String applyReasoningBreak(String delta) {
        if (!needsReasoningBreak) {
            return delta;
        }
        needsReasoningBreak = false;
        if (delta.startsWith("\n\n")) {
            return delta;
        }
        if (delta.startsWith("\n")) {
            return "\n" + delta;
        }
        return "\n\n" + delta;
    }

    // ==================== 收尾 ====================

    /** finish chunk 只发一次（上游可能重复发终态事件）。 */
    boolean claimFinish() {
        if (sentFinish) {
            return false;
        }
        sentFinish = true;
        return true;
    }

    boolean sentFinish() {
        return sentFinish;
    }

    /** finalizeStream 幂等。 */
    boolean claimFinalize() {
        if (finalized) {
            return false;
        }
        finalized = true;
        return true;
    }

    // ==================== usage ====================

    /** 记录最后一份非 null 的 usage 原文（终态事件一次给全；兼容中途携带的上游）。 */
    void recordUsage(String usageRaw) {
        if (usageRaw != null && !usageRaw.isBlank()) {
            this.lastUsageRaw = usageRaw;
        }
    }

    String lastUsageRaw() {
        return lastUsageRaw;
    }
}

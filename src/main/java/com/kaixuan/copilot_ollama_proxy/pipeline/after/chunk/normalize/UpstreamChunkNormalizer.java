package com.kaixuan.copilot_ollama_proxy.pipeline.after.chunk.normalize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.after.content.OpenAiContentDetector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 上游 chunk 的<strong>形态归一</strong> —— 把各供应商五花八门的 SSE chunk
 * 统一成本服务内部约定的 OpenAI 标准形态。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>实现体</strong>（静态工具） · 位置：{@code pipeline/after/chunk/normalize/}
 * 步骤「chunk 形态归一」的纯逻辑
 * <p>完整步骤树见 {@code pipeline/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>它做什么</h2>
 * <ol>
 *   <li>统一 reasoning 字段名：{@code thinking} / {@code reasoning} / {@code reasoning_text}
 *       / {@code cot_summary} → {@code reasoning_content}；</li>
 *   <li>统一 {@code finish_reason}：空字符串 → {@code null}；</li>
 *   <li>清理空 {@code tool_calls}（{@code []} / {@code null} → 删除）；</li>
 *   <li>递归剪枝空值（{@code null} / {@code ""} / {@code []} / 空 Map）。</li>
 * </ol>
 *
 * <p><strong>此方法不做「下游格式化」</strong>：清洗后的 chunk 仍然是 OpenAI 格式，
 * 只是把「同一件事的不同写法」收敛成一种。真正的下游差异由各端点自己的出口处理
 * （Chat 直接包 SSE 信封，Anthropic / Responses 各自转换）。
 *
 * <h2>为何专为 Chat 路径而做（不要「推广」到另两条线路）</h2>
 * Chat 路径的上游 chunk <strong>鱼龙混杂</strong> —— 各家供应商的字段名并不一致。
 * 实测症状：一度发现「某个模型在下游 agent 工具里不显示思考链」，
 * 起初以为它不支持思考链，抓包才发现是 chunk 格式太杂导致无法解析。
 * 清洗就是为此而做。
 *
 * <p>Anthropic 与 Responses 两条线路<strong>没有</strong>这一步是预期的协议差异，
 * 不是遗漏：它们的事件结构本就由各自协议规定，不存在「同义字段名」问题。
 * 因此本类在支线查表时只会被 Chat 命中，
 * 另两条<strong>未命中即跳过</strong>。
 *
 * <h2>为何不进主干阶段</h2>
 * 本类读写的是 OpenAI 专属形态（{@code choices[].delta} / {@code reasoning_content}
 * / {@code tool_calls}）。做成协议无关的主干阶段会对着 Anthropic 的
 * {@code content_block_delta} 做无效归一 —— 它没有 {@code choices}，
 * 那些判定只会全部落空，白跑一遍。
 *
 * <h2>为何 objectMapper 是参数</h2>
 * 与 {@code UpstreamRetryPolicy} / {@code UpstreamCallReporter} 同一取向：
 * 本类无状态、不做 Spring Bean、不持有依赖。{@code objectMapper} 与
 * {@code OpenAiContentDetector} / {@code OpenAiUsageParser} 的处理一致，
 * 由调用方传入，避免在工具类里藏一个静态可变引用。
 *
 * <h2>流级状态由调用方持有</h2>
 * {@code contentEmitted} / {@code reasoningBuffer} / {@code chunkId} 是
 * <strong>跨帧累积</strong>的流级状态（重试一次就要重置），因此它们不属于本类，
 * 而是作为参数穿线传递 —— 判据是「<strong>重试时该不该归零</strong>」。
 */
public final class UpstreamChunkNormalizer {

    private UpstreamChunkNormalizer() {
    }

    /**
     * 清洗一个上游 SSE chunk。
     *
     * <h2>三处「刻意保留结构」的补偿</h2>
     * 递归剪枝会删掉空值，但有几个空值本身<strong>携带结构语义</strong>，
     * 删掉会让下游解析不出「这是一个结束帧」。因此剪枝后按需补回：
     * <ul>
     *   <li>{@code finish_reason: null} —— 中间 chunk 的显式 null 是协议字段，
     *       与「字段不存在」在部分客户端里含义不同；</li>
     *   <li>{@code delta: {}} —— 结束帧与「空 delta 帧」都应保留空对象形态，
     *       而不是变成 {@code delta: null} 或整个消失；</li>
     *   <li>{@code content} 为纯空白（空格 / 换行 / 制表符）时<strong>不删</strong> ——
     *       对 Markdown 是有意义的内容，见 {@link #pruneEmptyValues}。</li>
     * </ul>
     *
     * <p>解析失败原样返回：与判定器「结构未知保守放行」同一取向 ——
     * 宁可把一个没见过的格式透传下去，也不要因为清洗失败把正常响应弄坏。
     *
     * @param objectMapper    JSON 解析器
     * @param chunkJson       原始 SSE data 的 JSON 字符串
     * @param contentEmitted  是否已经输出过正文 content（本方法会置位，供 fallback 判定）
     * @param reasoningBuffer 累积 reasoning_content 的缓冲区（本方法会追加）
     * @param chunkId         当前流的 chunk ID 引用（本方法会更新，供伪 chunk 复用同一 id）
     * @return 清洗后的 chunk JSON 字符串
     */
    @SuppressWarnings("unchecked")
    public static String normalize(ObjectMapper objectMapper, String chunkJson, AtomicBoolean contentEmitted,
                                   StringBuilder reasoningBuffer, AtomicReference<String> chunkId) {
        try {
            if ("[DONE]".equals(chunkJson)) {
                return chunkJson;
            }
            Map<String, Object> chunk = objectMapper.readValue(chunkJson, Map.class);

            // 记录 chunk ID，供 reasoning fallback 构建伪 chunk 时保持一致性。
            Object id = chunk.get("id");
            if (id instanceof String idStr && !idStr.isEmpty()) {
                chunkId.set(idStr);
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
            if (choices == null || choices.isEmpty()) {
                return chunkJson;
            }

            Map<String, Object> choice = choices.get(0);
            Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
            boolean preserveEmptyDelta = delta != null;
            boolean preserveNullFinishReason = false;

            // 统一 finish_reason：空字符串 → null
            Object finishReasonObj = choice.get("finish_reason");
            if (finishReasonObj instanceof String finishReason && finishReason.isBlank()) {
                choice.put("finish_reason", null);
                preserveNullFinishReason = true;
            } else if (choice.containsKey("finish_reason") && finishReasonObj == null) {
                preserveNullFinishReason = true;
            }

            if (delta != null) {
                normalizeDelta(delta, reasoningBuffer);

                // 标记是否已经输出过正文 content，用于判断是否需要 reasoning fallback。
                Object contentObj = delta.get("content");
                if (contentObj instanceof String content && !content.isEmpty()) {
                    contentEmitted.set(true);
                }

                // 如果 delta 规范化后为空，后续 prune 后再恢复为空对象，作为标准结束 chunk 形式
            }

            pruneEmptyValues(chunk);

            // 保留结构性字段：中间 chunk 的 finish_reason:null 不应被删除
            if (preserveNullFinishReason && !choice.containsKey("finish_reason")) {
                choice.put("finish_reason", null);
            }
            // 保留结构性字段：结束 chunk / 空 delta chunk 应保留 delta:{}
            if (preserveEmptyDelta && !choice.containsKey("delta")) {
                choice.put("delta", new LinkedHashMap<String, Object>());
            }

            return objectMapper.writeValueAsString(chunk);
        } catch (Exception exception) {
            return chunkJson;
        }
    }

    /**
     * 统一规范化 delta 字段。
     */
    private static void normalizeDelta(Map<String, Object> delta, StringBuilder reasoningBuffer) {
        // 统一 reasoning 字段名到 reasoning_content
        String reasoning = extractReasoning(delta);
        if (reasoning != null && !reasoning.isBlank()) {
            reasoningBuffer.append(reasoning);
            delta.put("reasoning_content", reasoning);
        }

        // 没有真实工具调用时，绝不保留 tool_calls（尤其不能保留 []）
        Object toolCallsObj = delta.get("tool_calls");
        if (!(toolCallsObj instanceof List<?> toolCalls) || !isMeaningfulToolCalls(toolCalls)) {
            delta.remove("tool_calls");
        }

        // 删除空字段（null / "" / []）
        pruneEmptyValues(delta);
    }

    /**
     * 从多个兼容字段中提取思考内容，并统一成 {@code reasoning_content}。
     *
     * <p><strong>副作用</strong>：无论提取是否成功，都会移除所有别名字段
     * （含值为空的），只留 {@code reasoning_content}，避免带着空串出站。
     * 调用方若要判断「是否产生了改动」，必须用
     * {@link #hasReasoningAliasKey(Map)} 先记录原状 —— 纯删除也算改动。
     *
     * <p>字段清单与空响应 gate 的判定共用
     * {@link OpenAiContentDetector#REASONING_KEYS} 一份：gate 工作在清洗之前，
     * 必须逐个检查兼容字段。新增兼容字段时改那一处即可，两侧同步生效。
     *
     * @param delta 载荷容器（流式是 {@code choices[].delta}，非流式是 {@code choices[].message}）
     * @return 提取到的思考内容；无则返回 null
     */
    public static String extractReasoning(Map<String, Object> delta) {
        String[] keys = OpenAiContentDetector.REASONING_KEYS;
        for (String key : keys) {
            Object value = delta.get(key);
            if (value instanceof String str && !str.isBlank()) {
                // 清理旧字段，只保留 reasoning_content
                for (String k : keys) {
                    if (!"reasoning_content".equals(k)) {
                        delta.remove(k);
                    }
                }
                return str;
            }
        }
        // 如果都为空，也要清理旧字段名，避免带着空串出去
        for (String k : keys) {
            if (!"reasoning_content".equals(k)) {
                delta.remove(k);
            }
        }
        return null;
    }

    /**
     * 载荷容器是否带 {@code reasoning_content} 之外的思考链别名字段（无论其值是否为空）。
     *
     * <p>只用于判断「{@link #extractReasoning(Map)} 是否会产生改动」：
     * 它会移除所有别名字段，包括值为空的那些，这类纯删除也需要把清洗结果写回。
     */
    public static boolean hasReasoningAliasKey(Map<String, Object> message) {
        for (String key : OpenAiContentDetector.REASONING_KEYS) {
            if (!"reasoning_content".equals(key) && message.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断 tool_calls 是否真的有意义（至少有一个非空元素）。
     */
    private static boolean isMeaningfulToolCalls(List<?> toolCalls) {
        if (toolCalls.isEmpty()) {
            return false;
        }
        for (Object item : toolCalls) {
            if (item instanceof Map<?, ?> map && !map.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 递归删除 Map/List 中的空值：null、空字符串（""）、空列表、空 Map。
     *
     * <p>注意：只删除真正的空字符串（{@code isEmpty}），不删除仅包含空白字符的字符串
     * （{@code isBlank}），因为空格（{@code " "}）、换行（{@code "\n"}）、
     * 制表符（{@code "\t"}）等在 content 中是有意义的内容，
     * 对 Markdown 格式（列表缩进、段落分隔、代码块）至关重要。
     *
     * <p>同一条口径在 {@link OpenAiContentDetector} 里以相反方向出现（判空时
     * 「仅空白算有内容」），两侧必须一致，否则会出现「gate 认它有内容、
     * 清洗却把它删掉」的组合 —— 那样 gate 放行的帧到了下游变成空帧。
     */
    @SuppressWarnings("unchecked")
    private static void pruneEmptyValues(Object node) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            List<String> keysToRemove = new ArrayList<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                Object value = entry.getValue();
                pruneEmptyValues(value);
                if (value == null
                        || (value instanceof String str && str.isEmpty())
                        || (value instanceof List<?> list && list.isEmpty())
                        || (value instanceof Map<?, ?> childMap && childMap.isEmpty())) {
                    keysToRemove.add(entry.getKey());
                }
            }
            for (String key : keysToRemove) {
                map.remove(key);
            }
        } else if (node instanceof List<?> rawList) {
            List<Object> list = (List<Object>) rawList;
            list.removeIf(item -> {
                pruneEmptyValues(item);
                return item == null
                        || (item instanceof String str && str.isEmpty())
                        || (item instanceof List<?> childList && childList.isEmpty())
                        || (item instanceof Map<?, ?> childMap && childMap.isEmpty());
            });
        }
    }

    /**
     * 判断是否为需要触发收尾逻辑的终止 chunk（{@code finish_reason} 为
     * {@code stop} 或 {@code tool_calls}）。
     *
     * <p>{@code [DONE]} 不算：它是协议的流结束标记，不承载 {@code choices}。
     */
    @SuppressWarnings("unchecked")
    public static boolean isTerminalChunk(ObjectMapper objectMapper, String chunkJson) {
        if ("[DONE]".equals(chunkJson)) {
            return false;
        }
        try {
            Map<String, Object> chunk = objectMapper.readValue(chunkJson, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
            if (choices == null || choices.isEmpty()) {
                return false;
            }
            Object finishReason = choices.get(0).get("finish_reason");
            return "stop".equals(finishReason) || "tool_calls".equals(finishReason);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * {@code finish_reason} 是否为 {@code stop}。
     *
     * <p>与 {@link #isTerminalChunk} 的区别：后者含 {@code tool_calls}，
     * 本方法只认 {@code stop}。reasoning fallback 只认后者，
     * 否则纯工具调用响应会被凭空插入一段思考内容作为正文。
     */
    public static boolean isStopFinishReason(ObjectMapper objectMapper, String chunkJson) {
        return hasFinishReason(objectMapper, chunkJson, "stop");
    }

    private static boolean hasFinishReason(ObjectMapper objectMapper, String chunkJson, String expected) {
        try {
            if ("[DONE]".equals(chunkJson)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> chunk = objectMapper.readValue(chunkJson, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
            if (choices == null || choices.isEmpty()) {
                return false;
            }
            Object finishReason = choices.get(0).get("finish_reason");
            return expected.equals(finishReason);
        } catch (Exception e) {
            return false;
        }
    }
}

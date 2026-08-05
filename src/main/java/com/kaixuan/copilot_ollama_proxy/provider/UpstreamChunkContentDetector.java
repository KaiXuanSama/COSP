package com.kaixuan.copilot_ollama_proxy.provider;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 判定一个上游原始 SSE 帧是否带<strong>实质载荷</strong>。
 *
 * <p>服务于空响应兜底：只要一轮里出现过任一实质帧，该轮就是正常响应；
 * 一轮到底都没有，则判定为空响应并触发重试。
 *
 * <h2>三类实质载荷</h2>
 * <ul>
 *   <li>正文 —— {@code choices[*].delta.content} 非空字符串；</li>
 *   <li>思考链 —— {@code choices[*].delta} 下 {@link #REASONING_KEYS} 任一字段非空白；</li>
 *   <li>工具调用 —— {@code choices[*].delta.tool_calls} 含至少一个非空元素。</li>
 * </ul>
 *
 * <h2>为何判原始帧而非清洗后的帧</h2>
 * 判定必须发生在 {@code retryWhen} <strong>内侧</strong>才能触发重试，而清洗链
 * （{@code normalizeUpstreamChunk}）挂在 {@code retryWhen} 外侧 —— 它依赖
 * {@code contentEmitted}、{@code reasoningBuffer} 这些<strong>跨往返累积</strong>的状态，
 * 移进重订阅范围内会引入一批新的状态重置问题。因此这里看到的是未清洗的原始帧，
 * 思考链的 5 个兼容字段都要判，不能只看 {@code reasoning_content}。
 *
 * <p>{@link #REASONING_KEYS} 与清洗链的 {@code extractReasoning} 共用同一份清单，
 * 避免将来新增兼容字段时只改一处、让判定与清洗口径漂移。
 *
 * <h2>不判 usage</h2>
 * 全 0 usage 只是空响应的<strong>伴随现象</strong>，不是独立判据：内容为空时它是冗余的
 * （gate 本就不会打开），内容非空时它是有害的 —— 不少中转站正常回复也不吐 usage 或吐全 0，
 * 把它当触发条件会误伤这些正常响应。
 *
 * <p>解析失败一律按「有内容」处理（{@code true}）：宁可放行一个可疑帧，
 * 也不要因为格式没见过就把正常响应判成空并重试。
 */
final class UpstreamChunkContentDetector {

    /**
     * 思考链的兼容字段名，与清洗链 {@code extractReasoning} 共用同一份清单。
     *
     * <p>上游各家命名不统一，清洗阶段会把这 5 种统一改写为 {@code reasoning_content}；
     * 但本判定器工作在清洗之前，因此必须逐个检查。
     */
    static final String[] REASONING_KEYS = {
            "reasoning_content", "reasoning_text", "reasoning", "thinking", "cot_summary"
    };

    private UpstreamChunkContentDetector() {
    }

    /**
     * 判断一个上游原始帧是否带实质载荷。
     *
     * @param objectMapper JSON 解析器
     * @param rawChunk     上游原始 SSE data 内容；{@code [DONE]} 等控制帧不算实质载荷
     * @return 含正文 / 思考链 / 工具调用之一返回 true；解析失败也返回 true（保守放行）
     */
    @SuppressWarnings("unchecked")
    static boolean hasMeaningfulPayload(ObjectMapper objectMapper, String rawChunk) {
        if (rawChunk == null || rawChunk.isBlank()) {
            return false;
        }
        // [DONE] 是流结束标记，不承载内容 —— 只有它的响应正是要兜底的空响应。
        if ("[DONE]".equals(rawChunk.trim())) {
            return false;
        }
        try {
            Map<String, Object> chunk = objectMapper.readValue(rawChunk, Map.class);
            Object choicesObj = chunk.get("choices");
            if (!(choicesObj instanceof List<?> choices)) {
                return false;
            }
            // 遍历所有 choice：n>1 时任一分支有内容即算有内容。
            for (Object choiceObj : choices) {
                if (choiceObj instanceof Map<?, ?> choice
                        && deltaHasPayload((Map<String, Object>) choice.get("delta"))) {
                    return true;
                }
            }
            return false;
        } catch (Exception exception) {
            // 结构未知：保守认为有内容，避免把没见过的正常格式判成空响应。
            return true;
        }
    }

    /** 判断单个 delta 是否带三类实质载荷之一。 */
    private static boolean deltaHasPayload(Map<String, Object> delta) {
        if (delta == null || delta.isEmpty()) {
            return false;
        }
        // 正文：空串不算（上游常用空 content 占位）；仅含空白的串算有内容 ——
        // 与清洗链 pruneEmptyValues 的口径一致，空格/换行对 Markdown 是有意义的。
        if (delta.get("content") instanceof String content && !content.isEmpty()) {
            return true;
        }
        // 思考链：5 个兼容字段任一非空白。
        for (String key : REASONING_KEYS) {
            if (delta.get(key) instanceof String reasoning && !reasoning.isBlank()) {
                return true;
            }
        }
        // 工具调用：需含至少一个非空元素，空数组 [] 不算。
        return delta.get("tool_calls") instanceof List<?> toolCalls && hasNonEmptyElement(toolCalls);
    }

    /** tool_calls 是否含至少一个非空 Map 元素。 */
    private static boolean hasNonEmptyElement(List<?> toolCalls) {
        for (Object item : toolCalls) {
            if (item instanceof Map<?, ?> map && !map.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}

package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 判定上游响应是否带<strong>实质载荷</strong>，流式与非流式各有一个入口。
 *
 * <p>服务于空响应兜底：只要出现过实质载荷就是正常响应；到底都没有则判定为空响应并触发重试。
 * 流式看 {@link #hasMeaningfulPayload}（逐帧判，一轮里任一帧有内容即算有），
 * 非流式看 {@link #hasMeaningfulNonStreamPayload}（一次拿到完整 body 直接判）。
 *
 * <h2>三类实质载荷</h2>
 * 载荷容器在流式是 {@code choices[*].delta}、非流式是 {@code choices[*].message}，
 * 但<strong>字段名完全相同</strong>，故两个入口共用同一份内层判定：
 * <ul>
 *   <li>正文 —— {@code content} 非空字符串；</li>
 *   <li>思考链 —— {@link #REASONING_KEYS} 任一字段非空白；</li>
 *   <li>工具调用 —— {@code tool_calls} 含至少一个非空元素。</li>
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
 *
 * <h2>与 Anthropic 侧对称</h2>
 * 本类只服务 <strong>OpenAI Chat Completions</strong> 协议（读 {@code choices[].delta}
 * 与 {@code choices[].message}），与 {@code provider.generic.anthropic.AnthropicContentDetector}
 * 是同一职责的两个协议实现：<strong>类别定义共享（正文 / 思考链 / 工具调用），
 * 取值路径各自独立</strong>。早期叫 {@code UpstreamChunkContentDetector} 并放在
 * {@code provider} 根下，那是「只有一种协议时」的产物，{@code Upstream} 一词隐含了
 * OpenAI；现已改名并与 Anthropic 侧同层。
 */
public final class OpenAiContentDetector {

    /**
     * 思考链的兼容字段名，与清洗链 {@code extractReasoning} 共用同一份清单。
     *
     * <p>上游各家命名不统一，清洗阶段会把这 5 种统一改写为 {@code reasoning_content}；
     * 但本判定器工作在清洗之前，因此必须逐个检查。
     */
    public static final String[] REASONING_KEYS = {
            "reasoning_content", "reasoning_text", "reasoning", "thinking", "cot_summary"
    };

    private OpenAiContentDetector() {
    }

    /**
     * 判断一个上游原始帧是否带实质载荷。
     *
     * @param objectMapper JSON 解析器
     * @param rawChunk     上游原始 SSE data 内容；{@code [DONE]} 等控制帧不算实质载荷
     * @return 含正文 / 思考链 / 工具调用之一返回 true；解析失败也返回 true（保守放行）
     */
    @SuppressWarnings("unchecked")
    public static boolean hasMeaningfulPayload(ObjectMapper objectMapper, String rawChunk) {
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
                        && payloadHasContent((Map<String, Object>) choice.get("delta"))) {
                    return true;
                }
            }
            return false;
        } catch (Exception exception) {
            // 结构未知：保守认为有内容，避免把没见过的正常格式判成空响应。
            return true;
        }
    }

    /**
     * 判断一个<strong>非流式</strong>完整响应体是否带实质载荷。
     *
     * <p>与流式版共用同一份内层判定（{@link #payloadHasContent}），因为
     * {@code choices[].message} 与 {@code choices[].delta} 的<strong>字段名完全相同</strong>
     * （{@code content} / {@link #REASONING_KEYS} / {@code tool_calls}），差别只在外层那个 key。
     * 共用而非各写一份，是为了让「两种传输模式判定口径一致」由同一份代码保证，
     * 而不是靠纪律维持 —— 将来新增兼容字段时天然不会漂移。
     *
     * <h2>为何非流式也要判</h2>
     * 「200 + 合法 JSON + 空 content」与流式「一轮下来没有任何实质 delta」是<strong>同一个
     * 上游行为</strong>在两种传输模式下的呈现（中转站抽风返回空回复）。若给非流式单独放宽，
     * 就会出现「切一下 stream 开关，同一个上游故障的结论就不同」的荒谬情形。
     *
     * <p>空 body（0 字节）同样判空：这是非流式独有的一档，比流式的「0 帧」更极端 ——
     * 连 JSON 骨架都没有。
     *
     * @param objectMapper JSON 解析器
     * @param fullBody     上游返回的完整响应体；null / 空白视为空响应
     * @return 含正文 / 思考链 / 工具调用之一返回 true；解析失败也返回 true（保守放行）
     */
    @SuppressWarnings("unchecked")
    public static boolean hasMeaningfulNonStreamPayload(ObjectMapper objectMapper, String fullBody) {
        // 空 body：非流式独有的极端形态，连 JSON 骨架都没有，判空。
        if (fullBody == null || fullBody.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> body = objectMapper.readValue(fullBody, Map.class);
            Object choicesObj = body.get("choices");
            if (!(choicesObj instanceof List<?> choices)) {
                return false;
            }
            // choices 为空数组：整个响应没有任何候选回复，判空。
            for (Object choiceObj : choices) {
                if (choiceObj instanceof Map<?, ?> choice
                        && payloadHasContent((Map<String, Object>) choice.get("message"))) {
                    return true;
                }
            }
            return false;
        } catch (Exception exception) {
            // 结构未知：保守认为有内容。宁可放行一个没见过的格式，
            // 也不要因为结构陌生就把正常响应判成空并重试。
            return true;
        }
    }

    /**
     * 判断单个载荷容器是否带三类实质载荷之一。
     *
     * <p>流式传 {@code choices[].delta}，非流式传 {@code choices[].message} ——
     * 两者字段名一致，故同一份判定通吃。
     */
    private static boolean payloadHasContent(Map<String, Object> delta) {
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

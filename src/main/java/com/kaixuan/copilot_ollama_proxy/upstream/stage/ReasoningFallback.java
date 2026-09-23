package com.kaixuan.copilot_ollama_proxy.upstream.stage;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 只有思考链、没有正文时的<strong>兜底回退</strong> —— 把累积的思考内容当作正文补发。
 *
 * <h2>它解决的问题</h2>
 * 早期部分模型会把思考链<strong>当作正文输出</strong>，即「只吐 reasoning_content、
 * 正文一个字符都没有」。下游看到的是<strong>空白回复</strong> —— 内容明明产生了，
 * 只是装在思考字段里。本类在流末尾发现这种情形时，用累积的思考内容构造一对
 * 伪 chunk 补发：一个承载正文，一个标记结束。
 *
 * <h2>为何保留（该缺陷已缓和）</h2>
 * 触发这种情形的模型如今几乎不再出现，剔除本类不会立刻造成故障。
 * <strong>保留是为了保险</strong>：那类模型一旦再次被接入（或某个中转站改写了响应形态），
 * 剔除会让下游重新看到空白回复，而排查方向会指向「模型没输出」——
 * 与真实成因（内容在思考字段里）相距很远。
 *
 * <h2>触发条件（四个同时成立）</h2>
 * <ol>
 *   <li>清洗后的 chunk 是<strong>终止 chunk</strong>（{@code finish_reason} 为
 *       {@code stop} 或 {@code tool_calls}）；</li>
 *   <li>{@code finish_reason} 恰为 <strong>{@code stop}</strong> ——
 *       它排除 {@code tool_calls}：纯工具调用响应<strong>绝不能</strong>被插入思考内容作为正文，
 *       否则下游会收到一段凭空多出来的回复；</li>
 *   <li>{@code contentEmitted} 为 false —— 整个流一个正文字符都没发出过；</li>
 *   <li>{@code reasoningBuffer} 非空 —— 确实有思考内容可以拿来回退。</li>
 * </ol>
 * 第 1、2 条在逻辑上前者包含后者（{@code stop} 必然也是终止 chunk），
 * <strong>两条都留</strong>是为了让本次从 {@code AbstractUpstreamChatService} 的搬移
 * 可被逐字核对：语义完全不变，而不是「我认为它冗余所以去掉」。
 *
 * <h2>为何专为 Chat 路径而做（不要「推广」到另两条线路）</h2>
 * 与 {@link UpstreamChunkNormalizer} 同一取向：本类读写的是 OpenAI 专属形态
 * （{@code choices[].delta.content} 与 {@code reasoning_content}），
 * 这是 Chat 特有的兼容措施。Anthropic 与 Responses 两条线路没有它是预期的协议差异，
 * 不是遗漏 —— 它在 Stage 3.1 成形、3.3d-2 完成查表接线（按
 * {@code ctx.upstreamProtocol()}），另两条<strong>未命中即跳过</strong>。
 *
 * <h2>流级状态由调用方持有</h2>
 * 三个状态参数都是<strong>跨帧累积</strong>的流级状态（重试一次就要重置），
 * 见方向文档 §2.4「两级状态要分清」。它们不属于本类，也不该被塞进请求级 context。
 */
public final class ReasoningFallback {

    private ReasoningFallback() {
    }

    /**
     * 判断是否应触发 reasoning fallback。
     *
     * @param objectMapper    JSON 解析器
     * @param normalizedChunk <strong>已清洗</strong>的 chunk（清洗统一了 {@code finish_reason}
     *                        与思考字段名，判定必须看清洗后的形态）
     * @param contentEmitted  整个流是否已发出过正文
     * @param reasoningBuffer 累积的思考内容
     * @return 四个触发条件同时成立时返回 true
     */
    public static boolean shouldFallback(ObjectMapper objectMapper, String normalizedChunk,
                                         AtomicBoolean contentEmitted, StringBuilder reasoningBuffer) {
        if (!UpstreamChunkNormalizer.isTerminalChunk(objectMapper, normalizedChunk)) {
            return false;
        }
        return UpstreamChunkNormalizer.isStopFinishReason(objectMapper, normalizedChunk)
                && !contentEmitted.get()
                && !reasoningBuffer.isEmpty();
    }

    /**
     * 构造回退用的两个伪 chunk：先正文、后结束标记。
     *
     * <p>顺序不可交换：下游按到达顺序累积正文，结束标记必须最后到，
     * 否则正文会被丢弃。
     *
     * @param objectMapper     JSON 序列化器
     * @param id               当前流的 chunk ID（取自清洗时记录的最近一个真实 id，
     *                         保持与真实 chunk 一致，避免下游按 id 分组时出现第二条流）
     * @param model            模型名
     * @param reasoningContent 累积的思考内容，作为正文补发
     * @return 两个元素的列表：[正文 chunk, 结束 chunk]
     */
    public static List<String> buildFallbackFrames(ObjectMapper objectMapper, String id, String model,
                                                   String reasoningContent) {
        return List.of(buildContentChunk(objectMapper, id, model, reasoningContent),
                buildFinishChunk(objectMapper, id, model));
    }

    /**
     * 构建 reasoning fallback 的正文 chunk。
     *
     * <p>当模型只输出了思考内容而没有正文时，用思考内容构造一个伪 content delta，
     * 使客户端看到的回复内容就是模型的思考过程。
     */
    private static String buildContentChunk(ObjectMapper objectMapper, String id, String model, String reasoningContent) {
        try {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);

            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("role", "assistant");
            delta.put("content", reasoningContent);

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", null);

            chunk.put("choices", List.of(choice));
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception exception) {
            return "{}";
        }
    }

    /**
     * 构建 reasoning fallback 的 finish chunk，紧跟在
     * {@link #buildContentChunk} 之后发出，标记流的结束。
     *
     * <p>{@code finish_reason} 固定为 {@code "stop"}：这是回退路径，
     * 不涉及工具调用。
     */
    private static String buildFinishChunk(ObjectMapper objectMapper, String id, String model) {
        try {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);

            Map<String, Object> delta = new LinkedHashMap<>();

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", "stop");

            chunk.put("choices", List.of(choice));
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception exception) {
            return "{}";
        }
    }
}

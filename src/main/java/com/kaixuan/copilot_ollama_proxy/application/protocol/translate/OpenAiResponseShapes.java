package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 响应报文的形态构造。
 *
 * <h2>为何单独一个类</h2>
 * 非流式与流式共用同一批「客户端会挑」的形态约定（首帧必须带 role、finish chunk 用
 * {@code "content":""} 而非空对象、usage chunk 用 {@code "choices":[]} 空数组）。
 * 这些约定不是逻辑，而是<strong>线格式事实</strong>——把它们集中在一处，
 * 改的时候不会漏掉某一侧。
 *
 * <p>形态依据见 {@code docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md} 第 10 节。
 *
 * <h2>为何返回 Map 而不是 DTO</h2>
 * 与项目既有取向一致：响应全程以字符串/Map 透传，不建响应 DTO。
 * 引入 DTO 等于加一道「上游字段必须符合预期结构」的约束，
 * 而对上游格式差异免疫是当前的优势。
 */
final class OpenAiResponseShapes {

    /** 非流式响应的 object 值。 */
    static final String OBJECT_COMPLETION = "chat.completion";
    /** 流式 chunk 的 object 值。 */
    static final String OBJECT_CHUNK = "chat.completion.chunk";

    /** SSE 终止哨兵。注意它不由 message_stop 触发，而由流结束触发。 */
    static final String DONE_SENTINEL = "[DONE]";

    private OpenAiResponseShapes() {
    }

    /**
     * 构造一个流式 chunk 的骨架。
     *
     * <p>{@code id} / {@code model} / {@code created} 回显在<strong>每个</strong> chunk 上。
     * 参考实现里只在首帧填这三项、靠编排层给后续帧回填，那是多余的往返——
     * 每帧都填更简单也更难出错。
     *
     * @param choice 已构造好的 choice 对象；usage chunk 传 null 表示 choices 为空数组
     */
    static Map<String, Object> chunk(String id, String model, long created, Map<String, Object> choice) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", OBJECT_CHUNK);
        chunk.put("created", created);
        chunk.put("model", model);
        // usage chunk 的 choices 是空数组而非省略 —— 客户端会挑这个形态。
        chunk.put("choices", choice == null ? List.of() : List.of(choice));
        return chunk;
    }

    /**
     * 构造带 delta 的 choice。
     *
     * @param finishReason 为 null 时显式写入 JSON null，而不是省略该键 ——
     *                     OpenAI 的线格式里这个键始终存在
     */
    static Map<String, Object> deltaChoice(Map<String, Object> delta, String finishReason) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        return choice;
    }

    /** 首帧的 delta：唯一带 role 的一帧。 */
    static Map<String, Object> roleDelta() {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("role", "assistant");
        // content 用空串而非省略：部分客户端按 content 存在与否判断这是不是内容帧。
        delta.put("content", "");
        return delta;
    }

    /** 正文增量。 */
    static Map<String, Object> contentDelta(String text) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("content", text);
        return delta;
    }

    /**
     * 思考增量。
     *
     * <p>字段名是 {@code reasoning_content}，与项目既有的上游归一化口径一致
     * （{@code AbstractUpstreamChatService} 把上游各种思考字段统一成这个名字）。
     * 不写 {@code reasoning} 别名——一个语义两个字段会让下游无从判断该读哪个。
     */
    static Map<String, Object> reasoningDelta(String text) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("reasoning_content", text);
        return delta;
    }

    /**
     * 工具调用的声明帧（带 id 与 name，不带 arguments 内容）。
     *
     * @param toolIndex OpenAI 侧的稠密序号，<strong>不是</strong> Anthropic 的 content block index
     */
    static Map<String, Object> toolCallStartDelta(int toolIndex, Object callId, Object name) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        // arguments 用空串占位：客户端通常按 index 累积字符串，缺这个键会让首次拼接失败。
        function.put("arguments", "");

        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("index", toolIndex);
        toolCall.put("id", callId);
        toolCall.put("type", "function");
        toolCall.put("function", function);

        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("tool_calls", List.of(toolCall));
        return delta;
    }

    /**
     * 工具参数的增量帧（只带 arguments 片段，不重复 id 与 name）。
     */
    static Map<String, Object> toolCallArgumentsDelta(int toolIndex, String partialJson) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("arguments", partialJson);

        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("index", toolIndex);
        toolCall.put("function", function);

        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("tool_calls", List.of(toolCall));
        return delta;
    }

    /**
     * 终止帧的 delta。
     *
     * <p>用 {@code "content":""} 而非空对象 {@code {}} —— 这是 OpenAI 的线格式，
     * 参考实现实测客户端会挑。
     */
    static Map<String, Object> finishDelta() {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("content", "");
        return delta;
    }
}

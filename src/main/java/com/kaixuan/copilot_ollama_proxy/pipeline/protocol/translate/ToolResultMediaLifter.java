package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C2M 去程：把工具结果中的非文本块移出 {@code tool_result}，放到同一 user 轮次的后部。
 *
 * <h2>为什么需要它</h2>
 * Anthropic 协议允许 {@code tool_result.content} 带图片，但部分上游拒绝这种形态
 * （"A tool_result may contain only text blocks"）。user 消息里的 image 块是所有上游都支持的
 * 最基础形态，因此移动后的请求对宽松上游同样合法。决策见 ADR-0019。
 *
 * <h2>输出形态（一个 user 轮次内）</h2>
 * <ol>
 *   <li>该轮全部 {@code tool_result}，顺序不变；含媒体的那几个 content 改为字符串：
 *       原文本按 {@code \n} 拼接，末尾追加 {@value #MEDIA_HINT}；</li>
 *   <li>按同一顺序，每个含媒体结果输出来源标记文本块 + 它的媒体块；</li>
 *   <li>该轮原有的其它块（普通正文等）。</li>
 * </ol>
 *
 * <p>提示措辞与 M2C 的 {@code [media moved to next user message]} 不同是刻意的：这里媒体仍在
 * <strong>同一条</strong> user 消息里。来源标记格式与 M2C 一致，id 用 JSON 转义。
 *
 * <p>必须在配对归一化<strong>之后</strong>运行：只有那时 tool_result 才已按调用顺序聚到各自的
 * user 轮次。纯文本结果与无媒体的消息原样返回，出站字节不变。
 */
final class ToolResultMediaLifter {

    static final String MEDIA_HINT = "[media moved after tool results]";

    private final ObjectMapper objectMapper;

    ToolResultMediaLifter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<Object> lift(List<Object> messages) {
        List<Object> output = new ArrayList<>(messages.size());
        for (Object item : messages) {
            output.add(item instanceof Map<?, ?> message && "user".equals(message.get("role"))
                    ? liftUser(message) : item);
        }
        return output;
    }

    private Object liftUser(Map<?, ?> message) {
        if (!(message.get("content") instanceof List<?> blocks)) {
            return message;
        }
        List<Object> results = new ArrayList<>();
        List<Object> media = new ArrayList<>();
        List<Object> others = new ArrayList<>();
        boolean changed = false;
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> raw) || !"tool_result".equals(raw.get("type"))) {
                others.add(block);
                continue;
            }
            Map<String, Object> lifted = liftResult(raw, media);
            changed |= lifted != raw;
            results.add(lifted);
        }
        if (!changed) {
            return message;
        }
        List<Object> content = new ArrayList<>(results.size() + media.size() + others.size());
        content.addAll(results);
        content.addAll(media);
        content.addAll(others);
        Map<String, Object> replacement = new LinkedHashMap<>();
        message.forEach((key, value) -> replacement.put(String.valueOf(key), value));
        replacement.put("content", content);
        return replacement;
    }

    /** 无媒体时返回原块（调用方据此判断是否改动）；有媒体时返回新块，媒体追加到 {@code mediaSink}。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> liftResult(Map<?, ?> result, List<Object> mediaSink) {
        if (!(result.get("content") instanceof List<?> parts)) {
            return (Map<String, Object>) result;
        }
        List<String> texts = new ArrayList<>();
        List<Object> media = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof Map<?, ?> raw && "text".equals(raw.get("type"))) {
                Object text = raw.get("text");
                texts.add(text == null ? "" : String.valueOf(text));
            } else {
                media.add(part);
            }
        }
        if (media.isEmpty()) {
            return (Map<String, Object>) result;
        }
        String text = String.join("\n", texts);
        Map<String, Object> replacement = new LinkedHashMap<>();
        result.forEach((key, value) -> replacement.put(String.valueOf(key), value));
        replacement.put("content", text.isEmpty() ? MEDIA_HINT : text + "\n" + MEDIA_HINT);

        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("type", "text");
        marker.put("text", "[media from tool_use_id=" + jsonString(result.get("tool_use_id")) + "]");
        mediaSink.add(marker);
        mediaSink.addAll(media);
        return replacement;
    }

    private String jsonString(Object id) {
        try {
            return objectMapper.writeValueAsString(id == null ? "" : String.valueOf(id));
        } catch (JsonProcessingException exception) {
            // String 序列化不会失败；保底仍给出带引号的值，不让标记格式漂移。
            return "\"" + id + "\"";
        }
    }
}

package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicContentBlockTranslator.ContentGroup;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicMessageTranslator.MessageRound;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicMessageTranslator.ToolResult;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicMessageTranslator.ToolUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M2C 的请求内工具配对与媒体延后输出。
 *
 * <p>先校验 ID 唯一性，再只匹配先前调用，最后按调用顺序输出连续结果。孤儿及未答调用
 * 清理不制造占位；真实空结果保留。媒体在本组工具结果之后带来源输出，普通正文相对顺序不变。
 * 不调用反方向的 merge→pair→merge，也不把 Chat 历史强制整理成角色交替。
 */
final class AnthropicToolResultExpander {

    private static final Logger log = LoggerFactory.getLogger(AnthropicToolResultExpander.class);
    private static final String ERROR_PREFIX = "[tool_result is_error=true]\n";
    private static final String MEDIA_HINT = "[media moved to next user message]";

    private final ObjectMapper objectMapper;
    private final AnthropicContentBlockTranslator contentTranslator;

    AnthropicToolResultExpander(ObjectMapper objectMapper, AnthropicContentBlockTranslator contentTranslator) {
        this.objectMapper = objectMapper;
        this.contentTranslator = contentTranslator;
    }

    List<Object> expand(List<MessageRound> rounds) {
        Map<String, ToolUse> calls = new LinkedHashMap<>();
        Map<String, ToolResult> allResults = new LinkedHashMap<>();
        for (MessageRound round : rounds) {
            for (ToolUse call : round.calls) {
                if (calls.putIfAbsent(call.id(), call) != null) {
                    throw new RequestTranslationException(call.path() + ".id", "工具调用 ID 重复");
                }
            }
            for (ToolResult result : round.results) {
                if (allResults.putIfAbsent(result.id(), result) != null) {
                    throw new RequestTranslationException(result.path() + ".tool_use_id", "工具结果 ID 重复");
                }
            }
        }
        Map<String, ToolResult> matched = new LinkedHashMap<>();
        for (ToolResult result : allResults.values()) {
            ToolUse call = calls.get(result.id());
            if (call != null && call.ordinal() < result.ordinal()) {
                matched.put(result.id(), result);
            } else {
                log.warn("[M2C] 孤儿工具结果已省略: path={}", result.path());
            }
        }

        List<Object> output = new ArrayList<>();
        for (MessageRound round : rounds) {
            if ("assistant".equals(round.role)) {
                appendAssistant(output, round, matched);
            } else if (round.hasOrdinaryContent) {
                output.add(message("user", AnthropicContentBlockTranslator.messageContent(round.content)));
            }
        }
        return output;
    }

    private void appendAssistant(List<Object> output, MessageRound round, Map<String, ToolResult> matched) {
        List<Object> retainedCalls = new ArrayList<>();
        List<Object> results = new ArrayList<>();
        List<Object> media = new ArrayList<>();
        for (ToolUse call : round.calls) {
            ToolResult result = matched.get(call.id());
            if (result == null) {
                log.warn("[M2C] 未应答工具调用已省略: path={}", call.path());
                continue;
            }
            retainedCalls.add(call.call());
            results.add(translateResult(result, media));
        }

        Object content = AnthropicContentBlockTranslator.messageContent(round.content);
        boolean hasText = content instanceof String text && !text.isEmpty();
        boolean keepEmptyOrdinary = round.calls.isEmpty() && round.hasOrdinaryContent;
        if (retainedCalls.isEmpty() && !hasText && !keepEmptyOrdinary) {
            return;
        }
        Map<String, Object> assistant = message("assistant", content);
        if (!retainedCalls.isEmpty()) {
            assistant.put("tool_calls", retainedCalls);
            if (!round.thinking.isEmpty()) {
                assistant.put("reasoning_content", String.join("\n", round.thinking));
            }
        }
        output.add(assistant);
        output.addAll(results);
        if (!media.isEmpty()) {
            output.add(message("user", media));
        }
    }

    private Map<String, Object> translateResult(ToolResult result, List<Object> mediaSink) {
        Map<?, ?> source = result.source();
        Object error = source.get("is_error");
        if (error != null && !(error instanceof Boolean)) {
            throw new RequestTranslationException(result.path() + ".is_error", "必须为布尔值");
        }
        List<ContentGroup> groups = contentTranslator.translate(source.get("content"), result.path() + ".content");
        List<Object> text = new ArrayList<>();
        List<Object> media = new ArrayList<>();
        for (ContentGroup group : groups) {
            // 一个含媒体文档整个迁移，标题/context/文档正文和媒体不拆散。
            (group.hasMedia() ? media : text).addAll(group.parts());
        }
        String content = AnthropicContentBlockTranslator.textContent(text);
        if (!media.isEmpty()) {
            content = content.isEmpty() ? MEDIA_HINT : content + "\n" + MEDIA_HINT;
            AnthropicContentBlockTranslator.addText(mediaSink, sourceMarker(result));
            mediaSink.addAll(media);
        }
        if (Boolean.TRUE.equals(error)) {
            content = ERROR_PREFIX + content;
        }
        Map<String, Object> tool = message("tool", content);
        tool.put("tool_call_id", result.id());
        return tool;
    }

    /** JSON 引号和转义固定 ID 边界，不把换行或括号当模板语法执行。 */
    private String sourceMarker(ToolResult result) {
        try {
            return "[media from tool_use_id=" + objectMapper.writeValueAsString(result.id()) + "]";
        } catch (JsonProcessingException exception) {
            throw new RequestTranslationException(result.path() + ".tool_use_id", "无法编码媒体归属");
        }
    }

    private static Map<String, Object> message(String role, Object content) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", role);
        result.put("content", content);
        return result;
    }
}
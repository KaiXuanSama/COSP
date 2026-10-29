package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicContentBlockTranslator.ContentGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicContentBlockTranslator.requireObject;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicContentBlockTranslator.requireString;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicContentBlockTranslator.textValue;

/**
 * Anthropic 消息解析为请求内语义轮，再交配对协作者构造 Chat 历史。
 *
 * <p>只合并相邻源消息的同角色轮，不强制最终 Chat 角色交替。保留源路径与全局出现顺序，
 * 使配对只能引用先前调用，错误仍指向原始索引。历史 thinking 在配对清理后再决定是否回放。
 * 角色按 ADR-0016 只检查字符串结构，枚举兼容性由上游判断；内容块的位置约束仍独立校验。
 */
final class AnthropicMessageTranslator {

    private static final Logger log = LoggerFactory.getLogger(AnthropicMessageTranslator.class);

    /** 每次 parse 新建；不是 Bean 字段，也不跨请求缓存。 */
    static final class MessageRound {
        final String role;
        final List<ContentGroup> content = new ArrayList<>();
        final List<String> thinking = new ArrayList<>();
        final List<ToolUse> calls = new ArrayList<>();
        final List<ToolResult> results = new ArrayList<>();
        boolean hasOrdinaryContent;

        MessageRound(String role) {
            this.role = role;
        }
    }

    record ToolUse(String id, String path, int ordinal, Map<String, Object> call) {
    }

    /** 内容在确认非孤儿之后才转换，避免孤儿媒体提前进入待发送队列。 */
    record ToolResult(String id, String path, int ordinal, Map<?, ?> source) {
    }

    private final ObjectMapper objectMapper;
    private final AnthropicContentBlockTranslator contentTranslator;
    private final AnthropicToolResultExpander resultExpander;

    AnthropicMessageTranslator(ObjectMapper objectMapper, AnthropicContentBlockTranslator contentTranslator) {
        this.objectMapper = objectMapper;
        this.contentTranslator = contentTranslator;
        this.resultExpander = new AnthropicToolResultExpander(objectMapper, contentTranslator);
    }

    List<Object> translate(Object rawMessages) {
        return resultExpander.expand(parse(rawMessages));
    }

    List<MessageRound> parse(Object rawMessages) {
        if (!(rawMessages instanceof List<?> messages)) {
            throw new RequestTranslationException("messages", "必须为消息数组");
        }
        List<MessageRound> rounds = new ArrayList<>();
        int ordinal = 0;
        for (int i = 0; i < messages.size(); i++) {
            String path = "messages[" + i + "]";
            Map<?, ?> message = requireObject(messages.get(i), path);
            // 不校验枚举、不 trim/改写；缺失、null 或非字符串仍报告源 role 路径。
            String role = textValue(message.get("role"), path + ".role");
            MessageRound round;
            if (!rounds.isEmpty() && role.equals(rounds.getLast().role)) {
                round = rounds.getLast();
            } else {
                round = new MessageRound(role);
                rounds.add(round);
            }
            Object content = message.get("content");
            if (content instanceof String text) {
                round.hasOrdinaryContent = true;
                round.content.addAll(contentTranslator.translate(text, path + ".content"));
                continue;
            }
            if (!(content instanceof List<?> blocks)) {
                throw new RequestTranslationException(path + ".content", "必须为字符串或内容块数组");
            }
            if (blocks.isEmpty()) {
                round.hasOrdinaryContent = true;
            }
            for (int j = 0; j < blocks.size(); j++) {
                String blockPath = path + ".content[" + j + "]";
                Map<?, ?> block = requireObject(blocks.get(j), blockPath);
                String type = requireString(block.get("type"), blockPath + ".type");
                int blockOrdinal = ordinal++;
                switch (type) {
                    case "tool_use" -> {
                        requireRole(round, "assistant", blockPath);
                        round.calls.add(parseToolUse(block, blockPath, blockOrdinal));
                    }
                    case "tool_result" -> {
                        requireRole(round, "user", blockPath);
                        String id = requireString(block.get("tool_use_id"), blockPath + ".tool_use_id");
                        round.results.add(new ToolResult(id, blockPath, blockOrdinal, block));
                    }
                    case "thinking" -> {
                        requireRole(round, "assistant", blockPath);
                        String text = textValue(block.get("thinking"), blockPath + ".thinking");
                        if (!text.isEmpty()) {
                            round.thinking.add(text);
                        }
                    }
                    case "redacted_thinking" -> {
                        requireRole(round, "assistant", blockPath);
                        log.warn("[M2C] 加密思考块已省略: path={}", blockPath);
                    }
                    case "text" -> {
                        round.hasOrdinaryContent = true;
                        round.content.add(contentTranslator.translateBlock(block, blockPath));
                    }
                    case "image", "document" -> {
                        requireRole(round, "user", blockPath);
                        round.hasOrdinaryContent = true;
                        round.content.add(contentTranslator.translateBlock(block, blockPath));
                    }
                    default -> throw new RequestTranslationException(blockPath + ".type",
                            "该消息块无法表达为 Chat 消息");
                }
            }
        }
        return rounds;
    }

    private ToolUse parseToolUse(Map<?, ?> block, String path, int ordinal) {
        String id = requireString(block.get("id"), path + ".id");
        String name = requireString(block.get("name"), path + ".name");
        Object input = block.get("input");
        if (input != null && !(input instanceof Map<?, ?>)) {
            throw new RequestTranslationException(path + ".input", "必须为对象，不能猜测工具参数");
        }
        String arguments;
        try {
            arguments = objectMapper.writeValueAsString(input == null ? Map.of() : input);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // 不把 Jackson 诊断（可能含参数片段）拼到面向用户的消息里。
            throw new RequestTranslationException(path + ".input", "无法序列化为 JSON 对象");
        }
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("arguments", arguments);
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", id);
        call.put("type", "function");
        call.put("function", function);
        return new ToolUse(id, path, ordinal, call);
    }

    private static void requireRole(MessageRound round, String role, String path) {
        if (!role.equals(round.role)) {
            throw new RequestTranslationException(path + ".type", "该块不能放在当前角色的消息中");
        }
    }
}
package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.TranslatedRequest;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.TranslationContext;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicContentBlockTranslator.copyJson;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicContentBlockTranslator.requireObject;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicContentBlockTranslator.requireString;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicContentBlockTranslator.textValue;

/**
 * M2C 去程：下游 Anthropic Messages 请求到上游 Chat 请求。
 *
 * <p>仅重建协议结构，先于设置层与请求体规则；不补生成参数、不猜模型能力、不做 I/O。
 * system 暂留顶层交 CHAT system 支线，显式 null 保留到设置层以守住 fallback 意见判据。
 * 使用源请求提取 TranslationContext 后再索取上游 usage，两种统计意图不能混为一谈。
 * 顶层 container / mcp_servers / context_management 按 ADR-0014 在白名单重建时直接省略，
 * 不校验其值、不模拟服务端能力，也不递归删除 schema/input 中的同名业务键。
 *
 * <p>声明键为 (MESSAGES, CHAT)，与现有响应方向相反的 MessagesToChatResponseTranslator
 * 不是一条链。C2M 回程缺失时仍为半轮开发态，不代表完整 Anthropic 客户端兼容。
 * 字段契约见 docs/reference/contracts/C2M-REQUEST-CONTRACT.md §10；取舍见 ADR-0012/0013/0014。
 */
@Component
public class MessagesToChatRequestTranslator implements RequestProtocolTranslator {

    private static final List<String> COPY_FIELDS = List.of("model", "stream", "max_tokens", "temperature", "top_p", "system");

    private final AnthropicMessageTranslator messageTranslator;
    private final AnthropicToolTranslator toolTranslator;

    public MessagesToChatRequestTranslator(ObjectMapper objectMapper) {
        this.messageTranslator = new AnthropicMessageTranslator(objectMapper, new AnthropicContentBlockTranslator());
        this.toolTranslator = new AnthropicToolTranslator();
    }

    @Override
    public WireProtocol downstreamProtocol() {
        return WireProtocol.MESSAGES;
    }

    @Override
    public WireProtocol upstreamProtocol() {
        return WireProtocol.CHAT;
    }

    @Override
    public TranslatedRequest translateRequest(Map<String, Object> downstreamBody) {
        Map<String, Object> source = downstreamBody == null ? Map.of() : downstreamBody;
        TranslationContext context = TranslationContext.fromDownstreamBody(source);
        Map<String, Object> target = new LinkedHashMap<>();
        for (String field : COPY_FIELDS) {
            if (source.containsKey(field)) {
                target.put(field, copyJson(source.get(field)));
            }
        }
        translateStop(source.get("stop_sequences"), target);
        translateThinking(source, target);
        translateFormat(source, target);
        target.put("messages", messageTranslator.translate(source.get("messages")));
        List<Object> tools = toolTranslator.translateTools(source.get("tools"));
        if (!tools.isEmpty()) {
            target.put("tools", tools);
        }
        AnthropicToolTranslator.ToolChoice choice = toolTranslator.translateChoice(source.get("tool_choice"), tools);
        if (choice.value() != null) {
            target.put("tool_choice", choice.value());
        }
        if (choice.parallelToolCalls() != null) {
            target.put("parallel_tool_calls", choice.parallelToolCalls());
        }
        if (context.wasStream()) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("include_usage", true);
            target.put("stream_options", options);
        }
        return new TranslatedRequest(target, context);
    }

    private static void translateStop(Object stop, Map<String, Object> target) {
        if (stop == null) {
            return;
        }
        if (!(stop instanceof List<?> values)) {
            throw new RequestTranslationException("stop_sequences", "必须为字符串数组");
        }
        List<Object> translated = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            translated.add(textValue(values.get(i), "stop_sequences[" + i + "]"));
        }
        if (!translated.isEmpty()) {
            target.put("stop", translated);
        }
    }

    private static void translateThinking(Map<String, Object> source, Map<String, Object> target) {
        if (source.containsKey("thinking")) {
            Object rawThinking = source.get("thinking");
            if (rawThinking == null) {
                target.put("thinking", null);
            } else {
                Map<?, ?> thinking = requireObject(rawThinking, "thinking");
                String type = requireString(thinking.get("type"), "thinking.type");
                String mapped = switch (type) {
                    case "disabled" -> "disabled";
                    case "enabled", "adaptive" -> "enabled";
                    default -> throw new RequestTranslationException("thinking.type", "无法表达该思考开关");
                };
                Map<String, Object> translated = new LinkedHashMap<>();
                translated.put("type", mapped);
                target.put("thinking", translated);
            }
        }
        if (source.containsKey("output_config")) {
            Object rawConfig = source.get("output_config");
            if (rawConfig == null) {
                target.put("reasoning_effort", null);
                return;
            }
            Map<?, ?> config = requireObject(rawConfig, "output_config");
            if (config.containsKey("effort")) {
                Object effort = config.get("effort");
                target.put("reasoning_effort", effort == null ? null : textValue(effort, "output_config.effort"));
            }
        }
    }

    private static void translateFormat(Map<String, Object> source, Map<String, Object> target) {
        Map<?, ?> config = source.get("output_config") instanceof Map<?, ?> map ? map : Map.of();
        if (source.containsKey("output_format") && config.containsKey("format")) {
            throw new RequestTranslationException("output_format", "与 output_config.format 同时出现，请仅使用一种格式字段");
        }
        boolean legacy = source.containsKey("output_format");
        Object rawFormat = legacy ? source.get("output_format") : config.get("format");
        if (rawFormat == null) {
            return;
        }
        String path = legacy ? "output_format" : "output_config.format";
        Map<?, ?> format = requireObject(rawFormat, path);
        if (!"json_schema".equals(format.get("type"))) {
            throw new RequestTranslationException(path + ".type", "首版只支持 json_schema 输出格式");
        }
        Map<?, ?> schema = requireObject(format.get("schema"), path + ".schema");
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", "cosp_output");
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", copyJson(schema));
        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_schema");
        responseFormat.put("json_schema", jsonSchema);
        target.put("response_format", responseFormat);
    }
}
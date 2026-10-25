package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicContentBlockTranslator.copyJson;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicContentBlockTranslator.optionalString;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicContentBlockTranslator.requireObject;
import static com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.AnthropicContentBlockTranslator.requireString;

/**
 * Anthropic 自定义工具到 Chat function 定义、选择与显式并行开关。
 *
 * <p>协议外壳按白名单重建，schema 深复制但不清洗约束。服务端工具与不可表达的调用方式
 * 仍拒绝；defer_loading=true 按 ADR-0017 省略并告警，不模拟工具的延迟发现。
 * 告警只记录路径，不记录示例或参数。字段契约见 docs/reference/contracts/M2C-REQUEST-CONTRACT.md §5.2。
 */
final class AnthropicToolTranslator {

    private static final Logger log = LoggerFactory.getLogger(AnthropicToolTranslator.class);

    record ToolChoice(Object value, Boolean parallelToolCalls) {
    }

    List<Object> translateTools(Object rawTools) {
        List<Object> result = new ArrayList<>();
        if (rawTools == null) {
            return result;
        }
        if (!(rawTools instanceof List<?> tools)) {
            throw new RequestTranslationException("tools", "必须为工具数组");
        }
        for (int i = 0; i < tools.size(); i++) {
            String path = "tools[" + i + "]";
            Map<?, ?> tool = requireObject(tools.get(i), path);
            Object type = tool.get("type");
            if (type != null && !"custom".equals(type)) {
                throw new RequestTranslationException(path + ".type", "无法把服务端工具表达为 Chat 客户端函数");
            }
            String name = requireString(tool.get("name"), path + ".name");
            validateExtensions(tool, path);
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", name);
            String description = optionalString(tool, "description", path);
            if (description != null) {
                function.put("description", description);
            }
            function.put("parameters", normalizeSchema(tool.get("input_schema"), path + ".input_schema"));
            Boolean strict = optionalBoolean(tool, "strict", path);
            if (strict != null) {
                function.put("strict", strict);
            }
            Map<String, Object> translated = new LinkedHashMap<>();
            translated.put("type", "function");
            translated.put("function", function);
            result.add(translated);
        }
        return result;
    }

    ToolChoice translateChoice(Object rawChoice, List<Object> tools) {
        if (rawChoice == null) {
            return new ToolChoice(null, null);
        }
        Map<?, ?> choice = requireObject(rawChoice, "tool_choice");
        String type = requireString(choice.get("type"), "tool_choice.type");
        Boolean disableParallel = optionalBoolean(choice, "disable_parallel_tool_use", "tool_choice");
        Object mapped;
        switch (type) {
            case "auto", "none" -> mapped = type;
            case "any" -> {
                if (tools.isEmpty()) {
                    throw new RequestTranslationException("tool_choice.type", "要求调用工具但没有声明工具");
                }
                mapped = "required";
            }
            case "tool" -> {
                String name = requireString(choice.get("name"), "tool_choice.name");
                if (!declaredNames(tools).contains(name)) {
                    throw new RequestTranslationException("tool_choice.name", "选择的工具未声明");
                }
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", name);
                Map<String, Object> named = new LinkedHashMap<>();
                named.put("type", "function");
                named.put("function", function);
                mapped = named;
            }
            default -> throw new RequestTranslationException("tool_choice.type", "无法表达该工具选择");
        }
        if (tools.isEmpty()) {
            return new ToolChoice(null, null);
        }
        return new ToolChoice(mapped, disableParallel == null ? null : !disableParallel);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeSchema(Object rawSchema, String path) {
        Map<String, Object> schema = rawSchema == null ? new LinkedHashMap<>()
                : (Map<String, Object>) copyJson(requireObject(rawSchema, path));
        if (!schema.containsKey("type")) {
            schema.put("type", "object");
        }
        if ("object".equals(schema.get("type")) && !schema.containsKey("properties")) {
            schema.put("properties", new LinkedHashMap<String, Object>());
        }
        return schema;
    }

    private static void validateExtensions(Map<?, ?> tool, String path) {
        Boolean deferred = optionalBoolean(tool, "defer_loading", path);
        if (Boolean.TRUE.equals(deferred)) {
            warnOmitted(path + ".defer_loading");
        }
        Object callers = tool.get("allowed_callers");
        if (callers != null) {
            if (!(callers instanceof List<?> values)) {
                throw new RequestTranslationException(path + ".allowed_callers", "必须为数组");
            }
            if (values.isEmpty()) {
                warnOmitted(path + ".allowed_callers");
            }
            for (int i = 0; i < values.size(); i++) {
                String callerPath = path + ".allowed_callers[" + i + "]";
                String caller = requireString(values.get(i), callerPath);
                if (!"direct".equals(caller)) {
                    throw new RequestTranslationException(callerPath, "无法等价表达该工具调用方式");
                }
            }
        }
        Object examples = tool.get("input_examples");
        if (examples != null && !(examples instanceof List<?> list && list.isEmpty())) {
            warnOmitted(path + ".input_examples");
        }
        if (optionalBoolean(tool, "eager_input_streaming", path) != null) {
            warnOmitted(path + ".eager_input_streaming");
        }
    }

    private static Boolean optionalBoolean(Map<?, ?> source, String key, String path) {
        Object value = source.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new RequestTranslationException(path + "." + key, "必须为布尔值");
    }

    private static Set<String> declaredNames(List<Object> tools) {
        Set<String> names = new LinkedHashSet<>();
        for (Object item : tools) {
            if (item instanceof Map<?, ?> tool && tool.get("function") instanceof Map<?, ?> function
                    && function.get("name") instanceof String name) {
                names.add(name);
            }
        }
        return names;
    }

    private static void warnOmitted(String path) {
        log.warn("[M2C] 工具提示字段已省略: path={}", path);
    }
}
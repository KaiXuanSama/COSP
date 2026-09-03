package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI {@code tools} / {@code tool_choice} → Anthropic 对应形态。
 *
 * <h2>两侧的差异只在外壳</h2>
 * 参数体都是 JSON Schema，区别是 OpenAI 把它包在
 * {@code function.parameters} 里、Anthropic 直接叫 {@code input_schema}。
 * 所以这里主要做拆包与补默认值，不改 schema 内容。
 *
 * <p>契约第 3.4 节。
 */
final class ToolTranslator {

    /**
     * 翻译工具定义。
     *
     * @return 翻译后的工具列表；输入非数组或全部被丢弃时返回空列表
     */
    List<Object> translateTools(Object rawTools) {
        List<Object> result = new ArrayList<>();
        if (!(rawTools instanceof List<?> tools)) {
            return result;
        }
        for (Object item : tools) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> tool = translateTool(asStringKeyMap(raw));
            if (tool != null) {
                result.add(tool);
            }
        }
        return result;
    }

    /**
     * 单个工具定义。
     *
     * <p>非 {@code function} 类型（hosted tool，如 web_search）丢弃：
     * 它们在 Anthropic 侧是完全不同的声明形态，硬映射得不到等价语义。
     */
    private Map<String, Object> translateTool(Map<String, Object> tool) {
        Object type = tool.get("type");
        if (type != null && !"function".equals(type)) {
            return null;
        }
        if (!(tool.get("function") instanceof Map<?, ?> rawFunction)) {
            return null;
        }
        Map<String, Object> function = asStringKeyMap(rawFunction);
        if (!(function.get("name") instanceof String name) || name.isBlank()) {
            // 无名工具无法被引用，也无法被 tool_choice 指向。
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        if (function.get("description") instanceof String description) {
            result.put("description", description);
        }
        result.put("input_schema", normalizeSchema(function.get("parameters")));
        return result;
    }

    /**
     * 归一化 {@code input_schema}。
     *
     * <p>Anthropic 要求 {@code input_schema} 是对象且带 {@code type}。
     * 无参工具在 OpenAI 侧常常干脆不给 {@code parameters}，
     * 补一个空 object schema 是无损的等价表达。
     *
     * <p>只在缺失时补，不改写用户给的 schema 内容——那是工具契约的一部分，
     * 代理无权改动。
     */
    private Map<String, Object> normalizeSchema(Object parameters) {
        if (!(parameters instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return emptyObjectSchema();
        }
        Map<String, Object> schema = new LinkedHashMap<>(asStringKeyMap(raw));
        schema.putIfAbsent("type", "object");
        if ("object".equals(schema.get("type"))) {
            schema.putIfAbsent("properties", new LinkedHashMap<String, Object>());
        }
        return schema;
    }

    private Map<String, Object> emptyObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        return schema;
    }

    /**
     * 翻译 {@code tool_choice}。
     *
     * <p>指向未声明工具时返回 null（整个字段丢弃）：保留会被上游拒，
     * 而这属于下游请求本身的不一致，代理没有能力也没有立场去修正它。
     *
     * @param rawToolChoice 下游的 tool_choice 值
     * @param translatedTools 已翻译的工具列表，用于校验具名选择
     * @return 翻译结果；无法翻译时返回 null
     */
    Object translateToolChoice(Object rawToolChoice, List<Object> translatedTools) {
        if (rawToolChoice == null) {
            return null;
        }
        if (rawToolChoice instanceof String choice) {
            return switch (choice) {
                case "auto" -> Map.of("type", "auto");
                case "required" -> Map.of("type", "any");
                case "none" -> Map.of("type", "none");
                default -> null;
            };
        }
        if (!(rawToolChoice instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> choice = asStringKeyMap(raw);
        // OpenAI 的具名形态：{"type":"function","function":{"name":"x"}}
        String name = null;
        if (choice.get("function") instanceof Map<?, ?> functionRaw
                && asStringKeyMap(functionRaw).get("name") instanceof String value) {
            name = value;
        } else if (choice.get("name") instanceof String value) {
            // 有些客户端把 name 放在顶层。
            name = value;
        }
        if (name == null || name.isBlank() || !declaredNames(translatedTools).contains(name)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "tool");
        result.put("name", name);
        return result;
    }

    private Set<String> declaredNames(List<Object> translatedTools) {
        Set<String> names = new LinkedHashSet<>();
        if (translatedTools == null) {
            return names;
        }
        for (Object item : translatedTools) {
            if (item instanceof Map<?, ?> raw && raw.get("name") instanceof String name) {
                names.add(name);
            }
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}

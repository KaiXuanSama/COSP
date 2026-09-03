package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.RequestTranslationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 消息数组 → Anthropic Messages 消息数组。
 *
 * <p>本类只做消息结构，不碰顶层字段。拆出来是因为消息是 O2A 里唯一有
 * <strong>结构性不变式</strong>的部分：顶层字段各自独立，逐个搬运即可，
 * 而消息之间有邻接与交替约束（见 {@link ToolPairingNormalizer}）。
 *
 * <h2>不处理 system</h2>
 * system 消息由 {@code GenericAnthropicChatService.extractSystemPrompt} 提到顶层，
 * 那一步在翻译之后。这里把 system 消息<strong>原样留在数组里</strong>交给它，
 * 不重复实现一遍——两份实现迟早会分叉。
 *
 * <p>{@code developer} 角色改写成 {@code system}，因为下游那一步只认 system。
 * 这是本类唯一对 role 的改写，且它不改变语义（两者在 OpenAI 侧同义）。
 */
final class MessageTranslator {

    /** 允许出现在出站消息里的 role。 */
    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_DEVELOPER = "developer";
    private static final String ROLE_TOOL = "tool";
    /** OpenAI 早期的函数调用角色，语义等同 tool。 */
    private static final String ROLE_FUNCTION = "function";

    private final ObjectMapper objectMapper;
    private final ContentBlockTranslator contentBlockTranslator;

    MessageTranslator(ObjectMapper objectMapper, ContentBlockTranslator contentBlockTranslator) {
        this.objectMapper = objectMapper;
        this.contentBlockTranslator = contentBlockTranslator;
    }

    /**
     * 翻译整个消息数组。
     *
     * @param rawMessages 下游的 {@code messages} 值，任意类型（非 List 时原样返回）
     * @return 翻译后的消息列表
     * @throws RequestTranslationException 遇到未知 role 或非法 tool_calls 参数
     */
    List<Object> translate(Object rawMessages) {
        if (!(rawMessages instanceof List<?> messages)) {
            // 不是数组就不是我们能处理的形态。交给上游报错而非在这里猜 ——
            // 契约只规定了合法请求的映射，畸形请求的错误信息由上游给更准确。
            return null;
        }
        List<Object> translated = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Object item = messages.get(i);
            if (!(item instanceof Map<?, ?> raw)) {
                // 同上：非对象元素交给上游报错。
                translated.add(item);
                continue;
            }
            translateOne(asStringKeyMap(raw), "messages[" + i + "]", translated);
        }
        return translated;
    }

    /**
     * 翻译单条消息，结果追加到 {@code sink}。
     *
     * <p>用 sink 而非返回值：一条 OpenAI 消息可能产出<strong>零条或多条</strong>
     * Anthropic 消息——空内容整条丢弃产出零条，而 assistant 带 tool_calls 时
     * 正文与 tool_use 块要合并成一条。返回 {@code Object} 就得为「零条」引入
     * null 判断、为「多条」引入 List 判断，调用点反而更啰嗦。
     */
    private void translateOne(Map<String, Object> message, String path, List<Object> sink) {
        String role = message.get("role") instanceof String value ? value : null;
        if (role == null || role.isBlank()) {
            throw new RequestTranslationException(path + ".role", "缺失或为空");
        }

        switch (role) {
            case ROLE_SYSTEM, ROLE_DEVELOPER -> sink.add(translateSystem(message));
            case ROLE_USER -> addIfNotEmpty(sink, translateUser(message, path));
            case ROLE_ASSISTANT -> addIfNotEmpty(sink, translateAssistant(message, path));
            case ROLE_TOOL, ROLE_FUNCTION -> addIfNotEmpty(sink, translateToolResult(message, path));
            default -> throw new RequestTranslationException(path + ".role",
                    "是未知角色 " + role + "，无法翻译到 Anthropic 协议");
        }
    }

    /**
     * system / developer 消息。
     *
     * <p>只把 role 归一到 {@code system} 并保留文本，交给下游的 system 提取。
     * content 用 {@code stringify} 压平：Anthropic 的顶层 system 接受字符串，
     * 而下游那一步本来就只认字符串。
     */
    private Map<String, Object> translateSystem(Map<String, Object> message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", ROLE_SYSTEM);
        result.put("content", contentBlockTranslator.stringify(message.get("content")));
        return result;
    }

    /**
     * user 消息。
     *
     * <p>字符串 content 保持字符串形态，不升格成块数组——部分上游对块数组更严格，
     * 而这里没有任何升格的必要。
     */
    private Map<String, Object> translateUser(Map<String, Object> message, String path) {
        Object content = message.get("content");
        if (content instanceof String text) {
            if (text.isBlank()) {
                return null;
            }
            return messageOf(ROLE_USER, text);
        }
        List<Object> blocks = contentBlockTranslator.translateBlocks(content, path + ".content");
        if (blocks.isEmpty()) {
            return null;
        }
        return messageOf(ROLE_USER, blocks);
    }

    /**
     * assistant 消息，正文与 {@code tool_calls} 合并成一条。
     *
     * <p>必须合并而非拆成两条：Anthropic 要求 {@code tool_use} 块与同轮正文在
     * 同一条 assistant 消息里，拆开会破坏角色交替。
     *
     * <p>思考内容不重建成 {@code thinking} 块——{@code signature} 由 Anthropic
     * 自己签发，跨协议造不出来，塞一个无签名的块上游直接 400。契约第 4.7 节。
     */
    private Map<String, Object> translateAssistant(Map<String, Object> message, String path) {
        List<Object> blocks = new ArrayList<>();

        Object content = message.get("content");
        if (content instanceof String text) {
            if (!text.isBlank()) {
                blocks.add(textBlock(text));
            }
        } else if (content != null) {
            blocks.addAll(contentBlockTranslator.translateBlocks(content, path + ".content"));
        }

        if (message.get("tool_calls") instanceof List<?> toolCalls) {
            for (int i = 0; i < toolCalls.size(); i++) {
                if (!(toolCalls.get(i) instanceof Map<?, ?> raw)) {
                    continue;
                }
                blocks.add(translateToolCall(asStringKeyMap(raw), path + ".tool_calls[" + i + "]"));
            }
        }

        if (blocks.isEmpty()) {
            return null;
        }
        return messageOf(ROLE_ASSISTANT, blocks);
    }

    /**
     * 单个 {@code tool_calls} 元素 → {@code tool_use} 块。
     *
     * <p>{@code arguments} 是 JSON <strong>字符串</strong>，而 Anthropic 的
     * {@code input} 是<strong>对象</strong>，必须解析。解析失败抛异常而非静默留空：
     * 空参数会让工具收到一个语义完全不同的调用，比 400 更难排查。
     *
     * <p>ID 原样搬运，不做前缀拼接——参考项目里给任何未知 id 拼 {@code toolu_}
     * 的做法可能撞长度限制或产出不合法 ID。
     */
    private Map<String, Object> translateToolCall(Map<String, Object> toolCall, String path) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_use");
        block.put("id", toolCall.get("id"));

        Object rawFunction = toolCall.get("function");
        if (!(rawFunction instanceof Map<?, ?> functionMap)) {
            throw new RequestTranslationException(path + ".function", "缺失，无法构造 tool_use 块");
        }
        Map<String, Object> function = asStringKeyMap(functionMap);
        block.put("name", function.get("name"));
        block.put("input", parseArguments(function.get("arguments"), path + ".function.arguments"));
        return block;
    }

    /**
     * 解析 {@code arguments}。
     *
     * <p>空串与缺失都视为「无参数」，映射成空对象——那是合法且常见的形态
     * （无参工具调用），不该报错。只有<strong>非空但解析不出对象</strong>才是错误。
     */
    private Map<String, Object> parseArguments(Object arguments, String path) {
        if (arguments == null) {
            return new LinkedHashMap<>();
        }
        if (arguments instanceof Map<?, ?> alreadyParsed) {
            // 有些客户端直接发对象。既然已经是目标形态，不必绕一圈序列化再解析。
            return asStringKeyMap(alreadyParsed);
        }
        if (!(arguments instanceof String text)) {
            throw new RequestTranslationException(path,
                    "既不是 JSON 字符串也不是对象，无法翻译成 tool_use.input");
        }
        if (text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(text, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (JsonProcessingException e) {
            throw new RequestTranslationException(path,
                    "不是合法的 JSON 对象，无法翻译成 tool_use.input: " + e.getOriginalMessage());
        }
    }

    /**
     * {@code role: tool} → 带 {@code tool_result} 块的 user 消息。
     *
     * <p>这里只做形态转换、<strong>不负责摆位置</strong>。位置由
     * {@link ToolPairingNormalizer} 统一修复——那需要看到全部消息才能判断
     * 谁该紧邻谁，逐条翻译时信息不足。
     *
     * <p>空内容整条丢弃，不塞占位字符串：参考项目用 {@code "..."} 或
     * {@code "(empty)"} 填空，那会作为真实内容进入模型上下文与计费。
     */
    private Map<String, Object> translateToolResult(Map<String, Object> message, String path) {
        Object toolCallId = message.get("tool_call_id");
        if (toolCallId == null) {
            // 没有 id 就无从配对。Anthropic 的 tool_result 必须带 tool_use_id。
            throw new RequestTranslationException(path + ".tool_call_id",
                    "缺失，无法构造 tool_result 块");
        }
        String text = contentBlockTranslator.stringify(message.get("content"));
        if (text == null || text.isBlank()) {
            return null;
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolCallId);
        block.put("content", text);
        return messageOf(ROLE_USER, List.of(block));
    }

    private static void addIfNotEmpty(List<Object> sink, Map<String, Object> message) {
        if (message != null) {
            sink.add(message);
        }
    }

    private static Map<String, Object> messageOf(String role, Object content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}

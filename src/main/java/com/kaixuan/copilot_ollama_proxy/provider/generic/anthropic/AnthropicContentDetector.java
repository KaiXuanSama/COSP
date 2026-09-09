package com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 判定 Anthropic 响应是否带<strong>实质载荷</strong>。
 *
 * <h2>与 OpenAI 侧共享的是「类别定义」，不是取值路径</h2>
 * 三类实质载荷的划分与 {@code OpenAiContentDetector} 完全一致 ——
 * 正文、思考链、工具调用。这个划分是<strong>策略</strong>，两侧必须同口径，
 * 否则会重演「切一下协议，同一个上游故障的结论就不同」。
 *
 * <p>但取值路径完全不同，故独立实现：Anthropic 的载荷在 {@code content[]} 数组里，
 * 每个元素靠 {@code type} 区分（{@code text} / {@code thinking} / {@code tool_use}），
 * 而非 OpenAI 的「同一个对象里按字段名找」。这正是「隔离实现」适用的部分。
 *
 * <h2>流式的判定单位是一整轮，不是单个事件</h2>
 * Anthropic 的事件流里 {@code message_start}、{@code content_block_start}
 * 本身不含内容（前者只有元信息、后者只声明 block 类型），
 * 内容分散在后续的 {@code content_block_delta} 里。因此不能逐事件判「有没有内容」——
 * 那会把正常响应的头两个事件判成空。流式入口 {@link #eventHasPayload} 的语义是
 * 「这个事件<em>贡献了</em>实质载荷吗」，由调用方在一轮内做逻辑或。
 *
 * <h2>解析失败一律保守放行</h2>
 * 与 OpenAI 侧同一取向：宁可放行一个没见过的格式，也不要因为结构陌生就把正常响应
 * 判成空并重试。
 *
 * <h2>与 OpenAI 侧对称</h2>
 * 本类与 {@code provider.generic.openai.OpenAiContentDetector} 是<strong>同一职责的两个协议实现</strong>，
 * 位于同一层级、命名风格一致：类别定义共享，取值路径各自独立。
 */
public final class AnthropicContentDetector {

    /** 思考链 block 的 type 值。Anthropic 规范只有这两种，不存在 OpenAI 那样的别名混乱。 */
    private static final String TYPE_THINKING = "thinking";
    private static final String TYPE_REDACTED_THINKING = "redacted_thinking";

    private AnthropicContentDetector() {
    }

    /**
     * 判断<strong>非流式</strong>完整响应体是否带实质载荷。
     *
     * @param fullBody 上游完整响应体；null / 空白视为空响应
     * @return 含正文 / 思考链 / 工具调用之一返回 true；解析失败也返回 true（保守放行）
     */
    public static boolean hasMeaningfulPayload(ObjectMapper objectMapper, String fullBody) {
        if (fullBody == null || fullBody.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(fullBody);
            JsonNode content = root.get("content");
            if (content == null || !content.isArray()) {
                // content 缺失或不是数组：整个响应没有可用的内容容器，判空。
                return false;
            }
            for (JsonNode block : content) {
                if (blockHasPayload(block)) {
                    return true;
                }
            }
            return false;
        } catch (Exception exception) {
            // 结构未知：保守认为有内容。
            return true;
        }
    }

    /**
     * 判断<strong>单个流式事件</strong>是否贡献了实质载荷。
     *
     * <p>返回 false 不代表这一轮是空响应 —— {@code message_start} 等控制事件本就不含内容。
     * 调用方需在一轮内对所有事件做逻辑或，整轮都为 false 才判空。
     *
     * @param eventData SSE data 字段的原始内容
     * @return 该事件带来了正文 / 思考链 / 工具调用之一返回 true；解析失败返回 true（保守放行）
     */
    public static boolean eventHasPayload(ObjectMapper objectMapper, String eventData) {
        if (eventData == null || eventData.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(eventData);
            String type = text(root, "type");
            if (type == null) {
                return false;
            }
            return switch (type) {
                // content_block_start 声明 block 类型，其 content_block 可能已带初始内容
                // （如 tool_use 的 name 已确定）。工具调用在此刻即算实质载荷 ——
                // 与 OpenAI 侧「tool_calls 含非空元素即算」同口径。
                case "content_block_start" -> blockHasPayload(root.get("content_block"));
                // content_block_delta 是内容的主要载体。
                case "content_block_delta" -> deltaHasPayload(root.get("delta"));
                // 其余事件（message_start / content_block_stop / message_delta /
                // message_stop / ping）均为控制事件，不贡献内容。
                default -> false;
            };
        } catch (Exception exception) {
            return true;
        }
    }

    /**
     * 判断一个 content block 是否带实质载荷。
     *
     * <p>非流式的 {@code content[]} 元素与流式 {@code content_block_start} 的
     * {@code content_block} 是同一种结构，故共用此方法。
     */
    private static boolean blockHasPayload(JsonNode block) {
        if (block == null || !block.isObject()) {
            return false;
        }
        String type = text(block, "type");
        if (type == null) {
            return false;
        }
        return switch (type) {
            // 正文：空串不算（与 OpenAI 侧 content 的口径一致）；
            // 仅含空白的串算有内容 —— 空格/换行对 Markdown 是有意义的。
            case "text" -> {
                String value = text(block, "text");
                yield value != null && !value.isEmpty();
            }
            // 思考链：thinking 与 redacted_thinking 都算。后者内容被服务端加密，
            // 但它的存在本身就证明模型思考过，不能因为读不到明文就判空。
            case TYPE_THINKING -> {
                String value = text(block, TYPE_THINKING);
                yield value != null && !value.isBlank();
            }
            case TYPE_REDACTED_THINKING -> true;
            // 工具调用：block 存在即算 —— 与 OpenAI 侧「含至少一个非空元素」同口径。
            case "tool_use" -> true;
            default -> false;
        };
    }

    /**
     * 判断一个 {@code content_block_delta} 的 delta 是否带实质载荷。
     *
     * <p>delta 的 type 与 block 的 type 不同名（{@code text_delta} 而非 {@code text}），
     * 这是 Anthropic 协议的刻意区分，不能与 {@link #blockHasPayload} 合并处理。
     */
    private static boolean deltaHasPayload(JsonNode delta) {
        if (delta == null || !delta.isObject()) {
            return false;
        }
        String type = text(delta, "type");
        if (type == null) {
            return false;
        }
        return switch (type) {
            case "text_delta" -> {
                String value = text(delta, "text");
                yield value != null && !value.isEmpty();
            }
            case "thinking_delta" -> {
                String value = text(delta, TYPE_THINKING);
                yield value != null && !value.isBlank();
            }
            // 工具调用参数的增量。哪怕是空片段也算 —— 它属于一个已经开始的 tool_use，
            // 而那个 block 在 content_block_start 时已被判为实质载荷。
            case "input_json_delta" -> true;
            // signature_delta 是思考链的完整性签名，不是内容本身，不单独算载荷。
            default -> false;
        };
    }

    /** 读取字符串字段；缺失或非字符串返回 null。 */
    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}

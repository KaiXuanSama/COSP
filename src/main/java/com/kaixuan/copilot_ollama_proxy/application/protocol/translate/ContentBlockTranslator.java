package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI 内容块 → Anthropic 内容块。
 *
 * <h2>白名单转换，认不出的块丢弃</h2>
 * 这是本类最重要的一条约束。默认透传未知分片是这里最容易踩的坑：
 * Responses 协议或某个客户端的专有分片原样发给 Anthropic，上游只会回 400
 * 把整轮打挂。丢弃虽然损失信息，但至少能让请求走完。
 *
 * <p>契约第 3.3 节。
 */
final class ContentBlockTranslator {

    /**
     * data URL 的形态：{@code data:<media_type>;base64,<data>}。
     *
     * <p>{@code media_type} 允许带参数（如 {@code image/jpeg;charset=utf-8}），
     * 因此用非贪婪匹配到最后一个 {@code ;base64,}。
     */
    private static final Pattern DATA_URL = Pattern.compile(
            "^data:([^;,]+)(?:;[^;,]+)*;base64,(.+)$", Pattern.DOTALL);

    /**
     * 翻译内容块数组。
     *
     * @param content 下游的 content 值
     * @param path    用于错误消息的字段路径
     * @return 翻译后的块列表，可能为空（全部块都被丢弃时）
     */
    List<Object> translateBlocks(Object content, String path) {
        List<Object> result = new ArrayList<>();
        if (!(content instanceof List<?> parts)) {
            return result;
        }
        for (Object part : parts) {
            if (!(part instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> block = translateBlock(asStringKeyMap(raw));
            if (block != null) {
                result.add(block);
            }
        }
        return result;
    }

    /**
     * 翻译单个块，认不出返回 null。
     */
    private Map<String, Object> translateBlock(Map<String, Object> block) {
        String type = block.get("type") instanceof String value ? value : null;
        if (type == null) {
            return null;
        }
        return switch (type) {
            // input_text 是 Responses 协议的形态。一并接受：它与 text 语义相同，
            // 而客户端混用两者的情况确实存在。
            case "text", "input_text" -> translateText(block);
            case "image_url" -> translateImage(block);
            // 其余一律丢弃。刻意不写 default 之外的分支列表 ——
            // 未来 OpenAI 新增块类型时，默默丢弃比默默透传安全。
            default -> null;
        };
    }

    private Map<String, Object> translateText(Map<String, Object> block) {
        Object text = block.get("text");
        if (!(text instanceof String value) || value.isBlank()) {
            // Anthropic 拒收空 text 块。
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "text");
        result.put("text", value);
        return result;
    }

    /**
     * {@code image_url} → Anthropic {@code image} 块。
     *
     * <h2>两种 URL 形态都不下载</h2>
     * data URL 就地解析成 base64 源；http/https 用 Anthropic 原生的
     * {@code source.type: "url"}。参考项目里把 http URL 一律下载转 base64 的做法
     * 是额外的带宽、延迟与失败点——Anthropic 本来就支持 URL 形态。
     */
    private Map<String, Object> translateImage(Map<String, Object> block) {
        String url = extractImageUrl(block);
        if (url == null || url.isBlank()) {
            return null;
        }

        Map<String, Object> source = new LinkedHashMap<>();
        Matcher dataUrl = DATA_URL.matcher(url);
        if (dataUrl.matches()) {
            source.put("type", "base64");
            source.put("media_type", dataUrl.group(1));
            source.put("data", dataUrl.group(2));
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            source.put("type", "url");
            source.put("url", url);
        } else {
            // 既不是 data URL 也不是 http(s)。可能是 file:// 或相对路径，
            // 两者上游都取不到，丢弃比发出去 400 好。
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "image");
        result.put("source", source);
        return result;
    }

    /**
     * 取出图片 URL。
     *
     * <p>OpenAI 的形态是 {@code {"type":"image_url","image_url":{"url":"..."}}}，
     * 但也有客户端直接给字符串。两种都收。
     */
    private String extractImageUrl(Map<String, Object> block) {
        Object imageUrl = block.get("image_url");
        if (imageUrl instanceof String direct) {
            return direct;
        }
        if (imageUrl instanceof Map<?, ?> map && map.get("url") instanceof String url) {
            return url;
        }
        return null;
    }

    /**
     * 把 content 压平成字符串，只取文本块。
     *
     * <p>用于 <strong>system</strong>——那里的目标形态就是字符串（Anthropic 的顶层
     * {@code system} 接受字符串，而下游的 system 提取那一步本来就只认字符串）。
     * 与 {@code GenericAnthropicChatService.stringifyContent} 同口径。
     *
     * <p><strong>不要用于 {@code tool_result}。</strong> 本方法会丢弃图片等非文本块，
     * 而 Anthropic 的 {@code tool_result.content} 原生支持图片块，压平会让 agent
     * 用工具读到的图在翻译中消失。那一处走 {@link #translateBlocks}，
     * 理由见 {@code MessageTranslator.translateToolResult}。
     *
     * @return 字符串形态；输入为 null 或无文本块时返回空串
     */
    String stringify(Object content) {
        if (content instanceof String text) {
            return text;
        }
        if (!(content instanceof List<?> parts)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof Map<?, ?> map
                    && isTextType(map.get("type"))
                    && map.get("text") instanceof String text) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }
        return builder.toString();
    }

    private static boolean isTextType(Object type) {
        return "text".equals(type) || "input_text".equals(type);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}

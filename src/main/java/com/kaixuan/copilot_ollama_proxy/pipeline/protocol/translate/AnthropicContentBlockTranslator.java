package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic 文本、图片与文档到 Chat 内容的纯结构转换。
 *
 * <p>普通消息与工具结果共用同一规则。每个源块保留为一个组：含媒体文档的标题、
 * 上下文与正文必须和媒体一起移动，不能逐 part 拆开后丢失关联。不下载、解析或重编码文件。
 * 目标格式与损失边界见请求契约 §10.5、§10.7。
 */
final class AnthropicContentBlockTranslator {

    /** 一个源块的完整产物；内部容器均为新建的可写副本。 */
    record ContentGroup(List<Object> parts) {
        boolean hasMedia() {
            return parts.stream().anyMatch(part -> part instanceof Map<?, ?> map
                    && ("image_url".equals(map.get("type")) || "file".equals(map.get("type"))));
        }
    }

    List<ContentGroup> translate(Object content, String path) {
        List<ContentGroup> groups = new ArrayList<>();
        if (content == null) {
            return groups;
        }
        if (content instanceof String text) {
            addTextGroup(groups, text);
            return groups;
        }
        if (!(content instanceof List<?> blocks)) {
            throw new RequestTranslationException(path, "必须为字符串或内容块数组");
        }
        for (int i = 0; i < blocks.size(); i++) {
            groups.add(translateBlock(blocks.get(i), path + "[" + i + "]"));
        }
        return groups;
    }

    ContentGroup translateBlock(Object rawBlock, String path) {
        Map<?, ?> block = requireObject(rawBlock, path);
        String type = requireString(block.get("type"), path + ".type");
        List<Object> parts = new ArrayList<>();
        switch (type) {
            case "text" -> addText(parts, textValue(block.get("text"), path + ".text"));
            case "image" -> parts.add(translateImage(block, path));
            case "document" -> parts.addAll(translateDocument(block, path));
            default -> throw new RequestTranslationException(path + ".type", "该内容块无法表达为 Chat 内容");
        }
        return new ContentGroup(parts);
    }

    /** 纯文本用字符串；有图片/文件时按源顺序保留 parts。 */
    static Object messageContent(List<ContentGroup> groups) {
        List<Object> parts = new ArrayList<>();
        groups.forEach(group -> parts.addAll(group.parts()));
        return groups.stream().anyMatch(ContentGroup::hasMedia) ? parts : textContent(parts);
    }

    static String textContent(List<Object> parts) {
        StringBuilder text = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof Map<?, ?> map && "text".equals(map.get("type"))
                    && map.get("text") instanceof String value && !value.isEmpty()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(value);
            }
        }
        return text.toString();
    }

    private Map<String, Object> translateImage(Map<?, ?> block, String path) {
        Map<?, ?> source = requireObject(block.get("source"), path + ".source");
        String type = requireString(source.get("type"), path + ".source.type");
        String url;
        switch (type) {
            case "base64" -> {
                String mediaType = requireString(source.get("media_type"), path + ".source.media_type");
                if (!mediaType.startsWith("image/")) {
                    throw new RequestTranslationException(path + ".source.media_type", "必须为图片 MIME 类型");
                }
                String data = textValue(source.get("data"), path + ".source.data");
                url = "data:" + mediaType + ";base64," + data;
            }
            case "url" -> {
                url = requireString(source.get("url"), path + ".source.url");
                if (!url.regionMatches(true, 0, "https://", 0, 8)
                        && !url.regionMatches(true, 0, "http://", 0, 7)) {
                    throw new RequestTranslationException(path + ".source.url", "只支持 HTTP(S) 图片地址");
                }
            }
            default -> throw new RequestTranslationException(path + ".source.type", "无法表达该图片源");
        }
        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", url);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "image_url");
        result.put("image_url", imageUrl);
        return result;
    }

    private List<Object> translateDocument(Map<?, ?> block, String path) {
        validateCitations(block.get("citations"), path + ".citations");
        Map<?, ?> source = requireObject(block.get("source"), path + ".source");
        String sourceType = requireString(source.get("type"), path + ".source.type");
        List<Object> parts = new ArrayList<>();
        addMetadata(parts, block, "title", path);
        addMetadata(parts, block, "context", path);
        switch (sourceType) {
            case "base64" -> {
                String mediaType = requireString(source.get("media_type"), path + ".source.media_type");
                if (!"application/pdf".equals(mediaType)) {
                    throw new RequestTranslationException(path + ".source.media_type", "Chat 内联文件首版仅支持 PDF");
                }
                String data = textValue(source.get("data"), path + ".source.data");
                String filename = optionalString(block, "filename", path);
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("filename", filename == null || filename.isBlank() ? "document.pdf" : filename);
                file.put("file_data", "data:application/pdf;base64," + data);
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("type", "file");
                part.put("file", file);
                parts.add(part);
            }
            case "text" -> {
                String mediaType = requireString(source.get("media_type"), path + ".source.media_type");
                if (!"text/plain".equals(mediaType)) {
                    throw new RequestTranslationException(path + ".source.media_type", "文本文档源必须声明 text/plain");
                }
                addText(parts, textValue(source.get("data"), path + ".source.data"));
            }
            case "content" -> parts.addAll(translateDocumentContent(source.get("content"), path + ".source.content"));
            case "url", "file" -> throw new RequestTranslationException(path + ".source.type",
                    "首版不下载 URL 文档，也不映射跨服务文件 ID");
            default -> throw new RequestTranslationException(path + ".source.type", "无法表达该文档源");
        }
        return parts;
    }

    private List<Object> translateDocumentContent(Object content, String path) {
        List<Object> parts = new ArrayList<>();
        if (content instanceof String text) {
            addText(parts, text);
            return parts;
        }
        if (!(content instanceof List<?> blocks)) {
            throw new RequestTranslationException(path, "必须为字符串或 text/image 块数组");
        }
        for (int i = 0; i < blocks.size(); i++) {
            String blockPath = path + "[" + i + "]";
            Map<?, ?> block = requireObject(blocks.get(i), blockPath);
            Object type = block.get("type");
            if (!"text".equals(type) && !"image".equals(type)) {
                throw new RequestTranslationException(blockPath + ".type", "文档 content 源仅接受 text/image");
            }
            parts.addAll(translateBlock(block, blockPath).parts());
        }
        return parts;
    }

    private static void validateCitations(Object rawCitations, String path) {
        if (rawCitations == null) {
            return;
        }
        Map<?, ?> citations = requireObject(rawCitations, path);
        Object enabled = citations.get("enabled");
        if (enabled != null && !(enabled instanceof Boolean)) {
            throw new RequestTranslationException(path + ".enabled", "必须为布尔值");
        }
        if (Boolean.TRUE.equals(enabled)) {
            throw new RequestTranslationException(path + ".enabled", "首版无法重建文档引用定位");
        }
    }

    private static void addMetadata(List<Object> parts, Map<?, ?> document, String key, String path) {
        String value = optionalString(document, key, path);
        if (value != null && !value.isEmpty()) {
            addText(parts, "[document " + key + "]\n" + value);
        }
    }

    private static void addTextGroup(List<ContentGroup> groups, String text) {
        List<Object> parts = new ArrayList<>();
        addText(parts, text);
        if (!parts.isEmpty()) {
            groups.add(new ContentGroup(parts));
        }
    }

    static void addText(List<Object> parts, String text) {
        if (!text.isEmpty()) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "text");
            part.put("text", text);
            parts.add(part);
        }
    }

    static Map<?, ?> requireObject(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new RequestTranslationException(path, "必须为对象");
    }

    static String requireString(Object value, String path) {
        String text = textValue(value, path);
        if (text.isBlank()) {
            throw new RequestTranslationException(path, "字符串不能为空白");
        }
        return text;
    }

    static String textValue(Object value, String path) {
        if (value instanceof String text) {
            return text;
        }
        throw new RequestTranslationException(path, "必须为字符串");
    }

    static String optionalString(Map<?, ?> source, String key, String path) {
        Object value = source.get(key);
        return value == null ? null : textValue(value, path + "." + key);
    }

    /** 只复制 JSON 容器，字符串/数值不经序列化；不删除任何同名业务键。 */
    static Object copyJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put((String) key, copyJson(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(copyJson(item)));
            return copy;
        }
        return value;
    }
}
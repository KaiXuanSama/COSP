package com.kaixuan.copilot_ollama_proxy.anthropic;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API 响应体 —— Mimo 后端返回的非流式响应。
 * 本代理在收到此响应后，通过 {@link com.kaixuan.copilot_ollama_proxy.proxy.MimoProxyService#convertResponse}
 * 将其转换为 Ollama 格式返回给 Copilot。
 * content 列表中可能包含多种类型的 block：
 * - text：普通文本回复
 * - thinking：思考过程（Thinking 模式下）
 * - tool_use：工具调用请求（模型决定需要调用某个工具时）
 */
public class AnthropicResponse {

    /** 响应唯一 ID */
    private String id;
    /** 响应类型 */
    private String type;
    /** 角色，固定为 "assistant" */
    private String role;

    /**
     * 内容块列表。一个响应可能包含多个 block，例如：
     * [thinking block, text block] 或 [text block, tool_use block]
     */
    private List<ContentBlock> content;

    /** 模型名称 */
    private String model;

    /** 停止原因："stop"（正常结束）或 "tool_use"（需要调用工具） */
    @JsonProperty("stop_reason")
    private String stopReason;

    @JsonProperty("stop_sequence")
    private String stopSequence;

    /** token 使用统计 */
    private Usage usage;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public List<ContentBlock> getContent() {
        return content;
    }

    public void setContent(List<ContentBlock> content) {
        this.content = content;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public String getStopSequence() {
        return stopSequence;
    }

    public void setStopSequence(String stopSequence) {
        this.stopSequence = stopSequence;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    /**
     * 内容块 —— Anthropic 响应中的基本内容单元。
     * type 字段决定此 block 的含义和应读取的字段：
     * - "text"：读取 text 字段
     * - "thinking"：读取 thinking 字段（思考过程）
     * - "tool_use"：读取 id、name、input 字段（工具调用请求）
     */
    public static class ContentBlock {
        /** block 类型：text / thinking / tool_use */
        private String type;
        /** 文本内容（type=text 时有值） */
        private String text;
        /** 工具调用 ID（type=tool_use 时有值） */
        private String id;
        /** 工具名称（type=tool_use 时有值） */
        private String name;
        /** 工具调用参数（type=tool_use 时有值） */
        private Map<String, Object> input;
        /** 思考过程文本（type=thinking 时有值） */
        private String thinking;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Map<String, Object> getInput() {
            return input;
        }

        public void setInput(Map<String, Object> input) {
            this.input = input;
        }

        public String getThinking() {
            return thinking;
        }

        public void setThinking(String thinking) {
            this.thinking = thinking;
        }
    }

    /**
     * token 使用统计。
     * Anthropic API 会报告输入/输出的 token 数量，用于计费和监控。
     */
    public static class Usage {
        /** 输入 token 数 */
        @JsonProperty("input_tokens")
        private int inputTokens;
        /** 输出 token 数 */
        @JsonProperty("output_tokens")
        private int outputTokens;
        /** 缓存写入的输入 token 数 */
        @JsonProperty("cache_creation_input_tokens")
        private int cacheCreationInputTokens;
        /** 缓存命中的输入 token 数（缓存命中的 token 价格更低） */
        @JsonProperty("cache_read_input_tokens")
        private int cacheReadInputTokens;

        public int getInputTokens() {
            return inputTokens;
        }

        public void setInputTokens(int inputTokens) {
            this.inputTokens = inputTokens;
        }

        public int getOutputTokens() {
            return outputTokens;
        }

        public void setOutputTokens(int outputTokens) {
            this.outputTokens = outputTokens;
        }

        public int getCacheCreationInputTokens() {
            return cacheCreationInputTokens;
        }

        public void setCacheCreationInputTokens(int cacheCreationInputTokens) {
            this.cacheCreationInputTokens = cacheCreationInputTokens;
        }

        public int getCacheReadInputTokens() {
            return cacheReadInputTokens;
        }

        public void setCacheReadInputTokens(int cacheReadInputTokens) {
            this.cacheReadInputTokens = cacheReadInputTokens;
        }
    }
}

package com.kaixuan.copilot_ollama_proxy.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API 请求体 —— 发送给 Mimo 后端的上游请求。
 * 本代理的核心职责之一就是将 Ollama 格式的请求（{@link com.kaixuan.copilot_ollama_proxy.ollama.OllamaChatRequest}）
 * 转换成此格式，然后通过 WebClient 发送到 Mimo 的 Anthropic 兼容端点。
 * Anthropic Messages API 与 Ollama Chat API 的主要区别：
 *   system 消息是独立的顶层字段，而非 messages 数组中的一条
 *   content 可以是纯字符串，也可以是结构化的 content block 列表
 *   工具定义使用 input_schema 而非 parameters
 *   工具结果通过 user 消息中的 tool_result block 传递，而非 role=tool 的消息
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicRequest {

    /** 模型名称，如 "mimo-v2.5-pro" */
    private String model;

    /** 最大生成 token 数 */
    @JsonProperty("max_tokens")
    private int maxTokens;

    /** 对话消息列表（不包含 system，system 单独放在顶层） */
    private List<Message> messages;

    /** 系统提示，作为独立的顶层字段 */
    private List<SystemContent> system;

    /** 工具定义列表 */
    private List<Tool> tools;

    /** 是否使用流式 SSE 响应 */
    private Boolean stream;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public List<SystemContent> getSystem() {
        return system;
    }

    public void setSystem(List<SystemContent> system) {
        this.system = system;
    }

    public List<Tool> getTools() {
        return tools;
    }

    public void setTools(List<Tool> tools) {
        this.tools = tools;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    /**
     * Anthropic 格式的消息。
     * content 可以是：
     * - 纯字符串（简单文本消息）
     * - List<Object>（结构化内容，包含 text、tool_use、tool_result 等 block）
     */
    public static class Message {
        private String role;
        private Object content;

        public Message() {
        }

        public Message(String role, Object content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public Object getContent() {
            return content;
        }

        public void setContent(Object content) {
            this.content = content;
        }
    }

    /**
     * 工具定义（Anthropic 格式）。
     * 与 Ollama 格式的关键区别：参数 schema 字段名为 input_schema（而非 parameters）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Tool {
        private String name;
        private String description;
        @JsonProperty("input_schema")
        private Map<String, Object> inputSchema;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Map<String, Object> getInputSchema() {
            return inputSchema;
        }

        public void setInputSchema(Map<String, Object> inputSchema) {
            this.inputSchema = inputSchema;
        }
    }

    /**
     * 系统提示内容块。
     * Anthropic 的 system 字段是一个列表，每个元素包含 type 和 text。
     */
    public static class SystemContent {
        /** 内容类型，固定为 "text" */
        private String type;
        /** 系统提示文本 */
        private String text;

        public SystemContent() {
        }

        public SystemContent(String text) {
            this.type = "text";
            this.text = text;
        }

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
    }
}

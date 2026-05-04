package com.kaixuan.copilot_ollama_proxy.ollama;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Ollama /api/chat 请求体 —— Copilot 发送给本代理的对话请求。
 * 对应 Copilot 调用 Ollama 时发送的 JSON，例如：
 * {
 *   "model": "mimo-v2.5-pro",
 *   "messages": [{"role": "user", "content": "你好"}],
 *   "stream": true,
 *   "tools": [...]
 * }
 */
public class OllamaChatRequest {

    /** 模型名称，如 "mimo-v2.5-pro"，由 Copilot 在模型选择时确定 */
    private String model;

    /**
     * 对话消息列表，包含完整的对话历史。
     * 每个 Message 包含 role（system/user/assistant/tool）和 content（消息内容）。
     */
    private List<Message> messages;

    /**
     * 工具定义列表（可选）。当 Copilot 需要调用外部工具（如文件读取、代码执行）时，
     * 会在这里声明工具的名称、描述和参数 schema。
     */
    private List<Tool> tools;

    /** Ollama 的模型参数选项，如 num_predict（最大生成 token 数）等 */
    private Map<String, Object> options;

    /** 是否使用流式响应。true 表示逐步返回增量文本，false 表示等生成完再返回。默认 true */
    @JsonProperty("stream")
    private Boolean stream;

    /** 保持模型加载状态的时间（可选），Ollama 的参数，本代理不使用 */
    @JsonProperty("keep_alive")
    private Object keepAlive;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public List<Tool> getTools() {
        return tools;
    }

    public void setTools(List<Tool> tools) {
        this.tools = tools;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public Object getKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(Object keepAlive) {
        this.keepAlive = keepAlive;
    }

    /** 判断是否为流式模式，默认 true（stream 字段为 null 时也视为流式） */
    public boolean isStream() {
        return stream == null || stream;
    }

    /**
     * 对话中的单条消息。
     * role 决定消息来源：system（系统提示）、user（用户）、assistant（模型）、tool（工具返回结果）
     */
    public static class Message {
        /** 消息角色：system / user / assistant / tool */
        private String role;
        /** 消息内容，可以是纯字符串，也可以是结构化的 content block 列表 */
        private Object content;
        /** 图片列表（base64 编码），用于多模态模型接收图片输入 */
        private List<String> images;
        /** 工具调用列表，仅 assistant 角色的消息中可能出现 */
        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;

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

        public List<String> getImages() {
            return images;
        }

        public void setImages(List<String> images) {
            this.images = images;
        }

        public List<ToolCall> getToolCalls() {
            return toolCalls;
        }

        public void setToolCalls(List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
        }
    }

    /** 工具定义，type 固定为 "function" */
    public static class Tool {
        private String type;
        private ToolFunction function;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public ToolFunction getFunction() {
            return function;
        }

        public void setFunction(ToolFunction function) {
            this.function = function;
        }
    }

    /**
     * 工具函数定义。
     * 例如：{"name": "read_file", "description": "读取文件内容", "parameters": {...}}
     */
    public static class ToolFunction {
        /** 函数名称 */
        private String name;
        /** 函数描述，告诉模型这个工具能做什么 */
        private String description;
        /** 参数的 JSON Schema 定义，描述函数接受哪些参数 */
        private Map<String, Object> parameters;
        /** 实际调用时传入的参数值（仅在 tool_calls 中出现） */
        private Map<String, Object> arguments;

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

        public Map<String, Object> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, Object> parameters) {
            this.parameters = parameters;
        }

        public Map<String, Object> getArguments() {
            return arguments;
        }

        public void setArguments(Map<String, Object> arguments) {
            this.arguments = arguments;
        }
    }

    /** 工具调用结果，包含模型决定调用的函数及其参数 */
    public static class ToolCall {
        private ToolFunction function;

        public ToolFunction getFunction() {
            return function;
        }

        public void setFunction(ToolFunction function) {
            this.function = function;
        }
    }
}

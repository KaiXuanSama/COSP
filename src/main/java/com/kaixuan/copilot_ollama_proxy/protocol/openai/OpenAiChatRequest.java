package com.kaixuan.copilot_ollama_proxy.protocol.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions API 请求体 —— Copilot 发送给本代理的请求格式。
 * 本代理接收此格式后，会交给 {@link com.kaixuan.copilot_ollama_proxy.application.openai.UpstreamChatService}
 * 的具体实现处理，默认实现会直接调用各供应商的 OpenAI 兼容接口。
 * OpenAI 格式示例：
 * {
 *   "model": "mimo-v2.5-pro",
 *   "messages": [
 *     {"role": "system", "content": "你是一个助手"},
 *     {"role": "user", "content": "你好"}
 *   ],
 *   "stream": true,
 *   "tools": [...]
 * }
 * content 可以是 String 或 List&lt;ContentPart&gt;（多模态场景）
 */
public class OpenAiChatRequest {

    /** 模型名称 */
    private String model;

    /** 对话消息列表 */
    private List<Message> messages;

    /** 采样温度，控制输出的随机性（0-2） */
    private Double temperature;

    /** nucleus 采样参数 */
    @JsonProperty("top_p")
    private Double topP;

    /** 最大生成 token 数 */
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /** 是否流式响应 */
    private Boolean stream;

    /** 工具定义列表 */
    private List<Tool> tools;

    /** 工具选择策略（auto / none / 指定工具） */
    @JsonProperty("tool_choice")
    private Object toolChoice;

    /** 请求数量（Copilot 固定为 1） */
    private Integer n;

    /** 流式选项（Copilot 用于请求 usage 统计） */
    @JsonProperty("stream_options")
    private Object streamOptions;

    // ========== getter/setter ==========

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

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public List<Tool> getTools() {
        return tools;
    }

    public void setTools(List<Tool> tools) {
        this.tools = tools;
    }

    public Object getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(Object toolChoice) {
        this.toolChoice = toolChoice;
    }

    public Integer getN() {
        return n;
    }

    public void setN(Integer n) {
        this.n = n;
    }

    public Object getStreamOptions() {
        return streamOptions;
    }

    public void setStreamOptions(Object streamOptions) {
        this.streamOptions = streamOptions;
    }

    /** 判断是否为流式模式 */
    public boolean isStream() {
        return stream != null && stream;
    }

    // ========== 内部类 ==========

    /**
     * 对话消息。
     * content 字段有两种格式：
     * - 纯文本：content 为 String
     * - 多模态（含图片）：content 为 List&lt;ContentPart&gt;
     */
    public static class Message {
        /** 消息角色：system / user / assistant / tool */
        private String role;
        /** 消息内容，可能是 String 或 List&lt;ContentPart&gt; */
        private Object content;
        /** 工具调用列表（仅 assistant 消息） */
        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;
        /** 工具调用 ID（仅 role=tool 的消息，用于关联 tool_result） */
        @JsonProperty("tool_call_id")
        private String toolCallId;

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

        public List<ToolCall> getToolCalls() {
            return toolCalls;
        }

        public void setToolCalls(List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
        }

        public String getToolCallId() {
            return toolCallId;
        }

        public void setToolCallId(String toolCallId) {
            this.toolCallId = toolCallId;
        }
    }

    /**
     * 多模态内容片段。
     * 当 content 是 List 时，每个元素是一个 ContentPart，
     * type 字段决定是文本还是图片。
     */
    public static class ContentPart {
        /** 内容类型："text" 或 "image_url" */
        private String type;
        /** 文本内容（type=text 时有值） */
        private String text;
        /** 图片 URL 信息（type=image_url 时有值） */
        @JsonProperty("image_url")
        private ImageUrl imageUrl;

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

        public ImageUrl getImageUrl() {
            return imageUrl;
        }

        public void setImageUrl(ImageUrl imageUrl) {
            this.imageUrl = imageUrl;
        }
    }

    /** 图片 URL，包含 url 字段（可以是 http 链接或 base64 data URI） */
    public static class ImageUrl {
        private String url;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
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

    /** 工具函数定义 */
    public static class ToolFunction {
        private String name;
        private String description;
        private Map<String, Object> parameters;

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
    }

    /** 工具调用（assistant 消息中模型决定调用的工具） */
    public static class ToolCall {
        private String id;
        private String type;
        private ToolCallFunction function;

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

        public ToolCallFunction getFunction() {
            return function;
        }

        public void setFunction(ToolCallFunction function) {
            this.function = function;
        }
    }

    /** 工具调用的函数详情 */
    public static class ToolCallFunction {
        private String name;
        /** JSON 字符串格式的参数 */
        private String arguments;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getArguments() {
            return arguments;
        }

        public void setArguments(String arguments) {
            this.arguments = arguments;
        }
    }
}

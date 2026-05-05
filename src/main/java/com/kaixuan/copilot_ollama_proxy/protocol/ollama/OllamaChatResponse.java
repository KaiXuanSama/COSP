package com.kaixuan.copilot_ollama_proxy.protocol.ollama;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Ollama /api/chat 响应体 —— 返回给 Copilot 的对话结果。
 * 在流式模式下，会发送多个此对象（每个是一个 NDJSON 行），逐步拼接出完整回复。
 * 最后一个对象的 done=true，标志本轮对话结束。
 * 示例（流式中间 chunk）：
 * {"model":"mimo-v2.5-pro","message":{"role":"assistant","content":"你"},"done":false}
 * 示例（流式结束 chunk）：
 * {"model":"mimo-v2.5-pro","message":{"role":"assistant","content":"你好！"},"done":true,"done_reason":"stop"}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OllamaChatResponse {

    /** 模型名称，原样返回请求中的 model */
    private String model;

    /** 响应创建时间，ISO 8601 格式 */
    @JsonProperty("created_at")
    private String createdAt;

    /** 模型回复的消息内容 */
    private ResponseMessage message;

    /** 是否生成完毕。流式模式下中间 chunk 为 false，最后一个 chunk 为 true */
    private boolean done;

    /** 生成结束的原因："stop"（正常结束）或 "tool_calls"（需要调用工具） */
    @JsonProperty("done_reason")
    private String doneReason;

    // 以下为性能统计字段（可选），本代理暂未填充

    @JsonProperty("total_duration")
    private Long totalDuration;
    @JsonProperty("load_duration")
    private Long loadDuration;
    @JsonProperty("prompt_eval_count")
    private Integer promptEvalCount;
    @JsonProperty("prompt_eval_duration")
    private Long promptEvalDuration;
    @JsonProperty("eval_count")
    private Integer evalCount;
    @JsonProperty("eval_duration")
    private Long evalDuration;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public ResponseMessage getMessage() {
        return message;
    }

    public void setMessage(ResponseMessage message) {
        this.message = message;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public String getDoneReason() {
        return doneReason;
    }

    public void setDoneReason(String doneReason) {
        this.doneReason = doneReason;
    }

    public Long getTotalDuration() {
        return totalDuration;
    }

    public void setTotalDuration(Long totalDuration) {
        this.totalDuration = totalDuration;
    }

    public Long getLoadDuration() {
        return loadDuration;
    }

    public void setLoadDuration(Long loadDuration) {
        this.loadDuration = loadDuration;
    }

    public Integer getPromptEvalCount() {
        return promptEvalCount;
    }

    public void setPromptEvalCount(Integer promptEvalCount) {
        this.promptEvalCount = promptEvalCount;
    }

    public Long getPromptEvalDuration() {
        return promptEvalDuration;
    }

    public void setPromptEvalDuration(Long promptEvalDuration) {
        this.promptEvalDuration = promptEvalDuration;
    }

    public Integer getEvalCount() {
        return evalCount;
    }

    public void setEvalCount(Integer evalCount) {
        this.evalCount = evalCount;
    }

    public Long getEvalDuration() {
        return evalDuration;
    }

    public void setEvalDuration(Long evalDuration) {
        this.evalDuration = evalDuration;
    }

    /**
     * 模型回复的消息体。
     * 流式模式下，每个中间 chunk 的 content 只包含新增的文本片段，
     * Copilot 负责将它们拼接起来显示给用户。
     */
    public static class ResponseMessage {
        /** 角色，固定为 "assistant"（表示这是模型的回复） */
        private String role;
        /** 文本内容 */
        private String content;
        /** 思考过程（部分模型支持，如 Thinking 模式下的推理过程） */
        private String thinking;
        /** 工具调用列表，当模型决定需要调用工具时此字段非空 */
        @JsonProperty("tool_calls")
        private List<ToolCallResult> toolCalls;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getThinking() {
            return thinking;
        }

        public void setThinking(String thinking) {
            this.thinking = thinking;
        }

        public List<ToolCallResult> getToolCalls() {
            return toolCalls;
        }

        public void setToolCalls(List<ToolCallResult> toolCalls) {
            this.toolCalls = toolCalls;
        }
    }

    /** 工具调用结果，包含函数名和参数 */
    public static class ToolCallResult {
        private ToolCallFunction function;

        public ToolCallFunction getFunction() {
            return function;
        }

        public void setFunction(ToolCallFunction function) {
            this.function = function;
        }
    }

    /** 工具调用的函数详情：名称 + 参数 */
    public static class ToolCallFunction {
        /** 要调用的函数名 */
        private String name;
        /** 传给函数的参数 */
        private Map<String, Object> arguments;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Map<String, Object> getArguments() {
            return arguments;
        }

        public void setArguments(Map<String, Object> arguments) {
            this.arguments = arguments;
        }
    }
}

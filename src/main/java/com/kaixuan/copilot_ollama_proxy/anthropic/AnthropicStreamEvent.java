package com.kaixuan.copilot_ollama_proxy.anthropic;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Anthropic SSE 流式事件 —— Mimo 后端在流式模式下逐个发送的事件。
 * 流式响应的生命周期由多个事件组成，按顺序为：
 * message_start        → 消息开始（包含模型信息和 usage）
 * content_block_start  → 内容块开始（标记 text 或 tool_use 块的开始）
 * content_block_delta  → 内容增量（实际的文本片段或工具参数片段，可能多次）
 * content_block_stop   → 内容块结束
 * message_delta        → 消息级别更新（包含 stop_reason）
 * message_stop         → 消息结束
 * 本代理通过 {@link com.kaixuan.copilot_ollama_proxy.proxy.MimoProxyService#processStreamEvents}
 * 逐个处理这些事件，将增量文本实时转换为 Ollama 格式的 chunk 返回给 Copilot。
 */
public class AnthropicStreamEvent {

    /**
     * 事件类型，决定如何处理此事件。
     * 可能的值：message_start / content_block_start / content_block_delta /
     *           content_block_stop / message_delta / message_stop
     */
    private String type;

    /** 消息元数据（仅 message_start 事件有值） */
    private Map<String, Object> message;

    /** 当前内容块的索引（从 0 开始） */
    private Integer index;

    /** 内容块信息（仅 content_block_start 事件有值，包含 type、name 等） */
    @JsonProperty("content_block")
    private Map<String, Object> contentBlock;

    /**
     * 内容增量（仅 content_block_delta 事件有值）。
     * 对于文本块：delta 包含 {"type": "text_delta", "text": "新增的文本片段"}
     * 对于工具块：delta 包含 {"type": "input_json_delta", "partial_json": "部分参数JSON"}
     */
    private Map<String, Object> delta;

    /** token 使用统计（仅 message_start 和 message_delta 事件有值） */
    private Map<String, Object> usage;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getMessage() {
        return message;
    }

    public void setMessage(Map<String, Object> message) {
        this.message = message;
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public Map<String, Object> getContentBlock() {
        return contentBlock;
    }

    public void setContentBlock(Map<String, Object> contentBlock) {
        this.contentBlock = contentBlock;
    }

    public Map<String, Object> getDelta() {
        return delta;
    }

    public void setDelta(Map<String, Object> delta) {
        this.delta = delta;
    }

    public Map<String, Object> getUsage() {
        return usage;
    }

    public void setUsage(Map<String, Object> usage) {
        this.usage = usage;
    }
}

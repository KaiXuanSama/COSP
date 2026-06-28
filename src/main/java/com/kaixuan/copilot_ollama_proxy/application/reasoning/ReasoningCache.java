package com.kaixuan.copilot_ollama_proxy.application.reasoning;

/**
 * 工具调用思考链缓存 —— 领域接口。
 *
 * provider 层通过该接口读写 reasoning_content 缓存，而不直接依赖具体的持久化实现，
 * 从而与底层存储（SQLite / Redis / 内存等）解耦。
 *
 * 这是依赖倒置（DIP）的体现：接口定义在 application 层，实现放在 infrastructure 层。
 */
public interface ReasoningCache {

    /**
     * 保存一条工具调用的思考链缓存。
     *
     * @param toolCallId       工具调用 ID
     * @param reasoningContent 思考链文本
     */
    void save(String toolCallId, String reasoningContent);

    /**
     * 根据工具调用 ID 查询思考链内容。
     *
     * @param toolCallId 工具调用 ID
     * @return 思考链文本，未找到时返回 null
     */
    String findByToolCallId(String toolCallId);
}

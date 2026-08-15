package com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;

/**
 * Anthropic Messages API 的 usage 解析。
 *
 * <h2>为何独立于 {@code UsageParser} 而非扩展它</h2>
 * 字段名全然不同（{@code input_tokens} / {@code output_tokens} /
 * {@code cache_read_input_tokens} / {@code cache_creation_input_tokens}），
 * 且 OpenAI 侧那份还带着「8+1 个供应商实证得出的 cached_tokens fallback 链」，
 * 那条链是 OpenAI 兼容生态混乱的产物，套到 Anthropic 上没有意义。
 *
 * <p><strong>但输出契约共享</strong>：两侧都产出 {@link UsageTokens}，
 * 因此落库、前端展示、缓存命中率计算全部无需分支 —— 这正是「解析各写一份、
 * 度量归一化」的分界。
 *
 * <h2>null 与 0 的区分必须保留</h2>
 * 与 OpenAI 侧同一约束：{@code null} 表示上游未提供该字段，{@code 0} 表示上游
 * 报告了真实零值。一律按<strong>字段存在性</strong>提取，绝不用 {@code asInt(0)}
 * 兜底，否则会把两者抹平，毁掉缓存命中率的可解读性。
 *
 * <h2>流式下 usage 分散在两个事件里</h2>
 * {@code message_start} 携带 {@code input_tokens}（此时 {@code output_tokens}
 * 通常为 0 或缺失），{@code message_delta} 携带最终的 {@code output_tokens}。
 * 因此流式链路不能只取一个事件就完事，需要跨事件合并 —— 见 {@link #merge}。
 *
 * <h2>与 OpenAI 侧的位置不一致（待统一）</h2>
 * TODO 本类与 {@code application.usage.UsageParser} 是<strong>同一职责的两个协议实现</strong>。
 *  后者硬编码 {@code prompt_tokens} / {@code completion_tokens}，同样只服务 OpenAI，
 *  却因为「只有一种协议时不需要区分」而放在 {@code application.usage} 且名字不带协议。
 *  <p>待翻译层阶段一并整理：重命名为 {@code OpenAiUsageParser} 并移入
 *  {@code provider.generic.openai}，与本类对称。注意它被 {@code OpenAiController} 与
 *  {@code AbstractUpstreamChatService} 引用，移包会波及那两处 import。
 *  <p>{@link UsageTokens} 不在此列 —— 它是<strong>共享的输出契约</strong>
 *  （两侧都产出它，落库与前端因此无需分支），留在 {@code application.usage} 是正确的归属。
 */
public final class AnthropicUsageParser {

    private AnthropicUsageParser() {
    }

    /**
     * 从一个 Anthropic 事件或完整响应体中提取 usage 对象的原始 JSON。
     *
     * <p>{@code message_start} 的 usage 嵌在 {@code message.usage} 下，
     * 而 {@code message_delta} 与非流式响应的 usage 在顶层 —— 两处都要看。
     *
     * @return usage 对象的原始 JSON；无 usage 或解析失败返回 null
     */
    public static String extractUsageRawJson(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode usage = locateUsage(root);
            return usage == null ? null : objectMapper.writeValueAsString(usage);
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 解析一个 Anthropic usage 对象的 JSON 为归一化的 token 指标。
     *
     * @param usageObjectJson usage 对象原始 JSON（非事件整体）
     */
    public static UsageTokens parseUsageObject(ObjectMapper objectMapper, String usageObjectJson) {
        if (usageObjectJson == null || usageObjectJson.isBlank()) {
            return UsageTokens.EMPTY;
        }
        try {
            return toTokens(objectMapper.readTree(usageObjectJson));
        } catch (Exception exception) {
            return UsageTokens.EMPTY;
        }
    }

    /**
     * 合并两轮 usage —— 流式下把 {@code message_start} 的输入与
     * {@code message_delta} 的输出拼成完整一份。
     *
     * <p>合并规则是「后来的非 null 值覆盖先前的」而非相加：Anthropic 的
     * {@code message_delta} 报告的 {@code output_tokens} 是<strong>累计值</strong>
     * 而非增量，相加会翻倍。而输入 token 只在 {@code message_start} 出现一次，
     * 后续事件里缺失，故 null 不得覆盖已有值。
     *
     * @param base   先前累积的指标；可为 null
     * @param update 新一轮解析出的指标；可为 null
     */
    public static UsageTokens merge(UsageTokens base, UsageTokens update) {
        if (base == null || base.isEmpty()) {
            return update == null ? UsageTokens.EMPTY : update;
        }
        if (update == null || update.isEmpty()) {
            return base;
        }
        return new UsageTokens(
                update.promptTokens() != null ? update.promptTokens() : base.promptTokens(),
                update.completionTokens() != null ? update.completionTokens() : base.completionTokens(),
                update.cachedTokens() != null ? update.cachedTokens() : base.cachedTokens());
    }

    /**
     * 定位 usage 节点：先看顶层，再看 {@code message.usage}。
     *
     * <p>顺序不能反 —— {@code message_delta} 事件的顶层就有 usage，
     * 而它没有 {@code message} 字段；{@code message_start} 反之。
     */
    private static JsonNode locateUsage(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode topLevel = root.get("usage");
        if (topLevel != null && topLevel.isObject()) {
            return topLevel;
        }
        JsonNode message = root.get("message");
        if (message != null && message.isObject()) {
            JsonNode nested = message.get("usage");
            if (nested != null && nested.isObject()) {
                return nested;
            }
        }
        return null;
    }

    /**
     * 把 Anthropic usage 对象映射为归一化指标。
     *
     * <p>缓存 token 取 {@code cache_read_input_tokens}（真正的缓存命中读取量），
     * <strong>不取</strong> {@code cache_creation_input_tokens} —— 后者是「本次写入缓存
     * 的量」，属于成本项而非命中项，混入会让缓存命中率虚高。
     */
    private static UsageTokens toTokens(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return UsageTokens.EMPTY;
        }
        return new UsageTokens(
                intIfPresent(usage, "input_tokens"),
                intIfPresent(usage, "output_tokens"),
                intIfPresent(usage, "cache_read_input_tokens"));
    }

    /** 仅当字段存在且为整数时返回值，否则 null —— 保住 null 与 0 的区分。 */
    private static Integer intIfPresent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : null;
    }
}

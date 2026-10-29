package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.kaixuan.copilot_ollama_proxy.pipeline.after.send.chat.OpenAiUsageParser;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTokens;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chat usage → Messages 的出站展示形态，非流式与流式共用。
 *
 * <p>缓存命中字段的优先级复用 {@link OpenAiUsageParser}，不另起一套 fallback。
 * 缓存写入只从原始 usage 的已知兼容字段取；它不是 {@link UsageTokens} 的第四列。
 * 本类的结果只能放进响应 JSON，绝不能写回事件的 usage 记账槽或 usage_raw。
 */
final class ChatToMessagesUsageConverter {

    private ChatToMessagesUsageConverter() {
    }

    static Map<String, Object> toAnthropicUsage(JsonNode usage) {
        UsageTokens tokens = OpenAiUsageParser.parse(usage);
        Long cacheCreation = cacheCreationTokens(usage);
        long cacheRead = tokens.cachedTokens() == null ? 0 : tokens.cachedTokens();
        long cacheWrite = cacheCreation == null ? 0 : cacheCreation;

        Map<String, Object> result = new LinkedHashMap<>();
        // 仅出站的必需数字字段用 0 占位；原生 UsageTokens 的 null/0 语义不受影响。
        result.put("input_tokens", Math.max(0L, tokens.promptOrZero() - cacheRead - cacheWrite));
        result.put("output_tokens", tokens.completionOrZero());
        if (tokens.cachedTokens() != null) {
            result.put("cache_read_input_tokens", tokens.cachedTokens());
        }
        if (cacheCreation != null) {
            result.put("cache_creation_input_tokens", cacheCreation);
        }
        return result;
    }

    /** Chat 的 usage 是累计快照，不套用 Anthropic 的跨事件正值合并规则。 */
    static boolean hasKnownCounters(JsonNode usage) {
        return !OpenAiUsageParser.parse(usage).isEmpty() || cacheCreationTokens(usage) != null;
    }

    private static Long cacheCreationTokens(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return null;
        }
        JsonNode details = usage.get("prompt_tokens_details");
        for (String key : new String[]{"cache_write_tokens", "cache_creation_tokens"}) {
            Long count = number(details, key);
            if (count != null) {
                return count;
            }
        }
        return number(usage, "cache_creation_input_tokens");
    }

    private static Long number(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isNumber() ? value.longValue() : null;
    }
}
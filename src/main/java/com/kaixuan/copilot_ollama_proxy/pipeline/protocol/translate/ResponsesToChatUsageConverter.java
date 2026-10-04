package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Responses usage → OpenAI usage 的换算（流式收尾与非流式共用）。
 *
 * <h2>为何独立成类</h2>
 * 两条路径（流式 finalizeStream 的 usage chunk、非流式响应体的 usage 键）必须
 * <strong>同口径</strong>，否则同一轮调用的日志对不上账。此前换算逻辑在
 * {@code ResponsesToChatNonStreamTranslator} 的私有方法里，流式拿不到 ——
 * 提出来是「一份实现、两个消费点」的标准动作（与 {@code AnthropicUsageAccumulator}
 * 的存在理由同构）。
 *
 * <h2>换算规则（与非流式实现逐字一致）</h2>
 * <ul>
 *   <li><strong>prompt = input 直接映射</strong>：Responses 的 {@code input_tokens}
 *       已是总输入（{@code input_tokens_details.cached_tokens} 是其中命中缓存的），
 *       <strong>不需要 Anthropic 侧那种三项相加</strong> —— 两种协议的口径相反，
 *       绝不能照抄 {@code AnthropicUsageAccumulator} 的换算；</li>
 *   <li>{@code reasoning_tokens} 从 {@code output_tokens_details} 归位到
 *       {@code completion_tokens_details}（M2C 拿不到的数字，R2C 的真实增量信息）；</li>
 *   <li>两份 details 只在非零时给：全零的 details 对象是噪声，且会让下游误以为
 *       上游支持该统计。</li>
 * </ul>
 */
final class ResponsesToChatUsageConverter {

    private ResponsesToChatUsageConverter() {
    }

    /**
     * 换算 usage 节点。
     *
     * @return OpenAI 形态的 usage Map；上游无 usage（null / 非对象）时返回 null
     */
    static Map<String, Object> toOpenAiUsage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prompt_tokens", intOrZero(usage, "input_tokens"));
        result.put("completion_tokens", intOrZero(usage, "output_tokens"));
        result.put("total_tokens", intOrZero(usage, "total_tokens"));

        JsonNode inputDetails = usage.get("input_tokens_details");
        long cached = inputDetails != null && inputDetails.isObject()
                ? longOrZero(inputDetails, "cached_tokens") : 0;
        if (cached > 0) {
            Map<String, Object> promptDetails = new LinkedHashMap<>();
            promptDetails.put("cached_tokens", cached);
            result.put("prompt_tokens_details", promptDetails);
        }

        JsonNode outputDetails = usage.get("output_tokens_details");
        long reasoning = outputDetails != null && outputDetails.isObject()
                ? longOrZero(outputDetails, "reasoning_tokens") : 0;
        if (reasoning > 0) {
            Map<String, Object> completionDetails = new LinkedHashMap<>();
            completionDetails.put("reasoning_tokens", reasoning);
            result.put("completion_tokens_details", completionDetails);
        }
        return result;
    }

    private static long longOrZero(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asLong() : 0;
    }

    private static int intOrZero(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : 0;
    }

    /**
     * 非流式的便捷入口：换算并直接转 JsonNode（响应体以 JsonNode 形态组装）。
     *
     * @return OpenAI 形态的 usage 节点；上游无 usage 时返回 null
     */
    static com.fasterxml.jackson.databind.JsonNode toOpenAiUsageNode(
            com.fasterxml.jackson.databind.ObjectMapper objectMapper, JsonNode usage) {
        Map<String, Object> converted = toOpenAiUsage(usage);
        return converted == null ? null : objectMapper.valueToTree(converted);
    }
}

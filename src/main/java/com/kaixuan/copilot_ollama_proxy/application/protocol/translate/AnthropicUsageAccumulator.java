package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anthropic usage → OpenAI usage 的换算。
 *
 * <h2>为何必须换算而非直接改名</h2>
 * <strong>Anthropic 的 {@code input_tokens} 不含缓存，OpenAI 的 {@code prompt_tokens}
 * 含缓存。</strong> 三个参考项目都做了这个换算且都写了注释说明——直接映射会让
 * {@code api_call_usage} 表少记缓存部分，影响计费口径。
 *
 * <pre>
 * prompt_tokens = input_tokens + cache_read_input_tokens + cache_creation_input_tokens
 * </pre>
 *
 * <h2>为何是可变累加器而非不可变 record</h2>
 * usage 分两个事件到达（{@code message_start} 给输入、{@code message_delta} 给输出），
 * 流式场景下必须跨事件累积。做成 record 每次合并都要重建对象，
 * 而这是热路径上每个事件都会走的代码。
 *
 * <p>契约第 9 节。
 */
final class AnthropicUsageAccumulator {

    private long inputTokens;
    private long outputTokens;
    private long cacheReadTokens;
    private long cacheCreationTokens;
    private boolean everSeen;

    /**
     * 合并一个 usage 对象。
     *
     * <h2>为何 0 不覆盖</h2>
     * {@code message_delta} 的 usage 里 {@code input_tokens} 常常是 0
     * （那一侧的数字已经在 {@code message_start} 给过了）。若无条件覆盖，
     * 就会把先前记下的输入 token 抹成 0。三个参考项目都踩过或规避了这个坑。
     *
     * @param usage Anthropic 的 usage 节点；null 或非对象时忽略
     */
    void merge(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return;
        }
        everSeen = true;
        inputTokens = mergeField(inputTokens, usage, "input_tokens");
        outputTokens = mergeField(outputTokens, usage, "output_tokens");
        cacheReadTokens = mergeField(cacheReadTokens, usage, "cache_read_input_tokens");
        cacheCreationTokens = mergeField(cacheCreationTokens, usage, "cache_creation_input_tokens");
    }

    private static long mergeField(long current, JsonNode usage, String field) {
        JsonNode value = usage.get(field);
        if (value == null || !value.isNumber()) {
            return current;
        }
        long incoming = value.asLong();
        // 只有正数才覆盖，见方法注释。
        return incoming > 0 ? incoming : current;
    }

    /** 是否见过任何 usage 信息。整轮没见过时不该凭空造一个全 0 的 usage 对象。 */
    boolean hasUsage() {
        return everSeen;
    }

    /**
     * 产出 OpenAI 形态的 usage。
     *
     * <p>{@code completion_tokens_details.reasoning_tokens} <strong>拿不到</strong>——
     * Anthropic 不单独上报思考 token。不凭空估算，直接不给这个字段。
     */
    Map<String, Object> toOpenAiUsage() {
        long promptTokens = inputTokens + cacheReadTokens + cacheCreationTokens;

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", outputTokens);
        usage.put("total_tokens", promptTokens + outputTokens);

        // 缓存明细只在真的有缓存时才给：全 0 的 details 对象是噪声，
        // 且会让下游误以为这个上游支持缓存统计。
        if (cacheReadTokens > 0 || cacheCreationTokens > 0) {
            Map<String, Object> promptDetails = new LinkedHashMap<>();
            promptDetails.put("cached_tokens", cacheReadTokens);
            usage.put("prompt_tokens_details", promptDetails);
        }
        return usage;
    }
}

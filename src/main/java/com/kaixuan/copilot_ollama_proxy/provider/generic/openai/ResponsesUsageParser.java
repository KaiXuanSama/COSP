package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;

/**
 * OpenAI <strong>Responses</strong> API 的 usage 解析。
 *
 * <h2>为何独立于 {@code OpenAiUsageParser} 而非复用它</h2>
 * 字段名不同：Chat 用 {@code prompt_tokens} / {@code completion_tokens}，
 * Responses 用 {@code input_tokens} / {@code output_tokens}，缓存命中在
 * {@code input_tokens_details.cached_tokens}（Chat 是 {@code prompt_tokens_details}）。
 *
 * <p>而且 Chat 侧那份带着「8+1 个供应商实证得出的 {@code cached_tokens} fallback 链」——
 * 那条链是 OpenAI <strong>兼容生态</strong>混乱的产物（各家把缓存命中放在不同字段名下），
 * Responses 的兼容端点目前没有同等程度的分歧，照抄那条链只会引入一堆无从验证的路径。
 *
 * <p><strong>但输出契约共享</strong>：都产出 {@link UsageTokens}，落库无需分支。
 *
 * <h2>换算是「直接搬」，不需要加法 —— 与 Anthropic 侧的关键差异</h2>
 * <pre>
 * prompt_tokens     = input_tokens                            ← 本身就是总输入
 * completion_tokens = output_tokens
 * cached_tokens     = input_tokens_details.cached_tokens      ← input_tokens 的<strong>子集</strong>
 * </pre>
 *
 * <p><strong>绝不要照抄 {@code AnthropicUsageParser.toTokens} 的三项相加。</strong>
 * 那边必须 {@code input + cache_read + cache_creation}，因为 Anthropic 把输入拆成三个
 * <strong>互斥</strong>的量，后两个排在 {@code input_tokens} 之外。OpenAI 系不拆：
 * {@code input_tokens} 已经是本轮全部输入，而 {@code cached_tokens} 是从其中<strong>标注</strong>
 * 出哪一部分命中了缓存。把它加回去等于把缓存 token 数了两遍 ——
 * 症状是缓存命中率永远小于真实值，且输入量虚高。
 *
 * <p>这一点已在调研中核对过 new-api 与 sub2api 的实现，两者都是直接搬。
 *
 * <h2>{@code reasoning_tokens} 不单独提取</h2>
 * Responses 在 {@code output_tokens_details.reasoning_tokens} 里给出思考消耗，
 * 但它<strong>已包含在 {@code output_tokens} 内</strong>，与 {@code cached_tokens} 之于
 * {@code input_tokens} 是同一种「细分标注」关系。三个 token 列的口径是
 * 「总输入 / 总输出 / 其中命中」，思考量不属于任何一列，需要时 {@code usage_raw}
 * 保留了上游原文。
 *
 * <p>不为它加第四个成员的理由与 Anthropic 侧一致：{@link UsageTokens} 是多协议共用的
 * 输出契约，为某一协议的私有细分加成员会把协议细节漏给其余各侧（那边永远是 null）。
 *
 * <h2>null 与 0 的区分必须保留</h2>
 * 与另两侧同一约束：{@code null} 表示上游未提供该字段，{@code 0} 表示上游报告了真实零值。
 * 一律按<strong>字段存在性</strong>提取，绝不用 {@code asInt(0)} 兜底，
 * 否则会把两者抹平，毁掉缓存命中率的可解读性（前端靠它区分「—」与「0.0%」）。
 *
 * <h2>流式下 usage 只在终态事件里出现一次</h2>
 * 与 Anthropic 需要跨事件合并不同：Responses 的 usage 挂在
 * {@code response.completed} 的 {@code response.usage} 下，一次给全。
 * 因此本类<strong>不需要 {@code merge}</strong> —— 那个方法在 Anthropic 侧存在，
 * 是因为它的输入与输出分散在 {@code message_start} 与 {@code message_delta} 两个事件。
 *
 * <p>但仍要兼容「非终态事件也带 usage」的上游：{@link #extractUsageRawJson} 同时看顶层
 * 与 {@code response.usage} 两处，调用方按「最后一份非 null」处理即可，不必挑选。
 */
public final class ResponsesUsageParser {

    private ResponsesUsageParser() {
    }

    /**
     * 从一个 Responses 事件或完整响应体中提取 usage 对象的原始 JSON。
     *
     * <p>两处都要看，因为形态不同：
     * <ul>
     *   <li>非流式响应体的 usage 在<strong>顶层</strong>；</li>
     *   <li>{@code response.completed} 事件的 usage 嵌在 {@code response.usage} 下。</li>
     * </ul>
     *
     * <p>顺序是先顶层后嵌套，与 {@code AnthropicUsageParser.locateUsage} 同一理由：
     * 顶层命中即返回，不必再往下找。
     *
     * @return usage 对象的原始 JSON；无 usage 或解析失败返回 null
     */
    public static String extractUsageRawJson(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode usage = locateUsage(objectMapper.readTree(json));
            return usage == null ? null : objectMapper.writeValueAsString(usage);
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 解析一个 Responses usage 对象的 JSON 为归一化的 token 指标。
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

    /** 定位 usage 节点：先看顶层（非流式响应体），再看 {@code response.usage}（流式终态事件）。 */
    private static JsonNode locateUsage(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode topLevel = root.get("usage");
        if (topLevel != null && topLevel.isObject()) {
            return topLevel;
        }
        JsonNode response = root.get("response");
        if (response != null && response.isObject()) {
            JsonNode nested = response.get("usage");
            if (nested != null && nested.isObject()) {
                return nested;
            }
        }
        return null;
    }

    /**
     * 把 Responses usage 对象映射为归一口径的指标。
     *
     * <p>三项<strong>直接搬</strong>，不做任何加减 —— 理由见类注释。
     * {@code cached_tokens} 藏在 {@code input_tokens_details} 下，缺失则为 null。
     */
    private static UsageTokens toTokens(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return UsageTokens.EMPTY;
        }
        return new UsageTokens(
                intIfPresent(usage, "input_tokens"),
                intIfPresent(usage, "output_tokens"),
                cachedTokens(usage));
    }

    /**
     * 提取缓存命中 token。
     *
     * <p>官方位置是 {@code input_tokens_details.cached_tokens}。<strong>同时兼容顶层
     * {@code cached_tokens}</strong>：部分中转站把它平铺到 usage 顶层，
     * 而漏读的症状是缓存命中率恒显示「—」—— 一个不报错、只是信息缺失的形态，
     * 极难被发现。多看一处的成本是一行代码。
     *
     * <p>不照抄 Chat 侧那条 8+1 项的 fallback 链：那些字段名（{@code cache_read_input_tokens}、
     * {@code prompt_cache_hit_tokens} 等）是 Chat 兼容生态的产物，在 Responses 端点上
     * 尚无一例实证。凭空加上等于给一堆无从验证的路径背书 —— 真遇到时再按实证补。
     */
    private static Integer cachedTokens(JsonNode usage) {
        JsonNode details = usage.get("input_tokens_details");
        if (details != null && details.isObject()) {
            Integer cached = intIfPresent(details, "cached_tokens");
            if (cached != null) {
                return cached;
            }
        }
        return intIfPresent(usage, "cached_tokens");
    }

    /** 仅当字段存在且为整数时返回值，否则 null —— 保住 null 与 0 的区分。 */
    private static Integer intIfPresent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : null;
    }
}

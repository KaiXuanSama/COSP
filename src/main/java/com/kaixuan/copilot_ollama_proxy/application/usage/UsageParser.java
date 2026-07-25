package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 上游 usage 对象解析器 —— 子项 A 的唯一 usage 解析实现。
 *
 * <p>非流式响应体、流式尾 chunk 与日聚合收集三处共用本解析器，避免多套解析逻辑漂移，
 * fallback 链只维护一份。
 *
 * <h2>null vs 0 语义（核心约束）</h2>
 * 所有字段一律按"存在性"取值：字段存在则取其值（哪怕是 0），字段缺失则为 {@code null}。
 * 绝不使用 {@code asInt(0)}，因为那会把"上游未提供"与"上游报告了 0"抹平，
 * 而这对"缓存命中率"至关重要（null 显示"—/不支持"，0 显示真实 0%）。
 *
 * <h2>cached_tokens fallback 链（实证自 8+1 个供应商样本）</h2>
 * 按下述优先级取"第一个存在的字段"：
 * <ol>
 *   <li>{@code prompt_tokens_details.cached_tokens} —— OpenAI 标准嵌套位，覆盖多数供应商与 DeepSeek。</li>
 *   <li>{@code prompt_cache_hit_tokens} —— DeepSeek 原生/第三方托管保险（DeepSeek 现双写且相等）。</li>
 *   <li>{@code effectiveCachedTokens} —— 某供应商私有位。</li>
 *   <li>全都不存在 → {@code null}。</li>
 * </ol>
 * <b>顶层 {@code cached_tokens} 永不参与</b>——实证样本证明它恒为 0 且不可信（真实命中藏在嵌套/私有位）。
 */
public final class UsageParser {

    private UsageParser() {
    }

    /**
     * 从一段可能包含 {@code usage} 字段的 JSON 文本解析 token 指标。
     *
     * <p>用于非流式响应体、流式尾 chunk。文本非法或不含 usage 对象时返回 {@link UsageTokens#EMPTY}。
     *
     * @param objectMapper Jackson 映射器
     * @param json         含 {@code usage} 的 JSON 文本；可为 null / 非法 / 无 usage
     * @return 解析出的 token 指标；无法解析时返回 {@link UsageTokens#EMPTY}
     */
    public static UsageTokens parseFromJson(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return UsageTokens.EMPTY;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            return parse(root.path("usage"));
        } catch (Exception e) {
            return UsageTokens.EMPTY;
        }
    }

    /**
     * 从已解析出的 {@code usage} 节点提取 token 指标。
     *
     * @param usage {@code usage} 节点；非对象（含 missing / null）时返回 {@link UsageTokens#EMPTY}
     * @return 解析出的 token 指标
     */
    public static UsageTokens parse(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return UsageTokens.EMPTY;
        }
        Integer prompt = intIfPresent(usage, "prompt_tokens");
        Integer completion = intIfPresent(usage, "completion_tokens");
        Integer cached = resolveCachedTokens(usage);
        return new UsageTokens(prompt, completion, cached);
    }

    /**
     * 按 fallback 链解析缓存命中 token，按存在性取第一个存在的字段。
     */
    private static Integer resolveCachedTokens(JsonNode usage) {
        // 1. OpenAI 标准嵌套位
        JsonNode promptDetails = usage.get("prompt_tokens_details");
        if (promptDetails != null && promptDetails.isObject()) {
            Integer nested = intIfPresent(promptDetails, "cached_tokens");
            if (nested != null) {
                return nested;
            }
        }
        // 2. DeepSeek 原生/第三方托管保险
        Integer deepseekHit = intIfPresent(usage, "prompt_cache_hit_tokens");
        if (deepseekHit != null) {
            return deepseekHit;
        }
        // 3. 私有位
        Integer effective = intIfPresent(usage, "effectiveCachedTokens");
        if (effective != null) {
            return effective;
        }
        // 4. 全缺失 → null（顶层 cached_tokens 永不参与）
        return null;
    }

    /**
     * 仅当字段存在且为数值时返回其 int 值，否则返回 null。
     *
     * <p>这是 null/0 区分的技术根基：用 {@link JsonNode#has(String)} 判断存在性，
     * 而非 {@code asInt(0)}（后者会把缺失与 0 抹平）。
     */
    private static Integer intIfPresent(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.asInt();
    }
}

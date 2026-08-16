package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段二验证与锁定：usage 解析器覆盖 8+1 个真实供应商样本。
 *
 * <p>核心断言 null vs 0 语义与 cached_tokens fallback 链：
 * <ul>
 *   <li>字段存在（哪怕 0）→ 取值；字段缺失 → null。</li>
 *   <li>cached 按存在性优先级链取：标准嵌套位 → DeepSeek 方言 → 私有位 → null。</li>
 *   <li>顶层 cached_tokens 永不参与（恒为 0 且不可信）。</li>
 * </ul>
 */
class OpenAiUsageParserTests {

    private final ObjectMapper mapper = new ObjectMapper();

    private UsageTokens parse(String usageJson) {
        return OpenAiUsageParser.parseFromJson(mapper, "{\"usage\":" + usageJson + "}");
    }

    // ==================== 样本 1：标准嵌套 cached + cache_creation ====================
    @Test
    void sample1_standardNestedCached() {
        UsageTokens t = parse("""
                {"prompt_tokens":206925,"completion_tokens":68,"total_tokens":206993,
                 "prompt_tokens_details":{"cached_tokens":100735,"cache_creation_tokens":161}}""");
        assertThat(t.promptTokens()).isEqualTo(206925);
        assertThat(t.completionTokens()).isEqualTo(68);
        assertThat(t.cachedTokens()).isEqualTo(100735);
    }

    // ==================== 样本 2：嵌套 cached（无 details 其它字段） ====================
    @Test
    void sample2_nestedCachedOnly() {
        UsageTokens t = parse("""
                {"completion_tokens":364,"prompt_tokens":28229,"total_tokens":28593,
                 "completion_tokens_details":{"reasoning_tokens":15},
                 "prompt_tokens_details":{"cached_tokens":27840}}""");
        assertThat(t.promptTokens()).isEqualTo(28229);
        assertThat(t.completionTokens()).isEqualTo(364);
        assertThat(t.cachedTokens()).isEqualTo(27840);
    }

    // ==================== 样本 3/4/5：仅三个根字段，无缓存信息 → cached null ====================
    @Test
    void sample3_noCacheInfo_cachedNull() {
        UsageTokens t = parse("""
                {"prompt_tokens":59521,"completion_tokens":74,"total_tokens":59595}""");
        assertThat(t.promptTokens()).isEqualTo(59521);
        assertThat(t.completionTokens()).isEqualTo(74);
        assertThat(t.cachedTokens()).isNull();
    }

    @Test
    void sample4_noCacheInfo_cachedNull() {
        UsageTokens t = parse("""
                {"prompt_tokens":21399,"completion_tokens":107,"total_tokens":21506}""");
        assertThat(t.cachedTokens()).isNull();
    }

    @Test
    void sample5_noCacheInfo_cachedNull() {
        UsageTokens t = parse("""
                {"prompt_tokens":216350,"completion_tokens":207,"total_tokens":216557}""");
        assertThat(t.cachedTokens()).isNull();
    }

    // ==================== 样本 6：DeepSeek 旧样本，嵌套 cached=0 但有方言字段 ====================
    @Test
    void sample6_nestedZeroTakesPrecedence() {
        // prompt_tokens_details.cached_tokens=0 存在 → 取 0（真实 0%），不落到方言字段
        UsageTokens t = parse("""
                {"prompt_tokens":73656,"completion_tokens":3567,"total_tokens":77223,
                 "completion_tokens_details":{"reasoning_tokens":2458,"cached_tokens":0},
                 "prompt_tokens_details":{"cached_tokens":0},
                 "prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":73656,"cached_tokens":0}""");
        assertThat(t.promptTokens()).isEqualTo(73656);
        assertThat(t.completionTokens()).isEqualTo(3567);
        assertThat(t.cachedTokens()).isEqualTo(0); // 真实 0%，非 null
    }

    // ==================== 样本 7：顶层 cached=0 是陷阱，无嵌套位 → cached null ====================
    @Test
    void sample7_topLevelCachedIsTrap_ignored() {
        // 顶层 input/output/cached 全是摆设，无嵌套/方言/私有位 → cached null（不被顶层 0 污染成 0）
        UsageTokens t = parse("""
                {"prompt_tokens":8877,"completion_tokens":277,"total_tokens":9154,
                 "prompt_tokens_details":{"text_tokens":0,"audio_tokens":0,"image_tokens":0},
                 "completion_tokens_details":{"text_tokens":0,"audio_tokens":0,"reasoning_tokens":0},
                 "input_tokens":0,"output_tokens":0,"input_tokens_details":null}""");
        assertThat(t.promptTokens()).isEqualTo(8877);
        assertThat(t.completionTokens()).isEqualTo(277);
        // 嵌套 prompt_tokens_details 存在但无 cached_tokens 键 → 继续 fallback → 全缺失 → null
        assertThat(t.cachedTokens()).isNull();
    }

    // ==================== 样本 8：顶层 cached=0 假，真实命中藏在嵌套/私有位 ====================
    @Test
    void sample8_nestedNotPollutedByTopLevelZero() {
        UsageTokens t = parse("""
                {"effectiveCachedTokens":25088,"completion_tokens":461,"prompt_tokens":25527,"total_tokens":25988,
                 "completion_tokens_details":{"reasoning_tokens":31},
                 "prompt_tokens_details":{"cached_tokens":25088,"audio_tokens":0,"image_tokens":0,"video_tokens":0,"text_tokens":0},
                 "cache_write_tokens":0,"cache_read_tokens":0,"input_tokens":0,"output_tokens":0,"cached_tokens":0}""");
        assertThat(t.promptTokens()).isEqualTo(25527);
        assertThat(t.completionTokens()).isEqualTo(461);
        // 标准嵌套位 25088 优先，不被顶层假 0 污染
        assertThat(t.cachedTokens()).isEqualTo(25088);
    }

    // ==================== 样本 9：真 DeepSeek，双写且相等，取标准位 ====================
    @Test
    void sample9_deepseekDualWriteTakesStandardPosition() {
        UsageTokens t = parse("""
                {"prompt_tokens":26349,"completion_tokens":348,"total_tokens":26697,
                 "prompt_tokens_details":{"cached_tokens":25856},
                 "completion_tokens_details":{"reasoning_tokens":35},
                 "prompt_cache_hit_tokens":25856,"prompt_cache_miss_tokens":493}""");
        assertThat(t.promptTokens()).isEqualTo(26349);
        assertThat(t.completionTokens()).isEqualTo(348);
        assertThat(t.cachedTokens()).isEqualTo(25856);
    }

    // ==================== fallback 链：仅 DeepSeek 方言（无标准嵌套位） ====================
    @Test
    void deepseekDialectOnly_usesPromptCacheHitTokens() {
        UsageTokens t = parse("""
                {"prompt_tokens":1000,"completion_tokens":50,
                 "prompt_cache_hit_tokens":800,"prompt_cache_miss_tokens":200}""");
        assertThat(t.cachedTokens()).isEqualTo(800);
    }

    // ==================== fallback 链：仅私有位 effectiveCachedTokens ====================
    @Test
    void privateFieldOnly_usesEffectiveCachedTokens() {
        UsageTokens t = parse("""
                {"prompt_tokens":1000,"completion_tokens":50,"effectiveCachedTokens":600}""");
        assertThat(t.cachedTokens()).isEqualTo(600);
    }

    // ==================== fallback 链：顶层 cached_tokens 永不参与 ====================
    @Test
    void topLevelCachedTokensNeverParticipates() {
        // 只有顶层 cached_tokens，无任何合法位 → null（不取顶层）
        UsageTokens t = parse("""
                {"prompt_tokens":1000,"completion_tokens":50,"cached_tokens":999}""");
        assertThat(t.cachedTokens()).isNull();
    }

    // ==================== 边界：prompt/completion 缺失 → null ====================
    @Test
    void missingRootTokens_areNull() {
        UsageTokens t = parse("{}");
        assertThat(t.promptTokens()).isNull();
        assertThat(t.completionTokens()).isNull();
        assertThat(t.cachedTokens()).isNull();
        assertThat(t.isEmpty()).isTrue();
    }

    // ==================== 边界：prompt=0 是真实 0，非 null ====================
    @Test
    void zeroRootTokens_arePreservedNotNull() {
        UsageTokens t = parse("{\"prompt_tokens\":0,\"completion_tokens\":0}");
        assertThat(t.promptTokens()).isEqualTo(0);
        assertThat(t.completionTokens()).isEqualTo(0);
        assertThat(t.isEmpty()).isFalse();
    }

    // ==================== 边界：非法 JSON / null / 空串 → EMPTY ====================
    @Test
    void invalidOrMissingUsage_returnsEmpty() {
        assertThat(OpenAiUsageParser.parseFromJson(mapper, null).isEmpty()).isTrue();
        assertThat(OpenAiUsageParser.parseFromJson(mapper, "").isEmpty()).isTrue();
        assertThat(OpenAiUsageParser.parseFromJson(mapper, "[DONE]").isEmpty()).isTrue();
        assertThat(OpenAiUsageParser.parseFromJson(mapper, "not json").isEmpty()).isTrue();
        assertThat(OpenAiUsageParser.parseFromJson(mapper, "{\"choices\":[]}").isEmpty()).isTrue();
    }

    // ==================== 边界：usage 为 null 字面量 → EMPTY ====================
    @Test
    void usageNullLiteral_returnsEmpty() {
        assertThat(OpenAiUsageParser.parseFromJson(mapper, "{\"usage\":null}").isEmpty()).isTrue();
    }

    // ==================== 边界：cached_tokens 为 null 字面量 → 视为缺失，继续 fallback ====================
    @Test
    void nestedCachedNullLiteral_fallsThrough() {
        UsageTokens t = parse("""
                {"prompt_tokens":100,"completion_tokens":10,
                 "prompt_tokens_details":{"cached_tokens":null},
                 "prompt_cache_hit_tokens":42}""");
        // 嵌套 cached_tokens 是 null 字面量 → 视为缺失 → fallback 到方言 42
        assertThat(t.cachedTokens()).isEqualTo(42);
    }

    // ==================== promptOrZero/completionOrZero：缺失记 0（日聚合兼容） ====================
    @Test
    void orZeroHelpers_treatNullAsZero() {
        UsageTokens empty = UsageTokens.EMPTY;
        assertThat(empty.promptOrZero()).isEqualTo(0);
        assertThat(empty.completionOrZero()).isEqualTo(0);

        UsageTokens partial = new UsageTokens(50, null, null);
        assertThat(partial.promptOrZero()).isEqualTo(50);
        assertThat(partial.completionOrZero()).isEqualTo(0);
    }
}

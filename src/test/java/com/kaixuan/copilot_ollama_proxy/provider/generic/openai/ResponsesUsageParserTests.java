package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Responses usage 解析。
 *
 * <h2>本类最重要的一组断言：换算是「直接搬」，不做加法</h2>
 * Anthropic 侧必须 {@code input + cache_read + cache_creation}，因为它把输入拆成三个
 * <strong>互斥</strong>的量。OpenAI 系不拆：{@code input_tokens} 已经是全部输入，
 * {@code cached_tokens} 只是从其中标注出哪一部分命中了缓存。
 *
 * <p>照抄那个加法的症状很隐蔽 —— 不报错，只是输入量虚高、缓存命中率恒低于真实值。
 * 因此这里用一个「输入含缓存」的样本显式钉住：{@code input=100, cached=80} 必须得到
 * {@code prompt=100}（而非 180）。
 */
class ResponsesUsageParserTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 换算口径 ====================

    /**
     * 三项直接搬，{@code cached_tokens} <strong>不</strong>加进 {@code prompt_tokens}。
     *
     * <p>这是本类存在的首要理由。若有人照 Anthropic 侧的形状改成相加，这条会立刻失败。
     */
    @Test
    void 缓存命中不重复计入总输入() {
        UsageTokens tokens = ResponsesUsageParser.parseUsageObject(objectMapper, """
                {"input_tokens":100,"output_tokens":20,
                 "input_tokens_details":{"cached_tokens":80}}
                """);

        assertThat(tokens.promptTokens()).isEqualTo(100);
        assertThat(tokens.completionTokens()).isEqualTo(20);
        assertThat(tokens.cachedTokens()).isEqualTo(80);
    }

    /** 全命中的极端情形：命中数等于输入数，命中率 100% 而非 50%。 */
    @Test
    void 全部命中时命中数等于输入数() {
        UsageTokens tokens = ResponsesUsageParser.parseUsageObject(objectMapper, """
                {"input_tokens":50,"output_tokens":5,
                 "input_tokens_details":{"cached_tokens":50}}
                """);

        assertThat(tokens.promptTokens()).isEqualTo(50);
        assertThat(tokens.cachedTokens()).isEqualTo(50);
    }

    /**
     * {@code reasoning_tokens} 不单独提取，也不从 {@code output_tokens} 里减掉。
     *
     * <p>它已包含在 {@code output_tokens} 内，与 {@code cached_tokens} 之于
     * {@code input_tokens} 是同一种「细分标注」关系。减掉会让输出量少于真实值。
     */
    @Test
    void 思考token不影响输出总量() {
        UsageTokens tokens = ResponsesUsageParser.parseUsageObject(objectMapper, """
                {"input_tokens":10,"output_tokens":100,
                 "output_tokens_details":{"reasoning_tokens":70}}
                """);

        assertThat(tokens.completionTokens()).isEqualTo(100);
    }

    // ==================== null 与 0 的区分 ====================

    /**
     * 缺失字段保持 {@code null}，不兜底成 0。
     *
     * <p>前端靠这个区分「—」与「0.0%」：{@code null} 表示上游没说，{@code 0} 表示上游
     * 说了零。用 {@code asInt(0)} 兜底会把两者抹平，毁掉缓存命中率的可解读性。
     */
    @Test
    void 缺失的缓存字段保持null() {
        UsageTokens tokens = ResponsesUsageParser.parseUsageObject(objectMapper,
                "{\"input_tokens\":10,\"output_tokens\":5}");

        assertThat(tokens.promptTokens()).isEqualTo(10);
        assertThat(tokens.cachedTokens()).isNull();
    }

    /** 上游明确报告 0 时落 0，与「没报告」区分开。 */
    @Test
    void 显式零值落零而非null() {
        UsageTokens tokens = ResponsesUsageParser.parseUsageObject(objectMapper, """
                {"input_tokens":10,"output_tokens":5,
                 "input_tokens_details":{"cached_tokens":0}}
                """);

        assertThat(tokens.cachedTokens()).isZero();
    }

    /** 非数字字段视为缺失 —— 某些上游把 token 数写成字符串。 */
    @Test
    void 非数字字段视为缺失() {
        UsageTokens tokens = ResponsesUsageParser.parseUsageObject(objectMapper,
                "{\"input_tokens\":\"100\",\"output_tokens\":5}");

        assertThat(tokens.promptTokens()).isNull();
        assertThat(tokens.completionTokens()).isEqualTo(5);
    }

    // ==================== 顶层 cached_tokens 兼容 ====================

    /**
     * 部分中转站把 {@code cached_tokens} 平铺到 usage 顶层。
     *
     * <p>漏读的症状是缓存命中率恒显示「—」—— 一个不报错、只是信息缺失的形态，
     * 极难被发现。多看一处的成本是一行代码。
     */
    @Test
    void 顶层cached_tokens也被识别() {
        UsageTokens tokens = ResponsesUsageParser.parseUsageObject(objectMapper,
                "{\"input_tokens\":100,\"output_tokens\":20,\"cached_tokens\":30}");

        assertThat(tokens.promptTokens()).isEqualTo(100);
        assertThat(tokens.cachedTokens()).isEqualTo(30);
    }

    /** 两处都有时优先官方位置（details）—— 那是规范定义的地方。 */
    @Test
    void 官方位置优先于顶层() {
        UsageTokens tokens = ResponsesUsageParser.parseUsageObject(objectMapper, """
                {"input_tokens":100,"output_tokens":20,"cached_tokens":1,
                 "input_tokens_details":{"cached_tokens":80}}
                """);

        assertThat(tokens.cachedTokens()).isEqualTo(80);
    }

    // ==================== 原文提取 ====================

    /** 非流式响应体的 usage 在顶层。 */
    @Test
    void 从非流式响应体提取usage原文() {
        String raw = ResponsesUsageParser.extractUsageRawJson(objectMapper, """
                {"id":"resp_1","output":[],"usage":{"input_tokens":10,"output_tokens":5}}
                """);

        assertThat(raw).isNotNull();
        assertThat(ResponsesUsageParser.parseUsageObject(objectMapper, raw).promptTokens())
                .isEqualTo(10);
    }

    /**
     * 流式终态事件的 usage 嵌在 {@code response.usage} 下。
     *
     * <p>两处形态不同但都要认，与 {@code AnthropicUsageParser.locateUsage} 同一处境
     * （那边是 {@code message_start} 的 {@code message.usage} 与 {@code message_delta} 的顶层）。
     */
    @Test
    void 从终态事件提取嵌套usage原文() {
        String raw = ResponsesUsageParser.extractUsageRawJson(objectMapper, """
                {"type":"response.completed","response":{"id":"resp_1",
                 "usage":{"input_tokens":42,"output_tokens":7}}}
                """);

        assertThat(raw).isNotNull();
        assertThat(ResponsesUsageParser.parseUsageObject(objectMapper, raw).promptTokens())
                .isEqualTo(42);
    }

    /** 无 usage 的事件返回 null，调用方据此跳过。 */
    @Test
    void 无usage返回null() {
        assertThat(ResponsesUsageParser.extractUsageRawJson(objectMapper,
                "{\"type\":\"response.output_text.delta\",\"delta\":\"x\"}")).isNull();
        assertThat(ResponsesUsageParser.extractUsageRawJson(objectMapper,
                "{\"type\":\"response.created\",\"response\":{\"id\":\"r\"}}")).isNull();
    }

    @Test
    void 空输入与非法JSON返回null() {
        assertThat(ResponsesUsageParser.extractUsageRawJson(objectMapper, null)).isNull();
        assertThat(ResponsesUsageParser.extractUsageRawJson(objectMapper, "  ")).isNull();
        assertThat(ResponsesUsageParser.extractUsageRawJson(objectMapper, "{broken")).isNull();
    }

    /** 解析空输入得到 EMPTY 而非抛错 —— 落库层据 {@code isEmpty()} 决定是否写行。 */
    @Test
    void 解析空输入得到EMPTY() {
        assertThat(ResponsesUsageParser.parseUsageObject(objectMapper, null)).isEqualTo(UsageTokens.EMPTY);
        assertThat(ResponsesUsageParser.parseUsageObject(objectMapper, "  ")).isEqualTo(UsageTokens.EMPTY);
        assertThat(ResponsesUsageParser.parseUsageObject(objectMapper, "{broken"))
                .isEqualTo(UsageTokens.EMPTY);
        // 合法 JSON 但不是对象：同样落 EMPTY，不抛错。
        assertThat(ResponsesUsageParser.parseUsageObject(objectMapper, "[]"))
                .isEqualTo(UsageTokens.EMPTY);
    }
}

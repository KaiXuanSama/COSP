package com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anthropic 的载荷判定与 usage 解析。
 *
 * <p>这两个组件是纯函数，不需要 HTTP stub，故与服务本身的测试分开 ——
 * 它们承载的是「口径」，值得独立且穷举地锁定。
 */
class AnthropicPayloadAndUsageTests {

    private final ObjectMapper mapper = new ObjectMapper();

    @Nested
    class 非流式载荷判定 {

        @Test
        void 有正文的响应算实质载荷() {
            String body = """
                    {"id":"msg_1","type":"message","role":"assistant",
                     "content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn"}""";

            assertThat(AnthropicContentDetector.hasMeaningfulPayload(mapper, body)).isTrue();
        }

        /** 与 OpenAI 侧 content 的口径一致：空串不算内容。 */
        @Test
        void 空正文不算实质载荷() {
            String body = """
                    {"id":"msg_1","content":[{"type":"text","text":""}],"stop_reason":"end_turn"}""";

            assertThat(AnthropicContentDetector.hasMeaningfulPayload(mapper, body)).isFalse();
        }

        /** 对照组：思考链是实质载荷，不该被判空。 */
        @Test
        void 只有思考链也算实质载荷() {
            String body = """
                    {"id":"msg_1","content":[{"type":"thinking","thinking":"让我想想"}],
                     "stop_reason":"end_turn"}""";

            assertThat(AnthropicContentDetector.hasMeaningfulPayload(mapper, body)).isTrue();
        }

        /**
         * 加密的思考链同样算 —— 读不到明文不等于模型没思考。
         */
        @Test
        void 加密思考链也算实质载荷() {
            String body = """
                    {"id":"msg_1","content":[{"type":"redacted_thinking","data":"xxx"}]}""";

            assertThat(AnthropicContentDetector.hasMeaningfulPayload(mapper, body)).isTrue();
        }

        /** 对照组：工具调用是实质载荷。 */
        @Test
        void 只有工具调用也算实质载荷() {
            String body = """
                    {"id":"msg_1","content":[{"type":"tool_use","id":"t1","name":"get_weather",
                     "input":{}}],"stop_reason":"tool_use"}""";

            assertThat(AnthropicContentDetector.hasMeaningfulPayload(mapper, body)).isTrue();
        }

        @Test
        void 空content数组判空() {
            assertThat(AnthropicContentDetector.hasMeaningfulPayload(mapper,
                    "{\"id\":\"msg_1\",\"content\":[]}")).isFalse();
        }

        @Test
        void 缺失content字段判空() {
            assertThat(AnthropicContentDetector.hasMeaningfulPayload(mapper,
                    "{\"id\":\"msg_1\",\"type\":\"message\"}")).isFalse();
        }

        /** 空 body 比流式的「0 事件」更极端 —— 连 JSON 骨架都没有。 */
        @Test
        void 空body判空() {
            assertThat(AnthropicContentDetector.hasMeaningfulPayload(mapper, "")).isFalse();
            assertThat(AnthropicContentDetector.hasMeaningfulPayload(mapper, null)).isFalse();
        }

        /**
         * 解析失败保守放行 —— 与 OpenAI 侧同一取向。
         *
         * <p>宁可放行一个没见过的格式，也不要因为结构陌生就把正常响应判成空并重试。
         */
        @Test
        void 残缺JSON保守放行() {
            assertThat(AnthropicContentDetector.hasMeaningfulPayload(mapper,
                    "{\"id\":\"msg_1\",\"content\":[{\"type\":\"te")).isTrue();
        }
    }

    @Nested
    class 流式事件载荷判定 {

        /**
         * 控制事件不贡献载荷，但这<strong>不代表</strong>整轮为空。
         *
         * <p>正常响应的头两个事件都是控制事件，若逐事件判空就会误伤。
         * 调用方需在一轮内做逻辑或。
         */
        @Test
        void 控制事件不贡献载荷() {
            assertThat(AnthropicContentDetector.eventHasPayload(mapper,
                    "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\"}}")).isFalse();
            assertThat(AnthropicContentDetector.eventHasPayload(mapper,
                    "{\"type\":\"content_block_stop\",\"index\":0}")).isFalse();
            assertThat(AnthropicContentDetector.eventHasPayload(mapper,
                    "{\"type\":\"message_stop\"}")).isFalse();
            assertThat(AnthropicContentDetector.eventHasPayload(mapper,
                    "{\"type\":\"ping\"}")).isFalse();
        }

        @Test
        void 正文增量贡献载荷() {
            assertThat(AnthropicContentDetector.eventHasPayload(mapper,
                    """
                    {"type":"content_block_delta","index":0,
                     "delta":{"type":"text_delta","text":"hi"}}""")).isTrue();
        }

        @Test
        void 空正文增量不贡献载荷() {
            assertThat(AnthropicContentDetector.eventHasPayload(mapper,
                    """
                    {"type":"content_block_delta","index":0,
                     "delta":{"type":"text_delta","text":""}}""")).isFalse();
        }

        @Test
        void 思考链增量贡献载荷() {
            assertThat(AnthropicContentDetector.eventHasPayload(mapper,
                    """
                    {"type":"content_block_delta","index":0,
                     "delta":{"type":"thinking_delta","thinking":"嗯"}}""")).isTrue();
        }

        /** 工具调用在 block 声明时即算载荷，无需等参数增量。 */
        @Test
        void 工具调用block声明即贡献载荷() {
            assertThat(AnthropicContentDetector.eventHasPayload(mapper,
                    """
                    {"type":"content_block_start","index":0,
                     "content_block":{"type":"tool_use","id":"t1","name":"get_weather","input":{}}}"""))
                    .isTrue();
        }

        /** signature_delta 是思考链的完整性签名，不是内容本身。 */
        @Test
        void 签名增量不算内容() {
            assertThat(AnthropicContentDetector.eventHasPayload(mapper,
                    """
                    {"type":"content_block_delta","index":0,
                     "delta":{"type":"signature_delta","signature":"abc"}}""")).isFalse();
        }

        @Test
        void 未知事件类型保守放行() {
            assertThat(AnthropicContentDetector.eventHasPayload(mapper, "{\"type\":\"te")).isTrue();
        }
    }

    @Nested
    class Usage解析 {

        /**
         * 字段名与 OpenAI 全然不同，但输出契约与<strong>口径</strong>都相同。
         *
         * <p>{@code promptTokens} 是 {@code input_tokens + cache_read_input_tokens} ——
         * Anthropic 把缓存读取排在 {@code input_tokens} 之外，而本列的口径是「总输入含缓存」。
         */
        @Test
        void 非流式响应的顶层usage() {
            String body = """
                    {"id":"msg_1","content":[{"type":"text","text":"hi"}],
                     "usage":{"input_tokens":100,"output_tokens":20,
                              "cache_read_input_tokens":80,"cache_creation_input_tokens":5}}""";

            String raw = AnthropicUsageParser.extractUsageRawJson(mapper, body);
            UsageTokens tokens = AnthropicUsageParser.parseUsageObject(mapper, raw);

            // 100 + 80；cache_creation 不参与（已知精度损失）。
            assertThat(tokens.promptTokens()).isEqualTo(180);
            assertThat(tokens.completionTokens()).isEqualTo(20);
            assertThat(tokens.cachedTokens()).isEqualTo(80);
        }

        /**
         * 归一口径使缓存占比必然落在 0~100%。
         *
         * <p>真实样本：{@code input_tokens} 55、{@code cache_read} 22528。
         * 不加回时前端算出 {@code 22528/55 = 40960%} —— 那正是本次修复的起因。
         */
        @Test
        void 缓存占比归一后可表达() {
            UsageTokens tokens = AnthropicUsageParser.parseUsageObject(mapper,
                    "{\"input_tokens\":55,\"output_tokens\":279,\"cache_read_input_tokens\":22528}");

            assertThat(tokens.promptTokens()).isEqualTo(22583);
            double rate = tokens.cachedTokens() / (double) tokens.promptTokens();
            assertThat(rate).isBetween(0.0, 1.0);
        }

        /**
         * 换算在解析层而非落库层，因此与下游是谁无关。
         *
         * <p>本类被无条件调用（Anthropic 直连与 A2O 共用），所以两条线路拿到的是
         * 同一个数。早期只在 A2O 上换算，于是一列承载两种定义，而汇总查询
         * 无法按行区分协议。这条用例钉住「只能换一次、且就在这里换」。
         */
        @Test
        void 换算只依赖上游协议而非下游() {
            String usage = "{\"input_tokens\":10,\"output_tokens\":5,\"cache_read_input_tokens\":3}";

            UsageTokens once = AnthropicUsageParser.parseUsageObject(mapper, usage);

            assertThat(once.promptTokens()).isEqualTo(13);
            // 在落库路径上再加一遍会得到 16，那是缓存被计两次。
            assertThat(once.promptTokens() + once.cachedTokens()).isEqualTo(16);
        }

        /**
         * 缓存 token 只取 cache_read，不取 cache_creation。
         *
         * <p>后者是「本次写入缓存的量」，属成本项而非命中项，混入会让命中率虚高。
         * 它同样不计入 {@code promptTokens} —— {@link UsageTokens} 没有它的位置，
         * 因此发生缓存写入时落库值略低于真实总输入。
         */
        @Test
        void 缓存写入量既不计命中也不计输入() {
            String usage = """
                    {"input_tokens":100,"output_tokens":10,"cache_creation_input_tokens":50}""";

            UsageTokens tokens = AnthropicUsageParser.parseUsageObject(mapper, usage);

            assertThat(tokens.cachedTokens()).isNull();
            assertThat(tokens.promptTokens()).isEqualTo(100);
        }

        /** message_start 的 usage 嵌在 message 下。 */
        @Test
        void 流式起始事件的嵌套usage() {
            String event = """
                    {"type":"message_start","message":{"id":"msg_1",
                     "usage":{"input_tokens":57,"output_tokens":0}}}""";

            UsageTokens tokens = AnthropicUsageParser.parseUsageObject(mapper,
                    AnthropicUsageParser.extractUsageRawJson(mapper, event));

            assertThat(tokens.promptTokens()).isEqualTo(57);
            assertThat(tokens.completionTokens()).isZero();
        }

        /**
         * 跨事件合并：输入来自 message_start、输出来自 message_delta。
         *
         * <p>这是 Anthropic 流式必须做合并的原因 —— 只取最后一个事件会丢掉输入 token。
         */
        @Test
        void 跨事件合并输入与输出() {
            UsageTokens start = AnthropicUsageParser.parseUsageObject(mapper,
                    AnthropicUsageParser.extractUsageRawJson(mapper,
                            """
                            {"type":"message_start","message":{"usage":{"input_tokens":57}}}"""));
            UsageTokens delta = AnthropicUsageParser.parseUsageObject(mapper,
                    AnthropicUsageParser.extractUsageRawJson(mapper,
                            """
                            {"type":"message_delta","delta":{"stop_reason":"end_turn"},
                             "usage":{"output_tokens":142}}"""));

            UsageTokens merged = AnthropicUsageParser.merge(start, delta);

            assertThat(merged.promptTokens()).isEqualTo(57);
            assertThat(merged.completionTokens()).isEqualTo(142);
        }

        /**
         * 全零的尾事件不得抹掉已收到的真实数字。
         *
         * <p>实测缺陷：有的上游<strong>每个</strong>事件都带完整 usage，只有少数几个带真实
         * 数字，其余（含最后的 {@code message_stop}）全是 0。按「非 null 就覆盖」会让落库值
         * 全变成 0，而同一次调用的出站报文却是对的 —— 因为出站侧
         * （{@code AnthropicUsageAccumulator}）从一开始就只让正数覆盖。两套规则必须一致。
         */
        @Test
        void 全零尾事件不得抹掉已收到的真实数字() {
            UsageTokens real = AnthropicUsageParser.parseUsageObject(mapper,
                    "{\"input_tokens\":475935,\"output_tokens\":64}");
            UsageTokens allZero = AnthropicUsageParser.parseUsageObject(mapper,
                    """
                    {"input_tokens":0,"output_tokens":0,
                     "cache_creation_input_tokens":0,"cache_read_input_tokens":0}""");

            UsageTokens merged = AnthropicUsageParser.merge(real, allZero);

            assertThat(merged.promptTokens()).isEqualTo(475935);
            assertThat(merged.completionTokens()).isEqualTo(64);
        }

        /**
         * 但「上游确实报告了 0」仍要能落到 0，不能变回 null。
         *
         * <p>缓存占比靠这个区分「—」（无从计算）与「0.0%」（真实未命中）。
         */
        @Test
        void 上游报告的零在合并后仍是零而非null() {
            UsageTokens base = AnthropicUsageParser.parseUsageObject(mapper,
                    "{\"input_tokens\":100,\"cache_read_input_tokens\":0}");
            UsageTokens update = AnthropicUsageParser.parseUsageObject(mapper,
                    "{\"output_tokens\":20,\"cache_read_input_tokens\":0}");

            UsageTokens merged = AnthropicUsageParser.merge(base, update);

            assertThat(merged.cachedTokens()).isZero();
            assertThat(merged.promptTokens()).isEqualTo(100);
            assertThat(merged.completionTokens()).isEqualTo(20);
        }

        /**
         * 合并是覆盖而非相加 —— message_delta 的 output_tokens 是累计值。
         *
         * <p>若写成相加，多次 message_delta 会让输出 token 翻倍。
         */
        @Test
        void 合并采用覆盖语义而非累加() {
            UsageTokens first = new UsageTokens(57, 10, null);
            UsageTokens second = new UsageTokens(null, 142, null);

            UsageTokens merged = AnthropicUsageParser.merge(first, second);

            assertThat(merged.completionTokens()).isEqualTo(142);
            // 输入 token 只在 message_start 出现，后续事件缺失时不得被 null 覆盖。
            assertThat(merged.promptTokens()).isEqualTo(57);
        }

        /** null 与 0 的区分必须保留 —— 这对缓存命中率的解读至关重要。 */
        @Test
        void 缺失字段为null而非零() {
            UsageTokens tokens = AnthropicUsageParser.parseUsageObject(mapper,
                    "{\"input_tokens\":10}");

            assertThat(tokens.promptTokens()).isEqualTo(10);
            assertThat(tokens.completionTokens()).isNull();
            assertThat(tokens.cachedTokens()).isNull();
        }

        /**
         * 两个输入侧字段都缺失时 {@code promptTokens} 保持 null。
         *
         * <p>不能把两个 null 相加成 0 —— 那会造出「上游报告了 0 输入」的假象，
         * 而缓存占比靠这个区分区分「—」与「0.0%」。
         */
        @Test
        void 两个输入侧字段都缺失时保持null() {
            UsageTokens tokens = AnthropicUsageParser.parseUsageObject(mapper,
                    "{\"output_tokens\":20}");

            assertThat(tokens.promptTokens()).isNull();
            assertThat(tokens.completionTokens()).isEqualTo(20);
        }

        /** 只有缓存命中、没有新增输入时，总输入就是缓存量。 */
        @Test
        void 输入缺失但缓存有值时仍产出和值() {
            UsageTokens tokens = AnthropicUsageParser.parseUsageObject(mapper,
                    "{\"output_tokens\":20,\"cache_read_input_tokens\":300}");

            assertThat(tokens.promptTokens()).isEqualTo(300);
        }

        @Test
        void 上游报告的零值保持为零() {
            UsageTokens tokens = AnthropicUsageParser.parseUsageObject(mapper,
                    "{\"input_tokens\":10,\"cache_read_input_tokens\":0}");

            assertThat(tokens.cachedTokens()).isZero();
            // 0 缓存参与相加不改变总输入。
            assertThat(tokens.promptTokens()).isEqualTo(10);
        }

        @Test
        void 无usage时返回null() {
            assertThat(AnthropicUsageParser.extractUsageRawJson(mapper,
                    "{\"type\":\"content_block_stop\",\"index\":0}")).isNull();
            assertThat(AnthropicUsageParser.extractUsageRawJson(mapper, "")).isNull();
        }

        @Test
        void 残缺JSON不抛异常() {
            assertThat(AnthropicUsageParser.extractUsageRawJson(mapper, "{\"usage\":{\"inp")).isNull();
            assertThat(AnthropicUsageParser.parseUsageObject(mapper, "{\"inp")).isEqualTo(UsageTokens.EMPTY);
        }
    }
}

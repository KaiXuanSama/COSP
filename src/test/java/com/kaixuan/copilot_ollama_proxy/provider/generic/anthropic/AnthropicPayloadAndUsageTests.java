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

        /** 字段名与 OpenAI 全然不同，但输出契约相同。 */
        @Test
        void 非流式响应的顶层usage() {
            String body = """
                    {"id":"msg_1","content":[{"type":"text","text":"hi"}],
                     "usage":{"input_tokens":100,"output_tokens":20,
                              "cache_read_input_tokens":80,"cache_creation_input_tokens":5}}""";

            String raw = AnthropicUsageParser.extractUsageRawJson(mapper, body);
            UsageTokens tokens = AnthropicUsageParser.parseUsageObject(mapper, raw);

            assertThat(tokens.promptTokens()).isEqualTo(100);
            assertThat(tokens.completionTokens()).isEqualTo(20);
            assertThat(tokens.cachedTokens()).isEqualTo(80);
        }

        /**
         * 缓存 token 只取 cache_read，不取 cache_creation。
         *
         * <p>后者是「本次写入缓存的量」，属成本项而非命中项，混入会让命中率虚高。
         */
        @Test
        void 缓存写入量不计入缓存命中() {
            String usage = """
                    {"input_tokens":100,"output_tokens":10,"cache_creation_input_tokens":50}""";

            UsageTokens tokens = AnthropicUsageParser.parseUsageObject(mapper, usage);

            assertThat(tokens.cachedTokens()).isNull();
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

        @Test
        void 上游报告的零值保持为零() {
            UsageTokens tokens = AnthropicUsageParser.parseUsageObject(mapper,
                    "{\"input_tokens\":10,\"cache_read_input_tokens\":0}");

            assertThat(tokens.cachedTokens()).isZero();
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

package com.kaixuan.copilot_ollama_proxy.provider;

import com.kaixuan.copilot_ollama_proxy.application.protocol.translate.AnthropicToOpenAiResponseTranslator;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DownstreamLogView} 的两个改写器。
 *
 * <p>这个类的存在理由是「落库在上游服务内部、翻译在其外侧」这个结构事实：
 * 上游服务不知道翻译存在，因此「下游到底看到了什么」必须由外侧注入。
 * 本测试钉住两件事 —— 注入生效，以及<strong>改写失败时退回什么</strong>。
 * 后者容易被忽略，但那正是「日志是观测手段，不该因改写失败而丢证据」的落点。
 */
class DownstreamLogViewTests {

    @Nested
    @DisplayName("usage 改写：跨协议时落下游口径")
    class UsageRewrite {

        /**
         * 直连视图不改写：上下游口径本就一致，动它反而制造偏差。
         */
        @Test
        void directViewPassesUsageThroughUnchanged() {
            UsageTokens upstream = new UsageTokens(55, 279, 22528);

            UsageTokens viewed = DownstreamLogView.direct("ANTHROPIC").viewUsage(upstream);

            assertThat(viewed).isSameAs(upstream);
        }

        /**
         * A2O 非流式：只换算 usage、不改写 chunk。
         *
         * <p>非流式响应体是单一字符串，日志记上游原文比记翻译后的更有用
         * （后者可由前者推导，反之不行）；但 usage 那三列是跨协议共用的度量列，
         * 必须换算。这两件事的取舍相反，所以有 {@code usageOnly} 这个工厂。
         */
        @Test
        void usageOnlyViewTranslatesUsageButLeavesChunksRaw() {
            DownstreamLogView view = DownstreamLogView.usageOnly(
                    "OPENAI", AnthropicToOpenAiResponseTranslator::translateUsageForLog);

            UsageTokens viewed = view.viewUsage(new UsageTokens(55, 279, 22528));
            ChunkLogPayload chunks = view.viewChunks(List.of("{\"type\":\"ping\"}"));

            assertThat(viewed.promptTokens()).isEqualTo(22583);
            assertThat(view.downstreamProtocol()).isEqualTo("OPENAI");
            // chunk 侧仍是直连形态（裸数组，无上游/翻译两栏）。
            assertThat(chunks.hasUpstreamView()).isFalse();
        }

        /**
         * A2O 流式：两个改写器同时生效。
         */
        @Test
        void streamViewRewritesBothChunksAndUsage() {
            DownstreamLogView view = new DownstreamLogView(
                    "OPENAI",
                    chunks -> ChunkLogPayload.translated(
                            List.of("{\"object\":\"chat.completion.chunk\"}"), chunks, List.of(1)),
                    AnthropicToOpenAiResponseTranslator::translateUsageForLog);

            assertThat(view.viewUsage(new UsageTokens(55, 279, 22528)).promptTokens())
                    .isEqualTo(22583);
            assertThat(view.viewChunks(List.of("{\"type\":\"message_start\"}")).hasUpstreamView())
                    .isTrue();
        }

        /**
         * 改写器抛异常时退回上游原样，而不是丢掉整行用量。
         *
         * <p>取舍：记一个口径可疑的数 vs 让这次调用在概览页彻底消失。选前者 ——
         * 用量行缺失是不可逆的信息损失，而口径偏差至少还能从同一行的
         * {@code usage_raw} 里查证。
         */
        @Test
        void rewriterFailureFallsBackToUpstreamTokens() {
            UsageTokens upstream = new UsageTokens(55, 279, 22528);
            DownstreamLogView view = DownstreamLogView.usageOnly("OPENAI", tokens -> {
                throw new IllegalStateException("换算失败");
            });

            assertThat(view.viewUsage(upstream)).isSameAs(upstream);
        }

        /** 改写器返回 null 同样退回原样，不能把 null 写进落库路径。 */
        @Test
        void rewriterReturningNullFallsBackToUpstreamTokens() {
            UsageTokens upstream = new UsageTokens(55, 279, 22528);
            DownstreamLogView view = DownstreamLogView.usageOnly("OPENAI", tokens -> null);

            assertThat(view.viewUsage(upstream)).isSameAs(upstream);
        }

        /** null 入参归一化为 EMPTY，调用方不必先判空。 */
        @Test
        void nullUsageBecomesEmpty() {
            assertThat(DownstreamLogView.direct("ANTHROPIC").viewUsage(null))
                    .isEqualTo(UsageTokens.EMPTY);
            assertThat(DownstreamLogView.usageOnly("OPENAI",
                            AnthropicToOpenAiResponseTranslator::translateUsageForLog)
                    .viewUsage(null)).isEqualTo(UsageTokens.EMPTY);
        }
    }

    @Nested
    @DisplayName("chunk 改写的退化路径")
    class ChunkRewriteFallback {

        @Test
        void rewriterFailureKeepsUpstreamChunks() {
            List<String> upstream = List.of("{\"type\":\"message_start\"}", "{\"type\":\"ping\"}");
            DownstreamLogView view = new DownstreamLogView("OPENAI", chunks -> {
                throw new IllegalStateException("翻译失败");
            }, null);

            ChunkLogPayload payload = view.viewChunks(upstream);

            // 退回直连形态：至少保住「上游到底返回了什么」这个更基础的事实。
            assertThat(payload.hasUpstreamView()).isFalse();
            assertThat(payload.translated()).isEqualTo(upstream);
        }

        @Test
        void nullChunksAreHandledWithoutInvokingRewriter() {
            DownstreamLogView view = new DownstreamLogView("OPENAI", chunks -> {
                throw new AssertionError("不应被调用");
            }, null);

            assertThat(view.viewChunks(null).hasUpstreamView()).isFalse();
        }
    }
}

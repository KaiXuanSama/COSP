package com.kaixuan.copilot_ollama_proxy.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DownstreamLogView} 的协议标识与 chunk 改写器。
 *
 * <p>这个类的存在理由是「落库在上游服务内部、翻译在其外侧」这个结构事实：
 * 上游服务不知道翻译存在，因此「下游到底看到了什么」必须由外侧注入。
 * 本测试钉住两件事 —— 注入生效，以及<strong>改写失败时退回什么</strong>。
 * 后者容易被忽略，但那正是「日志是观测手段，不该因改写失败而丢证据」的落点。
 *
 * <h2>这里曾有一组 usage 换算用例</h2>
 * 本类曾有第三个成员 {@code usageRewriter}，那组用例随它一并删除。
 * 换算（把 {@code cache_read} 加回输入）只依赖上游协议、与下游无关，
 * 已上提到 {@code AnthropicUsageParser}，对应的用例在
 * {@code AnthropicPayloadAndUsageTests} 里。
 */
class DownstreamLogViewTests {

    @Nested
    @DisplayName("协议标识")
    class ProtocolLabel {

        /** 直连：两侧同一个协议，chunk 不改写。 */
        @Test
        void directViewUsesOneProtocolForBothSides() {
            DownstreamLogView view = DownstreamLogView.direct("ANTHROPIC");

            assertThat(view.downstreamProtocol()).isEqualTo("ANTHROPIC");
            assertThat(view.chunkRewriter()).isNull();
        }

        /**
         * 跨协议非流式：协议列写下游的值，chunk 仍记上游原文。
         *
         * <p>两件事的取舍相反 —— 响应体是单一字符串，记上游原文比记翻译后的更有用
         * （后者可由前者推导，反之不行）；但协议列若不写下游值，日志里就看不出
         * 这是一次跨协议调用。所以有 {@code protocolOnly} 这个工厂。
         */
        @Test
        void protocolOnlyViewLabelsDownstreamButLeavesChunksRaw() {
            DownstreamLogView view = DownstreamLogView.protocolOnly("OPENAI");

            assertThat(view.downstreamProtocol()).isEqualTo("OPENAI");
            assertThat(view.viewChunks(List.of("{\"type\":\"ping\"}")).hasUpstreamView()).isFalse();
        }
    }

    @Nested
    @DisplayName("chunk 改写")
    class ChunkRewrite {

        /** 跨协议流式：两栏都留，排查解析失败需要同时看上游发了什么与客户端收到了什么。 */
        @Test
        void streamViewKeepsBothUpstreamAndTranslatedChunks() {
            DownstreamLogView view = new DownstreamLogView(
                    "OPENAI",
                    chunks -> ChunkLogPayload.translated(
                            List.of("{\"object\":\"chat.completion.chunk\"}"), chunks, List.of(1)));

            assertThat(view.viewChunks(List.of("{\"type\":\"message_start\"}")).hasUpstreamView())
                    .isTrue();
        }

        @Test
        void rewriterFailureKeepsUpstreamChunks() {
            List<String> upstream = List.of("{\"type\":\"message_start\"}", "{\"type\":\"ping\"}");
            DownstreamLogView view = new DownstreamLogView("OPENAI", chunks -> {
                throw new IllegalStateException("翻译失败");
            });

            ChunkLogPayload payload = view.viewChunks(upstream);

            // 退回直连形态：至少保住「上游到底返回了什么」这个更基础的事实。
            assertThat(payload.hasUpstreamView()).isFalse();
            assertThat(payload.translated()).isEqualTo(upstream);
        }

        /** 改写器返回 null 同样退回原样。 */
        @Test
        void rewriterReturningNullKeepsUpstreamChunks() {
            List<String> upstream = List.of("{\"type\":\"message_start\"}");
            DownstreamLogView view = new DownstreamLogView("OPENAI", chunks -> null);

            assertThat(view.viewChunks(upstream).translated()).isEqualTo(upstream);
        }

        @Test
        void nullChunksAreHandledWithoutInvokingRewriter() {
            DownstreamLogView view = new DownstreamLogView("OPENAI", chunks -> {
                throw new AssertionError("不应被调用");
            });

            assertThat(view.viewChunks(null).hasUpstreamView()).isFalse();
        }
    }
}

package com.kaixuan.copilot_ollama_proxy.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChunkLogPayload} 的两种形态，以及 {@link ChunkLogPayload#from} 的<strong>退回语义</strong>。
 *
 * <h2>这个类的存在理由</h2>
 * 落库发生在上游服务内部、翻译在其外侧，因此「下游到底看到了什么」必须由外侧注入。
 * 本测试钉住两件事 —— 注入生效，以及<strong>改写失败时退回什么</strong>。
 * 后者容易被忽略，但那正是「日志是观测手段，不该因改写失败而丢证据」的落点。
 *
 * <h2>退回语义为何用一组用例专门钉</h2>
 * 三条退回路径（改写器为 null / 抛异常 / 返回 null）都落在
 * {@link ChunkLogPayload#from} 里，而它们的失败方向是<strong>反直觉的</strong>：
 * 改写坏了的时候，恰恰是最需要日志的时候 —— 若那时连日志也丢了，
 * 排查会同时失去「翻译为什么坏」与「上游到底发了什么」两条线索。
 *
 * <p>本组用例原本属于 {@code DownstreamLogViewTests}（那 7 条里的一半）。
 * 后来删除了 {@code DownstreamLogView} 类型 —— 它重复持有下游协议，
 * 而那个协议的家是 {@code RequestPipelineContext}。剩下的「改写器 + 退回语义」
 * 正是本类现在承载的东西，用例因此随之迁移而非删除。
 */
class ChunkLogPayloadTests {

    @Nested
    @DisplayName("两种落库形态")
    class Shapes {

        /** 直连：只有一份 chunk，落库为裸数组 —— 与历史数据完全一致。 */
        @Test
        void directPayloadIsABareArray() {
            ChunkLogPayload payload = ChunkLogPayload.direct(List.of("{\"a\":1}", "[DONE]"));

            assertThat(payload.hasUpstreamView())
                    .as("裸数组形态：前端走现有逻辑，不必分辨两栏")
                    .isFalse();
            assertThat(payload.translated()).containsExactly("{\"a\":1}", "[DONE]");
        }

        /** 跨协议：两栏都留，排查解析失败需要同时看上游发了什么与客户端收到了什么。 */
        @Test
        void translatedPayloadKeepsBothViews() {
            ChunkLogPayload payload = ChunkLogPayload.translated(
                    List.of("{\"object\":\"chat.completion.chunk\"}"),
                    List.of("{\"type\":\"message_start\"}"),
                    List.of(1));

            assertThat(payload.hasUpstreamView()).isTrue();
            assertThat(payload.translated()).hasSize(1);
            assertThat(payload.upstream()).hasSize(1);
            assertThat(payload.frameCounts())
                    .as("帧数不对等时，两栏对齐只能靠它 —— 事后从两个数组反推不出来")
                    .containsExactly(1);
        }

        /** 序列化值随形态分派：裸数组 vs 带标记的对象。 */
        @Test
        void serializableValueFollowsTheShape() {
            assertThat(ChunkLogPayload.direct(List.of("a")).toSerializableValue())
                    .isInstanceOf(List.class);
            assertThat(ChunkLogPayload.translated(List.of("a"), List.of("b"), List.of(1))
                    .toSerializableValue())
                    .isInstanceOf(java.util.Map.class);
        }
    }

    @Nested
    @DisplayName("改写失败退回上游原文")
    class RewriterFallback {

        /** 正常改写：用改写器的产出。 */
        @Test
        void usesRewriterOutputWhenItSucceeds() {
            ChunkLogPayload payload = ChunkLogPayload.from(
                    chunks -> ChunkLogPayload.translated(
                            List.of("{\"object\":\"chat.completion.chunk\"}"), chunks, List.of(1)),
                    List.of("{\"type\":\"message_start\"}"));

            assertThat(payload.hasUpstreamView()).isTrue();
        }

        /**
         * <strong>改写器抛异常时退回上游原文</strong> —— 本组的核心。
         *
         * <p>理由：日志是观测手段，不该因为改写失败而丢掉「上游到底返回了什么」
         * 这个更基础的事实。而「翻译坏了」恰恰是最需要日志的时刻。
         */
        @Test
        void rewriterFailureKeepsUpstreamChunks() {
            List<String> upstream = List.of("{\"type\":\"message_start\"}", "{\"type\":\"ping\"}");

            ChunkLogPayload payload = ChunkLogPayload.from(chunks -> {
                throw new IllegalStateException("翻译失败");
            }, upstream);

            // 退回裸数组形态：至少保住「上游到底返回了什么」。
            assertThat(payload.hasUpstreamView()).isFalse();
            assertThat(payload.translated()).isEqualTo(upstream);
        }

        /** 改写器返回 null 同样退回原样 —— 另一种失败方式，同一条处置。 */
        @Test
        void rewriterReturningNullKeepsUpstreamChunks() {
            List<String> upstream = List.of("{\"type\":\"message_start\"}");

            ChunkLogPayload payload = ChunkLogPayload.from(chunks -> null, upstream);

            assertThat(payload.translated()).isEqualTo(upstream);
        }

        /** 改写器为 null（直连路线 / 跟协议非流式）：不改写，落上游原文。 */
        @Test
        void nullRewriterKeepsUpstreamChunks() {
            List<String> upstream = List.of("{\"type\":\"message_start\"}");

            ChunkLogPayload payload = ChunkLogPayload.from(null, upstream);

            assertThat(payload.hasUpstreamView()).isFalse();
            assertThat(payload.translated()).isEqualTo(upstream);
        }

        /** 上游 chunk 为 null 时<strong>不调用改写器</strong> —— 没有输入就没有可改写的对象。 */
        @Test
        void nullChunksAreHandledWithoutInvokingRewriter() {
            ChunkLogPayload payload = ChunkLogPayload.from(chunks -> {
                throw new AssertionError("不应被调用");
            }, null);

            assertThat(payload.hasUpstreamView()).isFalse();
        }
    }
}

package com.kaixuan.copilot_ollama_proxy.api.shared;

import com.kaixuan.copilot_ollama_proxy.observability.record.ApiUsageDailyService;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 出口 usage 消费的两档记账语义。
 *
 * <h2>为何这个测试值得单独存在</h2>
 * {@link UsageAccounting} 的类注释里写着「两档语义<strong>刻意不同</strong>，不要顺手统一」——
 * 而「统一掉一处看起来不一致的写法」正是后人最可能做的事。
 * 本测试把两档各自的边界逐条钉住：任何一次「统一」都会打中这里的某一条。
 *
 * <table border="1">
 *   <caption>被钉住的四档边界</caption>
 *   <tr><th>方法</th><th>输入</th><th>期望</th></tr>
 *   <tr><td rowspan="3">{@code accumulate}</td><td>{@code usage == null}</td><td>不覆盖（保留先前值）</td></tr>
 *   <tr><td>{@code usage == EMPTY}</td><td><strong>也不覆盖</strong>（收下会把真实值刷成 0）</td></tr>
 *   <tr><td>{@code usage} 有数据</td><td>覆盖</td></tr>
 *   <tr><td rowspan="2">{@code recordStream}</td><td>累积器为 {@code null}</td><td><strong>记 0,0</strong>（调用次数不能少算）</td></tr>
 *   <tr><td>累积器有值</td><td>记该值</td></tr>
 *   <tr><td rowspan="3">{@code recordNonStream}</td><td>槽为 {@code null}</td><td><strong>不记</strong></td></tr>
 *   <tr><td>槽为 {@link UsageTokens#EMPTY}</td><td><strong>不记</strong></td></tr>
 *   <tr><td>槽有值</td><td>记该值</td></tr>
 * </table>
 */
class UsageAccountingTests {

    private final ApiUsageDailyService collector = mock(ApiUsageDailyService.class);

    @Nested
    @DisplayName("accumulate：取最后一份有数据的")
    class Accumulate {

        @Test
        @DisplayName("null 不覆盖 —— 那是「生产者未见过 usage」，不是「上游报了空」")
        void nullDoesNotOverwrite() {
            AtomicReference<UsageTokens> ref = new AtomicReference<>(new UsageTokens(10, 20, 3));

            UsageAccounting.accumulate(UpstreamEvent.body("{}"), ref);

            assertThat(ref.get()).isEqualTo(new UsageTokens(10, 20, 3));
        }

        @Test
        @DisplayName("非 null 覆盖 —— 后到的总是更完整（结算值在尾帧）")
        void nonNullOverwrites() {
            AtomicReference<UsageTokens> ref = new AtomicReference<>(new UsageTokens(10, null, null));

            UsageAccounting.accumulate(
                    UpstreamEvent.terminal("[DONE]", new UsageTokens(10, 20, 3)), ref);

            assertThat(ref.get()).isEqualTo(new UsageTokens(10, 20, 3));
        }

        @Test
        @DisplayName("EMPTY 也不覆盖 —— 收下它会把先前的真实值刷成 0")
        void emptyDoesNotOverwrite() {
            AtomicReference<UsageTokens> ref = new AtomicReference<>(new UsageTokens(10, 20, null));

            UsageAccounting.accumulate(UpstreamEvent.body("{}", UsageTokens.EMPTY), ref);

            // 与重构前三条线路的行为逐字一致（都写成 `if (!tokens.isEmpty())` 才更新）。
            // 场景：某个后到的事件带 usage 对象但字段名本服务不认 → 解析为 EMPTY →
            // 若收下就会把 message_start 里的真实输入 token 刷成 0。
            assertThat(ref.get()).isEqualTo(new UsageTokens(10, 20, null));
        }
    }

    @Nested
    @DisplayName("recordStream：恒记（无 usage 记 0,0）")
    class StreamTier {

        @Test
        @DisplayName("累积器为 null → 仍记 0,0（否则统计卡的调用次数少算）")
        void recordsZeroWhenAbsent() {
            UsageAccounting.recordStream(collector, new AtomicReference<>(null));

            verify(collector).record(0, 0);
        }

        @Test
        @DisplayName("累积器有值 → 记该值")
        void recordsValues() {
            UsageAccounting.recordStream(collector, new AtomicReference<>(new UsageTokens(7, 8, 2)));

            verify(collector).record(7, 8);
        }

        @Test
        @DisplayName("部分缺失按 0 送（只接受 int 的聚合端口）")
        void partialBecomesZero() {
            UsageAccounting.recordStream(collector, new AtomicReference<>(new UsageTokens(7, null, null)));

            verify(collector).record(7, 0);
        }
    }

    @Nested
    @DisplayName("recordNonStream：有才记")
    class NonStreamTier {

        @Test
        @DisplayName("槽为 null → 不记")
        void absentNotRecorded() {
            UsageAccounting.recordNonStream(collector, UpstreamEvent.body("{}"));

            verify(collector, never()).record(anyInt(), anyInt());
        }

        @Test
        @DisplayName("槽为空 → 不记（null / 空都不能折成 0,0）")
        void emptyNotRecorded() {
            UsageAccounting.recordNonStream(collector, UpstreamEvent.body("{}", UsageTokens.EMPTY));

            verify(collector, never()).record(anyInt(), anyInt());
        }

        @Test
        @DisplayName("槽有值 → 记该值")
        void recordsValues() {
            UsageAccounting.recordNonStream(collector,
                    UpstreamEvent.body("{}", new UsageTokens(31, 42, 5)));

            verify(collector).record(31, 42);
        }
    }
}

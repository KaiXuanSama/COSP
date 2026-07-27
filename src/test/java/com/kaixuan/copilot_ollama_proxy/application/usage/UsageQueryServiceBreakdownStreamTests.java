package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证与锁定：下钻用量明细的 SSE 推送流。
 *
 * <p>覆盖点：
 * <ul>
 *   <li>订阅即推首帧，前端不必等第一次调用发生；</li>
 *   <li>调用变更信号驱动重查，实现「柱子随调用实时增长」；</li>
 *   <li>内容未变时不重复推送（避免前端无谓重绘与动画抖动）；</li>
 *   <li>天数同样钳制到 [1, 90]，与 HTTP 端点口径一致。</li>
 * </ul>
 */
class UsageQueryServiceBreakdownStreamTests {

    private static final UsageBreakdownRow ROW_ONE =
            new UsageBreakdownRow("2026-07-27", "deepseek", "chat", 1L);
    private static final UsageBreakdownRow ROW_TWO =
            new UsageBreakdownRow("2026-07-27", "deepseek", "chat", 2L);

    private ApiCallUsageRepository usageRepository;
    private UsageEventPublisher usageEventPublisher;
    private UsageQueryService service;

    @BeforeEach
    void setUp() {
        usageRepository = mock(ApiCallUsageRepository.class);
        usageEventPublisher = new UsageEventPublisher();
        service = new UsageQueryService(
                mock(ApiUsageRepository.class), usageRepository, usageEventPublisher);
    }

    /** 订阅建立时立即下发一帧，概览页无需等到下一次调用才有柱子。 */
    @Test
    void firstFrameIsEmittedOnSubscribe() {
        when(usageRepository.aggregateBreakdown(anyInt())).thenReturn(List.of(ROW_ONE));

        List<UsageBreakdownRow> first = service.streamUsageBreakdown(7)
                .blockFirst(Duration.ofSeconds(5));

        assertThat(first).containsExactly(ROW_ONE);
    }

    /** 调用变更信号触发重查并推送新明细，这是「实时更新」的核心路径。 */
    @Test
    void usageSignalTriggersFreshFrame() {
        // 链式 thenReturn 而非 thenReturn(a, b)：后者是可变参数重载，
        // 以泛型 List 作实参会触发「创建泛型数组」告警
        when(usageRepository.aggregateBreakdown(anyInt()))
                .thenReturn(List.of(ROW_ONE))
                .thenReturn(List.of(ROW_TWO));

        // 首帧到达后再发信号，确保信号不会早于订阅建立而被 directBestEffort sink 丢弃
        List<List<UsageBreakdownRow>> frames = service.streamUsageBreakdown(7)
                .doOnNext(rows -> {
                    if (rows.equals(List.of(ROW_ONE))) {
                        usageEventPublisher.publishUsageChanged();
                    }
                })
                .take(2)
                .collectList()
                .block(Duration.ofSeconds(5));

        // 逐帧断言而非 containsExactly(List...)：后者以泛型 List 作可变参数会触发泛型数组告警
        assertThat(frames).hasSize(2);
        assertThat(frames.get(0)).containsExactly(ROW_ONE);
        assertThat(frames.get(1)).containsExactly(ROW_TWO);
    }

    /**
     * 明细未变化时不推重复帧。
     *
     * <p>很重要：不少调用不产生 usage 明细行（上游未返回 usage），
     * 若逐帧下推会让前端柱状图无谓重绘、入场动画反复抖动。
     */
    @Test
    void unchangedBreakdownIsNotPushedAgain() {
        when(usageRepository.aggregateBreakdown(anyInt())).thenReturn(List.of(ROW_ONE));

        AtomicBoolean firstFrameSeen = new AtomicBoolean(false);
        AtomicBoolean secondFrameSeen = new AtomicBoolean(false);

        // 首帧到达后立刻发一次信号；因明细内容相同，distinctUntilChanged 应吞掉第二帧。
        // 故这里刻意只等一小段时间，用 timeout 正常收尾（收不到第二帧才是预期结果）。
        List<List<UsageBreakdownRow>> frames = service.streamUsageBreakdown(7)
                .doOnNext(rows -> {
                    if (firstFrameSeen.compareAndSet(false, true)) {
                        usageEventPublisher.publishUsageChanged();
                    } else {
                        secondFrameSeen.set(true);
                    }
                })
                .take(Duration.ofMillis(600))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(firstFrameSeen).isTrue();
        assertThat(secondFrameSeen).as("内容未变时不应重复推送").isFalse();
        assertThat(frames).as("整个窗口内只应有首帧").hasSize(1);
        assertThat(frames.get(0)).containsExactly(ROW_ONE);
    }

    /** 流式端点与 HTTP 端点共用同一套天数钳制，避免大范围查询拖垮 SQLite。 */
    @Test
    void streamDaysAreClamped() {
        when(usageRepository.aggregateBreakdown(anyInt())).thenReturn(List.of());

        service.streamUsageBreakdown(9999).blockFirst(Duration.ofSeconds(5));

        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(usageRepository).aggregateBreakdown(captor.capture());
        assertThat(captor.getValue()).isEqualTo(90);
    }
}

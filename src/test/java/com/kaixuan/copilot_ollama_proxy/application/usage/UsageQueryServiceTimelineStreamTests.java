package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTimelinePoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 折线图时间线的 SSE 推送流验证。
 *
 * <p>与下钻明细流共用同一批触发信号，因此折线、柱状图、统计卡由同一次调用事件一起刷新，
 * 三处视图之间不会出现新旧错位。这里锁的是流本身的行为：
 * <ul>
 *   <li>订阅即推首帧，前端不必等第一次调用发生；</li>
 *   <li>调用信号驱动重查，实现折线随调用实时延伸；</li>
 *   <li>内容未变时不重复推送，避免无谓重绘与动画抖动。</li>
 * </ul>
 */
class UsageQueryServiceTimelineStreamTests {

    private ApiCallUsageRepository usageRepository;
    private UsageEventPublisher usageEventPublisher;
    private UsageQueryService service;

    @BeforeEach
    void setUp() {
        usageRepository = mock(ApiCallUsageRepository.class);
        usageEventPublisher = new UsageEventPublisher();
        service = new UsageQueryService(
                mock(ApiUsageRepository.class), usageRepository, usageEventPublisher);
        when(usageRepository.aggregateHalfHourTokens(anyString(), anyString())).thenReturn(List.of());
    }

    /** 造一个「今天」的日点位，避免把测试钉死在某个具体日期上。 */
    private static UsageTimelinePoint todayPoint(long input, long output) {
        return new UsageTimelinePoint(LocalDate.now().toString(), input, output);
    }

    @Test
    void emitsFirstFrameOnSubscribe() {
        when(usageRepository.aggregateDailyTokens(anyInt())).thenReturn(List.of(todayPoint(100, 10)));

        List<UsageTimelinePoint> first = service.streamUsageTimeline("7d")
                .blockFirst(Duration.ofSeconds(5));

        // 补零后固定 7 个点，今天那一点带上真实数值
        assertThat(first).hasSize(7);
        assertThat(first.get(6).inputTokens()).isEqualTo(100);
    }

    @Test
    void usageSignalTriggersFreshFrame() {
        // 链式 thenReturn 而非 thenReturn(a, b)：后者是可变参数重载，
        // 以泛型 List 作实参会触发「创建泛型数组」告警
        when(usageRepository.aggregateDailyTokens(anyInt()))
                .thenReturn(List.of(todayPoint(100, 10)))
                .thenReturn(List.of(todayPoint(200, 20)));

        // 信号要在首帧到达之后再发：sink 是 directBestEffort，订阅前发出的信号会被丢弃
        List<List<UsageTimelinePoint>> frames = service.streamUsageTimeline("7d")
                .doOnNext(points -> {
                    if (points.get(6).inputTokens() == 100) {
                        usageEventPublisher.publishUsageChanged();
                    }
                })
                .take(2)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).get(6).inputTokens()).isEqualTo(100);
        assertThat(frames.get(1).get(6).inputTokens()).isEqualTo(200);
    }

    @Test
    void doesNotRepeatUnchangedFrames() {
        when(usageRepository.aggregateDailyTokens(anyInt())).thenReturn(List.of(todayPoint(100, 10)));

        // 用时间窗收尾：收到首帧后即便再发信号也不该有第二帧，
        // 因此「正常超时结束、只拿到一帧」才是预期结果
        List<List<UsageTimelinePoint>> frames = service.streamUsageTimeline("7d")
                .doOnNext(points -> usageEventPublisher.publishUsageChanged())
                .take(Duration.ofMillis(600))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(frames).hasSize(1);
    }

    @Test
    void dailyRangeStreamUsesHalfHourAggregation() {
        service.streamUsageTimeline("1d").blockFirst(Duration.ofSeconds(5));

        verify(usageRepository).aggregateHalfHourTokens(anyString(), anyString());
    }

    @Test
    void streamSharesRangeParsingWithHttpEndpoint() {
        service.streamUsageTimeline("7d").blockFirst(Duration.ofSeconds(5));

        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(usageRepository).aggregateDailyTokens(captor.capture());
        assertThat(captor.getValue()).isEqualTo(7);
    }
}

package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageHourlyPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证与锁定：今日时段折线的<strong>窗口边界</strong>与结果透传。
 *
 * <p>用例层在这条链路上只剩两件事：算出查询窗口的起止，以及原样返回仓储结果。
 * 归桶（整点居中）已下沉到 SQL，补零与「尚未到来」的判定上移到前端 ——
 * 那两者都是展示决策，会随时钟移动，写进后端就意味着帧一发出即开始过时。
 *
 * <h2>为什么窗口边界留在后端</h2>
 * 它是<strong>查询参数</strong>而非展示决策：前端不需要知道「5 点分界」这件事，
 * 只需按帧里的时间戳定位点位。把边界计算留在后端，5 点这个常量就只有一处定义。
 */
class UsageQueryServiceHourlyTests {

    private ApiCallUsageRepository usageRepository;
    private UsageQueryService service;

    @BeforeEach
    void setUp() {
        usageRepository = mock(ApiCallUsageRepository.class);
        service = new UsageQueryService(
                mock(ApiUsageRepository.class), usageRepository, new UsageEventPublisher());
        when(usageRepository.aggregateHourlyTokens(anyString(), anyString())).thenReturn(List.of());
    }

    /**
     * 窗口起点的锚定规则 —— 5 点之前算作「昨天」那一轮。
     *
     * <p>凌晨 3 点打开页面，看到的应是昨晚以来的连续曲线，而不是刚开始 3 小时的空图。
     * 跨夜编码是常态，按自然日切分会把一次连续工作截成两段。
     */
    @Nested
    class WindowStart {

        @Test
        void beforeFiveAmAnchorsToPreviousDay() {
            LocalDateTime start = UsageQueryService.resolveHourlyWindowStart(
                    LocalDateTime.of(2026, 7, 28, 3, 0));

            assertThat(start).isEqualTo(LocalDateTime.of(2026, 7, 27, 5, 0));
        }

        @Test
        void exactlyFiveAmAnchorsToSameDay() {
            LocalDateTime start = UsageQueryService.resolveHourlyWindowStart(
                    LocalDateTime.of(2026, 7, 28, 5, 0));

            assertThat(start).isEqualTo(LocalDateTime.of(2026, 7, 28, 5, 0));
        }

        @Test
        void justBeforeFiveAmStillAnchorsToPreviousDay() {
            LocalDateTime start = UsageQueryService.resolveHourlyWindowStart(
                    LocalDateTime.of(2026, 7, 28, 4, 59, 59));

            assertThat(start).isEqualTo(LocalDateTime.of(2026, 7, 27, 5, 0));
        }

        @Test
        void afternoonAnchorsToSameDay() {
            LocalDateTime start = UsageQueryService.resolveHourlyWindowStart(
                    LocalDateTime.of(2026, 7, 28, 14, 30));

            assertThat(start).isEqualTo(LocalDateTime.of(2026, 7, 28, 5, 0));
        }

        @Test
        void midnightAnchorsToPreviousDay() {
            LocalDateTime start = UsageQueryService.resolveHourlyWindowStart(
                    LocalDateTime.of(2026, 7, 28, 0, 0));

            assertThat(start).isEqualTo(LocalDateTime.of(2026, 7, 27, 5, 0));
        }
    }

    /**
     * 传给仓储的窗口恰好是 24 小时，格式与 {@code created_at} 一致。
     *
     * <p>格式必须逐字符对齐：SQL 用字面量比较（依赖字典序等于时间序），
     * 格式不一致会让比较静默失效。
     */
    @Test
    void queriesExactlyTwentyFourHourWindow() {
        service.getHourlyTokens().block();

        ArgumentCaptor<String> start = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> end = ArgumentCaptor.forClass(String.class);
        verify(usageRepository).aggregateHourlyTokens(start.capture(), end.capture());

        LocalDateTime parsedStart = LocalDateTime.parse(start.getValue());
        LocalDateTime parsedEnd = LocalDateTime.parse(end.getValue());

        assertThat(parsedStart.getHour()).isEqualTo(5);
        assertThat(parsedStart.getMinute()).isZero();
        assertThat(parsedEnd).isEqualTo(parsedStart.plusHours(24));
    }

    /** 窗口起点必然是今天或昨天的 05:00，取决于当前时刻是否已过 5 点。 */
    @Test
    void windowStartIsTodayOrYesterdayAtFive() {
        service.getHourlyTokens().block();

        ArgumentCaptor<String> start = ArgumentCaptor.forClass(String.class);
        verify(usageRepository).aggregateHourlyTokens(start.capture(), anyString());

        LocalDate startDate = LocalDateTime.parse(start.getValue()).toLocalDate();
        LocalDate today = LocalDate.now();
        assertThat(startDate).isIn(today, today.minusDays(1));
    }

    /**
     * 仓储结果原样返回：不补零、不截断、不标记未来。
     *
     * <p>这三件事全在前端。后端补零会让帧携带「此刻是几点」这个瞬时状态，
     * 而帧是快照 —— 一发出就开始过时。
     */
    @Test
    void repositoryPointsAreReturnedUnmodified() {
        List<UsageHourlyPoint> points = List.of(
                new UsageHourlyPoint("2026-07-27T08:00:00", 100L, 20L),
                new UsageHourlyPoint("2026-07-27T14:00:00", 250L, 30L));
        when(usageRepository.aggregateHourlyTokens(anyString(), anyString())).thenReturn(points);

        List<UsageHourlyPoint> result = service.getHourlyTokens().block();

        assertThat(result).containsExactlyElementsOf(points);
    }

    /** 无数据时返回空列表 —— 前端据此渲染空图，而非报错。 */
    @Test
    void returnsEmptyListWhenNoData() {
        assertThat(service.getHourlyTokens().block()).isNotNull().isEmpty();
    }
}

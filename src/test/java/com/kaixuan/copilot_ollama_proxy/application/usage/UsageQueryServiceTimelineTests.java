package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTimelinePoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 折线图时间线用例层的验证与锁定。
 *
 * <p>用例层承担三件仓储层刻意不做的事，测试即围绕这三件：
 * <ul>
 *   <li><strong>补零</strong> —— 缺失的日期要占位，否则折线的横轴不再是等距时间轴；</li>
 *   <li><strong>截断</strong> —— 今日尚未到来的时段不出现，补零会让折线扎到底、误读为骤降；</li>
 *   <li><strong>5 点起算</strong> —— 窗口边界与桶号换算，含跨午夜的归属。</li>
 * </ul>
 *
 * <p>时间相关断言一律从 {@link LocalDateTime#now()} 推导，不写死具体日期或小时，
 * 否则测试会在特定时段失败。
 */
class UsageQueryServiceTimelineTests {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private ApiCallUsageRepository usageRepository;
    private UsageQueryService service;

    @BeforeEach
    void setUp() {
        usageRepository = mock(ApiCallUsageRepository.class);
        service = new UsageQueryService(
                mock(ApiUsageRepository.class), usageRepository, new UsageEventPublisher());
        when(usageRepository.aggregateDailyTokens(anyInt())).thenReturn(List.of());
        when(usageRepository.aggregateHourlyTokens(anyString(), anyString())).thenReturn(List.of());
    }

    private List<UsageTimelinePoint> weekly() {
        return service.getUsageTimeline("7d", 2).block();
    }

    private List<UsageTimelinePoint> daily(int bucketHours) {
        return service.getUsageTimeline("1d", bucketHours).block();
    }

    /** 当前时刻所属窗口的起点，与被测实现同一套 5 点起算规则。 */
    private static LocalDateTime expectedWindowStart() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate anchor = now.getHour() < 5 ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        return anchor.atTime(5, 0);
    }

    /** 当前时刻落在第几个桶（自 0 起），即最后一个应出现的点。 */
    private static int expectedLastSlot(int bucketHours) {
        long elapsedHours = Duration.between(expectedWindowStart(), LocalDateTime.now()).toHours();
        return (int) (elapsedHours / bucketHours);
    }

    @Nested
    class WeeklyRange {

        @Test
        void padsSevenPointsWhenNoData() {
            // 折线按点位等距绘制：跳过空日会让「隔了几天」这一信息丢失，
            // 故过去的空日必须补零占位
            List<UsageTimelinePoint> points = weekly();

            assertThat(points).hasSize(7);
            assertThat(points).allSatisfy(point -> {
                assertThat(point.inputTokens()).isZero();
                assertThat(point.outputTokens()).isZero();
            });
        }

        @Test
        void ordersPointsAscendingEndingToday() {
            List<UsageTimelinePoint> points = weekly();

            assertThat(points).extracting(UsageTimelinePoint::bucket).isSorted();
            assertThat(points.get(6).bucket()).isEqualTo(LocalDate.now().toString());
            assertThat(points.get(0).bucket()).isEqualTo(LocalDate.now().minusDays(6).toString());
        }

        @Test
        void keepsRealValuesAndPadsTheRest() {
            String today = LocalDate.now().toString();
            when(usageRepository.aggregateDailyTokens(anyInt()))
                    .thenReturn(List.of(new UsageTimelinePoint(today, 1234, 56)));

            List<UsageTimelinePoint> points = weekly();

            assertThat(points).hasSize(7);
            assertThat(points.get(6).inputTokens()).isEqualTo(1234);
            assertThat(points.get(6).outputTokens()).isEqualTo(56);
            assertThat(points.get(5).inputTokens()).isZero();
        }

        @Test
        void queriesExactlySevenDays() {
            weekly();

            ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
            verify(usageRepository).aggregateDailyTokens(captor.capture());
            assertThat(captor.getValue()).isEqualTo(7);
        }

        @Test
        void fallsBackToWeeklyForUnknownRange() {
            assertThat(service.getUsageTimeline("unknown", 2).block()).hasSize(7);
            verify(usageRepository).aggregateDailyTokens(anyInt());
        }
    }

    @Nested
    class DailyRange {

        @Test
        void queriesWindowStartingAtFiveSpanningOneDay() {
            daily(2);

            ArgumentCaptor<String> start = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> end = ArgumentCaptor.forClass(String.class);
            verify(usageRepository).aggregateHourlyTokens(start.capture(), end.capture());

            LocalDateTime windowStart = expectedWindowStart();
            assertThat(start.getValue()).isEqualTo(windowStart.format(TIMESTAMP_FORMAT));
            assertThat(end.getValue()).isEqualTo(windowStart.plusHours(24).format(TIMESTAMP_FORMAT));
        }

        @Test
        void truncatesAtCurrentSlotWithoutPaddingFuture() {
            // 未来时段补零会让折线一头扎到底，读起来像用量骤降；
            // 截断则由折线自然的结束位置表达「后面还没发生」
            assertThat(daily(2)).hasSize(expectedLastSlot(2) + 1);
            assertThat(daily(4)).hasSize(expectedLastSlot(4) + 1);
        }

        @Test
        void startsAtWindowStartLabel() {
            assertThat(daily(2).get(0).bucket()).isEqualTo("05:00");
        }

        @Test
        void labelsBucketWithItsStartTime() {
            List<UsageTimelinePoint> points = daily(2);

            // 2 小时颗粒度下依次为 05:00、07:00、09:00…
            assertThat(points.get(0).bucket()).isEqualTo("05:00");
            if (points.size() > 1) {
                assertThat(points.get(1).bucket()).isEqualTo("07:00");
            }
        }

        @Test
        void mergesHoursFallingIntoSameBucket() {
            // 05 与 06 点在 2 小时颗粒度下属同一桶
            when(usageRepository.aggregateHourlyTokens(anyString(), anyString())).thenReturn(List.of(
                    new UsageTimelinePoint("05", 100, 10),
                    new UsageTimelinePoint("06", 200, 20)));

            List<UsageTimelinePoint> points = daily(2);

            assertThat(points.get(0).bucket()).isEqualTo("05:00");
            assertThat(points.get(0).inputTokens()).isEqualTo(300);
            assertThat(points.get(0).outputTokens()).isEqualTo(30);
        }

        @Test
        void keepsHoursSeparateAtOneHourGranularity() {
            when(usageRepository.aggregateHourlyTokens(anyString(), anyString())).thenReturn(List.of(
                    new UsageTimelinePoint("05", 100, 10),
                    new UsageTimelinePoint("06", 200, 20)));

            List<UsageTimelinePoint> points = daily(1);

            assertThat(points.get(0).inputTokens()).isEqualTo(100);
            if (points.size() > 1) {
                assertThat(points.get(1).bucket()).isEqualTo("06:00");
                assertThat(points.get(1).inputTokens()).isEqualTo(200);
            }
        }

        @Test
        void fallsBackToDefaultForDisallowedGranularity() {
            // 只有能整除 24 的档位才让午夜落在桶边界上，否则横轴的日期分段线会与刻度错位。
            // 这是展示参数，非法值退回默认而非让整个卡片失败。
            assertThat(daily(3)).hasSameSizeAs(daily(2));
            assertThat(daily(5)).hasSameSizeAs(daily(2));
        }

        @Test
        void fallsBackForZeroAndNegativeGranularity() {
            assertThat(daily(0)).hasSameSizeAs(daily(2));
            assertThat(daily(-1)).hasSameSizeAs(daily(2));
        }

        @Test
        void padsBucketsWithZeroWhenWindowHasNoData() {
            List<UsageTimelinePoint> points = daily(4);

            assertThat(points).isNotEmpty();
            assertThat(points).allSatisfy(point -> {
                assertThat(point.inputTokens()).isZero();
                assertThat(point.outputTokens()).isZero();
            });
        }

        @Test
        void placesPostMidnightHoursInLaterBuckets() {
            // 凌晨 1 点在 5 点起算的窗口里是第 20 小时，2 小时颗粒度下是第 10 个桶
            when(usageRepository.aggregateHourlyTokens(anyString(), anyString()))
                    .thenReturn(List.of(new UsageTimelinePoint("01", 500, 50)));

            List<UsageTimelinePoint> points = daily(2);

            if (expectedLastSlot(2) >= 10) {
                assertThat(points.get(10).bucket()).isEqualTo("01:00");
                assertThat(points.get(10).inputTokens()).isEqualTo(500);
            } else {
                // 当前时刻尚未走到该桶，数据被截断丢弃属预期
                assertThat(points).allSatisfy(point -> assertThat(point.inputTokens()).isZero());
            }
        }
    }
}

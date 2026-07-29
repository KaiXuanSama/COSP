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
        when(usageRepository.aggregateHalfHourTokens(anyString(), anyString())).thenReturn(List.of());
    }

    private List<UsageTimelinePoint> weekly() {
        return service.getUsageTimeline("7d").block();
    }

    private List<UsageTimelinePoint> daily() {
        return service.getUsageTimeline("1d").block();
    }

    /** 当前时刻所属窗口的起点，与被测实现同一套 5 点起算规则。 */
    private static LocalDateTime expectedWindowStart() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate anchor = now.getHour() < 5 ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        return anchor.atTime(5, 0);
    }

    /** 当前时刻所在的整点序号（自 0 起）；它之后的点标记为尚未到来。 */
    private static int expectedCurrentPoint() {
        return (int) Duration.between(expectedWindowStart(), LocalDateTime.now()).toHours();
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
            assertThat(service.getUsageTimeline("unknown").block()).hasSize(7);
            verify(usageRepository).aggregateDailyTokens(anyInt());
        }
    }

    @Nested
    class DailyRange {

        @Test
        void queriesWindowStartingAtFiveSpanningOneDay() {
            daily();

            ArgumentCaptor<String> start = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> end = ArgumentCaptor.forClass(String.class);
            verify(usageRepository).aggregateHalfHourTokens(start.capture(), end.capture());

            LocalDateTime windowStart = expectedWindowStart();
            assertThat(start.getValue()).isEqualTo(windowStart.format(TIMESTAMP_FORMAT));
            assertThat(end.getValue()).isEqualTo(windowStart.plusHours(24).format(TIMESTAMP_FORMAT));
        }

        @Test
        void coversFullDayWithSymmetricEnds() {
            // 25 个点使首尾都落在 05:00，一圈闭合的语义直接可见；
            // 若只有 24 个点，两端一个 05:00 一个 04:00，看不出这是完整一天
            List<UsageTimelinePoint> points = daily();

            assertThat(points).hasSize(25);
            assertThat(points.get(0).bucket()).isEqualTo("05:00");
            assertThat(points.get(24).bucket()).isEqualTo("05:00");
        }

        @Test
        void marksPointsAfterCurrentAsFuture() {
            // 未来段的 token 也是 0，若不加区分，折线会一路贴底延伸到轴末，
            // 读起来像用量已归零。故用 future 标记让展示侧断开绘制。
            List<UsageTimelinePoint> points = daily();
            int currentPoint = expectedCurrentPoint();

            assertThat(points.subList(0, currentPoint + 1))
                    .allSatisfy(point -> assertThat(point.future()).isFalse());
            if (currentPoint + 1 < points.size()) {
                assertThat(points.subList(currentPoint + 1, points.size()))
                        .allSatisfy(point -> assertThat(point.future()).isTrue());
            }
        }

        @Test
        void labelsEveryHourInOrder() {
            List<UsageTimelinePoint> points = daily();

            assertThat(points.get(1).bucket()).isEqualTo("06:00");
            assertThat(points.get(19).bucket()).isEqualTo("00:00");
            assertThat(points.get(23).bucket()).isEqualTo("04:00");
        }

        @Test
        void centersEachPointOnTheHour() {
            // 07:00 覆盖 06:30–07:30 —— 这正是首尾都能落在 05:00 的原因
            when(usageRepository.aggregateHalfHourTokens(anyString(), anyString())).thenReturn(List.of(
                    new UsageTimelinePoint("06:30", 100, 10),
                    new UsageTimelinePoint("07:00", 200, 20)));

            List<UsageTimelinePoint> points = daily();

            assertThat(points.get(2).bucket()).isEqualTo("07:00");
            assertThat(points.get(2).inputTokens()).isEqualTo(300);
            assertThat(points.get(2).outputTokens()).isEqualTo(30);
        }

        @Test
        void firstPointCoversOnlyItsSecondHalf() {
            // 首点没有前半小时（窗口自 05:00 起），故只覆盖 05:00–05:30
            when(usageRepository.aggregateHalfHourTokens(anyString(), anyString())).thenReturn(List.of(
                    new UsageTimelinePoint("05:00", 100, 10),
                    new UsageTimelinePoint("05:30", 999, 99)));

            List<UsageTimelinePoint> points = daily();

            assertThat(points.get(0).inputTokens()).isEqualTo(100);
            // 05:30 归入第二个点（06:00 覆盖 05:30–06:30）
            assertThat(points.get(1).inputTokens()).isEqualTo(999);
        }

        @Test
        void lastPointCoversOnlyItsFirstHalf() {
            // 末点没有后半小时（窗口在次日 05:00 截止），故只覆盖 04:30–05:00。
            // 与首点的半小时相加正好补成完整一小时，总量不重不漏。
            when(usageRepository.aggregateHalfHourTokens(anyString(), anyString()))
                    .thenReturn(List.of(new UsageTimelinePoint("04:30", 700, 70)));

            List<UsageTimelinePoint> points = daily();

            assertThat(points.get(24).inputTokens()).isEqualTo(700);
        }

        @Test
        void padsPointsWithZeroWhenWindowHasNoData() {
            List<UsageTimelinePoint> points = daily();

            assertThat(points).hasSize(25);
            assertThat(points).allSatisfy(point -> {
                assertThat(point.inputTokens()).isZero();
                assertThat(point.outputTokens()).isZero();
            });
        }

        @Test
        void placesPostMidnightHoursInLaterPoints() {
            // 凌晨 1 点在 5 点起算的窗口里是第 20 个整点。
            // 完整一天始终包含该点，故无需再按当前时刻分支。
            when(usageRepository.aggregateHalfHourTokens(anyString(), anyString()))
                    .thenReturn(List.of(new UsageTimelinePoint("01:00", 500, 50)));

            List<UsageTimelinePoint> points = daily();

            assertThat(points.get(20).bucket()).isEqualTo("01:00");
            assertThat(points.get(20).inputTokens()).isEqualTo(500);
        }
    }
}

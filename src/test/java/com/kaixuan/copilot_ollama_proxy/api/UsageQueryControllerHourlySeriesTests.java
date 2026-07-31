package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.usage.UsageQueryService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.SseConnectionGate;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageHourlyPoint;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageHourlySeries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证与锁定：时段用量端点的参数绑定与响应体形状。
 *
 * <p>{@code bindToController} 起独立 WebFlux 环境（绕开 JWT），关注点收窄到
 * 「查询参数怎么进、JSON 怎么出」。5 点分界、日期收敛与补零属于用例层，
 * 在 {@code UsageQueryServiceHourlySeriesTests} 中覆盖。
 *
 * <p>特别确认 {@code date} 缺省时以 {@code null} 抵达服务层 —— 若控制器擅自填了
 * 「今天」，凌晨 5 点前的回退逻辑就被绕过了，用户会看到一张刚开始几小时的空图。
 */
class UsageQueryControllerHourlySeriesTests {

    private UsageQueryService usageQueryService;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        usageQueryService = mock(UsageQueryService.class);
        webTestClient = WebTestClient
                .bindToController(new UsageQueryController(usageQueryService, new SseConnectionGate(20)))
                .build();
        when(usageQueryService.getHourlySeries(any())).thenReturn(Mono.just(sampleSeries()));
    }

    /** 25 个点位的样例：首点、14 点、末点有数据，其余补零。 */
    private static UsageHourlySeries sampleSeries() {
        List<UsageHourlyPoint> points = new ArrayList<>();
        points.add(new UsageHourlyPoint("2026-07-28T05:00:00", 10L, 1L));
        for (int hour = 6; hour <= 13; hour++) {
            points.add(UsageHourlyPoint.empty("2026-07-28T" + String.format("%02d", hour) + ":00:00"));
        }
        points.add(new UsageHourlyPoint("2026-07-28T14:00:00", 200L, 40L));
        for (int hour = 15; hour <= 23; hour++) {
            points.add(UsageHourlyPoint.empty("2026-07-28T" + hour + ":00:00"));
        }
        for (int hour = 0; hour <= 4; hour++) {
            points.add(UsageHourlyPoint.empty("2026-07-29T0" + hour + ":00:00"));
        }
        points.add(new UsageHourlyPoint("2026-07-29T05:00:00", 30L, 3L));
        return new UsageHourlySeries(
                "2026-07-28", "2026-07-28T05:00:00", "2026-07-29T05:00:00", false, points);
    }

    /**
     * 缺省 {@code date} 时以 {@code null} 抵达服务层，由那里决定当前窗口。
     *
     * <p>控制器若自己填「今天」，凌晨 5 点前的回退就被绕过了 —— 那个时刻用户
     * 应看到昨晚以来的连续曲线，而不是刚开始几小时的空图。
     */
    @Test
    void missingDateReachesServiceAsNull() {
        webTestClient.get().uri("/config/api/usage-hourly/series")
                .exchange()
                .expectStatus().isOk();

        verify(usageQueryService).getHourlySeries(isNull());
    }

    /** 日期原样透传，收敛留在用例层。 */
    @Test
    void dateParameterIsPassedThrough() {
        webTestClient.get().uri("/config/api/usage-hourly/series?date=2026-07-28")
                .exchange()
                .expectStatus().isOk();

        verify(usageQueryService).getHourlySeries("2026-07-28");
    }

    /** 畸形日期同样原样进入服务层 —— 在那里收敛，不在 API 层报 400。 */
    @Test
    void malformedDateReachesServiceUntouched() {
        webTestClient.get().uri("/config/api/usage-hourly/series?date=not-a-date")
                .exchange()
                .expectStatus().isOk();

        verify(usageQueryService).getHourlySeries("not-a-date");
    }

    /** 响应体形状：4 个顶层字段 + points 内 3 个字段。 */
    @Test
    void responseBodyExposesWindowAndPoints() {
        webTestClient.get().uri("/config/api/usage-hourly/series?date=2026-07-28")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.date").isEqualTo("2026-07-28")
                .jsonPath("$.windowStart").isEqualTo("2026-07-28T05:00:00")
                .jsonPath("$.windowEnd").isEqualTo("2026-07-29T05:00:00")
                .jsonPath("$.isCurrentWindow").isEqualTo(false)
                .jsonPath("$.points.length()").isEqualTo(25)
                .jsonPath("$.points[0].bucket").isEqualTo("2026-07-28T05:00:00")
                .jsonPath("$.points[0].inputTokens").isEqualTo(10)
                .jsonPath("$.points[0].outputTokens").isEqualTo(1)
                // 补零时段是完整对象，字段为 0 而非缺失 —— 前端可无条件读取
                .jsonPath("$.points[1].inputTokens").isEqualTo(0)
                .jsonPath("$.points[9].inputTokens").isEqualTo(200)
                .jsonPath("$.points[24].bucket").isEqualTo("2026-07-29T05:00:00")
                .jsonPath("$.points[24].inputTokens").isEqualTo(30);
    }

    /** 既有的「当前那一天、不补零」端点不受影响。 */
    @Test
    void legacyHourlyEndpointStillWorks() {
        when(usageQueryService.getHourlyTokens()).thenReturn(Mono.just(List.of()));

        webTestClient.get().uri("/config/api/usage-hourly")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$").isArray();

        verify(usageQueryService).getHourlyTokens();
    }
}

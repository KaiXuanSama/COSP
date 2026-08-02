package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.usage.UsageQueryService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.SseConnectionGate;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageDailyPage;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageDailyPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证与锁定：按日 token 用量分页端点的参数绑定与响应体形状。
 *
 * <p>用 {@code bindToController} 起独立 WebFlux 环境（绕开 JWT），关注点收窄到
 * 「查询参数怎么进、JSON 怎么出」。钳制与补零属于用例层，在
 * {@code UsageQueryServiceDailyPageTests} 中覆盖。
 *
 * <p>字段名断言不是形式主义：record 组件名经 Jackson 直接成为 JSON 键，
 * 重命名在 Java 侧毫无警告，却会让前端读到 undefined。
 */
class UsageQueryControllerDailyPageTests {

    private UsageQueryService usageQueryService;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        usageQueryService = mock(UsageQueryService.class);
        webTestClient = WebTestClient
                .bindToController(new UsageQueryController(usageQueryService, new SseConnectionGate(20)))
                .build();
    }

    private void stubPage(UsageDailyPage page) {
        when(usageQueryService.getUsageDailyPage(anyInt(), anyInt())).thenReturn(Mono.just(page));
    }

    private static UsageDailyPage samplePage() {
        return new UsageDailyPage("2026-07-24", "2026-07-30", 7, 1, false, true, true,
                List.of(
                        new UsageDailyPoint("2026-07-24", 3L, 300L, 90L),
                        UsageDailyPoint.empty("2026-07-25"),
                        UsageDailyPoint.empty("2026-07-26"),
                        UsageDailyPoint.empty("2026-07-27"),
                        UsageDailyPoint.empty("2026-07-28"),
                        UsageDailyPoint.empty("2026-07-29"),
                        new UsageDailyPoint("2026-07-30", 5L, 500L, 150L)));
    }

    /** 不传参数时默认「含今天的最近 7 天」，与柱状图端点一致。 */
    @Test
    void defaultsToSevenDayWindowAtLatestPosition() {
        stubPage(samplePage());

        webTestClient.get().uri("/config/api/usage-daily/page")
                .exchange()
                .expectStatus().isOk();

        verify(usageQueryService).getUsageDailyPage(7, 0);
    }

    /** 两个参数原样透传。 */
    @Test
    void queryParametersArePassedThrough() {
        stubPage(samplePage());

        webTestClient.get().uri("/config/api/usage-daily/page?size=10&offset=3")
                .exchange()
                .expectStatus().isOk();

        verify(usageQueryService).getUsageDailyPage(10, 3);
    }

    /** 越界参数同样原样进入服务层 —— 钳制留在那里，不在 API 层报 400。 */
    @Test
    void outOfRangeParametersReachServiceLayerUntouched() {
        stubPage(samplePage());

        webTestClient.get().uri("/config/api/usage-daily/page?size=999&offset=-4")
                .exchange()
                .expectStatus().isOk();

        verify(usageQueryService).getUsageDailyPage(999, -4);
    }

    /** 响应体形状：8 个顶层字段 + points 内 4 个字段。 */
    @Test
    void responseBodyExposesWindowMetadataAndPaddedPoints() {
        stubPage(samplePage());

        webTestClient.get().uri("/config/api/usage-daily/page?size=7&offset=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.startDate").isEqualTo("2026-07-24")
                .jsonPath("$.endDate").isEqualTo("2026-07-30")
                .jsonPath("$.size").isEqualTo(7)
                .jsonPath("$.offset").isEqualTo(1)
                .jsonPath("$.includesToday").isEqualTo(false)
                .jsonPath("$.hasNewer").isEqualTo(true)
                .jsonPath("$.hasOlder").isEqualTo(true)
                .jsonPath("$.points.length()").isEqualTo(7)
                .jsonPath("$.points[0].date").isEqualTo("2026-07-24")
                .jsonPath("$.points[0].callCount").isEqualTo(3)
                .jsonPath("$.points[0].inputTokens").isEqualTo(300)
                .jsonPath("$.points[0].outputTokens").isEqualTo(90)
                // 补零的日期同样是完整对象，字段为 0 而非缺失 —— 前端可无条件读取
                .jsonPath("$.points[1].date").isEqualTo("2026-07-25")
                .jsonPath("$.points[1].callCount").isEqualTo(0)
                .jsonPath("$.points[1].inputTokens").isEqualTo(0)
                .jsonPath("$.points[6].date").isEqualTo("2026-07-30")
                .jsonPath("$.points[6].inputTokens").isEqualTo(500);
    }

    /** points 是数组且长度等于 size，即便整个窗口无数据。 */
    @Test
    void pointsAlwaysSerializeAsArrayOfWindowLength() {
        stubPage(new UsageDailyPage("2026-07-25", "2026-07-31", 7, 0, true, false, true,
                List.of(
                        UsageDailyPoint.empty("2026-07-25"),
                        UsageDailyPoint.empty("2026-07-26"),
                        UsageDailyPoint.empty("2026-07-27"),
                        UsageDailyPoint.empty("2026-07-28"),
                        UsageDailyPoint.empty("2026-07-29"),
                        UsageDailyPoint.empty("2026-07-30"),
                        UsageDailyPoint.empty("2026-07-31"))));

        webTestClient.get().uri("/config/api/usage-daily/page")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.points").isArray()
                .jsonPath("$.points.length()").isEqualTo(7)
                .jsonPath("$.includesToday").isEqualTo(true);
    }
}

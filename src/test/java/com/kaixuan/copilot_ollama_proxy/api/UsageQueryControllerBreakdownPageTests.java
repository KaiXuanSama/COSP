package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.usage.UsageQueryService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.SseConnectionGate;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownPage;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证与锁定：滑动窗口分页端点的<strong>参数绑定与响应体形状</strong>。
 *
 * <p>用 {@code bindToController} 起独立 WebFlux 环境，只装这一个控制器 ——
 * 不启完整应用，因而绕开 JWT，测试关注点收窄到「查询参数怎么进、JSON 怎么出」。
 * 钳制与窗口计算属于用例层，在 {@code UsageQueryServiceBreakdownPageTests} 中覆盖，
 * 此处只断言控制器把参数<strong>原样</strong>交给服务层（钳制不能悄悄搬到 API 层，
 * 否则两处规则会各自漂移）。
 *
 * <p>响应体的字段名同时是前端契约：record 的组件名经 Jackson 直接成为 JSON 键，
 * 改名会静默破坏前端读取，故逐个字段断言。
 */
class UsageQueryControllerBreakdownPageTests {

    private UsageQueryService usageQueryService;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        usageQueryService = mock(UsageQueryService.class);
        webTestClient = WebTestClient
                .bindToController(new UsageQueryController(usageQueryService, new SseConnectionGate(20)))
                .build();
    }

    private void stubPage(UsageBreakdownPage page) {
        when(usageQueryService.getUsageBreakdownPage(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(Mono.just(page));
    }

    /** 不传参数时默认「含今天的最近 7 天」。 */
    @Test
    void defaultsToSevenDayWindowAtLatestPosition() {
        stubPage(new UsageBreakdownPage("2026-07-25", "2026-07-31", 7, 0, true, false, true, List.of()));

        webTestClient.get().uri("/config/api/usage-breakdown/page")
                .exchange()
                .expectStatus().isOk();

        verify(usageQueryService).getUsageBreakdownPage(7, 0);
    }

    /** 两个参数原样透传，钳制留在用例层。 */
    @Test
    void queryParametersArePassedThroughUnclamped() {
        stubPage(new UsageBreakdownPage("2026-07-17", "2026-07-23", 7, 8, false, true, false, List.of()));

        webTestClient.get().uri("/config/api/usage-breakdown/page?size=7&offset=8")
                .exchange()
                .expectStatus().isOk();

        verify(usageQueryService).getUsageBreakdownPage(7, 8);
    }

    /** 越界参数同样原样进入服务层 —— 由那里静默钳制，不在 API 层报 400。 */
    @Test
    void outOfRangeParametersReachServiceLayerUntouched() {
        stubPage(new UsageBreakdownPage("2026-07-17", "2026-07-31", 15, 0, true, false, false, List.of()));

        webTestClient.get().uri("/config/api/usage-breakdown/page?size=999&offset=-4")
                .exchange()
                .expectStatus().isOk();

        verify(usageQueryService).getUsageBreakdownPage(999, -4);
    }

    /**
     * 响应体形状即前端契约：8 个顶层字段 + rows 内 6 个字段。
     *
     * <p>逐字段断言的意义在于 record 组件名直接成为 JSON 键 ——
     * 重命名组件在 Java 侧毫无警告，却会让前端读到 undefined。
     */
    @Test
    void responseBodyExposesWindowMetadataAndRows() {
        stubPage(new UsageBreakdownPage(
                "2026-07-24", "2026-07-30", 7, 1, false, true, true,
                List.of(new UsageBreakdownRow("2026-07-24", "deepseek", "chat", 12L, 1200L, 340L))));

        webTestClient.get().uri("/config/api/usage-breakdown/page?size=7&offset=1")
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
                .jsonPath("$.rows.length()").isEqualTo(1)
                .jsonPath("$.rows[0].date").isEqualTo("2026-07-24")
                .jsonPath("$.rows[0].providerKey").isEqualTo("deepseek")
                .jsonPath("$.rows[0].modelName").isEqualTo("chat")
                .jsonPath("$.rows[0].callCount").isEqualTo(12)
                .jsonPath("$.rows[0].inputTokens").isEqualTo(1200)
                .jsonPath("$.rows[0].outputTokens").isEqualTo(340);
    }

    /** 窗口内无数据时 rows 是空数组而非 null —— 前端可无条件遍历。 */
    @Test
    void emptyWindowSerializesRowsAsEmptyArray() {
        stubPage(new UsageBreakdownPage("2026-07-17", "2026-07-23", 7, 8, false, true, false, List.of()));

        webTestClient.get().uri("/config/api/usage-breakdown/page?size=7&offset=8")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.rows").isArray()
                .jsonPath("$.rows.length()").isEqualTo(0);
    }

    /** 既有的「最近 N 天」端点不受影响，仍走 days 参数。 */
    @Test
    void legacyBreakdownEndpointStillUsesDaysParameter() {
        when(usageQueryService.getUsageBreakdown(eq(7))).thenReturn(Mono.just(List.of()));

        webTestClient.get().uri("/config/api/usage-breakdown")
                .exchange()
                .expectStatus().isOk();

        verify(usageQueryService).getUsageBreakdown(7);
    }
}

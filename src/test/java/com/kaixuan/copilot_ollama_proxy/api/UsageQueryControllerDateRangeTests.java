package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.usage.UsageQueryService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.SseConnectionGate;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageDateRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证与锁定：日期范围端点的路径与响应体形状。
 *
 * <p>{@code bindToController} 起独立 WebFlux 环境（绕开 JWT），关注点收窄到「JSON 怎么出」。
 * 可选范围的推导规则属于用例层，在 {@code UsageQueryServiceDateRangeTests} 中覆盖。
 *
 * <p>特别锁住空库时两个日期字段序列化为 {@code null} 而非被省略 —— 前端用它区分
 * 「没有数据」与「有数据但很少」，字段消失会让 {@code undefined} 与 {@code null}
 * 走到不同分支。
 */
class UsageQueryControllerDateRangeTests {

    private UsageQueryService usageQueryService;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        usageQueryService = mock(UsageQueryService.class);
        webTestClient = WebTestClient
                .bindToController(new UsageQueryController(usageQueryService, new SseConnectionGate(20)))
                .build();
    }

    /** 有数据时六个字段齐全。 */
    @Test
    void exposesBothRawBoundsAndSelectableRange() {
        when(usageQueryService.getUsageDateRange()).thenReturn(Mono.just(new UsageDateRange(
                "2026-05-01", "2026-07-30", "2026-07-31", true, "2026-07-17", "2026-07-31")));

        webTestClient.get().uri("/config/api/usage-date-range")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.earliestDate").isEqualTo("2026-05-01")
                .jsonPath("$.latestDate").isEqualTo("2026-07-30")
                .jsonPath("$.today").isEqualTo("2026-07-31")
                .jsonPath("$.hasData").isEqualTo(true)
                .jsonPath("$.earliestSelectable").isEqualTo("2026-07-17")
                .jsonPath("$.latestSelectable").isEqualTo("2026-07-31");
    }

    /**
     * 空库时两个日期字段为 {@code null}，可选范围仍是有效日期。
     *
     * <p>字段若被省略，前端的 {@code undefined} 与 {@code null} 会走到不同分支。
     */
    @Test
    void emptyDatabaseKeepsNullBoundsAndValidSelectableRange() {
        when(usageQueryService.getUsageDateRange()).thenReturn(Mono.just(new UsageDateRange(
                null, null, "2026-07-31", false, "2026-07-31", "2026-07-31")));

        webTestClient.get().uri("/config/api/usage-date-range")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.earliestDate").isEqualTo(null)
                .jsonPath("$.latestDate").isEqualTo(null)
                .jsonPath("$.hasData").isEqualTo(false)
                .jsonPath("$.earliestSelectable").isEqualTo("2026-07-31")
                .jsonPath("$.latestSelectable").isEqualTo("2026-07-31");
    }
}

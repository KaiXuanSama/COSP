package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.logging.CallLogFilter;
import com.kaixuan.copilot_ollama_proxy.application.logging.CallLogQueryService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.SseConnectionGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日志列表端点的检索参数解析。
 *
 * <p>用 {@code bindToController}：只验证「查询参数进、条件对象出 / 400」，服务层用 mock 替换。
 */
class CallLogControllerSearchTests {

    private CallLogQueryService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(CallLogQueryService.class);
        when(service.listLogs(any(), any(), anyInt()))
                .thenReturn(Mono.just(Map.of("items", List.of(), "hasMore", false, "pageSize", 50)));
        client = WebTestClient.bindToController(new CallLogController(service, new SseConnectionGate(1))).build();
    }

    /** 不带检索参数时传给服务层的是 NONE，行为与引入检索前一致。 */
    @Test
    void noSearchParamsPassesNone() {
        client.get().uri("/config/api/logs?pageSize=50").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.items").isArray();
        verify(service).listLogs(eq(CallLogFilter.NONE), eq(null), eq(50));
    }

    @Test
    void parsesAllParamsIncludingRepeatedStatus() {
        client.get().uri(uri -> uri.path("/config/api/logs")
                        .queryParam("cursor", 99)
                        .queryParam("pageSize", 50)
                        .queryParam("from", "2026-11-01T00:00:00")
                        .queryParam("to", "2026-11-01T23:59:59")
                        .queryParam("status", "2xx")
                        .queryParam("status", "429")
                        .queryParam("status", "-1")
                        .queryParam("provider", "stepfun")
                        .queryParam("model", "glm")
                        .queryParam("modelMatch", "contains")
                        .build())
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<CallLogFilter> captor = ArgumentCaptor.forClass(CallLogFilter.class);
        verify(service).listLogs(captor.capture(), eq(99L), eq(50));
        CallLogFilter filter = captor.getValue();
        assertThat(filter.fromText()).isEqualTo("2026-11-01T00:00:00");
        assertThat(filter.toText()).isEqualTo("2026-11-01T23:59:59");
        assertThat(filter.statusClasses()).containsExactly(CallLogFilter.StatusClass.SUCCESS);
        assertThat(filter.statusCodes()).containsExactly(429, -1);
        assertThat(filter.provider()).isEqualTo("stepfun");
        assertThat(filter.providerMatch()).isEqualTo(CallLogFilter.MatchMode.EXACT);
        assertThat(filter.model()).isEqualTo("glm");
        assertThat(filter.modelMatch()).isEqualTo(CallLogFilter.MatchMode.CONTAINS);
    }

    @Test
    void invalidParamsReturn400WithMessage() {
        for (String query : List.of("from=2026-11-01", "from=2026-11-02T00:00:00&to=2026-11-01T00:00:00",
                "status=3xx", "providerMatch=prefix&provider=x", "model=" + "m".repeat(201))) {
            client.get().uri("/config/api/logs?" + query).exchange()
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.ok").isEqualTo(false)
                    .jsonPath("$.message").isNotEmpty();
        }
        verify(service, never()).listLogs(any(), any(), anyInt());
    }
}

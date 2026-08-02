package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.UsageEventPublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageBreakdownRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段一验证与锁定：下钻用量明细用例层的天数钳制与结果透传。
 *
 * <p>覆盖：
 * <ul>
 *   <li>正常天数原样传入仓储；</li>
 *   <li>超出上下限时钳制到 [1, 90]，防止恶意大范围查询拖垮 SQLite；</li>
 *   <li>仓储结果原样返回，用例层不做二次加工（pivot 全在前端）。</li>
 * </ul>
 */
class UsageQueryServiceBreakdownTests {

    private ApiCallUsageRepository usageRepository;
    private UsageQueryService service;

    @BeforeEach
    void setUp() {
        usageRepository = mock(ApiCallUsageRepository.class);
        service = new UsageQueryService(
                mock(ApiUsageRepository.class), usageRepository, new UsageEventPublisher());
        when(usageRepository.aggregateBreakdown(anyInt())).thenReturn(List.of());
    }

    /** 捕获实际传给仓储的天数。 */
    private int capturedDays(int requestedDays) {
        service.getUsageBreakdown(requestedDays).block();
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(usageRepository).aggregateBreakdown(captor.capture());
        return captor.getValue();
    }

    @Test
    void normalDaysArePassedThrough() {
        assertThat(capturedDays(7)).isEqualTo(7);
    }

    @Test
    void zeroAndNegativeDaysAreClampedToMinimum() {
        assertThat(capturedDays(0)).isEqualTo(1);
    }

    @Test
    void negativeDaysAreClampedToMinimum() {
        assertThat(capturedDays(-30)).isEqualTo(1);
    }

    @Test
    void excessiveDaysAreClampedToMaximum() {
        assertThat(capturedDays(9999)).isEqualTo(90);
    }

    @Test
    void repositoryRowsAreReturnedUnmodified() {
        List<UsageBreakdownRow> rows = List.of(
                new UsageBreakdownRow("2026-07-26", "deepseek", "chat", 12L, 1200L, 340L),
                new UsageBreakdownRow("2026-07-26", "zhipu", "glm", 3L, 300L, 90L));
        when(usageRepository.aggregateBreakdown(anyInt())).thenReturn(rows);

        List<UsageBreakdownRow> result = service.getUsageBreakdown(7).block();

        // 用例层不做汇总或排序加工：三级 pivot 全在前端完成
        assertThat(result).containsExactlyElementsOf(rows);
    }
}

package com.kaixuan.copilot_ollama_proxy.application.usage;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台的调用统计查询用例。
 */
@Service
public class UsageQueryService {

    private final ApiUsageRepository apiUsageRepository;

    public UsageQueryService(ApiUsageRepository apiUsageRepository) {
        this.apiUsageRepository = apiUsageRepository;
    }

    public Mono<Map<String, Object>> getStats() {
        return Mono.fromCallable(() -> {
            int[] tokens = apiUsageRepository.sumTokensToday();
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("totalApiCalls", apiUsageRepository.countTotal());
            stats.put("todayApiCalls", tokens[0]);
            stats.put("todayInputTokens", tokens[0]);
            stats.put("todayOutputTokens", tokens[1]);
            return stats;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<Map<String, Object>>> getHeatmap(int requestedDays) {
        return Mono.fromCallable(() -> buildHeatmap(requestedDays))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private List<Map<String, Object>> buildHeatmap(int requestedDays) {
        int days = Math.max(7, Math.min(365, requestedDays));
        List<Map<String, Object>> data = apiUsageRepository.listRecentDays(days);
        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (Map<String, Object> row : data) {
            byDate.put((String) row.get("usageDate"), row);
        }

        List<Map<String, Object>> full = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 364; i >= 0; i--) {
            String date = today.minusDays(i).toString();
            Map<String, Object> row = byDate.get(date);
            if (row != null) {
                full.add(row);
                continue;
            }
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("usageDate", date);
            empty.put("callCount", 0);
            empty.put("inputTokens", 0);
            empty.put("outputTokens", 0);
            full.add(empty);
        }
        return full;
    }
}
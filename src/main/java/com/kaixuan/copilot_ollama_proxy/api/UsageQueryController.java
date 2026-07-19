package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.usage.UsageQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** 管理后台调用统计 API。 */
@RestController
public class UsageQueryController {

    private final UsageQueryService usageQueryService;

    public UsageQueryController(UsageQueryService usageQueryService) {
        this.usageQueryService = usageQueryService;
    }

    @GetMapping("/config/api/stats")
    public Mono<Map<String, Object>> apiStats() {
        return usageQueryService.getStats();
    }

    @GetMapping("/config/api/heatmap")
    public Mono<List<Map<String, Object>>> heatmapData(@RequestParam(defaultValue = "360") int days) {
        return usageQueryService.getHeatmap(days);
    }
}
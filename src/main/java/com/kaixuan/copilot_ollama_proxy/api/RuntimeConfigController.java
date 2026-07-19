package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.config.RuntimeConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** 管理后台运行时配置 API。 */
@RestController
public class RuntimeConfigController {

    private final RuntimeConfigService runtimeConfigService;

    public RuntimeConfigController(RuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    @GetMapping("/config/api/fake-version")
    public Mono<Map<String, Object>> getFakeVersion() {
        return runtimeConfigService.getFakeVersion().map(version -> Map.of("fakeVersion", version));
    }

    @PostMapping("/config/api/fake-version")
    public Mono<Map<String, Object>> saveFakeVersion(@RequestParam String fakeVersion) {
        return runtimeConfigService.saveFakeVersion(fakeVersion).thenReturn(Map.of("ok", true));
    }
}
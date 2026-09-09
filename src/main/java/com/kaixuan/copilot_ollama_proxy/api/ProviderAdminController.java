package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** 管理后台供应商配置 API。 */
@RestController
public class ProviderAdminController {

    private final ProviderAdminService providerAdminService;

    public ProviderAdminController(ProviderAdminService providerAdminService) {
        this.providerAdminService = providerAdminService;
    }

    @GetMapping("/config/api/providers")
    public Mono<Map<String, Object>> listProviders() {
        return providerAdminService.listProviders();
    }

    @PostMapping("/config/api/providers/{providerKey}/toggle")
    public Mono<Map<String, Object>> toggleProvider(@PathVariable String providerKey,
                                                     @RequestBody Map<String, Object> body) {
        return providerAdminService.toggleProvider(providerKey, Boolean.TRUE.equals(body.get("enabled")));
    }

    /**
     * 切换供应商的出站代理开关。
     *
     * <p>与 {@code /toggle}（启停供应商）分开：代理开关是正交维度，前端也是独立入口。
     * 请求体形如 {@code { "useProxy": true }}。
     */
    @PostMapping("/config/api/providers/{providerKey}/proxy")
    public Mono<Map<String, Object>> toggleProviderProxy(@PathVariable String providerKey,
                                                         @RequestBody Map<String, Object> body) {
        return providerAdminService.toggleProviderProxy(providerKey, Boolean.TRUE.equals(body.get("useProxy")));
    }

    @PostMapping("/config/api/providers/{providerKey}/config")
    public Mono<ResponseEntity<Map<String, Object>>> saveProviderConfig(@PathVariable String providerKey,
                                                                         ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> providerAdminService.saveProviderConfig(providerKey, form))
                .map(this::toResponse);
    }

    @Deprecated
    @GetMapping("/config/api/custom-providers")
    public Mono<List<Map<String, Object>>> listLegacyProviders() {
        return providerAdminService.listLegacyProviders();
    }

    @PostMapping({"/config/api/providers", "/config/api/custom-providers"})
    public Mono<ResponseEntity<Map<String, Object>>> addProvider(ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(providerAdminService::addProvider).map(this::toResponse);
    }

    @DeleteMapping({"/config/api/providers/{providerKey}", "/config/api/custom-providers/{providerKey}"})
    public Mono<Map<String, Object>> deleteProvider(@PathVariable String providerKey) {
        return providerAdminService.deleteProvider(providerKey);
    }

    @PutMapping({"/config/api/providers/{providerKey}", "/config/api/custom-providers/{providerKey}"})
    public Mono<ResponseEntity<Map<String, Object>>> updateProvider(@PathVariable String providerKey,
                                                                     ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> providerAdminService.updateProvider(providerKey, form))
                .map(this::toResponse);
    }

    private ResponseEntity<Map<String, Object>> toResponse(ProviderAdminService.Outcome outcome) {
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }
}
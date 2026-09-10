package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderAdminService;
import org.springframework.http.HttpStatus;
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

    @PostMapping("/config/api/providers/{providerKey}/config")
    public Mono<ResponseEntity<Map<String, Object>>> saveProviderConfig(@PathVariable String providerKey,
                                                                         ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> providerAdminService.saveProviderConfig(providerKey, form))
                .map(this::toResponse);
    }

    /**
     * 按 keyUuid 解密回传单条 API Key 的明文（供复制到剪贴板）。
     *
     * <p>与网关 Key 的 reveal 同一安全口径：列表接口只返回脱敏值，明文仅在管理员
     * 显式点击复制时按需解密回传。端点位于 {@code /config/**} 之下，受管理后台
     * JWT 保护；供应商或 keyUuid 不存在时返回 404。
     *
     * @return {@code { apiKey }}
     */
    @GetMapping("/config/api/providers/{providerKey}/keys/{keyUuid}/reveal")
    public Mono<ResponseEntity<Map<String, Object>>> revealProviderApiKey(@PathVariable String providerKey,
                                                                           @PathVariable String keyUuid) {
        return providerAdminService.revealProviderApiKey(providerKey, keyUuid)
                .map(key -> {
                    if (key.isEmpty()) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(Map.<String, Object>of("ok", false, "error", "API Key 不存在"));
                    }
                    return ResponseEntity.ok(Map.<String, Object>of("apiKey", key));
                });
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